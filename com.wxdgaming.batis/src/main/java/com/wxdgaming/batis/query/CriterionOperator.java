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
    /** 原始 SQL 片段逃生舱（有注入风险，仅限受信输入） */
    RAW(null);

    private final String sql;

    CriterionOperator(String sql) {
        this.sql = sql;
    }

    public String getSql() {
        return sql;
    }

}
