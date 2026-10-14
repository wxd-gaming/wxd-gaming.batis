package com.wxdgaming.batis.sql.pgsql;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link PgsqlIdentifier} 单元测试：锁定"双引号 + 强制小写"这套标识符口径。
 * <p>意义：整个方言的引号出口只有这一个方法，而建表、查询、元数据回读三方必须写成同一个名字。
 * 任何一方漏了小写（或漏了引号），结果都是运行期 42P01/42703，
 * 而不是启动期就能看到的报错，所以这里把它当契约钉住。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-14
 **/
class PgsqlIdentifierTest {

    /** 用例①：普通标识符加双引号并折小写，与 PG 对未加引号标识符的折叠结果一致 */
    @Test
    void quotesAndLowercases() {
        assertEquals("\"t_role\"", PgsqlIdentifier.quote("T_Role"));
        assertEquals("\"t_role\"", PgsqlIdentifier.quote("t_role"));
    }

    /**
     * 用例②：标识符内部的双引号必须双写。
     * <p>PG 里没有合法的裸双引号列名，但防御性地做转义：漏掉这一步的话
     * {@code a"b} 会拼成 {@code "a"b"}，后半截被当成新的语法单元——那是标识符位置的注入点。</p>
     */
    @Test
    void doublesInnerQuotes() {
        assertEquals("\"a\"\"b\"", PgsqlIdentifier.quote("a\"b"));
    }

    /**
     * 用例③：大小写折叠必须与 JVM 默认 Locale 无关。
     * <p>土耳其 Locale 下 {@code "ID".toLowerCase()} 得到的是 dotless ı 开头的 "ıd"，
     * 而 PG 服务器按自己的规则折叠出 "id"，两侧就对不上；引号强制小写一旦跟着 Locale 漂移，
     * 报错只会是一句难以归因的 column "ıd" does not exist。</p>
     */
    @Test
    void lowercasingIsLocaleIndependent() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("\"id\"", PgsqlIdentifier.quote("ID"));
        } finally {
            Locale.setDefault(original);
        }
    }

    /** 用例④：两段式（表.列）各自加引号，中间一个点 */
    @Test
    void quotesQualifiedPair() {
        assertEquals("\"t_role\".\"level\"", PgsqlIdentifier.quote("T_Role", "Level"));
    }

    /**
     * 用例⑤：保留字列名不需要任何特殊处理。
     * <p>这是"一律加引号"策略买到的收益：{@code order}、{@code user} 在 PG 里都是关键字，
     * 裸写会 42601，加引号后是合法列名；实体侧因此不必为方言保留字维护一份黑名单。</p>
     */
    @Test
    void reservedWordsQuoteCleanly() {
        assertEquals("\"order\"", PgsqlIdentifier.quote("order"));
        assertEquals("\"user\"", PgsqlIdentifier.quote("user"));
        assertEquals("\"select\"", PgsqlIdentifier.quote("SELECT"));
    }
}
