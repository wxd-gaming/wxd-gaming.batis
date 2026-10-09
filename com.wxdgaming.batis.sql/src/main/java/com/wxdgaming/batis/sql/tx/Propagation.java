package com.wxdgaming.batis.sql.tx;

/**
 * 事务传播行为
 * <p>P0 支持 REQUIRED / REQUIRES_NEW / SUPPORTS / NOT_SUPPORTED / MANDATORY；
 * NESTED（savepoint）暂未提供。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public enum Propagation {

    /** 存在事务则加入，否则新建事务（默认） */
    REQUIRED,
    /** 挂起当前事务，新建独立事务（需要连接池至少 2 个可用连接） */
    REQUIRES_NEW,
    /** 存在事务则加入，否则非事务执行 */
    SUPPORTS,
    /** 挂起当前事务，非事务执行 */
    NOT_SUPPORTED,
    /** 必须存在事务，否则抛 IllegalTransactionStateException */
    MANDATORY

}
