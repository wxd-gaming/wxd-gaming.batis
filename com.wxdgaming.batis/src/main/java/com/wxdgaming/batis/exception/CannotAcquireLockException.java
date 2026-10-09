package com.wxdgaming.batis.exception;

/**
 * 无法获取锁（锁等待超时）
 * <p>
 * 什么时候抛出（以翻译器实际映射为准）：
 * <ul>
 *   <li>MySQL 错误码 1205（ER_LOCK_WAIT_TIMEOUT，Lock wait timeout exceeded）；</li>
 *   <li>消息含 "lock wait timeout" 的关键字兜底（驱动侧 errorCode 为 0 时靠这条命中）。</li>
 * </ul>
 * </p>
 * <p>
 * <b>与 {@link DeadlockLoserDataAccessException} 的区别决定恢复成本</b>：死锁是数据库主动检测环路后
 * 回滚了整个事务；而锁等待超时只是这条语句在 innodb_lock_wait_timeout 内没等到锁，
 * 事务本身没有被回滚，之前的改动仍然有效。因此这里既可能只需重跑当前语句，
 * 也可能需要在重跑前先确认事务状态——不能照搬死锁的"整段重来"。
 * </p>
 * <p>上层怎么应对：属瞬时故障（isRetryable() 为 true），可退避重试；
 * 若稳定复现，通常是持锁方执行过长（大事务、事务里做远程调用）或热点行竞争，
 * 应从缩短事务、拆分热点入手，重试只是掩盖问题。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class CannotAcquireLockException extends TransientDataAccessException {

    /** 合成/非 SQL 场景，仅消息与 cause */
    public CannotAcquireLockException(String message, Throwable cause) {
        super(message, cause);
    }

    /** 方言翻译器构造，携带原始 SQL/参数/错误码/SQLState */
    public CannotAcquireLockException(String message, String sql, Object[] params,
                                      int errorCode, String sqlState, Throwable cause) {
        super(message, sql, params, errorCode, sqlState, cause);
    }

}
