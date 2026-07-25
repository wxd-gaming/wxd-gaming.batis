package com.wxdgaming.batis.parser;

import com.wxdgaming.batis.annotation.Column;
import com.wxdgaming.batis.annotation.GeneratedValue;
import com.wxdgaming.batis.annotation.GenerationType;
import com.wxdgaming.batis.annotation.Id;
import com.wxdgaming.batis.annotation.Index;
import com.wxdgaming.batis.annotation.Table;
import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.IndexInfo;
import com.wxdgaming.batis.model.StorageMode;
import com.wxdgaming.batis.model.TableInfo;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 实体注解解析器
 * <p>
 * 解析 {@link Table}、{@link Id}、{@link Column}、{@link Index}、{@link GeneratedValue} 等注解，
 * 生成运行时元数据 {@link TableInfo}。
 * <p>
 * 线程安全。解析结果会被缓存，同一个 Class 只会解析一次。
 *
 * @author wxd-gaming
 */
public class EntityParser {

    private static final Map<Class<?>, TableInfo> CACHE = new ConcurrentHashMap<>();

    private EntityParser() {
    }

    /**
     * 解析实体类，返回表元数据（缓存结果）
     */
    public static TableInfo parse(Class<?> entityClass) {
        return CACHE.computeIfAbsent(entityClass, EntityParser::doParse);
    }

    /** 清除指定类的缓存 */
    public static void clearCache(Class<?> entityClass) {
        CACHE.remove(entityClass);
    }

    /** 清除全部缓存 */
    public static void clearAll() {
        CACHE.clear();
    }

    // ============== 内部解析逻辑 ==============

    private static TableInfo doParse(Class<?> entityClass) {
        TableInfo tableInfo = new TableInfo();
        tableInfo.setEntityClass(entityClass);

        resolveTableAnnotation(entityClass, tableInfo);
        LinkedHashMap<String, ColumnInfo> columns = resolveColumns(entityClass);
        tableInfo.setColumns(columns);
        List<IndexInfo> indexes = resolveIndexes(entityClass, columns, tableInfo);
        tableInfo.setIndexes(indexes);

        return tableInfo;
    }

    /** 解析 {@link Table} 或 {@link com.wxdgaming.batis.annotation.Entity} 注解 */
    private static void resolveTableAnnotation(Class<?> entityClass, TableInfo tableInfo) {
        Table table = entityClass.getAnnotation(Table.class);
        if (table != null) {
            String tableName = table.name();
            if (tableName.isEmpty()) {
                tableName = camelToUnderscore(entityClass.getSimpleName());
            }
            tableInfo.setTableName(tableName);
            tableInfo.setComment(table.comment());
            return;
        }

        com.wxdgaming.batis.annotation.Entity entity =
                entityClass.getAnnotation(com.wxdgaming.batis.annotation.Entity.class);
        if (entity != null) {
            String tableName = entity.name();
            if (tableName.isEmpty()) {
                tableName = camelToUnderscore(entityClass.getSimpleName());
            }
            tableInfo.setTableName(tableName);
            tableInfo.setComment(entity.comment());
            return;
        }

        tableInfo.setTableName(camelToUnderscore(entityClass.getSimpleName()));
    }

    /** 解析所有字段上的 {@link Column}、{@link Id}、{@link GeneratedValue} 注解 */
    private static LinkedHashMap<String, ColumnInfo> resolveColumns(Class<?> entityClass) {
        LinkedHashMap<String, ColumnInfo> columnMap = new LinkedHashMap<>();
        java.util.Set<String> fieldNames = new java.util.HashSet<>();

        Class<?> currentClass = entityClass;
        while (currentClass != null && currentClass != Object.class) {
            for (Field field : currentClass.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                if (!fieldNames.add(field.getName())) continue;
                ColumnInfo col = buildColumnInfo(field);
                columnMap.put(col.getColumnName(), col);
            }
            currentClass = currentClass.getSuperclass();
        }

        return columnMap;
    }

    private static ColumnInfo buildColumnInfo(Field field) {
        ColumnInfo columnInfo = new ColumnInfo();
        columnInfo.setFieldName(field.getName());
        columnInfo.setJavaType(field.getType());

        Column columnAnn = field.getAnnotation(Column.class);
        if (columnAnn != null) {
            String colName = columnAnn.name();
            columnInfo.setColumnName(colName.isEmpty() ? camelToUnderscore(field.getName()) : colName);
            columnInfo.setComment(columnAnn.comment());
            columnInfo.setLength(columnAnn.length());
            columnInfo.setScale(columnAnn.scale());
            columnInfo.setNullable(columnAnn.nullable());
            columnInfo.setUnique(columnAnn.unique());
            columnInfo.setDefaultValue(columnAnn.defaultValue());

            // 1. 确定存储模式
            StorageMode storageMode = resolveStorageMode(columnAnn.storageMode(), field.getType());
            columnInfo.setStorageMode(storageMode);

            // 2. 推断 DB 列类型
            if (!columnAnn.type().isEmpty()) {
                columnInfo.setType(columnAnn.type());
            } else {
                columnInfo.setType(inferType(field.getType(), columnAnn.length(), storageMode));
            }
        } else {
            columnInfo.setColumnName(camelToUnderscore(field.getName()));
            StorageMode storageMode = resolveStorageMode(StorageMode.AUTO, field.getType());
            columnInfo.setStorageMode(storageMode);
            columnInfo.setType(inferType(field.getType(), 255, storageMode));
            columnInfo.setLength(255);
            columnInfo.setNullable(true);
        }

        if (field.getAnnotation(Id.class) != null) {
            columnInfo.setPrimaryKey(true);
            columnInfo.setNullable(false);
        }

        GeneratedValue gvAnn = field.getAnnotation(GeneratedValue.class);
        if (gvAnn != null) {
            columnInfo.setGenerationType(gvAnn.strategy());
        }

        return columnInfo;
    }

    /**
     * 解析存储模式
     * <p>
     * 如果注解显式指定了 {@link StorageMode}，直接使用；
     * 否则按 Java 类型自动推断：
     * <ul>
     *   <li>{@link Collection}/{@link Map}/数组/非 JDK 内置复杂对象 → JSON</li>
     *   <li>其他基础类型 → AUTO</li>
     * </ul>
     */
    private static StorageMode resolveStorageMode(StorageMode annotationMode, Class<?> javaType) {
        if (annotationMode != StorageMode.AUTO) {
            return annotationMode;
        }
        // byte[] 默认直接映射为 BLOB，不走 JSON 或 Protostuff 序列化
        if (javaType == byte[].class) {
            return StorageMode.AUTO;
        }
        // 复杂类型自动升级为 JSON
        if (javaType.isArray()
                || Collection.class.isAssignableFrom(javaType)
                || Map.class.isAssignableFrom(javaType)) {
            return StorageMode.JSON;
        }
        // 非 JDK 内置的自定义 POJO -> JSON（排除基础类型和日期等）
        if (!isBasicType(javaType)) {
            return StorageMode.JSON;
        }
        return StorageMode.AUTO;
    }

    /** 判断是否为 JDK 内置基础类型 / 日期等 */
    private static boolean isBasicType(Class<?> javaType) {
        return javaType.isPrimitive()
                || javaType == String.class
                || javaType == Boolean.class
                || javaType == Byte.class
                || javaType == Short.class
                || javaType == Integer.class
                || javaType == Long.class
                || javaType == Float.class
                || javaType == Double.class
                || javaType == BigDecimal.class
                || javaType == Date.class
                || javaType == java.time.LocalDate.class
                || javaType == java.time.LocalTime.class
                || javaType == java.time.LocalDateTime.class
                || javaType == byte[].class;
    }

    /** 解析索引：字段级 + 表级复合索引 + @Column(unique=true) 自动生成 */
    private static List<IndexInfo> resolveIndexes(Class<?> entityClass,
                                                   LinkedHashMap<String, ColumnInfo> columns,
                                                   TableInfo tableInfo) {
        List<IndexInfo> indexes = new ArrayList<>();

        // 1. 字段级索引
        Class<?> currentClass = entityClass;
        while (currentClass != null && currentClass != Object.class) {
            for (Field field : currentClass.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                Index indexAnn = field.getAnnotation(Index.class);
                if (indexAnn == null) continue;

                ColumnInfo colInfo = columns.values().stream()
                        .filter(c -> c.getFieldName().equals(field.getName()))
                        .findFirst().orElse(null);
                if (colInfo == null) continue;

                IndexInfo indexInfo = new IndexInfo();
                String colName = colInfo.getColumnName();
                String name = indexAnn.name();
                // 无显式 name 时：唯一索引用 uk_ 前缀，非唯一用 idx_ 前缀，与 @Column(unique=true) 自动生成保持一致
                indexInfo.setName(name.isEmpty()
                        ? (indexAnn.unique() ? "uk_" + colName : "idx_" + colName)
                        : name);

                String columnList = indexAnn.columnList();
                if (!columnList.isEmpty()) {
                    indexInfo.setColumns(
                            Arrays.stream(columnList.split(","))
                                    .map(String::trim).collect(Collectors.toList())
                    );
                } else {
                    indexInfo.setColumns(List.of(colName));
                }
                indexInfo.setUnique(indexAnn.unique());
                indexes.add(indexInfo);
            }
            currentClass = currentClass.getSuperclass();
        }

        // 2. 表级复合索引
        Table tableAnn = entityClass.getAnnotation(Table.class);
        if (tableAnn != null) {
            for (Index indexAnn : tableAnn.indexes()) {
                IndexInfo indexInfo = new IndexInfo();
                String name = indexAnn.name();
                if (name.isEmpty()) {
                    name = "idx_" + indexAnn.columnList().replace(",", "_").replace(" ", "");
                }
                indexInfo.setName(name);
                indexInfo.setColumns(
                        Arrays.stream(indexAnn.columnList().split(","))
                                .map(String::trim).collect(Collectors.toList())
                );
                indexInfo.setUnique(indexAnn.unique());
                indexes.add(indexInfo);
            }
        }

        // 3. @Column(unique=true) → 自动生成唯一索引
        for (ColumnInfo col : columns.values()) {
            if (col.isUnique()) {
                boolean alreadyExists = indexes.stream().anyMatch(idx ->
                        idx.getColumns().size() == 1
                                && idx.getColumns().get(0).equals(col.getColumnName())
                );
                if (!alreadyExists) {
                    IndexInfo indexInfo = new IndexInfo();
                    indexInfo.setName("uk_" + col.getColumnName());
                    indexInfo.setColumns(List.of(col.getColumnName()));
                    indexInfo.setUnique(true);
                    indexes.add(indexInfo);
                }
            }
        }

        return indexes.stream()
                .distinct()
                .sorted(Comparator.comparing(IndexInfo::getName))
                .collect(Collectors.toList());
    }

    // ============== 类型推断 ==============

    /**
     * 根据 Java 类型和存储模式推断数据库列类型
     *
     * @param javaType    Java 字段类型
     * @param length      列长度（主要用于 VARCHAR）
     * @param storageMode 存储模式
     */
    private static String inferType(Class<?> javaType, int length, StorageMode storageMode) {
        switch (storageMode) {
            case JSON:
                return "LONGTEXT";
            case BOOL:
                return "TINYINT(1)";
            case BYTE:
                return "TINYINT";
            case SHORT:
                return "SMALLINT";
            case INT:
                return "INT";
            case LONG:
                return "BIGINT";
            case FLOAT:
                return "FLOAT";
            case DOUBLE:
                return "DOUBLE";
            case DECIMAL:
                return "DECIMAL(20,6)";
            case VARCHAR:
                return "VARCHAR(" + Math.max(length, 1) + ")";
            case TEXT:
                return "TEXT";
            case MEDIUMTEXT:
                return "MEDIUMTEXT";
            case LONGTEXT:
                return "LONGTEXT";
            case BLOB:
                return "BLOB";
            case MEDIUMBLOB:
                return "MEDIUMBLOB";
            case LONGBLOB:
                return "LONGBLOB";
            case DATE:
                return "DATE";
            case DATETIME:
                return "DATETIME";
            case TIME:
                return "TIME";
            // AUTO 模式：按 Java 类型推断
            case AUTO:
            default:
                // fall through to Java type inference
        }

        // AUTO 模式：原有推断逻辑
        if (javaType == String.class) {
            if (length > 16777215) return "LONGTEXT";
            if (length > 65535) return "MEDIUMTEXT";
            if (length > 16383) return "TEXT";
            return "VARCHAR(" + length + ")";
        }
        if (javaType == boolean.class || javaType == Boolean.class) return "TINYINT(1)";
        if (javaType == byte.class || javaType == Byte.class) return "TINYINT";
        if (javaType == short.class || javaType == Short.class) return "SMALLINT";
        if (javaType == int.class || javaType == Integer.class) return "INT";
        if (javaType == long.class || javaType == Long.class) return "BIGINT";
        if (javaType == float.class || javaType == Float.class) return "FLOAT";
        if (javaType == double.class || javaType == Double.class) return "DOUBLE";
        if (javaType == BigDecimal.class) return "DECIMAL(20,6)";
        if (javaType == Date.class || javaType == LocalDateTime.class) return "DATETIME";
        if (javaType == LocalDate.class) return "DATE";
        if (javaType == LocalTime.class) return "TIME";
        if (javaType == byte[].class) return "BLOB";
        return "VARCHAR(" + length + ")";
    }

    /**
     * @deprecated 请使用 {@link #inferType(Class, int, StorageMode)}
     */
    @Deprecated
    private static String inferType(Class<?> javaType, int length) {
        return inferType(javaType, length, StorageMode.AUTO);
    }

    // ============== 工具方法 ==============

    /** 驼峰转下划线命名 */
    public static String camelToUnderscore(String str) {
        if (str == null || str.isEmpty()) return str;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < str.length(); i++) {
            char ch = str.charAt(i);
            if (Character.isUpperCase(ch)) {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '_') {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(ch));
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }
}
