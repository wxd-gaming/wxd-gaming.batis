package com.wxdgaming.batis.parser;

import com.wxdgaming.batis.annotation.Column;
import com.wxdgaming.batis.annotation.GeneratedValue;
import com.wxdgaming.batis.annotation.GenerationType;
import com.wxdgaming.batis.annotation.Id;
import com.wxdgaming.batis.annotation.Index;
import com.wxdgaming.batis.annotation.Table;
import com.wxdgaming.batis.converter.NoneTypeConverter;
import com.wxdgaming.batis.converter.TypeConverter;
import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.IndexInfo;
import com.wxdgaming.batis.model.SqlType;
import com.wxdgaming.batis.model.StorageMode;
import com.wxdgaming.batis.model.TableInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 实体注解解析器：把静态的 Java 类型 + 注解，一次性翻译成运行时的表元数据 {@link TableInfo}。
 * <p>
 * 这是整个框架的元数据入口——缓存 Key、DDL 建表/结构比对、SQL 参数绑定、结果集映射
 * 全部依赖这里产出的 {@link TableInfo} / {@link ColumnInfo}，所以它只负责"描述列"，
 * 不负责"生成 SQL"，保持与方言层解耦。
 *
 * <h3>解析管线（{@link #doParse}）</h3>
 * <ol>
 *   <li>{@link #resolveTableAnnotation}：读表级注解确定表名与注释（缺表名时类名驼峰转下划线）。</li>
 *   <li>{@link #resolveColumns}：沿继承链自底向上收集字段，逐字段 {@link #buildColumnInfo}
 *       确定列名、存储模式、逻辑类型、主键、生成策略与自定义转换器。</li>
 *   <li>{@link #resolveIndexes}：合并字段级索引、表级复合索引、唯一列自动派生的唯一索引，
 *       去重后按名字典序排序（稳定顺序，避免每次解析产出不同列表扰乱结构比对）。</li>
 * </ol>
 *
 * <h3>逻辑类型与物理类型的解耦（P1-4）</h3>
 * <p>本类只产出<b>逻辑类型</b> {@link SqlType} 加长度/精度，绝不产出方言字面量。
 * "这一列具体写成 VARCHAR(64) 还是 TINYINT(1)" 已下沉到 sql 层的 ColumnTypeDefHandler SPI，
 * 由各方言（MySQL / pgsql）各自渲染。因此这里 {@link #inferSqlType} 的任何"类型推断"都止步于逻辑档；
 * {@link ColumnInfo#getType()} 对自动推断的列保持 null，只有显式书面的物理类型注解才写它。</p>
 * <p><b>回归风险</b>：结构比对（checkTable）会把"实体侧渲染再规格化"与"库端元数据规格化"做 diff，
 * 只要逻辑档位、或 UUID=36 / BigDecimal=DECIMAL(20,6) / char=CHAR(1) 这类宽度约定与历史字面量不一致，
 * 就会对已有表判成漂移并反复刷出虚假 MODIFY（大表上等同线上锁表事故）。
 * 下方 {@link #applyInferredType} 里逐条修正 length/scale 的动作正是为守住"渲染结果与原字面量逐字节一致"这条不变式。</p>
 *
 * <h3>线程安全与缓存</h3>
 * <p>解析结果放在 {@link #CACHE}（Class → TableInfo），用 {@code computeIfAbsent} 保证同一 Class 只解析一次，
 * 并发首次访问也只有一个线程真正执行 doParse。TableInfo 建好后视为只读共享。
 * 需要重新解析（如热更实体定义）时调 {@link #clearCache} / {@link #clearAll}。</p>
 *
 * @author wxd-gaming
 */
public class EntityParser {

    /** 元数据缓存：同一实体 Class 只解析一次，解析后的 TableInfo 全局共享、视为只读 */
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

    /**
     * 解析单实体：表级注解 → 列集合 → 索引集合，组装成不可变使用的 {@link TableInfo}。
     * <p>仅在 {@link #CACHE} 首次未命中时执行，故同一 Class 的字段/列声明顺序被"冻结"为首次解析结果，
     * 这保证了建表 DDL 的列顺序稳定（列顺序抖动同样会触发结构比对差异）。</p>
     */
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

    /**
     * 解析表级来源注解，确定表名与注释。
     * <p>优先表注解，其次实体注解；两者皆无时按类名驼峰转下划线兜底生成表名，
     * 让"不打注解也能用"的最小约定成立。</p>
     */
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

    /**
     * 解析所有字段并构建列映射。
     * <p>用 {@link LinkedHashMap} 按"子类字段在前、父类字段在后"的声明顺序累积，
     * 顺序即建表时的物理列序，必须稳定（见 {@link #doParse} 的顺序冻结说明）。
     * {@code fieldNames} 去重保证子类同名覆盖字段只登记一次，避免父子同名字段映射到同一列产生重复列定义。</p>
     */
    private static LinkedHashMap<String, ColumnInfo> resolveColumns(Class<?> entityClass) {
        LinkedHashMap<String, ColumnInfo> columnMap = new LinkedHashMap<>();
        java.util.Set<String> fieldNames = new java.util.HashSet<>();

        Class<?> currentClass = entityClass;
        // 沿继承链向上收集，遇到 Object 停止；跳过 static 字段（不属于持久化列）
        while (currentClass != null && currentClass != Object.class) {
            for (Field field : currentClass.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                if (!fieldNames.add(field.getName())) continue;
                ColumnInfo col = buildColumnInfo(entityClass, field);
                columnMap.put(col.getColumnName(), col);
            }
            currentClass = currentClass.getSuperclass();
        }

        return columnMap;
    }

    /**
     * 单字段 → 列元数据：先记 Java 侧事实（字段/类型/getter/setter），再叠加注解语义。
     * <p>getter/setter 用 {@code entityClass}（而非声明字段的父类）查找，保证拿到运行时实际方法。
     * 注意：这里只写逻辑类型（{@code sqlType}）或显式物理类型（{@code type}），二者互斥。</p>
     */
    private static ColumnInfo buildColumnInfo(Class<?> entityClass, Field field) {
        ColumnInfo columnInfo = new ColumnInfo();
        columnInfo.setFieldName(field.getName());
        columnInfo.setJavaType(field.getType());
        columnInfo.setField(field);
        columnInfo.setGenericType(field.getGenericType());
        columnInfo.setReadMethod(findReadMethod(entityClass, field));
        columnInfo.setWriteMethod(findWriteMethod(entityClass, field));

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

            // 2. 确定列类型：显式物理类型优先，否则推断逻辑类型（物理渲染交给方言 ColumnTypeDefHandler）
            if (!columnAnn.type().isEmpty()) {
                columnInfo.setType(columnAnn.type());
            } else {
                applyInferredType(columnInfo, field.getType(), columnAnn.length(), storageMode);
            }
        } else {
            // 无列注解时的零配置约定：列名取字段驼峰转下划线，字符串宽度默认 255，可空
            columnInfo.setColumnName(camelToUnderscore(field.getName()));
            StorageMode storageMode = resolveStorageMode(StorageMode.AUTO, field.getType());
            columnInfo.setStorageMode(storageMode);
            columnInfo.setLength(255);
            applyInferredType(columnInfo, field.getType(), 255, storageMode);
            columnInfo.setNullable(true);
        }

        // 3. 解析自定义 TypeConverter
        //    仅在显式指定（非 NoneTypeConverter 占位）时实例化并缓存，要求无参构造且线程安全
        if (columnAnn != null && columnAnn.converter() != NoneTypeConverter.class) {
            try {
                TypeConverter<?, ?> converterInstance = columnAnn.converter()
                        .getDeclaredConstructor().newInstance();
                columnInfo.setConverter(converterInstance);
            } catch (Exception e) {
                throw new RuntimeException(
                        "实例化字段 " + field.getName() + " 的类型转换器失败: "
                                + columnAnn.converter().getName(), e);
            }
        }

        // 主键列强制非空：主键注解的存在本身就是 NOT NULL 语义，覆盖注解里的 nullable
        if (field.getAnnotation(Id.class) != null) {
            columnInfo.setPrimaryKey(true);
            columnInfo.setNullable(false);
        }

        // 主键生成策略（IDENTITY / 手动等）：供上层决定插入时是否回写自增主键
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
                || javaType == Character.class
                || javaType == BigDecimal.class
                || javaType == BigInteger.class
                || javaType == Date.class
                || javaType == java.sql.Date.class
                || javaType == java.sql.Time.class
                || javaType == java.sql.Timestamp.class
                || javaType == LocalDate.class
                || javaType == LocalTime.class
                || javaType == LocalDateTime.class
                || javaType == Instant.class
                || javaType == OffsetDateTime.class
                || javaType == ZonedDateTime.class
                || javaType == UUID.class
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
                // IndexInfo 按"名字"判等：同名字段索引与自动派生的唯一索引合并，避免重复建索引
                .distinct()
                // 按名字典序稳定排序，保证解析结果与建表 DDL 的索引顺序可复现
                .sorted(Comparator.comparing(IndexInfo::getName))
                .collect(Collectors.toList());
    }

    // ============== 类型推断（仅逻辑类型） ==============

    /**
     * 推断逻辑类型并落库到 ColumnInfo。
     * 个别 Java 类型有固定宽度约定（UUID=36），在推断时一并修正 length，保证渲染结果与原字面量一致。
     * <p>这些"宽度补丁"是结构比对零虚假 ALTER 的关键：它们复刻了解耦前 EntityParser 直出字面量时的
     * 隐含宽度，改动任何一条都可能让存量表的对应列被判成漂移。因此只允许产出 {@link SqlType} + 长度/精度，
     * 绝不在此拼方言物理类型字符串。</p>
     */
    private static void applyInferredType(ColumnInfo columnInfo, Class<?> javaType, int length, StorageMode storageMode) {
        SqlType sqlType = inferSqlType(javaType, length, storageMode);
        columnInfo.setSqlType(sqlType);
        // UUID 固定 36 位字符串宽度（与原字面量推断 VARCHAR(36) 保持一致）
        if (javaType == UUID.class && sqlType == SqlType.VARCHAR) {
            columnInfo.setLength(36);
        }
        // BigDecimal 自动档固定 DECIMAL(20,6) 精度契约（与原字面量推断一致，避免注解默认 length=255 污染精度）
        if (storageMode == StorageMode.AUTO && sqlType == SqlType.DECIMAL) {
            columnInfo.setLength(20);
            columnInfo.setScale(6);
        }
        // char/Character 固定 1 字符宽度（与原字面量推断 CHAR(1) 一致）
        if (sqlType == SqlType.CHAR && (javaType == char.class || javaType == Character.class)) {
            columnInfo.setLength(1);
        }
    }

    /**
     * 根据 Java 类型和存储模式推断逻辑列类型（不产生任何方言字面量，
     * 物理类型渲染由 sql 层 ColumnTypeDefHandler SPI 完成）
     *
     * @param javaType    Java 字段类型
     * @param length      列长度（主要用于 VARCHAR / TEXT 档位选择）
     * @param storageMode 存储模式
     */
    private static SqlType inferSqlType(Class<?> javaType, int length, StorageMode storageMode) {
        // 显式存储模式直接锁定逻辑档（不再看 Java 类型）；AUTO / default 落到下方 Java 类型推断
        switch (storageMode) {
            case JSON:
                return SqlType.JSON;
            case BOOL:
                return SqlType.BOOLEAN;
            case BYTE:
                return SqlType.BYTE;
            case SHORT:
                return SqlType.SHORT;
            case INT:
                return SqlType.INT;
            case LONG:
                return SqlType.BIGINT;
            case FLOAT:
                return SqlType.FLOAT;
            case DOUBLE:
                return SqlType.DOUBLE;
            case DECIMAL:
                return SqlType.DECIMAL;
            case VARCHAR:
                return SqlType.VARCHAR;
            case TEXT:
                return SqlType.TEXT;
            case MEDIUMTEXT:
                return SqlType.MEDIUMTEXT;
            case LONGTEXT:
                return SqlType.LONGTEXT;
            case BLOB:
                return SqlType.BLOB;
            case MEDIUMBLOB:
                return SqlType.MEDIUMBLOB;
            case LONGBLOB:
                return SqlType.LONGBLOB;
            case DATE:
                return SqlType.DATE;
            case DATETIME:
                return SqlType.DATETIME;
            case TIME:
                return SqlType.TIME;
            // AUTO 模式：按 Java 类型推断
            case AUTO:
            default:
                // fall through to Java type inference
        }

        // AUTO 模式：按 Java 类型推断
        if (javaType == String.class) {
            // 变长字符串按声明长度落档：越过 MySQL 各 TEXT 上限即升级为更大文本档，避免建出的列装不下数据
            if (length > 16777215) return SqlType.LONGTEXT;
            if (length > 65535) return SqlType.MEDIUMTEXT;
            if (length > 16383) return SqlType.TEXT;
            return SqlType.VARCHAR;
        }
        if (javaType == boolean.class || javaType == Boolean.class) return SqlType.BOOLEAN;
        if (javaType == byte.class || javaType == Byte.class) return SqlType.BYTE;
        if (javaType == short.class || javaType == Short.class) return SqlType.SHORT;
        if (javaType == int.class || javaType == Integer.class) return SqlType.INT;
        if (javaType == long.class || javaType == Long.class) return SqlType.BIGINT;
        if (javaType == float.class || javaType == Float.class) return SqlType.FLOAT;
        if (javaType == double.class || javaType == Double.class) return SqlType.DOUBLE;
        if (javaType == BigDecimal.class) return SqlType.DECIMAL;
        if (javaType == Date.class || javaType == LocalDateTime.class) return SqlType.DATETIME;
        if (javaType == LocalDate.class) return SqlType.DATE;
        if (javaType == LocalTime.class) return SqlType.TIME;
        if (javaType == byte[].class) return SqlType.BLOB;
        if (javaType == char.class || javaType == Character.class) return SqlType.CHAR;
        if (javaType == BigInteger.class) return SqlType.BIGINT;
        if (javaType == UUID.class) return SqlType.VARCHAR;
        if (javaType == Instant.class || javaType == OffsetDateTime.class
                || javaType == ZonedDateTime.class || javaType == java.sql.Timestamp.class) return SqlType.DATETIME;
        if (javaType == java.sql.Date.class) return SqlType.DATE;
        if (javaType == java.sql.Time.class) return SqlType.TIME;
        // 兜底：未知/自定义但未走 JSON 的类型一律按字符串列处理
        return SqlType.VARCHAR;
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

    // ============== getter / setter 方法查找（解析时缓存） ==============

    /** 查找 getter：优先 getXxx，boolean 类型兼容 isXxx */
    private static Method findReadMethod(Class<?> entityClass, Field field) {
        String cap = capitalize(field.getName());
        Method m = findMethod(entityClass, "get" + cap);
        if (m != null) return m;
        Class<?> t = field.getType();
        if (t == boolean.class || t == Boolean.class) {
            m = findMethod(entityClass, "is" + cap);
            if (m != null) return m;
        }
        return null;
    }

    /** 查找 setter：兼容字段基本类型与包装类型参数 */
    private static Method findWriteMethod(Class<?> entityClass, Field field) {
        String cap = capitalize(field.getName());
        Class<?> t = field.getType();
        Method m = findMethod(entityClass, "set" + cap, t);
        if (m == null) {
            Class<?> alt = t.isPrimitive() ? boxOf(t) : unboxOf(t);
            if (alt != null) m = findMethod(entityClass, "set" + cap, alt);
        }
        return m;
    }

    /** 沿父类链查找方法（兼容继承字段与重写） */
    private static Method findMethod(Class<?> start, String name, Class<?>... paramTypes) {
        Class<?> current = start;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredMethod(name, paramTypes);
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static String capitalize(String name) {
        if (name == null || name.isEmpty()) return name;
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static Class<?> boxOf(Class<?> p) {
        if (p == int.class) return Integer.class;
        if (p == long.class) return Long.class;
        if (p == short.class) return Short.class;
        if (p == byte.class) return Byte.class;
        if (p == double.class) return Double.class;
        if (p == float.class) return Float.class;
        if (p == boolean.class) return Boolean.class;
        if (p == char.class) return Character.class;
        return null;
    }

    private static Class<?> unboxOf(Class<?> b) {
        if (b == Integer.class) return int.class;
        if (b == Long.class) return long.class;
        if (b == Short.class) return short.class;
        if (b == Byte.class) return byte.class;
        if (b == Double.class) return double.class;
        if (b == Float.class) return float.class;
        if (b == Boolean.class) return boolean.class;
        if (b == Character.class) return char.class;
        return null;
    }
}
