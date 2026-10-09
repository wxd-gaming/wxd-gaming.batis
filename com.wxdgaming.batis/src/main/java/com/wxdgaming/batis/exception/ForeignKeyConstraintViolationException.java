package com.wxdgaming.batis.exception;

/**
 * 外键约束违反异常
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class ForeignKeyConstraintViolationException extends DataIntegrityViolationException {

    public ForeignKeyConstraintViolationException(String message, Throwable cause) {
        super(message, cause);
    }

    public ForeignKeyConstraintViolationException(String message, String sql, Object[] params,
                                                  int errorCode, String sqlState, Throwable cause) {
        super(message, sql, params, errorCode, sqlState, cause);
    }

}
