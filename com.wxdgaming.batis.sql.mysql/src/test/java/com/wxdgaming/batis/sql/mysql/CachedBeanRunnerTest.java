package com.wxdgaming.batis.sql.mysql;

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
    }

    @AfterEach
    void cleanUp() {
        cachedRunner.clearAll();
        try (QueryRunner qr = new QueryRunner(template)) {
            qr.update("DELETE FROM `" + TABLE_NAME + "`");
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
}
