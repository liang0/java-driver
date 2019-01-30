/*
 * Copyright DataStax, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.oss.driver.internal.mapper.processor.entity;

import com.datastax.oss.driver.api.core.type.reflect.GenericType;
import com.datastax.oss.driver.api.mapper.entity.EntityHelper;
import com.datastax.oss.driver.internal.mapper.MapperContext;
import com.datastax.oss.driver.internal.mapper.processor.GeneratedNames;
import com.datastax.oss.driver.internal.mapper.processor.PartialClassGenerator;
import com.datastax.oss.driver.internal.mapper.processor.ProcessorContext;
import com.datastax.oss.driver.internal.mapper.processor.SingleFileCodeGenerator;
import com.datastax.oss.driver.internal.mapper.processor.util.NameIndex;
import com.datastax.oss.driver.shaded.guava.common.collect.ImmutableList;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import java.beans.Introspector;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;

public class EntityHelperGenerator extends SingleFileCodeGenerator {

  private final TypeElement classElement;
  private final EntityDefinition entityDefinition;
  private final ClassName helperName;
  private final NameIndex nameIndex = new NameIndex();
  private final Map<TypeName, String> typeConstantNames = new HashMap<>();
  private final Map<TypeElement, String> childHelpers = new HashMap<>();

  public EntityHelperGenerator(TypeElement classElement, ProcessorContext context) {
    super(context);
    this.classElement = classElement;
    entityDefinition = context.getEntityFactory().getDefinition(classElement);
    helperName = GeneratedNames.entityHelper(classElement);
  }

  @Override
  protected String getFileName() {
    return helperName.packageName() + "." + helperName.simpleName();
  }

  /**
   * Requests the generation of a constant holding the {@link GenericType} for the given type.
   *
   * <p>If this is called multiple times, only a single constant will be created.
   *
   * @return the name of the constant.
   */
  String addGenericTypeConstant(TypeName type) {
    return typeConstantNames.computeIfAbsent(
        type, k -> nameIndex.uniqueField(GeneratedNames.GENERIC_TYPE_CONSTANT));
  }

  /**
   * Requests the generation of a field that holding an instance of another entity's helper class.
   *
   * <p>If this is called multiple times, only a single field will be created.
   *
   * @return the name of the field.
   */
  String addChildHelper(TypeElement childEntityElement) {
    return childHelpers.computeIfAbsent(
        childEntityElement,
        k -> {
          String baseName =
              Introspector.decapitalize(childEntityElement.getSimpleName().toString()) + "Helper";
          return nameIndex.uniqueField(baseName);
        });
  }

  @Override
  protected JavaFile.Builder getContents() {

    List<PartialClassGenerator> methodGenerators =
        ImmutableList.of(new EntityHelperInjectGenerator(entityDefinition, this, context));

    TypeSpec.Builder classContents =
        TypeSpec.classBuilder(helperName)
            .addJavadoc(JAVADOC_GENERATED_WARNING)
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(
                ParameterizedTypeName.get(
                    ClassName.get(EntityHelper.class), ClassName.get(classElement)))
            .addField(
                FieldSpec.builder(MapperContext.class, "context", Modifier.PRIVATE, Modifier.FINAL)
                    .build());

    MethodSpec.Builder constructorContents =
        MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(MapperContext.class, "context")
            .addStatement("this.context = context");

    for (PartialClassGenerator methodGenerator : methodGenerators) {
      methodGenerator.addConstructorInstructions(constructorContents);
      methodGenerator.addMembers(classContents);
    }

    for (Map.Entry<TypeName, String> entry : typeConstantNames.entrySet()) {
      TypeName typeParameter = entry.getKey();
      String name = entry.getValue();
      ParameterizedTypeName type =
          ParameterizedTypeName.get(ClassName.get(GenericType.class), typeParameter);
      classContents.addField(
          FieldSpec.builder(type, name, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
              .initializer("new $T(){}", type)
              .build());
    }

    for (Map.Entry<TypeElement, String> entry : childHelpers.entrySet()) {
      TypeElement childEntity = entry.getKey();
      String fieldName = entry.getValue();

      ClassName helperClassName = GeneratedNames.entityHelper(childEntity);
      classContents.addField(
          FieldSpec.builder(helperClassName, fieldName, Modifier.PRIVATE, Modifier.FINAL).build());

      constructorContents.addStatement("this.$L = new $T(context)", fieldName, helperClassName);
    }

    classContents.addMethod(constructorContents.build());

    return JavaFile.builder(helperName.packageName(), classContents.build());
  }
}
