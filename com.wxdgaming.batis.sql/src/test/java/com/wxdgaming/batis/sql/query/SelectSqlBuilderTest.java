package com.wxdgaming.batis.sql.query;

import com.wxdgaming.batis.exception.UnknownColumnException;
import com.wxdgaming.batis.model.TableInfo;
import com.wxdgaming.batis.parser.EntityParser;
import com.wxdgaming.batis.query.Criteria;
import com.wxdgaming.batis.query.Criterion;
import com.wxdgaming.batis.query.CriterionOperator;
import com.wxdgaming.batis.query.Sort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SelectSqlBuilder 渲染单测（不连 DB，用 FakeSelectHandler + 注解实体）
 * <p>覆盖：列名解析、操作符渲染、括号优先级、参数顺序不变式。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
class SelectSqlBuilderTest {

    private static final TableInfo TABLE = EntityParser.parse(DslTestEntity.class);
    private static final FakeSelectHandler HANDLER = new FakeSelectHandler();

    /** 不可变渲染规格（测试视图） */
    private record TestSpec(Criteria where, List<String> columns, Sort sort,
                            Integer limit, Integer offset)
            implements SelectSqlBuilder.SelectQuerySpec {
    }

    private static TestSpec spec(Criteria where, List<String> columns, Sort sort,
                                 Integer limit, Integer offset) {
        return new TestSpec(where, columns, sort, limit, offset);
    }

    private static SelectSqlBuilder.Plan render(Criteria where) {
        return SelectSqlBuilder.build(HANDLER, TABLE, spec(where, null, null, null, null));
    }

    @Test
    void simpleEqRendersQuotedColumnAndParam() {
        SelectSqlBuilder.Plan plan = render(Criteria.where("status").eq(3));
        assertEquals("SELECT * FROM `t_dsl_test` WHERE `status` = ?", plan.sql());
        assertArrayEquals(new Object[]{3}, plan.params());
    }

    @Test
    void camelFieldResolvesToUnderlineColumn() {
        SelectSqlBuilder.Plan plan = render(Criteria.where("createTime").eq(1));
        assertTrue(plan.sql().contains("`create_time`"), plan.sql());
        SelectSqlBuilder.Plan byDbName = render(Criteria.where("user_name").eq(1));
        assertTrue(byDbName.sql().contains("`user_name`"), byDbName.sql());
        SelectSqlBuilder.Plan byField = render(Criteria.where("userName").eq(1));
        assertTrue(byField.sql().contains("`user_name`"), byField.sql());
    }

    @Test
    void inRendersOnePlaceholderPerValue() {
        SelectSqlBuilder.Plan plan = render(Criteria.where("status").in(1, 2, 3));
        assertEquals("SELECT * FROM `t_dsl_test` WHERE `status` IN (?, ?, ?)", plan.sql());
        assertArrayEquals(new Object[]{1, 2, 3}, plan.params());
    }

    @Test
    void betweenRendersTwoPlaceholders() {
        SelectSqlBuilder.Plan plan = render(Criteria.where("amount").between(1, 9));
        assertEquals("SELECT * FROM `t_dsl_test` WHERE `amount` BETWEEN ? AND ?", plan.sql());
        assertArrayEquals(new Object[]{1, 9}, plan.params());
    }

    @Test
    void isNullRendersNoPlaceholder() {
        SelectSqlBuilder.Plan plan = render(Criteria.where("userName").isNull());
        assertEquals("SELECT * FROM `t_dsl_test` WHERE `user_name` IS NULL", plan.sql());
        assertEquals(0, plan.params().length);
    }

    @Test
    void eqColRendersTwoIdentifiersNoParam() {
        SelectSqlBuilder.Plan plan = render(Criteria.where("id").eqCol("status"));
        assertEquals("SELECT * FROM `t_dsl_test` WHERE `id` = `status`", plan.sql());
        assertEquals(0, plan.params().length);
    }

    @Test
    void likeKeepsWildcardsInParamDialectNeutral() {
        SelectSqlBuilder.Plan like = render(Criteria.where("userName").like("abc"));
        assertEquals("SELECT * FROM `t_dsl_test` WHERE `user_name` LIKE ?", like.sql());
        assertArrayEquals(new Object[]{"%abc%"}, like.params());

        SelectSqlBuilder.Plan left = render(Criteria.where("userName").leftLike("abc"));
        assertTrue(left.sql().endsWith("LIKE ?"), left.sql());
        assertArrayEquals(new Object[]{"%abc"}, left.params());

        SelectSqlBuilder.Plan right = render(Criteria.where("userName").rightLike("abc"));
        assertArrayEquals(new Object[]{"abc%"}, right.params());
    }

    @Test
    void orUnderAndGetsParentheses() {
        Criteria c = Criteria.allOf(
                Criteria.where("status").eq(1),
                Criteria.anyOf(
                        Criteria.where("userName").eq("a"),
                        Criteria.where("userName").eq("b")));
        SelectSqlBuilder.Plan plan = render(c);
        assertEquals("SELECT * FROM `t_dsl_test`"
                + " WHERE `status` = ? AND (`user_name` = ? OR `user_name` = ?)", plan.sql());
        assertArrayEquals(new Object[]{1, "a", "b"}, plan.params());
    }

    @Test
    void notWrapsChild() {
        SelectSqlBuilder.Plan plan = render(Criteria.not(Criteria.where("status").eq(1)));
        assertEquals("SELECT * FROM `t_dsl_test` WHERE NOT (`status` = ?)", plan.sql());
    }

    @Test
    void rawFragmentPassesThrough() {
        SelectSqlBuilder.Plan plan = render(Criteria.raw("`status` + ? > 0", 5));
        assertEquals("SELECT * FROM `t_dsl_test` WHERE `status` + ? > 0", plan.sql());
        assertArrayEquals(new Object[]{5}, plan.params());
    }

    @Test
    void columnsProjectionAndOrderBy() {
        SelectSqlBuilder.Plan plan = SelectSqlBuilder.build(HANDLER, TABLE,
                spec(null, List.of("id", "userName"), Sort.by("createTime").and(Sort.desc("id")), null, null));
        assertEquals("SELECT `id`, `user_name` FROM `t_dsl_test`"
                + " ORDER BY `create_time` ASC, `id` DESC", plan.sql());
    }

    @Test
    void parameterOrderInvariantWhereThenLimitThenOffset() {
        SelectSqlBuilder.Plan plan = SelectSqlBuilder.build(HANDLER, TABLE,
                spec(Criteria.allOf(Criteria.where("status").eq(7),
                        Criteria.where("userName").in("x", "y")),
                        null, null, 10, 20));
        assertEquals("SELECT * FROM `t_dsl_test`"
                + " WHERE `status` = ? AND `user_name` IN (?, ?) LIMIT ? OFFSET ?", plan.sql());
        // 不变式：[WHERE 参数..., LIMIT, OFFSET]
        assertArrayEquals(new Object[]{7, "x", "y", 10, 20}, plan.params());
    }

    @Test
    void countPlanDropsOrderByAndPagingKeepsWhereParams() {
        SelectSqlBuilder.Plan plan = SelectSqlBuilder.buildCount(HANDLER, TABLE,
                spec(Criteria.where("status").eq(7), null, Sort.desc("id"), 10, 20));
        assertEquals("SELECT COUNT(*) FROM `t_dsl_test` WHERE `status` = ?", plan.sql());
        assertArrayEquals(new Object[]{7}, plan.params());
    }

    @Test
    void unknownColumnInWhereThrows() {
        assertThrows(UnknownColumnException.class,
                () -> render(Criteria.where("no_such_col").eq(1)));
    }

    @Test
    void unknownColumnInProjectionThrows() {
        assertThrows(UnknownColumnException.class, () -> SelectSqlBuilder.build(HANDLER, TABLE,
                spec(null, List.of("ghost"), null, null, null)));
    }

    @Test
    void resolveColumnRejectsEmptyName() {
        assertThrows(UnknownColumnException.class, () -> SelectSqlBuilder.resolveColumn(TABLE, ""));
    }

    @Test
    void criterionPlaceholderCounts() {
        assertEquals(0, new Criterion("a", CriterionOperator.IS_NULL, List.of()).placeholderCount());
        assertEquals(0, new Criterion("a", CriterionOperator.EQ_COL, List.of("b")).placeholderCount());
        assertEquals(2, new Criterion("a", CriterionOperator.BETWEEN, List.of(1, 2)).placeholderCount());
        assertEquals(3, new Criterion("a", CriterionOperator.IN, List.of(1, 2, 3)).placeholderCount());
    }

}
