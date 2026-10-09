package com.wxdgaming.batis.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 实体标记注解
 * <p>
 * 解析位置：EntityParser 解析表名与表注释时，先找 {@link Table}，没有才找本注解，
 * 两者都没有则直接取类简单名做驼峰转下划线。也就是说<b>不标任何一个也能正常建表/读写</b>，
 * 本注解只在你想显式声明"这是个实体"、或想改表名/加表注释时才需要。
 * </p>
 * <p>
 * 与 {@link Table} 的分工：二者在表名、注释上等价，但只有 Table 带 indexes 属性可配复合索引
 * ——EntityParser 的表级索引解析只读 Table，因此用 Entity 时复合索引只能靠字段级
 * {@link Index} 逐个标注。同一个类上两者同时出现时 Table 生效，Entity 被完全忽略，
 * 所以别指望靠 Entity 补 Table 没写的信息（反之也一样：Table 存在时 Entity 的 comment 不会进 DDL）。
 * </p>
 * <p>
 * 注意解析结果按 Class 缓存（EntityParser 内部 Map），同一个进程里只解析一次；
 * 运行期改注解属性不会生效，需要重启或调用 EntityParser 的缓存清理方法。
 * </p>
 *
 * @author wxd-gaming
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Entity {

    /** 实体名称，缺省时取类简单名作为表名；非空则原样作表名（不再做驼峰转下划线） */
    String name() default "";

    /** 实体注释，随建表/改表写进 DDL 的表注释，不参与查询与映射 */
    String comment() default "";
}
