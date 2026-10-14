package com.wxdgaming.batis.sql;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.wxdgaming.batis.converter.TypeConverter;
import com.wxdgaming.batis.exception.TypeMismatchDataAccessException;
import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.StorageMode;
import com.wxdgaming.batis.util.ProtostuffUtil;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Blob;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bean 值处理抽象基类，提供与数据库无关的通用实现
 * <p>方言只需实现 {@link #convertToDbValue} 和 {@link #convertFromDbValue}，
 * 并复用本类提供的四组能力：自定义转换器调用（{@link #applyConverterToDb}/{@link #applyConverterFromDb}）、
 * StorageMode 的 JSON/Protostuff 编解码（{@link #toJsonString}/{@link #toBlobBytes}/
 * {@link #fromJsonText}/{@link #fromBlobBytes}）、基础类型派发（{@link #convertBasicValue}）
 * 与时间族归一（{@link #convertTemporal}）。</p>
 * <p>之所以把反射与编解码都抽成基类而不是 default 方法：这些逻辑与数据库完全无关，
 * 但错误信息、数值收窄、可访问性处理都很容易在各方言里写歪，收敛到一处才能保证
 * "同一个字段在 MySQL 和 pgsql 里报错方式一致"。新增方言时继承本类，
 * 不要再复制一遍反射与序列化代码——尤其是 JSON 侧"刻意不开 autoType"这条安全约定，
 * 复制一份就意味着未来的修复只落在其中一个方言上。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-31
 */
@Slf4j
public abstract class AbstractBeanValueHandler implements BeanValueHandler {

    /** JSON/Protostuff 序列化最大重试次数，应对多线程并发修改集合的并发问题 */
    private static final int MAX_SERIALIZE_RETRIES = 3;

    /**
     * 自定义 {@link TypeConverter} 是否存在——写读两侧都用它决定"是否完全接管该字段"。
     * <p>判定放在基类而不由方言各写一次 {@code col.getConverter() != null}：
     * 一旦某个方言漏判，同一实体在两种库里的转换优先级就不一样了。</p>
     */
    protected boolean hasConverter(ColumnInfo col) {
        return col.getConverter() != null;
    }

    /** 走自定义转换器的 Java 值 → JDBC 参数；失败时带字段名升级为 TypeMismatch，而不是抛出无上下文的反射异常 */
    protected Object applyConverterToDb(Object value, ColumnInfo col) {
        @SuppressWarnings({"rawtypes", "unchecked"})
        TypeConverter converter = col.getConverter();
        try {
            return converter.convertToDatabaseColumn(value);
        } catch (Exception e) {
            throw new TypeMismatchDataAccessException(
                    "自定义转换器 " + converter.getClass().getSimpleName()
                            + " convertToDatabaseColumn 失败，字段: " + col.getFieldName(), e);
        }
    }

    /** 走自定义转换器的 JDBC 值 → Java 对象 */
    protected Object applyConverterFromDb(Object value, ColumnInfo col) {
        @SuppressWarnings({"rawtypes", "unchecked"})
        TypeConverter converter = col.getConverter();
        try {
            return converter.convertToEntityAttribute(value);
        } catch (Exception e) {
            throw new TypeMismatchDataAccessException(
                    "自定义转换器 " + converter.getClass().getSimpleName()
                            + " convertToEntityAttribute 失败，字段: " + col.getFieldName(), e);
        }
    }

    /**
     * JSON 存储模式的写入编码：对象 → JSON 字符串。
     * <p>默认<b>不</b>写类型标记（见 {@link #fromJsonText} 的安全约定）；只有列显式声明
     * {@code @Column(polymorphic = true)} 时追加 {@code WriteClassName}，让"接口/父类字段存子类"
     * 能被读回。读取端不需要为此开 autoType——fastjson 在期望类型是接口或抽象类时，
     * 只放行指向其子类的类型标记，这条通道本身就是受限的。</p>
     * <p>失败按 {@link #MAX_SERIALIZE_RETRIES} 重试并 {@code Thread.yield}——针对集合被多线程并发修改
     * 导致的偶发序列化异常，让出 CPU 后重试通常即可成功，重试耗尽才升级为
     * {@code TypeMismatchDataAccessException}。</p>
     */
    protected String toJsonString(Object value, ColumnInfo col) {
        SerializerFeature[] features = col.isPolymorphic()
                ? new SerializerFeature[]{SerializerFeature.WriteClassName}
                : new SerializerFeature[0];
        for (int i = 0; ; i++) {
            try {
                return JSON.toJSONString(value, features);
            } catch (Exception e) {
                if (i >= MAX_SERIALIZE_RETRIES - 1) {
                    throw new TypeMismatchDataAccessException("JSON序列化字段" + col.getFieldName() + "失败，已重试" + MAX_SERIALIZE_RETRIES + "次", e);
                }
                log.error("JSON序列化字段{}失败，第{}次重试", col.getFieldName(), i + 1, e);
                Thread.yield();
            }
        }
    }

    /**
     * 二进制存储模式的写入编码：byte[] 原样落库，其余用 Protostuff 编码。
     * <p>byte[] 不再套一层 Protostuff：那样库里存的字节和调用方给的字节不一致，读回来还得手工剥壳。</p>
     */
    protected byte[] toBlobBytes(Object value, ColumnInfo col) {
        if (value instanceof byte[]) {
            return (byte[]) value;
        }
        for (int i = 0; ; i++) {
            try {
                return ProtostuffUtil.encode(value);
            } catch (Exception e) {
                if (i >= MAX_SERIALIZE_RETRIES - 1) {
                    throw new TypeMismatchDataAccessException("Protostuff序列化字段" + col.getFieldName() + "失败，已重试" + MAX_SERIALIZE_RETRIES + "次", e);
                }
                log.error("Protostuff序列化字段{}失败，第{}次重试", col.getFieldName(), i + 1, e);
                Thread.yield();
            }
        }
    }

    /**
     * JSON 存储模式的读取解码：按字段声明类型反解。
     * <p>刻意不开 Feature.SupportAutoType：开了之后，凡声明得比较宽的字段（Object、
     * {@code Map<String, Object>} 的 value）都会按 JSON 里的类型标记实例化任意非黑名单类，
     * 而"谁能写这一列"就不再是边界了——业务把用户输入塞进 Map 再落库，等于对外开放反序列化入口。</p>
     * <p>关闭后按字段声明的 genericType/javaType 反解，普通 POJO 与集合不受影响。
     * 多态场景（接口/抽象类字段存具体子类）由 {@code @Column(polymorphic = true)} 逐列开启写侧类型标记，
     * 读取走 fastjson 的受限通道：类型标记只有指向期望类型的子类时才被接受，指向不相干类照样拒绝。
     * 实测边界：polymorphic 只对声明为接口/抽象类/父类的列有确定效果；期望类型为 Object 时是否放行标记
     * 取决于 fastjson 内部的已注册类缓存，同一份文本读到过具体子类、也直接抛过反序列化失败，不能当契约用。</p>
     */
    protected Object fromJsonText(Object value, ColumnInfo col) {
        Type genericType = col.getGenericType();
        String jsonStr = value.toString();
        try {
            return JSON.parseObject(jsonStr, genericType != null ? genericType : col.getJavaType());
        } catch (Exception e) {
            throw new TypeMismatchDataAccessException("JSON 反序列化字段 " + col.getFieldName() + " 失败，value=" + jsonStr, e);
        }
    }

    /**
     * 二进制存储模式的读取解码：byte[] 字段直接交出原始字节，其余按声明类型 Protostuff 解码。
     * <p>{@code toBytes} 兼容两种驱动形态：MySQL 的 {@code java.sql.Blob} 需 getBytes 物化，
     * pgsql 的 bytea 已经是 byte[]。</p>
     */
    protected Object fromBlobBytes(Object value, ColumnInfo col) {
        byte[] bytes = toBytes(value, col);
        if (col.getJavaType() == byte[].class) {
            return bytes;
        }
        try {
            return ProtostuffUtil.decode(bytes, col.getJavaType());
        } catch (Exception e) {
            throw new TypeMismatchDataAccessException("Protostuff 解码字段 " + col.getFieldName() + " 失败，bytes length=" + bytes.length, e);
        }
    }

    /** 把二进制列读出的值取成字节数组：已是 byte[] 直接用，java.sql.Blob 需 getBytes 物化，其余类型不支持 */
    private byte[] toBytes(Object value, ColumnInfo col) {
        if (value instanceof byte[]) {
            return (byte[]) value;
        }
        if (value instanceof Blob blob) {
            try {
                return blob.getBytes(1, (int) blob.length());
            } catch (SQLException e) {
                throw new TypeMismatchDataAccessException("读取 Blob 字段 " + col.getFieldName() + " 失败", e);
            }
        }
        throw new TypeMismatchDataAccessException("字段 " + col.getFieldName() + " 不支持的二进制类型: " + value.getClass());
    }

    /** 该存储模式是否走 Protostuff 二进制档（BLOB / MEDIUMBLOB / LONGBLOB） */
    protected static boolean isBinaryMode(StorageMode mode) {
        return mode == StorageMode.BLOB || mode == StorageMode.MEDIUMBLOB || mode == StorageMode.LONGBLOB;
    }

    /**
     * 非 JSON/BLOB 的基础列值转换：目标类型与值已匹配则直接返回，否则按目标 javaType 逐档适配。
     * <p>顺序体现优先级：同类型短路 → 枚举按名反查 → Number 数值族收窄/放宽 → 布尔 → 时间族
     * （{@link #convertTemporal}）→ UUID/Character/String 兜底。无法识别时显式报错并带上列名与类型，
     * 不要把原始 JDBC 对象直接塞给 setter（setter 抛出的参数类型错误里没有列名，定位成本很高）。</p>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    protected Object convertBasicValue(Object value, ColumnInfo col) {
        Class<?> javaType = col.getJavaType();
        if (javaType.isInstance(value)) {
            return value;
        }
        if (javaType.isEnum() && value instanceof String) {
            return Enum.valueOf((Class<? extends Enum>) javaType, (String) value);
        }
        if (value instanceof Number num) {
            if (javaType == long.class || javaType == Long.class) return num.longValue();
            if (javaType == int.class || javaType == Integer.class) return num.intValue();
            if (javaType == short.class || javaType == Short.class) return num.shortValue();
            if (javaType == byte.class || javaType == Byte.class) return num.byteValue();
            if (javaType == double.class || javaType == Double.class) return num.doubleValue();
            if (javaType == float.class || javaType == Float.class) return num.floatValue();
            if (javaType == BigDecimal.class) return new BigDecimal(num.toString());
            if (javaType == boolean.class || javaType == Boolean.class) return num.intValue() != 0;
            if (javaType == BigInteger.class) return num instanceof BigDecimal bd ? bd.toBigInteger() : BigInteger.valueOf(num.longValue());
            if (javaType == AtomicInteger.class) return new AtomicInteger(num.intValue());
            if (javaType == AtomicLong.class) return new AtomicLong(num.longValue());
            if (javaType == Character.class || javaType == char.class) return (char) num.intValue();
        }
        if (value instanceof Boolean && (javaType == boolean.class || javaType == Boolean.class)) {
            return value;
        }
        Object temporal = convertTemporal(value, javaType);
        if (temporal != null) {
            return temporal;
        }
        if (javaType == UUID.class) {
            return UUID.fromString(value.toString());
        }
        if (javaType == Character.class || javaType == char.class) {
            if (value instanceof String s) {
                if (s.isEmpty()) {
                    // 空串没有"第一个字符"：直接说明是哪一列，而不是抛 StringIndexOutOfBoundsException
                    throw new TypeMismatchDataAccessException(
                            "列 " + col.getFieldName() + " 声明为 char/Character，但库端值为空字符串");
                }
                return s.charAt(0);
            }
            String text = value.toString();
            if (text.isEmpty()) {
                throw new TypeMismatchDataAccessException(
                        "列 " + col.getFieldName() + " 声明为 char/Character，但库端值转换后为空字符串");
            }
            return text.charAt(0);
        }
        if (javaType == String.class) {
            return value.toString();
        }
        throw new TypeMismatchDataAccessException(
                "列 " + col.getFieldName() + " 无法把库端值 " + value.getClass().getName()
                        + " 转换为 " + javaType.getName() + "，请为该列配置 @Column(converter=...)");
    }

    /**
     * 时间族互转：先把各种来源统一收敛为 Timestamp 再按目标 javaType 派发。
     * <p>Time 单独短路（TIME 列只关心时分秒，不能被当成日期）；其余先归一到 Timestamp，
     * 再转 Timestamp/java.util.Date/java.sql.Date/LocalDateTime/LocalDate/Instant/ZonedDateTime/OffsetDateTime。
     * 跨时区档位（Zoned/Offset）依赖系统时区或 UTC，是与 JDBC 驱动时区配置相关的既有约定。
     * 无法归一时返回 null，交回调用方走后续兜底分支。</p>
     */
    protected Object convertTemporal(Object value, Class<?> javaType) {
        Timestamp ts = null;
        if (value instanceof Timestamp) {
            ts = (Timestamp) value;
        } else if (value instanceof java.sql.Date) {
            ts = new Timestamp(((java.sql.Date) value).getTime());
        } else if (value instanceof java.util.Date) {
            ts = new Timestamp(((java.util.Date) value).getTime());
        } else if (value instanceof LocalDateTime) {
            ts = Timestamp.valueOf((LocalDateTime) value);
        } else if (value instanceof LocalDate) {
            ts = Timestamp.valueOf(((LocalDate) value).atStartOfDay());
        } else if (value instanceof OffsetDateTime) {
            ts = Timestamp.from(((OffsetDateTime) value).toInstant());
        } else if (value instanceof Instant) {
            ts = Timestamp.from((Instant) value);
        }

        if (value instanceof java.sql.Time || value instanceof LocalTime) {
            LocalTime lt = value instanceof java.sql.Time
                    ? ((java.sql.Time) value).toLocalTime() : (LocalTime) value;
            if (javaType == LocalTime.class) return lt;
            if (javaType == java.sql.Time.class) return java.sql.Time.valueOf(lt);
            return null;
        }

        if (ts == null) {
            return null;
        }
        if (javaType == Timestamp.class) return ts;
        if (javaType == java.util.Date.class) return new java.util.Date(ts.getTime());
        if (javaType == java.sql.Date.class) return new java.sql.Date(ts.getTime());
        if (javaType == LocalDateTime.class) return ts.toLocalDateTime();
        if (javaType == LocalDate.class) return ts.toLocalDateTime().toLocalDate();
        if (javaType == LocalTime.class) return ts.toLocalDateTime().toLocalTime();
        if (javaType == Instant.class) return ts.toInstant();
        if (javaType == ZonedDateTime.class) return ts.toInstant().atZone(java.time.ZoneId.systemDefault());
        if (javaType == OffsetDateTime.class) return ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
        return null;
    }

    /**

     * 反射读字段（走 getter）。
     * <p>缺 getter 时抛出的消息点名实体与字段：参数数组是按下标绑定的，
     * 少读一个字段会表现为"整条语句错位"，靠堆栈根本查不到是哪个字段没 getter。</p>
     */
    @Override
    public Object readFieldValue(Object bean, ColumnInfo col) {
        Method getter = col.getReadMethod();
        if (getter == null) {
            throw new RuntimeException("实体 " + bean.getClass().getSimpleName()
                    + " 缺少字段 " + col.getFieldName() + " 的 getter 方法，请为其添加 getter");
        }
        try {
            return getter.invoke(bean);
        } catch (Exception e) {
            throw new RuntimeException("通过 getter 读取字段 " + col.getFieldName() + " 失败", e);
        }
    }

    /**
     * 反射写字段（走 setter）。
     * <p>异常消息额外带上值的运行时类型：行映射失败九成是转换环节给错了载体
     * （比如把 Blob 直接塞给 String 字段），带上类型才能一眼看出是转换问题还是实体问题。</p>
     */
    @Override
    public void writeFieldValue(Object bean, ColumnInfo col, Object value) {
        Method setter = col.getWriteMethod();
        if (setter == null) {
            throw new RuntimeException("实体 " + bean.getClass().getSimpleName()
                    + " 缺少字段 " + col.getFieldName() + " 的 setter 方法，请为其添加 setter");
        }
        try {
            setter.invoke(bean, value);
        } catch (Exception e) {
            throw new RuntimeException("通过 setter 写回字段 " + col.getFieldName()
                    + " 失败，value=" + value + (value == null ? "" : ", valueType=" + value.getClass().getName()), e);
        }
    }

    /**
     * 生成键回写：按 setter 参数类型把 Number 收窄成实体字段能接受的形态。
     * <p>驱动返回的生成键类型由数据库决定（多为 Long，部分驱动给 BigInteger），
     * 而实体字段常声明为 int/long 甚至原子类型；直接 {@code invoke} 会因参数类型不匹配失败。
     * 收窄在这里统一做，方言只负责交出 Number。</p>
     * <p>遇到未覆盖的参数类型时原样交出，让反射自然报错，而不是悄悄丢掉主键值。</p>
     */
    @Override
    public void writeGeneratedKey(Object bean, ColumnInfo col, Number genId) {
        if (genId == null) return;
        Method setter = col.getWriteMethod();
        if (setter == null) {
            throw new RuntimeException("实体 " + bean.getClass().getSimpleName()
                    + " 缺少字段 " + col.getFieldName() + " 的 setter 方法，请为其添加 setter");
        }
        Class<?> paramType = setter.getParameterTypes()[0];
        Object arg;
        if (paramType == long.class || paramType == Long.class) arg = genId.longValue();
        else if (paramType == int.class || paramType == Integer.class) {
            arg = (int) requireInRange(genId, col, Integer.MIN_VALUE, Integer.MAX_VALUE, "int");
        } else if (paramType == short.class || paramType == Short.class) {
            arg = (short) requireInRange(genId, col, Short.MIN_VALUE, Short.MAX_VALUE, "short");
        } else if (paramType == byte.class || paramType == Byte.class) {
            arg = (byte) requireInRange(genId, col, Byte.MIN_VALUE, Byte.MAX_VALUE, "byte");
        } else if (paramType == double.class || paramType == Double.class) arg = genId.doubleValue();
        else if (paramType == float.class || paramType == Float.class) arg = genId.floatValue();
        else if (paramType == BigDecimal.class) arg = BigDecimal.valueOf(genId.longValue());
        else if (paramType == BigInteger.class) arg = BigInteger.valueOf(genId.longValue());
        else if (paramType == AtomicInteger.class) {
            arg = new AtomicInteger((int) requireInRange(genId, col, Integer.MIN_VALUE, Integer.MAX_VALUE, "int"));
        } else if (paramType == AtomicLong.class) arg = new AtomicLong(genId.longValue());
        else arg = genId;
        try {
            setter.invoke(bean, arg);
        } catch (Exception e) {
            throw new RuntimeException("通过 setter 写回字段 " + col.getFieldName() + " 失败", e);
        }
    }

    /**
     * 收窄前做范围校验：生成键超出字段类型范围时抛错，而不是静默溢出成负数。
     * <p>
     * {@code intValue()} 在自增 ID 超过 int 上限时会回绕成负数，调用方拿这个负 ID 继续关联，
     * 表现为"查不到自己刚插入的行"这类难查的问题；{@code byte/short} 截断更隐蔽。</p>
     */
    private static long requireInRange(Number genId, ColumnInfo col, long min, long max, String typeName) {
        long value = genId.longValue();
        if (value < min || value > max) {
            throw new IllegalStateException("字段 " + col.getFieldName() + " 声明为 " + typeName
                    + "，但数据库生成的键值 " + value + " 超出其取值范围，请把该字段改为 long/Long");
        }
        return value;
    }
}
