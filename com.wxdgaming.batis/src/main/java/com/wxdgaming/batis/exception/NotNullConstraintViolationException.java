package com.wxdgaming.batis.exception;

/**
 * 非空约束违反异常
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class NotNullConstraintViolationException extends DataIntegrityViolationException {

    public NotNullConstraintViolationException(String message, Throwable cause) {
        super(message, cause);
    }

    public NotNullConstraintViolationException(String message, String sql, Object[] params,
                                               int errorCode, String sqlState, Throwable cause) {
        super(message, sql, params, errorCode, sqlState, cause);
    }

}
