package com.wxdgaming.batis.sql.partition;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * 分区表达式接口（跨数据库抽象）
 *
 * <p>把"按日/周/月/季/年"语义转换成数据库函数表达式，
 * 同时提供 Java 侧的纯数字值计算，保证分区名与分区值统一为数字格式。</p>
 *
 * <h3>为什么一个接口要同时管两件事</h3>
 * <p>{@code byDay(column)} 产出的是<b>写进 DDL 的库端表达式</b>（每个分区一个字符串，
 * 执行时由数据库对每行求值）；{@code dayValue(date)} 产出的是<b>Java 端算好的边界数字</b>。
 * 两者必须是对同一个整数值的不同写法：库端对某行求出的表达式值要和 Java 端生成的边界值
 * 做比较，任何一侧口径不一致（比如一侧按周一起始、另一侧按周日起始），
 * 分区边界就会整体错位，行被静默路由进错误分区且不报错。
 * 所以这两组方法必须成对实现、成对测试，不允许只改一侧。</p>
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
 * <p>位数固定不是排版问题而是正确性前提：若月份按 {@code 20261}、{@code 202612}
 * 这样的不定宽输出，字典序/数值序都会让 1 月排到 12 月之后。测试用例⑩逐字钉住了
 * 各档位位数，并要求边界值转成整数后的大小关系与时间先后一致。</p>
 *
 * <p>实现方按方言提供（当前只有 {@code MysqlPartitionExpr}），
 * {@link PartitionBuilder} 的快捷方法都要求先 {@code withExpr(...)} 才能用。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-08-08
 */
public interface PartitionExpr {

    // ========== 分区键表达式（写入 SQL） ==========
    // 返回值会被拼进 PARTITION BY RANGE(此处)，必须对每个分区档位取到不同的整数

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
    // 用于 VALUES LESS THAN 的边界，必须与上面的库端表达式同口径

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
