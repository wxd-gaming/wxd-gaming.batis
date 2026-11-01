package com.wxdgaming.batis.sql.mysql.partition;

import com.wxdgaming.batis.sql.partition.PartitionBuilder;
import com.wxdgaming.batis.sql.partition.PartitionDef;
import com.wxdgaming.batis.sql.partition.PartitionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分区 Builder 的纯渲染验收测试（不连库）。
 *
 * <p>Builder 与方言 Manager 的分工在这里被钉住：Builder 负责收集意图、去重、生成合法参数，
 * 遇到必然失败的输入要在链式调用点就抛错；真正拼 SQL 的是 Manager。因此本类只断言
 * "参数校验 + 传入 Manager 的数据形状"，SQL 文本的方言细节由
 * {@link MysqlPartitionManagerTest} 单独覆盖。</p>
 *
 * <p>重点用例：<b>同一批内重复/边界值重复</b>（缓存只能挡库端已存在，挡不住这一批自身写错）、
 * <b>table() 重置</b>（复用 Builder 时残留的 truncateName 会去清另一张表）、
 * <b>无缓存构造只能 build 不能 execute</b>（execute 的取连接途径只有 PartitionCache）。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-11-01
 */
class PartitionBuilderTest {

    private static PartitionBuilder builder() {
        return PartitionBuilder.of(MysqlPartitionManager.INSTANCE).withExpr(MysqlPartitionExpr.INSTANCE);
    }

    // ==================== 参数校验 ====================

    @Test
    @DisplayName("HASH/KEY 分区数必须为正：0 或负数在链式调用点立即失败")
    void invalidPartitionCountIsRejected() {
        IllegalArgumentException hash = assertThrows(IllegalArgumentException.class,
                () -> builder().table("t").hashBy("id", 0));
        assertTrue(hash.getMessage().contains("HASH"));

        IllegalArgumentException key = assertThrows(IllegalArgumentException.class,
                () -> builder().table("t").keyBy("id", -1));
        assertTrue(key.getMessage().contains("KEY"));
    }

    @Test
    @DisplayName("分区名/边界值不能为空，不能在同一批内重名或共用边界")
    void batchLocalDuplicatesAreRejected() {
        PartitionBuilder b = builder().table("t").rangeBy("id");

        assertThrows(IllegalArgumentException.class, () -> b.addPartition(" ", "VALUES LESS THAN (1)"));
        assertThrows(IllegalArgumentException.class, () -> b.addPartition("p1", " "));

        b.addPartition("p1", "VALUES LESS THAN (10)");
        IllegalArgumentException duplicateName = assertThrows(IllegalArgumentException.class,
                () -> b.addPartition("P1", "VALUES LESS THAN (20)"));
        assertTrue(duplicateName.getMessage().contains("分区名重复"));

        IllegalArgumentException duplicateValue = assertThrows(IllegalArgumentException.class,
                () -> b.addPartition("p2", "values less than (10)"));
        assertTrue(duplicateValue.getMessage().contains("边界值"));
    }

    @Test
    @DisplayName("按日/周/月/季/年快捷方法缺少 PartitionExpr 时明确失败")
    void rangeHelpersRequireExpr() {
        PartitionBuilder noExpr = PartitionBuilder.of(MysqlPartitionManager.INSTANCE).table("t");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> noExpr.rangeByDaily("log_time", LocalDate.now(), LocalDate.now()));
        assertTrue(ex.getMessage().contains("withExpr"), ex.getMessage());

        assertThrows(IllegalStateException.class,
                () -> noExpr.rangeByWeekly("log_time", LocalDate.now(), LocalDate.now()));
        assertThrows(IllegalStateException.class,
                () -> noExpr.rangeByMonthly("log_time", YearMonth.now(), YearMonth.now()));
        assertThrows(IllegalStateException.class,
                () -> noExpr.rangeByYearly("log_time", 2026, 2026));
        assertThrows(IllegalStateException.class,
                () -> noExpr.rangeByQuarterly("log_time", 2026, 1, 2026, 1));
    }

    @Test
    @DisplayName("步长必须为正：0/负数会死循环，入口直接拒绝")
    void nonPositiveDaysPerPartIsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> builder().table("t")
                        .rangeByDaily("log_time", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 2), 0));
        assertTrue(ex.getMessage().contains("daysPerPart"));
    }

    // ==================== 渲染数据形状 ====================

    @Test
    @DisplayName("按日分区：名字取区间起点、边界取下一天，MAXVALUE 收尾，表名统一小写")
    void dailyPartitionsRenderAsExpected() {
        String sql = builder()
                .table("T_Daily_Log")
                .rangeByDaily("log_time", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 2))
                .withMaxValue()
                .buildPartition();

        assertTrue(sql.contains("ALTER TABLE `t_daily_log`"), sql);
        assertTrue(sql.contains("PARTITION `p20260801` VALUES LESS THAN (20260802)"), sql);
        assertTrue(sql.contains("PARTITION `p20260802` VALUES LESS THAN (20260803)"), sql);
        assertTrue(sql.contains("PARTITION `pmax` VALUES LESS THAN MAXVALUE"), sql);
        assertTrue(sql.contains("YEAR(log_time)*10000 + MONTH(log_time)*100 + DAY(log_time)"), sql);
    }

    @Test
    @DisplayName("按季分区在 Q4 后进位到次年 Q1，边界保持单调")
    void quarterlyPartitionsRollOverYear() {
        String sql = builder()
                .table("t_q")
                .rangeByQuarterly("create_time", 2025, 4, 2026, 1)
                .buildPartition();

        assertTrue(sql.contains("PARTITION `p20254` VALUES LESS THAN (20261)"), sql);
        assertTrue(sql.contains("PARTITION `p20261` VALUES LESS THAN (20262)"), sql);
    }

    @Test
    @DisplayName("HASH/KEY 只靠分区数：SQL 里出现配置的数字，而不是默认值")
    void hashAndKeyCountsFlowIntoDdl() {
        String hash = builder().table("t").hashBy("user_id", 16).buildPartition();
        assertTrue(hash.contains("PARTITION BY HASH(user_id)"), hash);
        assertTrue(hash.contains("PARTITIONS 16"), hash);

        String key = builder().table("t").keyBy("id", 8).buildPartition();
        assertTrue(key.contains("PARTITION BY KEY(id)"), key);
        assertTrue(key.contains("PARTITIONS 8"), key);
    }

    @Test
    @DisplayName("逃生口 partitionBy 支持 RANGE COLUMNS / LIST COLUMNS 等快捷方法未覆盖的类型")
    void escapeHatchSupportsColumnVariants() {
        String sql = builder().table("t")
                .partitionBy(PartitionType.RANGE_COLUMNS, "log_time")
                .addPartition("p1", "VALUES LESS THAN ('2026-09-01')")
                .buildPartition();

        assertTrue(sql.contains("PARTITION BY RANGE COLUMNS (log_time)"), sql);
    }

    @Test
    @DisplayName("ADD/DROP/TRUNCATE/REORGANIZE 各自渲染正确的语句头")
    void maintenanceStatementsRender() {
        String add = builder().table("t")
                .rangeBy("id")
                .addPartitions(new PartitionDef("p1", "VALUES LESS THAN (10)"),
                        new PartitionDef("p2", "VALUES LESS THAN (20)"))
                .buildAddPartition();
        assertTrue(add.contains("ALTER TABLE `t` ADD PARTITION ("), add);
        assertTrue(add.contains("PARTITION `p1` VALUES LESS THAN (10)"), add);

        String drop = builder().table("t")
                .dropPartition("p1")
                .dropPartition("p2")
                .buildDropPartition();
        assertEquals("ALTER TABLE `t` DROP PARTITION `p1`, DROP PARTITION `p2`;", drop,
                "多个 DROP 应合并成一条 ALTER");

        String truncate = builder().table("t").truncatePartition("p1").buildTruncatePartition();
        assertEquals("ALTER TABLE `t` TRUNCATE PARTITION `p1`;", truncate);

        String reorganize = builder().table("t")
                .reorganizePartition("pmax", "p202609", "VALUES LESS THAN (202610)")
                .reorganizePartition("pmax", "pmax", "VALUES LESS THAN MAXVALUE")
                .buildReorganizePartition();
        assertTrue(reorganize.contains("REORGANIZE PARTITION `pmax` INTO ("), reorganize);
        assertTrue(reorganize.contains("PARTITION `p202609` VALUES LESS THAN (202610)"), reorganize);
        assertTrue(reorganize.contains("PARTITION `pmax` VALUES LESS THAN MAXVALUE"), reorganize);
    }

    // ==================== 空意图 ====================

    @Test
    @DisplayName("缺少必需目标时 buildXxx 一律返回 null，而不是渲染出语法错误的空语句")
    void emptyIntentsBuildNull() {
        assertNull(PartitionBuilder.of(MysqlPartitionManager.INSTANCE).table("t").buildPartition(),
                "未指定分区类型");
        assertNull(builder().table("t").rangeBy("id").buildPartition(),
                "RANGE 没有分区定义");
        assertNull(builder().table("t").buildAddPartition());
        assertNull(builder().table("t").buildDropPartition());
        assertNull(builder().table("t").buildTruncatePartition());
        assertNull(builder().table("t").buildReorganizePartition());
    }

    @Test
    @DisplayName("table() 会重置上一轮的 truncate/drop/分区定义：复用 Builder 不会误伤另一张表")
    void tableResetsPreviousIntent() {
        PartitionBuilder b = builder()
                .table("t_old")
                .truncatePartition("p20260801")
                .dropPartition("p20260802")
                .rangeBy("id")
                .addPartition("p1", "VALUES LESS THAN (10)");

        b.table("t_new");

        assertNull(b.buildTruncatePartition(), "换表后不能保留上一张表的 truncate 目标");
        assertNull(b.buildDropPartition(), "换表后不能保留上一张表的 drop 目标");
        assertNull(b.buildPartition(), "换表后分区定义与类型都应清空");
    }

    @Test
    @DisplayName("无缓存构造只能 build 不能 execute：取连接的唯一途径是 PartitionCache")
    void executeWithoutCacheFailsFast() {
        PartitionBuilder b = PartitionBuilder.of(MysqlPartitionManager.INSTANCE)
                .table("t").hashBy("id", 4);

        IllegalStateException ex = assertThrows(IllegalStateException.class, b::executePartition);
        assertTrue(ex.getMessage().contains("PartitionCache"), ex.getMessage());
        assertFalse(b.buildPartition().isBlank());
    }
}
