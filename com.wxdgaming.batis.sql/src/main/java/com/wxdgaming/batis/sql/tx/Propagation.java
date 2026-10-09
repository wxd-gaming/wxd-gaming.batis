package com.wxdgaming.batis.sql.tx;

/**
 * 事务传播行为
 * <p>P0 支持 REQUIRED / REQUIRES_NEW / SUPPORTS / NOT_SUPPORTED / MANDATORY；
 * NESTED（savepoint）暂未提供。</p>
 * <p>
 * 没实现 NESTED 是有意为之：savepoint 需要每条语句后判断是否回滚到点名，
 * 而 Write-Behind 的脏表按 key 记账、无法跟着 savepoint 一起局部回滚，
 * 真要做必须连缓存语义一起重设计，所以宁可让调用方明确报错，也不给一个"看着能用其实不一致"的档位。
 * </p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public enum Propagation {

    /** 存在事务则加入，否则新建事务（默认） */
    REQUIRED,
    /**
     * 挂起当前事务，新建独立事务（需要连接池至少 2 个可用连接）
     * <p>新事务用<b>另一条</b>连接，所以外层事务持有的那条连接不会被提交：
     * 内层提交的结果外层看不见、也回滚不掉（典型用法是审计日志、序列号取号）。
     * 池子最小时会让内外两层互相等连接，形成自己等自己的死锁。</p>
     */
    REQUIRES_NEW,
    /**
     * 存在事务则加入，否则非事务执行
     * <p>非事务执行时不会 bind 任何持有者，Runner 照常从池借还连接、批量走自己的小事务。
     * 所以"我是不是在事务里"要看 {@code ConnectionHolder.isTransactional()}，不能只看 holder 是否非空。</p>
     */
    SUPPORTS,
    /**
     * 挂起当前事务，非事务执行
     * <p>里面的每条 DML 各自 autocommit：外层回滚救不了它，它失败也不影响外层。
     * 常用于大批量统计写入这类"不希望被业务事务拖长锁时间"的场景。</p>
     */
    NOT_SUPPORTED,
    /** 必须存在事务，否则抛 IllegalTransactionStateException */
    MANDATORY

}
