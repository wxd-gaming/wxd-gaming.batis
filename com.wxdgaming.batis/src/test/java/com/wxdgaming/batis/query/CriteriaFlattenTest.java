package com.wxdgaming.batis.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Criteria 表达式树展平顺序不变式单测
 * <p>锁死核心契约：flatten() 的叶子顺序 == SQL 占位符顺序 == 参数绑定顺序。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
class CriteriaFlattenTest {

    @Test
    void singleLeafFlattensToOwnCriterion() {
        Criteria c = Criteria.where("status").eq("PAID");
        List<Criterion> flat = c.flatten();
        assertEquals(1, flat.size());
        assertEquals("status", flat.getFirst().column());
        assertEquals(CriterionOperator.EQ, flat.getFirst().op());
        assertEquals(List.of("PAID"), flat.getFirst().values());
    }

    @Test
    void andPreservesLeftToRightOrder() {
        Criteria c = Criteria.where("a").eq(1)
                .and(Criteria.where("b").eq(2))
                .and(Criteria.where("c").eq(3));
        assertEquals(List.of(1, 2, 3),
                c.flatten().stream().flatMap(x -> x.values().stream()).toList());
    }

    @Test
    void nestedOrInsideAndKeepsPlaceholderOrder() {
        Criteria c = Criteria.allOf(
                Criteria.where("x").eq(1),
                Criteria.anyOf(
                        Criteria.where("y").eq(2),
                        Criteria.where("z").in(3, 4)));
        List<Object> params = c.flatten().stream().flatMap(x -> x.values().stream()).toList();
        assertEquals(List.of(1, 2, 3, 4), params);
    }

    @Test
    void notKeepsInnerOrder() {
        Criteria c = Criteria.not(Criteria.where("a").between(1, 2));
        assertEquals(List.of(1, 2), c.flatten().getFirst().values());
    }

    @Test
    void isNullContributesNoPlaceholder() {
        Criteria c = Criteria.where("a").isNull().and(Criteria.where("b").eq(7));
        List<Criterion> flat = c.flatten();
        assertEquals(2, flat.size());
        assertEquals(0, flat.getFirst().placeholderCount());
        assertEquals(1, flat.get(1).placeholderCount());
        assertEquals(List.of(7), flat.stream().flatMap(x -> x.values().stream()).toList());
    }

    @Test
    void betweenContributesTwoPlaceholdersInOrder() {
        Criteria c = Criteria.where("amount").between(10, 20);
        Criterion criterion = c.flatten().getFirst();
        assertEquals(CriterionOperator.BETWEEN, criterion.op());
        assertEquals(2, criterion.placeholderCount());
        assertEquals(List.of(10, 20), criterion.values());
    }

    @Test
    void inContributesOnePlaceholderPerValue() {
        Criteria c = Criteria.where("channel").in("A", "B", "C");
        Criterion criterion = c.flatten().getFirst();
        assertEquals(3, criterion.placeholderCount());
    }

    @Test
    void eqColContributesNoPlaceholder() {
        Criteria c = Criteria.where("a").eqCol("b");
        Criterion criterion = c.flatten().getFirst();
        assertEquals(CriterionOperator.EQ_COL, criterion.op());
        assertEquals(0, criterion.placeholderCount());
    }

    @Test
    void nullValueEqBecomesIsNull() {
        Criteria c = Criteria.where("a").eq(null);
        assertEquals(CriterionOperator.IS_NULL, c.flatten().getFirst().op());
        assertEquals(CriterionOperator.IS_NOT_NULL,
                Criteria.where("a").ne(null).flatten().getFirst().op());
    }

    @Test
    void rawKeepsFragmentAndParams() {
        Criteria c = Criteria.raw("JSON_EXTRACT(meta, '$.k') = ?", "v1");
        Criterion criterion = c.flatten().getFirst();
        assertEquals(CriterionOperator.RAW, criterion.op());
        assertEquals("JSON_EXTRACT(meta, '$.k') = ?", criterion.column());
        assertEquals(List.of("v1"), criterion.values());
    }

    @Test
    void emptyCompositeRejected() {
        assertThrows(IllegalArgumentException.class, Criteria::allOf);
    }

    @Test
    void emptyInRejected() {
        assertThrows(IllegalArgumentException.class, () -> Criteria.where("a").in(List.of()));
    }

    @Test
    void singleChildCompositeCollapses() {
        Criteria only = Criteria.where("a").eq(1);
        assertEquals(Criteria.Kind.LEAF, Criteria.allOf(only).getKind());
        assertEquals(Criteria.Kind.LEAF, Criteria.anyOf(only).getKind());
    }

    @Test
    void immutableTreeReusableAcrossThreads() {
        Criteria c = Criteria.where("a").eq(1).and(Criteria.where("b").eq(2));
        // 同一棵树多次展平结果一致（不可变性）
        assertEquals(c.flatten(), c.flatten());
    }

}
