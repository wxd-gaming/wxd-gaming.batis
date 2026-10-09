package com.wxdgaming.batis.exception;

/**
 * 非瞬时故障异常基类
 * <p>SQL 语法错误、约束冲突、数据截断等场景，重试无意义，需要修正数据或语句。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class NonTransientDataAccessException extends DataAccessException {

    public NonTransientDataAccessException(String message) {
        super(message);
    }

    public NonTransientDataAccessException(String message, Throwable cause) {
        super(message, cause);
    }

    public NonTransientDataAccessException(String message, String sql, Object[] params,
                                           int errorCode, String sqlState, Throwable cause) {
        super(message, sql, params, errorCode, sqlState, cause);
    }

}
