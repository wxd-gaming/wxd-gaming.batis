package com.wxdgaming.batis.exception;

/**
 * 未知列异常
 * <p>SQL 引用了不存在的列；查询 DSL 本地校验失败时同样抛出此异常。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class UnknownColumnException extends BadSqlGrammarException {

    public UnknownColumnException(String message) {
        super(message, (Throwable) null);
    }

    public UnknownColumnException(String message, Throwable cause) {
        super(message, cause);
    }

    public UnknownColumnException(String message, String sql, Object[] params,
                                  int errorCode, String sqlState, Throwable cause) {
        super(message, sql, params, errorCode, sqlState, cause);
    }

}
