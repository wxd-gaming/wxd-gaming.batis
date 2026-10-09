package com.wxdgaming.batis.exception;

/**
 * 未分类 SQL 异常（兜底）
 * <p>方言错误码表与通用规则都无法识别时抛出，保留原始 SQLException 为 cause。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class UncategorizedDataAccessException extends NonTransientDataAccessException {

    public UncategorizedDataAccessException(String message, Throwable cause) {
        super(message, cause);
    }

    public UncategorizedDataAccessException(String message, String sql, Object[] params,
                                            int errorCode, String sqlState, Throwable cause) {
        super(message, sql, params, errorCode, sqlState, cause);
    }

}
