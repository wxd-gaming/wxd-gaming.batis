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
