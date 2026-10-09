package com.wxdgaming.batis.sql.tx;

/**
 * 事务状态句柄
 * <p>回调内可查询当前事务状态并标记回滚。</p>
 * <p>
 * 这里<b>故意没有 commit()/rollback()</b>：事务的生命周期归 {@link TransactionTemplate} 所有，
 * 回调只能"表态"不能"动手"。参与内层事务的代码若真去提交，会把外层尚未完成的工作一起钉死，
 * 外层随后回滚也救不回来——这正是本框架修掉的那类"静默提前提交"事故。
 * </p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public interface TransactionStatus {

    /**
     * 标记当前事务只能回滚
     * <p>加入已有事务时，标记的是<b>全局</b>回滚标志（最外层提交前统一检查），
     * 而不是"只有我这一段作废"——JDBC 事务没有部分回滚，能做的只有让整笔失效。</p>
     * <p>非事务执行体（SUPPORTS/NOT_SUPPORTED 在无事务时）调用它会抛异常：
     * 此时每句话都已各自提交，"回滚"是个无法兑现的承诺，静默忽略比假装有效更安全。</p>
     */
    void setRollbackOnly();

    /** 是否已标记为只能回滚（外层可用它提前跳出，省掉后续无用的写库） */
    boolean isRollbackOnly();

    /** 本次回调是否开启了新事务（加入已有事务时为 false；只有开新事务的那层负责提交） */
    boolean isNewTransaction();

    /** 是否只读事务（写操作会被 Runner 拦住并抛 IllegalTransactionStateException） */
    boolean isReadOnly();

    /** 是否在事务中（非事务执行体为 false，用它决定批量写要不要挂到外层事务上） */
    boolean isTransactional();

}
