package com.wxdgaming.batis.sql;

import com.wxdgaming.batis.model.ColumnInfo;

/**
 * Bean ↔ DB 双向值转换处理器接口（方言 SPI）
 * <p>每个数据库方言提供对应的实现，隔离类型转换差异（如 JSONB/TIMESTAMPTZ/UUID 等）。</p>
 * <p>五个方法分成两组，这是本接口的关键结构：</p>
 * <ul>
 *   <li><b>方言相关</b>：{@link #convertToDbValue} 与 {@link #convertFromDbValue}——
 *       同一个 Java 字段在不同库里该存成什么（JSON 文本还是 JSONB、
 *       时间要不要带时区、二进制走 byte[] 还是 Blob），只有这两个方法需要方言操心；</li>
 *   <li><b>方言无关</b>：后三个纯反射方法，形态与数据库毫无关系，
 *       由 {@link AbstractBeanValueHandler} 统一实现，方言继承它即可，
 *       避免每个方言各写一遍 getter/setter 反射并各出各的错。</li>
 * </ul>
 * <p>转换发生在 SQL 边界上：写侧的产物会被直接 {@code setObject} 绑定，
 * 读侧的入参是驱动 {@code getObject} 的结果。把这条线画清楚，
 * 类型差异才不会渗进 SQL 文本构建（{@code BeanTo*Handler}）和 core 的实体模型。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-31
 */
public interface BeanValueHandler {

    /**
     * Bean → DB：把 Java 值转成可以直接绑定到 PreparedStatement 的值
     * <p>实现方必须保证：</p>
     * <ul>
     *   <li>null 原样返回 null——未赋值的字段要能落成 SQL 的 NULL，这是写侧唯一的空值约定；</li>
     *   <li>产物必须是驱动可绑定的类型（字符串、数值、java.sql 时间类型、byte[] 等），
     *       不能把 LocalDateTime 之类的对象直接交给只认 {@code java.sql.*} 的驱动；</li>
     *   <li>字段上配置了自定义转换器时，转换器优先级最高，直接采用它的产物；</li>
     *   <li>转换失败要抛出带字段名与原始值的类型不匹配异常，而不是返回一个"看着对"的值。</li>
     * </ul>
     *
     * @param javaValue 实体字段值，可为 null
     * @param col       列元数据，提供存储形态、Java 类型与自定义转换器
     * @return 可绑定的数据库值
     */
    Object convertToDbValue(Object javaValue, ColumnInfo col);

    /**
     * DB → Bean：把 ResultSet 读出的值转成实体字段类型的对象
     * <p>与写侧不同，实现方这里可以假定 value 非 null：行映射会先跳过 NULL 列，
     * 让字段保持 Java 默认值，所以不需要为 null 特判（自定义转换器若要求处理 null，
     * 请在方言实现里自行确认）。</p>
     * <p>入参的实际类型由驱动决定，同一列可能回 byte[]、Blob 或 String，
     * 因此实现要对"同一种数据库形态的多种 Java 载体"保持宽容；
     * 无法转换时抛类型不匹配异常并带上字段名，不要静默返回原值。</p>
     *
     * @param dbValue {@code ResultSet.getObject} 的结果，由行映射保证非 null
     * @param col     列元数据，提供目标 Java 类型、泛型信息与自定义转换器
     * @return 可写入实体字段的值
     */
    Object convertFromDbValue(Object dbValue, ColumnInfo col);

    /**
     * 通过 getter 读取 bean 字段值（纯反射，所有方言相同）
     * <p>缺 getter 或调用失败时抛出带实体名与字段名的异常——配置错误应当在第一次写入时
     * 就指到具体字段，而不是让参数数组里凭空少一个值。</p>
     */
    Object readFieldValue(Object bean, ColumnInfo col);

    /**
     * 通过 setter 写入 bean 字段值（纯反射，所有方言相同）
     * <p>失败信息里带字段名、值和值的运行时类型：行映射错最常见的原因是类型转换没做到位，
     * 光看"setter 调用失败"定位不到是哪个环节给错了类型。</p>
     */
    void writeFieldValue(Object bean, ColumnInfo col, Object value);

    /**
     * 将自增主键 Number 回写到 bean（纯反射，所有方言相同）
     * <p>驱动给出的生成键类型（Long 或 BigInteger）与实体字段类型（常为 int/long）往往不一致，
     * 这里按 setter 参数形态做一次数值收窄，让方言不必各自处理。</p>
     *
     * @param bean  目标实体
     * @param col   主键列元数据
     * @param genId 数据库生成的主键值，为 null 时静默跳过
     */
    void writeGeneratedKey(Object bean, ColumnInfo col, Number genId);
}
