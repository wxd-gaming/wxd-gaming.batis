package com.wxdgaming.batis.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 索引注解
 * <p>
 * 使用方式：
 * <ul>
 *   <li><b>字段级索引</b>：直接在字段上标注唯一或普通索引（{@code unique = true} 表示唯一），
 *       此时 {@link #columnList()} 可省略，默认取当前字段</li>
 *   <li><b>复合索引</b>：在 {@link Table} 注解的 {@code indexes} 属性中配置，
 *       {@link #columnList()} 指定多列
 *       <pre>
 *       &#64;Table(name = "t_player", comment = "玩家表",
 *              indexes = &#64;Index(columnList = "server_id,level"))
 *       public class Player { ... }
 *       </pre>
 *   </li>
 * </ul>
 * <p>
 * <b>索引名不可自定义，统一生成为 {@code 表名_列名(多列按下划线组合)_类型后缀}</b>
 * （唯一索引 {@code _uk}、普通索引 {@code _idx}），例如 {@code t_player_server_id_level_idx}。
 * 名字是"表 + 列 + 唯一性"的纯函数：同样的定义在任何环境生成同一个名字——
 * 它同时是去重依据（定义相同则名字相同，自然合并）与结构漂移比对的锚点，
 * 并且自带表名前缀，在 PG 这种索引名 schema 级唯一的库上也不会跨表撞名。
 * 自定义入口被刻意拿掉：名字一旦可以两写，"同一个索引两个名字"和
 * "两个索引共用一个名字"都会变成运行期才能发现的配置错误。
 * 名字超过 63 字符（PG/MySQL 双方便携上限）时截断中段并补稳定哈希，类型后缀始终保留。
 * </p>
 * <p>
 * <b>解析位置</b>：EntityParser 的索引解析分三步，全部在本注解上——先扫字段级（含父类字段），
 * 再读 Table 的 indexes 数组，最后把 {@code Column.unique = true} 的列自动派生成唯一索引。
 * 三步结果合并后按索引名去重、按名字排序，产出 IndexInfo 列表，供建表/改表方言生成索引 DDL。
 * </p>
 * <p>
 * <b>没有 Target 元注解限制</b>：这是刻意的——同一个注解既要能标字段（字段级索引），
 * 又要能嵌在 Table 的属性里（数组元素不是声明位置）。代价是标在方法、构造器或包上不会报错，
 * 而解析器只认字段级和 Table 级两种位置，别处标了等于静默无效，索引不会被创建。
 * </p>
 * <p>
 * <b>与唯一约束的关系</b>：字段上同时写 {@code Column.unique = true} 和本注解的 {@code unique = true}
 * 不会生成两个索引——两者产出的是同一个名字（同列同唯一性），IndexInfo 按名字判等去重后只剩一条。
 * 若同列只有普通索引（{@code unique = false}），{@code Column.unique = true} 会把它就地升级为唯一索引，
 * 名字同步换成 {@code _uk} 后缀。
 * </p>
 *
 * @author wxd-gaming
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
public @interface Index {

    /**
     * 索引列名列表，多列用英文逗号分隔。字段级使用时可以留空，默认取当前标注字段的列名。
     * <p>列名按数据库列名匹配（解析时只做 trim，不做驼峰转换），因此这里写 {@code Column.name()}
     * 之后的真实列名。Table 级使用时留空没有意义：解析器仍会按逗号切一次，得到一个空列名，
     * 生成出的索引 DDL 是坏的。</p>
     */
    String columnList() default "";

    /**
     * 是否唯一索引，默认 false（普通索引）。
     * <p>本值参与索引名生成（{@code _uk} 还是 {@code _idx} 后缀），
     * 所以改本值会连带改变索引名，进而影响与库端已有索引的漂移比对结果。</p>
     */
    boolean unique() default false;
}
