package com.wxdgaming.batis.sql.mysql;

import com.wxdgaming.batis.DbStats;
import com.wxdgaming.batis.cache.LocalEntityCache;
import com.wxdgaming.batis.sql.BeanRunner;
import com.wxdgaming.batis.sql.CachedBeanRunner;
import com.wxdgaming.batis.sql.FlushResult;
import com.wxdgaming.batis.sql.QueryRunner;
import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CachedBeanRunner 带缓存与延迟批刷模式的集成测试
 * <p>覆盖查询缓存（含 Tombstone）、延迟/立即写入、批量刷盘、监控等核心功能。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-08-05
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CachedBeanRunnerTest {

    static final Logger log = LoggerFactory.getLogger(CachedBeanRunnerTest.class);
    static final String TABLE_NAME = "t_order";

    static MysqlTemplate template;
    static CachedBeanRunner cachedRunner;

    @RegisterExtension
    static MariaDB4jExtension maria = new MariaDB4jExtension();

    @BeforeAll
    static void setUp() {
        if (template != null) return;
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(maria.getJdbcUrl());
        config.setUsername("root");
        config.setPassword("");
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        template = new MysqlTemplate(config);
        cachedRunner = template.cachedBeanRunner();
    }

    @BeforeEach
    void ensureTable() {
        template.checkTable(OrderEntity.class);
        template.checkTable(RoleEntity.class);
        template.checkTable(AccountEntity.class);
    }

    @AfterEach
    void cleanUp() {
        cachedRunner.clearAll();
        try (QueryRunner qr = new QueryRunner(template)) {
            qr.update("DELETE FROM `" + TABLE_NAME + "`");
            qr.update("DELETE FROM `t_role`");
            qr.update("DELETE FROM `t_account`");
        }
    }

    // ==================== queryById 查询测试 ====================

    @Test
    @Order(1)
    void testQueryByIdCacheMissThenHit() {
        // 先直接写入一条到 DB
        OrderEntity order = buildSampleOrder("缓存查询测试", 1, 100, false, new BigDecimal("1.50"));
        try (BeanRunner br = template.beanRunner()) {
            br.insertBean(order);
        }
        long id = order.getId();

        // 第一次查询：缓存未命中，走 DB 加载
        OrderEntity result1 = cachedRunner.queryById(OrderEntity.class, id);
        assertNotNull(result1, "第一次查询应返回实体");
        assertEquals("缓存查询测试", result1.getTitle());
        assertEquals(1, cachedRunner.cacheSize(), "缓存应包含 1 条");

        // 第二次查询：应命中缓存
        OrderEntity result2 = cachedRunner.queryById(OrderEntity.class, id);
        assertNotNull(result2, "第二次查询应返回实体");
        assertSame(result1, result2, "两次查询应返回同一缓存实例");

        log.info("queryById 缓存命中测试通过");
    }

    @Test
    @Order(2)
    void testQueryByIdNotFound() {
        // 查询不存在的 ID
        OrderEntity result = cachedRunner.queryById(OrderEntity.class, 99999L);
        assertNull(result, "不存在的 ID 应返回 null");

        log.info("queryById 未找到测试通过");
    }

    @Test
    @Order(3)
    void testQueryByIdTombstone() {
        // 插入后延迟删除 → Tombstone
        OrderEntity order = buildSampleOrder("Tombstone测试", 1, 100, false, new BigDecimal("1.00"));
        try (BeanRunner br = template.beanRunner()) {
            br.insertBean(order);
        }
        long id = order.getId();

        // 先查询一次让缓存加载
        cachedRunner.queryById(OrderEntity.class, id);

        // 延迟删除：缓存写入 Tombstone
        cachedRunner.deleteBean(order, false);

        // 再次查询应返回 null（Tombstone 命中）
        OrderEntity result = cachedRunner.queryById(OrderEntity.class, id);
        assertNull(result, "Tombstone 命中应返回 null");

        log.info("queryById Tombstone 测试通过");
    }

    // ==================== insertBean 插入测试 ====================

    @Test
    @Order(4)
    void testInsertBeanImmediate() {
        OrderEntity order = buildSampleOrder("立即插入测试", 5, 500, true, new BigDecimal("25.00"));

        Number genId = cachedRunner.insertBean(order, true);
        assertNotNull(genId, "立即插入应返回自增主键");
        assertTrue(genId.longValue() > 0);
        assertEquals(genId.longValue(), order.getId(), "自增主键应回写到 bean");

        // 应立即写入缓存，且无脏数据
        assertEquals(1, cachedRunner.cacheSize(), "立即插入后缓存应为 1");
        assertEquals(0, cachedRunner.dirtyInsertCount());

        // 验证 DB 中确实存在
        OrderEntity fromDb = cachedRunner.queryById(OrderEntity.class, genId.longValue());
        assertNotNull(fromDb);
        assertEquals("立即插入测试", fromDb.getTitle());

        log.info("立即插入测试通过: id={}", genId.longValue());
    }

    @Test
    @Order(5)
    void testInsertBeanDelayed() {
        OrderEntity order = buildSampleOrder("延迟插入测试", 2, 200, false, new BigDecimal("4.00"));

        Number result = cachedRunner.insertBean(order, false);
        assertNull(result, "延迟插入应返回 null");
        assertEquals(1, cachedRunner.dirtyInsertCount(), "应有 1 条脏插入");
        assertTrue(cachedRunner.cacheSize() > 0, "缓存应包含该实体");

        // DB 中不应有数据（延迟模式未写 DB）
        try (BeanRunner br = template.beanRunner()) {
            List<OrderEntity> all = br.queryAll(OrderEntity.class);
            assertTrue(all.isEmpty(), "延迟模式下 DB 应为空");
        }

        log.info("延迟插入测试通过，脏计数: insert={}, update={}, delete={}",
                cachedRunner.dirtyInsertCount(), cachedRunner.dirtyUpdateCount(), cachedRunner.dirtyDeleteCount());
    }

    // ==================== updateBean 更新测试 ====================

    @Test
    @Order(6)
    void testUpdateBeanImmediate() {
        // 先立即插入
        OrderEntity order = buildSampleOrder("更新前", 1, 100, false, new BigDecimal("1.00"));
        cachedRunner.insertBean(order, true);
        long id = order.getId();

        // 立即更新
        order.setTitle("立即更新后");
        order.setQuantity(10);
        int rows = cachedRunner.updateBean(order, true);
        assertEquals(1, rows, "立即更新应影响 1 行");

        // 验证缓存
        OrderEntity result = cachedRunner.queryById(OrderEntity.class, id);
        assertNotNull(result);
        assertEquals("立即更新后", result.getTitle());
        assertEquals(10, result.getQuantity());

        // 立即更新后不应有脏数据
        assertEquals(0, cachedRunner.dirtyUpdateCount());

        log.info("立即更新测试通过");
    }

    @Test
    @Order(7)
    void testUpdateBeanDelayed() {
        // 先立即插入到 DB
        OrderEntity order = buildSampleOrder("延迟更新前", 3, 300, false, new BigDecimal("9.00"));
        cachedRunner.insertBean(order, true);
        long id = order.getId();

        // 查询加载到缓存
        cachedRunner.queryById(OrderEntity.class, id);

        // 延迟更新
        order.setTitle("延迟更新后");
        order.setQuantity(5);
        int rows = cachedRunner.updateBean(order, false);
        assertEquals(-1, rows, "延迟更新应返回 -1");
        assertEquals(1, cachedRunner.dirtyUpdateCount(), "应有 1 条脏更新");

        // 缓存中应为新值
        OrderEntity cached = cachedRunner.queryById(OrderEntity.class, id);
        assertNotNull(cached);
        assertEquals("延迟更新后", cached.getTitle());
        assertEquals(5, cached.getQuantity());

        // DB 中应仍是旧值
        try (BeanRunner br = template.beanRunner()) {
            OrderEntity db = br.queryById(OrderEntity.class, id);
            assertEquals("延迟更新前", db.getTitle(), "DB 中应仍是旧值");
        }

        log.info("延迟更新测试通过，脏计数: update={}", cachedRunner.dirtyUpdateCount());
    }

    @Test
    @Order(8)
    void testUpdateBeanOnDirtyInsert() {
        // 延迟插入后又延迟更新：应仍在 insert 脏表中，而非进入 update 脏表
        OrderEntity order = buildSampleOrder("脏插入更新", 1, 100, false, new BigDecimal("1.00"));
        cachedRunner.insertBean(order, false);

        assertEquals(1, cachedRunner.dirtyInsertCount());
        assertEquals(0, cachedRunner.dirtyUpdateCount());

        order.setTitle("脏插入后更新");
        cachedRunner.updateBean(order, false);

        assertEquals(1, cachedRunner.dirtyInsertCount(), "更新脏插入应仍在 insert 表");
        assertEquals(0, cachedRunner.dirtyUpdateCount(), "更新脏插入不应进入 update 表");

        log.info("脏插入上的延迟更新测试通过");
    }

    // ==================== deleteBean 删除测试 ====================

    @Test
    @Order(9)
    void testDeleteBeanImmediate() {
        OrderEntity order = buildSampleOrder("立即删除", 1, 100, false, new BigDecimal("1.00"));
        cachedRunner.insertBean(order, true);
        long id = order.getId();

        int rows = cachedRunner.deleteBean(order, true);
        assertEquals(1, rows, "立即删除应影响 1 行");

        // 缓存应被清除，查询返回 null
        OrderEntity result = cachedRunner.queryById(OrderEntity.class, id);
        assertNull(result, "立即删除后查询应返回 null");

        log.info("立即删除测试通过");
    }

    @Test
    @Order(10)
    void testDeleteBeanDelayed() {
        OrderEntity order = buildSampleOrder("延迟删除", 2, 200, false, new BigDecimal("4.00"));
        cachedRunner.insertBean(order, true);
        long id = order.getId();

        // 加载到缓存
        cachedRunner.queryById(OrderEntity.class, id);

        // 延迟删除
        int rows = cachedRunner.deleteBean(order, false);
        assertEquals(-1, rows, "延迟删除应返回 -1");
        assertEquals(1, cachedRunner.dirtyDeleteCount(), "应有 1 条脏删除");

        // 查询命中 Tombstone → null
        OrderEntity result = cachedRunner.queryById(OrderEntity.class, id);
        assertNull(result, "延迟删除后 Tombstone 命中应返回 null");

        // DB 中应仍存在
        try (BeanRunner br = template.beanRunner()) {
            OrderEntity db = br.queryById(OrderEntity.class, id);
            assertNotNull(db, "DB 中应仍存在（尚未刷盘）");
        }

        log.info("延迟删除测试通过，脏计数: delete={}", cachedRunner.dirtyDeleteCount());
    }

    // ==================== flush 刷盘测试 ====================

    @Test
    @Order(11)
    void testFlushAll() {
        // 1. 延迟插入 2 条
        OrderEntity order1 = buildSampleOrder("刷盘插入1", 1, 100, false, new BigDecimal("1.00"));
        OrderEntity order2 = buildSampleOrder("刷盘插入2", 2, 200, false, new BigDecimal("4.00"));
        cachedRunner.insertBean(order1, false);
        cachedRunner.insertBean(order2, false);

        // 2. 立即插入 1 条到 DB，再延迟更新
        OrderEntity order3 = buildSampleOrder("刷盘更新", 3, 300, false, new BigDecimal("9.00"));
        cachedRunner.insertBean(order3, true);
        long id3 = order3.getId();
        cachedRunner.queryById(OrderEntity.class, id3);
        order3.setTitle("已刷盘更新");
        cachedRunner.updateBean(order3, false);

        // 3. 立即插入 1 条到 DB，再延迟删除
        OrderEntity order4 = buildSampleOrder("刷盘删除", 4, 400, false, new BigDecimal("16.00"));
        cachedRunner.insertBean(order4, true);
        long id4 = order4.getId();
        cachedRunner.queryById(OrderEntity.class, id4);
        cachedRunner.deleteBean(order4, false);

        // 脏数据统计
        assertEquals(2, cachedRunner.dirtyInsertCount());
        assertEquals(1, cachedRunner.dirtyUpdateCount());
        assertEquals(1, cachedRunner.dirtyDeleteCount());

        // 刷盘
        FlushResult result = cachedRunner.flushAll();
        assertEquals(2, result.getInsertCount(), "应有 2 条脏插入");
        assertEquals(1, result.getUpdateCount(), "应有 1 条脏更新");
        assertEquals(1, result.getDeleteCount(), "应有 1 条脏删除");

        // 刷盘后脏数据清空
        assertEquals(0, cachedRunner.dirtyTotalCount());

        // 验证 DB：2 条 insert + 1 条 update = 3 条；1 条 delete 已删除
        try (BeanRunner br = template.beanRunner()) {
            List<OrderEntity> all = br.queryAll(OrderEntity.class);
            assertEquals(3, all.size(), "刷盘后 DB 应有 3 条");

            OrderEntity updated = br.queryById(OrderEntity.class, id3);
            assertEquals("已刷盘更新", updated.getTitle());

            OrderEntity deleted = br.queryById(OrderEntity.class, id4);
            assertNull(deleted);
        }

        log.info("批量刷盘测试通过: {}", result);
    }

    @Test
    @Order(12)
    void testFlushSingleBean() {
        OrderEntity order = buildSampleOrder("单条刷盘", 1, 100, false, new BigDecimal("1.00"));
        cachedRunner.insertBean(order, false);
        assertEquals(1, cachedRunner.dirtyInsertCount());

        FlushResult result = cachedRunner.flush(order);
        assertEquals(1, result.getInsertCount());
        assertEquals(0, cachedRunner.dirtyInsertCount());

        // 验证 DB：getGeneratedKeys 已将 id 回写
        OrderEntity fromDb = cachedRunner.queryById(OrderEntity.class, order.getId());
        assertNotNull(fromDb, "单条刷盘后可从 DB 查到");

        log.info("单条刷盘测试通过: {}", result);
    }

    @Test
    @Order(13)
    void testFlushByClass() {
        OrderEntity order = buildSampleOrder("按类刷盘", 5, 500, true, new BigDecimal("25.00"));
        cachedRunner.insertBean(order, false);
        assertEquals(1, cachedRunner.dirtyInsertCount());

        FlushResult result = cachedRunner.flush(OrderEntity.class);
        assertEquals(1, result.getInsertCount());
        assertEquals(0, cachedRunner.dirtyInsertCount());

        log.info("按类刷盘测试通过: {}", result);
    }

    // ==================== 综合场景测试 ====================

    @Test
    @Order(14)
    void testInsertThenUpdateThenFlush() {
        // 延迟插入 → 延迟更新 → 刷盘：应只执行一次 INSERT（含更新后的值）
        OrderEntity order = buildSampleOrder("插入后更新再刷", 1, 100, false, new BigDecimal("1.00"));
        cachedRunner.insertBean(order, false);
        assertEquals(1, cachedRunner.dirtyInsertCount());

        order.setTitle("已更新内容");
        order.setQuantity(10);
        cachedRunner.updateBean(order, false);

        // 脏插入上更新，应仍在 insert 表
        assertEquals(1, cachedRunner.dirtyInsertCount());
        assertEquals(0, cachedRunner.dirtyUpdateCount());

        FlushResult result = cachedRunner.flushAll();
        assertEquals(1, result.getInsertCount());
        assertEquals(0, result.getUpdateCount());
        assertEquals(0, result.getDeleteCount());

        // DB 中应有更新后的值（getGeneratedKeys 已将自增 id 回写）
        OrderEntity fromDb = cachedRunner.queryById(OrderEntity.class, order.getId());
        assertNotNull(fromDb);
        assertEquals("已更新内容", fromDb.getTitle());
        assertEquals(10, fromDb.getQuantity());

        log.info("插入后更新再刷盘测试通过");
    }

    @Test
    @Order(15)
    void testInsertThenDeleteThenFlush() {
        // 延迟插入 → 延迟删除 → 刷盘：不应 INSERT，只执行 DELETE
        OrderEntity order = buildSampleOrder("插入后删除", 2, 200, false, new BigDecimal("4.00"));
        cachedRunner.insertBean(order, false);
        assertEquals(1, cachedRunner.dirtyInsertCount());

        cachedRunner.deleteBean(order, false);

        // delete 操作已将 insert 移除，转为 delete 脏数据
        assertEquals(0, cachedRunner.dirtyInsertCount(), "删除后 insert 脏表应为空");
        assertEquals(1, cachedRunner.dirtyDeleteCount(), "应有 1 条脏删除");

        FlushResult result = cachedRunner.flushAll();
        assertEquals(0, result.getInsertCount());
        assertEquals(1, result.getDeleteCount());
        assertEquals(0, cachedRunner.dirtyDeleteCount());

        log.info("插入后删除再刷盘测试通过");
    }

    // ==================== 透传方法测试 ====================

    @Test
    @Order(16)
    void testQueryAllTransparent() {
        // queryAll 是透传方法，不走缓存
        OrderEntity order1 = buildSampleOrder("透传查询1", 1, 100, false, new BigDecimal("1.00"));
        OrderEntity order2 = buildSampleOrder("透传查询2", 2, 200, false, new BigDecimal("4.00"));
        cachedRunner.insertBean(order1, true);
        cachedRunner.insertBean(order2, true);

        List<OrderEntity> all = cachedRunner.queryAll(OrderEntity.class);
        assertNotNull(all);
        assertEquals(2, all.size(), "透传 queryAll 应返回 2 条");

        log.info("透传 queryAll 测试通过，返回 {} 条", all.size());
    }

    // ==================== 监控方法 & clearAll 测试 ====================

    @Test
    @Order(17)
    void testMonitoring() {
        assertEquals(0, cachedRunner.dirtyTotalCount());
        assertEquals(0, cachedRunner.dirtyInsertCount());
        assertEquals(0, cachedRunner.dirtyUpdateCount());
        assertEquals(0, cachedRunner.dirtyDeleteCount());
        assertEquals(0, cachedRunner.cacheSize());

        OrderEntity order = buildSampleOrder("监控测试", 1, 100, false, new BigDecimal("1.00"));
        cachedRunner.insertBean(order, false);

        assertEquals(1, cachedRunner.dirtyInsertCount());
        assertEquals(1, cachedRunner.dirtyTotalCount());
        assertTrue(cachedRunner.cacheSize() > 0);

        cachedRunner.clearAll();
        assertEquals(0, cachedRunner.dirtyTotalCount(), "clearAll 后脏数据应为 0");
        assertEquals(0, cachedRunner.cacheSize(), "clearAll 后缓存应为 0");

        log.info("监控方法与 clearAll 测试通过");
    }

    // ==================== 长时间运行仿真测试 ====================

    /**
     * 长时间运行仿真测试：模拟真实环境中频繁的增删改查与缓存过期行为。
     * <p>使用短 TTL (3s expireAfterAccess) 的本地缓存，在 30 秒内循环执行：
     * 延迟/立即插入、延迟/立即更新、延迟/立即删除、缓存过期淘汰、刷盘。</p>
     * <p>每轮周期结束后断言脏数据已刷盘，最终断言系统一致。</p>
     */
    @Test
    @Order(18)
    @Timeout(value = 120, unit = java.util.concurrent.TimeUnit.SECONDS)
    void testLongRunningSimulation() throws InterruptedException {
        // 短 TTL 缓存：3 秒无人访问即过期，2 秒后触发异步刷新
        LocalEntityCache<String, Object> shortCache = new LocalEntityCache<>(
                100_000,
                3,   // expireAfterAccess
                2,   // refreshAfterWrite
                TimeUnit.SECONDS
        );
        CachedBeanRunner runner = template.cachedBeanRunner(shortCache);

        final long durationMs = TimeUnit.SECONDS.toMillis(30);
        final long startMs = System.currentTimeMillis();
        final long endMs = startMs + durationMs;

        long totalInserts = 0, totalUpdates = 0, totalDeletes = 0;
        long immediateInserts = 0, immediateUpdates = 0, immediateDeletes = 0;
        int cycles = 0;

        log.info("========== 长时间运行仿真开始，预计运行 {} 秒 ==========", durationMs / 1000);

        try {
            while (System.currentTimeMillis() < endMs) {
                cycles++;
                log.info("--- 周期 #{} 开始: dirty[I={}, U={}, D={}], cache={} ---",
                        cycles,
                        runner.dirtyInsertCount(), runner.dirtyUpdateCount(),
                        runner.dirtyDeleteCount(), runner.cacheSize());

                // ===== 1. 延迟批量插入 =====
                int batchSize = ThreadLocalRandom.current().nextInt(3, 15);
                for (int i = 0; i < batchSize; i++) {
                    OrderEntity order = buildSampleOrder(
                            "长测-" + totalInserts + "-c" + cycles,
                            ThreadLocalRandom.current().nextInt(1, 100),
                            ThreadLocalRandom.current().nextInt(10, 1000),
                            ThreadLocalRandom.current().nextBoolean(),
                            BigDecimal.valueOf(ThreadLocalRandom.current().nextDouble(1, 500))
                    );
                    runner.insertBean(order, false);
                    totalInserts++;
                }
                assertTrue(runner.dirtyInsertCount() > 0 || runner.cacheSize() > 0,
                        "延迟插入后应有脏数据或缓存条目");

                // ===== 2. 立即插入少量关键数据 =====
                int immIns = ThreadLocalRandom.current().nextInt(0, 3);
                for (int i = 0; i < immIns; i++) {
                    OrderEntity order = buildSampleOrder(
                            "立即插入-" + immediateInserts + "-c" + cycles,
                            ThreadLocalRandom.current().nextInt(1, 50),
                            ThreadLocalRandom.current().nextInt(100, 5000),
                            true,
                            BigDecimal.valueOf(ThreadLocalRandom.current().nextDouble(10, 100))
                    );
                    Number genId = runner.insertBean(order, true);
                    assertNotNull(genId, "立即插入应返回自增 ID");
                    assertTrue(genId.longValue() > 0);
                    assertEquals(genId.longValue(), order.getId(), "自增 ID 应回写到 bean");
                    immediateInserts++;
                    totalInserts++;
                }

                // ===== 3. 刷盘（延迟插入落库、获取自增 ID） =====
                FlushResult flush1 = runner.flushAll();
                log.info("刷盘 #{} (周期内): {}", cycles, flush1);
                assertEquals(0, runner.dirtyTotalCount(), "刷盘后脏数据计数应为 0");

                // ===== 4. 查询 DB 当前状态，用于后续更新/删除 =====
                List<OrderEntity> all = runner.queryAll(OrderEntity.class);
                int dbCount = all.size();
                log.info("周期 #{} DB 记录数: {}", cycles, dbCount);

                if (dbCount > 0) {
                    // 4a. 延迟更新 — 先加载到缓存，再更新，验证缓存与 DB 差异
                    int updCount = Math.min(
                            ThreadLocalRandom.current().nextInt(1, Math.min(8, dbCount + 1)),
                            dbCount
                    );
                    for (int i = 0; i < updCount; i++) {
                        OrderEntity entity = all.get(ThreadLocalRandom.current().nextInt(dbCount));
                        OrderEntity cached = runner.queryById(OrderEntity.class, entity.getId());
                        assertNotNull(cached, "queryById 应返回已存在的实体");

                        String oldTitle = cached.getTitle();
                        int oldQty = cached.getQuantity();
                        cached.setTitle("更新-" + totalUpdates);
                        cached.setQuantity(oldQty + ThreadLocalRandom.current().nextInt(1, 10));
                        int rows = runner.updateBean(cached, false);
                        assertEquals(-1, rows, "延迟更新应返回 -1");

                        // 缓存中应为新值
                        OrderEntity recheck = runner.queryById(OrderEntity.class, entity.getId());
                        assertNotEquals(oldTitle, recheck.getTitle(), "缓存中应为更新后的值");
                        totalUpdates++;
                    }

                    // 刷盘 4a 累积的脏更新，避免干扰 4b 的断言
                    FlushResult flushAfterDelay = runner.flushAll();
                    log.info("刷盘 #{} (延迟更新后): {}", cycles, flushAfterDelay);
                    assertEquals(0, runner.dirtyTotalCount(), "延迟更新刷盘后脏数据应为 0");

                    // 4b. 立即更新
                    if (ThreadLocalRandom.current().nextBoolean()) {
                        OrderEntity entity = all.get(ThreadLocalRandom.current().nextInt(dbCount));
                        runner.queryById(OrderEntity.class, entity.getId());
                        entity.setTitle("立即更新-" + System.currentTimeMillis() % 100000);
                        int rows = runner.updateBean(entity, true);
                        assertTrue(rows >= 0, "立即更新不应抛异常");
                        assertEquals(0, runner.dirtyUpdateCount(), "立即更新后不应有脏更新");
                        immediateUpdates++;
                        totalUpdates++;
                    }

                    // 4c. 延迟删除 → Tombstone
                    int delCount = Math.min(
                            ThreadLocalRandom.current().nextInt(0, Math.min(4, dbCount / 2 + 1)),
                            dbCount
                    );
                    for (int i = 0; i < delCount; i++) {
                        OrderEntity entity = all.get(ThreadLocalRandom.current().nextInt(dbCount));
                        long entityId = entity.getId();
                        runner.queryById(OrderEntity.class, entityId);
                        int rows = runner.deleteBean(entity, false);
                        assertEquals(-1, rows, "延迟删除应返回 -1");

                        // Tombstone 验证
                        OrderEntity tombstoneCheck = runner.queryById(OrderEntity.class, entityId);
                        assertNull(tombstoneCheck, "延迟删除后 Tombstone 命中应返回 null");
                        totalDeletes++;
                    }

                    // 4d. 立即删除
                    if (ThreadLocalRandom.current().nextInt(5) == 0 && dbCount > 0) {
                        OrderEntity entity = all.get(ThreadLocalRandom.current().nextInt(dbCount));
                        long entityId = entity.getId();
                        runner.queryById(OrderEntity.class, entityId);
                        int rows = runner.deleteBean(entity, true);
                        assertTrue(rows >= 0, "立即删除不应抛异常");
                        OrderEntity afterDel = runner.queryById(OrderEntity.class, entityId);
                        assertNull(afterDel, "立即删除后查询应返回 null");
                        immediateDeletes++;
                        totalDeletes++;
                    }
                }

                // ===== 5. 周期末刷盘（清空本周期累积的脏数据） =====
                FlushResult flush2 = runner.flushAll();
                log.info("刷盘 #{} (周期末): {}", cycles, flush2);
                assertEquals(0, runner.dirtyTotalCount(), "周期末刷盘后脏数据应为 0");

                // ===== 6. 模拟缓存过期 =====
                if (runner.cacheSize() > 0 && cycles % 3 == 0) {
                    long preSleepCache = runner.cacheSize();
                    int preSleepDirty = runner.dirtyTotalCount();
                    log.info("等待 4 秒触发缓存过期 (当前 cache={}, dirty={})...",
                            preSleepCache, preSleepDirty);

                    // 超过 expireAfterAccess (3s)，Caffeine 淘汰过期条目
                    Thread.sleep(4000);

                    // 再次访问以触发 Caffeine 维护周期
                    long postSleepCache = runner.cacheSize();
                    int postSleepDirty = runner.dirtyTotalCount();
                    log.info("过期等待后: cache={} (前 {}), dirty={} (前 {})",
                            postSleepCache, preSleepCache, postSleepDirty, preSleepDirty);
                }

                // ===== 7. 间隔，模拟真实请求节奏 =====
                long remaining = endMs - System.currentTimeMillis();
                if (remaining > 0) {
                    Thread.sleep(Math.min(
                            ThreadLocalRandom.current().nextLong(100, 400),
                            remaining
                    ));
                }
            }
        } finally {
            // ===== 最终收尾：确保所有脏数据落库 =====
            FlushResult finalFlush = runner.flushAll();
            log.info("最终刷盘: {}", finalFlush);

            int finalDirty = runner.dirtyTotalCount();
            long finalCache = runner.cacheSize();

            // 清理：清空缓存和 DB
            runner.clearAll();
            try (BeanRunner br = template.beanRunner()) {
                br.deleteAll(OrderEntity.class);
            }

            log.info("========== 长时间运行仿真结束 (共 {} 个周期) ==========", cycles);
            log.info("操作统计: 插入={}(立即={}), 更新={}(立即={}), 删除={}(立即={})",
                    totalInserts, immediateInserts,
                    totalUpdates, immediateUpdates,
                    totalDeletes, immediateDeletes);
            log.info("最终状态: dirty={}, cache={}", finalDirty, finalCache);

            // ===== 最终断言 =====
            assertTrue(totalInserts > 0, "应至少有一次插入操作");
            assertTrue(cycles > 1, "应至少完成 2 个周期");
            assertEquals(0, finalDirty, "最终不应有未刷盘的脏数据");
        }
    }

    // ======================== 辅助方法 ========================

    /** 构建一条示例订单 */
    private static OrderEntity buildSampleOrder(String title, int quantity, int price,
                                                boolean paid, BigDecimal totalAmount) {
        OrderEntity order = new OrderEntity();
        order.setTitle(title);
        order.setQuantity(quantity);
        order.setPrice(price);
        order.setPaid(paid);
        order.setCreateTime(new Date());
        order.setTotalAmount(totalAmount);
        return order;
    }

    // ==================== 多线程多表并发测试 ====================

    private static final Class<?>[] ENTITY_CLASSES = {
            OrderEntity.class, RoleEntity.class, AccountEntity.class
    };

    @Test
    @Order(19)
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void testMultiThreadMultiTable() throws InterruptedException {
        final int threadCount = 12;
        final int roundsPerThread = 40;

        // 阶段1: 预插入种子数据（每个表10条，立即写入DB）
        for (Class<?> clz : ENTITY_CLASSES) {
            for (int i = 0; i < 10; i++) {
                Object bean = buildBean(clz, "seed-" + i + "-" + getTableName(clz));
                Number genId = cachedRunner.insertBean(bean, true);
                assertNotNull(genId, "种子数据插入应返回ID");
            }
        }
        log.info("种子数据插入完成，每表10条");

        // 统计计数器
        AtomicInteger delayedInserts = new AtomicInteger(0);
        AtomicInteger immediateInserts = new AtomicInteger(0);
        AtomicInteger delayedUpdates = new AtomicInteger(0);
        AtomicInteger immediateUpdates = new AtomicInteger(0);
        AtomicInteger delayedDeletes = new AtomicInteger(0);
        AtomicInteger immediateDeletes = new AtomicInteger(0);
        AtomicInteger queryByIdCount = new AtomicInteger(0);
        AtomicInteger flushCount = new AtomicInteger(0);
        AtomicInteger queryAllCount = new AtomicInteger(0);

        ConcurrentLinkedQueue<String> errorMessages = new ConcurrentLinkedQueue<>();

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        // 阶段2: 启动12个工作线程，共享 cachedRunner 实例
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int r = 0; r < roundsPerThread; r++) {
                        int op = ThreadLocalRandom.current().nextInt(100);
                        Class<?> clz = ENTITY_CLASSES[ThreadLocalRandom.current().nextInt(ENTITY_CLASSES.length)];

                        if (op < 20) {
                            doDelayedInsert(clz);
                            delayedInserts.incrementAndGet();
                        } else if (op < 25) {
                            doImmediateInsert(clz);
                            immediateInserts.incrementAndGet();
                        } else if (op < 45) {
                            doDelayedUpdate(clz);
                            delayedUpdates.incrementAndGet();
                        } else if (op < 50) {
                            doImmediateUpdate(clz);
                            immediateUpdates.incrementAndGet();
                        } else if (op < 60) {
                            doDelayedDelete(clz);
                            delayedDeletes.incrementAndGet();
                        } else if (op < 65) {
                            doImmediateDelete(clz);
                            immediateDeletes.incrementAndGet();
                        } else if (op < 80) {
                            doQueryById(clz);
                            queryByIdCount.incrementAndGet();
                        } else if (op < 90) {
                            doFlush(clz);
                            flushCount.incrementAndGet();
                        } else {
                            doQueryAll(clz);
                            queryAllCount.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    errorMessages.offer("Thread-" + threadId + ": " + e.getMessage());
                    log.error("Thread-{} 异常", threadId, e);
                } finally {
                    doneLatch.countDown();
                }
            }, "test-worker-" + threadId).start();
        }

        // 同时启动所有线程
        log.info("========== 多线程多表并发测试开始: {} 线程 x {} 轮 ==========", threadCount, roundsPerThread);
        startLatch.countDown();

        // 等待所有线程完成
        boolean finished = doneLatch.await(120, TimeUnit.SECONDS);
        assertTrue(finished, "所有线程应在超时前完成");

        // 阶段3: 验证
        assertTrue(errorMessages.isEmpty(), "不应有线程异常: " + errorMessages);

        // 最终刷盘
        FlushResult finalFlush = cachedRunner.flushAll();
        log.info("多线程最终刷盘: {}", finalFlush);

        // 最终检查：脏数据应为0
        int finalDirty = cachedRunner.dirtyTotalCount();
        long finalCacheSize = cachedRunner.cacheSize();
        log.info("最终状态: dirty={}, cacheSize={}", finalDirty, finalCacheSize);
        assertEquals(0, finalDirty, "最终不应有未刷盘的脏数据");

        // 验证各表DB数据一致性
        for (Class<?> clz : ENTITY_CLASSES) {
            List<?> all = cachedRunner.queryAll(clz);
            log.info("表 {} 最终记录数: {}", getTableName(clz), all != null ? all.size() : 0);
        }

        int totalOps = delayedInserts.get() + immediateInserts.get()
                + delayedUpdates.get() + immediateUpdates.get()
                + delayedDeletes.get() + immediateDeletes.get()
                + queryByIdCount.get() + flushCount.get() + queryAllCount.get();

        log.info("========== 多线程多表并发测试结束 ==========");
        log.info("操作统计(total={}): 延迟插入={}, 立即插入={}, 延迟更新={}, 立即更新={}, 延迟删除={}, 立即删除={}, queryById={}, flush={}, queryAll={}",
                totalOps,
                delayedInserts.get(), immediateInserts.get(),
                delayedUpdates.get(), immediateUpdates.get(),
                delayedDeletes.get(), immediateDeletes.get(),
                queryByIdCount.get(), flushCount.get(), queryAllCount.get());

        DbStats stats = cachedRunner.getJdbcTemplate().getStats();
        String prettyString = stats.toPrettyString();
        log.info("DB 统计: \n{}", prettyString);

        assertTrue(totalOps > 0, "应至少执行了一些操作");
    }

    // ======================== 多线程辅助方法 ========================

    private String getTableName(Class<?> clz) {
        if (clz == OrderEntity.class) return "t_order";
        if (clz == RoleEntity.class) return "t_role";
        if (clz == AccountEntity.class) return "t_account";
        throw new IllegalArgumentException("Unknown entity: " + clz);
    }

    private Object buildBean(Class<?> clz, String suffix) {
        if (clz == OrderEntity.class) {
            return buildSampleOrder("mt-" + suffix,
                    ThreadLocalRandom.current().nextInt(1, 50),
                    ThreadLocalRandom.current().nextInt(10, 500),
                    ThreadLocalRandom.current().nextBoolean(),
                    BigDecimal.valueOf(ThreadLocalRandom.current().nextDouble(1, 200)));
        }
        if (clz == RoleEntity.class) {
            return buildSampleRole("mt-" + suffix);
        }
        if (clz == AccountEntity.class) {
            return buildSampleAccount("mt-" + suffix);
        }
        throw new IllegalArgumentException("Unknown entity: " + clz);
    }

    private void mutateBean(Object bean) {
        if (bean instanceof OrderEntity) {
            OrderEntity o = (OrderEntity) bean;
            o.setTitle(o.getTitle() + "-u");
            o.setQuantity(o.getQuantity() + ThreadLocalRandom.current().nextInt(1, 5));
            o.setPrice(o.getPrice() + ThreadLocalRandom.current().nextInt(1, 10));
        } else if (bean instanceof RoleEntity) {
            RoleEntity r = (RoleEntity) bean;
            r.setName(r.getName() + "-u");
            r.setLevel(r.getLevel() + ThreadLocalRandom.current().nextInt(1, 5));
            r.setScore(r.getScore() + ThreadLocalRandom.current().nextInt(10, 100));
        } else if (bean instanceof AccountEntity) {
            AccountEntity a = (AccountEntity) bean;
            a.setAccountName(a.getAccountName() + "-u");
            a.setBalance(a.getBalance().add(
                    BigDecimal.valueOf(ThreadLocalRandom.current().nextDouble(1, 100))));
            a.setVipLevel(a.getVipLevel() + 1);
        }
    }

    /** 从DB中随机选取一条已存在的记录 */
    private Object pickRandomExisting(Class<?> clz) {
        List<?> all = cachedRunner.queryAll(clz);
        if (all == null || all.isEmpty()) return null;
        return all.get(ThreadLocalRandom.current().nextInt(all.size()));
    }

    private long extractId(Object bean) {
        if (bean instanceof OrderEntity) return ((OrderEntity) bean).getId();
        if (bean instanceof RoleEntity) return ((RoleEntity) bean).getId();
        if (bean instanceof AccountEntity) return ((AccountEntity) bean).getId();
        throw new IllegalArgumentException("Unknown bean: " + bean.getClass());
    }

    private void doDelayedInsert(Class<?> clz) {
        Object bean = buildBean(clz, "delayed-" + System.nanoTime());
        cachedRunner.insertBean(bean, false);
    }

    private void doImmediateInsert(Class<?> clz) {
        Object bean = buildBean(clz, "immediate-" + System.nanoTime());
        Number genId = cachedRunner.insertBean(bean, true);
        assertNotNull(genId, "立即插入应返回ID");
    }

    private void doDelayedUpdate(Class<?> clz) {
        Object existing = pickRandomExisting(clz);
        if (existing == null) {
            // 没有可更新的记录，插入一条
            doDelayedInsert(clz);
            return;
        }
        long id = extractId(existing);
        Object cached = cachedRunner.queryById(clz, id);
        if (cached == null) return; // 可能已被其他线程删除
        mutateBean(cached);
        cachedRunner.updateBean(cached, false);
    }

    private void doImmediateUpdate(Class<?> clz) {
        Object existing = pickRandomExisting(clz);
        if (existing == null) {
            doImmediateInsert(clz);
            return;
        }
        long id = extractId(existing);
        Object cached = cachedRunner.queryById(clz, id);
        if (cached == null) return;
        mutateBean(cached);
        int rows = cachedRunner.updateBean(cached, true);
        assertTrue(rows >= 0, "立即更新不应抛异常");
    }

    private void doDelayedDelete(Class<?> clz) {
        Object existing = pickRandomExisting(clz);
        if (existing == null) return;
        long id = extractId(existing);
        Object cached = cachedRunner.queryById(clz, id);
        if (cached == null) return;
        int rows = cachedRunner.deleteBean(cached, false);
        assertEquals(-1, rows, "延迟删除应返回-1");
    }

    private void doImmediateDelete(Class<?> clz) {
        Object existing = pickRandomExisting(clz);
        if (existing == null) return;
        long id = extractId(existing);
        Object cached = cachedRunner.queryById(clz, id);
        if (cached == null) return;
        int rows = cachedRunner.deleteBean(cached, true);
        assertTrue(rows >= 0, "立即删除不应抛异常");
    }

    private void doQueryById(Class<?> clz) {
        long randomId = ThreadLocalRandom.current().nextLong(1, 200);
        Object result = cachedRunner.queryById(clz, randomId);
        if (result != null) {
            assertEquals(randomId, extractId(result), "查询返回的ID应与请求一致");
        }
    }

    private void doFlush(Class<?> clz) {
        FlushResult result = cachedRunner.flush(clz);
        assertNotNull(result, "刷盘结果不应为null");
    }

    private void doQueryAll(Class<?> clz) {
        List<?> all = cachedRunner.queryAll(clz);
        assertNotNull(all, "queryAll结果不应为null");
    }

    /** 构建一条示例角色 */
    private static RoleEntity buildSampleRole(String nameSuffix) {
        RoleEntity r = new RoleEntity();
        r.setName("角色-" + nameSuffix);
        r.setLevel(ThreadLocalRandom.current().nextInt(1, 100));
        r.setAccount("acc-" + nameSuffix);
        r.setScore(ThreadLocalRandom.current().nextInt(0, 1000));
        r.setBanned(ThreadLocalRandom.current().nextBoolean());
        r.setCreateTime(new Date());
        r.setRecharge(BigDecimal.valueOf(ThreadLocalRandom.current().nextDouble(0, 500)));
        r.setPrice(ThreadLocalRandom.current().nextFloat() * 100);
        return r;
    }

    /** 构建一条示例账号 */
    private static AccountEntity buildSampleAccount(String nameSuffix) {
        AccountEntity a = new AccountEntity();
        a.setAccountName("账号-" + nameSuffix);
        a.setBalance(BigDecimal.valueOf(ThreadLocalRandom.current().nextDouble(0, 10000)));
        a.setVipLevel(ThreadLocalRandom.current().nextInt(0, 10));
        a.setLastLoginTime(new Date());
        a.setFrozen(ThreadLocalRandom.current().nextBoolean());
        return a;
    }
}
