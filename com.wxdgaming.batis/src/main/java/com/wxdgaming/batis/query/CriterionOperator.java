package com.wxdgaming.batis.query;

/**
 * 条件操作符
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public enum CriterionOperator {

    EQ("="),
    NE("<>"),
    GT(">"),
    GE(">="),
    LT("<"),
    LE("<="),
    // 三种 LIKE 的 SQL 关键字相同，模糊差异只体现在绑定值的通配符位置（由 CriteriaBuilder 拼接）
    LIKE("LIKE"),
    LEFT_LIKE("LIKE"),
    RIGHT_LIKE("LIKE"),
    IN("IN"),
    NOT_IN("NOT IN"),
    BETWEEN("BETWEEN"),
    IS_NULL("IS NULL"),
    IS_NOT_NULL("IS NOT NULL"),
    /** 列对列相等比较，右侧为另一个列名（非参数） */
    EQ_COL("="),
    /** 原始 SQL 片段逃生舱（有注入风险，仅限受信输入）；无操作符 token，故 sql 为 null */
    RAW(null);

    private final String sql;

    CriterionOperator(String sql) {
        this.sql = sql;
    }

    public String getSql() {
        return sql;
    }

}
