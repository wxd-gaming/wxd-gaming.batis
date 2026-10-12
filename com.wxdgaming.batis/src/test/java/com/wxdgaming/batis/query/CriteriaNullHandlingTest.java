package com.wxdgaming.batis.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 查询条件的 null 语义测试。
 * <p>三条契约：</p>
 * <ol>
 *   <li>{@code eq/ne} 把 null 收敛成 IS NULL / IS NOT NULL（SQL 里 {@code col = NULL} 恒为未知）；</li>
 *   <li>其余操作符传 null <b>快速失败</b>并给出"哪一列、哪个操作符"的可读报错，
 *       而不是让 {@code List.of} 抛无消息的 NPE（同一 API 里两种失败形态会让人排查很久）；</li>
 *   <li>{@code and/or} 传 null 不允许静默丢弃——那会把过滤条件无声放宽。</li>
 * </ol>
 *
 * @author wxd-gaming(無心道, 15388152619)
 */
class CriteriaNullHandlingTest {

    @Test
    void eqNullBecomesIsNull() {
        Criterion c = Criteria.where("deletedAt").eq(null).getLeaf();
        assertEquals(CriterionOperator.IS_NULL, c.op());
        assertEquals(0, c.placeholderCount());
    }

    @Test
    void neNullBecomesIsNotNull() {
        Criterion c = Criteria.where("deletedAt").ne(null).getLeaf();
        assertEquals(CriterionOperator.IS_NOT_NULL, c.op());
    }

    @Test
    void nonNullRejectedOperatorsFailFastWithReadableMessage() {
        IllegalArgumentException gt = assertThrows(IllegalArgumentException.class,
                () -> Criteria.where("level").gt(null));
        assertTrue(gt.getMessage().contains("level") && gt.getMessage().contains("GT"),
                "报错要能看出是哪一列哪个操作符，实际: " + gt.getMessage());

        assertThrows(IllegalArgumentException.class, () -> Criteria.where("level").between(1, null));
        assertThrows(IllegalArgumentException.class, () -> Criteria.where("name").like(null));
    }

    @Test
    void inRejectsNullElementAndEmptyCollection() {
        IllegalArgumentException withNull = assertThrows(IllegalArgumentException.class,
                () -> Criteria.where("id").in(1, null, 3));
        assertTrue(withNull.getMessage().contains("id"));
        assertThrows(IllegalArgumentException.class, () -> Criteria.where("id").in());
    }

    @Test
    void andOrRejectNullInsteadOfSilentlyDroppingCondition() {
        Criteria base = Criteria.where("tenantId").eq(7);
        assertThrows(NullPointerException.class, () -> base.and(null));
        assertThrows(NullPointerException.class, () -> base.or(null));
    }

    @Test
    void allOfStillFiltersNullVarargs() {
        // varargs 组合保留"过滤 null"的便利语义；空组合仍然快速失败
        Criteria only = Criteria.allOf(null, Criteria.where("a").eq(1), null);
        assertEquals(CriterionOperator.EQ, only.getLeaf().op());
        assertThrows(IllegalArgumentException.class, () -> Criteria.allOf((Criteria[]) new Criteria[0]));
    }

    @Test
    void rawAllowsNullParameter() {
        // raw 是逃生舱：片段由调用方书写，绑定 NULL 是合法用法（如 IS NOT DISTINCT FROM ?）
        Criteria raw = Criteria.raw("`meta` IS NOT DISTINCT FROM ?", (Object) null);
        Criterion leaf = raw.getLeaf();
        assertEquals(CriterionOperator.RAW, leaf.op());
        assertEquals(1, leaf.values().size());
        assertNull(leaf.values().getFirst(), "raw 必须容忍 null 参数，而不是抛无消息 NPE");
    }

    @Test
    void criterionFactoryRejectsNullValue() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> Criterion.of("amount", CriterionOperator.GT, (Object) null));
        assertTrue(ex.getMessage().contains("amount"));
    }
}
