package com.wxdgaming.batis.sql.mysql.partition;

import com.wxdgaming.batis.sql.partition.PartitionExpr;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.IsoFields;

/**
 * MySQL 分区表达式助手（纯数字格式）
 *
 * <h3>命名规则</h3>
 * <pre>
 * 按日 → p20260808,  值: 20260809
 * 按周 → p202632,    值: 202633  (年+ISO周序号)
 * 按月 → p202608,    值: 202609
 * 按季 → p20263,     值: 20264   (年*10+季度)
 * 按年 → p2026,      值: 2027
 * </pre>
 *
 * <p>纯数字命名的好处：分区值可直接做数值大小比较，便于查询优化器路由。</p>
 *
 * <h3>为什么必须把日期"编码成整数"而不是直接用日期列</h3>
 * <p>MySQL 的 RANGE 分区只接受<b>整数表达式</b>，写 {@code PARTITION BY RANGE(log_time)}
 * （DATETIME 列）是不合法的。所以本类的做法是给每个档位一个把日期折算成整数的算式，
 * 让分区键和 {@code VALUES LESS THAN} 的边界落在同一个数轴上。</p>
 * <p>这个数轴唯一的正确性要求是<b>随时间单调递增</b>：一旦某个档位的编码不单调
 * （例如月份不零填充，1 月变成 20261 而 12 月是 202612），分区边界就会错位，
 * 行被静默路由进错误分区且<b>不报任何错</b>——这是分区最难发现的一类事故。
 * 测试用例⑩逐字断言各档位位数，并断言 20260101 与 20261231 的大小关系，就是为了守这条。</p>
 *
 * <h3>上下两组方法必须同口径</h3>
 * <p>{@code byXxx} 产出写进 DDL 的库端算式（对每行求值），{@code xxxValue} 产出 Java 端
 * 算好的边界数字，两者必须表示同一个整数。两侧分别由数据库和本类计算，
 * 改一侧不改另一侧不会有任何编译错误，只会得到错位的分区——所以成对实现、成对修改。</p>
 *
 * <p>无状态，私有构造 + {@link #INSTANCE} 单例供 Builder 注入。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-08-08
 */
public final class MysqlPartitionExpr implements PartitionExpr {

    public static final MysqlPartitionExpr INSTANCE = new MysqlPartitionExpr();

    private MysqlPartitionExpr() {}

    // ========== 分区键表达式（写入 SQL） ==========

    /**
     * 按日: YEAR(x)*10000 + MONTH(x)*100 + DAY(x) → 20260808
     * <p>等价于 TO_DAYS 的数值表示，但保持纯数字便于跨库迁移</p>
     * <p>补记：这里刻意不用 {@code TO_DAYS(col)}——它返回的是"距公元元年天数"的偏移量，
     * 与 {@link #dayValue} 的 yyyyMMdd 数字完全不同两套值；分区边界必须和 Java 端算出的
     * 数字同源，所以选算式而不是选库函数。算式与 {@code dayValue} 逐项对应，
     * 也顺带满足了对 DATE/DATETIME 列都能用的要求。</p>
     */
    @Override
    public String byDay(String column) {
        return "YEAR(" + column + ")*10000 + MONTH(" + column + ")*100 + DAY(" + column + ")";
    }

    /**
     * 按周: YEARWEEK(x, 1) → 202632
     * <p>MySQL 的 YEARWEEK 函数，mode=1 表示周一为每周第一天</p>
     * <p>mode 不能随便换：{@link #weekValue} 用的是 ISO 约定（周一起始 + 周所属年），
     * 与本算式只对 mode=1 对齐。另外 12 月末/1 月初的"边界周"在两种约定下仍可能出现 1 周之差，
     * 所以按周分区不建议把边界周名单写死（测试用例⑧为此只断言"生成了分区"）。</p>
     */
    @Override
    public String byWeek(String column) {
        return "YEARWEEK(" + column + ", 1)";
    }

    /**
     * 按月: YEAR(x)*100 + MONTH(x) → 202608
     * <p>乘 100 而不是乘 12，是为了让月份占固定两位（1 月也得写成 …01），
     * 这样 202601 到 202612 天然递增；位数一飘，1 月就会排到 12 月后面。</p>
     */
    @Override
    public String byMonth(String column) {
        return "YEAR(" + column + ")*100 + MONTH(" + column + ")";
    }

    /**
     * 按季度: YEAR(x)*10 + QUARTER(x) → 20263
     * <p>五个档位里位数最少的编码：季度只占一位（1~4），所以 Q4 之后必须靠年份进位
     * （20264 → 20271）才能保持单调。{@code PartitionBuilder.rangeByQuarterly} 里
     * 那两处"归一到下一年第 1 季"就是为了对齐这个算式，漏一处就会算出 20265 这种
     * 库端永远取不到的值，边界随之错位。</p>
     */
    @Override
    public String byQuarter(String column) {
        return "YEAR(" + column + ")*10 + QUARTER(" + column + ")";
    }

    /**
     * 按年: YEAR(x) → 2026
     * <p>最简形式；配合 {@code rangeByYearly} 时边界写成下一年，
     * 于是 {@code p2026 VALUES LESS THAN (2027)} 恰好覆盖 2026 全年。</p>
     */
    @Override
    public String byYear(String column) {
        return "YEAR(" + column + ")";
    }

    // ========== 分区值（纯数字字符串，用于 VALUES LESS THAN） ==========
    // 以下每个方法都是上面对应库端算式的 Java 复刻：同一条数据在库里算出来的值
    // 必须与此处返回的字符串相等，否则 Builder 生成的边界就与分区键不在同一数轴上。

    /** "20260808" */
    @Override
    public String dayValue(LocalDate date) {
        return String.valueOf(
            date.getYear() * 10000 + date.getMonthValue() * 100 + date.getDayOfMonth()
        );
    }

    /**
     * "202632"（ISO 周序号，与 YEARWEEK(x, 1) 对齐）
     * <p>年份取的是 {@code WEEK_BASED_YEAR}（这一周归属的年）而不是 {@code getYear()}：
     * 12 月底的那几天常常属于下一年的第 1 周，若用日历年会得到 {@code 202601}，
     * 比上一个分区的 {@code 202552} 还小，边界立刻不再单调——数据会静默落错分区。
     * 用周所属年后 202552 到 202601 的次序才和时间先后一致。</p>
     */
    @Override
    public String weekValue(LocalDate date) {
        int weekBasedYear = date.get(IsoFields.WEEK_BASED_YEAR);
        int weekOfWeekBasedYear = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        return String.valueOf(weekBasedYear * 100 + weekOfWeekBasedYear);
    }

    /** "202608"（月份零填充到两位，保证 1 月排在 12 月之前） */
    @Override
    public String monthValue(YearMonth ym) {
        return String.valueOf(ym.getYear() * 100 + ym.getMonthValue());
    }

    /** "20263"（年*10+季度，跨年靠年份进位维持单调） */
    @Override
    public String quarterValue(int year, int quarter) {
        return String.valueOf(year * 10 + quarter);
    }

    /** "2026" */
    @Override
    public String yearValue(int year) {
        return String.valueOf(year);
    }
}
