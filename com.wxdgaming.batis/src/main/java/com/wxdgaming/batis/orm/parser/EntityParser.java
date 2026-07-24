package com.wxdgaming.batis.orm.parser;

import com.wxdgaming.batis.orm.annotation.Column;
import com.wxdgaming.batis.orm.annotation.GeneratedValue;
import com.wxdgaming.batis.orm.annotation.GenerationType;
import com.wxdgaming.batis.orm.annotation.Id;
import com.wxdgaming.batis.orm.annotation.Index;
import com.wxdgaming.batis.orm.annotation.Table;
import com.wxdgaming.batis.orm.model.ColumnInfo;
import com.wxdgaming.batis.orm.model.IndexInfo;
import com.wxdgaming.batis.orm.model.TableInfo;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
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

    /**
     * 清除指定类的缓存
     */
    public static void clearCache(Class<?> entityClass) {
        CACHE.remove(entityClass);
    }

    /**
     * 清除全部缓存
     */
    public static void clearAll() {
        CACHE.clear();
    }

    // ============== 内部解析逻辑 ==============

    private static TableInfo doParse(Class<?> entityClass) {
        TableInfo tableInfo = new TableInfo();
        tableInfo.setEntityClass(entityClass);

        // 1. 解析表级注解
        resolveTableAnnotation(entityClass, tableInfo);

        // 2. 解析字段注解
        List<ColumnInfo> columns = resolveColumns(entityClass);
        tableInfo.setColumns(columns);

        // 3. 解析索引（字段级 + 类级复合索引）
        List<IndexInfo> indexes = resolveIndexes(entityClass, columns, tableInfo);
        tableInfo.setIndexes(indexes);

        return tableInfo;
    }

    /**
     * 解析 {@link Table} 或 {@link com.wxdgaming.batis.orm.annotation.Entity} 注解
     */
    private static void resolveTableAnnotation(Class<?> entityClass, TableInfo tableInfo) {
        // 优先取 @Table
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

        // 兼容 @Entity
        com.wxdgaming.batis.orm.annotation.Entity entity =
                entityClass.getAnnotation(com.wxdgaming.batis.orm.annotation.Entity.class);
        if (entity != null) {
            String tableName = entity.name();
            if (tableName.isEmpty()) {
                tableName = camelToUnderscore(entityClass.getSimpleName());
            }
            tableInfo.setTableName(tableName);
            tableInfo.setComment(entity.comment());
            return;
        }

        // 无注解时使用类名转下划线
        tableInfo.setTableName(camelToUnderscore(entityClass.getSimpleName()));
    }

    /**
     * 解析所有字段上的 {@link Column}、{@link Id}、{@link GeneratedValue} 注解
     */
    private static List<ColumnInfo> resolveColumns(Class<?> entityClass) {
        // 用 LinkedHashMap 保持字段定义顺序
        Map<String, ColumnInfo> columnMap = new LinkedHashMap<>();

        // 遍历所有字段（包含父类）
        Class<?> currentClass = entityClass;
        while (currentClass != null && currentClass != Object.class) {
            for (Field field : currentClass.getDeclaredFields()) {
                // 跳过静态字段
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }

                String fieldName = field.getName();

                // 子类已处理过同名字段则跳过
                if (columnMap.containsKey(fieldName)) {
                    continue;
                }

                ColumnInfo columnInfo = buildColumnInfo(field);
                columnMap.put(fieldName, columnInfo);
            }
            currentClass = currentClass.getSuperclass();
        }

        return new ArrayList<>(columnMap.values());
    }

    /**
     * 根据字段构建 ColumnInfo
     */
    private static ColumnInfo buildColumnInfo(Field field) {
        ColumnInfo columnInfo = new ColumnInfo();
        columnInfo.setFieldName(field.getName());
        columnInfo.setJavaType(field.getType());

        // @Column
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

            // type: 显式指定则用指定值，否则自动推断
            if (!columnAnn.type().isEmpty()) {
                columnInfo.setType(columnAnn.type());
            } else {
                columnInfo.setType(inferType(field.getType(), columnAnn.length()));
            }
        } else {
            // 无 @Column 注解时，使用默认推断
            columnInfo.setColumnName(camelToUnderscore(field.getName()));
            columnInfo.setType(inferType(field.getType(), 255));
            columnInfo.setLength(255);
            columnInfo.setNullable(true);
        }

        // @Id
        Id idAnn = field.getAnnotation(Id.class);
        if (idAnn != null) {
            columnInfo.setPrimaryKey(true);
            columnInfo.setNullable(false); // 主键不可为空
        }

        // @GeneratedValue
        GeneratedValue gvAnn = field.getAnnotation(GeneratedValue.class);
        if (gvAnn != null) {
            columnInfo.setGenerationType(gvAnn.strategy());
        }

        return columnInfo;
    }

    /**
     * 解析索引：字段级 {@link Index} + 表级 {@link Table#indexes()} 复合索引
     */
    private static List<IndexInfo> resolveIndexes(Class<?> entityClass,
                                                   List<ColumnInfo> columns,
                                                   TableInfo tableInfo) {
        List<IndexInfo> indexes = new ArrayList<>();

        // 1. 字段级索引
        Class<?> currentClass = entityClass;
        while (currentClass != null && currentClass != Object.class) {
            for (Field field : currentClass.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }

                Index indexAnn = field.getAnnotation(Index.class);
                if (indexAnn == null) {
                    continue;
                }

                // 找到对应的 ColumnInfo
                String fieldName = field.getName();
                ColumnInfo colInfo = columns.stream()
                        .filter(c -> c.getFieldName().equals(fieldName))
                        .findFirst()
                        .orElse(null);
                if (colInfo == null) {
                    continue;
                }

                IndexInfo indexInfo = new IndexInfo();
                String colName = colInfo.getColumnName();
                String name = indexAnn.name();
                if (name.isEmpty()) {
                    name = "idx_" + colName;
                }
                indexInfo.setName(name);

                String columnList = indexAnn.columnList();
                if (!columnList.isEmpty()) {
                    indexInfo.setColumns(
                            Arrays.stream(columnList.split(","))
                                    .map(String::trim)
                                    .collect(Collectors.toList())
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
                                .map(String::trim)
                                .collect(Collectors.toList())
                );
                indexInfo.setUnique(indexAnn.unique());
                indexes.add(indexInfo);
            }
        }

        // 3. 字段 @Column(unique=true) → 自动生成唯一索引（避免与已有索引重复）
        for (ColumnInfo col : columns) {
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

        // 去重（基于索引名）
        return indexes.stream()
                .distinct()
                .sorted(Comparator.comparing(IndexInfo::getName))
                .collect(Collectors.toList());
    }

    // ============== 类型推断 ==============

    /**
     * 根据 Java 类型推断数据库类型（MySQL 方言风格）
     */
    private static String inferType(Class<?> javaType, int length) {
        if (javaType == String.class) {
            if (length > 65535) return "TEXT";
            if (length > 16383) return "MEDIUMTEXT";
            return "VARCHAR(" + length + ")";
        }
        if (javaType == boolean.class || javaType == Boolean.class) {
            return "TINYINT(1)";
        }
        if (javaType == byte.class || javaType == Byte.class) {
            return "TINYINT";
        }
        if (javaType == short.class || javaType == Short.class) {
            return "SMALLINT";
        }
        if (javaType == int.class || javaType == Integer.class) {
            return "INT";
        }
        if (javaType == long.class || javaType == Long.class) {
            return "BIGINT";
        }
        if (javaType == float.class || javaType == Float.class) {
            return "FLOAT";
        }
        if (javaType == double.class || javaType == Double.class) {
            return "DOUBLE";
        }
        if (javaType == java.math.BigDecimal.class) {
            return "DECIMAL(20,6)";
        }
        if (javaType == java.util.Date.class || javaType == LocalDateTime.class) {
            return "DATETIME";
        }
        if (javaType == LocalDate.class) {
            return "DATE";
        }
        if (javaType == LocalTime.class) {
            return "TIME";
        }
        if (javaType == byte[].class) {
            return "BLOB";
        }
        // 默认兜底
        return "VARCHAR(" + length + ")";
    }

    // ============== 工具方法 ==============

    /**
     * 驼峰转下划线命名
     * <pre>
     *   userName  → user_name
     *   UserName  → user_name
     *   user_name → user_name (不变)
     * </pre>
     */
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
