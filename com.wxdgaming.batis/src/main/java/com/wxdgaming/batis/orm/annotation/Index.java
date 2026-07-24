package com.wxdgaming.batis.orm.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 索引注解
 * <p>
 * 使用方式：
 * <ul>
 *   <li><b>字段级索引</b>：直接在字段上标注 {@code @Index(unique = true)}，此时 {@code columnList} 可省略，默认取当前字段</li>
 *   <li><b>复合索引</b>：在 {@link Table} 注解的 {@code indexes} 属性中配置，{@code columnList} 指定多列
 *       <pre>{@code
 *       @Table(name = "t_player", comment = "玩家表",
 *              indexes = {@Index(name = "idx_srv_level", columnList = "server_id,level", unique = false)})
 *       public class Player { ... }
 *       }</pre>
 *   </li>
 * </ul>
 *
 * @author wxd-gaming
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
public @interface Index {

    /** 索引名，不填时自动生成 */
    String name() default "";

    /**
     * 索引列名列表，多列用英文逗号分隔。
     * 字段级使用时可以留空，默认取当前标注字段的列名。
     */
    String columnList() default "";

    /** 是否唯一索引，默认 false（普通索引） */
    boolean unique() default false;
}
