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

    /**
     * 构造叶子条件。
     * <p>值里出现 null 时抛 {@link IllegalArgumentException} 而不是让 {@code List.of} 抛出无消息的 NPE：
     * 只有 {@code IS_NULL / IS_NOT_NULL} 才表达空值语义，其它操作符绑 null 在 SQL 里恒为"未知"，
     * 属于调用方用错了条件类型，报错要能看出是哪一列、哪个操作符。</p>
     */
    public static Criterion of(String column, CriterionOperator op, Object... values) {
        for (Object value : values) {
            if (value == null) {
                throw new IllegalArgumentException(
                        "列 " + column + " 的 " + op + " 条件不接受 null 值，空值判断请用 IS_NULL / IS_NOT_NULL");
            }
        }
        return new Criterion(column, op, List.of(values));
    }

    /**
     * 该叶子贡献的占位符数量。
     * <p>IS_NULL / IS_NOT_NULL 无值、EQ_COL 的右侧是列标识符而非绑定参数，故均为 0；
     * 其余按 values 数量计。渲染端据此校验"值个数 == 占位符个数"，防止参数错位。</p>
     */
    public int placeholderCount() {
        return switch (op) {
            case IS_NULL, IS_NOT_NULL, EQ_COL -> 0;
            default -> values.size();
        };
    }

}
