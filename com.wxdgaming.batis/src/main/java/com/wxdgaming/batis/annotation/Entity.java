package com.wxdgaming.batis.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 实体标记注解
 *
 * @author wxd-gaming
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Entity {

    /** 实体名称，缺省时取类简单名作为表名 */
    String name() default "";

    /** 实体注释 */
    String comment() default "";
}
