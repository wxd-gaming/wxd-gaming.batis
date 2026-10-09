package com.wxdgaming.batis.exception;

/**
 * 无法获取锁（锁等待超时）
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class CannotAcquireLockException extends TransientDataAccessException {

    public CannotAcquireLockException(String message, Throwable cause) {
        super(message, cause);
    }

    public CannotAcquireLockException(String message, String sql, Object[] params,
                                      int errorCode, String sqlState, Throwable cause) {
        super(message, sql, params, errorCode, sqlState, cause);
    }

}
