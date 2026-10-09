package com.wxdgaming.batis.converter;

/**
 * 自定义类型转换器，类似 JPA 的 {@code AttributeConverter<X, Y>}
 * <p>
 * 用于在实体类字段 Java 类型与数据库存储类型之间进行自定义双向转换。
 * 实现类必须提供无参构造函数，实例会被框架缓存并复用，因此必须是线程安全的。
 * </p>
 * <p>
 * <b>无参构造 + 线程安全这两条不是建议</b>：转换器实例按列缓存在 ColumnInfo 里长期复用，
 * 一次反射 newInstance 之后就不再重建。把请求上下文塞进转换器字段（比如当前用户、当前时间）
 * 会在多线程下互相串值，而且是偶发错误，比编译不过更难查。
 * </p>
 * <p>
 * 转换器是"完全接管"：一旦声明，框架不再对这一列做 JSON/BLOB 等 StorageMode 序列化，
 * 也不会替你把 null 转成默认值，列的物理类型请以你返回的值为准。
 * </p>
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
     * <p>
     * 两侧 null 语义不对称。<b>写侧</b>：调用方（如 {@code MysqlBeanValueHandler#convertToDbValue}）
     * 一进门就 {@code value == null → 直接写 NULL 列}，转换器根本不会被调用，
     * 所以这里实际不会收到 null——但别依赖这一点，实现仍应容忍 null 并返回 null，
     * 免得将来加了"null 转默认值"的开关时行为失控。
     * </p>
     * <p>返回值的类型要和列的物理类型对得上，否则到 JDBC 绑定或库端校验时才报错，
     * 栈信息会远离真正的出错字段。</p>
     *
     * @param attribute 实体字段值（写侧已判空，实际非 null）
     * @return 数据库兼容的值
     */
    Y convertToDatabaseColumn(X attribute);

    /**
     * 数据库值 → 实体属性（DB → Bean）
     * <p>
     * <b>读侧收不到 null</b>：行映射在拿到 {@code rs.getObject(i) == null} 时就跳过这一列了
     * （{@code MysqlDbToBeanHandler#toBean}），所以"库里是 NULL"对应的结果是<b>字段保持 Java 默认值</b>
     * （对象字段为 null、{@code int} 字段为 0、{@code List} 字段为 null），而不是转换器给的兜底值。
     * 这一点很容易被误解：想让 NULL 列读成空集合，得在实体字段初始化上解决，转换器帮不上。
     * </p>
     * <p>转换器内部抛异常会让整条查询失败（行映射不吞单个字段的错），
     * 遇到历史脏数据时请在转换器里返回兜底值而不是抛出。</p>
     *
     * @param dbData 从数据库读取的原始值（当前实现保证非 null）
     * @return 实体字段值
     */
    X convertToEntityAttribute(Y dbData);
}
