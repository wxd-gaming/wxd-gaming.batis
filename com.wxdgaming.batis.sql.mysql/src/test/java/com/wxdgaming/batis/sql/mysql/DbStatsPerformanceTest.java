package com.wxdgaming.batis.sql.mysql;

import com.wxdgaming.batis.DbStats;
import com.wxdgaming.batis.sql.BeanRunner;
import com.wxdgaming.batis.sql.QueryRunner;
import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DbStats 性能统计测试（多线程模拟真实环境）
 * <p>
 * 测试流程：
 * <ol>
 *   <li>清空表 → 多线程并发插入 50000 条（每线程每批 1000 条，间隔 1 秒）</li>
 *   <li>查询全部数据</li>
 *   <li>多线程并发更新 50000 条（每线程每批 1000 条，间隔 1 秒）</li>
 *   <li>输出 DbStats 性能统计报告</li>
 * </ol>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-08-08
 */
@DisplayName("DbStats 性能统计测试（多线程）")
class DbStatsPerformanceTest {

    static final Logger log = LoggerFactory.getLogger(DbStatsPerformanceTest.class);
    static final int TOTAL = 50000;
    static final int BATCH = 1000;
    static final long INTERVAL_MS = 1000L;
    /** 并发线程数（模拟真实环境中多个客户端同时操作数据库） */
    static final int THREAD_COUNT = 5;

    static MysqlTemplate template;
    /** 全局唯一 ID 生成器，基于当前纳秒时间戳起始 */
    static final AtomicLong ID_GENERATOR = new AtomicLong(System.nanoTime());

    @RegisterExtension
    static MariaDB4jExtension maria = new MariaDB4jExtension();

    @BeforeAll
    static void setUp() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(maria.getJdbcUrl());
        config.setUsername("root");
        config.setPassword("");
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        template = new MysqlTemplate(config);
        // 确保表结构存在（幂等）
        template.checkTable(AccountEntityManualId.class);
    }

    /**
     * 清理初始化：删除全部已有数据，重置统计器
     */
    @BeforeEach
    void cleanStart() {
        try (var qr = new QueryRunner(template)) {
            qr.update("DELETE FROM `t_account_manual`");
        }
        template.getStats().reset();
    }

    @Test
    @DisplayName("50000 条多线程插入 + 50000 条多线程更新 — 性能统计")
    void performanceTest() throws InterruptedException {
        int totalBatches = TOTAL / BATCH;

        // ======================================================================
        // Phase 1: 多线程并发插入 50000 条
        // ======================================================================
        log.info("""
                
                ╔══════════════════════════════════════════════════════════════╗
                ║  Phase 1: {} 线程并发插入 {} 条数据 (每批 {} 条, 批间隔 {}ms)║
                ╚══════════════════════════════════════════════════════════════╝""",
                THREAD_COUNT, TOTAL, BATCH, INTERVAL_MS);

        long insertStart = System.currentTimeMillis();
        ExecutorService insertPool = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch insertLatch = new CountDownLatch(THREAD_COUNT);
        AtomicInteger insertProgress = new AtomicInteger(0);

        for (int t = 0; t < THREAD_COUNT; t++) {
            final int threadIndex = t;
            insertPool.submit(() -> {
                try {
                    for (int round = threadIndex; round < totalBatches; round += THREAD_COUNT) {
                        List<AccountEntityManualId> batch = buildBatch(round);
                        long batchStart = System.currentTimeMillis();
                        try (var br = template.beanRunner()) {
                            br.insertBeanList(batch);
                        }
                        long batchMs = System.currentTimeMillis() - batchStart;
                        int done = insertProgress.addAndGet(BATCH);
                        log.info("  [Thread-{}] 已插入: {}/{} 条 | 本批耗时: {}ms",
                                threadIndex, done, TOTAL, batchMs);

                        if (round + THREAD_COUNT < totalBatches) {
                            sleep(INTERVAL_MS);
                        }
                    }
                } catch (Exception e) {
                    log.error("插入线程 {} 异常", threadIndex, e);
                } finally {
                    insertLatch.countDown();
                }
            });
        }

        insertLatch.await();
        insertPool.shutdown();
        insertPool.awaitTermination(10, TimeUnit.SECONDS);

        long insertElapsed = System.currentTimeMillis() - insertStart;
        log.info("Phase 1 完成 — 真实耗时: {}ms (含间隔等待)", insertElapsed);

        // ======================================================================
        // Phase 2: 查询全部数据（为更新做准备）
        // ======================================================================
        log.info("""
                
                ╔══════════════════════════════════════════════════════════════╗
                ║  Phase 2: 查询全部数据，准备批量更新                         ║
                ╚══════════════════════════════════════════════════════════════╝""");

        List<AccountEntityManualId> allAccounts;
        try (var br = template.beanRunner()) {
            long qStart = System.currentTimeMillis();
            allAccounts = br.queryAll(AccountEntityManualId.class);
            log.info("  查询 {} 条记录，耗时: {}ms", allAccounts.size(),
                    System.currentTimeMillis() - qStart);
        }

        // ======================================================================
        // Phase 3: 多线程并发更新 50000 条
        // ======================================================================
        log.info("""
                
                ╔══════════════════════════════════════════════════════════════╗
                ║  Phase 3: {} 线程并发更新全部 {} 条数据 (每批 {} 条, 批间隔 {}ms)║
                ╚══════════════════════════════════════════════════════════════╝""",
                THREAD_COUNT, TOTAL, BATCH, INTERVAL_MS);

        long updateStart = System.currentTimeMillis();
        ExecutorService updatePool = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch updateLatch = new CountDownLatch(THREAD_COUNT);
        AtomicInteger updateProgress = new AtomicInteger(0);

        for (int t = 0; t < THREAD_COUNT; t++) {
            final int threadIndex = t;
            updatePool.submit(() -> {
                try {
                    for (int round = threadIndex; round < totalBatches; round += THREAD_COUNT) {
                        int offset = round * BATCH;
                        List<AccountEntityManualId> batch = allAccounts.subList(offset, offset + BATCH);
                        // 修改字段值以触发更新
                        for (AccountEntityManualId account : batch) {
                            account.setBalance(new BigDecimal("999.99"));
                            account.setVipLevel((account.getVipLevel() + threadIndex + 1) % 10);
                            account.setLastLoginTime(new Date());
                            account.setFrozen((round % 2) == 0);
                        }
                        long batchStart = System.currentTimeMillis();
                        try (var br = template.beanRunner()) {
                            br.updateBeanList(batch);
                        }
                        long batchMs = System.currentTimeMillis() - batchStart;
                        int done = updateProgress.addAndGet(BATCH);
                        log.info("  [Thread-{}] 已更新: {}/{} 条 | 本批耗时: {}ms",
                                threadIndex, done, TOTAL, batchMs);

                        if (round + THREAD_COUNT < totalBatches) {
                            sleep(INTERVAL_MS);
                        }
                    }
                } catch (Exception e) {
                    log.error("更新线程 {} 异常", threadIndex, e);
                } finally {
                    updateLatch.countDown();
                }
            });
        }

        updateLatch.await();
        updatePool.shutdown();
        updatePool.awaitTermination(10, TimeUnit.SECONDS);

        long updateElapsed = System.currentTimeMillis() - updateStart;
        log.info("Phase 3 完成 — 真实耗时: {}ms (含间隔等待)", updateElapsed);

        // ======================================================================
        // 输出性能统计报告
        // ======================================================================
        log.info("\n{}", template.getStats().toPrettyString());

        // ======================================================================
        // 单独输出插入/更新明细
        // ======================================================================
        DbStats.OpStats insertStats = template.getStats().getInsertStats();
        DbStats.OpStats updateStats = template.getStats().getUpdateStats();

        log.info("\n=== 插入明细 ===");
        log.info("  调用次数: {}", insertStats.getCallCount());
        log.info("  记录条数: {}", insertStats.getRecordCount());
        log.info("  总耗时:   {}ms", insertStats.getTotalMs());
        log.info("  最大耗时: {}ms", insertStats.getMaxMs());
        log.info("  最小耗时: {}ms", insertStats.getMinMs());
        log.info("  平均耗时: {}ms", insertStats.getAvgMs());
        log.info("  最近一次: {}ms, {} 条", insertStats.getLastMs(), insertStats.getLastRecords());

        long[] insHis = insertStats.getHistogram();
        long[] insBnd = insertStats.getBoundaries();
        log.info("  5段耗时分布:");
        log.info("    ≤{}ms:  {}", insBnd[0], insHis[0]);
        log.info("    {}~{}ms:  {}", insBnd[0], insBnd[1], insHis[1]);
        log.info("    {}~{}ms:  {}", insBnd[1], insBnd[2], insHis[2]);
        log.info("    {}~{}ms:  {}", insBnd[2], insBnd[3], insHis[3]);
        log.info("    >{}ms:  {}", insBnd[3], insHis[4]);

        log.info("\n=== 更新明细 ===");
        log.info("  调用次数: {}", updateStats.getCallCount());
        log.info("  记录条数: {}", updateStats.getRecordCount());
        log.info("  总耗时:   {}ms", updateStats.getTotalMs());
        log.info("  最大耗时: {}ms", updateStats.getMaxMs());
        log.info("  最小耗时: {}ms", updateStats.getMinMs());
        log.info("  平均耗时: {}ms", updateStats.getAvgMs());
        log.info("  最近一次: {}ms, {} 条", updateStats.getLastMs(), updateStats.getLastRecords());

        long[] updHis = updateStats.getHistogram();
        long[] updBnd = updateStats.getBoundaries();
        log.info("  5段耗时分布:");
        log.info("    ≤{}ms:  {}", updBnd[0], updHis[0]);
        log.info("    {}~{}ms:  {}", updBnd[0], updBnd[1], updHis[1]);
        log.info("    {}~{}ms:  {}", updBnd[1], updBnd[2], updHis[2]);
        log.info("    {}~{}ms:  {}", updBnd[2], updBnd[3], updHis[3]);
        log.info("    >{}ms:  {}", updBnd[3], updHis[4]);

        // ======================================================================
        // 验证数据完整性
        // ======================================================================
        try (var qr = new QueryRunner(template)) {
            Number count = (Number) qr.query("SELECT COUNT(*) FROM `t_account_manual`",
                    com.wxdgaming.batis.sql.ResultSetHandlers.firstCell());
            Assertions.assertEquals(TOTAL, count.intValue(),
                    "表中的记录数应为 " + TOTAL);
        }
    }

    // ======================== 辅助方法 ========================

    /**
     * 构建一批测试数据（唯一 accountName 避免唯一索引冲突）
     */
    private static List<AccountEntityManualId> buildBatch(int round) {
        List<AccountEntityManualId> batch = new ArrayList<>(BATCH);
        for (int i = 0; i < BATCH; i++) {
            AccountEntityManualId account = new AccountEntityManualId();
            account.setId(ID_GENERATOR.incrementAndGet());
            account.setAccountName("perf_" + (round * BATCH + i));
            account.setBalance(new BigDecimal(String.format("%.2f", 100.0 + i % 100)));
            account.setVipLevel(i % 10);
            account.setLastLoginTime(new Date());
            account.setFrozen(false);
            batch.add(account);
        }
        return batch;
    }

    @SuppressWarnings("SameParameterValue")
    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
