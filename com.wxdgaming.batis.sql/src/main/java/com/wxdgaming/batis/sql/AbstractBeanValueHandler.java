package com.wxdgaming.batis.sql;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONReader;
import com.alibaba.fastjson2.JSONWriter;
import com.wxdgaming.batis.converter.TypeConverter;
import com.wxdgaming.batis.exception.TypeMismatchDataAccessException;
import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.StorageMode;
import com.wxdgaming.batis.util.ProtostuffUtil;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
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
 * 不要再复制一遍反射与序列化代码——尤其是 JSON 侧"类型标记必须过继承树白名单"这条安全约定，
 * 复制一份就意味着未来的修复只落在其中一个方言上。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-31
 */
@Slf4j
public abstract class AbstractBeanValueHandler implements BeanValueHandler {

    /** JSON/Protostuff 序列化最大重试次数，应对多线程并发修改集合的并发问题 */
    private static final int MAX_SERIALIZE_RETRIES = 3;

    /** 普通 JSON 列的写特性：空数组即 fastjson2 默认（不写类名、不做引用探测） */
    private static final JSONWriter.Feature[] NO_WRITE_FEATURES = new JSONWriter.Feature[0];

    /**
     * 多态列的写特性：写类型标记，但抑制 JDK 容器、数字与 Set 的类名。
     * <p>这三类抑制不是美化输出，而是保住列的形态：开了 {@code WriteClassName} 后 fastjson2 会把
     * {@code Long} 写成 {@code 9L}、把非 ArrayList 的 Set 写成 {@code Set[...]}——都不是标准 JSON，
     * PG 的原生 json/jsonb 列会直接报非法输入，别的语言也读不懂。
     * 抑制后容器实现类（TreeMap 之类）仍可能留下类名，那是合法 JSON 的伪键，读取端会统一换成
     * {@code LinkedHashMap}/{@code ArrayList}；Number 的具体子类与容器实现本来就不在多态保证范围内，
     * 要精确保住请用 {@code @Column(converter = ...)}。</p>
     */
    private static final JSONWriter.Feature[] MARK_WITH_ROOT = {
            JSONWriter.Feature.WriteClassName,
            JSONWriter.Feature.NotWriteHashMapArrayListClassName,
            JSONWriter.Feature.NotWriteSetClassName,
            JSONWriter.Feature.NotWriteNumberClassName,
    };

    /** 根值是容器/数组/final 类型时的写特性：根上的类名要么无意义，要么会破坏列形态 */
    private static final JSONWriter.Feature[] MARK_WITHOUT_ROOT = {
            JSONWriter.Feature.WriteClassName,
            JSONWriter.Feature.NotWriteRootClassName,
            JSONWriter.Feature.NotWriteHashMapArrayListClassName,
            JSONWriter.Feature.NotWriteSetClassName,
            JSONWriter.Feature.NotWriteNumberClassName,
    };

    /**
     * 多态列的编解码配置：写特性与读白名单必须同源。
     * <p>分开算会出现"库里写了标记、读取端不认"的半截状态，表现是数据写得进去但取回来类型丢了，
     * 这种问题只在读存量行时暴露。</p>
     */
    private record PolymorphicCodec(JSONWriter.Feature[] writeFeatures,
                                    JSONReader.AutoTypeBeforeHandler readFilter) {

        /** 声明类型给不出可用上界时的配置：两侧都退化成普通列，绝不写出无人认领的类型标记 */
        static final PolymorphicCodec UNBOUNDED =
                new PolymorphicCodec(NO_WRITE_FEATURES, null);
    }

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
     * <p>默认<b>不</b>写类型标记；只有列显式声明 {@code @Column(polymorphic = true)} 且声明类型给得出
     * 继承树上界时才追加 {@code WriteClassName}（见 {@link #polymorphicCodec}）。</p>
     * <p>失败按 {@link #MAX_SERIALIZE_RETRIES} 重试并 {@code Thread.yield}——针对集合被多线程并发修改
     * 导致的偶发序列化异常，让出 CPU 后重试通常即可成功，重试耗尽才升级为
     * {@code TypeMismatchDataAccessException}。</p>
     */
    protected String toJsonString(Object value, ColumnInfo col) {
        JSONWriter.Feature[] features = col.isPolymorphic()
                ? polymorphicCodec(col).writeFeatures()
                : NO_WRITE_FEATURES;
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
     * <p>只有 {@code @Column(polymorphic = true)} 且声明类型能算出上界的列才开启 autoType，
     * 且必须配 {@link SubtypeFilter}：上界由字段声明类型自己推出，标记是这些上界的子类才放行，
     * JDK 容器实现类归一到 LinkedHashMap/ArrayList，其余一律抛异常，本方法再升级为带列名的
     * {@link TypeMismatchDataAccessException}。开 autoType 是因为嵌套容器只有在这个特性下才带
     * {@code @type} 写回（否则对象图里的 List/Map 子类读不回来），而白名单会拒绝继承树外的类，
     * 所以"谁能写这一列"仍然是安全边界。</p>
     * <p>非多态列，以及声明太宽、算不出上界的列（Object、{@code List<Object>}、
     * {@code Map<String, Object>}）都走默认解析：不注入任何 autoType 特性，
     * 这类字段开了就等于让 JSON 里的类型标记决定实例化哪个类，业务把用户输入塞进 Map 再落库，
     * 就变成对外开放的反序列化入口。写入端同源，这类列也不写类型标记。</p>
     */
    protected Object fromJsonText(Object value, ColumnInfo col) {
        Type type = declaredType(col);
        String jsonStr = value.toString();
        try {
            if (!col.isPolymorphic()) {
                return JSON.parseObject(jsonStr, type);
            }
            JSONReader.AutoTypeBeforeHandler filter = polymorphicCodec(col).readFilter();
            return filter == null
                    ? JSON.parseObject(jsonStr, type)
                    : JSON.parseObject(jsonStr, type, filter, JSONReader.Feature.SupportAutoType);
        } catch (Exception e) {
            throw new TypeMismatchDataAccessException("JSON 反序列化字段 " + col.getFieldName() + " 失败，value=" + jsonStr, e);
        }
    }

    /** 字段声明类型：优先泛型签名，裸类型字段退回 javaType */
    private static Type declaredType(ColumnInfo col) {
        return col.getGenericType() != null ? col.getGenericType() : col.getJavaType();
    }

    /**
     * 取多态列的编解码配置：一次算出写特性与读白名单，保证两侧同源。
     * <p>不做缓存：上界只是沿着声明类型走几层 instanceof，比随后的 JSON 编解码便宜得多，
     * 而把 ColumnInfo 当 map 键要依赖它的 equals/hashCode（模型类多为 Lombok @Data，
     * 按字段全量比较），两个语义相同的列会被折叠，风险大于收益。</p>
     */
    private static PolymorphicCodec polymorphicCodec(ColumnInfo col) {
        Type declared = declaredType(col);
        Set<Class<?>> bounds = new LinkedHashSet<>();
        collectBounds(declared, bounds);
        if (bounds.isEmpty()) {
            return PolymorphicCodec.UNBOUNDED;
        }
        // 根位置不可能装子类时抑制根类名：容器/数组根会写成 {"@type":"...","@value":[...]} 这种破坏列形态的包装
        boolean suppressRoot = !canCarrySubtypeAtRoot(declared);
        return new PolymorphicCodec(suppressRoot ? MARK_WITHOUT_ROOT : MARK_WITH_ROOT,
                new SubtypeFilter(bounds));
    }

    /**
     * 声明类型的根位置是否可能装着子类：只有接口和非 final 的普通类可能。
     * <p>容器和数组先判——{@code List}/{@code Map} 本身也是接口，但根上的容器类名不是子类信息，
     * 写成 {@code {"@type":"java.util.LinkedHashMap","value":...}} 反而让 fastjson2 读不回来。
     * final 类型（String、包装类、枚举、record）没有子类可还原，写了只剩伪键。</p>
     */
    private static boolean canCarrySubtypeAtRoot(Type declared) {
        if (declared instanceof GenericArrayType) {
            return false;
        }
        Class<?> raw = erase(declared);
        if (raw == null) {
            return false;
        }
        if (raw.isArray() || raw.isPrimitive() || raw.isEnum() || raw.isRecord()
                || Collection.class.isAssignableFrom(raw) || Map.class.isAssignableFrom(raw)) {
            return false;
        }
        return raw.isInterface() || !Modifier.isFinal(raw.getModifiers());
    }

    /** 取声明类型的裸类型：拿不到（TypeVariable 等）返回 null */
    private static Class<?> erase(Type type) {
        if (type instanceof Class<?> c) {
            return c;
        }
        if (type instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> raw) {
            return raw;
        }
        return null;
    }

    /**
     * 由字段声明类型算出多态允许实例化的上界集合。
     * <p>{@code Object} 一律排除——它 assignable-from 一切，收下就等于开了全局 autoType。
     * 容器（Collection/Map）的裸类型也不作为上界：写侧已经抑制了容器类名，列里不会出现
     * {@code java.util.ArrayList} 这种标记；把 {@code List} 当成上界只会让"声明是 List&lt;Object&gt;
     * 或 Map&lt;String,Object&gt;"这类无界字段被误判成可用（上界非空即启用多态）。</p>
     * <p>结果是空集时，这一列的多态不生效：写不带标记、读按声明类型，与 {@code polymorphic = false} 等价。
     * {@code List<Object>}、{@code Map<String,Object>} 正好落在这一档——别指望框架替你猜类型。</p>
     */
    private static void collectBounds(Type type, Set<Class<?>> out) {
        if (type instanceof Class<?> c) {
            if (c == Object.class) {
                return;
            }
            out.add(c);
            if (c.isArray()) {
                // 数组协变：Shape[] 能接受 Circle[]，同时把元素类型也收进来供嵌套解析用
                collectBounds(c.getComponentType(), out);
            }
        } else if (type instanceof ParameterizedType pt) {
            if (pt.getRawType() instanceof Class<?> raw
                    && !Collection.class.isAssignableFrom(raw) && !Map.class.isAssignableFrom(raw)) {
                out.add(raw);
            }
            for (Type arg : pt.getActualTypeArguments()) {
                collectBounds(arg, out);
            }
        } else if (type instanceof GenericArrayType array) {
            collectBounds(array.getGenericComponentType(), out);
        } else if (type instanceof WildcardType wildcard) {
            for (Type bound : wildcard.getUpperBounds()) {
                collectBounds(bound, out);
            }
        }
        // TypeVariable（实体里几乎不出现）没有可执行的上界信息，按"不放行"处理
    }

    /**
     * 多态读取白名单。
     * <p>三条规则，缺一不可：</p>
     * <ol>
     *   <li>业务类标记必须落在字段声明类型的继承树里（{@code bounds}），或者是该位置声明类型（{@code expectClass}，
     *       由 fastjson2 从实体字段声明带出来）的子类——两者都来自编译期声明，不来自库里的字符串；</li>
     *   <li>JDK 容器标记一律换成 {@code LinkedHashMap}/{@code ArrayList}：容器实现类本来就不在保证范围内，
     *       换成标准容器既能让 {@code TreeMap}/{@code Map.of} 这类嵌套容器读回来，也不把库里点名的实现类当真；
     *       出现在业务类型位置上的容器标记（比如 {@code List<Shape>} 的元素写着 PriorityQueue）照样拒绝。</li>
     *   <li>其余一律抛错。这里不能"忽略标记继续按声明类型读"：fastjson2 的 {@code AutoTypeBeforeHandler}
     *       交出 null 后仍会走它自己的 autoType 判定，配 {@code SupportAutoType} 就等于放行；
     *       主动抛是唯一能把边界焊死的写法。</li>
     * </ol>
     */
    private record SubtypeFilter(Class<?>[] bounds) implements JSONReader.AutoTypeBeforeHandler {

        SubtypeFilter(Collection<Class<?>> bounds) {
            this(bounds.toArray(new Class<?>[0]));
        }

        @Override
        public Class<?> apply(String typeName, Class<?> expectClass, long features) {
            Class<?> clazz = load(typeName);
            if (clazz == null) {
                throw new JSONException("多态列的类型标记指向加载不了的类: " + typeName);
            }
            if (clazz == Object.class) {
                throw new JSONException("多态列不接受 java.lang.Object 标记: " + typeName);
            }
            // 只置换 JDK 自带容器：业务类可能自己实现 Collection，那是声明类型继承树里的合法子类，不能被换掉
            if (clazz.getName().startsWith("java.") && isContainer(clazz)) {
                Class<?> standard = Map.class.isAssignableFrom(clazz) ? LinkedHashMap.class : ArrayList.class;
                if (expectClass == null || expectClass == Object.class || expectClass.isAssignableFrom(standard)) {
                    return standard;
                }
                throw new JSONException("多态列的容器标记 " + typeName + " 出现在业务类型位置: "
                        + (expectClass == null ? "?" : expectClass.getName()));
            }
            for (Class<?> bound : bounds) {
                if (bound.isAssignableFrom(clazz)) {
                    return clazz;
                }
            }
            if (expectClass != null && expectClass != Object.class && expectClass.isAssignableFrom(clazz)) {
                return clazz;
            }
            throw new JSONException("多态列的类型标记 " + typeName + " 不是声明类型 "
                    + (expectClass == null ? "?" : expectClass.getName()) + " 的子类");
        }

        private static boolean isContainer(Class<?> clazz) {
            return Map.class.isAssignableFrom(clazz) || Collection.class.isAssignableFrom(clazz);
        }

        private static Class<?> load(String typeName) {
            if (typeName == null || typeName.isEmpty()) {
                return null;
            }
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            if (loader == null) {
                loader = SubtypeFilter.class.getClassLoader();
            }
            try {
                return Class.forName(typeName, false, loader);
            } catch (Throwable e) {
                return null;
            }
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
