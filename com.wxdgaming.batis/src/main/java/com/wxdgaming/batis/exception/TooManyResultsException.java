package com.wxdgaming.batis.exception;

/**
 * 期望单条结果但命中多条
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class TooManyResultsException extends NonTransientDataAccessException {

    public TooManyResultsException(String message) {
        super(message);
    }

    public TooManyResultsException(String message, Throwable cause) {
        super(message, cause);
    }

}
