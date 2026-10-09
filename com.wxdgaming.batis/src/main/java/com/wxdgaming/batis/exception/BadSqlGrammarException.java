package com.wxdgaming.batis.exception;

/**
 * SQL 语法/对象错误异常
 * <p>语法解析失败、表/列不存在等。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class BadSqlGrammarException extends NonTransientDataAccessException {

    public BadSqlGrammarException(String message, Throwable cause) {
        super(message, cause);
    }

    public BadSqlGrammarException(String message, String sql, Object[] params,
                                  int errorCode, String sqlState, Throwable cause) {
        super(message, sql, params, errorCode, sqlState, cause);
    }

}
