package com.wxdgaming.batis.query;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * 单列条件构造器
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public final class CriteriaBuilder {

    private final String column;

    CriteriaBuilder(String column) {
        this.column = column;
    }

    /** 相等；value 为 null 时自动转为 IS NULL */
    public Criteria eq(Object value) {
        return value == null ? isNull() : leaf(CriterionOperator.EQ, value);
    }

    /** 不等；value 为 null 时自动转为 IS NOT NULL */
    public Criteria ne(Object value) {
        return value == null ? isNotNull() : leaf(CriterionOperator.NE, value);
    }

    public Criteria gt(Object value) {
        return leaf(CriterionOperator.GT, value);
    }

    public Criteria ge(Object value) {
        return leaf(CriterionOperator.GE, value);
    }

    public Criteria lt(Object value) {
        return leaf(CriterionOperator.LT, value);
    }

    public Criteria le(Object value) {
        return leaf(CriterionOperator.LE, value);
    }

    /** 全模糊 LIKE %pattern% */
    public Criteria like(String pattern) {
        return leaf(CriterionOperator.LIKE, "%" + pattern + "%");
    }

    /** 左模糊 LIKE %pattern */
    public Criteria leftLike(String pattern) {
        return leaf(CriterionOperator.LEFT_LIKE, "%" + pattern);
    }

    /** 右模糊 LIKE pattern% */
    public Criteria rightLike(String pattern) {
        return leaf(CriterionOperator.RIGHT_LIKE, pattern + "%");
    }

    public Criteria in(Collection<?> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("IN 条件值不能为空，空集合场景请直接短路业务逻辑");
        }
        return Criteria.of(new Criterion(column, CriterionOperator.IN, List.copyOf(values)));
    }

    public Criteria in(Object... values) {
        return in(Arrays.asList(values));
    }

    public Criteria notIn(Collection<?> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("NOT IN 条件值不能为空");
        }
        return Criteria.of(new Criterion(column, CriterionOperator.NOT_IN, List.copyOf(values)));
    }

    public Criteria notIn(Object... values) {
        return notIn(Arrays.asList(values));
    }

    public Criteria between(Object lo, Object hi) {
        return leaf(CriterionOperator.BETWEEN, lo, hi);
    }

    public Criteria isNull() {
        return Criteria.of(new Criterion(column, CriterionOperator.IS_NULL, List.of()));
    }

    public Criteria isNotNull() {
        return Criteria.of(new Criterion(column, CriterionOperator.IS_NOT_NULL, List.of()));
    }

    /** 列对列比较（右侧为另一个列名，走标识符而非参数绑定） */
    public Criteria eqCol(String otherColumn) {
        return Criteria.of(new Criterion(column, CriterionOperator.EQ_COL, List.of(otherColumn)));
    }

    private Criteria leaf(CriterionOperator op, Object... values) {
        return Criteria.of(new Criterion(column, op, List.of(values)));
    }

}
