package com.wxdgaming.batis.converter;

/**
 * 自定义类型转换器，类似 JPA 的 {@code AttributeConverter<X, Y>}
 * <p>
 * 用于在实体类字段 Java 类型与数据库存储类型之间进行自定义双向转换。
 * 实现类必须提供无参构造函数，实例会被框架缓存并复用，因此必须是线程安全的。
 *
 * <pre>
 * // 示例：自定义枚举转数据库 TINYINT
 * public class GameStatusConverter implements TypeConverter&lt;GameStatus, Integer&gt; {
 *     &#064;Override
 *     public Integer convertToDatabaseColumn(GameStatus status) {
 *         return status == null ? null : status.getCode();
 *     }
 *     &#064;Override
 *     public GameStatus convertToEntityAttribute(Integer code) {
 *         return code == null ? null : GameStatus.fromCode(code);
 *     }
 * }
 *
 * // 使用
 * &#064;Column(type = "TINYINT", converter = GameStatusConverter.class)
 * private GameStatus status;
 * </pre>
 *
 * @param <X> 实体类中的 Java 属性类型
 * @param <Y> 存入数据库的 JDBC 兼容类型（如 String、Integer、Long、byte[] 等）
 * @author wxd-gaming
 */
public interface TypeConverter<X, Y> {

    /**
     * 实体属性 → 数据库值（Bean → DB）
     *
     * @param attribute 实体字段值，运行时不会为 null（框架已做 null 检查）
     * @return 数据库兼容的值
     */
    Y convertToDatabaseColumn(X attribute);

    /**
     * 数据库值 → 实体属性（DB → Bean）
     *
     * @param dbData 从数据库读取的原始值，可能为 null
     * @return 实体字段值
     */
    X convertToEntityAttribute(Y dbData);
}
