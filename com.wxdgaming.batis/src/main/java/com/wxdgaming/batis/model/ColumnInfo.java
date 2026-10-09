package com.wxdgaming.batis.model;

import com.wxdgaming.batis.annotation.GenerationType;
import com.wxdgaming.batis.converter.TypeConverter;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;

/**
 * 运行时列元数据
 *
 * @author wxd-gaming
 */
@Getter
@Setter
@Accessors(chain = true)
public class ColumnInfo {

    /** 列名 */
    private String columnName;
    /**
     * 物理列类型字符串（方言字面量，如 VARCHAR(255)）。
     * 仅两处来源：{@code @Column(type=...)} 显式指定、数据库元数据回填。
     * 由 Java 类型自动推断的列为 null，逻辑类型见 {@link #sqlType}。
     */
    private String type;
    /** 逻辑列类型，实体解析自动推断时写入；显式 @Column(type=...) 时为 null */
    private SqlType sqlType;
    /** 列长度 */
    private int length;
    /** 小数精度 */
    private int scale;
    /** 列注释 */
    private String comment;
    /** 默认值（SQL 字面量） */
    private String defaultValue;
    /** 是否允许为空 */
    private boolean nullable;
    /** 是否唯一 */
    private boolean unique;
    /** 是否主键 */
    private boolean primaryKey;
    /** 主键生成策略 */
    private GenerationType generationType = GenerationType.NONE;
    /** 存储模式 */
    private StorageMode storageMode = StorageMode.AUTO;
    /** 对应的 Java 字段名 */
    private String fieldName;
    /** 对应的 Java 类型 */
    private Class<?> javaType;
    /** 对应的 Java 字段引用，解析时缓存 */
    private Field field;
    /** 字段的泛型类型（如 List&lt;Xxx&gt;），用于 JSON 反序列化时还原泛型 */
    private Type genericType;
    /** 读取方法（getter），解析时缓存 */
    private Method readMethod;
    /** 写入方法（setter），解析时缓存 */
    private Method writeMethod;
    /** 自定义类型转换器，从 @Column(converter=...) 解析而来，可能为 null */
    private TypeConverter<?, ?> converter;

    @Override
    public String toString() {
        return "ColumnInfo{" +
                "columnName='" + columnName + '\'' +
                ", type='" + type + '\'' +
                ", sqlType=" + sqlType +
                ", length=" + length +
                ", comment='" + comment + '\'' +
                ", primaryKey=" + primaryKey +
                ", unique=" + unique +
                ", storageMode=" + storageMode +
                '}';
    }
}
