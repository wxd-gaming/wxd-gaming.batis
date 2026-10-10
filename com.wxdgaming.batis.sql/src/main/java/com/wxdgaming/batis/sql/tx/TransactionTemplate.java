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
 * <p>
 * 实现要点只有一个：<b>一条连接 + 一个线程上下文槽位</b>。begin 时从池里借一条连接关掉 autoCommit，
 * 把 {@link ConnectionHolder} 绑到 {@link TxContext}；期间任何 Runner 经
 * {@code JdbcTemplate#getConnection()} 拿到的都是这条连接；完成时提交或回滚、解绑、恢复连接状态再归还。
 * 因此"事务边界"等价于"这段回调 + 这个线程"，跨线程池提交的任务一律在事务之外。
 * </p>
 * <p>本对象是轻量配置载体（isolation/readOnly/timeout），可以每次现建，也可以长期复用；
 * 但三个配置字段本身不是线程安全的（有事务在跑时改配置会影响该事务后续语句），请按启动期配置对待。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class TransactionTemplate {

    private static final Logger log = LoggerFactory.getLogger(TransactionTemplate.class);

    /** 事务绑定的目标模板：同时也是 TxContext 里区分不同数据源的 key */
    private final JdbcTemplate jdbcTemplate;
    /** 隔离级别，DEFAULT 不主动设置（不调 setTransactionIsolation，沿用连接/数据库配置） */
    @Setter @Getter private Isolation isolation = Isolation.DEFAULT;
    /** 只读事务：连接层面置 readOnly，且每次写操作前由 QueryRunner#checkWritable 拦下 */
    @Setter @Getter private boolean readOnly = false;
    /** 语句级超时（秒），0 表示不限制；经 ConnectionHolder 传给事务内创建的每个 Statement */
    @Setter @Getter private int timeoutSeconds = 0;

    public TransactionTemplate(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 以 REQUIRED 传播执行事务（最常用的入口） */
    public <T> T execute(TransactionCallback<T> callback) {
        return execute(Propagation.REQUIRED, callback);
    }

    /** 以 REQUIRED 传播执行无返回值事务（返回值统一置 null，避免回调里硬写 return null） */
    public void executeWithoutResult(Consumer<TransactionStatus> callback) {
        execute(status -> {
            callback.accept(status);
            return null;
        });
    }

    /**
     * 事务提交后自动刷盘 CachedBeanRunner 的脏数据（推荐的高吞吐姿势）。
     * <p>为什么放在 afterCommit 而不是事务内：脏表是 JVM 全局共享的，事务内 flushAll
     * 会把<b>其他线程</b>的待写数据一并卷进本事务，本事务回滚时它们的写入也一起消失；
     * 放在提交后再刷，业务事务时长也不受批量刷盘影响。代价是"提交成功但进程立刻崩溃"
     * 这一窗口内脏数据会丢失，属于 Write-Behind 的固有权衡。</p>
     * <p>若需要强一致（回滚也不丢），改用事务内 immediate 写或直接走 BeanRunner。</p>
     */
    public <T> T executeAndFlush(CachedBeanRunner runner, TransactionCallback<T> callback) {
        return execute(status -> {
            ConnectionHolder holder = TxContext.get(jdbcTemplate);
            // holder 理论上必非空（REQUIRED 已保证事务存在）；为空时退化为"不刷盘"，交由 runner 自身的定时刷盘兜底
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

    /**
     * 按指定传播行为执行事务
     * <p>
     * "加入"与"新建"的分野就在 {@code active} 这一个判断上：加入时绝不提交也绝不回滚，
     * 只把异常转成全局 rollbackOnly 标记，把决定权留给开启事务的那一层。
     * 内层若擅自 commit，外层尚未完成的写就被一起钉死了——这正是本框架修掉的"批量写提前提交外层事务"问题。
     * </p>
     * <p>
     * {@code SUPPORTS}/{@code NOT_SUPPORTED} 在无事务时用的是 {@code Status(null, ...)}：
     * holder 为 null 就代表"非事务执行体"，此时调 {@code setRollbackOnly()} 会明确抛异常，
     * 而不是让人误以为标记生效了。
     * </p>
     */
    public <T> T execute(Propagation propagation, TransactionCallback<T> callback) {
        ConnectionHolder existing = TxContext.get(jdbcTemplate);
        // 必须同时判 isTransactional()：只挂载不参与事务的 holder 不能被视为"已在事务中"
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
                // 挂起 = 从线程上下文摘掉旧 holder，让新建的事务看到"没有外层事务"；
                // finally 里必须原样绑回去，否则外层后续语句会去池里另借一条连接，写进另一个事务里
                ConnectionHolder suspended = existing;
                if (suspended != null) TxContext.unbind(jdbcTemplate);
                try {
                    return executeNew(callback);
                } finally {
                    if (suspended != null) TxContext.bind(jdbcTemplate, suspended);
                }
            }
            case NOT_SUPPORTED: {
                // 与 REQUIRES_NEW 同样的挂起/恢复，只是不建新事务：里面的每条 DML 各自 autocommit
                ConnectionHolder suspended = existing;
                if (suspended != null) TxContext.unbind(jdbcTemplate);
                try {
                    return callback.doInTransaction(new Status(null, false, false, false));
                } finally {
                    if (suspended != null) TxContext.bind(jdbcTemplate, suspended);
                }
            }
            case NEVER:
                // 直接拒绝而非像 NOT_SUPPORTED 那样挂起后静默执行：这段逻辑绝不允许被外层事务卷入
                if (active) {
                    throw new IllegalTransactionStateException("NEVER 传播行为要求当前线程不存在事务");
                }
                return callback.doInTransaction(new Status(null, false, false, false));
            default:
                // NESTED 等未实现的档位落到这里：明确报错优于静默按 REQUIRED 执行
                throw new IllegalTransactionStateException("不支持的传播行为: " + propagation);
        }
    }

    /**
     * 加入已有事务：不提交不回滚，异常只标记全局 rollbackOnly，由开启事务的一层统一回滚
     * <p>{@code newTransaction=false} 是关键：它让内层的 {@code finally} 不会去碰连接，
     * 也让 Write-Behind 知道"这批写要等外层的结果"。</p>
     */
    private <T> T participate(ConnectionHolder holder, TransactionCallback<T> callback) {
        Status status = new Status(holder, false, holder.isTransactional(), holder.isReadOnly());
        try {
            return callback.doInTransaction(status);
        } catch (Throwable ex) {
            // 抓 Throwable 而不是 Exception：Error（如 OOM、断言失败）同样必须让这笔事务作废
            holder.setRollbackOnly();
            throw ex;
        }
    }

    /**
     * 开启新事务并执行
     * <p>顺序上有个刻意的安排：{@code unbind} 与 {@code release} 都放在 finally，
     * 且先解绑再归还连接。反过来的话，归还到池里的连接在被别的线程借走的那一刻，
     * 本线程上下文里还留着它，后续语句会写进一条已经不属于我的连接。</p>
     */
    private <T> T executeNew(TransactionCallback<T> callback) {
        ConnectionHolder holder = doBegin();
        TxContext.bind(jdbcTemplate, holder);
        Status status = new Status(holder, true, true, holder.isReadOnly());
        try {
            T result = callback.doInTransaction(status);
            // 正常返回也可能被要求回滚（setRollbackOnly），所以提交前必须再查一次标志
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
                // 回滚本身失败不覆盖原始异常：业务异常才是现场，回滚异常挂成 suppressed 供排障
                ex.addSuppressed(rollbackEx);
            }
            throw ex;
        } finally {
            TxContext.unbind(jdbcTemplate);
            release(holder);
        }
    }

    /**
     * 借连接并把它改造成事务连接
     * <p>
     * 三步的顺序不能换：先记下 {@code previousAutoCommit} 再关自动提交，
     * 否则归还时无从恢复；设置隔离级别/readOnly 失败要立刻关掉连接再抛错，
     * 因为一条"声称只读但其实没设上"的连接继续用下去，比直接失败危险得多。
     * </p>
     * <p>隔离级别是<b>连接级</b>设置：池化连接会被复用，而这里只在 begin 时设置、
     * 不在 release 时恢复（{@code release} 只恢复 autoCommit 与 readOnly），
     * 因此显式指定 isolation 时，同一池里其他事务可能继承这个级别。有跨事务级别要求请单独建池。</p>
     */
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

    /**
     * 提交：beforeCommit → JDBC commit → afterCommit → afterCompletion
     * <p>
     * beforeCommit 的异常会走 catch 分支进而触发回滚——这是它和 afterCommit 的本质区别，
     * 也是"提交前补最后一刀"的逻辑该放这里的原因。
     * </p>
     * <p>
     * commit 调用本身失败时上报的是 {@code STATUS_UNKNOWN} 而不是 ROLLED_BACK：
     * 网络/超时类失败下数据库可能其实已经提交了，把它当成"肯定没提交"会让补偿逻辑写出重复数据。
     * 此处随后尝试的 rollback 只是尽力把连接收拾干净，不代表结果可知。
     * </p>
     * <p>afterCommit/afterCompletion 里的异常一律只记日志：数据已经落库，
     * 这时候把缓存刷盘的失败上抛给业务方，只会让人误以为"事务没成功"。</p>
     */
    private void commit(ConnectionHolder holder) {
        try {
            for (TransactionSynchronization sync : holder.getSynchronizations()) {
                sync.beforeCommit();
            }
        } catch (RuntimeException e) {
            // beforeCommit 失败：JDBC commit 尚未发生，数据确定未落库，按回滚处理
            log.warn("beforeCommit 回调失败，执行回滚: {}", e.getMessage());
            try {
                holder.getConnection().rollback();
            } catch (SQLException rollbackEx) {
                holder.markRollbackFailed();
            }
            triggerAfterCompletion(holder, TransactionSynchronization.STATUS_ROLLED_BACK);
            throw e;
        }
        try {
            holder.getConnection().commit();
        } catch (SQLException e) {
            log.warn("事务提交失败，执行回滚: {}", e.getMessage());
            try {
                holder.getConnection().rollback();
            } catch (SQLException rollbackEx) {
                holder.markRollbackFailed();
            }
            // commit 调用本身失败时上报 STATUS_UNKNOWN 而不是 ROLLED_BACK：网络/超时类失败下
            // 数据库可能其实已经提交了，当成"肯定没提交"会让补偿逻辑写出重复数据。
            triggerAfterCompletion(holder, TransactionSynchronization.STATUS_UNKNOWN);
            throw new TransactionStartedException("事务提交失败: " + e.getMessage(), e);
        }
        // 提交成功后才跑：此刻数据已不可撤回，回调只该做"通知类"工作
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

    /**
     * 回滚
     * <p>回滚失败只 warn 不上抛：能走到这里说明业务异常已经在路上了，
     * 把次要的连接清理异常顶掉主异常反而更糟。但回滚失败的连接状态不可信，
     * 会标记 {@code rollbackFailed} 让 {@link #release} 跳过 autoCommit 恢复直接关闭，
     * 否则 setAutoCommit(true) 会把本该回滚的数据隐式提交进库。</p>
     */
    private void rollback(ConnectionHolder holder) {
        try {
            holder.getConnection().rollback();
        } catch (SQLException e) {
            log.warn("事务回滚失败: {}", e.getMessage());
            holder.markRollbackFailed();
        }
        // Write-Behind 的"精确失效缓存"挂在这里：库没写成，缓存里那条也必须作废
        triggerAfterCompletion(holder, TransactionSynchronization.STATUS_ROLLED_BACK);
        log.debug("事务回滚");
    }

    /**
     * 触发 afterCompletion：恰好一次（幂等），单个回调异常吞掉，避免一个收尾失败连带跳过其余收尾。
     * <p>幂等靠 {@link ConnectionHolder#markCompletionNotified} 的原子抢占：commit 失败路径先触发
     * STATUS_UNKNOWN 再抛异常，外层 catch 的 rollback 又试图触发 ROLLED_BACK，第二次被这里拦下。</p>
     */
    private void triggerAfterCompletion(ConnectionHolder holder, int status) {
        if (!holder.markCompletionNotified()) return;
        for (TransactionSynchronization sync : holder.getSynchronizations()) {
            try {
                sync.afterCompletion(status);
            } catch (Exception e) {
                log.warn("afterCompletion 回调异常: {}", e.getMessage());
            }
        }
    }

    /**
     * 恢复连接原始状态并归还池中
     * <p>
     * {@code setAutoCommit(previousAutoCommit)} 是这里最关键的一行：连接池里的连接是被复用的，
     * 留一条 autoCommit=false 的连接回池，下一个借用者的所有写入都会悬在未提交状态，
     * 表现为"数据时隐时现"的诡异故障。恢复失败也只 warn——此时 close() 会让池把它丢弃或重置，
     * 上抛只会盖掉真正的原因。
     * </p>
     * <p>注意 isolation 不在此恢复（见 {@link #doBegin} 的说明）。</p>
     */
    private void release(ConnectionHolder holder) {
        Connection conn = holder.getConnection();
        try {
            if (holder.isReadOnly()) conn.setReadOnly(false);
            // 回滚失败的连接状态不可信：setAutoCommit(true) 会隐式提交本该回滚的数据，
            // 只能跳过恢复直接 close，让池丢弃/重置这条连接
            if (!holder.isRollbackFailed()) {
                conn.setAutoCommit(holder.getPreviousAutoCommit());
            }
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

    /**
     * TransactionStatus 默认实现
     * <p>
     * 四个字段都是<b>构造期定死的快照</b>，只有 rollbackOnly 是例外（它读的是 holder 上的可变标志）。
     * 这么设计是为了让内层看到的"我是不是新事务"不会因为外层状态变化而漂移；
     * 而"要不要回滚"必须实时读 holder，否则内层标记了 rollbackOnly、外层拿着旧的 false 照样提交。
     * </p>
     * <p>{@code holder == null} 表示非事务执行体（SUPPORTS/NOT_SUPPORTED 在无事务时），
     * 此时标记回滚没有意义，直接抛异常而不是静默忽略。</p>
     */
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
