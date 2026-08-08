package com.wxdgaming.batis.sql.partition;

import com.wxdgaming.batis.model.TableInfo;
import com.wxdgaming.batis.parser.EntityParser;
import com.wxdgaming.batis.sql.JdbcTemplate;
import com.wxdgaming.batis.sql.QueryRunner;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * 分区构建器（链式 API，与具体数据库无关）
 *
 * <p>持有 {@link PartitionConfig}（数据）、{@link PartitionManager}（SQL 生成）
 * 和 {@link PartitionCache}（缓存），通过 buildXxx() 生成 SQL，通过 executeXxx() 执行。</p>
 *
 * <h3>核心特性</h3>
 * <ul>
 *   <li>缓存检查：添加分区前自动检查是否已存在，跳过重复添加</li>
 *   <li>纯数字命名：分区名 p20260808，分区值 20260809，便于大小比较</li>
 *   <li>跨数据库：切换 DB 仅需更换 Manager 和 Expr 实现</li>
 *   <li>零入侵：独立附加模块，不修改现有任何代码</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * PartitionCache cache = new PartitionCache(template, MysqlPartitionMetaReader.INSTANCE);
 *
 * // 每日分区
 * PartitionBuilder.of(MysqlPartitionManager.INSTANCE, cache)
 *     .table(LogEntity.class)
 *     .withExpr(MysqlPartitionExpr.INSTANCE)
 *     .rangeByDaily("log_time", LocalDate.now(), LocalDate.now())
 *     .executeAddPartition();
 *
 * // 每月分区
 * PartitionBuilder.of(MysqlPartitionManager.INSTANCE, cache)
 *     .table(OrderEntity.class)
 *     .withExpr(MysqlPartitionExpr.INSTANCE)
 *     .rangeByMonthly("create_time",
 *         YearMonth.of(2026, 1), YearMonth.of(2026, 12))
 *     .withMaxValue()
 *     .executePartition();
 * }</pre>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-08-08
 */
@Slf4j
public class PartitionBuilder {

    private final PartitionManager manager;
    private final PartitionCache cache;
    private final PartitionConfig config = new PartitionConfig();
    private PartitionExpr expr;

    /** 被缓存跳过的分区名（仅日志用） */
    private final List<String> skipped = new ArrayList<>();

    private PartitionBuilder(PartitionManager manager, PartitionCache cache) {
        this.manager = manager;
        this.cache = cache;
    }

    /** 工厂方法（带缓存，支持 executeXxx） */
    public static PartitionBuilder of(PartitionManager manager, PartitionCache cache) {
        return new PartitionBuilder(manager, cache);
    }

    /** 工厂方法（不带缓存，仅支持 buildXxx） */
    public static PartitionBuilder of(PartitionManager manager) {
        return new PartitionBuilder(manager, null);
    }

    // ==================== 设置表达式助手 ====================

    public PartitionBuilder withExpr(PartitionExpr expr) {
        this.expr = expr;
        return this;
    }

    // ==================== 指定表 ====================

    public PartitionBuilder table(Class<?> entityClass) {
        resetConfig();
        TableInfo tableInfo = EntityParser.parse(entityClass);
        config.setTableName(tableInfo.getTableName().toLowerCase());
        return this;
    }

    public PartitionBuilder table(String tableName) {
        resetConfig();
        config.setTableName(tableName.toLowerCase());
        return this;
    }

    /** 重置配置（每次调用 table() 时清空） */
    private void resetConfig() {
        config.clearPartitions();
        config.clearDropNames();
        config.clearReorganize();
        config.setPartitionCount(0);
        config.setType(null);
        config.setExpression(null);
        skipped.clear();
    }

    // ==================== 分区类型 ====================

    public PartitionBuilder rangeBy(String expression) {
        config.setType(PartitionType.RANGE);
        config.setExpression(expression);
        return this;
    }

    public PartitionBuilder listBy(String expression) {
        config.setType(PartitionType.LIST);
        config.setExpression(expression);
        return this;
    }

    public PartitionBuilder hashBy(String expression, int count) {
        config.setType(PartitionType.HASH);
        config.setExpression(expression);
        config.setPartitionCount(count);
        return this;
    }

    public PartitionBuilder keyBy(String columns, int count) {
        config.setType(PartitionType.KEY);
        config.setExpression(columns);
        config.setPartitionCount(count);
        return this;
    }

    public PartitionBuilder partitionBy(PartitionType type, String expression) {
        config.setType(type);
        config.setExpression(expression);
        return this;
    }

    // ==================== 添加分区（带缓存检查） ====================

    /**
     * 添加一个分区定义
     * <p>若缓存存在且分区已存在，自动跳过。</p>
     */
    public PartitionBuilder addPartition(String name, String value) {
        if (cache != null && cache.contains(config.getTableName(), name)) {
            skipped.add(name);
            log.debug("分区 {}.{} 已存在，跳过", config.getTableName(), name);
            return this;
        }
        config.addPartition(new PartitionDef(name, value));
        return this;
    }

    /** 批量添加分区 */
    public PartitionBuilder addPartitions(PartitionDef... defs) {
        for (PartitionDef def : defs) {
            addPartition(def.getName(), def.getValue());
        }
        return this;
    }

    // ==================== 删除分区 ====================

    public PartitionBuilder dropPartition(String name) {
        config.addDropName(name);
        return this;
    }

    // ==================== 清空分区 ====================

    public PartitionBuilder truncatePartition(String name) {
        config.setTruncateName(name);
        return this;
    }

    // ==================== 重组分区 ====================

    public PartitionBuilder reorganizePartition(String oldPartition,
                                                 String newName, String newValue) {
        config.setReorganizeOldName(oldPartition);
        config.addReorganizeDef(new PartitionDef(newName, newValue));
        return this;
    }

    // ==================== 快捷：按日批量分区 ====================

    /**
     * 按日生成 RANGE 分区（纯数字命名）
     *
     * <p>分区名：p20260808<br>
     * 分区值：VALUES LESS THAN (20260809)</p>
     *
     * @param column 日期列名
     * @param start  起始日期（含）
     * @param end    结束日期（含）
     */
    public PartitionBuilder rangeByDaily(String column, LocalDate start, LocalDate end) {
        requireExpr();
        rangeBy(expr.byDay(column));

        LocalDate cursor = start;
        while (!cursor.isAfter(end)) {
            LocalDate next = cursor.plusDays(1);
            String name = "p" + expr.dayValue(cursor);
            String value = "VALUES LESS THAN (" + expr.dayValue(next) + ")";
            addPartition(name, value);
            cursor = next;
        }
        return this;
    }

    /**
     * 按日生成 RANGE 分区，每 N 天一个分区
     */
    public PartitionBuilder rangeByDaily(String column, LocalDate start,
                                          LocalDate end, int daysPerPart) {
        requireExpr();
        rangeBy(expr.byDay(column));

        LocalDate cursor = start;
        while (!cursor.isAfter(end)) {
            LocalDate next = cursor.plusDays(daysPerPart);
            String name = "p" + expr.dayValue(cursor);
            String value = "VALUES LESS THAN (" + expr.dayValue(next) + ")";
            addPartition(name, value);
            cursor = next;
        }
        return this;
    }

    // ==================== 快捷：按月批量分区 ====================

    /**
     * 按月生成 RANGE 分区（纯数字命名）
     *
     * <p>分区名：p202608<br>
     * 分区值：VALUES LESS THAN (202609)</p>
     *
     * @param column 日期列名
     * @param start  起始月份（含）
     * @param end    结束月份（含）
     */
    public PartitionBuilder rangeByMonthly(String column,
                                            YearMonth start, YearMonth end) {
        requireExpr();
        rangeBy(expr.byMonth(column));

        YearMonth cursor = start;
        while (!cursor.isAfter(end)) {
            YearMonth next = cursor.plusMonths(1);
            String name = "p" + expr.monthValue(cursor);
            String value = "VALUES LESS THAN (" + expr.monthValue(next) + ")";
            addPartition(name, value);
            cursor = next;
        }
        return this;
    }

    // ==================== 快捷：按周批量分区 ====================

    /**
     * 按周生成 RANGE 分区（纯数字命名）
     *
     * <p>分区名：p202632<br>
     * 分区值：VALUES LESS THAN (202633)</p>
     *
     * @param column 日期列名
     * @param start  起始日期（含，自动对齐到周一）
     * @param end    结束日期（含）
     */
    public PartitionBuilder rangeByWeekly(String column,
                                           LocalDate start, LocalDate end) {
        requireExpr();
        rangeBy(expr.byWeek(column));

        // 对齐到周一
        LocalDate cursor = start;
        while (cursor.getDayOfWeek().getValue() != 1) {
            cursor = cursor.minusDays(1);
        }

        while (!cursor.isAfter(end)) {
            LocalDate next = cursor.plusWeeks(1);
            String name = "p" + expr.weekValue(cursor);
            String value = "VALUES LESS THAN (" + expr.weekValue(next) + ")";
            addPartition(name, value);
            cursor = next;
        }
        return this;
    }

    // ==================== 快捷：按年批量分区 ====================

    public PartitionBuilder rangeByYearly(String column, int startYear, int endYear) {
        requireExpr();
        rangeBy(expr.byYear(column));

        for (int year = startYear; year <= endYear; year++) {
            String name = "p" + expr.yearValue(year);
            String value = "VALUES LESS THAN (" + expr.yearValue(year + 1) + ")";
            addPartition(name, value);
        }
        return this;
    }

    // ==================== 快捷：按季度批量分区 ====================

    public PartitionBuilder rangeByQuarterly(String column, int startYear, int startQuarter,
                                              int endYear, int endQuarter) {
        requireExpr();
        rangeBy(expr.byQuarter(column));

        int year = startYear;
        int quarter = startQuarter;
        while (year < endYear || (year == endYear && quarter <= endQuarter)) {
            String name = "p" + expr.quarterValue(year, quarter);
            // 下一个季度对应的值
            int nextQ = quarter + 1;
            int nextY = year;
            if (nextQ > 4) {
                nextQ = 1;
                nextY++;
            }
            String value = "VALUES LESS THAN (" + expr.quarterValue(nextY, nextQ) + ")";
            addPartition(name, value);

            quarter++;
            if (quarter > 4) {
                quarter = 1;
                year++;
            }
        }
        return this;
    }

    // ==================== MAXVALUE 收尾 ====================

    /** 添加 MAXVALUE 分区作为最后兜底 */
    public PartitionBuilder withMaxValue() {
        addPartition("pmax", "VALUES LESS THAN MAXVALUE");
        return this;
    }

    // ==================== 构建 SQL ====================

    public String buildPartition() {
        return manager.buildCreatePartition(config);
    }

    /**
     * 构建追加分区的 SQL
     * @return SQL 语句；无待添加分区时返回 null
     */
    public String buildAddPartition() {
        if (config.getPartitions().isEmpty()) {
            log.info("表 {} 没有需要新增的分区（全部已存在）", config.getTableName());
            if (!skipped.isEmpty()) {
                log.info("已跳过已存在的分区: {}", skipped);
            }
            return null;
        }
        return manager.buildAddPartition(config);
    }

    public String buildDropPartition() {
        return manager.buildDropPartition(config);
    }

    public String buildTruncatePartition() {
        return manager.buildTruncatePartition(config);
    }

    public String buildReorganizePartition() {
        return manager.buildReorganizePartition(config);
    }

    // ==================== 执行 ====================

    /** 执行建分区（非分区表 → 分区表） */
    public void executePartition() {
        String sql = buildPartition();
        execute(sql);
        // 建分区后刷新缓存
        if (cache != null) {
            cache.refresh(config.getTableName());
            for (PartitionDef def : config.getPartitions()) {
                cache.add(config.getTableName(), def.getName());
            }
        }
    }

    /** 执行追加分区（自动跳过已存在的，执行后更新缓存） */
    public void executeAddPartition() {
        if (config.getPartitions().isEmpty()) {
            log.info("表 {} 没有需要新增的分区，跳过执行", config.getTableName());
            if (!skipped.isEmpty()) {
                log.info("已跳过已存在的分区: {}", skipped);
            }
            return;
        }

        String sql = buildAddPartition();
        if (sql == null) return;
        execute(sql);

        // 执行成功后更新缓存
        if (cache != null) {
            for (PartitionDef def : config.getPartitions()) {
                cache.add(config.getTableName(), def.getName());
            }
        }
        log.info("表 {} 新增 {} 个分区: {}", config.getTableName(),
                config.getPartitions().size(),
                config.getPartitions().stream().map(PartitionDef::getName).toList());
        if (!skipped.isEmpty()) {
            log.info("跳过已存在的分区: {}", skipped);
        }
    }

    public void executeDropPartition() {
        String sql = buildDropPartition();
        execute(sql);
        if (cache != null) {
            for (String name : config.getDropNames()) {
                cache.remove(config.getTableName(), name);
            }
        }
    }

    public void executeTruncatePartition() {
        execute(buildTruncatePartition());
    }

    public void executeReorganizePartition() {
        execute(buildReorganizePartition());
        if (cache != null) {
            cache.refresh(config.getTableName());
        }
    }

    // ==================== 内部方法 ====================

    private void requireExpr() {
        if (expr == null) {
            throw new IllegalStateException(
                "请先调用 .withExpr(MysqlPartitionExpr.INSTANCE) 设置表达式助手");
        }
    }

    private void execute(String sql) {
        if (sql == null || sql.isEmpty()) return;
        JdbcTemplate jt = cache != null ? cache.getJdbcTemplate() : null;
        if (jt == null) {
            throw new IllegalStateException(
                "PartitionCache 未设置，无法执行 SQL。请使用 PartitionBuilder.of(manager, cache) 创建实例");
        }
        log.info("执行分区SQL:\n{}", sql);
        try (QueryRunner qr = new QueryRunner(jt)) {
            qr.update(sql);
        }
    }
}
