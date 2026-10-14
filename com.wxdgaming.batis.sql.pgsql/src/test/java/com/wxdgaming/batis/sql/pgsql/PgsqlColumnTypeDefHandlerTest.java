package com.wxdgaming.batis.sql.pgsql;

import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.SqlType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link PgsqlColumnTypeDefHandler} 单元测试。
 * <p>锁住的是"零虚假 ALTER"这条不变式：改表处理器判漂移靠
 * {@code renderNormalized(实体列) == normalize(库端回读形态)}，
 * 两侧任何一对同义词没折到同一个比较键，每次启动都会对存量表刷一串无谓的 ALTER
 * （PG 的 {@code ALTER COLUMN TYPE} 会重写整表，比 MySQL 的代价更高）。</p>
 * <p>库端形态字面量取自真实 PG 14 的 {@code format_type()} 输出（由
 * {@code PgsqlDbMetaHandlerIT} 在嵌入式 PG 上对拍验证），不是推测出来的写法。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-14
 **/
class PgsqlColumnTypeDefHandlerTest {

    private final PgsqlColumnTypeDefHandler handler = PgsqlColumnTypeDefHandler.INSTANCE;

    /** 构造"由 Java 类型自动推断出来的列"：只有逻辑类型 + 长度/精度 */
    private static ColumnInfo col(SqlType sqlType, int length, int scale) {
        ColumnInfo c = new ColumnInfo();
        c.setColumnName("test_col");
        c.setSqlType(sqlType);
        c.setLength(length);
        c.setScale(scale);
        return c;
    }

    /** 构造"实体上用 @Column(type=...) 显式指定物理类型"的列 */
    private static ColumnInfo explicit(String physical) {
        ColumnInfo c = new ColumnInfo();
        c.setColumnName("test_col");
        c.setType(physical);
        return c;
    }

    // ==================== renderType ====================

    /**
     * 用例①：逐档核对逻辑类型渲染出的 PG 物理字面量。
     * <p>三处与 MySQL 的实质差别都在断言里点名：布尔是原生 BOOLEAN（不是 TINYINT(1) 约定）；
     * 没有 tinyint/mediumint 两档，BYTE 上折 SMALLINT、MEDIUMINT 上折 INTEGER（容量只放大不缩小，
     * 存量 MySQL 列迁过来不会因宽度不足而溢出）；文本与二进制分族收敛成 TEXT/BYTEA，
     * JSON 落 TEXT 而不是 JSONB（读写两侧都按字符串走 fastjson，与 MySQL 侧把 JSON 落 LONGTEXT 同一口径）。</p>
     */
    @Test
    void rendersPgPhysicalLiterals() {
        assertEquals("BOOLEAN", handler.renderType(col(SqlType.BOOLEAN, 255, 0)));
        assertEquals("SMALLINT", handler.renderType(col(SqlType.BYTE, 0, 0)));
        assertEquals("SMALLINT", handler.renderType(col(SqlType.SHORT, 0, 0)));
        assertEquals("INTEGER", handler.renderType(col(SqlType.MEDIUMINT, 0, 0)));
        assertEquals("INTEGER", handler.renderType(col(SqlType.INT, 0, 0)));
        assertEquals("BIGINT", handler.renderType(col(SqlType.BIGINT, 0, 0)));
        assertEquals("REAL", handler.renderType(col(SqlType.FLOAT, 0, 0)));
        assertEquals("DOUBLE PRECISION", handler.renderType(col(SqlType.DOUBLE, 0, 0)));
        assertEquals("NUMERIC(20,6)", handler.renderType(col(SqlType.DECIMAL, 20, 6)));
        assertEquals("VARCHAR(64)", handler.renderType(col(SqlType.VARCHAR, 64, 0)));
        assertEquals("CHAR(10)", handler.renderType(col(SqlType.CHAR, 10, 0)));
        assertEquals("TEXT", handler.renderType(col(SqlType.TEXT, 20000, 0)));
        assertEquals("TEXT", handler.renderType(col(SqlType.MEDIUMTEXT, 70000, 0)));
        assertEquals("TEXT", handler.renderType(col(SqlType.LONGTEXT, 0, 0)));
        assertEquals("BYTEA", handler.renderType(col(SqlType.BLOB, 0, 0)));
        assertEquals("BYTEA", handler.renderType(col(SqlType.MEDIUMBLOB, 0, 0)));
        assertEquals("BYTEA", handler.renderType(col(SqlType.LONGBLOB, 0, 0)));
        assertEquals("DATE", handler.renderType(col(SqlType.DATE, 0, 0)));
        assertEquals("TIME", handler.renderType(col(SqlType.TIME, 0, 0)));
        assertEquals("TIMESTAMP", handler.renderType(col(SqlType.DATETIME, 0, 0)));
        assertEquals("TIMESTAMP", handler.renderType(col(SqlType.TIMESTAMP, 0, 0)));
        assertEquals("TEXT", handler.renderType(col(SqlType.JSON, 255, 0)));
    }

    /**
     * 用例②：必须有参数的档位不能渲染成裸类型。
     * <p>{@code CHAR()} 与 {@code NUMERIC()} 无参在 PG 里各有默认含义（CHAR 变 character(1)、
     * NUMERIC 变"不限精度"），而"不限精度"的 numeric 与实体侧的 numeric(20,2) 是真差异。
     * 未写长度时按 1 补、精度未写时按 20 补，保证 DDL 与实体的取值范围一致。</p>
     */
    @Test
    void parameterizedTypesGetFallbackArguments() {
        assertEquals("CHAR(1)", handler.renderType(col(SqlType.CHAR, 0, 0)));
        assertEquals("NUMERIC(20,0)", handler.renderType(col(SqlType.DECIMAL, 0, 0)));
        // 负数 scale 是脏输入，按 0 处理，不能渲染出 NUMERIC(20,-2) 这种跑不了的 DDL
        assertEquals("NUMERIC(20,0)", handler.renderType(col(SqlType.DECIMAL, 20, -2)));
    }

    /** 用例③：显式 {@code @Column(type=...)} 一个字都不改地透传——逃生舱必须可靠 */
    @Test
    void explicitPhysicalTypeWinsVerbatim() {
        assertEquals("NUMERIC(12,2)", handler.renderType(explicit("NUMERIC(12,2)")));
        assertEquals("citext", handler.renderType(explicit("citext")));
        assertEquals("jsonb", handler.renderType(explicit("jsonb")));
    }

    /** 用例④：既无逻辑类型又无物理类型的列当场失败，不默认给 VARCHAR 把表建错 */
    @Test
    void renderRejectsColumnWithoutAnyType() {
        ColumnInfo c = new ColumnInfo();
        c.setColumnName("x");
        assertThrows(IllegalArgumentException.class, () -> handler.renderType(c));
    }

    /**
     * 用例⑤：遍历 SqlType 全部枚举值，保证"新增档位忘了加渲染分支"会被拦住。
     * <p>断言两点：渲染非空（switch 没有漏分支）；渲染结果再规格化一次幂等。
     * 幂等这条是结构比对的地基——比对是"实体侧规格化 vs 库侧规格化"，
     * 规格化形式再被规格化一次若会变化，同一张表每次启动都被判成漂移。</p>
     */
    @Test
    void everySqlTypeIsRenderableAndIdempotent() {
        for (SqlType t : SqlType.values()) {
            String physical = handler.renderType(col(t, 255, 2));
            assertNotNull(physical, "渲染漏分支: " + t);
            String normalized = handler.normalize(physical);
            assertEquals(normalized, handler.normalize(normalized), "normalize 幂等: " + t);
        }
    }

    // ==================== normalize：与库端回读形态对齐 ====================

    /**
     * 用例⑥（核心）：每个逻辑档的"实体侧规格化"与"真实 PG 回读形态规格化"必须逐档相等。
     * <p>左列字面量是 {@code format_type()} 在 PG 14 上的实际输出：布尔/整数没有宽度修饰，
     * varchar 回读成两词的 {@code character varying(n)}，char 回读成 {@code character(n)}
     * （{@code bpchar} 是它的内部名，也一并测），double 回读成 {@code double precision}，
     * 时间类回读<em>总带</em> {@code without time zone} 尾巴。
     * 只要有一档对不上，接入框架的存量 PG 表就会在每次启动时对这些列重发一次
     * {@code ALTER COLUMN ... TYPE}——那是整表重写。</p>
     */
    @Test
    void dbReadBackFormMatchesRenderedForm() {
        assertEquals(handler.renderNormalized(col(SqlType.BOOLEAN, 0, 0)), handler.normalize("boolean"));
        assertEquals(handler.renderNormalized(col(SqlType.BYTE, 0, 0)), handler.normalize("smallint"));
        assertEquals(handler.renderNormalized(col(SqlType.SHORT, 0, 0)), handler.normalize("smallint"));
        assertEquals(handler.renderNormalized(col(SqlType.INT, 0, 0)), handler.normalize("integer"));
        assertEquals(handler.renderNormalized(col(SqlType.MEDIUMINT, 0, 0)), handler.normalize("integer"));
        assertEquals(handler.renderNormalized(col(SqlType.BIGINT, 0, 0)), handler.normalize("bigint"));
        assertEquals(handler.renderNormalized(col(SqlType.FLOAT, 0, 0)), handler.normalize("real"));
        assertEquals(handler.renderNormalized(col(SqlType.DOUBLE, 0, 0)), handler.normalize("double precision"));
        assertEquals(handler.renderNormalized(col(SqlType.DECIMAL, 20, 6)), handler.normalize("numeric(20,6)"));
        assertEquals(handler.renderNormalized(col(SqlType.VARCHAR, 255, 0)), handler.normalize("character varying(255)"));
        assertEquals(handler.renderNormalized(col(SqlType.CHAR, 10, 0)), handler.normalize("character(10)"));
        assertEquals(handler.renderNormalized(col(SqlType.TEXT, 0, 0)), handler.normalize("text"));
        assertEquals(handler.renderNormalized(col(SqlType.JSON, 0, 0)), handler.normalize("text"));
        assertEquals(handler.renderNormalized(col(SqlType.BLOB, 0, 0)), handler.normalize("bytea"));
        assertEquals(handler.renderNormalized(col(SqlType.DATE, 0, 0)), handler.normalize("date"));
        assertEquals(handler.renderNormalized(col(SqlType.TIME, 0, 0)), handler.normalize("time without time zone"));
        assertEquals(handler.renderNormalized(col(SqlType.DATETIME, 0, 0)), handler.normalize("timestamp without time zone"));
    }

    /**
     * 用例⑦：同义写法与内部名全部折到同一个比较键。
     * <p>PG 允许在 DDL 里写 {@code bool}、{@code int4}、{@code serial}、{@code float8}、{@code decimal}、
     * {@code bpchar}，而 {@code format_type} 一律回读成标准拼写。框架建表用了简写、
     * 回读拿到标准名，如果两侧不同键，自己刚建出来的表立刻就会被判成漂移。
     * {@code bigserial}/{@code serial} 这里只比类型档（自增属性走 extra，不参与类型比较）。</p>
     */
    @Test
    void synonymsFoldToTheSameKey() {
        assertEquals(handler.normalize("boolean"), handler.normalize("bool"));
        assertEquals(handler.normalize("smallint"), handler.normalize("int2"));
        assertEquals(handler.normalize("integer"), handler.normalize("int4"));
        assertEquals(handler.normalize("integer"), handler.normalize("serial"));
        assertEquals(handler.normalize("integer"), handler.normalize("mediumint"));
        assertEquals(handler.normalize("bigint"), handler.normalize("int8"));
        assertEquals(handler.normalize("bigint"), handler.normalize("bigserial"));
        assertEquals(handler.normalize("real"), handler.normalize("float4"));
        assertEquals(handler.normalize("double precision"), handler.normalize("float8"));
        assertEquals(handler.normalize("double precision"), handler.normalize("float"));
        assertEquals(handler.normalize("numeric(20,2)"), handler.normalize("decimal(20,2)"));
        assertEquals(handler.normalize("character varying(64)"), handler.normalize("varchar(64)"));
        assertEquals(handler.normalize("character(1)"), handler.normalize("bpchar"));
        assertEquals(handler.normalize("character(1)"), handler.normalize("char"));
        // MySQL 的 longtext/mediumblob 不是 PG 的类型名，库里读不到这种写法（CREATE 时就直接 42704），
        // 所以档位收敛只发生在逻辑类型那一侧，见用例⑧
        assertEquals(handler.normalize("bytea"), handler.normalize("blob"));
        assertEquals(handler.normalize("timestamp"), handler.normalize("datetime"));
    }

    /**
     * 用例⑧：容量档收敛不能反过来把不同语义的类型并成一键。
     * <p>{@code TEXT/MEDIUMTEXT/LONGTEXT → text}、{@code BLOB 三档 → bytea} 是有意的收敛
     * （PG 靠 TOAST 自动扩展，没有分档必要），断言从逻辑档这一侧走：
     * 三个不同档位渲染出的物理类型规格化后同键。
     * 另外 {@code byte}/{@code short} 同键、{@code mediumint}/{@code int} 同键是 PG 没有更窄整数档的结果，
     * 一旦有人为了"更精确"给它们各造一个键，存量 MySQL 迁过来的列就会全部被判漂移。</p>
     */
    @Test
    void capacityFamiliesCollapseDeliberately() {
        assertEquals(handler.renderNormalized(col(SqlType.TEXT, 0, 0)),
                handler.renderNormalized(col(SqlType.LONGTEXT, 0, 0)));
        assertEquals(handler.renderNormalized(col(SqlType.MEDIUMTEXT, 0, 0)),
                handler.renderNormalized(col(SqlType.TEXT, 0, 0)));
        assertEquals(handler.renderNormalized(col(SqlType.BLOB, 0, 0)),
                handler.renderNormalized(col(SqlType.LONGBLOB, 0, 0)));
        assertEquals(handler.renderNormalized(col(SqlType.MEDIUMBLOB, 0, 0)), handler.normalize("bytea"));
        assertEquals(handler.renderNormalized(col(SqlType.BYTE, 0, 0)),
                handler.renderNormalized(col(SqlType.SHORT, 0, 0)));
        assertEquals(handler.renderNormalized(col(SqlType.MEDIUMINT, 0, 0)),
                handler.renderNormalized(col(SqlType.INT, 0, 0)));
    }

    /**
     * 用例⑨：长度与精度是列契约，必须保留在比较键里。
     * <p>断言 varchar(64) 与 varchar(128) 判不等——放过它就会让超长写入 22001（值过长）。
     * 同时断言 {@code (20, 6)} 这种带空格的写法与 {@code (20,6)} 判等：
     * PG 的回读形态不带空格，而实体侧 {@code @Column(type="numeric(20, 6)")} 常带，
     * 折紧之前这正是"明明没变却反复发 MODIFY"的来源。</p>
     */
    @Test
    void keepsArgumentsButFoldsTheirWhitespace() {
        assertNotEquals(handler.normalize("character varying(64)"), handler.normalize("character varying(128)"));
        assertNotEquals(handler.normalize("numeric(20,6)"), handler.normalize("numeric(20,4)"));
        assertEquals(handler.normalize("numeric(20,6)"), handler.normalize("numeric(20, 6)"));
        assertEquals("character varying(64)", handler.normalize("CHARACTER VARYING(64)"));
    }

    /**
     * 用例⑩：时间类型自带的精度修饰不构成漂移，但"是否带时区"必须算漂移。
     * <p>{@code timestamp(3)} 与 {@code timestamp} 在 PG 里是两个可互通的写法
     * （{@code format_type} 只在显式写了精度时才带数字），若把它们判成不同，
     * 框架会对存量精度列反复发 {@code ALTER COLUMN ... TYPE timestamp}——那是有损的整表重写。</p>
     * <p>反过来，{@code timestamptz} 与 {@code timestamp} 的写入换算行为不同，
     * 判成"一致"就等于永远不提示一个真实的语义差异，所以这里断言两者键不同。</p>
     */
    @Test
    void temporalPrecisionCollapsesButTimeZoneDoesNot() {
        assertEquals(handler.normalize("timestamp"), handler.normalize("timestamp without time zone"));
        assertEquals(handler.normalize("timestamp"), handler.normalize("timestamp(3) without time zone"));
        assertEquals(handler.normalize("time"), handler.normalize("time(2) without time zone"));
        assertNotEquals(handler.normalize("timestamp"), handler.normalize("timestamp with time zone"));
        assertNotEquals(handler.normalize("timestamp"), handler.normalize("timestamptz"));
    }

    /**
     * 用例⑪：框架不认识的类型保持"可见的差异"，不被静默归一到某个已知档。
     * <p>取样 {@code geometry}（PostGIS，档位表里没有）与 {@code integer[]}（数组）：
     * 前者原样返回，后者带着尾部修饰返回，两者都与任何已知档不等。
     * 静默归一（比如都算成 text）会让漂移检测说"结构没问题"，
     * 而实际读写两侧都按各自的形态在跑——报出差异交人处理才是对的。</p>
     */
    @Test
    void unknownBaseTypesStayDistinguishable() {
        assertEquals("geometry", handler.normalize("geometry"));
        assertNotEquals(handler.normalize("geometry"), handler.normalize("text"));
        assertNotEquals(handler.normalize("integer[]"), handler.normalize("integer"));
        // 同一种未知写法两侧各读回一次必须判等，否则"库里本来就有的数组列"会被反复改
        assertEquals(handler.normalize("integer[]"), handler.normalize("INTEGER[]"));
    }

    /**
     * 用例⑫：PG 原生 JSON 档与 text 档必须判不等，json 与 jsonb 之间也不等。
     * <p>这一条守着"json/jsonb 故意不进基名表"那个决定：注册进去就被折成
     * {@code SqlType.JSON → "text"}，于是"实体声明 jsonb、库里还是 text"会被判成没变，
     * 用户的显式选型从此得不到任何结构保障；反向（库里 jsonb、实体退回 text）同样被掩盖。
     * 留在表外走"未识别基名原样返回"，两侧都折成小写拼写照样判等，而写法差异不参与判定。</p>
     */
    @Test
    void nativeJsonTypesStayDistinctFromText() {
        assertEquals("jsonb", handler.normalize("JSONB"));
        assertEquals("json", handler.normalize(" json "));
        assertNotEquals(handler.normalize("jsonb"), handler.normalize("text"));
        assertNotEquals(handler.normalize("json"), handler.normalize("jsonb"));
        assertNotEquals(handler.normalize("jsonb"), handler.normalize("citext"));
        // 不带 type 的 JSON 存储模式按既有口径落 text，与显式声明的 jsonb 是两个档
        ColumnInfo plain = col(SqlType.JSON, 0, 0);
        ColumnInfo explicit = col(SqlType.JSON, 0, 0);
        explicit.setType("JSONB");
        assertEquals("text", handler.renderNormalized(plain));
        assertEquals("jsonb", handler.renderNormalized(explicit));
        assertNotEquals(handler.renderNormalized(explicit), handler.renderNormalized(plain));
    }

    /** 用例⑬：null 规格化成空串而不是抛 NPE——比对两侧都可能是 null（列未填物理类型） */
    @Test
    void nullNormalizesToEmpty() {
        assertEquals("", handler.normalize(null));
        assertEquals("", handler.normalize("   "));
    }

    /** 用例⑭：方言名参与日志与错误信息，拼错会让人在多方言排查时认错对象 */
    @Test
    void dialectNameIsStable() {
        assertEquals("pgsql", handler.dialectName());
    }
}
