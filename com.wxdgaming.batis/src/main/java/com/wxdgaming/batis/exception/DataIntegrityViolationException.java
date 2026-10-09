package com.wxdgaming.batis.exception;

/**
 * 数据完整性约束违反异常基类
 * <p>唯一键、外键、非空、截断等约束冲突。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class DataIntegrityViolationException extends NonTransientDataAccessException {

    public DataIntegrityViolationException(String message, Throwable cause) {
        super(message, cause);
    }

    public DataIntegrityViolationException(String message, String sql, Object[] params,
                                           int errorCode, String sqlState, Throwable cause) {
        super(message, sql, params, errorCode, sqlState, cause);
    }

}
