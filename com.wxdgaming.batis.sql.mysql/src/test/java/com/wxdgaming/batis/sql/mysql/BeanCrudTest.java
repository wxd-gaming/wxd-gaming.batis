package com.wxdgaming.batis.sql.mysql;

import com.wxdgaming.batis.sql.Handlers;
import com.wxdgaming.batis.sql.JdbcTemplate;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bean CRUD 快捷操作集成测试
 * <p>测试 {@link QueryRunner#insertBean(Object)}、
 * {@link QueryRunner#updateBean(Object)} 以及
 * {@link Handlers#bean(JdbcTemplate, Class)}} /
 * {@link Handlers#beanList(JdbcTemplate, Class)} 查询。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BeanCrudTest {

    static final Logger log = LoggerFactory.getLogger(BeanCrudTest.class);
    static final String TABLE_NAME = "t_order";

    static MysqlTemplate template;

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
    }

    @BeforeEach
    void ensureTable() {
        // 每个测试方法前确保表存在；checkTable 幂等，表已存在时不会重复建
        template.checkTable(OrderEntity.class);
    }

    @AfterEach
    void cleanTable() {
        // 每条测试后清空数据，保证测试间无残留干扰
        try (var qr = new QueryRunner(template)) {
            qr.update("DELETE FROM `" + TABLE_NAME + "`");
        }
    }

    @Test
    @Order(1)
    void testInsertBean() {
        try (var qr = new QueryRunner(template)) {
            // ===== 构造订单 =====
            OrderEntity order = new OrderEntity();
            order.setTitle("测试商品A");
            order.setQuantity(2);
            order.setPrice(500);     // 单价 5 元（500 分）
            order.setPaid(false);
            order.setCreateTime(new Date());
            order.setTotalAmount(new BigDecimal("10.00"));

            // ===== 执行插入 =====
            Number genId = qr.insertBean(order);
            assertNotNull(genId, "自增主键不应为 null");
            assertTrue(genId.longValue() > 0, "自增主键应 > 0");

            // ===== 验证自增主键已回写到实体 =====
            assertEquals(genId.longValue(), order.getId(),
                    "insertBean 应将自增主键回写到 bean.id");

            log.info("插入订单成功, {}", order);
        }
    }

    @Test
    @Order(2)
    void testQueryByBean() {
        try (var qr = new QueryRunner(template)) {
            // ===== 先插入一条 =====
            OrderEntity order = buildSampleOrder("查询Bean测试", 3, 1000, false, new BigDecimal("30.00"));
            qr.insertBean(order);
            long insertedId = order.getId();

            // ===== 使用 template-aware bean 查询 =====
            OrderEntity result = qr.query(
                    "SELECT * FROM `" + TABLE_NAME + "` WHERE `id` = ?",
                    new Object[]{insertedId},
                    Handlers.bean(template, OrderEntity.class));

            assertNotNull(result, "bean 查询应返回对象");
            assertEquals(insertedId, result.getId());
            assertEquals("查询Bean测试", result.getTitle());
            assertEquals(3, result.getQuantity());
            assertEquals(1000, result.getPrice());
            assertFalse(result.isPaid());
            assertEquals(0, new BigDecimal("30.00").compareTo(result.getTotalAmount()),
                    "totalAmount 应一致");

            log.info("bean 查询通过: {}", result);
        }
    }

    @Test
    @Order(3)
    void testQueryBeanList() {
        try (var qr = new QueryRunner(template)) {
            // ===== 批量插入 3 条 =====
            List<OrderEntity> inserted = new ArrayList<>();
            for (int i = 1; i <= 3; i++) {
                OrderEntity order = buildSampleOrder(
                        "批量商品" + i, i, i * 100, i % 2 == 0,
                        new BigDecimal(String.format("%.2f", i * 1.5)));
                qr.insertBean(order);
                inserted.add(order);
            }

            // ===== 使用 template-aware beanList 查询全部 =====
            List<OrderEntity> list = qr.query(
                    "SELECT * FROM `" + TABLE_NAME + "` ORDER BY `id`",
                    Handlers.beanList(template, OrderEntity.class));

            assertNotNull(list);
            assertEquals(3, list.size(), "应查到 3 条记录");

            for (int i = 0; i < 3; i++) {
                OrderEntity expected = inserted.get(i);
                OrderEntity actual = list.get(i);
                assertEquals(expected.getId(), actual.getId());
                assertEquals(expected.getTitle(), actual.getTitle());
                assertEquals(expected.getQuantity(), actual.getQuantity());
                assertEquals(expected.getPrice(), actual.getPrice());
                assertEquals(expected.isPaid(), actual.isPaid());
                assertEquals(0, expected.getTotalAmount().compareTo(actual.getTotalAmount()),
                        "第" + (i + 1) + "条 totalAmount 应一致");
            }

            log.info("beanList 查询通过, 共 {} 条", list.size());
            for (OrderEntity orderEntity : list) {
                log.info("beanList 查询结果: {}", orderEntity);
            }
        }
    }

    @Test
    @Order(4)
    void testUpdateBean() {
        try (var qr = new QueryRunner(template)) {
            // ===== 先插入 =====
            OrderEntity order = buildSampleOrder("更新前", 1, 200, false, new BigDecimal("2.00"));
            qr.insertBean(order);
            long insertedId = order.getId();

            // ===== 修改实体部分字段，执行 updateBean =====
            order.setTitle("更新后商品");
            order.setQuantity(5);
            order.setPrice(999);
            order.setPaid(true);
            order.setTotalAmount(new BigDecimal("49.95"));

            int affected = qr.updateBean(order);
            assertEquals(1, affected, "更新应影响 1 行");

            // ===== 回查验证 =====
            OrderEntity after = qr.query(
                    "SELECT * FROM `" + TABLE_NAME + "` WHERE `id` = ?",
                    new Object[]{insertedId},
                    Handlers.bean(template, OrderEntity.class));
            assertNotNull(after);
            assertEquals("更新后商品", after.getTitle());
            assertEquals(5, after.getQuantity());
            assertEquals(999, after.getPrice());
            assertTrue(after.isPaid());
            assertEquals(0, new BigDecimal("49.95").compareTo(after.getTotalAmount()));

            log.info("updateBean 测试通过: {}", after);
        }
    }

    @Test
    @Order(5)
    void testBeanLifecycle() {
        // 完整的 bean 生命周期：插入 → 查询 → 更新 → 查询 → 删除
        try (var qr = new QueryRunner(template)) {
            // ===== 1. 插入 =====
            OrderEntity order = buildSampleOrder("生命周期测试", 10, 888, false, new BigDecimal("88.80"));
            qr.insertBean(order);
            long id = order.getId();
            assertTrue(id > 0, "自增主键应 > 0");

            // ===== 2. 查询 =====
            OrderEntity queried = qr.query(
                    "SELECT * FROM `" + TABLE_NAME + "` WHERE `id` = ?",
                    new Object[]{id},
                    Handlers.bean(template, OrderEntity.class));
            assertNotNull(queried);
            assertEquals("生命周期测试", queried.getTitle());
            assertEquals(10, queried.getQuantity());

            // ===== 3. 更新 =====
            queried.setTitle("已更新-生命周期");
            queried.setPaid(true);
            int updated = qr.updateBean(queried);
            assertEquals(1, updated);

            // ===== 4. 回查 =====
            OrderEntity afterUpdate = qr.query(
                    "SELECT * FROM `" + TABLE_NAME + "` WHERE `id` = ?",
                    new Object[]{id},
                    Handlers.bean(template, OrderEntity.class));
            assertNotNull(afterUpdate);
            assertEquals("已更新-生命周期", afterUpdate.getTitle());
            assertTrue(afterUpdate.isPaid());

            // ===== 5. 删除 =====
            int deleted = qr.update(
                    "DELETE FROM `" + TABLE_NAME + "` WHERE `id` = ?",
                    new Object[]{id});
            assertEquals(1, deleted);

            Number count = (Number) qr.query(
                    "SELECT COUNT(*) FROM `" + TABLE_NAME + "`",
                    Handlers.firstCell());
            assertEquals(0, count.intValue(), "删除后 COUNT 应为 0");

            log.info("Bean 完整生命周期测试通过");
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
}
