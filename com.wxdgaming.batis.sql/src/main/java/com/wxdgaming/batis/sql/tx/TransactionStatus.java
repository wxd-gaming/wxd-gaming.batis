package com.wxdgaming.batis.sql.tx;

/**
 * 事务状态句柄
 * <p>回调内可查询当前事务状态并标记回滚。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public interface TransactionStatus {

    /** 标记当前事务只能回滚（加入事务时标记的是全局回滚标志） */
    void setRollbackOnly();

    /** 是否已标记为只能回滚 */
    boolean isRollbackOnly();

    /** 本次回调是否开启了新事务（加入已有事务时为 false） */
    boolean isNewTransaction();

    /** 是否只读事务 */
    boolean isReadOnly();

    /** 是否在事务中（非事务执行体为 false） */
    boolean isTransactional();

}
