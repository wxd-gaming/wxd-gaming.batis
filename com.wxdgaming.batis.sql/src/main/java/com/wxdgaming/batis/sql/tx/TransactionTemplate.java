package com.wxdgaming.batis.sql.tx;

import com.wxdgaming.batis.exception.CannotGetJdbcConnectionException;
import com.wxdgaming.batis.exception.IllegalTransactionStateException;
import com.wxdgaming.batis.exception.TransactionStartedException;
import com.wxdgaming.batis.sql.CachedBeanRunner;
import com.wxdgaming.batis.sql.JdbcTemplate;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Consumer;

/**
 * 编程式事务模板
 * <p>用法：</p>
 * <pre>{@code
 * TransactionTemplate tx = template.transactionTemplate();
 * int rows = tx.execute(status -> {
 *     try (BeanRunner br = template.beanRunner()) {
 *         br.insertBean(a);
 *         br.updateBean(b);
 *     }
 *     return 1;
 * });
 * }</pre>
 * <p>事务期间线程内创建的所有 Runner（queryRunner/beanRunner/cachedBeanRunner）
 * 自动复用同一连接；回调抛出任何异常都会回滚；也可 {@link TransactionStatus#setRollbackOnly()} 主动标记。</p>
 * <p><b>注意</b>：事务是线程绑定的，不要在回调内跨线程执行需要参与事务的操作；
 * {@link Propagation#REQUIRES_NEW} 需要连接池至少 2 个可用连接，否则可能死锁等待。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class TransactionTemplate {

    private static final Logger log = LoggerFactory.getLogger(TransactionTemplate.class);

    private final JdbcTemplate jdbcTemplate;
    /** 隔离级别，DEFAULT 不主动设置 */
    @Setter @Getter private Isolation isolation = Isolation.DEFAULT;
    /** 只读事务：事务内写操作将抛 IllegalTransactionStateException */
    @Setter @Getter private boolean readOnly = false;
    /** 语句级超时（秒），0 表示不限制；作用于事务内创建的每个 Statement */
    @Setter @Getter private int timeoutSeconds = 0;

    public TransactionTemplate(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 以 REQUIRED 传播执行事务 */
    public <T> T execute(TransactionCallback<T> callback) {
        return execute(Propagation.REQUIRED, callback);
    }

    /** 以 REQUIRED 传播执行无返回值事务 */
    public void executeWithoutResult(Consumer<TransactionStatus> callback) {
        execute(status -> {
            callback.accept(status);
            return null;
        });
    }

    /**
     * 事务提交后自动刷盘 CachedBeanRunner 的脏数据（推荐的高吞吐姿势：
     * 业务事务时长不受刷盘影响，且不会把其他线程的脏数据卷入本事务）。
     */
    public <T> T executeAndFlush(CachedBeanRunner runner, TransactionCallback<T> callback) {
        return execute(status -> {
            ConnectionHolder holder = TxContext.get(jdbcTemplate);
            if (holder != null) {
                TxContext.register(holder, new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        runner.flushAll();
                    }
                });
            }
            return callback.doInTransaction(status);
        });
    }

    /** 按指定传播行为执行事务 */
    public <T> T execute(Propagation propagation, TransactionCallback<T> callback) {
        ConnectionHolder existing = TxContext.get(jdbcTemplate);
        boolean active = existing != null && existing.isTransactional();
        switch (propagation) {
            case MANDATORY:
                if (!active) {
                    throw new IllegalTransactionStateException("MANDATORY 传播行为要求当前线程已存在事务");
                }
                return participate(existing, callback);
            case REQUIRED:
                return active ? participate(existing, callback) : executeNew(callback);
            case SUPPORTS:
                return active ? participate(existing, callback)
                        : callback.doInTransaction(new Status(null, false, false, false));
            case REQUIRES_NEW: {
                ConnectionHolder suspended = existing;
                if (suspended != null) TxContext.unbind(jdbcTemplate);
                try {
                    return executeNew(callback);
                } finally {
                    if (suspended != null) TxContext.bind(jdbcTemplate, suspended);
                }
            }
            case NOT_SUPPORTED: {
                ConnectionHolder suspended = existing;
                if (suspended != null) TxContext.unbind(jdbcTemplate);
                try {
                    return callback.doInTransaction(new Status(null, false, false, false));
                } finally {
                    if (suspended != null) TxContext.bind(jdbcTemplate, suspended);
                }
            }
            default:
                throw new IllegalTransactionStateException("不支持的传播行为: " + propagation);
        }
    }

    /** 加入已有事务：不提交不回滚，异常只标记全局 rollbackOnly，由开启事务的一层统一回滚 */
    private <T> T participate(ConnectionHolder holder, TransactionCallback<T> callback) {
        Status status = new Status(holder, false, holder.isTransactional(), holder.isReadOnly());
        try {
            return callback.doInTransaction(status);
        } catch (Throwable ex) {
            holder.setRollbackOnly();
            throw ex;
        }
    }

    /** 开启新事务并执行 */
    private <T> T executeNew(TransactionCallback<T> callback) {
        ConnectionHolder holder = doBegin();
        TxContext.bind(jdbcTemplate, holder);
        Status status = new Status(holder, true, true, holder.isReadOnly());
        try {
            T result = callback.doInTransaction(status);
            if (holder.isRollbackOnly()) {
                rollback(holder);
            } else {
                commit(holder);
            }
            return result;
        } catch (Throwable ex) {
            try {
                rollback(holder);
            } catch (RuntimeException rollbackEx) {
                ex.addSuppressed(rollbackEx);
            }
            throw ex;
        } finally {
            TxContext.unbind(jdbcTemplate);
            release(holder);
        }
    }

    private ConnectionHolder doBegin() {
        Connection conn;
        try {
            conn = jdbcTemplate.getDataSource().getConnection();
        } catch (SQLException e) {
            throw new CannotGetJdbcConnectionException("获取事务连接失败: " + e.getMessage(), e);
        }
        boolean prevAutoCommit;
        try {
            prevAutoCommit = conn.getAutoCommit();
            Integer jdbcIsolation = isolation.toJdbcConstant();
            if (jdbcIsolation != null) conn.setTransactionIsolation(jdbcIsolation);
            if (readOnly) conn.setReadOnly(true);
            conn.setAutoCommit(false);
        } catch (SQLException e) {
            try {
                conn.close();
            } catch (SQLException ignored) {
            }
            throw new TransactionStartedException("开启事务失败: " + e.getMessage(), e);
        }
        ConnectionHolder holder = new ConnectionHolder(conn, prevAutoCommit, readOnly, true);
        holder.setQueryTimeoutSeconds(timeoutSeconds);
        log.debug("事务开始: isolation={}, readOnly={}, timeout={}s", isolation, readOnly, timeoutSeconds);
        return holder;
    }

    private void commit(ConnectionHolder holder) {
        try {
            for (TransactionSynchronization sync : holder.getSynchronizations()) {
                sync.beforeCommit();
            }
            holder.getConnection().commit();
        } catch (SQLException | RuntimeException e) {
            log.warn("事务提交失败，执行回滚: {}", e.getMessage());
            try {
                holder.getConnection().rollback();
            } catch (SQLException ignored) {
            }
            triggerAfterCompletion(holder, TransactionSynchronization.STATUS_UNKNOWN);
            if (e instanceof RuntimeException re) throw re;
            throw new TransactionStartedException("事务提交失败: " + e.getMessage(), e);
        }
        for (TransactionSynchronization sync : holder.getSynchronizations()) {
            try {
                sync.afterCommit();
            } catch (Exception e) {
                log.warn("afterCommit 回调异常: {}", e.getMessage());
            }
        }
        triggerAfterCompletion(holder, TransactionSynchronization.STATUS_COMMITTED);
        log.debug("事务提交");
    }

    private void rollback(ConnectionHolder holder) {
        try {
            holder.getConnection().rollback();
        } catch (SQLException e) {
            log.warn("事务回滚失败: {}", e.getMessage());
        }
        triggerAfterCompletion(holder, TransactionSynchronization.STATUS_ROLLED_BACK);
        log.debug("事务回滚");
    }

    private void triggerAfterCompletion(ConnectionHolder holder, int status) {
        for (TransactionSynchronization sync : holder.getSynchronizations()) {
            try {
                sync.afterCompletion(status);
            } catch (Exception e) {
                log.warn("afterCompletion 回调异常: {}", e.getMessage());
            }
        }
    }

    /** 恢复连接原始状态并归还池中 */
    private void release(ConnectionHolder holder) {
        Connection conn = holder.getConnection();
        try {
            if (holder.isReadOnly()) conn.setReadOnly(false);
            conn.setAutoCommit(holder.getPreviousAutoCommit());
        } catch (SQLException e) {
            log.warn("归还事务连接前恢复状态失败: {}", e.getMessage());
        } finally {
            try {
                conn.close();
            } catch (SQLException e) {
                log.warn("事务连接归还失败: {}", e.getMessage());
            }
        }
    }

    /** TransactionStatus 默认实现 */
    private static class Status implements TransactionStatus {
        private final ConnectionHolder holder;
        private final boolean newTransaction;
        private final boolean transactional;
        private final boolean readOnly;

        Status(ConnectionHolder holder, boolean newTransaction, boolean transactional, boolean readOnly) {
            this.holder = holder;
            this.newTransaction = newTransaction;
            this.transactional = transactional;
            this.readOnly = readOnly;
        }

        @Override
        public void setRollbackOnly() {
            if (holder == null) {
                throw new IllegalTransactionStateException("非事务执行体无法标记回滚");
            }
            holder.setRollbackOnly();
        }

        @Override
        public boolean isRollbackOnly() {
            return holder != null && holder.isRollbackOnly();
        }

        @Override
        public boolean isNewTransaction() {
            return newTransaction;
        }

        @Override
        public boolean isReadOnly() {
            return readOnly;
        }

        @Override
        public boolean isTransactional() {
            return transactional;
        }
    }

}
