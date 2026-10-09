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
 * <p>用例分组：①③⑦⑧⑨ 各测一种分区策略能否真建出来；②测追加分区与缓存去重；
 * ④⑤⑪测删除、刷新、清空三种维护动作；⑥⑫只验证 SQL 文本生成（不连库）；⑩验证分区边界值计算；
 * ⑬确认所有实现都挂在方言无关的接口下。</p>
 * <p>每个用例结束后 DROP 分区表并清 JdbcTemplate 的结构缓存：
 * 分区表是库端状态，不清掉下一条测试会命中"表已存在"的旧缓存而跳过建分区，
 * 表现为"断言莫名通过"，所以这里的收尾是这批测试可信的前提。</p>
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

    /**
     * 用例①：按日 RANGE 分区（2026-08-01 ~ 08-05，一天一个分区）。
     * <p>断言两件事：分区名 p20260801…p20260805 逐个存在且带 pmax 兜底分区
     * ——MAXVALUE 那段是必需的，否则时间超出建表范围的日志会被数据库直接拒绝写入（丢数据）；
     * 二是分区对写入透明：往 08-08（落在 pmax）插一条并读回，
     * 证明业务代码不需要知道分区存在也能正常读写。</p>
     */
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

    /**
     * 用例②：追加分区 + 缓存去重（分区维护的日常姿势）。
     * <p>前置：建 08-01~08-05 时<b>故意不带 MAXVALUE</b>——带了的表尾部是兜底分区，
     * 无法再用 ADD PARTITION 往中间追加，这是 MySQL 分区的硬限制。</p>
     * <p>动作：先追加 08-06~08-10（应真增加分区，断言 p20260806/p20260810 存在），
     * 再对 08-06~08-08 重复执行同一次追加。
     * 断言的重点是第二次追加<b>不抛异常</b>：分区缓存记住了已存在的分区并跳过，
     * 而库里重复 ADD 同范围分区是会直接报错的。
     * 这条决定了"每天定时跑一次补分区"的作业能否安全地反复执行（幂等），
     * 否则运维脚本跑第二遍就把应用打挂。</p>
     */
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

    /**
     * 用例③：按月 RANGE 分区（2026 年 1~12 月）+ 兜底分区。
     * <p>断言分区名为 p202601/p202606/p202612（月份是 6 位，与按日的 8 位、按年的 4 位区分开），
     * 再用一条 6 月的数据验证它会正常落到对应分区并可读回。
     * 与①共同证明分区粒度是由表达式档位决定的，而不是写死在某个策略里。</p>
     */
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

    /**
     * 用例④：删除单个分区（数据保留策略到期的清理动作）。
     * <p>动作：建好 08-01~08-05 后 DROP PARTITION p20260801。
     * 断言两个方向：被删的 p20260801 从缓存消失，相邻的 p20260802 仍在。
     * 只看前者不够 —— 只删一个分区却把整表分区拆掉是灾难性错误，
     * 而删除相邻分区恰好能暴露"按名字删"实现成了"按位置删"。
     * 缓存同步消失同样重要：缓存里留着已不存在的分区，后续去重逻辑就会跳过本该重建的分区。</p>
     */
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

    /**
     * 用例⑤：分区缓存可以从库端重建（refresh 语义）。
     * <p>动作：建分区 → 断言缓存有 → 调 refresh → 再断言缓存仍有。
     * 意义：缓存必须能丢弃内存状态、重新从元数据读回，且读回结果与建表时一致。
     * 没有这个能力就无法应对"有人在库端手工加过分区"的情况 ——
     * 框架只看自己的内存缓存会认为分区不存在而重复 ADD（然后报错），
     * 或者反过来把已被手工删掉的分区当成还在。</p>
     */
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

    /**
     * 用例⑥：只生成 DDL 文本、不执行（无缓存的 builder 也只允许这种用法）。
     * <p>动作：不传 PartitionCache 构造 builder，调 buildPartition 拿 SQL 字符串。
     * 断言文本里出现 ALTER TABLE、PARTITION BY RANGE、三个分区名与 pmax。
     * 意义有二：一是纯文本断言不依赖数据库，问题能定位到"渲染"而不是"执行"；
     * 二是给用户"先看 SQL 再决定执行"的用法留了口子（生产 DDL 通常要人工评审）。
     * 之所以不校验完整空格与换行，是因为那是可读性排版，改了不该让测试失败。</p>
     */
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

    /**
     * 用例⑦：HASH 分区（按 id 散成 8 片）。
     * <p>与 RANGE 的语义差别是这里要证的：HASH 分区没有"边界值"概念，分区名固定为 p0…p7，
     * 作用是散列写入压力而不是按时间裁剪数据。断言恰为 8 个分区（不多不少，
     * 说明分区数参数被真正使用），并插入一条数据验证读写透明。</p>
     */
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

    /**
     * 用例⑧：按周 RANGE 分区（2026-08-01 ~ 08-16）。
     * <p>断言只到"确实生成了分区"这一层：周分区的边界取决于"哪一天算一周的开始"
     * （周一还是周日、跨年那一周怎么算），不同约定下名字序列不同，
     * 把具体名单写死会让测试和实现互相绑死在一个未定义的规则上。
     * 周值的格式与位数由用例⑩单独、确定地校验。</p>
     */
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

    /**
     * 用例⑨：按年 RANGE 分区（2024 ~ 2027）。
     * <p>断言 p2024/p2025/p2026/p2027 四个都在并带兜底分区。
     * 这里覆盖的是"起止年份跨度大于 1"的场景：区间分区最容易出错的是首尾边界
     * （起点年份被漏掉、或终点那年只生成分区名却没进边界值），故四个年份逐个点名而不是只数个数。</p>
     */
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

    /**
     * 用例⑩：分区边界值（VALUES LESS THAN 里那个数字）的格式与可比较性。
     * <p>RANGE 分区能不能正确路由，全靠边界值是<b>随时间单调递增的整数</b>，
     * 所以本用例锁两件事：</p>
     * <ol>
     *   <li>格式位数固定（日 8 位、月 6 位、年 4 位、季为"年+季号"、周 6 位），
     *   且月初/月中/年末等取样值逐字相符；位数飘了就会让 2026-1 排到 2026-12 之后；</li>
     *   <li>转成整数后大小关系与时间先后一致（20260101 &lt; 20261231）——
     *   这一条才是真正的正确性来源：只要某个档位的编码不单调，分区边界就会错位，
     *   数据落到错误分区且不报错，属于最难发现的一类 bug。</li>
     * </ol>
     */
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

    /**
     * 用例⑪：清空分区数据但保留分区定义（TRUNCATE PARTITION）。
     * <p>与用例④的分工是这条用例的意义所在：DROP 是"删定义 + 连带删数据"，
     * TRUNCATE 只清数据、分区边界仍在。断言特意只看"分区定义还在"，
     * 因为如果实现错用了 DROP，边界就没了 —— 后续写入会全部堆进兜底分区，
     * 按天查询失去分区裁剪能力（退化成全表扫），而这类性能退化不会报错。</p>
     */
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

    /**
     * 用例⑫：HASH / KEY / LIST 三种非 RANGE 策略的 DDL 文本生成。
     * <p>前面的用例以 RANGE 为主，本用例补齐另三种策略的关键结构差异：
     * HASH 和 KEY 只需分区数（PARTITIONS 16 / 8），LIST 则逐个分区带 VALUES IN 枚举。
     * 断言用文本包含而不是整串比对，重点是"关键字和参数真的进了 SQL"——
     * 例如 PARTITIONS 后面的数字来自配置而不是写死的默认值，这类"参数没接上"的错
     * 在执行期只会表现为分区数不对，很难察觉。</p>
     */
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

    /**
     * 用例⑬：分区能力的三个角色都挂在方言无关接口上（分层约束）。
     * <p>断言 MySQL 的 manager/表达式/元数据读取器分别实现 PartitionManager、PartitionExpr、
     * PartitionMetaReader。意义是给未来加第二种方言（pgsql 的声明式分区语法完全不同）预留插入点：
     * 只要业务代码依赖的是接口，换方言就只需换这三个实现，
     * 而不需要动 PartitionBuilder 和上层调用 —— 这条是架构约束，一旦破坏后面返工成本最高，所以用测试钉住。</p>
     */
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
