package com.wxdgaming.batis.parser;

import com.wxdgaming.batis.annotation.Column;
import com.wxdgaming.batis.annotation.GeneratedValue;
import com.wxdgaming.batis.annotation.GenerationType;
import com.wxdgaming.batis.annotation.Id;
import com.wxdgaming.batis.annotation.Index;
import com.wxdgaming.batis.annotation.Table;
import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.IndexInfo;
import com.wxdgaming.batis.model.TableInfo;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

            if (!columnAnn.type().isEmpty()) {
                columnInfo.setType(columnAnn.type());
            } else {
                columnInfo.setType(inferType(field.getType(), columnAnn.length()));
            }
        } else {
            columnInfo.setColumnName(camelToUnderscore(field.getName()));
            columnInfo.setType(inferType(field.getType(), 255));
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
                indexInfo.setName(name.isEmpty() ? "idx_" + colName : name);

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

    private static String inferType(Class<?> javaType, int length) {
        if (javaType == String.class) {
            if (length > 65535) return "TEXT";
            if (length > 16383) return "MEDIUMTEXT";
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
