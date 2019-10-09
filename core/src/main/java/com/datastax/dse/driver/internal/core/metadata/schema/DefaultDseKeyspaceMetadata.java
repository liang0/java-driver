/*
 * Copyright DataStax, Inc.
 *
 * This software can be used solely with DataStax Enterprise. Please consult the license at
 * http://www.datastax.com/terms/datastax-dse-driver-license-terms
 */
package com.datastax.dse.driver.internal.core.metadata.schema;

import com.datastax.dse.driver.api.core.metadata.schema.DseKeyspaceMetadata;
import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.metadata.schema.AggregateMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.FunctionMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.FunctionSignature;
import com.datastax.oss.driver.api.core.metadata.schema.TableMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.ViewMetadata;
import com.datastax.oss.driver.api.core.type.UserDefinedType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.Objects;
import net.jcip.annotations.Immutable;

@Immutable
public class DefaultDseKeyspaceMetadata implements DseKeyspaceMetadata {

  @NonNull private final CqlIdentifier name;
  private final boolean durableWrites;
  private final boolean virtual;
  @NonNull private final Map<String, String> replication;
  @NonNull private final Map<CqlIdentifier, UserDefinedType> types;
  @NonNull private final Map<CqlIdentifier, TableMetadata> tables;
  @NonNull private final Map<CqlIdentifier, ViewMetadata> views;
  @NonNull private final Map<FunctionSignature, FunctionMetadata> functions;
  @NonNull private final Map<FunctionSignature, AggregateMetadata> aggregates;

  public DefaultDseKeyspaceMetadata(
      @NonNull CqlIdentifier name,
      boolean durableWrites,
      boolean virtual,
      @NonNull Map<String, String> replication,
      @NonNull Map<CqlIdentifier, UserDefinedType> types,
      @NonNull Map<CqlIdentifier, TableMetadata> tables,
      @NonNull Map<CqlIdentifier, ViewMetadata> views,
      @NonNull Map<FunctionSignature, FunctionMetadata> functions,
      @NonNull Map<FunctionSignature, AggregateMetadata> aggregates) {
    this.name = name;
    this.durableWrites = durableWrites;
    this.virtual = virtual;
    this.replication = replication;
    this.types = types;
    this.tables = tables;
    this.views = views;
    this.functions = functions;
    this.aggregates = aggregates;
  }

  @NonNull
  @Override
  public CqlIdentifier getName() {
    return name;
  }

  @Override
  public boolean isDurableWrites() {
    return durableWrites;
  }

  @Override
  public boolean isVirtual() {
    return virtual;
  }

  @NonNull
  @Override
  public Map<String, String> getReplication() {
    return replication;
  }

  @NonNull
  @Override
  public Map<CqlIdentifier, UserDefinedType> getUserDefinedTypes() {
    return types;
  }

  @NonNull
  @Override
  public Map<CqlIdentifier, TableMetadata> getTables() {
    return tables;
  }

  @NonNull
  @Override
  public Map<CqlIdentifier, ViewMetadata> getViews() {
    return views;
  }

  @NonNull
  @Override
  public Map<FunctionSignature, FunctionMetadata> getFunctions() {
    return functions;
  }

  @NonNull
  @Override
  public Map<FunctionSignature, AggregateMetadata> getAggregates() {
    return aggregates;
  }

  @Override
  public boolean equals(Object other) {
    if (other == this) {
      return true;
    } else if (other instanceof DseKeyspaceMetadata) {
      DseKeyspaceMetadata that = (DseKeyspaceMetadata) other;
      return Objects.equals(this.name, that.getName())
          && this.durableWrites == that.isDurableWrites()
          && this.virtual == that.isVirtual()
          && Objects.equals(this.replication, that.getReplication())
          && Objects.equals(this.types, that.getUserDefinedTypes())
          && Objects.equals(this.tables, that.getTables())
          && Objects.equals(this.views, that.getViews())
          && Objects.equals(this.functions, that.getFunctions())
          && Objects.equals(this.aggregates, that.getAggregates());
    } else {
      return false;
    }
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        name, durableWrites, virtual, replication, types, tables, views, functions, aggregates);
  }
}