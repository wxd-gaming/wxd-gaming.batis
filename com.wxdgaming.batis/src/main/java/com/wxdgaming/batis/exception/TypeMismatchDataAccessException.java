package com.wxdgaming.batis.exception;

/**
 * Java 类型与数据库类型转换失败异常
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class TypeMismatchDataAccessException extends NonTransientDataAccessException {

    public TypeMismatchDataAccessException(String message) {
        super(message);
    }

    public TypeMismatchDataAccessException(String message, Throwable cause) {
        super(message, cause);
    }

    public TypeMismatchDataAccessException(String message, String sql, Object[] params,
                                           int errorCode, String sqlState, Throwable cause) {
        super(message, sql, params, errorCode, sqlState, cause);
    }

}
