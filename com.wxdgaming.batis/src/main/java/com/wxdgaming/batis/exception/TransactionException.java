package com.wxdgaming.batis.exception;

/**
 * 事务操作异常基类
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class TransactionException extends DataAccessException {

    public TransactionException(String message) {
        super(message);
    }

    public TransactionException(String message, Throwable cause) {
        super(message, cause);
    }

    public TransactionException(String message, String sql, Object[] params,
                                int errorCode, String sqlState, Throwable cause) {
        super(message, sql, params, errorCode, sqlState, cause);
    }

}
