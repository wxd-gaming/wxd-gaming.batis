package com.wxdgaming.batis.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 表注解，标注在实体类上，定义表名、表注释、复合索引等元信息
 * <p>
 * 解析位置：EntityParser 表级元数据解析，优先级高于 {@link Entity}（两者同时存在时本注解生效，
 * Entity 被忽略）。不标注时表名取类简单名驼峰转下划线、注释为空，因此本注解是"要改表名、
 * 要写注释、要配复合索引"时才需要，不是实体必需注解。
 * </p>
 * <p>
 * 消费方：表名与注释进入建表/改表 DDL 与所有 SQL 拼接；{@link #indexes()} 被 EntityParser
 * 转成 IndexInfo 列表，参与建索引 DDL 与表结构漂移比对。字段级 {@link Column} 上的注解
 * 决定列的形态，本注解只决定表这一层。
 * </p>
 * <p>使用形态（复合索引必须走这里，索引注解本身没有类的目标限制）：</p>
 * <pre>
 * &#64;Table(name = "t_player", comment = "玩家表",
 *        indexes = &#64;Index(columnList = "server_id,level"))
 * public class Player { ... }
 * </pre>
 *
 * @author wxd-gaming
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Table {

    /** 表名，不填则默认使用类简单名（驼峰转下划线）；填写后原样使用，框架不再做命名转换 */
    String name() default "";

    /** 表注释，写进 DDL 的表注释；同时是 TableInfo 的 comment，供表结构比对使用 */
    String comment() default "";

    /**
     * 复合索引列表。
     * <p>EntityParser 只在找到 Table 注解时才解析这一项，用 {@link Entity} 声明实体会丢掉这段配置。
     * 索引名不可自定义，按 {@code 表名_列名(多列组合)_类型后缀} 自动生成（见 {@link Index}）。</p>
     */
    Index[] indexes() default {};
}
