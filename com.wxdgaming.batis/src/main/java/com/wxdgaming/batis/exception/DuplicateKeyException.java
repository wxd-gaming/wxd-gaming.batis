package com.wxdgaming.batis.exception;

/**
 * 唯一键/主键冲突异常
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class DuplicateKeyException extends DataIntegrityViolationException {

    public DuplicateKeyException(String message, Throwable cause) {
        super(message, cause);
    }

    public DuplicateKeyException(String message, String sql, Object[] params,
                                 int errorCode, String sqlState, Throwable cause) {
        super(message, sql, params, errorCode, sqlState, cause);
    }

}
