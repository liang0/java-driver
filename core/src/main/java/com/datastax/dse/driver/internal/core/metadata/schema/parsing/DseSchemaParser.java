/*
 * Copyright DataStax, Inc.
 *
 * This software can be used solely with DataStax Enterprise. Please consult the license at
 * http://www.datastax.com/terms/datastax-dse-driver-license-terms
 */
package com.datastax.dse.driver.internal.core.metadata.schema.parsing;

import com.datastax.dse.driver.api.core.metadata.schema.DseAggregateMetadata;
import com.datastax.dse.driver.api.core.metadata.schema.DseFunctionMetadata;
import com.datastax.dse.driver.api.core.metadata.schema.DseKeyspaceMetadata;
import com.datastax.dse.driver.api.core.metadata.schema.DseTableMetadata;
import com.datastax.dse.driver.api.core.metadata.schema.DseViewMetadata;
import com.datastax.dse.driver.internal.core.metadata.schema.DefaultDseKeyspaceMetadata;
import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.metadata.schema.AggregateMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.FunctionMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.FunctionSignature;
import com.datastax.oss.driver.api.core.metadata.schema.KeyspaceMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.TableMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.ViewMetadata;
import com.datastax.oss.driver.api.core.type.UserDefinedType;
import com.datastax.oss.driver.internal.core.adminrequest.AdminRow;
import com.datastax.oss.driver.internal.core.context.InternalDriverContext;
import com.datastax.oss.driver.internal.core.metadata.schema.parsing.CassandraSchemaParser;
import com.datastax.oss.driver.internal.core.metadata.schema.parsing.SchemaParser;
import com.datastax.oss.driver.internal.core.metadata.schema.parsing.SimpleJsonParser;
import com.datastax.oss.driver.internal.core.metadata.schema.parsing.UserDefinedTypeParser;
import com.datastax.oss.driver.internal.core.metadata.schema.queries.SchemaRows;
import com.datastax.oss.driver.internal.core.metadata.schema.refresh.SchemaRefresh;
import com.datastax.oss.driver.internal.core.util.NanoTime;
import com.datastax.oss.driver.shaded.guava.common.base.MoreObjects;
import com.datastax.oss.driver.shaded.guava.common.collect.ImmutableMap;
import java.util.Collections;
import java.util.Map;
import net.jcip.annotations.ThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default parser implementation for Cassandra.
 *
 * <p>For modularity, the code for each element row is split into separate classes (schema stuff is
 * not on the hot path, so creating a few extra objects doesn't matter).
 */
@ThreadSafe
public class DseSchemaParser implements SchemaParser {

  private static final Logger LOG = LoggerFactory.getLogger(CassandraSchemaParser.class);

  private final SchemaRows rows;
  private final UserDefinedTypeParser userDefinedTypeParser;
  private final DseTableParser tableParser;
  private final DseViewParser viewParser;
  private final DseFunctionParser functionParser;
  private final DseAggregateParser aggregateParser;
  private final String logPrefix;
  private final long startTimeNs = System.nanoTime();

  public DseSchemaParser(SchemaRows rows, InternalDriverContext context) {
    this.rows = rows;
    this.logPrefix = context.getSessionName();

    this.userDefinedTypeParser = new UserDefinedTypeParser(rows.dataTypeParser(), context);
    this.tableParser = new DseTableParser(rows, context);
    this.viewParser = new DseViewParser(rows, context);
    this.functionParser = new DseFunctionParser(rows.dataTypeParser(), context);
    this.aggregateParser = new DseAggregateParser(rows.dataTypeParser(), context);
  }

  @Override
  public SchemaRefresh parse() {
    ImmutableMap.Builder<CqlIdentifier, KeyspaceMetadata> keyspacesBuilder = ImmutableMap.builder();
    for (AdminRow row : rows.keyspaces()) {
      DseKeyspaceMetadata keyspace = parseKeyspace(row);
      keyspacesBuilder.put(keyspace.getName(), keyspace);
    }
    for (AdminRow row : rows.virtualKeyspaces()) {
      DseKeyspaceMetadata keyspace = parseVirtualKeyspace(row);
      keyspacesBuilder.put(keyspace.getName(), keyspace);
    }
    SchemaRefresh refresh = new SchemaRefresh(keyspacesBuilder.build());
    LOG.debug("[{}] Schema parsing took {}", logPrefix, NanoTime.formatTimeSince(startTimeNs));
    return refresh;
  }

  private DseKeyspaceMetadata parseKeyspace(AdminRow keyspaceRow) {

    // Cassandra <= 2.2
    // CREATE TABLE system.schema_keyspaces (
    //     keyspace_name text PRIMARY KEY,
    //     durable_writes boolean,
    //     strategy_class text,
    //     strategy_options text
    // )
    //
    // Cassandra >= 3.0:
    // CREATE TABLE system_schema.keyspaces (
    //     keyspace_name text PRIMARY KEY,
    //     durable_writes boolean,
    //     replication frozen<map<text, text>>
    // )
    CqlIdentifier keyspaceId = CqlIdentifier.fromInternal(keyspaceRow.getString("keyspace_name"));
    boolean durableWrites =
        MoreObjects.firstNonNull(keyspaceRow.getBoolean("durable_writes"), false);

    Map<String, String> replicationOptions;
    if (keyspaceRow.contains("strategy_class")) {
      String strategyClass = keyspaceRow.getString("strategy_class");
      Map<String, String> strategyOptions =
          SimpleJsonParser.parseStringMap(keyspaceRow.getString("strategy_options"));
      replicationOptions =
          ImmutableMap.<String, String>builder()
              .putAll(strategyOptions)
              .put("class", strategyClass)
              .build();
    } else {
      replicationOptions = keyspaceRow.getMapOfStringToString("replication");
    }

    Map<CqlIdentifier, UserDefinedType> types = parseTypes(keyspaceId);

    return new DefaultDseKeyspaceMetadata(
        keyspaceId,
        durableWrites,
        false,
        replicationOptions,
        types,
        parseTables(keyspaceId, types),
        parseViews(keyspaceId, types),
        parseFunctions(keyspaceId, types),
        parseAggregates(keyspaceId, types));
  }

  private Map<CqlIdentifier, UserDefinedType> parseTypes(CqlIdentifier keyspaceId) {
    return userDefinedTypeParser.parse(rows.types().get(keyspaceId), keyspaceId);
  }

  private Map<CqlIdentifier, TableMetadata> parseTables(
      CqlIdentifier keyspaceId, Map<CqlIdentifier, UserDefinedType> types) {
    ImmutableMap.Builder<CqlIdentifier, TableMetadata> tablesBuilder = ImmutableMap.builder();
    for (AdminRow tableRow : rows.tables().get(keyspaceId)) {
      DseTableMetadata table = tableParser.parseTable(tableRow, keyspaceId, types);
      if (table != null) {
        tablesBuilder.put(table.getName(), table);
      }
    }
    return tablesBuilder.build();
  }

  private Map<CqlIdentifier, ViewMetadata> parseViews(
      CqlIdentifier keyspaceId, Map<CqlIdentifier, UserDefinedType> types) {
    ImmutableMap.Builder<CqlIdentifier, ViewMetadata> viewsBuilder = ImmutableMap.builder();
    for (AdminRow viewRow : rows.views().get(keyspaceId)) {
      DseViewMetadata view = viewParser.parseView(viewRow, keyspaceId, types);
      if (view != null) {
        viewsBuilder.put(view.getName(), view);
      }
    }
    return viewsBuilder.build();
  }

  private Map<FunctionSignature, FunctionMetadata> parseFunctions(
      CqlIdentifier keyspaceId, Map<CqlIdentifier, UserDefinedType> types) {
    ImmutableMap.Builder<FunctionSignature, FunctionMetadata> functionsBuilder =
        ImmutableMap.builder();
    for (AdminRow functionRow : rows.functions().get(keyspaceId)) {
      DseFunctionMetadata function = functionParser.parseFunction(functionRow, keyspaceId, types);
      if (function != null) {
        functionsBuilder.put(function.getSignature(), function);
      }
    }
    return functionsBuilder.build();
  }

  private Map<FunctionSignature, AggregateMetadata> parseAggregates(
      CqlIdentifier keyspaceId, Map<CqlIdentifier, UserDefinedType> types) {
    ImmutableMap.Builder<FunctionSignature, AggregateMetadata> aggregatesBuilder =
        ImmutableMap.builder();
    for (AdminRow aggregateRow : rows.aggregates().get(keyspaceId)) {
      DseAggregateMetadata aggregate =
          aggregateParser.parseAggregate(aggregateRow, keyspaceId, types);
      if (aggregate != null) {
        aggregatesBuilder.put(aggregate.getSignature(), aggregate);
      }
    }
    return aggregatesBuilder.build();
  }

  private DseKeyspaceMetadata parseVirtualKeyspace(AdminRow keyspaceRow) {

    CqlIdentifier keyspaceId = CqlIdentifier.fromInternal(keyspaceRow.getString("keyspace_name"));
    boolean durableWrites =
        MoreObjects.firstNonNull(keyspaceRow.getBoolean("durable_writes"), false);

    return new DefaultDseKeyspaceMetadata(
        keyspaceId,
        durableWrites,
        true,
        Collections.emptyMap(),
        Collections.emptyMap(),
        parseVirtualTables(keyspaceId),
        Collections.emptyMap(),
        Collections.emptyMap(),
        Collections.emptyMap());
  }

  private Map<CqlIdentifier, TableMetadata> parseVirtualTables(CqlIdentifier keyspaceId) {
    ImmutableMap.Builder<CqlIdentifier, TableMetadata> tablesBuilder = ImmutableMap.builder();
    for (AdminRow tableRow : rows.virtualTables().get(keyspaceId)) {
      DseTableMetadata table = tableParser.parseVirtualTable(tableRow, keyspaceId);
      if (table != null) {
        tablesBuilder.put(table.getName(), table);
      }
    }
    return tablesBuilder.build();
  }
}