package com.wxdgaming.batis.sql.tx;

/**
 * 事务同步回调
 * <p>注册到 {@link TransactionTemplate} 事务上，在事务生命周期的关键点被调用；
 * 用于实现"提交后刷盘"、"回滚后缓存失效"等 Write-Behind 与事务的协同语义。</p>
 * <p>三个回调的分工不是风格差异而是<b>失败后果不同</b>：
 * {@code beforeCommit} 里出错会连带回滚整个事务（适合做提交前的最后一道校验/补写）；
 * {@code afterCommit} 里出错只记日志，因为数据已经落库、此时回滚也撤不掉（适合发通知、刷缓存）；
 * {@code afterCompletion} 无论成败都跑，适合清理 ThreadLocal、失效缓存这类"必须收尾"的动作。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public interface TransactionSynchronization {

    int STATUS_COMMITTED = 0;
    int STATUS_ROLLED_BACK = 1;
    /**
     * 提交结果未知（commit 调用本身失败）
     * <p>不能归成 ROLLED_BACK：网络抖动下 {@code commit()} 抛异常时数据库可能已经提交了，
     * 把它当"肯定回滚了"会导致缓存失效策略和补偿逻辑做出错误假设。
     * 需要精确结论只能靠幂等重试或对账。</p>
     */
    int STATUS_UNKNOWN = 2;

    /** 提交前回调（回滚事务不会触发；抛异常会连带整笔事务回滚） */
    default void beforeCommit() {
    }

    /** 提交成功后回调（此处异常只记日志，不回滚也不上抛——数据已经不可撤回了） */
    default void afterCommit() {
    }

    /**
     * 事务完成后回调（无论提交或回滚）
     * <p>参数用 {@code STATUS_*} 三个常量之一；Write-Behind 的"回滚后精确失效缓存"就挂在这里。</p>
     */
    default void afterCompletion(int status) {
    }

}
