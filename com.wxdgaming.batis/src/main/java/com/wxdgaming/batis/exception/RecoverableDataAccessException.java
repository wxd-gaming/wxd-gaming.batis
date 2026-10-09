package com.wxdgaming.batis.exception;

/**
 * 连接中断类异常
 * <p>执行过程中连接断开（Communications link failure），重连后重试可能成功。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class RecoverableDataAccessException extends TransientDataAccessException {

    public RecoverableDataAccessException(String message, Throwable cause) {
        super(message, cause);
    }

    public RecoverableDataAccessException(String message, String sql, Object[] params,
                                          int errorCode, String sqlState, Throwable cause) {
        super(message, sql, params, errorCode, sqlState, cause);
    }

}
