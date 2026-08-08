package com.wxdgaming.batis.sql.partition;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * 分区表达式接口（跨数据库抽象）
 *
 * <p>把"按日/周/月/季/年"语义转换成数据库函数表达式，
 * 同时提供 Java 侧的纯数字值计算，保证分区名与分区值统一为数字格式。</p>
 *
 * <h3>纯数字命名规则</h3>
 * <ul>
 *   <li>按日 → 8 位：{@code 20260808}</li>
 *   <li>按周 → 6 位：{@code 202632}（年+ISO周序号）</li>
 *   <li>按月 → 6 位：{@code 202608}</li>
 *   <li>按季 → 5 位：{@code 20263}（年*10+季度）</li>
 *   <li>按年 → 4 位：{@code 2026}</li>
 * </ul>
 *
 * <p>分区名 = "p" + 数字，如 {@code p20260808}；
 * 分区值 = 纯数字字符串，如 {@code "20260809"}。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-08-08
 */
public interface PartitionExpr {

    // ========== 分区键表达式（写入 SQL） ==========

    /** 按日 → 返回数字表达式 */
    String byDay(String column);

    /** 按周 → 返回数字表达式 */
    String byWeek(String column);

    /** 按月 → 返回数字表达式 */
    String byMonth(String column);

    /** 按季度 → 返回数字表达式 */
    String byQuarter(String column);

    /** 按年 → 返回数字表达式 */
    String byYear(String column);

    // ========== 分区值（纯数字字符串） ==========

    /** 日 → "20260808" */
    String dayValue(LocalDate date);

    /** 周 → "202632" */
    String weekValue(LocalDate date);

    /** 月 → "202608" */
    String monthValue(YearMonth ym);

    /** 季 → "20263" */
    String quarterValue(int year, int quarter);

    /** 年 → "2026" */
    String yearValue(int year);
}
