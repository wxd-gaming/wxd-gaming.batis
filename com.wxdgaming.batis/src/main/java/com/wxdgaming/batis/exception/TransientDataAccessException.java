package com.wxdgaming.batis.exception;

/**
 * 瞬时故障异常基类
 * <p>连接不可用、死锁、锁等待超时、查询超时等场景，重试可能成功。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class TransientDataAccessException extends DataAccessException {

    public TransientDataAccessException(String message) {
        super(message);
    }

    public TransientDataAccessException(String message, Throwable cause) {
        super(message, cause);
    }

    public TransientDataAccessException(String message, String sql, Object[] params,
                                        int errorCode, String sqlState, Throwable cause) {
        super(message, sql, params, errorCode, sqlState, cause);
    }

    @Override
    public boolean isRetryable() {
        return true;
    }

}
