package com.wxdgaming.batis.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 主键值生成策略注解，与 {@link Id} 配合使用
 * <pre>
 * &#64;Id
 * &#64;GeneratedValue(strategy = GenerationType.IDENTITY)
 * private long id;
 * </pre>
 *
 * @author wxd-gaming
 */
@Documented
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface GeneratedValue {

    /** 主键生成策略，默认 {@link GenerationType#NONE} */
    GenerationType strategy() default GenerationType.NONE;
}
