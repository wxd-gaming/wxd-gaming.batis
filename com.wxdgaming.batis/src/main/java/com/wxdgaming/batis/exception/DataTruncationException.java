package com.wxdgaming.batis.exception;

/**
 * 数据截断/越界异常
 * <p>列长度不足、数值超出列类型范围等。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class DataTruncationException extends DataIntegrityViolationException {

    public DataTruncationException(String message, Throwable cause) {
        super(message, cause);
    }

    public DataTruncationException(String message, String sql, Object[] params,
                                   int errorCode, String sqlState, Throwable cause) {
        super(message, sql, params, errorCode, sqlState, cause);
    }

}
