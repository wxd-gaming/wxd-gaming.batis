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
     */
    @Override
    public String byDay(String column) {
        return "YEAR(" + column + ")*10000 + MONTH(" + column + ")*100 + DAY(" + column + ")";
    }

    /**
     * 按周: YEARWEEK(x, 1) → 202632
     * <p>MySQL 的 YEARWEEK 函数，mode=1 表示周一为每周第一天</p>
     */
    @Override
    public String byWeek(String column) {
        return "YEARWEEK(" + column + ", 1)";
    }

    /**
     * 按月: YEAR(x)*100 + MONTH(x) → 202608
     */
    @Override
    public String byMonth(String column) {
        return "YEAR(" + column + ")*100 + MONTH(" + column + ")";
    }

    /**
     * 按季度: YEAR(x)*10 + QUARTER(x) → 20263
     */
    @Override
    public String byQuarter(String column) {
        return "YEAR(" + column + ")*10 + QUARTER(" + column + ")";
    }

    /**
     * 按年: YEAR(x) → 2026
     */
    @Override
    public String byYear(String column) {
        return "YEAR(" + column + ")";
    }

    // ========== 分区值（纯数字字符串，用于 VALUES LESS THAN） ==========

    /** "20260808" */
    @Override
    public String dayValue(LocalDate date) {
        return String.valueOf(
            date.getYear() * 10000 + date.getMonthValue() * 100 + date.getDayOfMonth()
        );
    }

    /** "202632"（ISO 周序号，与 YEARWEEK(x, 1) 对齐） */
    @Override
    public String weekValue(LocalDate date) {
        int weekBasedYear = date.get(IsoFields.WEEK_BASED_YEAR);
        int weekOfWeekBasedYear = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        return String.valueOf(weekBasedYear * 100 + weekOfWeekBasedYear);
    }

    /** "202608" */
    @Override
    public String monthValue(YearMonth ym) {
        return String.valueOf(ym.getYear() * 100 + ym.getMonthValue());
    }

    /** "20263" */
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
