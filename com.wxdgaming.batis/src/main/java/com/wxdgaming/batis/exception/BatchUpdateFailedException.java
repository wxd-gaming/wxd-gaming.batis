package com.wxdgaming.batis.exception;

/**
 * 批量更新失败异常
 * <p>携带失败条目在批次中的下标（未知时为 -1），与 {@code BatchResult} 的降级语义协同。</p>
 * <p>
 * 它解决的问题是"批量执行只能告诉你这一批里有一条以上失败，但不告诉你是第几条"：
 * JDBC 的 executeBatch 在部分驱动上抛 BatchUpdateException 时不保证给出可靠的失败下标，
 * 于是这里用 failedIndex 显式表达框架定位到的位置（由批次起始下标加上组内偏移得到），
 * 拿不到就填 -1，表示"知道失败但定位不到条目"。
 * </p>
 * <p>
 * 上层怎么应对：不可重试（继承 {@link NonTransientDataAccessException}）。注意批量中途失败时
 * 前面的语句可能已经生效，所以不能"整批重来"；正确做法是走安全批量
 * （先批量、失败后逐条降级，把每条结果收进 BatchResult 的成功/失败明细），
 * 或直接让整个事务回滚。
 * </p>
 * <p>
 * 现状说明：当前主流程的安全批量（BeanRunner）采用"降级逐条 + BatchResult 收集明细"的方式，
 * 只上抛翻译后的具体子类而不构造本异常，因此本类目前作为批量语义的显式表达保留，
 * 供调用方需要"以异常形式中断整批"时选用。
 * </p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class BatchUpdateFailedException extends NonTransientDataAccessException {

    /** 批次内失败的条目下标，未知为 -1 */
    private final int failedIndex;

    /** 无 SQL 上下文（如参数绑定阶段就失败）时使用 */
    public BatchUpdateFailedException(String message, int failedIndex, Throwable cause) {
        super(message, cause);
        this.failedIndex = failedIndex;
    }

    /**
     * 完整形态：在 {@link DataAccessException} 的诊断字段之上多一个失败下标。
     *
     * @param failedIndex 批次内失败条目的下标，驱动未提供时为 -1
     */
    public BatchUpdateFailedException(String message, String sql, Object[] params,
                                      int errorCode, String sqlState, int failedIndex, Throwable cause) {
        super(message, sql, params, errorCode, sqlState, cause);
        this.failedIndex = failedIndex;
    }

    /** 失败条目下标，-1 表示驱动/框架无法定位到具体哪一条 */
    public int getFailedIndex() {
        return failedIndex;
    }

}
