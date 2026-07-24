package com.wxdgaming.batis.orm.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 字段列注解，标注在实体字段上，定义列名、类型、长度、注释、默认值等元信息
 * <p>
 * 当 {@link #type()} 未指定时，解析器会根据 Java 类型推测对应的数据库类型（参考 MySQL 方言）。
 *
 * @author wxd-gaming
 */
@Documented
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Column {

    /**
     * 列名，不填时默认使用字段名（驼峰转下划线）
     */
    String name() default "";

    /**
     * 数据库列类型，如 VARCHAR, BIGINT, INT, TEXT 等。
     * 不填则由 {@code EntityParser} 按 Java 类型自动推断。
     */
    String type() default "";

    /** 列长度（仅对字符串/可变长类型有效），默认 255 */
    int length() default 255;

    /**
     * 小数精度（仅对 DECIMAL / NUMERIC 类型有效），默认 0 表示不设置
     */
    int scale() default 0;

    /** 列注释 */
    String comment() default "";

    /** 列默认值（SQL 字面量，如 {@code '0'}、{@code 0}、{@code CURRENT_TIMESTAMP}） */
    String defaultValue() default "";

    /** 是否允许为空，默认 true */
    boolean nullable() default true;

    /** 是否唯一约束，默认 false */
    boolean unique() default false;
}
