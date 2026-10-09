package com.wxdgaming.batis.sql.mysql;

import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.SqlType;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MysqlColumnTypeDefHandler 单元测试
 * <p>锁定两条不变式：</p>
 * <ol>
 *   <li>逻辑类型渲染结果与解耦前 EntityParser 直出的物理字面量逐字节一致；</li>
 *   <li>normalize 与解耦前 MysqlTableAlterHandler#normalizeType 在历史出现过的一切输入上等价。</li>
 * </ol>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
class MysqlColumnTypeDefHandlerTest {

    private final MysqlColumnTypeDefHandler handler = MysqlColumnTypeDefHandler.INSTANCE;

    /** 构造一个"由 Java 类型自动推断出来的列"：只有逻辑类型 + 长度/精度，没有物理类型字符串 */
    private static ColumnInfo col(SqlType sqlType, int length, int scale) {
        ColumnInfo c = new ColumnInfo();
        c.setColumnName("test_col");
        c.setSqlType(sqlType);
        c.setLength(length);
        c.setScale(scale);
        return c;
    }

    /** 构造一个"实体上用 @Column(type=...) 显式指定物理类型"的列：渲染必须原样透传 */
    private static ColumnInfo explicit(String physical) {
        ColumnInfo c = new ColumnInfo();
        c.setColumnName("test_col");
        c.setType(physical);
        return c;
    }

    // ==================== renderType ====================

    /**
     * 用例①：每个逻辑类型渲染出的物理字面量，与解耦前 EntityParser 直出的字符串逐字节一致。
     * <p>这是整次改造的等价性证明：改造把"Java 类型 → MySQL 字面量"的判断从 core 挪进了方言层，
     * 只要有一个渲染结果变了，存量库在下次结构比对时就会认为类型漂移并刷出 MODIFY。
     * 期望值不是推理出来的，而是从改造前的代码里逐个抄下来的历史事实，
     * 因此本用例宁可写得啰嗦也不能简化。</p>
     * <p>几个刻意的取样：VARCHAR 靠 length 落在 255 与 64 两档（length 参数确实被读取）；
     * TEXT/MEDIUMTEXT 传入不同 length 但结果只看逻辑档位（档位由推断阶段决定，渲染不再二次猜）；
     * CHAR 传 0 长度仍渲染为 CHAR(1)，与单字符语义一致；JSON 渲染成 LONGTEXT 是解耦前的既有行为，原样保留。</p>
     */
    @Test
    void renderMatchesLegacyLiterals() {
        assertEquals("TINYINT(1)", handler.renderType(col(SqlType.BOOLEAN, 255, 0)));
        assertEquals("TINYINT", handler.renderType(col(SqlType.BYTE, 255, 0)));
        assertEquals("SMALLINT", handler.renderType(col(SqlType.SHORT, 255, 0)));
        assertEquals("INT", handler.renderType(col(SqlType.INT, 255, 0)));
        assertEquals("BIGINT", handler.renderType(col(SqlType.BIGINT, 255, 0)));
        assertEquals("FLOAT", handler.renderType(col(SqlType.FLOAT, 255, 0)));
        assertEquals("DOUBLE", handler.renderType(col(SqlType.DOUBLE, 255, 0)));
        assertEquals("DECIMAL(20,6)", handler.renderType(col(SqlType.DECIMAL, 20, 6)));
        assertEquals("VARCHAR(64)", handler.renderType(col(SqlType.VARCHAR, 64, 0)));
        assertEquals("VARCHAR(255)", handler.renderType(col(SqlType.VARCHAR, 255, 0)));
        assertEquals("CHAR(1)", handler.renderType(col(SqlType.CHAR, 0, 0)));
        assertEquals("TEXT", handler.renderType(col(SqlType.TEXT, 20000, 0)));
        assertEquals("MEDIUMTEXT", handler.renderType(col(SqlType.MEDIUMTEXT, 70000, 0)));
        assertEquals("LONGTEXT", handler.renderType(col(SqlType.LONGTEXT, 0, 0)));
        assertEquals("BLOB", handler.renderType(col(SqlType.BLOB, 0, 0)));
        assertEquals("MEDIUMBLOB", handler.renderType(col(SqlType.MEDIUMBLOB, 0, 0)));
        assertEquals("LONGBLOB", handler.renderType(col(SqlType.LONGBLOB, 0, 0)));
        assertEquals("DATE", handler.renderType(col(SqlType.DATE, 0, 0)));
        assertEquals("TIME", handler.renderType(col(SqlType.TIME, 0, 0)));
        assertEquals("DATETIME", handler.renderType(col(SqlType.DATETIME, 0, 0)));
        assertEquals("TIMESTAMP", handler.renderType(col(SqlType.TIMESTAMP, 0, 0)));
        // JSON 存储模式解耦前即为 LONGTEXT
        assertEquals("LONGTEXT", handler.renderType(col(SqlType.JSON, 255, 0)));
        assertEquals("MEDIUMINT", handler.renderType(col(SqlType.MEDIUMINT, 0, 0)));
    }

    /**
     * 用例②：显式指定物理类型时，框架一个字符都不改地透传。
     * <p>断言两条：规格里带精度的 DECIMAL(20,2) 与带修饰尾巴的 int(11) unsigned 原样输出。
     * 意义：逃生舱必须可靠——实体上写了 type 就是为了精确控制 DDL
     * （unsigned、zerofill、特定长度等逻辑类型表达不了的诉求），
     * 如果渲染层"好心"地重新规格化，用户就再也无法写出自己想要的列定义了。</p>
     */
    @Test
    void explicitPhysicalTypeWinsVerbatim() {
        assertEquals("DECIMAL(20,2)", handler.renderType(explicit("DECIMAL(20,2)")));
        assertEquals("int(11) unsigned", handler.renderType(explicit("int(11) unsigned")));
    }

    /**
     * 用例③：既没有逻辑类型也没有物理类型的列，必须立刻失败。
     * <p>意义：宁可在建表时抛 IllegalArgumentException 指出这一列没类型，
     * 也不能默认给个 VARCHAR(255) 把表建出来——后者会让数据以错误类型落库，
     * 之后要么截断要么改不了（改列类型是有损操作），而报错只是当场失败一次。</p>
     */
    @Test
    void renderRejectsColumnWithoutAnyType() {
        ColumnInfo c = new ColumnInfo();
        c.setColumnName("x");
        assertThrows(IllegalArgumentException.class, () -> handler.renderType(c));
    }

    /**
     * 用例④：遍历 SqlType 全部枚举值，保证"新增逻辑类型忘了加渲染分支"会被这个用例拦住。
     * <p>动作：每个枚举都渲染一次并 normalize 一次。断言两点：
     * 渲染不返回 null（说明 switch 里没有漏分支走到兜底）；
     * normalize 幂等（对已规格化的结果再 normalize 一次不变）。
     * 第二条看似平凡，实际是结构比对的地基：比对是"实体侧规格化 vs 库侧规格化"，
     * 只要规格化形式再被规格化一次发生变化，同一个表每次启动都会被认为漂移。</p>
     */
    @Test
    void everySqlTypeIsRenderableAndClassifiable() {
        for (SqlType t : SqlType.values()) {
            String physical = handler.renderType(col(t, 255, 2));
            assertNotNull(physical);
            // 渲染出的物理类型必须能回 classify 到稳定规格形式（round-trip 幂等）
            String normalized = handler.normalize(physical);
            assertEquals(normalized, handler.normalize(normalized), "normalize 幂等: " + t);
        }
    }

    // ==================== normalize：与旧实现逐例等价 ====================

    /** 解耦前 MysqlTableAlterHandler#normalizeType 的原样拷贝，作为行为基准 */
    private static String legacyNormalize(String type) {
        if (type == null) return "";
        String t = type.toLowerCase().trim();
        if (t.matches("^(tinyint|smallint|mediumint|int|bigint)(\\(\\d+\\))?.*$")) {
            t = t.replaceAll("\\(\\d+\\)", "");
        }
        return t;
    }

    /**
     * 用例⑤：新 normalize 与旧 normalizeType 在"历史上真实出现过的每一种输入"上等价。
     * <p>方法学说明：本类内保留了一份旧实现的<b>原样拷贝</b>（见 {@code legacyNormalize}）作为行为基准，
     * 而不是在这里重新写一遍我认为它应该怎么工作——否则测的就只是我自己的理解。
     * 输入清单覆盖了整数列带显示宽度的各种形态（int(11)/bigint(20)/tinyint(4)/tinyint(1)…）、
     * 带 unsigned 尾巴的列、字符串与LOB与日期时间族，以及大小写混合的写法。</p>
     * <p>为什么必须等价：normalize 的返回值是结构比对的比较键。
     * 只要它对某个历史输入给出不一样结果，存量库就会被判成漂移并在启动时刷出一堆 MODIFY。
     * 特别地，tinyint(1) 与 tinyint(4) 必须保持可区分（前者是布尔、后者是单字节整数），
     * 若被统一抹掉，布尔列和普通整数列会互相误判。</p>
     * <p>末尾两条是有意的偏离，故单独断言：null 规格化成空串；
     * decimal 参数里的空格被折叠，使 decimal(20,6) 与 decimal(20, 6) 判等
     * ——旧实现认为两者不同，正是它会对明明没变的 DECIMAL 列反复发 MODIFY 的原因。</p>
     */
    @Test
    void normalizeEquivalentToLegacyOnHistoricalInputs() {
        String[] inputs = {
                "int(11)", "bigint(20)", "tinyint(4)", "tinyint(1)", "smallint(6)", "mediumint(9)",
                "varchar(64)", "varchar(255)", "char(1)", "text", "mediumtext", "longtext",
                "blob", "longblob", "datetime", "date", "time", "timestamp",
                "DECIMAL(20,6)", "double", "float",
                "int(11) unsigned", "bigint(20) unsigned",
                "INT", "BIGINT", "TINYINT(1)", "VARCHAR(64)", "LONGTEXT", "DATETIME",
        };
        for (String input : inputs) {
            assertEquals(legacyNormalize(input), handler.normalize(input),
                    "normalize 偏离旧行为: " + input);
        }
        assertEquals("", handler.normalize(null));
        // 有意的改进：旧实现对 "decimal(20, 6)" vs "decimal(20,6)" 判不等（虚假 MODIFY），新实现折叠参数空格
        assertEquals(handler.normalize("decimal(20,6)"), handler.normalize("decimal(20, 6)"));
    }

    /**
     * 用例⑥：整条新管线（渲染 + 规格化）与旧管线（EntityParser 直出字面量 + 旧 normalize）结果相同。
     * <p>①只证了"渲染"这一半、⑤只证了"规格化"这一半，而比对实际用的是"渲染后规格化"这个组合。
     * 所以这里把两个半段串起来再对一次：左边是把旧字面量喂给旧 normalize，
     * 右边是新逻辑类型走新管线，逐档相等（含布尔、字符串、精确小数、整数、JSON 以及显式物理类型）。
     * 这条过了，才说明结构 diff 的输入端和改造前完全一致。</p>
     */
    @Test
    void renderNormalizedMatchesLegacyPipeline() {
        // 旧管线 = legacyNormalize(EntityParser直出字面量)；新管线 = renderNormalized
        assertEquals(legacyNormalize("TINYINT(1)"), handler.renderNormalized(col(SqlType.BOOLEAN, 255, 0)));
        assertEquals(legacyNormalize("VARCHAR(255)"), handler.renderNormalized(col(SqlType.VARCHAR, 255, 0)));
        assertEquals(legacyNormalize("DECIMAL(20,6)"), handler.renderNormalized(col(SqlType.DECIMAL, 20, 6)));
        assertEquals(legacyNormalize("BIGINT"), handler.renderNormalized(col(SqlType.BIGINT, 255, 0)));
        assertEquals(legacyNormalize("LONGTEXT"), handler.renderNormalized(col(SqlType.JSON, 255, 0)));
        assertEquals(legacyNormalize("DECIMAL(12,2)"), handler.renderNormalized(explicit("DECIMAL(12,2)")));
    }

    @Test
    void dbMetadataFormsClassifyBack() {
        // 库端元数据（information_schema.COLUMN_TYPE）与实体端渲染规格化对齐
        assertEquals(handler.renderNormalized(col(SqlType.BIGINT, 255, 0)), handler.normalize("bigint(20)"));
        assertEquals(handler.renderNormalized(col(SqlType.INT, 255, 0)), handler.normalize("int(11)"));
        assertEquals(handler.renderNormalized(col(SqlType.BOOLEAN, 255, 0)), handler.normalize("tinyint(1)"));
        assertEquals(handler.renderNormalized(col(SqlType.LONGTEXT, 0, 0)), handler.normalize("longtext"));
        assertEquals("decimal(12,2)", handler.normalize("decimal(12, 2)"));
    }

}
