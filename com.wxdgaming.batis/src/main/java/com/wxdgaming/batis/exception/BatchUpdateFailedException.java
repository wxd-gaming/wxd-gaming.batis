package com.wxdgaming.batis.exception;

/**
 * 批量更新失败异常
 * <p>携带失败条目在批次中的下标（未知时为 -1），与 {@code BatchResult} 的降级语义协同。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class BatchUpdateFailedException extends NonTransientDataAccessException {

    /** 批次内失败的条目下标，未知为 -1 */
    private final int failedIndex;

    public BatchUpdateFailedException(String message, int failedIndex, Throwable cause) {
        super(message, cause);
        this.failedIndex = failedIndex;
    }

    public BatchUpdateFailedException(String message, String sql, Object[] params,
                                      int errorCode, String sqlState, int failedIndex, Throwable cause) {
        super(message, sql, params, errorCode, sqlState, cause);
        this.failedIndex = failedIndex;
    }

    public int getFailedIndex() {
        return failedIndex;
    }

}
