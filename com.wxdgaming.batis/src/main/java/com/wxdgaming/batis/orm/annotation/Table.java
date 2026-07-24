package com.wxdgaming.batis.orm.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 表注解，标注在实体类上，定义表名、表注释、复合索引等元信息
 *
 * @author wxd-gaming
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Table {

    /** 表名，不填则默认使用类简单名（驼峰转下划线） */
    String name() default "";

    /** 表注释 */
    String comment() default "";

    /** 复合索引列表 */
    Index[] indexes() default {};
}
