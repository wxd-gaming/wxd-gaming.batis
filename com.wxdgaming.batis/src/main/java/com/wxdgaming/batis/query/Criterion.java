package com.wxdgaming.batis.query;

import java.util.List;

/**
 * 叶子条件：列 + 操作符 + 值
 * <p>RAW 类型时 column 为原始 SQL 片段、values 为绑定参数。</p>
 *
 * @param column 列名（数据库列名或 Java 字段名）；RAW 时为片段文本
 * @param op     操作符
 * @param values 参数值序列（占位符顺序即数组顺序）
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public record Criterion(String column, CriterionOperator op, List<Object> values) {

    public static Criterion of(String column, CriterionOperator op, Object... values) {
        return new Criterion(column, op, List.of(values));
    }

    /** 占位符数量 */
    public int placeholderCount() {
        return switch (op) {
            case IS_NULL, IS_NOT_NULL, EQ_COL -> 0;
            default -> values.size();
        };
    }

}
