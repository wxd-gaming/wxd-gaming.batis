package com.wxdgaming.batis.annotation;

import com.wxdgaming.batis.model.StorageMode;

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

    /** 列名，不填时默认使用字段名（驼峰转下划线） */
    String name() default "";

    /**
     * 数据库列类型，如 VARCHAR, BIGINT, INT, TEXT 等。
     * 不填则由 {@code EntityParser} 按 Java 类型自动推断。
     */
    String type() default "";

    /** 列长度（仅对字符串/可变长类型有效），默认 255 */
    int length() default 255;

    /** 小数精度（仅对 DECIMAL / NUMERIC 类型有效），默认 0 表示不设置 */
    int scale() default 0;

    /** 列注释 */
    String comment() default "";

    /** 列默认值（SQL 字面量，如 {@code '0'}、{@code 0}、{@code CURRENT_TIMESTAMP}） */
    String defaultValue() default "";

    /** 是否允许为空，默认 true */
    boolean nullable() default true;

    /** 是否唯一约束，默认 false */
    boolean unique() default false;

    /**
     * 存储模式
     * <p>
     * <ul>
     *   <li>{@link StorageMode#AUTO} - 自动推断（默认）：基础类型直接映射，复杂类型（List/Map/Set/自定义POJO）自动升级为 JSON</li>
     *   <li>{@link StorageMode#JSON} - 强制以 JSON 字符串存储，列类型推断为 LONGTEXT</li>
     *   <li>{@link StorageMode#BLOB} / {@link StorageMode#MEDIUMBLOB} / {@link StorageMode#LONGBLOB} -
     *       强制以二进制存储，使用 ProtostuffUtil 序列化/反序列化，列类型自动映射为对应数据库 BLOB 类型</li>
     * </ul>
     */
    StorageMode storageMode() default StorageMode.AUTO;
}
