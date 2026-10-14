package com.wxdgaming.batis.sql.pgsql;

import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.SqlType;
import com.wxdgaming.batis.sql.ColumnTypeDefHandler;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PostgreSQL 列类型定义处理器
 * <p>逻辑类型（SqlType）→ PG 物理类型渲染，以及物理类型规格化（把同义写法折成同一个比较键）。</p>
 * <p><b>本类是"零虚假 ALTER"不变式的核心</b>：{@code PgsqlTableAlterHandler} 拿
 * {@code renderNormalized(实体列)} 与 {@code normalize(库端回读类型)} 比对来决定是否发变更，
 * 两侧任何一对同义词没折到同一个串上，每次启动就会对存量表刷一串无谓的 ALTER（锁表 + 拖慢启动）。
 * 因此新增档位时必须同步三件事：renderType 的字面量、BASE_TYPE_MAP 的同义词、
 * {@code PgsqlDbMetaHandler} 从 information_schema 拼出的形态，并由
 * {@code PgsqlColumnTypeDefHandlerTest#normalizeIsIdempotentOverRenderedTypes} 与 PgSchemaDriftIT 双向锁住。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-14
 **/
public class PgsqlColumnTypeDefHandler implements ColumnTypeDefHandler {

    public static final PgsqlColumnTypeDefHandler INSTANCE = new PgsqlColumnTypeDefHandler();

    /**
     * 物理类型分解：基名 + 括号参数 + 尾部修饰。
     * <p>基名允许数字（{@code int2}、{@code float8}、{@code serial4}）：PG 的内部名后缀是位数，
     * 基名不含数字时 "int2" 会被拆成基名 "int" + 尾巴 "2"，规格化成 "integer 2" 这种谁也认不出的键，
     * 于是库里读回的 int2 列与实体侧的 SMALLINT 永远判不等。</p>
     */
    private static final Pattern PHYSICAL_PATTERN =
            Pattern.compile("^([a-z][a-z0-9]*(?: [a-z][a-z0-9]*)*)\\s*(?:\\(([^)]*)\\))?\\s*(.*)$");

    /**
     * PG 物理类型基名（含同义词与内部名）→ 逻辑类型。
     * <p>同时收录"实体侧渲染写法"（varchar/char/int/decimal）与"库端回读写法"
     * （character varying/bpchar/integer/numeric），两侧都折到同一个逻辑档。</p>
     * <p><b>刻意不含 {@code json}/{@code jsonb}</b>：注册进来就是把它们折到
     * {@code SqlType.JSON → "text"} 这一档，于是"实体声明 jsonb、库里还是 text"会被判成没变，
     * 用户显式选的列类型从此得不到任何结构保障（详见 {@link PgsqlJsonType}）。
     * 留在表外则走"未识别基名原样返回"，{@code "jsonb"} 与 {@code "text"} 是两个不同的比较键，
     * 漂移可见并由 {@link PgsqlTableAlterHandler} 带 USING 修回。</p>
     */
    private static final Map<String, SqlType> BASE_TYPE_MAP = new HashMap<>();

    /**
     * 逻辑类型 → 规格化后的比较键前缀（参数按需要追加）。
     * <p>刻意用库端的标准拼写（{@code character varying}、{@code double precision}）而不是渲染侧的简写，
     * 这样 {@code normalize} 的输出与 {@code PgsqlDbMetaHandler} 拼出的库端形态天然同一。</p>
     */
    private static final Map<SqlType, String> CANONICAL = new LinkedHashMap<>();

    /** 是否携带长度/精度参数参与比较的档位 */
    private static final java.util.EnumSet<SqlType> PARAMETERIZED =
            java.util.EnumSet.of(SqlType.VARCHAR, SqlType.CHAR, SqlType.DECIMAL);

    static {
        CANONICAL.put(SqlType.BOOLEAN, "boolean");
        CANONICAL.put(SqlType.BYTE, "smallint");
        CANONICAL.put(SqlType.SHORT, "smallint");
        CANONICAL.put(SqlType.MEDIUMINT, "integer");
        CANONICAL.put(SqlType.INT, "integer");
        CANONICAL.put(SqlType.BIGINT, "bigint");
        CANONICAL.put(SqlType.FLOAT, "real");
        CANONICAL.put(SqlType.DOUBLE, "double precision");
        CANONICAL.put(SqlType.DECIMAL, "numeric");
        CANONICAL.put(SqlType.VARCHAR, "character varying");
        CANONICAL.put(SqlType.CHAR, "character");
        CANONICAL.put(SqlType.TEXT, "text");
        CANONICAL.put(SqlType.MEDIUMTEXT, "text");
        CANONICAL.put(SqlType.LONGTEXT, "text");
        CANONICAL.put(SqlType.JSON, "text");
        CANONICAL.put(SqlType.BLOB, "bytea");
        CANONICAL.put(SqlType.MEDIUMBLOB, "bytea");
        CANONICAL.put(SqlType.LONGBLOB, "bytea");
        CANONICAL.put(SqlType.DATE, "date");
        CANONICAL.put(SqlType.TIME, "time");
        CANONICAL.put(SqlType.DATETIME, "timestamp");
        CANONICAL.put(SqlType.TIMESTAMP, "timestamp");

        BASE_TYPE_MAP.put("boolean", SqlType.BOOLEAN);
        BASE_TYPE_MAP.put("bool", SqlType.BOOLEAN);
        // PG 没有比 smallint 更小的整数档：BYTE/SHORT 都落到它，靠 BOOLEAN 与整数的区分而不是宽度区分布尔
        BASE_TYPE_MAP.put("smallint", SqlType.SHORT);
        BASE_TYPE_MAP.put("int2", SqlType.SHORT);
        BASE_TYPE_MAP.put("mediumint", SqlType.MEDIUMINT);
        BASE_TYPE_MAP.put("integer", SqlType.INT);
        BASE_TYPE_MAP.put("int", SqlType.INT);
        BASE_TYPE_MAP.put("int4", SqlType.INT);
        BASE_TYPE_MAP.put("serial", SqlType.INT);
        BASE_TYPE_MAP.put("serial4", SqlType.INT);
        BASE_TYPE_MAP.put("bigint", SqlType.BIGINT);
        BASE_TYPE_MAP.put("int8", SqlType.BIGINT);
        BASE_TYPE_MAP.put("bigserial", SqlType.BIGINT);
        BASE_TYPE_MAP.put("real", SqlType.FLOAT);
        BASE_TYPE_MAP.put("float4", SqlType.FLOAT);
        BASE_TYPE_MAP.put("double precision", SqlType.DOUBLE);
        BASE_TYPE_MAP.put("float8", SqlType.DOUBLE);
        // PG 里裸 float 是 double precision 的同义词，与逻辑档 DOUBLE 对齐
        BASE_TYPE_MAP.put("float", SqlType.DOUBLE);
        BASE_TYPE_MAP.put("numeric", SqlType.DECIMAL);
        BASE_TYPE_MAP.put("decimal", SqlType.DECIMAL);
        BASE_TYPE_MAP.put("character varying", SqlType.VARCHAR);
        BASE_TYPE_MAP.put("varchar", SqlType.VARCHAR);
        BASE_TYPE_MAP.put("character", SqlType.CHAR);
        // bpchar 是 PG 内部名：库端 format_type 会给出它，information_schema 给出 character
        BASE_TYPE_MAP.put("bpchar", SqlType.CHAR);
        BASE_TYPE_MAP.put("char", SqlType.CHAR);
        BASE_TYPE_MAP.put("text", SqlType.TEXT);
        BASE_TYPE_MAP.put("citext", SqlType.TEXT);
        BASE_TYPE_MAP.put("bytea", SqlType.BLOB);
        BASE_TYPE_MAP.put("blob", SqlType.BLOB);
        BASE_TYPE_MAP.put("date", SqlType.DATE);
        BASE_TYPE_MAP.put("time", SqlType.TIME);
        BASE_TYPE_MAP.put("time without time zone", SqlType.TIME);
        BASE_TYPE_MAP.put("timestamp", SqlType.DATETIME);
        BASE_TYPE_MAP.put("timestamp without time zone", SqlType.DATETIME);
        BASE_TYPE_MAP.put("datetime", SqlType.DATETIME);
        // timestamptz 不进气泡表：它必须被判成与 timestamp 不同的类型（见 normalize 的保留原样分支），
        // 否则存量带时区列会被框架认为"一致"而永远不提示，写入时的时区换算行为却和实体预期不符
    }

    /**
     * 逻辑类型 → PostgreSQL 物理类型字面量。
     * <p>与 MySQL 渲染的三处实质差别，都不是风格选择：</p>
     * <ul>
     *   <li>布尔是原生 {@code BOOLEAN}，不是 MySQL 的 {@code TINYINT(1)} 约定；</li>
     *   <li>没有比 smallint 更小的整数档，也没有 mediumint——{@code BYTE}/{@code SHORT}/{@code MEDIUMINT}
     *       分别上折到 {@code SMALLINT}/{@code SMALLINT}/{@code INTEGER}，容量只放大不缩小，
     *       存量 MySQL 列迁过来不会因为宽度不足而溢出；</li>
     *   <li>文本与二进制分族收敛：TEXT/MEDIUMTEXT/LONGTEXT 同为 {@code TEXT}，
     *       BLOB 三档同为 {@code BYTEA}（PG 按 TOAST 自动扩展，没有分档必要）；
     *       JSON 存储模式渲染为 {@code TEXT} 而非原生 {@code JSONB}——与 MySQL 侧把 JSON 落成 LONGTEXT
     *       的既有口径一致，读写两侧都按字符串走 fastjson，不引入 PG 专有的 PGobject 绑定。</li>
     * </ul>
     * <p>需要 PG 原生 JSON 列的用户走 {@code @Column(type = "jsonb")}（或 {@code "json"}）这一档：
     * 字面量分支在上面第一条就被原样透传，物理类型由此落到列上，绑定形态由
     * {@link PgsqlBeanValueHandler} 负责，结构比对见 {@link PgsqlJsonType}。默认仍是 TEXT，
     * 因为"不改注解的存量实体"必须继续能用。</p>
     * <p>优先级同 MySQL：显式 {@code @Column(type=...)} 字面量原样透传（逃生舱），否则按 sqlType 渲染。</p>
     *
     * @param col 列元数据
     * @return 物理类型字符串
     * @throws IllegalArgumentException sqlType 与 type 皆空时抛出，宁可建表当场失败也不默认给 VARCHAR
     */
    @Override
    public String renderType(ColumnInfo col) {
        String explicit = col.getType();
        if (explicit != null && !explicit.isEmpty()) {
            return explicit;
        }
        SqlType sqlType = col.getSqlType();
        if (sqlType == null) {
            throw new IllegalArgumentException("列 " + col.getColumnName()
                    + " 既无逻辑类型(sqlType)也无物理类型(type)，无法渲染 DDL 列定义");
        }
        return switch (sqlType) {
            case BOOLEAN -> "BOOLEAN";
            case BYTE -> "SMALLINT";
            case SHORT -> "SMALLINT";
            case MEDIUMINT -> "INTEGER";
            case INT -> "INTEGER";
            case BIGINT -> "BIGINT";
            case FLOAT -> "REAL";
            case DOUBLE -> "DOUBLE PRECISION";
            case DECIMAL -> "NUMERIC(" + paramOr(col.getLength(), 20) + "," + Math.max(col.getScale(), 0) + ")";
            case VARCHAR -> "VARCHAR(" + Math.max(col.getLength(), 1) + ")";
            case CHAR -> "CHAR(" + paramOr(col.getLength(), 1) + ")";
            case TEXT, MEDIUMTEXT, LONGTEXT -> "TEXT";
            case BLOB, MEDIUMBLOB, LONGBLOB -> "BYTEA";
            case DATE -> "DATE";
            case TIME -> "TIME";
            case DATETIME, TIMESTAMP -> "TIMESTAMP";
            case JSON -> "TEXT";
        };
    }

    /**
     * 结构比对用的"实体侧比较键"：先渲染再规格化，等价于 {@code normalize(renderType(col))}。
     */
    @Override
    public String renderNormalized(ColumnInfo col) {
        return normalize(renderType(col));
    }

    /**
     * 把物理类型字符串规格化为可比较形式：同义写法折叠到同一个键，参数只在有契约意义时保留。
     * <p>与 MySQL 版的两点不同：</p>
     * <ul>
     *   <li>不存在"整数显示宽度"要剥（PG 的 integer 就是 integer），但存在<b>多词基名</b>
     *       （{@code character varying}、{@code double precision}、{@code timestamp without time zone}），
     *       所以解析要先容忍空格；</li>
     *   <li>{@code BYTE} 与 {@code SHORT} 折到同一个键：PG 没有 tinyint，若坚持区分就会让每个
     *       {@code byte} 字段每次启动都判成漂移。</li>
     * </ul>
     * <p>参数保留规则：VARCHAR/CHAR/NUMERIC 的长度与精度是列契约的一部分，保留并把
     * {@code (20, 6)} 这类带空格的写法折紧；其余档位（含 FLOAT/DOUBLE，PG 不接受其精度参数）丢弃参数。
     * 未识别的基名原样返回（小写、折空白），让"库里存了个框架不认识的类型"表现为可见的差异，
     * 而不是被静默归一到某个已知档。</p>
     *
     * @param physicalType 实体侧渲染结果或库端回读形态
     * @return 可比较字符串；null 入参返回空串
     */
    @Override
    public String normalize(String physicalType) {
        if (physicalType == null) return "";
        String t = physicalType.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
        Matcher m = PHYSICAL_PATTERN.matcher(t);
        if (!m.matches()) return t;
        String base = m.group(1);
        String params = m.group(2);
        String suffix = m.group(3) == null ? "" : m.group(3).trim();

        SqlType sqlType = BASE_TYPE_MAP.get(base);
        if (sqlType == null) return t;

        // without time zone 是 PG 对 timestamp/time 的默认拼写，不是列契约的一部分：
        // format_type 只在列显式写了精度时才给出 "timestamp(3) without time zone"，
        // 不带精度时给出 "timestamp without time zone"。这两者必须是同一个比较键，
        // 否则前者会落到基名+尾巴的组合上去查不到档位，与实体侧渲染的 TIMESTAMP 判成漂移，
        // 每次启动都发一次有损精度的 ALTER COLUMN ... TYPE timestamp。
        // with time zone 反过来必须保留：它与 timestamp 语义不同，且不在档位表里，
        // 会以"未识别基名"或带尾巴的形式与 timestamp 区分开，让存量带时区列暴露成真实差异。
        if (suffix.startsWith("without time zone")) {
            suffix = suffix.substring("without time zone".length()).trim();
        }

        String canonical = CANONICAL.get(sqlType);
        if (PARAMETERIZED.contains(sqlType)) {
            if (params != null) {
                String compact = params.replaceAll("\\s*,\\s*", ",").replaceAll("\\s+", "");
                canonical = canonical + "(" + compact + ")";
            } else if (sqlType == SqlType.CHAR) {
                // PG 里裸写的 character 就是 character(1)（format_type 不给长度时才走这一支）：
                // 补上默认长度，实体侧渲染的 CHAR(1) 与库端回读的 character 才是同一个键，
                // 否则手工建的定长列会被反复判成漂移并发出整表重写的 ALTER COLUMN TYPE
                canonical = canonical + "(1)";
            }
        }
        // 保留尾部修饰（如 using 之外的意外内容），避免把未知信息当成"没变"
        return suffix.isEmpty() ? canonical : canonical + " " + suffix;
    }

    /** length 非正（未指定）时回退到方言默认值，用于 CHAR/NUMERIC 这类必须有参数的档位 */
    private static int paramOr(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    @Override
    public String dialectName() {
        return "pgsql";
    }

}
