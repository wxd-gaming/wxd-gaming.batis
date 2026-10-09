package com.wxdgaming.batis.sql.mysql;

import com.wxdgaming.batis.exception.DataAccessException;
import com.wxdgaming.batis.exception.IllegalTransactionStateException;
import com.wxdgaming.batis.sql.BeanRunner;
import com.wxdgaming.batis.sql.CachedBeanRunner;
import com.wxdgaming.batis.sql.QueryRunner;
import com.wxdgaming.batis.sql.ResultSetHandlers;
import com.wxdgaming.batis.sql.tx.Propagation;
import com.wxdgaming.batis.sql.tx.TxContext;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事务 API 集成测试（MariaDB4j 真库）
 * <p>覆盖：提交/回滚可见性、事务内 Runner 同连接复用与归还、事务内批量不再提前提交（P0-2 正确性专项）、
 * REQUIRES_NEW 双连接、只读事务写拒绝、Write-Behind 事务边界（幽灵缓存失效、事务内 flush 回滚补偿、
 * executeAndFlush 提交后刷盘）、并发转账守恒。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
@TestMethodOrder(OrderAnnotation.class)
class TransactionTemplateIT {

    @RegisterExtension
    static MariaDB4jExtension maria = new MariaDB4jExtension();

    static MysqlTemplate template;

    @BeforeAll
    static void setUp() {
        if (template != null) return;
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(maria.getJdbcUrl());
        config.setUsername("root");
        config.setPassword("");
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        config.setMaximumPoolSize(8);
        template = new MysqlTemplate(config);
        template.checkTable(AccountEntity.class);
    }

    @BeforeEach
    @AfterEach
    void cleanTable() {
        try (QueryRunner qr = new QueryRunner(template)) {
            qr.update("DELETE FROM `t_account`");
        }
    }

    private static AccountEntity newAccount(String name, String balance) {
        AccountEntity a = new AccountEntity();
        a.setAccountName(name);
        a.setBalance(new BigDecimal(balance));
        return a;
    }

    private static long rowCount() {
        try (QueryRunner qr = new QueryRunner(template)) {
            Object cell = qr.query("SELECT COUNT(*) FROM `t_account`", ResultSetHandlers.firstCell());
            return ((Number) cell).longValue();
        }
    }

    private static boolean exists(String name) {
        try (QueryRunner qr = new QueryRunner(template)) {
            Object cell = qr.query("SELECT COUNT(*) FROM `t_account` WHERE `account_name` = ?",
                    new Object[]{name}, ResultSetHandlers.firstCell());
            return ((Number) cell).longValue() > 0;
        }
    }

    // ===== 基本语义 =====

    /**
     * 用例①：事务提交后写入必须可见。
     * <p>前置：空表。动作：事务内插入 1 条，回调正常返回（不抛异常、不标记回滚）。
     * 断言：表行数 = 1。意义：证明 {@code execute()} 结束时真的执行了 COMMIT，
     * 而不是"连接关回池里时碰巧保留了数据"——后者在 autocommit 连接上同样成立，会掩盖漏提交的 bug。</p>
     */
    @Test
    @Order(1)
    void commitMakesWritesVisible() {
        template.transactionTemplate().executeWithoutResult(status -> {
            try (BeanRunner br = template.beanRunner()) {
                br.insertBean(newAccount("tx-commit", "100.00"));
            }
        });
        assertEquals(1, rowCount());
    }

    /**
     * 用例②：回调抛异常必须触发回滚，且异常原样上抛。
     * <p>动作：事务内插入 1 条后抛业务异常。断言两点：
     * 一是异常穿透 {@code executeWithoutResult}（不被吞掉），二是表行数 = 0（写入被 ROLLBACK）。
     * 这是事务 API 最核心的语义，回滚失效等于线上脏数据。</p>
     */
    @Test
    @Order(2)
    void rollbackDiscardsWrites() {
        assertThrows(RuntimeException.class, () ->
                template.transactionTemplate().executeWithoutResult(status -> {
                    try (BeanRunner br = template.beanRunner()) {
                        br.insertBean(newAccount("tx-rollback", "100.00"));
                    }
                    throw new RuntimeException("业务失败");
                }));
        assertEquals(0, rowCount());
    }

    /**
     * 用例③：未抛异常但主动 {@code setRollbackOnly()} 也必须回滚。
     * <p>动作：事务内插入 1 条，然后仅标记回滚，回调正常返回。断言：行数 = 0。
     * 意义：覆盖"业务校验失败但不想靠异常控制流程"的写法；
     * 若实现只 catch 异常才回滚，这条会失败。</p>
     */
    @Test
    @Order(3)
    void setRollbackOnlyForcesRollback() {
        template.transactionTemplate().executeWithoutResult(status -> {
            try (BeanRunner br = template.beanRunner()) {
                br.insertBean(newAccount("tx-mark-rollback", "1"));
            }
            status.setRollbackOnly();
        });
        assertEquals(0, rowCount());
    }

    // ===== 连接复用与归还 =====

    /**
     * 用例④：事务内所有 Runner 共用同一条连接，且事务结束后必须归还池、解绑上下文。
     * <p>动作：事务内新建两个 {@link QueryRunner}（不显式注入连接），与 {@code TxContext} 绑定的连接对比。
     * 断言四点：
     * <ol>
     *   <li>两个 Runner 拿到的 Connection 是同一实例（同事务 = 同连接，否则回滚管不住它们）；</li>
     *   <li>事务进行中 HikariCP activeConnections = 1（没有多余借出）；</li>
     *   <li>Runner 的 try-with-resources close() 之后 active 仍为 1 —— 事务连接的归还权属于事务模板，
     *       Runner 绝不能把这条连接关回池里（这是 {@code Source.TRANSACTION} 存在的理由）；</li>
     *   <li>事务结束后 active = 0 且 {@code TxContext.get()} 为 null —— 既不泄漏连接，也不给线程留下脏上下文
     *       （线程池复用线程，残留绑定会让后续无关代码误用已归还的连接）。</li>
     * </ol>
     */
    @Test
    @Order(4)
    void runnersShareTransactionConnectionAndReturnIt() throws Exception {
        HikariDataSource ds = template.getDataSource();
        template.transactionTemplate().executeWithoutResult(status -> {
            Connection txConn = TxContext.get(template).getConnection();
            try (QueryRunner qr1 = new QueryRunner(template);
                 QueryRunner qr2 = new QueryRunner(template)) {
                try {
                    assertEquals(txConn, qr1.getConnection(), "事务内 Runner 应复用事务连接");
                    assertEquals(txConn, qr2.getConnection());
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException(e);
                }
            }
            assertEquals(1, ds.getHikariPoolMXBean().getActiveConnections());
        });
        assertEquals(0, ds.getHikariPoolMXBean().getActiveConnections(), "事务结束后连接必须归还池");
        assertNull(TxContext.get(template), "事务上下文必须解绑");
    }

    // ===== P0-2 正确性专项：事务内批量不再提前提交 =====

    /**
     * 用例⑤（P0-2 正确性专项）：事务内批量插入不得提前提交。
     * <p>背景：修复前 {@code QueryRunner.executeBatch} 与 {@code BeanRunner.doInsertBatch} 无条件执行
     * {@code setAutoCommit(false) → executeBatch → commit}，即使外层已有事务也会把这一批静默提交掉，
     * 于是"整事务回滚"只能回滚掉批次之外的写入 —— 属于数据正确性事故，而不是性能问题。</p>
     * <p>动作：事务内 {@code insertBeanList} 3 条后抛异常。断言：行数 = 0，
     * 即这一批和事务内其他写入一起被 ROLLBACK。若本用例失败（行数 3），
     * 说明批量路径又绕过了事务边界，必须优先修。</p>
     */
    @Test
    @Order(5)
    void batchInsideTransactionRollsBackEntirely() {
        List<AccountEntity> batch = List.of(
                newAccount("batch-a", "1"), newAccount("batch-b", "2"), newAccount("batch-c", "3"));
        assertThrows(RuntimeException.class, () ->
                template.transactionTemplate().executeWithoutResult(status -> {
                    try (BeanRunner br = template.beanRunner()) {
                        br.insertBeanList(batch); // 旧实现会在这里静默 commit（bug 专项）
                    }
                    throw new RuntimeException("批量后回滚");
                }));
        assertEquals(0, rowCount(), "事务内批量不得提前提交，回滚应整批不可见");
    }

    /**
     * 用例⑥：批量路径与事务的另一个方向 —— 提交时必须一起成功。
     * <p>与用例⑤配对：⑤只证明"批量没被提前提交"，如果实现干脆把事务内批量丢掉，⑤也会通过。
     * 本用例断言正常提交后 2 条批量数据落库，证明批量仍然执行、只是把提交权交还事务模板。</p>
     */
    @Test
    @Order(6)
    void batchInsideTransactionCommitsWithTransaction() {
        List<AccountEntity> batch = List.of(
                newAccount("batch-d", "1"), newAccount("batch-e", "2"));
        template.transactionTemplate().executeWithoutResult(status -> {
            try (BeanRunner br = template.beanRunner()) {
                br.insertBeanList(batch);
            }
        });
        assertEquals(2, rowCount());
    }

    // ===== 传播行为 =====

    /**
     * 用例⑦：{@code REQUIRES_NEW} 挂起外层、另借连接、独立提交。
     * <p>动作：外层事务开启 → 内层以 REQUIRES_NEW 运行并插入 inner 后提交 → 外层检查可见性 →
     * 外层插入 outer 并 {@code setRollbackOnly()}。</p>
     * <p>断言：</p>
     * <ol>
     *   <li>内层提交后 {@code exists("inner")} 为真 —— 且这次读取走的是<b>外层</b>连接，
     *       说明内层确实在另一条连接上完成了提交（同连接读到自己未提交的数据无法区分这一点，
     *       所以用外层连接读才有意义）；</li>
     *   <li>此刻 {@code exists("outer")} 为假 —— 外层尚未写入，作为基线；</li>
     *   <li>两个 Connection 实例不同（REQUIRES_NEW 不能复用外层连接，否则不可能独立提交）；</li>
     *   <li>结束后 inner 仍在、outer 消失 —— 外层回滚不影响已独立提交的内层（典型用途：日志/流水号必须留下）；</li>
     *   <li>池 activeConnections = 0 —— 两层事务各借一条，退出时都必须归还，
     *       这也是连接池容量要设为 8 的原因：嵌套事务同时占用多条连接，容量 1 会直接死等。</li>
     * </ol>
     */
    @Test
    @Order(7)
    void requiresNewUsesSecondConnectionAndSurvivesOuterRollback() throws Exception {
        Connection[] outerConn = new Connection[1];
        Connection[] innerConn = new Connection[1];
        HikariDataSource ds = template.getDataSource();
        template.transactionTemplate().executeWithoutResult(outer -> {
            outerConn[0] = TxContext.get(template).getConnection();
            // 内层 REQUIRES_NEW 挂起外层、另借连接、独立提交
            template.transactionTemplate().execute(Propagation.REQUIRES_NEW, inner -> {
                innerConn[0] = TxContext.get(template).getConnection();
                try (BeanRunner br = template.beanRunner()) {
                    br.insertBean(newAccount("inner", "1"));
                }
                return null;
            });
            // 内层已独立提交，对外层连接立即可见；外层自身未提交数据不可见
            assertTrue(exists("inner"), "内层独立提交对外层立即可见");
            assertFalse(exists("outer"), "外层未提交数据不可见");
            try (BeanRunner br = template.beanRunner()) {
                br.insertBean(newAccount("outer", "1"));
            }
            outer.setRollbackOnly();
        });
        assertNotNull(innerConn[0]);
        assertTrue(outerConn[0] != innerConn[0], "REQUIRES_NEW 必须使用独立连接");
        assertEquals(0, ds.getHikariPoolMXBean().getActiveConnections(), "两层事务都必须归还连接");
        // 内层独立提交保留，外层回滚丢弃
        assertTrue(exists("inner"), "REQUIRES_NEW 内层事务应独立提交");
        assertFalse(exists("outer"), "外层事务回滚后不应可见");
    }

    // ===== 只读事务 =====

    /**
     * 用例⑧：只读事务中执行写操作要被拒绝，且不落到数据库。
     * <p>动作：把共享的 transactionTemplate 置为 readOnly，事务内插入。
     * 断言：抛 {@link IllegalTransactionStateException}。
     * 意义：写保护是执行前校验（{@code QueryRunner.checkWritable} 读线程上下文），
     * 不依赖连接是否已惰性获取，也不依赖驱动把 readOnly 翻译成数据库端限制——
     * 后者只有一部分语句会被拦住。
     * 注意 finally 里必须复位 readOnly：模板实例是类级静态共享的，否则后续用例全部被污染。</p>
     */
    @Test
    @Order(8)
    void readOnlyTransactionRejectsWrites() {
        var tt = template.transactionTemplate();
        tt.setReadOnly(true);
        try {
            assertThrows(IllegalTransactionStateException.class, () ->
                    tt.executeWithoutResult(status -> {
                        try (BeanRunner br = template.beanRunner()) {
                            br.insertBean(newAccount("ro-write", "1"));
                        }
                    }));
        } finally {
            tt.setReadOnly(false);
        }
    }

    // ===== Write-Behind 事务边界 =====

    /**
     * 用例⑨（Write-Behind 边界规则一）：事务内回滚后，缓存不得残留未提交的新值（防幽灵读）。
     * <p>前置：立即插入并把实体加载进缓存（余额 10.00）。</p>
     * <p>动作：事务内 {@code updateBean(a, false)}——延迟写在事务里会自动升级为立即写（TX_IMMEDIATE），
     * 把内存值改成 99.00 —— 然后抛异常回滚。</p>
     * <p>断言两步：DB 仍是 10.00（回滚生效）；随后<b>再走缓存</b> {@code queryById} 仍读到 10.00。
     * 后者才是本用例的重点：如果 afterCompletion 没按 key 精确失效缓存，
     * 进程内会长期存在一条"从未提交过"的 99.00，任何读该 ID 的代码都会读到幽灵数据，
     * 且重启才恢复，属于最难排查的一类不一致。</p>
     */
    @Test
    @Order(9)
    void rollbackInvalidatesCachedImmediateWriteNoGhostRead() {
        CachedBeanRunner runner = template.cachedBeanRunner();
        AccountEntity a = newAccount("wb-base", "10.00");
        runner.insertBean(a, true);
        long id = a.getId();
        assertNotNull(runner.queryById(AccountEntity.class, id), "先让实体进入缓存");

        // 事务内延迟更新自动升级为立即写，随后回滚 → 缓存必须被精确失效（防幽灵读）
        a.setBalance(new BigDecimal("99.00"));
        assertThrows(RuntimeException.class, () ->
                template.transactionTemplate().executeWithoutResult(status -> {
                    runner.updateBean(a, false);
                    throw new RuntimeException("回滚");
                }));
        try (BeanRunner br = template.beanRunner()) {
            assertEquals(0, new BigDecimal("10.00").compareTo(br.queryById(AccountEntity.class, id).getBalance()),
                    "回滚后 DB 仍是旧值");
        }
        // 缓存失效后 queryById 回源，取到旧值而非内存里的新值
        AccountEntity reloaded = runner.queryById(AccountEntity.class, id);
        assertEquals(0, new BigDecimal("10.00").compareTo(reloaded.getBalance()),
                "回滚后缓存不得残留未提交的新值");
        runner.clearAll();
    }

    /**
     * 用例⑩（Write-Behind 边界规则二）：事务内 {@code flushAll()} 加入当前事务，回滚时脏快照必须放回。
     * <p>前置：立即插入（10.00）、加载缓存、延迟更新为 99.00（此时脏更新计数 = 1，DB 仍是旧值）。</p>
     * <p>动作：事务内调用 {@code flushAll()}——刷盘 SQL 加入当前事务但<b>不提交</b>，随后标记回滚。</p>
     * <p>断言三段：</p>
     * <ol>
     *   <li>DB 仍是 10.00 —— 刷盘跟着事务一起被回滚，没有偷偷提前提交；</li>
     *   <li>{@code dirtyUpdateCount()} 恢复成 1 —— 这是本用例存在的意义：flush 会把条目移出脏表，
     *       若回滚后不做补偿，这条修改在内存里已被丢弃、库里又没落，数据永久丢失（静默丢写）；</li>
     *   <li>补偿放回后再次 {@code flushAll()} 能把 99.00 正常写进 DB —— 证明放回的不是坏数据。</li>
     * </ol>
     */
    @Test
    @Order(10)
    void flushInsideTxRollbackRestoresDirtySnapshot() {
        CachedBeanRunner runner = template.cachedBeanRunner();
        AccountEntity a = newAccount("wb-flush", "10.00");
        runner.insertBean(a, true);
        long id = a.getId();
        runner.queryById(AccountEntity.class, id);
        a.setBalance(new BigDecimal("99.00"));
        runner.updateBean(a, false);
        assertEquals(1, runner.dirtyUpdateCount());

        // 事务内 flushAll = JOIN 事务；回滚后脏快照必须放回，防止数据永久丢失
        template.transactionTemplate().executeWithoutResult(status -> {
            runner.flushAll();
            status.setRollbackOnly();
        });
        try (BeanRunner br = template.beanRunner()) {
            assertEquals(0, new BigDecimal("10.00").compareTo(br.queryById(AccountEntity.class, id).getBalance()),
                    "回滚后 DB 保持旧值");
        }
        assertEquals(1, runner.dirtyUpdateCount(), "回滚后脏更新应恢复");

        // 补偿放回的脏数据可再次刷盘成功
        runner.flushAll();
        try (BeanRunner br = template.beanRunner()) {
            assertEquals(0, new BigDecimal("99.00").compareTo(br.queryById(AccountEntity.class, id).getBalance()));
        }
        runner.clearAll();
    }

    /**
     * 用例⑪（Write-Behind 推荐姿势）：{@code executeAndFlush} 在事务提交之后才刷盘。
     * <p>动作：延迟插入（不落库、只进脏表）+ 事务正常结束。
     * 断言：DB 行数 = 1（afterCommit 回调把脏数据刷了下去）且脏计数 = 0（刷完即清，不残留）。
     * 与用例⑩的区别：⑩是"事务内主动 flush = 全有或全无"，⑪是"提交后再 flush = 不占用事务时长"，
     * 后者适合写入量大、可以接受极短可见延迟的场景，是 Write-Behind 与事务配合的默认推荐。</p>
     */
    @Test
    @Order(11)
    void executeAndFlushWritesAfterCommit() {
        CachedBeanRunner runner = template.cachedBeanRunner();
        AccountEntity a = newAccount("wb-after", "5.00");
        runner.getJdbcTemplate().transactionTemplate()
                .executeAndFlush(runner, status -> runner.insertBean(a, false));
        assertEquals(1, rowCount(), "提交后 afterCommit 刷盘落库");
        assertEquals(0, runner.dirtyTotalCount());
        runner.clearAll();
    }

    // ===== 并发守恒 =====

    /**
     * 用例⑫：并发转账余额守恒（端到端验证事务 + 异常分类 + 死锁处理）。
     * <p>前置：两个账户各 10000.00，总额 20000.00。动作：4 个线程各 50 轮转账，
     * 一半方向 low→high、一半 high→low，制造最大冲突。
     * 每次转账最多重试 5 次，且只对 {@code isRetryable()} 为真的异常重试
     * —— 这正是 P0-1 异常体系的使用姿势：死锁/锁超时/连接断开属 Transient 可重试，
     * 唯一键冲突等属 NonTransient 重试无意义，必须直接失败上抛。</p>
     * <p>断言两点：一是 SUM(balance) 仍等于 20000.00（没有任何一笔转账丢半条：
     * 扣款和入账在同一事务内，隔离性成立）；二是重试 5 次仍失败的事务数为 0
     * （{@link #transfer} 里固定按 ID 大小顺序加锁，不应出现解不开的死锁）。
     * 守恒被破坏 = 事务边界或连接复用有实质 bug，属最高优先级问题。</p>
     */
    @Test
    @Order(12)
    void concurrentTransfersConserveBalance() throws Exception {
        AccountEntity low = newAccount("acct-low", "10000.00");
        AccountEntity high = newAccount("acct-high", "10000.00");
        try (BeanRunner br = template.beanRunner()) {
            br.insertBean(low);
            br.insertBean(high);
        }
        long lowId = low.getId(), highId = high.getId();

        int threads = 4, rounds = 50;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger failures = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            final boolean upward = t % 2 == 0; // 一半 low→high，一半 high→low
            new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < rounds; i++) {
                        for (int attempt = 0; attempt < 5; attempt++) {
                            try {
                                transfer(upward, lowId, highId);
                                break;
                            } catch (DataAccessException e) {
                                if (!e.isRetryable() || attempt == 4) {
                                    failures.incrementAndGet();
                                    break;
                                }
                            }
                        }
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertTrue(done.await(180, TimeUnit.SECONDS), "转账线程超时");
        try (QueryRunner qr = new QueryRunner(template)) {
            Object sum = qr.query("SELECT SUM(`balance`) FROM `t_account`", ResultSetHandlers.firstCell());
            assertEquals(0, new BigDecimal("20000.00").compareTo(new BigDecimal(sum.toString())),
                    "并发转账后总额必须守恒");
        }
        assertEquals(0, failures.get(), "重试 5 次后仍失败的事务数必须为 0");
    }

    /** 固定加锁顺序（小 ID 先锁）避免死锁；扣款方与收款方在同事务内更新 */
    private static void transfer(boolean fromLowToHigh, long lowId, long highId) {
        template.transactionTemplate().executeWithoutResult(status -> {
            try (BeanRunner br = template.beanRunner()) {
                AccountEntity first = br.queryById(AccountEntity.class, lowId);
                AccountEntity second = br.queryById(AccountEntity.class, highId);
                if (fromLowToHigh) {
                    first.setBalance(first.getBalance().subtract(BigDecimal.ONE));
                    second.setBalance(second.getBalance().add(BigDecimal.ONE));
                } else {
                    second.setBalance(second.getBalance().subtract(BigDecimal.ONE));
                    first.setBalance(first.getBalance().add(BigDecimal.ONE));
                }
                br.updateBean(first);
                br.updateBean(second);
            }
        });
    }

}
