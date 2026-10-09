package com.wxdgaming.batis.sql.mysql;

import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.SqlType;
import com.wxdgaming.batis.sql.ColumnTypeDefHandler;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MySQL 列类型定义处理器
 * <p>逻辑类型（SqlType）→ MySQL 物理类型渲染，以及物理类型规格化（消除整数显示宽度等同义写法）。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class MysqlColumnTypeDefHandler implements ColumnTypeDefHandler {

    public static final MysqlColumnTypeDefHandler INSTANCE = new MysqlColumnTypeDefHandler();

    /** 物理类型分解：基名 + 括号参数 + 尾部修饰（unsigned/zerofill 等） */
    private static final Pattern PHYSICAL_PATTERN =
            Pattern.compile("^([a-z]+)\\s*(?:\\(([^)]*)\\))?\\s*(.*)$");

    /** MySQL 物理类型基名（含同义词）→ 逻辑类型 */
    private static final Map<String, SqlType> BASE_TYPE_MAP = new HashMap<>();

    static {
        BASE_TYPE_MAP.put("tinyint", SqlType.BYTE);
        BASE_TYPE_MAP.put("smallint", SqlType.SHORT);
        BASE_TYPE_MAP.put("mediumint", SqlType.MEDIUMINT);
        BASE_TYPE_MAP.put("int", SqlType.INT);
        BASE_TYPE_MAP.put("integer", SqlType.INT);
        BASE_TYPE_MAP.put("bigint", SqlType.BIGINT);
        BASE_TYPE_MAP.put("float", SqlType.FLOAT);
        BASE_TYPE_MAP.put("double", SqlType.DOUBLE);
        BASE_TYPE_MAP.put("decimal", SqlType.DECIMAL);
        BASE_TYPE_MAP.put("numeric", SqlType.DECIMAL);
        BASE_TYPE_MAP.put("varchar", SqlType.VARCHAR);
        BASE_TYPE_MAP.put("char", SqlType.CHAR);
        BASE_TYPE_MAP.put("tinytext", SqlType.TEXT);
        BASE_TYPE_MAP.put("text", SqlType.TEXT);
        BASE_TYPE_MAP.put("mediumtext", SqlType.MEDIUMTEXT);
        BASE_TYPE_MAP.put("longtext", SqlType.LONGTEXT);
        BASE_TYPE_MAP.put("json", SqlType.JSON);
        BASE_TYPE_MAP.put("tinyblob", SqlType.BLOB);
        BASE_TYPE_MAP.put("blob", SqlType.BLOB);
        BASE_TYPE_MAP.put("mediumblob", SqlType.MEDIUMBLOB);
        BASE_TYPE_MAP.put("longblob", SqlType.LONGBLOB);
        BASE_TYPE_MAP.put("date", SqlType.DATE);
        BASE_TYPE_MAP.put("time", SqlType.TIME);
        BASE_TYPE_MAP.put("datetime", SqlType.DATETIME);
        BASE_TYPE_MAP.put("timestamp", SqlType.DATETIME);
        BASE_TYPE_MAP.put("year", SqlType.SHORT);
        BASE_TYPE_MAP.put("bit", SqlType.BYTE);
        BASE_TYPE_MAP.put("binary", SqlType.BLOB);
        BASE_TYPE_MAP.put("varbinary", SqlType.BLOB);
        BASE_TYPE_MAP.put("enum", SqlType.VARCHAR);
        BASE_TYPE_MAP.put("set", SqlType.VARCHAR);
    }

    /**
     * 逻辑类型 → MySQL 物理类型字面量。
     * <p>渲染结果必须与"解耦前 EntityParser 直出的物理字面量"逐字节一致（见
     * {@code MysqlColumnTypeDefHandlerTest#renderMatchesLegacyLiterals}）：只要某个档位的字符串变了，
     * 存量库下次结构比对就会认为该列漂移并刷出无谓的 MODIFY。所以这里的 case 分支看似能"美化"，
     * 实际是历史事实的锁定，改动任何一条都要同步验证 SchemaDriftIT 的零漂移不变式。</p>
     * <p>优先级：显式 {@code @Column(type=...)} 字面量原样透传（逃生舱，用于表达 unsigned/zerofill
     * 等逻辑类型写不出的列定义）；否则按 sqlType 渲染。几个刻意的历史行为——
     * BOOLEAN 渲染为带宽度的 {@code TINYINT(1)}（布尔在 MySQL 里就是 tinyint(1)，宽度是语义的一部分，
     * 与 BYTE 渲染的裸 {@code TINYINT} 必须可区分）；JSON 存储模式渲染为 {@code LONGTEXT}
     * 而非原生 JSON（解耦前既有行为，保持兼容）；CHAR/DECIMAL 用 {@code paramOr} 给长度兜默认值，
     * VARCHAR 用 {@code Math.max(len,1)} 保证长度至少为 1。</p>
     *
     * @param col 列元数据，读其显式 type、sqlType、length、scale
     * @return 大写形式的 MySQL 物理类型字符串
     * @throws IllegalArgumentException sqlType 与 type 皆空时抛出，宁可建表当场失败也不默认给 VARCHAR 让数据错类型落库
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
            case BOOLEAN -> "TINYINT(1)";
            case BYTE -> "TINYINT";
            case SHORT -> "SMALLINT";
            case MEDIUMINT -> "MEDIUMINT";
            case INT -> "INT";
            case BIGINT -> "BIGINT";
            case FLOAT -> "FLOAT";
            case DOUBLE -> "DOUBLE";
            case DECIMAL -> "DECIMAL(" + paramOr(col.getLength(), 20) + "," + Math.max(col.getScale(), 0) + ")";
            case VARCHAR -> "VARCHAR(" + Math.max(col.getLength(), 1) + ")";
            case CHAR -> "CHAR(" + paramOr(col.getLength(), 1) + ")";
            case TEXT -> "TEXT";
            case MEDIUMTEXT -> "MEDIUMTEXT";
            case LONGTEXT -> "LONGTEXT";
            case BLOB -> "BLOB";
            case MEDIUMBLOB -> "MEDIUMBLOB";
            case LONGBLOB -> "LONGBLOB";
            case DATE -> "DATE";
            case TIME -> "TIME";
            case DATETIME -> "DATETIME";
            case TIMESTAMP -> "TIMESTAMP";
            case JSON -> "LONGTEXT";
        };
    }

    /**
     * 结构比对用的"实体侧比较键"：先渲染再规格化，等价于 {@code normalize(renderType(col))}。
     * <p>{@code MysqlTableAlterHandler} 正是拿它的返回与 {@code normalize(库端 COLUMN_TYPE)} 比对来决定
     * 是否发 MODIFY，因此这条链路的输出必须与库端元数据经 normalize 后的形式完全对齐
     * （见 {@code MysqlColumnTypeDefHandlerTest#renderNormalizedMatchesLegacyPipeline}）。</p>
     */
    @Override
    public String renderNormalized(ColumnInfo col) {
        return normalize(renderType(col));
    }

    /**
     * 把物理类型字符串规格化为可比较形式：统一小写、消除同义写法与方言显示宽度差异。
     * <p>WHY 必须剥掉整数显示宽度：MariaDB/MySQL 的 information_schema.COLUMN_TYPE 会给整数列回报宽度
     * （{@code int(11)}、{@code tinyint(4)}、{@code bigint(20)}、{@code smallint(6)}），而实体侧渲染出的是裸
     * {@code INT}；不剥宽度则每次启动都判成漂移、对全表刷一遍 MODIFY——大表上等于线上锁表事故
     * （回归护栏见 SchemaDriftIT 与 {@code dbMetadataFormsClassifyBack}）。整数类型本身无宽度语义，直接归一到基名。</p>
     * <p>与解耦前 {@code MysqlTableAlterHandler#normalizeType} 在历史输入上逐例等价（见
     * {@code normalizeEquivalentToLegacyOnHistoricalInputs}），但有两处刻意的改进：
     * 一是 null 规格化为空串而非抛异常；二是折叠 decimal 参数里的空格，使 {@code decimal(20, 6)} 与
     * {@code decimal(20,6)} 判等——旧实现认为二者不同，正是它对没变的 DECIMAL 列反复发 MODIFY 的根因。</p>
     * <p>刻意保留的差异：{@code tinyint(1)}（布尔档）与 {@code tinyint(4)}（单字节整数档）必须仍可区分，
     * 否则布尔列与整数列会互相误判；VARCHAR/CHAR/DECIMAL 的长度/精度是列契约的一部分，予以保留。</p>
     *
     * @param physicalType 物理类型字符串，可为实体侧渲染结果或库端回读的 COLUMN_TYPE
     * @return 规格化后的可比较字符串；null 入参返回空串，未识别的基名原样（小写去空白）返回
     */
    @Override
    public String normalize(String physicalType) {
        if (physicalType == null) return "";
        String t = physicalType.toLowerCase(Locale.ROOT).trim();
        Matcher m = PHYSICAL_PATTERN.matcher(t);
        if (!m.matches()) return t;
        String base = m.group(1);
        String params = m.group(2);
        String suffix = m.group(3) == null ? "" : m.group(3).trim();

        SqlType sqlType = BASE_TYPE_MAP.get(base);
        if (sqlType == null) return t;

        // 整数类型无宽度概念：int(11) / bigint(20) 均规格化为基名
        if (sqlType == SqlType.BYTE || sqlType == SqlType.SHORT || sqlType == SqlType.MEDIUMINT
                || sqlType == SqlType.INT || sqlType == SqlType.BIGINT) {
            return concat(base, suffix);
        }
        // boolean 逻辑档：任意宽度的 tinyint(1) 都归一为 tinyint(1)
        if ("tinyint".equals(base) && params != null && "1".equals(params.trim())) {
            return "tinyint(1)";
        }
        // FLOAT/DOUBLE 显示宽度（如 double(10,2)） MySQL 8 已不保留，按旧规格化语义丢弃
        if (sqlType == SqlType.FLOAT || sqlType == SqlType.DOUBLE) {
            return concat(base, suffix);
        }
        // 其余（VARCHAR/CHAR/DECIMAL/TEXT/BLOB 等）：参数是类型契约的一部分，保留
        if (params == null) return concat(base, suffix);
        String compact = params.replaceAll("\\s*,\\s*", ",").replaceAll("\\s+", "");
        return concat(base + "(" + compact + ")", suffix);
    }

    /** 拼接类型基名与尾部修饰（unsigned/zerofill），无修饰时不加多余空格，保证比较键规范 */
    private static String concat(String type, String suffix) {
        return suffix.isEmpty() ? type : type + " " + suffix;
    }

    /** length 非正（未指定）时回退到方言默认值，用于 CHAR/DECIMAL 这类必须有长度的档位 */
    private static int paramOr(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    @Override
    public String dialectName() {
        return "mysql";
    }

}
