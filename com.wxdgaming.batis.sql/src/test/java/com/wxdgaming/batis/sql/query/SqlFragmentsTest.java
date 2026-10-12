package com.wxdgaming.batis.sql.query;

import com.wxdgaming.batis.exception.BadSqlGrammarException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 遗留字符串 WHERE 片段闸门测试。
 * <p>
 * 黑名单只能拦"特征明显"的注入（分号 / 注释 / UNION / 系统库），而最常见的拼接误用
 * {@code "name = '" + input + "'"} 结果形如 {@code name = '' OR '1'='1}——不含任何被禁 token。
 * 因此闸门补上两条硬规则：占位符数量必须与参数数量一致；没有参数时不允许出现字符串字面量。
 * </p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 */
class SqlFragmentsTest {

    @Test
    void parameterizedFragmentPasses() {
        String checked = SqlFragments.guardWhereSection("`account_name` = ? AND `level` > ?", "a", 3);
        assertEquals("`account_name` = ? AND `level` > ?", checked);
    }

    @Test
    void bareConditionWithoutParamsPasses() {
        assertEquals("`deleted` = 0", SqlFragments.guardWhereSection("`deleted` = 0"));
    }

    @Test
    void nullOrBlankIsReturnedAsIs() {
        assertNull(SqlFragments.guardWhereSection(null));
        assertNull(SqlFragments.guardWhereSection(null, "x"));
        assertEquals("  ", SqlFragments.guardWhereSection("  ", "x"));
    }

    @Test
    void placeholderCountMismatchIsRejected() {
        // 两个占位符只给一个参数：绑定会整体错位，必须当场拒绝
        assertThrows(BadSqlGrammarException.class,
                () -> SqlFragments.guardWhereSection("`a` = ? AND `b` = ?", 1));
        assertThrows(BadSqlGrammarException.class,
                () -> SqlFragments.guardWhereSection("`a` = ?", 1, 2));
    }

    @Test
    void questionMarkInsideLiteralIsNotCountedAsPlaceholder() {
        // 引号里的 ? 不是占位符：下面这条带字面量 + 占位符的片段只有 1 个占位符，配 1 个参数即通过
        assertEquals("`remark` = 'a?b' AND `id` = ?",
                SqlFragments.guardWhereSection("`remark` = 'a?b' AND `id` = ?", 7));
        // 纯字面量、无参数的写法被"无参不得含字面量"规则拒绝（代价见 README 已知边界第 23 条）
        assertThrows(BadSqlGrammarException.class,
                () -> SqlFragments.guardWhereSection("`remark` = 'why?'"));
    }

    @Test
    void inlineLiteralWithoutParamsIsRejected() {
        assertThrows(BadSqlGrammarException.class,
                () -> SqlFragments.guardWhereSection("`name` = 'tom'"));
    }

    @Test
    void classicTautologyBypassIsRejected() {
        // 这正是黑名单漏掉的那一类：没有分号、没有注释、没有 UNION
        assertThrows(BadSqlGrammarException.class,
                () -> SqlFragments.guardWhereSection("`name` = '' OR '1'='1"));
        assertThrows(BadSqlGrammarException.class,
                () -> SqlFragments.guardWhereSection("`name` = '' OR `id` > 0 AND ''='"));
    }

    @Test
    void blacklistStillRejectsObviousAttacks() {
        assertThrows(BadSqlGrammarException.class,
                () -> SqlFragments.guardWhereSection("`id` = 1; DROP TABLE `t_account`"));
        assertThrows(BadSqlGrammarException.class,
                () -> SqlFragments.guardWhereSection("`id` = 1 UNION SELECT * FROM `t_account`"));
        assertThrows(BadSqlGrammarException.class,
                () -> SqlFragments.guardWhereSection("`id` = 1 -- 注释掉后半段"));
    }
}
