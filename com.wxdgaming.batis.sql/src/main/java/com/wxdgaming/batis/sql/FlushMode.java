package com.wxdgaming.batis.sql;

/**
 * CachedBeanRunner 在事务中的延迟写策略
 * <p>Write-Behind 的脏表是 JVM 全局的，而事务是线程级的，两者需要明确的协同规则。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public enum FlushMode {

    /** 默认：事务内调用延迟写（insertBean(bean,false) 等）自动升级为事务内立即写；回滚时精确失效缓存 */
    TX_IMMEDIATE,
    /** 加入事务：与 TX_IMMEDIATE 相同的写升级语义；事务内 flushAll() 与业务写同批提交，全有或全无 */
    TX_JOIN,
    /** 挂起拒绝：事务内调用延迟写直接抛 IllegalTransactionStateException */
    TX_SUSPEND,
    /** 事务本地缓冲（规划中，暂未实现）：写入线程本地缓冲，提交时并入全局脏表 */
    TX_PENDING_BUFFER

}
