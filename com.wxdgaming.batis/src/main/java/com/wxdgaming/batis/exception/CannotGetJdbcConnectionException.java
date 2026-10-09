package com.wxdgaming.batis.exception;

/**
 * 无法获取数据库连接
 * <p>连接池耗尽、数据库不可达、连接数超限等。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class CannotGetJdbcConnectionException extends TransientDataAccessException {

    public CannotGetJdbcConnectionException(String message, Throwable cause) {
        super(message, cause);
    }

    public CannotGetJdbcConnectionException(String message, String sql, Object[] params,
                                            int errorCode, String sqlState, Throwable cause) {
        super(message, sql, params, errorCode, sqlState, cause);
    }

}
