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
 *              indexes = &#64;Index(name = "idx_srv_level", columnList = "server_id,level", unique = false))
 *       public class Player { ... }
 *       </pre>
 *   </li>
 * </ul>
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
 * 不会生成两个索引——自动派生的唯一索引名与字段级默认名同为 "uk_" + 列名，而 IndexInfo 只按名字判等，
 * 去重后只剩一条。反过来说，两个语义不同的索引取了相同名字时，也会有一条被静默丢弃，
 * 命名冲突比缺索引更难查，建议显式指定 {@link #name()}。
 * </p>
 *
 * @author wxd-gaming
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
public @interface Index {

    /**
     * 索引名，不填时自动生成。
     * <p>字段级用：按 unique 取前缀，唯一是 "uk_" + 列名，普通是 "idx_" + 列名；
     * Table 级用：固定 "idx_" + 列名列表（逗号换成下划线、空格去掉）。
     * 名字是去重与漂移比对的依据，跨表保持稳定命名比省这几个字符更值钱。</p>
     */
    String name() default "";

    /**
     * 索引列名列表，多列用英文逗号分隔。字段级使用时可以留空，默认取当前标注字段的列名。
     * <p>列名按数据库列名匹配（解析时只做 trim，不做驼峰转换），因此这里写 {@code Column.name()}
     * 之后的真实列名。Table 级使用时留空没有意义：解析器仍会按逗号切一次，得到一个空列名，
     * 生成出的索引 DDL 是坏的。</p>
     */
    String columnList() default "";

    /**
     * 是否唯一索引，默认 false（普通索引）。
     * <p>字段级留空 {@link #name()} 时，本值决定自动命名的前缀（uk_ 还是 idx_），
     * 所以改本值会连带改变索引名，进而影响与已有索引的去重结果。</p>
     */
    boolean unique() default false;
}
