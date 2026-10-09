package com.wxdgaming.batis.sql.tx;

/**
 * 事务同步回调
 * <p>注册到 {@link TransactionTemplate} 事务上，在事务生命周期的关键点被调用；
 * 用于实现"提交后刷盘"、"回滚后缓存失效"等 Write-Behind 与事务的协同语义。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public interface TransactionSynchronization {

    int STATUS_COMMITTED = 0;
    int STATUS_ROLLED_BACK = 1;
    /** 提交结果未知（commit 调用本身失败） */
    int STATUS_UNKNOWN = 2;

    /** 提交前回调（回滚事务不会触发） */
    default void beforeCommit() {
    }

    /** 提交成功后回调 */
    default void afterCommit() {
    }

    /** 事务完成后回调（无论提交或回滚） */
    default void afterCompletion(int status) {
    }

}
