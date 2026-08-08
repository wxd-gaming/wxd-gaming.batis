package com.wxdgaming.batis.converter;

/**
 * 标记类，表示未指定自定义类型转换器。
 * <p>
 * 作为 {@code @Column(converter=...)} 的默认值占位，
 * 类似于 JPA 中 {@code AttributeConverter} 对应的 NONE 标记。
 * <p>
 * 此类不应被用户直接使用或实例化。
 *
 * @author wxd-gaming
 */
public abstract class NoneTypeConverter implements TypeConverter<Void, Void> {
    private NoneTypeConverter() {
    }
}
