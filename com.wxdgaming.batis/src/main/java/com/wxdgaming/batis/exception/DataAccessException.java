package com.wxdgaming.batis.exception;

/**
 * 数据库访问异常基类
 * <p>所有由框架抛出的数据库相关异常都继承此类，携带触发异常的 SQL、绑定参数、
 * 数据库原生错误码与 SQLState，便于上层做重试/降级/告警决策。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class DataAccessException extends RuntimeException {

    /** 触发异常的 SQL 语句，可为 null */
    private final String sql;
    /** 绑定参数，可为 null */
    private final Object[] params;
    /** 数据库原生错误码，未知为 0 */
    private final int errorCode;
    /** SQLState，未知为 null */
    private final String sqlState;

    public DataAccessException(String message) {
        this(message, null, null, 0, null, null);
    }

    public DataAccessException(String message, Throwable cause) {
        this(message, null, null, 0, null, cause);
    }

    public DataAccessException(String message, String sql, Object[] params,
                               int errorCode, String sqlState, Throwable cause) {
        super(message, cause);
        this.sql = sql;
        this.params = params;
        this.errorCode = errorCode;
        this.sqlState = sqlState;
    }

    public String getSql() {
        return sql;
    }

    public Object[] getParams() {
        return params;
    }

    public int getErrorCode() {
        return errorCode;
    }

    public String getSqlState() {
        return sqlState;
    }

    /** 是否可重试（瞬时故障子类返回 true） */
    public boolean isRetryable() {
        return false;
    }

}
