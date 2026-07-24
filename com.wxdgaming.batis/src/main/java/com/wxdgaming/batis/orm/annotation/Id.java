package com.wxdgaming.batis.orm.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 主键标记注解，标注在字段上标识该字段是主键
 * <p>
 * 支持联合主键：可在多个字段上标注 {@code @Id}
 *
 * @author wxd-gaming
 */
@Documented
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Id {
}
