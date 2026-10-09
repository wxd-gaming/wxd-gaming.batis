package com.wxdgaming.batis.annotation;

import com.wxdgaming.batis.converter.NoneTypeConverter;
import com.wxdgaming.batis.converter.TypeConverter;
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
     * 数据库物理列类型字面量（方言相关，如 MySQL 的 VARCHAR(64)、pgsql 的 varchar(64)）。
     * 一般不需要填写：不填时框架按 Java 类型推断逻辑类型（SqlType），再由当前方言渲染物理类型；
     * 填写后则跳过推断，原样用于 DDL。
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

    /**
     * 自定义类型转换器类，需实现 {@link com.wxdgaming.batis.converter.TypeConverter}
     * <p>
     * 指定后，该字段的 Bean↔DB 值转换将完全由转换器接管，
     * 不再走框架默认的类型映射逻辑（包括 {@link #storageMode()} 设置也会被忽略）。
     * <p>
     * 转换器类必须有无参构造函数，实例会被框架缓存并复用（须线程安全）。
     */
    Class<? extends TypeConverter<?, ?>> converter() default NoneTypeConverter.class;
}
