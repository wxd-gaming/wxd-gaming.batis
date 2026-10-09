package com.wxdgaming.batis.sql.tx;

import java.sql.Connection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 事务连接持有者
 * <p>绑定到当前线程（见 {@link TxContext}），事务期间所有 Runner 从这里取连接，
 * 保证同一线程的多次操作落在同一个 JDBC 事务上。内部使用，业务代码不应直接构造。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class ConnectionHolder {

    private final Connection connection;
    /** begin 之前的 autoCommit 值，完成时恢复 */
    private final boolean previousAutoCommit;
    /** 事务内被直接写过 DB 的缓存 key（供回滚时精确失效缓存） */
    private final Set<String> cacheKeysWritten = ConcurrentHashMap.newKeySet();
    /** 事务同步回调列表 */
    private final List<TransactionSynchronization> synchronizations = new CopyOnWriteArrayList<>();
    /** 是否只读事务 */
    private final boolean readOnly;
    /** 语句级超时（秒），0 表示不限制 */
    private int queryTimeoutSeconds;
    /** 全局回滚标志（参与事务的内部标记的也是它） */
    private volatile boolean rollbackOnly;
    /** 是否开启了真实事务（SUPPORTS 非事务执行时为 false） */
    private final boolean transactional;

    public ConnectionHolder(Connection connection, boolean previousAutoCommit,
                            boolean readOnly, boolean transactional) {
        this.connection = connection;
        this.previousAutoCommit = previousAutoCommit;
        this.readOnly = readOnly;
        this.transactional = transactional;
    }

    public Connection getConnection() {
        return connection;
    }

    public boolean getPreviousAutoCommit() {
        return previousAutoCommit;
    }

    public Set<String> getCacheKeysWritten() {
        return cacheKeysWritten;
    }

    public List<TransactionSynchronization> getSynchronizations() {
        return synchronizations;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public boolean isTransactional() {
        return transactional;
    }

    public int getQueryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }

    public void setQueryTimeoutSeconds(int queryTimeoutSeconds) {
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    public boolean isRollbackOnly() {
        return rollbackOnly;
    }

    public void setRollbackOnly() {
        this.rollbackOnly = true;
    }

}
