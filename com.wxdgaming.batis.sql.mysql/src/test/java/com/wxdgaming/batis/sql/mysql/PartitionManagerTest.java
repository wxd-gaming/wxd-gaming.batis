package com.wxdgaming.batis.sql.mysql;

import com.wxdgaming.batis.sql.QueryRunner;
import com.wxdgaming.batis.sql.partition.*;
import com.wxdgaming.batis.sql.mysql.partition.*;
import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MySQL 分区管理集成测试
 *
 * <p>测试覆盖：按日/按月/按周/按年分区、HASH分区、缓存去重、删除分区、清空分区。</p>
 *
 * <p>使用嵌入式 MariaDB4j，无需外部数据库。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-08-08
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PartitionManagerTest {

    static final Logger log = LoggerFactory.getLogger(PartitionManagerTest.class);

    static MysqlTemplate template;
    static PartitionCache cache;

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

        // 创建分区缓存
        cache = new PartitionCache(template, MysqlPartitionMetaReader.INSTANCE);
    }

    @BeforeEach
    void ensureTable() {
        // 确保基础表存在
        template.checkTable(DailyLogEntity.class);
        template.checkTable(MonthlyOrderEntity.class);
    }

    @AfterEach
    void cleanTables() {
        // 测试后删除分区表，避免缓存干扰下一条测试
        try (QueryRunner qr = new QueryRunner(template)) {
            qr.update("DROP TABLE IF EXISTS `t_daily_log`");
            qr.update("DROP TABLE IF EXISTS `t_monthly_order`");
        }
        // ★ 清空 JdbcTemplate 的表结构缓存，否则 checkTable 误判表已存在
        template.clearTableCache();
        cache.refreshAll();
    }

    // ====================================================================
    // 1. 按日分区测试
    // ====================================================================

    @Test
    @Order(1)
    void testDailyPartition() {
        log.info("=== 测试：按日分区 ===");

        // 建分区：2026-08-01 ~ 2026-08-05，每天一个分区
        PartitionBuilder.of(MysqlPartitionManager.INSTANCE, cache)
                .table(DailyLogEntity.class)
                .withExpr(MysqlPartitionExpr.INSTANCE)
                .rangeByDaily("log_time",
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 8, 5))
                .withMaxValue()
                .executePartition();

        // 验证分区已创建
        Set<String> partitions = cache.getPartitions("t_daily_log");
        assertTrue(partitions.contains("p20260801"), "应包含分区 p20260801");
        assertTrue(partitions.contains("p20260802"), "应包含分区 p20260802");
        assertTrue(partitions.contains("p20260805"), "应包含分区 p20260805");
        assertTrue(partitions.contains("pmax"), "应包含兜底分区 pmax");

        // 验证数据可以正常写入（分区透明）
        verifyInsertWorks("t_daily_log");

        log.info("按日分区测试通过");
    }

    // ====================================================================
    // 2. 追加分区 + 缓存去重
    // ====================================================================

    @Test
    @Order(2)
    void testAddPartitionAndCacheDeDuplication() {
        log.info("=== 测试：追加分区 + 缓存去重 ===");

        PartitionBuilder builder = PartitionBuilder.of(MysqlPartitionManager.INSTANCE, cache);

        // 先建基础分区（不用 MAXVALUE，因为后续要追加）
        builder.table(DailyLogEntity.class)
                .withExpr(MysqlPartitionExpr.INSTANCE)
                .rangeByDaily("log_time",
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 8, 5))
                .executePartition();

        // 追加 2026-08-06 ~ 2026-08-10
        builder.table(DailyLogEntity.class)
                .withExpr(MysqlPartitionExpr.INSTANCE)
                .rangeByDaily("log_time",
                        LocalDate.of(2026, 8, 6),
                        LocalDate.of(2026, 8, 10))
                .executeAddPartition();

        Set<String> partitions = cache.getPartitions("t_daily_log");
        assertTrue(partitions.contains("p20260806"), "应包含新增分区 p20260806");
        assertTrue(partitions.contains("p20260810"), "应包含新增分区 p20260810");

        // ★ 核心：重复添加相同分区，应该被缓存跳过
        builder.table(DailyLogEntity.class)
                .withExpr(MysqlPartitionExpr.INSTANCE)
                .rangeByDaily("log_time",
                        LocalDate.of(2026, 8, 6),
                        LocalDate.of(2026, 8, 8))
                .executeAddPartition(); // 应该全部跳过，不抛异常

        verifyInsertWorks("t_daily_log");

        log.info("追加分区 + 缓存去重测试通过");
    }

    // ====================================================================
    // 3. 按月分区测试
    // ====================================================================

    @Test
    @Order(3)
    void testMonthlyPartition() {
        log.info("=== 测试：按月分区 ===");

        PartitionBuilder.of(MysqlPartitionManager.INSTANCE, cache)
                .table(MonthlyOrderEntity.class)
                .withExpr(MysqlPartitionExpr.INSTANCE)
                .rangeByMonthly("create_time",
                        YearMonth.of(2026, 1),
                        YearMonth.of(2026, 12))
                .withMaxValue()
                .executePartition();

        Set<String> partitions = cache.getPartitions("t_monthly_order");
        assertTrue(partitions.contains("p202601"), "应包含分区 p202601");
        assertTrue(partitions.contains("p202606"), "应包含分区 p202606");
        assertTrue(partitions.contains("p202612"), "应包含分区 p202612");
        assertTrue(partitions.contains("pmax"), "应包含兜底分区 pmax");

        verifyMonthlyInsertWorks();

        log.info("按月分区测试通过");
    }

    // ====================================================================
    // 4. 删除分区测试
    // ====================================================================

    @Test
    @Order(4)
    void testDropPartition() {
        log.info("=== 测试：删除分区 ===");

        PartitionBuilder builder = PartitionBuilder.of(MysqlPartitionManager.INSTANCE, cache);

        // 先建分区
        builder.table(DailyLogEntity.class)
                .withExpr(MysqlPartitionExpr.INSTANCE)
                .rangeByDaily("log_time",
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 8, 5))
                .withMaxValue()
                .executePartition();

        // 删除 p20260801
        builder.table(DailyLogEntity.class)
                .dropPartition("p20260801")
                .executeDropPartition();

        // 缓存应移除
        Set<String> partitions = cache.getPartitions("t_daily_log");
        assertFalse(partitions.contains("p20260801"), "p20260801 应已被删除");
        assertTrue(partitions.contains("p20260802"), "p20260802 应仍存在");

        log.info("删除分区测试通过");
    }

    // ====================================================================
    // 5. 缓存刷新测试
    // ====================================================================

    @Test
    @Order(5)
    void testCacheRefresh() {
        log.info("=== 测试：缓存刷新 ===");

        PartitionBuilder builder = PartitionBuilder.of(MysqlPartitionManager.INSTANCE, cache);

        // 先建分区
        builder.table(DailyLogEntity.class)
                .withExpr(MysqlPartitionExpr.INSTANCE)
                .rangeByDaily("log_time",
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 8, 2))
                .executePartition();

        // 缓存中有
        assertTrue(cache.contains("t_daily_log", "p20260801"));

        // 刷新缓存
        cache.refresh("t_daily_log");

        // 刷新后重新从 DB 加载，仍应有
        assertTrue(cache.contains("t_daily_log", "p20260801"));

        log.info("缓存刷新测试通过");
    }

    // ====================================================================
    // 6. SQL 构建（不执行）测试
    // ====================================================================

    @Test
    @Order(6)
    void testBuildSql() {
        log.info("=== 测试：构建 SQL（不执行） ===");

        // 不带缓存的 builder，只能 build 不能 execute
        PartitionBuilder builder = PartitionBuilder.of(MysqlPartitionManager.INSTANCE);
        builder.table(DailyLogEntity.class)
                .withExpr(MysqlPartitionExpr.INSTANCE)
                .rangeByDaily("log_time",
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 8, 3))
                .withMaxValue();

        String sql = builder.buildPartition();
        assertNotNull(sql, "构建的 SQL 不应为 null");
        assertTrue(sql.contains("ALTER TABLE"), "SQL 应包含 ALTER TABLE");
        assertTrue(sql.contains("PARTITION BY RANGE"), "SQL 应包含 PARTITION BY RANGE");
        assertTrue(sql.contains("p20260801"), "SQL 应包含分区名");
        assertTrue(sql.contains("p20260802"), "SQL 应包含分区名");
        assertTrue(sql.contains("p20260803"), "SQL 应包含分区名");
        assertTrue(sql.contains("pmax"), "SQL 应包含 MAXVALUE");

        log.info("构建的 SQL:\n{}", sql);
        log.info("SQL 构建测试通过");
    }

    // ====================================================================
    // 7. HASH 分区测试
    // ====================================================================

    @Test
    @Order(7)
    void testHashPartition() {
        log.info("=== 测试：HASH 分区 ===");

        PartitionBuilder.of(MysqlPartitionManager.INSTANCE, cache)
                .table(DailyLogEntity.class)
                .withExpr(MysqlPartitionExpr.INSTANCE)
                .hashBy("id", 8)
                .executePartition();

        // HASH 分区名是 p0, p1, ..., p7
        Set<String> partitions = cache.getPartitions("t_daily_log");
        assertFalse(partitions.isEmpty(), "HASH分区应有分区");
        assertTrue(partitions.size() == 8, "应有 8 个分区");

        verifyInsertWorks("t_daily_log");

        log.info("HASH 分区测试通过");
    }

    // ====================================================================
    // 8. 按周分区测试
    // ====================================================================

    @Test
    @Order(8)
    void testWeeklyPartition() {
        log.info("=== 测试：按周分区 ===");

        PartitionBuilder.of(MysqlPartitionManager.INSTANCE, cache)
                .table(DailyLogEntity.class)
                .withExpr(MysqlPartitionExpr.INSTANCE)
                .rangeByWeekly("log_time",
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 8, 16))
                .withMaxValue()
                .executePartition();

        Set<String> partitions = cache.getPartitions("t_daily_log");
        assertFalse(partitions.isEmpty(), "按周分区应有分区");

        log.info("按周分区列表: {}", partitions);
        log.info("按周分区测试通过");
    }

    // ====================================================================
    // 9. 按年分区测试
    // ====================================================================

    @Test
    @Order(9)
    void testYearlyPartition() {
        log.info("=== 测试：按年分区 ===");

        PartitionBuilder.of(MysqlPartitionManager.INSTANCE, cache)
                .table(DailyLogEntity.class)
                .withExpr(MysqlPartitionExpr.INSTANCE)
                .rangeByYearly("log_time", 2024, 2027)
                .withMaxValue()
                .executePartition();

        Set<String> partitions = cache.getPartitions("t_daily_log");
        assertTrue(partitions.contains("p2024"), "应包含分区 p2024");
        assertTrue(partitions.contains("p2025"), "应包含分区 p2025");
        assertTrue(partitions.contains("p2026"), "应包含分区 p2026");
        assertTrue(partitions.contains("p2027"), "应包含分区 p2027");
        assertTrue(partitions.contains("pmax"), "应含兜底分区 pmax");

        log.info("按年分区测试通过");
    }

    // ====================================================================
    // 10. 分区表达式数值验证
    // ====================================================================

    @Test
    @Order(10)
    void testExprValues() {
        log.info("=== 测试：分区表达式数值计算 ===");

        MysqlPartitionExpr expr = MysqlPartitionExpr.INSTANCE;

        // 按日
        assertEquals("20260808", expr.dayValue(LocalDate.of(2026, 8, 8)));
        assertEquals("20260101", expr.dayValue(LocalDate.of(2026, 1, 1)));
        assertEquals("20261231", expr.dayValue(LocalDate.of(2026, 12, 31)));

        // 按月
        assertEquals("202608", expr.monthValue(YearMonth.of(2026, 8)));
        assertEquals("202601", expr.monthValue(YearMonth.of(2026, 1)));

        // 按年
        assertEquals("2026", expr.yearValue(2026));
        assertEquals("2027", expr.yearValue(2027));

        // 按季
        assertEquals("20263", expr.quarterValue(2026, 3));
        assertEquals("20261", expr.quarterValue(2026, 1));

        // 按周（2026-08-03 是周一）
        String weekVal = expr.weekValue(LocalDate.of(2026, 8, 3));
        assertNotNull(weekVal);
        assertTrue(weekVal.length() == 6, "周值应为 6 位数字");

        // 确保按日值可做大小比较
        int val01 = Integer.parseInt(expr.dayValue(LocalDate.of(2026, 1, 1)));
        int val02 = Integer.parseInt(expr.dayValue(LocalDate.of(2026, 12, 31)));
        assertTrue(val01 < val02, "20260101 应小于 20261231");

        log.info("分区表达式数值验证通过");
    }

    // ====================================================================
    // 11. 清空分区测试
    // ====================================================================

    @Test
    @Order(11)
    void testTruncatePartition() {
        log.info("=== 测试：清空分区 ===");

        PartitionBuilder builder = PartitionBuilder.of(MysqlPartitionManager.INSTANCE, cache);

        // 先建分区
        builder.table(DailyLogEntity.class)
                .withExpr(MysqlPartitionExpr.INSTANCE)
                .rangeByDaily("log_time",
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 8, 2))
                .executePartition();

        // 清空分区（不清除分区定义，只清数据）
        builder.table(DailyLogEntity.class)
                .truncatePartition("p20260801")
                .executeTruncatePartition();

        // 分区定义仍然存在
        assertTrue(cache.contains("t_daily_log", "p20260801"),
                "清空后分区定义应仍存在");

        log.info("清空分区测试通过");
    }

    // ====================================================================
    // 12. 多类型表达式生成 SQL 验证
    // ====================================================================

    @Test
    @Order(12)
    void testVariousClauseBuild() {
        log.info("=== 测试：多类型分区 SQL 生成 ===");

        MysqlPartitionManager mgr = MysqlPartitionManager.INSTANCE;

        // HASH
        PartitionConfig hashConfig = new PartitionConfig();
        hashConfig.setTableName("t_test");
        hashConfig.setType(PartitionType.HASH);
        hashConfig.setExpression("user_id");
        hashConfig.setPartitionCount(16);
        String hashSql = mgr.buildCreatePartition(hashConfig);
        assertTrue(hashSql.contains("PARTITION BY HASH(user_id)"));
        assertTrue(hashSql.contains("PARTITIONS 16"));

        // KEY
        PartitionConfig keyConfig = new PartitionConfig();
        keyConfig.setTableName("t_test");
        keyConfig.setType(PartitionType.KEY);
        keyConfig.setExpression("id");
        keyConfig.setPartitionCount(8);
        String keySql = mgr.buildCreatePartition(keyConfig);
        assertTrue(keySql.contains("PARTITION BY KEY(id)"));
        assertTrue(keySql.contains("PARTITIONS 8"));

        // LIST
        PartitionConfig listConfig = new PartitionConfig();
        listConfig.setTableName("t_test");
        listConfig.setType(PartitionType.LIST);
        listConfig.setExpression("region");
        listConfig.addPartition(new PartitionDef("p_east", "VALUES IN (1, 2, 3)"));
        listConfig.addPartition(new PartitionDef("p_west", "VALUES IN (4, 5, 6)"));
        String listSql = mgr.buildCreatePartition(listConfig);
        assertTrue(listSql.contains("PARTITION BY LIST(region)"));
        assertTrue(listSql.contains("p_east"));
        assertTrue(listSql.contains("p_west"));

        log.info("多类型分区 SQL 生成验证通过");
    }

    // ====================================================================
    // 13. 分区接口抽象验证
    // ====================================================================

    @Test
    @Order(13)
    void testInterfaceAbstraction() {
        log.info("=== 测试：接口抽象 ===");

        // 验证所有 MySQL 实现都是接口的子类型
        assertInstanceOf(PartitionManager.class, MysqlPartitionManager.INSTANCE);
        assertInstanceOf(PartitionExpr.class, MysqlPartitionExpr.INSTANCE);
        assertInstanceOf(PartitionMetaReader.class, MysqlPartitionMetaReader.INSTANCE);

        log.info("接口抽象验证通过");
    }

    // ====================================================================
    // 辅助方法
    // ====================================================================

    /** 验证 DailyLog 表可以正常写入和读取 */
    private void verifyInsertWorks(String tableName) {
        try (QueryRunner qr = new QueryRunner(template)) {
            // 插入一条测试数据
            int rows = qr.update(
                    "INSERT INTO `" + tableName + "` (`log_time`, `content`, `level`) " +
                    "VALUES ('2026-08-08 12:00:00', 'test partition', 1)"
            );
            assertEquals(1, rows, "应成功插入 1 行");

            // 查询验证
            List<?> list = qr.query(
                    "SELECT * FROM `" + tableName + "` WHERE `log_time` = '2026-08-08 12:00:00'",
                    (ResultSet rs) -> {
                        if (rs.next()) return java.util.List.of("ok");
                        return java.util.List.of();
                    }
            );
            assertFalse(list.isEmpty(), "应能查到刚插入的数据");

            // 清理
            qr.update("DELETE FROM `" + tableName + "`");
        }
    }

    /** 验证 MonthlyOrder 表可以正常写入和读取 */
    private void verifyMonthlyInsertWorks() {
        try (QueryRunner qr = new QueryRunner(template)) {
            int rows = qr.update(
                    "INSERT INTO `t_monthly_order` (`create_time`, `title`, `quantity`) " +
                    "VALUES ('2026-06-15 12:00:00', 'test', 1)"
            );
            assertEquals(1, rows, "应成功插入 1 行");

            List<?> list = qr.query(
                    "SELECT * FROM `t_monthly_order` WHERE `create_time` = '2026-06-15 12:00:00'",
                    (ResultSet rs) -> {
                        if (rs.next()) return java.util.List.of("ok");
                        return java.util.List.of();
                    }
            );
            assertFalse(list.isEmpty(), "应能查到刚插入的数据");
            qr.update("DELETE FROM `t_monthly_order`");
        }
    }
}
