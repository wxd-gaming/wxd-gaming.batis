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
 * <p>
 * 可以把它理解成"一次事务的全部现场"：连接本身、连接被改过的三个开关（autoCommit/隔离级别/readOnly）
 * 的原值、写过的缓存 key、要回调的同步器、以及回滚标志。
 * {@link TransactionTemplate} 提交或回滚后据此把连接恢复原样再归还连接池——
 * <b>漏恢复 autoCommit 会让下一个借到这条连接的业务误以为自己的写入都成功了</b>，
 * 所以 release 里的恢复步骤比 close 更重要。
 * </p>
 * <p>非线程安全但有例外：{@code cacheKeysWritten} 与 {@code synchronizations} 用的是并发容器，
 * 因为 Write-Behind 的定时刷盘线程可能读到持有者并操作这两个集合；
 * 其余字段都只在开启事务的那个线程上读写。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class ConnectionHolder {

    private final Connection connection;
    /** begin 之前的 autoCommit 值，完成时恢复（池化连接被复用，谁借到都得是"默认自动提交"的状态） */
    private final boolean previousAutoCommit;
    /**
     * 事务内被直接写过 DB 的缓存 key（供回滚时精确失效缓存）
     * <p>只记 key 不记旧值：回滚后缓存里的内容已经不可信，正确做法是丢掉让下次读回源，
     * 而不是凭内存快照猜一个回去。</p>
     */
    private final Set<String> cacheKeysWritten = ConcurrentHashMap.newKeySet();
    /** 事务同步回调列表（注册顺序执行；用并发容器是因为刷盘线程也可能注册） */
    private final List<TransactionSynchronization> synchronizations = new CopyOnWriteArrayList<>();
    /** 是否只读事务：既用于连接层 setReadOnly，也是 Runner 写保护的判据 */
    private final boolean readOnly;
    /** 语句级超时（秒），0 表示不限制；由 QueryRunner 在每条语句上应用 */
    private int queryTimeoutSeconds;
    /**
     * 全局回滚标志（参与事务的内层标记的也是它）
     * <p>故意做成"一标记就全局失效"：内层方法不能只回滚自己那一段，JDBC 没有部分回滚，
     * 所以内层捕获异常后必须让整笔事务作废，最外层提交前检查这个标志。</p>
     */
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

    /** 事务绑定的那条物理连接（调用方不得自行 close，归还权在 TransactionTemplate） */
    public Connection getConnection() {
        return connection;
    }

    /** 借出前的 autoCommit，release 时恢复回去 */
    public boolean getPreviousAutoCommit() {
        return previousAutoCommit;
    }

    /** 本事务写过的缓存 key 集合（可变，Runner 在写成功后追加；回滚时按它精确失效缓存） */
    public Set<String> getCacheKeysWritten() {
        return cacheKeysWritten;
    }

    /** 本事务的同步回调列表（提交前/提交后/完成时按注册顺序触发） */
    public List<TransactionSynchronization> getSynchronizations() {
        return synchronizations;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    /**
     * 是否真的有事务。
     * <p>SUPPORTS/NOT_SUPPORTED 在无事务时也会造一个非事务的持有者/状态对象，
     * 用它来区分"我在事务里"和"我只是在同一个 holder 上跑"，
     * 判错会让批量写误走"事务内不 commit"分支而静默不落库。</p>
     */
    public boolean isTransactional() {
        return transactional;
    }

    public int getQueryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }

    public void setQueryTimeoutSeconds(int queryTimeoutSeconds) {
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    /** 是否已标记为只能回滚（最外层 commit 前检查） */
    public boolean isRollbackOnly() {
        return rollbackOnly;
    }

    /**
     * 标记只能回滚。
     * <p>置位后没有任何路径能撤销——这是刻意的：内层一旦出错，外层不该再假定自己能提交。</p>
     */
    public void setRollbackOnly() {
        this.rollbackOnly = true;
    }

}
