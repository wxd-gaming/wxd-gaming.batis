package com.wxdgaming.batis.exception;

/**
 * 死锁牺牲者异常
 * <p>数据库检测到死锁并回滚了本事务，重试整个事务通常可以成功。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class DeadlockLoserDataAccessException extends TransientDataAccessException {

    public DeadlockLoserDataAccessException(String message, Throwable cause) {
        super(message, cause);
    }

    public DeadlockLoserDataAccessException(String message, String sql, Object[] params,
                                            int errorCode, String sqlState, Throwable cause) {
        super(message, sql, params, errorCode, sqlState, cause);
    }

}
