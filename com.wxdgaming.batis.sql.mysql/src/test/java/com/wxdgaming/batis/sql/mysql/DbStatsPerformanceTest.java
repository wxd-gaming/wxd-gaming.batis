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

/**
 * DbStats 性能统计测试
 * <p>
 * 测试流程：
 * <ol>
 *   <li>清空表 → 每间隔 1 秒插入 1000 条，累计插入 50000 条</li>
 *   <li>查询全部数据</li>
 *   <li>每间隔 1 秒更新 1000 条，将所有数据更新一遍</li>
 *   <li>输出 DbStats 性能统计报告</li>
 * </ol>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-08-07
 */
@DisplayName("DbStats 性能统计测试")
class DbStatsPerformanceTest {

    static final Logger log = LoggerFactory.getLogger(DbStatsPerformanceTest.class);
    static final int TOTAL = 50000;
    static final int BATCH = 1000;
    static final long INTERVAL_MS = 1000L;

    static MysqlTemplate template;

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
        template.checkTable(AccountEntity.class);
    }

    /**
     * 清理初始化：删除全部已有数据，重置统计器
     */
    @BeforeEach
    void cleanStart() {
        try (var qr = new QueryRunner(template)) {
            qr.update("DELETE FROM `t_account`");
        }
        template.getStats().reset();
    }

    @Test
    @DisplayName("50000 条批量插入 + 50000 条批量更新 — 性能统计")
    void performanceTest() {
        // ======================================================================
        // Phase 1: 批量插入 50000 条（50 批 × 1000 条，每批间隔 1 秒）
        // ======================================================================
        log.info("╔══════════════════════════════════════════════════════════════╗");
        log.info("║  Phase 1: 开始插入 {} 条数据 (每 {} 条间隔 {}ms)          ║", TOTAL, BATCH, INTERVAL_MS);
        log.info("╚══════════════════════════════════════════════════════════════╝");

        long insertStart = System.currentTimeMillis();
        for (int round = 0; round < TOTAL / BATCH; round++) {
            List<AccountEntity> batch = buildBatch(round);
            try (var br = template.beanRunner()) {
                br.insertBeanList(batch);
            }
            int done = (round + 1) * BATCH;
            log.info("  已插入: {}/{} 条 | 最近一次: {}ms, {} 条",
                    done, TOTAL,
                    template.getStats().getInsertStats().getLastMs(),
                    template.getStats().getInsertStats().getLastRecords());

            if (round < TOTAL / BATCH - 1) {
                sleep(INTERVAL_MS);
            }
        }
        long insertElapsed = System.currentTimeMillis() - insertStart;
        log.info("Phase 1 完成 — 真实耗时: {}ms (含间隔等待)", insertElapsed);

        // ======================================================================
        // Phase 2: 查询全部数据（为更新做准备）
        // ======================================================================
        log.info("\n╔══════════════════════════════════════════════════════════════╗");
        log.info("║  Phase 2: 查询全部数据，准备批量更新                         ║");
        log.info("╚══════════════════════════════════════════════════════════════╝");

        List<AccountEntity> allAccounts;
        try (var br = template.beanRunner()) {
            long qStart = System.currentTimeMillis();
            allAccounts = br.queryAll(AccountEntity.class);
            log.info("  查询 {} 条记录，耗时: {}ms", allAccounts.size(),
                    System.currentTimeMillis() - qStart);
        }

        // ======================================================================
        // Phase 3: 批量更新 50000 条（50 批 × 1000 条，每批间隔 1 秒）
        // ======================================================================
        log.info("\n╔══════════════════════════════════════════════════════════════╗");
        log.info("║  Phase 3: 开始更新全部 {} 条数据 (每 {} 条间隔 {}ms)       ║", TOTAL, BATCH, INTERVAL_MS);
        log.info("╚══════════════════════════════════════════════════════════════╝");

        long updateStart = System.currentTimeMillis();
        for (int round = 0; round < TOTAL / BATCH; round++) {
            int offset = round * BATCH;
            List<AccountEntity> batch = allAccounts.subList(offset, offset + BATCH);
            // 修改字段值以触发更新
            for (AccountEntity account : batch) {
                account.setBalance(new BigDecimal("999.99"));
                account.setVipLevel((account.getVipLevel() + 1) % 10);
                account.setLastLoginTime(new Date());
                account.setFrozen((round % 2) == 0);
            }
            try (var br = template.beanRunner()) {
                br.updateBeanList(batch);
            }
            int done = (round + 1) * BATCH;
            log.info("  已更新: {}/{} 条 | 最近一次: {}ms, {} 条",
                    done, TOTAL,
                    template.getStats().getUpdateStats().getLastMs(),
                    template.getStats().getUpdateStats().getLastRecords());

            if (round < TOTAL / BATCH - 1) {
                sleep(INTERVAL_MS);
            }
        }
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
            Number count = (Number) qr.query("SELECT COUNT(*) FROM `t_account`",
                    com.wxdgaming.batis.sql.ResultSetHandlers.firstCell());
            Assertions.assertEquals(TOTAL, count.intValue(),
                    "表中的记录数应为 " + TOTAL);
        }
    }

    // ======================== 辅助方法 ========================

    /**
     * 构建一批测试数据（唯一 accountName 避免唯一索引冲突）
     */
    private static List<AccountEntity> buildBatch(int round) {
        List<AccountEntity> batch = new ArrayList<>(BATCH);
        for (int i = 0; i < BATCH; i++) {
            AccountEntity account = new AccountEntity();
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
