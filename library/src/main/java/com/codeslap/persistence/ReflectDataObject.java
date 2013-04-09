/*
 * Copyright 2013 CodeSlap
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.codeslap.persistence;

import android.text.TextUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.*;

import static com.codeslap.persistence.StrUtil.concat;

/**
 * Data object that gets object information using reflection.
 *
 * @author cristian
 */
public class ReflectDataObject implements DataObject<Object> {

  private final Class<?> objectType;
  private final Map<String, Field> fields;
  private final boolean hasAutoincrement;
  private final String tableName;
  private final Collection<HasManySpec> hasManyList = new ArrayList<HasManySpec>();
  private final Collection<ManyToManySpec> manyToManyList = new ArrayList<ManyToManySpec>();
  private final Class<?> belongsTo;
  private final String primaryKeyName;

  public ReflectDataObject(Class<?> type) {
    this(type, new TreeSet<Class<?>>(SqliteAdapterImpl.CLASS_COMPARATOR));
  }

  ReflectDataObject(Class<?> type, Set<Class<?>> graph) {
    objectType = type;
    fields = new HashMap<String, Field>();

    PrimaryKey primaryKey = null;
    String primaryKeyName = null;
    for (Field field : objectType.getDeclaredFields()) {
      if (field.isAnnotationPresent(Ignore.class) ||
          Modifier.isStatic(field.getModifiers()) ||// ignore static fields
          Modifier.isFinal(field.getModifiers())) { // ignore final fields
        continue;
      }
      fields.put(field.getName(), field);
      field.setAccessible(true);

      PrimaryKey pk = primaryKey == null ? field.getAnnotation(PrimaryKey.class) : null;
      Class<?> fieldType = field.getType();
      if (pk != null) {
        if (pk.autoincrement() && fieldType != long.class && fieldType != Long.class &&
            fieldType != int.class && fieldType != Integer.class) {
          throw new RuntimeException(
              "Only long and int can be used with autoincrement = true: " + objectType
                  .getSimpleName());
        }
        primaryKey = pk;
        primaryKeyName = field.getName();
      }

      if (fieldType == List.class) {
        HasMany hasMany = field.getAnnotation(HasMany.class);
        if (hasMany != null) {
          ParameterizedType parameterizedType = (ParameterizedType) field.getGenericType();
          Class<?> collectionClass = (Class<?>) parameterizedType.getActualTypeArguments()[0];

          if (!collectionClass.isAnnotationPresent(Belongs.class)) {
            throw new IllegalStateException(
                "When defining a HasMany relation you must specify a Belongs annotation in the child class");
          }
          Belongs belongs = collectionClass.getAnnotation(Belongs.class);
          if (belongs.to() != objectType) {
            throw new IllegalStateException(
                "Belongs class points to " + belongs.to() + " but should point to " + objectType);
          }

          Belongs thisBelongsTo = objectType.getAnnotation(Belongs.class);
          if (thisBelongsTo != null && thisBelongsTo.to() == collectionClass) {
            throw new IllegalStateException(
                "Cyclic has-many relations not supported. Use many-to-many instead: " +
                    collectionClass.getSimpleName() + " belongs to " + objectType
                    .getSimpleName() + " and viceversa");
          }

          HasManySpec hasManySpec = new HasManySpec(objectType, field);
          hasManyList.add(hasManySpec);
        }

        ManyToMany manyToMany = field.getAnnotation(ManyToMany.class);
        if (manyToMany != null && !graph.contains(objectType)) {
          ParameterizedType parameterizedType = (ParameterizedType) field.getGenericType();
          Class<?> collectionClass = (Class<?>) parameterizedType.getActualTypeArguments()[0];

          boolean relationExists = false;
          ManyToMany manyToManyColl;
          for (Field collField : collectionClass.getDeclaredFields()) {
            manyToManyColl = collField.getAnnotation(ManyToMany.class);
            if (manyToManyColl != null && collField.getType() == List.class) {
              ParameterizedType parameterizedTypeColl = (ParameterizedType) collField
                  .getGenericType();
              Class<?> selfClass = (Class<?>) parameterizedTypeColl.getActualTypeArguments()[0];
              if (selfClass == objectType) {
                relationExists = true;
                break;
              }
            }
          }

          if (!relationExists) {
            throw new IllegalStateException(
                "When defining a ManyToMany relation both classes must use the ManyToMany annotation");
          }

          if (graph.add(objectType)) {
            ReflectDataObject collDataObject = new ReflectDataObject(collectionClass, graph);
            ManyToManySpec manyToManySpec = new ManyToManySpec(this, field.getName(), collDataObject);
            manyToManyList.add(manyToManySpec);
          }
        }
      }
    }

    if (primaryKey == null) {
      throw new IllegalArgumentException(
          "Primay keys are mandatory: " + objectType.getSimpleName());
    }
    this.primaryKeyName = primaryKeyName;

    Belongs annotation = objectType.getAnnotation(Belongs.class);
    belongsTo = annotation != null ? annotation.to() : null;

    hasAutoincrement = primaryKey.autoincrement();
    tableName = getTableName(objectType);
  }

  @Override public Object newInstance() {
    try {
      return objectType.getDeclaredConstructor().newInstance();
    } catch (Exception e) {
      error(e);
    }
    return null;
  }

  @Override public boolean hasAutoincrement() {
    return hasAutoincrement;
  }

  @Override public Collection<HasManySpec> hasMany() {
    return hasManyList;
  }

  @Override public Collection<ManyToManySpec> manyToMany() {
    return manyToManyList;
  }

  @Override public HasManySpec hasMany(Class<?> theClass) {
    for (HasManySpec hasManySpec : hasManyList) {
      if (hasManySpec.contained == theClass) {
        return hasManySpec;
      }
    }
    throw new IllegalArgumentException(
        "Cannot find has-many relation between " + objectType + "and" + theClass);
  }

  @Override public Class<?> belongsTo() {
    return belongsTo;
  }

  @Override public Class getObjectClass() {
    return objectType;
  }

  @Override public String getTableName() {
    return tableName;
  }

  @Override public String getPrimaryKeyFieldName() {
    return primaryKeyName;
  }

  @Override public boolean set(String fieldName, Object target, Object value) {
    if (!fields.containsKey(fieldName)) {
      throw new IllegalStateException("Cannot find field " + fieldName + " in " + objectType);
    }
    try {
      fields.get(fieldName).set(target, value);
      return true;
    } catch (IllegalAccessException e) {
      return false;
    }
  }

  @Override public Object get(String fieldName, Object target) {
    if (!fields.containsKey(fieldName)) {
      throw new IllegalStateException("Cannot find field " + fieldName + " in " + objectType);
    }
    try {
      return fields.get(fieldName).get(target);
    } catch (IllegalAccessException e) {
      return null;
    }
  }

  @Override public boolean hasData(String fieldName, Object bean) {
    Object value = get(fieldName, bean);
    Class<?> type = fields.get(fieldName).getType();
    if (type == long.class || type == Long.class) {
      return value != null && ((Long) value) != 0L;
    }
    if (type == int.class || type == Integer.class) {
      return value != null && ((Integer) value) != 0;
    }
    if (type == float.class || type == Float.class) {
      return value != null && ((Float) value) != 0.0;
    }
    if (type == double.class || type == Double.class) {
      return value != null && ((Double) value) != 0.0;
    }
    if (type == boolean.class || type == Boolean.class) {
      if (value instanceof Boolean) {
        return (Boolean) value;
      }
      if (value instanceof Integer) {
        return ((Integer) value) != 0;
      }
      return false;
    }
    if (type == byte[].class || type == Byte[].class) {
      return value != null && ((byte[]) value).length > 0;
    }
    return value != null;
  }

  @Override public String getCreateTableSentence() {
    CreateTableHelper createTable = CreateTableHelper.init(tableName);
    for (Field field : fields.values()) {
      String columnName = ReflectHelper.getColumnName(field);
      CreateTableHelper.Type type = getTypeFrom(field);
      if (ReflectHelper.isPrimaryKey(field)) {
        String column = ReflectHelper.getIdColumn(field);
        createTable.addPk(column, type, hasAutoincrement);
      } else if (field.getType() != List.class) {
        boolean notNull = false;
        Column columnAnnotation = field.getAnnotation(Column.class);
        if (columnAnnotation != null) {
          notNull = columnAnnotation.notNull();
        }
        createTable.add(columnName, type, notNull);
      }
    }

    // check whether this class belongs to a has-many relation,
    // in which case we need to create an additional field
    Class<?> containerClass = belongsTo();
    if (containerClass != null) {
      DataObject<?> containerDataObject = DataObjectFactory.getDataObject(containerClass);
      for (HasManySpec hasManySpec : containerDataObject.hasMany()) {
        if (hasManySpec.contained != objectType) {
          continue;
        }
        // add a new field to the table creation statement to create the relation
        // TODO is it really necessary to mark this field as "not null"?
        createTable
            .add(hasManySpec.getThroughColumnName(), getTypeFrom(hasManySpec.throughField), false);
        break;
      }
    }

    return createTable.build();
  }

  private static CreateTableHelper.Type getTypeFrom(Field field) {
    Class<?> type = field.getType();
    if (type == int.class || type == Integer.class || type == long.class || type == Long.class ||
        type == boolean.class || type == Boolean.class) {
      return CreateTableHelper.Type.INTEGER;
    } else if (type == float.class || type == Float.class || type == double.class ||
        type == Double.class) {
      return CreateTableHelper.Type.REAL;
    } else if (type == byte[].class || type == Byte[].class) {
      return CreateTableHelper.Type.BLOB;
    }
    return CreateTableHelper.Type.TEXT;
  }

  private void error(Exception e) {
    Throwable cause = e.getCause();
    throw cause instanceof RuntimeException ? (RuntimeException) cause : new RuntimeException(
        cause);
  }

  private static String getTableName(Class<?> theClass) {
    Table table = theClass.getAnnotation(Table.class);
    String tableName;
    if (table != null) {
      tableName = table.value();
      if (TextUtils.isEmpty(tableName)) {
        String msg = concat("You cannot leave a table name empty: class ",
            theClass.getSimpleName());
        throw new IllegalArgumentException(msg);
      }
      if (tableName.contains(" ")) {
        String msg = concat("Table name cannot have spaces: '", tableName, "'; found in class ",
            theClass.getSimpleName());
        throw new IllegalArgumentException(msg);
      }
    } else {
      String name = theClass.getSimpleName();
      if (name.endsWith("y")) {
        name = name.substring(0, name.length() - 1) + "ies";
      } else if (!name.endsWith("s")) {
        name += "s";
      }
      tableName = SQLHelper.normalize(name);
    }
    return tableName;
  }
}
