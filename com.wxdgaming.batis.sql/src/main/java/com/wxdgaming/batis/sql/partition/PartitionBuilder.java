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
 * <h3>本类的三条主线：分区类型 × 表达式 × 边界生成</h3>
 * <ol>
 *   <li><b>分区类型</b>（{@code rangeBy/listBy/hashBy/keyBy/partitionBy}）只写
 *   {@code type} 与 {@code expression}，决定 Manager 走哪个渲染分支。
 *   RANGE/LIST 需要逐分区给边界，HASH/KEY 只需要分区数。</li>
 *   <li><b>表达式</b>（{@link PartitionExpr}，由 {@code withExpr} 注入）负责把"按日/按月"
 *   翻译成库端整数表达式。MySQL 的 RANGE 分区只接受整数表达式，所以日期列必须走
 *   {@code YEAR(x)*10000 + MONTH(x)*100 + DAY(x)} 这类编码，而不是直接写 DATETIME 列。</li>
 *   <li><b>边界生成</b>（{@code rangeByDaily/Monthly/Weekly/Yearly/Quarterly}）把时间段
 *   展开成"分区名 + 上界数字"的有序列表，是本类最容易出错的一段。</li>
 * </ol>
 *
 * <h3>每段最终生成的 SQL 长什么样</h3>
 * <pre>{@code
 * // rangeByDaily("log_time", 08-01, 08-03) + withMaxValue() → executePartition()
 * ALTER TABLE `t_daily_log`
 * PARTITION BY RANGE(YEAR(log_time)*10000 + MONTH(log_time)*100 + DAY(log_time))
 * (
 *   PARTITION p20260801 VALUES LESS THAN (20260802),
 *   PARTITION p20260802 VALUES LESS THAN (20260803),
 *   PARTITION p20260803 VALUES LESS THAN (20260804),
 *   PARTITION pmax VALUES LESS THAN MAXVALUE
 * );
 *
 * // 同一张表再跑 rangeByDaily(08-04, 08-05) → executeAddPartition()
 * ALTER TABLE `t_daily_log` ADD PARTITION (
 *   PARTITION p20260804 VALUES LESS THAN (20260805),
 *   PARTITION p20260805 VALUES LESS THAN (20260806)
 * );
 *
 * // hashBy("id", 8) → executePartition()：没有边界值，也没有名字，名字由库端生成 p0…p7
 * ALTER TABLE `t_daily_log`
 * PARTITION BY HASH(id)
 * PARTITIONS 8;
 * }</pre>
 *
 * <h3>边界语义：LESS THAN 是"排他上界"，且只有上界</h3>
 * <p>每个分区只声明上界，下界由前一个分区的上界隐含。因此
 * {@code p20260801 VALUES LESS THAN (20260802)} 恰好容纳 08-01 一天（名与值同用日编号，
 * 名字取区间起点、边界取区间终点的下一天）。两个后果必须知道：</p>
 * <ul>
 *   <li>列表<b>第一个</b>分区没有下界，早于起始日期的历史数据会落进第一个分区而不是报错，
 *   所以起始日期设得偏早是安全的；</li>
 *   <li><b>超出最后一个上界</b>的数据会被数据库直接拒绝写入（丢数据），
 *   这正是 {@link #withMaxValue()} 存在的理由——尾部加一个 MAXVALUE 兜底分区。
 *   但兜底分区一旦存在，就不能再用 {@code ADD PARTITION} 往它前面插分区
 *   （MySQL 硬限制，测试用例②因此刻意不在前置建表时加 pmax），
 *   需要扩边界时只能改用 {@link #reorganizePartition}。</li>
 * </ul>
 *
 * <h3>关于"与具体数据库无关"的实际边界</h3>
 * <p>DDL 骨架（ALTER TABLE / PARTITION BY / 反引号 / RANGE COLUMNS / PARTITIONS）确实全部在
 * 方言层的 {@link PartitionManager} 实现里；但分区<b>边界子句的文本</b>
 * （{@code "VALUES LESS THAN (n)"}、{@code "VALUES LESS THAN MAXVALUE"}）是在本类拼出来的，
 * 也就是说这半句 MySQL 味道的话术目前留在了方言无关层。换 pgsql 时这部分需要下沉或参数化，
 * 不要误以为本类完全不含方言字符串。</p>
 *
 * <h3>核心特性</h3>
 * <ul>
 *   <li>缓存检查：添加分区前自动检查是否已存在，跳过重复添加</li>
 *   <li>纯数字命名：分区名 p20260808，分区值 20260809，便于大小比较</li>
 *   <li>跨数据库：切换 DB 仅需更换 Manager 和 Expr 实现</li>
 *   <li>零入侵：独立附加模块，不修改现有任何代码</li>
 * </ul>
 *
 * <h3>实例状态与复用姿势</h3>
 * <p>一个实例 = "一次待执行的 DDL"：{@code table()} 会 {@link #resetConfig()} 清空上一轮的
 * 分区列表，所以同一个 builder 可以连续跑多轮（测试用例②就是这么复用的），
 * 但每次都必须从 {@code table()} 重新开始。{@code expr} 是唯一不被 reset 清掉的字段，
 * 一旦 {@code withExpr} 就对本实例后续所有轮次生效。</p>
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

    /** SQL 渲染器：方言插入点，本类所有 buildXxx 都委托给它 */
    private final PartitionManager manager;
    /** 已有分区视图：为 null 时本实例只能 build 不能 execute（execute 还要靠它拿连接） */
    private final PartitionCache cache;
    /** 本轮待渲染的数据，table() 会清空它 */
    private final PartitionConfig config = new PartitionConfig();
    /** 日期档位 → 数字表达式的翻译器；实例级设置，不随 table() 重置 */
    private PartitionExpr expr;

    /** 被缓存跳过的分区名（仅日志用） */
    // 只用于打日志说明"这次跑了几轮里哪些是幂等跳过的"，不参与任何 SQL 生成
    private final List<String> skipped = new ArrayList<>();

    private PartitionBuilder(PartitionManager manager, PartitionCache cache) {
        this.manager = manager;
        this.cache = cache;
    }

    /**
     * 工厂方法（带缓存，支持 executeXxx）
     * <p>缓存同时承担两件事：ADD 前去重、执行后同步库端视图，
     * 并且是 execute() 取 {@link JdbcTemplate} 的唯一途径。</p>
     */
    public static PartitionBuilder of(PartitionManager manager, PartitionCache cache) {
        return new PartitionBuilder(manager, cache);
    }

    /**
     * 工厂方法（不带缓存，仅支持 buildXxx）
     * <p>用于"先看 SQL 再决定执行"的场景（生产 DDL 走人工评审，测试用例⑥）。
     * 此时没有任何库端分区视图，{@code addPartition} 全部直接进列表、不会跳过；
     * 调 executeXxx 会在 {@link #execute(String)} 处以 IllegalStateException 失败。</p>
     */
    public static PartitionBuilder of(PartitionManager manager) {
        return new PartitionBuilder(manager, null);
    }

    // ==================== 设置表达式助手 ====================

    /**
     * 注入日期表达式翻译器（按日/周/月/季/年的快捷方法都依赖它）
     * <p>必须在使用 {@code rangeByXxx} 之前调用，否则 {@link #requireExpr()} 直接抛异常。
     * 之所以要显式注入而不是内置默认值：库端表达式和 Java 端边界值必须同口径，
     * 这件事天然是方言职责（见 {@code MysqlPartitionExpr}）。</p>
     */
    public PartitionBuilder withExpr(PartitionExpr expr) {
        this.expr = expr;
        return this;
    }

    // ==================== 指定表 ====================

    /**
     * 由实体类指定表（解析实体得到真实表名，并顺带保证表已存在）
     * <p>表名统一转小写：缓存 key 和后续 {@code cache.contains} 都按小写比对，
     * 大小写混用会让去重失效。</p>
     * <p>注意这一步会 {@link #resetConfig()} 丢掉上一轮的分区列表——复用同一实例跑第二轮时，
     * 必须从本方法重新开始。</p>
     */
    public PartitionBuilder table(Class<?> entityClass) {
        resetConfig();
        TableInfo tableInfo = EntityParser.parse(entityClass);
        config.setTableName(tableInfo.getTableName().toLowerCase());
        return this;
    }

    /** 由表名直接指定（不依赖实体类，用于非 ORM 管理的表） */
    public PartitionBuilder table(String tableName) {
        resetConfig();
        config.setTableName(tableName.toLowerCase());
        return this;
    }

    /**
     * 重置配置（每次调用 table() 时清空）
     * <p>清掉本轮所有意图数据，但<b>不清 expr</b>：翻译器是实例级配置，
     * 这样 {@code builder.table(A).withExpr(E).…} 之后换表重用不必重复注入。</p>
     */
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
    // 这一组只写 type + expression（HASH/KEY 另写 count），不碰分区列表，
    // 因此可以任意顺序调用；Manager 依据"count 是否 &gt; 0 且分区列表是否为空"选渲染分支。

    /**
     * RANGE 分区：按表达式的数值区间切分，后续要用 addPartition 逐个给上界。
     * <p>{@code expression} 这里已经是一个<b>整数表达式</b>（如
     * {@code YEAR(log_time)*10000 + …}），通常由 {@code rangeByDaily} 等快捷方法
     * 经 {@link PartitionExpr} 生成后传入，而不是手写列名——MySQL 的 RANGE 不接受裸 DATETIME 列。</p>
     */
    public PartitionBuilder rangeBy(String expression) {
        config.setType(PartitionType.RANGE);
        config.setExpression(expression);
        return this;
    }

    /** LIST 分区：按枚举值分派，分区值形如 {@code VALUES IN (1, 2, 3)}（测试用例⑫） */
    public PartitionBuilder listBy(String expression) {
        config.setType(PartitionType.LIST);
        config.setExpression(expression);
        return this;
    }

    /**
     * HASH 分区：按表达式散列成 count 个等长分区，用于散写入压力而不是按时间裁剪。
     * <p>不需要也不能提供边界值，DDL 只渲染成 {@code PARTITION BY HASH(x) PARTITIONS n}，
     * 分区名由库端生成（p0…p{count-1}），所以本类的按日/按月批量方法对它没有意义。</p>
     *
     * @param count 分区个数，必须与最终期望的分区数一致（测试用例⑦断言"恰为 8 个"就是防参数没接上）
     */
    public PartitionBuilder hashBy(String expression, int count) {
        config.setType(PartitionType.HASH);
        config.setExpression(expression);
        config.setPartitionCount(count);
        return this;
    }

    /**
     * KEY 分区：与 HASH 同样只要分区数，差别在库端用内部哈希函数对列取值。
     * <p>{@code columns} 是列名（多列逗号分隔），可以是主键/非整型列，
     * 因此比 HASH 更通用；渲染形态与 HASH 一致。</p>
     */
    public PartitionBuilder keyBy(String columns, int count) {
        config.setType(PartitionType.KEY);
        config.setExpression(columns);
        config.setPartitionCount(count);
        return this;
    }

    /** 逃生口：直接指定类型与表达式，用于快捷方法没覆盖的类型（RANGE_COLUMNS / LIST_COLUMNS 等） */
    public PartitionBuilder partitionBy(PartitionType type, String expression) {
        config.setType(type);
        config.setExpression(expression);
        return this;
    }

    // ==================== 添加分区（带缓存检查） ====================

    /**
     * 添加一个分区定义
     * <p>若缓存存在且分区已存在，自动跳过。</p>
     *
     * <p>整个链式 API 里唯一有"库端判断"的一步，也是补分区幂等性的落点：
     * 缓存命中就只记一条 skipped 日志、不进分区列表，最终生成的 DDL 里根本没有这个分区，
     * 所以同一个作业重复执行不会撞上重复分区名错误（测试用例②）。
     * 不带缓存构造时这里永不让步，每次都进列表。</p>
     *
     * <p>{@code value} 是完整边界子句（含 {@code VALUES LESS THAN} 前缀）而非裸数字，
     * 本方法不校验其内容，写错只能在执行时由数据库暴露。</p>
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

    /**
     * 批量添加分区
     * <p>逐个走 {@link #addPartition(String, String)}，因此同样享受缓存去重。
     * 传入顺序即 DDL 中的分区顺序，RANGE 语义下<b>必须</b>保持边界值递增，
     * 顺序错了会直接生成非单调的 DDL。</p>
     */
    public PartitionBuilder addPartitions(PartitionDef... defs) {
        for (PartitionDef def : defs) {
            addPartition(def.getName(), def.getValue());
        }
        return this;
    }

    // ==================== 删除分区 ====================

    /**
     * 记下一个待删除的分区名（可多次调用，合并成一条 ALTER 里的多个 DROP PARTITION）
     * <p>按<b>名字</b>而不是按位置删除——这是分区维护的安全底线：实现若退化成按序号删，
     * "删掉最老一个分区"就会变成删掉随机一个分区。测试用例④用"邻居仍在"来暴露这种退化。</p>
     * <p>注意本方法<b>不做缓存存在性检查</b>（与 addPartition 不同）：删一个库里不存在的
     * 分区名会在执行时由数据库报错。语义上是"点名删除"，调用方要保证名字来自缓存或库端。</p>
     * <p>语义提醒：DROP 是分区定义连同数据一起消失，只用于数据保留期到期的清理；
     * 想清数据但留边界请改用 {@link #truncatePartition(String)}。</p>
     */
    public PartitionBuilder dropPartition(String name) {
        config.addDropName(name);
        return this;
    }

    // ==================== 清空分区 ====================

    /**
     * 记下一个待清空的分区名：只删数据、<b>保留分区定义与边界</b>。
     * <p>与 {@link #dropPartition} 的分工就在"留不留定义"：留定义时后续数据仍按边界落回原分区，
     * 分区裁剪继续有效；若误用 DROP，边界消失，新数据全堆进兜底分区，
     * 按时间查询退化成全表扫且不报错（测试用例⑪专门断言定义仍在）。
     * 因此执行后也<b>不需要</b>同步缓存——分区名列表没变。</p>
     */
    public PartitionBuilder truncatePartition(String name) {
        config.setTruncateName(name);
        return this;
    }

    // ==================== 重组分区 ====================

    /**
     * 把 {@code oldPartition} 拆成新的若干分区（数据会按新边界重分布）
     * <p>这是唯一能改动已有分区边界的动作，典型用途有两个：
     * 给尾部 MAXVALUE 兜底分区"腾出"一个具体区间（ADD 在兜底存在时用不了），
     * 或把粗粒度分区细化按月/按天。代价接近一次局部数据重写，不适合高频调用。</p>
     * <p>执行后分区名列表发生变化（旧名消失、新名出现），增量维护推不出来，
     * 所以 {@link #executeReorganizePartition()} 走整体 {@code refresh}。</p>
     */
    public PartitionBuilder reorganizePartition(String oldPartition,
                                                 String newName, String newValue) {
        config.setReorganizeOldName(oldPartition);
        config.addReorganizeDef(new PartitionDef(newName, newValue));
        return this;
    }

    // ==================== 快捷：按日批量分区 ====================
    // 以下五组 rangeByXxx 是同一个模板的五个档位：
    //   requireExpr() → rangeBy(expr.byXxx(column)) 设好分区键
    //   → 从 start 步进到 end，每步产出「名字 = 区间起点的数字，边界 = 区间终点下一段的数字」
    // 名字与边界都走同一个 expr，保证不会出现"名字按周一起始、边界按周日起始"的口径分裂。

    /**
     * 按日生成 RANGE 分区（纯数字命名）
     *
     * <p>分区名：p20260808<br>
     * 分区值：VALUES LESS THAN (20260809)</p>
     *
     * <p>生成结果形如：{@code PARTITION p20260801 VALUES LESS THAN (20260802)}，
     * 即一天一个分区、边界是"下一天"。循环条件 {@code !cursor.isAfter(end)} 表示
     * {@code end} 当天也会生成分区（闭区间）。</p>
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
            // 边界取"下一天"的数字，LESS THAN 是排他上界，所以本分区恰好容纳 cursor 当天
            String value = "VALUES LESS THAN (" + expr.dayValue(next) + ")";
            addPartition(name, value);
            cursor = next;
        }
        return this;
    }

    /**
     * 按日生成 RANGE 分区，每 N 天一个分区
     * <p>用于日志量小、不想让分区数量爆炸的表（分区数本身在库端有元数据开销）。
     * 命名仍取区间起点，如步长 7 时得到 {@code p20260801 VALUES LESS THAN (20260808)}。
     * 注意 {@code daysPerPart} 必须为正，0 或负数会让 {@code next} 不前进从而死循环，
     * 这里在入口直接拒绝而不是让循环卡死。</p>
     */
    public PartitionBuilder rangeByDaily(String column, LocalDate start,
                                          LocalDate end, int daysPerPart) {
        if (daysPerPart <= 0) {
            throw new IllegalArgumentException("daysPerPart 必须为正数: " + daysPerPart);
        }
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
     * <p>分区键随之变成 {@code YEAR(col)*100 + MONTH(col)}（6 位、零填充），
     * 因此 1 月是 202601 而不是 20261——位数一致才能既按名字排好序又让边界单调。
     * 用 {@link YearMonth} 而不是 {@code LocalDate} 步进，也顺带避免了"某月 31 号
     * 加一个月溢出到下月"的日期陷阱。</p>
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
     * <p>先把起点回退对齐到周一，再按 7 天步进——这样分区边界与库端
     * {@code YEARWEEK(col, 1)}（周一为一周之始）口径一致；不对齐的话同一周的数据
     * 会被切成两个分区。</p>
     *
     * <p>边界数字是"年*100 + 周序号"的 6 位形式，且周序号取 ISO 的<b>周所属年</b>
     * 而不是日历年，跨年那一周才不会从 202601 倒退到 202552 而破坏单调性。
     * 即便如此，12 月末/1 月初的边界周在不同约定下仍可能有 1 周之差，
     * 所以测试用例⑧只断言"确实生成了分区"，具体名单不写死。</p>
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

    /**
     * 按年生成 RANGE 分区（4 位命名，如 p2026 / VALUES LESS THAN (2027)）
     * <p>分区键简化为 {@code YEAR(col)}。起止年份是闭区间且首尾都会生成分区
     * （测试用例⑨逐个点名 2024~2027，因为跨度大于 1 时最容易漏掉起点或漏建终点）。</p>
     */
    public PartitionBuilder rangeByYearly(String column, int startYear, int endYear) {
        requireExpr();
        rangeBy(expr.byYear(column));

        for (int year = startYear; year <= endYear; year++) {
            String name = "p" + expr.yearValue(year);
            // 边界是下一年的年份：LESS THAN 排他，所以本分区正好容纳 year 全年
            String value = "VALUES LESS THAN (" + expr.yearValue(year + 1) + ")";
            addPartition(name, value);
        }
        return this;
    }

    // ==================== 快捷：按季度批量分区 ====================

    /**
     * 按季度生成 RANGE 分区（5 位命名：年*10+季度，如 p20263）
     * <p>分区键是 {@code YEAR(col)*10 + QUARTER(col)}。</p>
     * <p>编码位数最小、也最容易出问题的一处：Q4 之后必须把年份进位（20264 → 20271），
     * 若只让季度加一变成 20265，边界就不再单调且与库端取值对不上，
     * 于是那段数据会静默落进错误分区——这是本方法出现两次"进位归一"逻辑的原因
     * （一次算边界值、一次推进循环游标）。</p>
     * <p>季度边界是闭区间：{@code endQuarter} 当季也会生成分区。</p>
     */
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

    /**
     * 添加 MAXVALUE 分区作为最后兜底
     *
     * <p>没有兜底分区时，任何超出最后一个边界值的数据都会被数据库<b>直接拒绝写入</b>
     * （时间超出建表范围的日志就此丢失，测试用例①③⑨都专门断言 pmax 存在）。
     * 分区名固定为 {@code pmax}，走 {@link #addPartition} 所以同样享受缓存去重，
     * 重复执行不会撞上"分区名已存在"。</p>
     *
     * <p>两条使用约束：</p>
     * <ul>
     *   <li>必须在所有 {@code rangeByXxx} <b>之后</b>调用。分区列表的顺序就是 DDL 里的顺序，
     *   而 MySQL 要求 MAXVALUE 分区位于末尾，先调用会生成非单调的非法 DDL。</li>
     *   <li>如果这张表之后还要滚动 {@code ADD PARTITION}，基础分区阶段<b>不要</b>加 pmax
     *   （尾部是兜底分区时无法再往前插入分区），需要扩边界时改用
     *   {@link #reorganizePartition}。MySQL 的硬限制，测试用例②的前置就刻意不带 pmax。</li>
     * </ul>
     */
    public PartitionBuilder withMaxValue() {
        addPartition("pmax", "VALUES LESS THAN MAXVALUE");
        return this;
    }

    // ==================== 构建 SQL ====================
    // buildXxx 全部是纯渲染、不碰数据库，可以脱库断言文本（测试用例⑥⑫），
    // 也便于把 SQL 交给人工评审后再手工执行。

    /**
     * 构建"非分区表 → 分区表"的 DDL
     * <p>与 {@link #buildAddPartition()} 不同，这里<b>没有空列表保护</b>：
     * 类型是 RANGE 而分区列表为空时，会把空括号交给 Manager 渲染出无法执行的语句。
     * 调用前要保证已经至少添加过一个分区定义。</p>
     */
    public String buildPartition() {
        return manager.buildCreatePartition(config);
    }

    /**
     * 构建追加分区的 SQL
     *
     * <p>返回 null 是正常结果而不是错误：表示本轮候选分区已被缓存全部判定为"已存在"，
     * 于是补分区作业什么都不用做。日志里会打出跳过的名单，便于确认幂等确实生效。</p>
     *
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

    /** 构建删除分区的 SQL（dropNames 为空时会渲染出无 DROP 子句的语句，调用方需自行保证有目标） */
    public String buildDropPartition() {
        return manager.buildDropPartition(config);
    }

    /** 构建清空分区的 SQL（只清数据，保留分区定义） */
    public String buildTruncatePartition() {
        return manager.buildTruncatePartition(config);
    }

    /** 构建重组分区的 SQL（把原分区拆成 reorganizeDefs 里的若干新分区） */
    public String buildReorganizePartition() {
        return manager.buildReorganizePartition(config);
    }

    // ==================== 执行 ====================
    // 每个 executeXxx 的差别主要在"执行完之后怎么让缓存重新对齐库端"，
    // 对不齐的后果是下一轮去重判断出错：该加的被跳过、不该加的又加一遍。

    /**
     * 执行建分区（非分区表 → 分区表）
     * <p>先 {@code refresh} 丢掉旧的分区视图、从元数据重读，再把本轮意图的名字补写进缓存。
     * 两步各有必要：refresh 负责拿到库端的真实结果（HASH/KEY 的 p0…p7 根本不是本轮生成的，
     * 只能靠重读拿到）；补写名字则是防元数据查询降级为空视图时缓存仍然留不住本轮成果。</p>
     * <p>提醒：本方法会重建整表数据，大表上要谨慎调度（业务低峰、或改用 ADD 滚动扩分区）。</p>
     */
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

    /**
     * 执行追加分区（自动跳过已存在的，执行后更新缓存）
     * <p>这是日常滚动补分区的主入口。分区列表为空时（候选全被缓存判定为已存在）
     * <b>直接返回、一条 DDL 都不发</b>，正是这一点让定时作业可以幂等重跑。</p>
     * <p>缓存只在 {@code execute} 成功返回之后才写，DDL 失败时不会留下"库里没有却缓存里存在"
     * 的脏名字。</p>
     */
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

    /**
     * 执行删除分区（定义和数据一起消失），并把删掉的名字从缓存移除
     * <p>缓存不同步的话，将来想重建同一时间窗口的分区会被误判为"已存在"而跳过，
     * 数据就无处可落（测试用例④断言的正是这一点）。</p>
     */
    public void executeDropPartition() {
        String sql = buildDropPartition();
        execute(sql);
        if (cache != null) {
            for (String name : config.getDropNames()) {
                cache.remove(config.getTableName(), name);
            }
        }
    }

    /**
     * 执行清空分区
     * <p>刻意<b>不动缓存</b>：TRUNCATE 只删数据、分区名列表没变，
     * 缓存仍然是准确的。这里如果顺手加一次 remove，反而会把还在的分区记成不存在。</p>
     */
    public void executeTruncatePartition() {
        execute(buildTruncatePartition());
    }

    /**
     * 执行重组分区，并整体刷新缓存
     * <p>重组让旧分区名消失、新名字出现，增量 add/remove 推不出准确结果，
     * 所以直接重读库端视图。</p>
     */
    public void executeReorganizePartition() {
        execute(buildReorganizePartition());
        if (cache != null) {
            cache.refresh(config.getTableName());
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 按日/周/月/季/年的快捷方法前置检查
     * <p>缺了 {@link PartitionExpr} 就没法把日期档位翻译成"库端表达式 + Java 端边界数字"，
     * 宁可在这里快速失败，也不要生成出边界与表达式口径不一致的分区（那种表会静默路由错数据）。
     * 异常文案里以 MySQL 举例，当前工程也确实是先有 MySQL 方言。</p>
     */
    private void requireExpr() {
        if (expr == null) {
            throw new IllegalStateException(
                "请先调用 .withExpr(MysqlPartitionExpr.INSTANCE) 设置表达式助手");
        }
    }

    /**
     * 发送 DDL
     * <p>连接只能从 {@link PartitionCache} 拿，所以无缓存构造（{@code of(manager)}）走到这里
     * 会明确抛异常而不是静默什么都不做——这是"只能 build 不能 execute"的实现依据。</p>
     * <p>{@code sql} 为 null/空串时静默返回（对应 ADD 全部跳过的正常路径）。
     * 每条 DDL 各开一个 {@link QueryRunner}，失败异常原样抛出：DDL 在 MySQL 下本来就不成事务，
     * 不做回滚也不吞异常。</p>
     */
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
