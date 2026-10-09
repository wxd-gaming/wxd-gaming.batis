package com.wxdgaming.batis.exception;

/**
 * 非空约束违反异常
 * <p>
 * 什么时候抛出（以翻译器实际映射为准）：
 * <ul>
 *   <li>MySQL 错误码 1048（ER_BAD_NULL_ERROR，向非空列写入 null）与
 *       1364（ER_NO_DEFAULT，非空列既没给值也没有默认值，严格模式下报错）；</li>
 *   <li>SQLState 23/45 类别下消息含 "cannot be null" 或 "not null"，以及同样的关键字兜底。</li>
 * </ul>
 * 两者区别：1048 是显式写了 null，1364 是压根没提供这一列。
 * </p>
 * <p>
 * 上层怎么应对：不可重试。绝大多数情况是实体字段为 null 却落到了 NOT NULL 列上——
 * 要么入参校验漏了，要么 {@code Id} 注解 / {@code Column.nullable()} 与真实 DDL 不一致。
 * 批量写入时它会触发降级逐条，从而在 BatchResult 的失败明细里拿到具体是哪一行、哪个字段。
 * </p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class NotNullConstraintViolationException extends DataIntegrityViolationException {

    /** 合成/包装场景，仅消息与 cause */
    public NotNullConstraintViolationException(String message, Throwable cause) {
        super(message, cause);
    }

    /** 方言翻译器构造，携带原始 SQL/参数/错误码/SQLState */
    public NotNullConstraintViolationException(String message, String sql, Object[] params,
                                               int errorCode, String sqlState, Throwable cause) {
        super(message, sql, params, errorCode, sqlState, cause);
    }

}
