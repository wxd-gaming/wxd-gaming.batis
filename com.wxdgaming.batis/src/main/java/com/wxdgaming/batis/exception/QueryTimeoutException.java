package com.wxdgaming.batis.exception;

/**
 * 查询超时异常
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class QueryTimeoutException extends TransientDataAccessException {

    public QueryTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }

    public QueryTimeoutException(String message, String sql, Object[] params,
                                 int errorCode, String sqlState, Throwable cause) {
        super(message, sql, params, errorCode, sqlState, cause);
    }

}
