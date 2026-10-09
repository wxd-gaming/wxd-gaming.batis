package com.wxdgaming.batis.exception;

/**
 * 连接中断类异常
 * <p>执行过程中连接断开（Communications link failure），重连后重试可能成功。</p>
 * <p>
 * 什么时候抛出（以翻译器实际映射为准）：
 * <ul>
 *   <li>SQLState 为 08S01（通信链路异常）；</li>
 *   <li>SQLState 以 08 开头且消息含 "communications link failure"；</li>
 *   <li>消息含 "communications link failure" 或 "connection closed" 的关键字兜底。</li>
 * </ul>
 * 与 {@link CannotGetJdbcConnectionException} 的分界很明确：后者是"连接拿不到"，
 * 本类是"连接拿到了、执行到一半断了"。
 * </p>
 * <p>
 * 上层怎么应对：重新借一条连接再试通常就会成功（池里那条是坏连接，close 后不会被复用）。
 * 但要点是<b>已执行语句的状态未知</b>——断开发生在响应回来之前，事务未提交的话会被服务端回滚，
 * 而 autocommit 下的写入可能已经落库。因此幂等的读/更新可直接重试，非幂等的 INSERT 重试前
 * 必须有唯一键或先查后插，否则就是重复数据。
 * </p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class RecoverableDataAccessException extends TransientDataAccessException {

    /** 合成/非 SQL 场景，仅消息与 cause */
    public RecoverableDataAccessException(String message, Throwable cause) {
        super(message, cause);
    }

    /** 方言翻译器构造，携带原始 SQL/参数/错误码/SQLState */
    public RecoverableDataAccessException(String message, String sql, Object[] params,
                                          int errorCode, String sqlState, Throwable cause) {
        super(message, sql, params, errorCode, sqlState, cause);
    }

}
