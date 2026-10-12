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
 * 运行时列元数据：EntityParser 对"一个持久化字段"的完整描述。
 * <p>关键区分——{@link #type}（物理字面量，方言相关，可能为 null）与 {@link #sqlType}（逻辑类型）互斥：
 * 自动推断的列只填 sqlType，显式书写物理类型的列只填 type；DDL 渲染时由方言层据此选择来源。
 * {@link #field} 与 {@link #readMethod}/{@link #writeMethod} 在解析期一次性缓存，
 * 运行期读写实体走它们而非重复反射。</p>
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
    /**
     * 库端列的字符集（仅由数据库元数据回填，实体解析不产出）
     * <p>用于发现"库端 latin1 / 实体期望 utf8mb4"这类漂移：两边的 COLUMN_TYPE 完全相同，
     * 只比类型永远发现不了，而写入非 ASCII 字符时会被截断或直接报 1366。</p>
     */
    private String characterSet;
    /** 库端列的排序规则（仅由数据库元数据回填） */
    private String collation;
    /**
     * 库端列的 EXTRA 信息（如 {@code auto_increment}、{@code DEFAULT_GENERATED}）
     * <p>仅由数据库元数据回填；结构比对用它判断自增属性是否与实体声明一致。</p>
     */
    private String extra;

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
