package com.wxdgaming.batis.converter;

/**
 * 标记类，表示未指定自定义类型转换器。
 * <p>
 * 作为 {@code @Column(converter=...)} 的默认值占位，
 * 类似于 JPA 中 {@code AttributeConverter} 对应的 NONE 标记。
 * </p>
 * <p>
 * 为什么需要一个空类而不是 {@code default = void}：注解属性只能收 Class，
 * 判断"用户有没有指定转换器"只能靠比较 Class 引用（{@code converter != NoneTypeConverter.class}）。
 * 因此这个类存在的意义就是"一个几乎不可能被用户误填的默认值"，它不承担任何转换职责。
 * </p>
 * <p>
 * 之所以做成 abstract + 私有构造：既不允许实例化（框架按无参构造反射创建转换器，
 * 一旦有人真填了它，得到的是"实例化失败"这种与真实问题无关的报错），
 * 也防止被继承后误当真实转换器使用。
 * </p>
 * <p>
 * 类型参数写成 {@code <Void, Void>}：与"永不转换"的语义一致，
 * 这样 {@code Class<? extends TypeConverter<?, ?>>} 的声明处能收下它而不必放宽成裸类型。
 * </p>
 *
 * @author wxd-gaming
 */
public abstract class NoneTypeConverter implements TypeConverter<Void, Void> {
    /** 私有构造：这个类只是注解默认值的占位符，永远不该被创建或使用 */
    private NoneTypeConverter() {
    }
}
