package com.wxdgaming.batis.exception;

/**
 * 事务开启/提交/回滚自身失败
 * <p>
 * 实际抛出点（事务模板内部，均来自连接层操作失败，不是 SQL 执行失败）：
 * <ul>
 *   <li>doBegin 里设置隔离级别、readOnly、setAutoCommit(false) 抛 SQLException——
 *       此时连接已从池借到但没能配成事务形态，模板会先把连接关掉再抛，避免泄漏；</li>
 *   <li>commit 里 beforeCommit 回调或 connection.commit() 失败——模板会先尝试回滚补偿、
 *       触发 afterCompletion，然后才抛本异常。</li>
 * </ul>
 * 注意类名只写了 Started，实际覆盖"事务的建立与解除"两侧；而回滚失败并不会抛这里——
 * 模板的 rollback() 只记 warn（回滚本身失败已经没有更好的补救动作，再抛异常会覆盖原始病因）。
 * </p>
 * <p>
 * 与 {@link CannotGetJdbcConnectionException} 的分界：连接拿不到（借不出）归那边，属瞬时；
 * 连接拿到了但事务状态设不上或提交不掉归本类。
 * </p>
 * <p>
 * 上层怎么应对：不要自动重试整段业务。提交失败尤其危险——业务语句可能已全部执行成功，
 * 只是没落库；也可能提交实际已完成但响应丢失。盲目重跑会造成重复写，
 * 正确做法是记录现场并按业务幂等键核对数据实际状态。（本类继承 TransactionException，
 * isRetryable() 为 false，正是这个意思。）
 * </p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class TransactionStartedException extends TransactionException {

    /** 事务边界操作失败：cause 为连接层抛出的原始 SQLException */
    public TransactionStartedException(String message, Throwable cause) {
        super(message, cause);
    }

}
