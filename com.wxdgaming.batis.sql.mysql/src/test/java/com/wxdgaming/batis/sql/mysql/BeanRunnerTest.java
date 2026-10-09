package com.wxdgaming.batis.sql.mysql;

import com.wxdgaming.batis.sql.BeanRunner;
import com.wxdgaming.batis.sql.ResultSetHandlers;
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
 * <p>测试 {@link BeanRunner#insertBean(Object)}、
 * {@link BeanRunner#updateBean(Object)} 以及
 * {@link ResultSetHandlers#bean(JdbcTemplate, Class)} /
 * {@link ResultSetHandlers#beanList(JdbcTemplate, Class)} 查询。</p>
 * <p>关注的不是"SQL 能不能跑"，而是实体这一层的三件事：
 * 自增主键是否回写到 bean（不回写则后续 update/delete 拿不到 ID）、
 * 类型化映射是否无损（重点看 BigDecimal 精度与 boolean，两者最容易在读回时变形）、
 * 以及批量插入后 queryAll 的顺序与条数是否与写入一致。
 * 每个用例前幂等确保表存在，每个用例后清空数据，用例之间互不依赖。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BeanRunnerTest {

    static final Logger log = LoggerFactory.getLogger(BeanRunnerTest.class);
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

    /**
     * 用例①：插入返回自增主键，且主键同步回写到实体的 id 字段。
     * <p>断言两点缺一不可：返回值 &gt; 0 只证明数据库生成了键；
     * {@code order.getId()} 等于该值才证明框架把它写回了对象——
     * 回写缺失时调用方手里是一条"没有 ID 的实体"，紧接着的 update/delete 会全部失效。</p>
     */
    @Test
    @Order(1)
    void testInsertBean() {
        try (var br = new BeanRunner(template)) {
            // ===== 构造订单 =====
            OrderEntity order = new OrderEntity();
            order.setTitle("测试商品A");
            order.setQuantity(2);
            order.setPrice(500);     // 单价 5 元（500 分）
            order.setPaid(false);
            order.setCreateTime(new Date());
            order.setTotalAmount(new BigDecimal("10.00"));

            // ===== 执行插入 =====
            Number genId = br.insertBean(order);
            assertNotNull(genId, "自增主键不应为 null");
            assertTrue(genId.longValue() > 0, "自增主键应 > 0");

            // ===== 验证自增主键已回写到实体 =====
            assertEquals(genId.longValue(), order.getId(),
                    "insertBean 应将自增主键回写到 bean.id");

            log.info("插入订单成功, {}", order);
        }
    }

    /**
     * 用例②：按主键回读实体，逐字段校验类型化映射无损。
     * <p>断言覆盖字符串、int、boolean 与 BigDecimal。其中 BigDecimal 刻意用 compareTo 而非 equals
     * ——JDBC 回读的 scale 可能与构造时不同（10.00 与 10.0 值相等但 equals 为假），
     * 用 equals 会得到一个"看着像 bug"的假失败。</p>
     */
    @Test
    @Order(2)
    void testQueryByBean() {
        try (var br = new BeanRunner(template)) {
            // ===== 先插入一条 =====
            OrderEntity order = buildSampleOrder("查询Bean测试", 3, 1000, false, new BigDecimal("30.00"));
            br.insertBean(order);
            long insertedId = order.getId();

            // ===== 使用 queryById 查询 =====
            OrderEntity result = br.queryById(OrderEntity.class, insertedId);

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

    /**
     * 用例③：批量插入后全表读回，条数与每条字段都一致。
     * <p>动作：insertBeanList 一次写 3 条（三条数据故意做出差异：quantity/price/paid/金额都不同）。
     * 断言：queryAll 返回 3 条且逐条与插入顺序对应。
     * 意义：批量路径走的是 JDBC batch（与单条完全不同的代码分支），
     * 这里同时守住两件事——批次没有漏写/重写，以及批量后自增 ID 仍被正确回写
     * （断言里比对了 id，若批量分支忘记回填，id 会全是 0 而被抓住）。
     * 依赖单表无并发写入，故顺序稳定。</p>
     */
    @Test
    @Order(3)
    void testQueryBeanList() {
        try (var br = new BeanRunner(template)) {
            // ===== 批量插入 3 条 =====
            List<OrderEntity> inserted = new ArrayList<>();
            for (int i = 1; i <= 3; i++) {
                OrderEntity order = buildSampleOrder(
                        "批量商品" + i, i, i * 100, i % 2 == 0,
                        new BigDecimal(String.format("%.2f", i * 1.5)));
                inserted.add(order);
            }
            br.insertBeanList(inserted);

            // ===== 使用 queryAll 查询全部 =====
            List<OrderEntity> list = br.queryAll(OrderEntity.class);

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

    /**
     * 用例④：按主键整行更新，回查确认新值生效。
     * <p>动作：插入后改动 title/quantity/price/paid/totalAmount 再 updateBean。
     * 断言影响行数 = 1（更新没有错命中 0 行或多行），并重新从库读回逐字段核对——
     * 只看返回值不够，因为"更新到别的行"同样返回 1。
     * 这里也隐含验证了 update 用的是主键作为条件、且不会去动 id 列本身。</p>
     */
    @Test
    @Order(4)
    void testUpdateBean() {
        try (var br = template.beanRunner()) {
            // ===== 先插入 =====
            OrderEntity order = buildSampleOrder("更新前", 1, 200, false, new BigDecimal("2.00"));
            br.insertBean(order);
            long insertedId = order.getId();

            // ===== 修改实体部分字段，执行 updateBean =====
            order.setTitle("更新后商品");
            order.setQuantity(5);
            order.setPrice(999);
            order.setPaid(true);
            order.setTotalAmount(new BigDecimal("49.95"));

            int affected = br.updateBean(order);
            assertEquals(1, affected, "更新应影响 1 行");

            // ===== 回查验证 =====
            OrderEntity after = br.queryById(OrderEntity.class, insertedId);
            assertNotNull(after);
            assertEquals("更新后商品", after.getTitle());
            assertEquals(5, after.getQuantity());
            assertEquals(999, after.getPrice());
            assertTrue(after.isPaid());
            assertEquals(0, new BigDecimal("49.95").compareTo(after.getTotalAmount()));

            log.info("updateBean 测试通过: {}", after);
        }
    }

    /**
     * 用例⑤：一条实体的完整生命周期 插入 → 查 → 改 → 查 → 删。
     * <p>与前面四个用例的分工：①②③④各测一个动作，本用例把五个动作串在同一条数据上，
     * 验证它们能连续复用同一个对象（尤其删除必须以更新后的实体为准）。
     * 删除的断言刻意不靠"返回 1 行"了事，而是再用 COUNT 直接查库确认表为空
     * ——COUNT 来自数据库，不受框架内存状态影响。</p>
     */
    @Test
    @Order(5)
    void testBeanLifecycle() {
        // 完整的 bean 生命周期：插入 → 查询 → 更新 → 查询 → 删除
        try (var br = template.beanRunner()) {
            // ===== 1. 插入 =====
            OrderEntity order = buildSampleOrder("生命周期测试", 10, 888, false, new BigDecimal("88.80"));
            br.insertBean(order);
            long id = order.getId();
            assertTrue(id > 0, "自增主键应 > 0");

            // ===== 2. 查询 =====
            OrderEntity queried = br.queryById(OrderEntity.class, id);
            assertNotNull(queried);
            assertEquals("生命周期测试", queried.getTitle());
            assertEquals(10, queried.getQuantity());

            // ===== 3. 更新 =====
            queried.setTitle("已更新-生命周期");
            queried.setPaid(true);
            int updated = br.updateBean(queried);
            assertEquals(1, updated);

            // ===== 4. 回查 =====
            OrderEntity afterUpdate = br.queryById(OrderEntity.class, id);
            assertNotNull(afterUpdate);
            assertEquals("已更新-生命周期", afterUpdate.getTitle());
            assertTrue(afterUpdate.isPaid());

            List<OrderEntity> orderEntities = br.queryAll(OrderEntity.class);
            for (OrderEntity orderEntity : orderEntities) {
                log.info("Bean 生命周期测试结果: {}", orderEntity);
            }

            // ===== 5. 删除 =====
            int deleted = br.deleteBean(queried);
            assertEquals(1, deleted);

            Number count = (Number) br.getQueryRunner().query(
                    "SELECT COUNT(*) FROM `" + TABLE_NAME + "`",
                    ResultSetHandlers.firstCell());
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
