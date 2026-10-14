package com.wxdgaming.batis.sql.pgsql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link PgsqlSqlLiteral} 单元测试：DDL 拼接出口的字面量渲染与转义。
 * <p>这一层直接决定"注解里的字符串能不能安全地出现在 DDL 文本里"，
 * 也是与 MySQL 版行为差异最集中的一处（不做反斜杠转义），所以逐条钉住。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-14
 **/
class PgsqlSqlLiteralTest {

    // ==================== escapeString ====================

    /**
     * 用例①：单引号双写是 PG 唯一的转义方式。
     * <p>断言含引号的注释渲染成 {@code ''}：写成 MySQL 那种反斜杠转义的话，
     * 在 {@code standard_conforming_strings=on}（PG 默认）下 {@code \'} 里的反斜杠是普通字符，
     * 存进目录的注释就凭空多一个反斜杠——静默改写用户数据，比报错更糟。</p>
     */
    @Test
    void doublesSingleQuotes() {
        assertEquals("O''Brien", PgsqlSqlLiteral.escapeString("O'Brien"));
        assertEquals("say''hi", PgsqlSqlLiteral.escapeString("say'hi"));
    }

    /**
     * 用例②：反斜杠必须原样保留（这是与 MySQL 版的实质差别，不是风格差别）。
     * <p>PG 默认不按反斜杠转义，Windows 路径、正则串、{@code \n} 字面量都应当逐字符存进去。
     * 照抄 MySQL 的 {@code \\}/{@code \n} 会把两个字符写成字面量、把换行写成 {@code \n}，
     * 事后从注释里看不出来被改写过。</p>
     */
    @Test
    void leavesBackslashAlone() {
        assertEquals("C:\\logs\\app.log", PgsqlSqlLiteral.escapeString("C:\\logs\\app.log"));
        assertEquals("line1\nline2", PgsqlSqlLiteral.escapeString("line1\nline2"));
    }

    /**
     * 用例③：NUL 当场拒绝。
     * <p>PG 的 text 无法存储 0x00。让它穿过去的话，报错发生在执行期、
     * 内容是"invalid byte sequence"之类完全不提是哪一列的注释；在渲染阶段抛就不是这样。</p>
     */
    @Test
    void rejectsNulByte() {
        assertThrows(IllegalArgumentException.class, () -> PgsqlSqlLiteral.escapeString("a\0b"));
    }

    /** 用例④：空串与不含特殊字符的普通文本原样通过（注释为空是常见输入，不能被"顺手"改掉） */
    @Test
    void plainTextPassesThrough() {
        assertEquals("", PgsqlSqlLiteral.escapeString(""));
        assertEquals("订单主表", PgsqlSqlLiteral.escapeString("订单主表"));
    }

    // ==================== defaultValue ====================

    /**
     * 用例⑤：带引号的字符串默认值按"SQL 字面量原样"通过，只校验不二次转义。
     * <p>{@code 'O''Brien'} 表示默认值就是 {@code O'Brien}：引号的双写已经是 SQL 层的转义，
     * 渲染器再转一次就变成 {@code O''Brien}（两个真实引号），存进去的内容与注解写的不是一回事，
     * 而且元数据回读会给出 {@code 'O''Brien'}，两侧每次比对都差一层——这正是本用例拦住的回归。</p>
     */
    @Test
    void quotedStringDefaultStaysQuotedOnce() {
        assertEquals("'abc'", PgsqlSqlLiteral.defaultValue("'abc'"));
        assertEquals("'O''Brien'", PgsqlSqlLiteral.defaultValue("'O''Brien'"));
        assertEquals("'abc'''", PgsqlSqlLiteral.defaultValue("'abc'''"));
        assertEquals("''", PgsqlSqlLiteral.defaultValue("''"));
    }

    /**
     * 用例⑥：裸字面量（数字、负数、函数、关键字、类型转换）原样透传。
     * <p>这些形态不能加引号：给 0 加上引号就变成字符串默认值，
     * 整数列上会 42804（类型不匹配）或被隐式转换，行为随列类型变化。</p>
     */
    @Test
    void bareDefaultsPassThrough() {
        assertEquals("0", PgsqlSqlLiteral.defaultValue("0"));
        assertEquals("-1", PgsqlSqlLiteral.defaultValue("-1"));
        assertEquals("20", PgsqlSqlLiteral.defaultValue(" 20 "));
        assertEquals("CURRENT_TIMESTAMP", PgsqlSqlLiteral.defaultValue("CURRENT_TIMESTAMP"));
        assertEquals("true", PgsqlSqlLiteral.defaultValue("true"));
    }

    /**
     * 用例⑥之二：日期时间默认值写成带引号的字符串，而不是 {@code '...'::type}。
     * <p>PG 在建表时按列类型转换字符串字面量，所以 {@code DEFAULT '2026-01-01'} 落在 date 列上就是合法的
     * 日期默认值；带类型转换的写法混合了引号与裸字符，两条分支都判不通过（见用例⑦），
     * 而且回读侧折回来的形式也是带引号的那个——写 cast 反而对不上。</p>
     */
    @Test
    void temporalDefaultIsWrittenAsQuotedString() {
        assertEquals("'2026-01-01'", PgsqlSqlLiteral.defaultValue("'2026-01-01'"));
    }

    /**
     * 用例⑦：既不合法又可疑的输入必须拒绝。
     * <p>取样分两类：</p>
     * <ul>
     *   <li><b>结构不合法的字符串字面量</b>（{@code 'a'b'}、闭合引号后接语句）：引号没成对双写时，
     *       PG 会把前半截当字面量、后半截当裸标识符或多出来的语句，报错是一句
     *       {@code column "b" does not exist}，离真实原因（注解写错）隔了一层，所以渲染阶段就拒绝；</li>
     *   <li><b>不符合白名单的裸写法</b>（{@code 0 OR 1=1}、含引号的 {@code nextval(...)}、
     *       引号与 cast 混合的 {@code '...'::date}）：与其把半截 DDL 发到库端，不如当场快速失败。</li>
     * </ul>
     */
    @Test
    void rejectsUnsafeDefaults() {
        assertThrows(IllegalArgumentException.class, () -> PgsqlSqlLiteral.defaultValue("''); DROP TABLE t;--"));
        assertThrows(IllegalArgumentException.class, () -> PgsqlSqlLiteral.defaultValue("'a'b'"));
        assertThrows(IllegalArgumentException.class, () -> PgsqlSqlLiteral.defaultValue("0 OR 1=1"));
        assertThrows(IllegalArgumentException.class, () -> PgsqlSqlLiteral.defaultValue("nextval('t_id_seq')"));
        // 引号 + cast 的混合形态：两条分支都不适用，宁可拒绝也不要放行成半截 DDL
        assertThrows(IllegalArgumentException.class, () -> PgsqlSqlLiteral.defaultValue("'2026-01-01'::date"));
    }
}
