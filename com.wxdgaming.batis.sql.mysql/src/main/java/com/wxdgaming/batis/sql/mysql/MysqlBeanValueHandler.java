package com.wxdgaming.batis.sql.mysql;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.parser.Feature;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.wxdgaming.batis.converter.TypeConverter;
import com.wxdgaming.batis.exception.TypeMismatchDataAccessException;
import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.StorageMode;
import com.wxdgaming.batis.sql.AbstractBeanValueHandler;
import com.wxdgaming.batis.util.ProtostuffUtil;
import lombok.extern.slf4j.Slf4j;

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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import java.time.ZonedDateTime;

/**
 * MySQL 方言的 Bean ↔ DB 双向值转换实现
 * <p>继承 {@link com.wxdgaming.batis.sql.AbstractBeanValueHandler} 复用纯反射的字段读写与主键回写，
 * 本类只实现两个方言差异点：{@code convertToDbValue}/{@code convertFromDbValue} 的类型映射
 * （JSON 走 fastjson 带类名、BLOB 走 Protostuff、日期统一转 Timestamp、枚举存 name 等）。</p>
 * <p>由 {@code MysqlTemplate#initDefaults} 造出单一实例并共享给 Insert/Update/Delete/DbToBean 四个 handler，
 * 保证写端与读端使用同一套对称的转换规则。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-31
 */
@Slf4j
public class MysqlBeanValueHandler extends AbstractBeanValueHandler {

    /** JSON/Protostuff 序列化最大重试次数，应对多线程并发修改集合的并发问题 */
    private static final int MAX_SERIALIZE_RETRIES = 3;

    /**
     * Java 值 → JDBC 参数。
     * <p>转换优先级：自定义 {@link TypeConverter}（完全接管）&gt; 列的 StorageMode（JSON/BLOB 序列化）&gt;
     * 基础类型归一。JSON 存成带类名的字符串（WriteClassName），配合读取端 SupportAutoType 才能还原多态/泛型结构；
     * BLOB 非 byte[] 时用 Protostuff 二进制编码。序列化失败按 {@link #MAX_SERIALIZE_RETRIES} 重试并
     * {@code Thread.yield}——针对集合被多线程并发修改导致的偶发序列化异常，让出 CPU 后重试通常即可成功，
     * 重试耗尽才升级为 {@code TypeMismatchDataAccessException}。</p>
     * <p>基础类型 switch 里的方言取舍：日期族统一转 {@code java.sql.Timestamp}（MySQL DATETIME 的 JDBC 形态）；
     * 枚举存 {@code name()}（稳定、可读、不随 ordinal 重排而损坏）；{@code UUID}/{@code Character} 转 String；
     * {@code BigInteger} 转 long 是精度收窄的既有行为。</p>
     */
    @Override
    public Object convertToDbValue(Object value, ColumnInfo col) {
        if (value == null) return null;

        // 优先使用自定义 TypeConverter（完全接管该字段的转换）
        @SuppressWarnings({"rawtypes"})
        TypeConverter converter = col.getConverter();
        if (converter != null) {
            try {
                @SuppressWarnings("unchecked")
                Object object = converter.convertToDatabaseColumn(value);
                return object;
            } catch (Exception e) {
                throw new TypeMismatchDataAccessException(
                        "自定义转换器 " + converter.getClass().getSimpleName()
                                + " convertToDatabaseColumn 失败，字段: " + col.getFieldName(), e);
            }
        }

        StorageMode mode = col.getStorageMode();
        if (mode == StorageMode.JSON) {
            for (int i = 0; ; i++) {
                try {
                    return JSON.toJSONString(value, SerializerFeature.WriteClassName);
                } catch (Exception e) {
                    if (i >= MAX_SERIALIZE_RETRIES - 1) {
                        throw new TypeMismatchDataAccessException("JSON序列化字段" + col.getFieldName() + "失败，已重试" + MAX_SERIALIZE_RETRIES + "次", e);
                    }
                    log.error("JSON序列化字段{}失败，第{}次重试", col.getFieldName(), i + 1, e);
                    Thread.yield();
                }
            }
        }
        if (mode == StorageMode.BLOB || mode == StorageMode.MEDIUMBLOB || mode == StorageMode.LONGBLOB) {
            if (value instanceof byte[]) return value;
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
        return switch (value) {
            case java.util.Date date -> new Timestamp(date.getTime());
            case LocalDateTime localDateTime -> Timestamp.valueOf(localDateTime);
            case LocalDate localDate -> Timestamp.valueOf(localDate.atStartOfDay());
            case LocalTime localTime -> java.sql.Time.valueOf(localTime);
            case OffsetDateTime offsetDateTime -> Timestamp.from(offsetDateTime.toInstant());
            case ZonedDateTime zonedDateTime -> Timestamp.from(zonedDateTime.toInstant());
            case Instant instant -> Timestamp.from(instant);
            case BigInteger bigInt -> bigInt.longValue();
            case AtomicInteger ai -> ai.get();
            case AtomicLong al -> al.get();
            case Character c -> c.toString();
            case UUID uuid -> uuid.toString();
            case Enum<?> anEnum -> anEnum.name();
            default -> value;
        };
    }

    /**
     * JDBC/ResultSet 读出的值 → Java 对象，是 {@link #convertToDbValue} 的逆过程。
     * <p>转换优先级与写入端对称：自定义 TypeConverter &gt; StorageMode（JSON 用 SupportAutoType 反解、
     * BLOB 用 Protostuff 解码；byte[] 列直接返回原始字节不解码）&gt; 基础类型转换。</p>
     * <p>基础类型转换（{@link #convertBasicValue}）重点处理易变形档位：枚举按 name 反查、数值在 Number
     * 间按目标 javaType 精确收窄/放宽、布尔与数值互转、时间族经 {@link #convertTemporal} 归一到 Timestamp 再派发。
     * 精度问题（如 BigDecimal scale）在此不做四舍五入，交由测试端用 compareTo 规避。</p>
     */
    @Override
    public Object convertFromDbValue(Object value, ColumnInfo col) {
        // 优先使用自定义 TypeConverter（完全接管该字段的转换）
        @SuppressWarnings({"rawtypes"})
        TypeConverter converter = col.getConverter();
        if (converter != null) {
            try {
                @SuppressWarnings("unchecked")
                Object object = converter.convertToEntityAttribute(value);
                return object;
            } catch (Exception e) {
                throw new TypeMismatchDataAccessException(
                        "自定义转换器 " + converter.getClass().getSimpleName()
                                + " convertToEntityAttribute 失败，字段: " + col.getFieldName(), e);
            }
        }

        StorageMode mode = col.getStorageMode();
        if (mode == StorageMode.JSON) {
            Type genericType = col.getGenericType();
            String jsonStr = value.toString();
            try {
                return JSON.parseObject(jsonStr, genericType != null ? genericType : col.getJavaType(), Feature.SupportAutoType);
            } catch (Exception e) {
                throw new TypeMismatchDataAccessException("JSON 反序列化字段 " + col.getFieldName() + " 失败，value=" + jsonStr, e);
            }
        }
        if (mode == StorageMode.BLOB || mode == StorageMode.MEDIUMBLOB || mode == StorageMode.LONGBLOB) {
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
        return convertBasicValue(value, col);
    }

    // ===== 私有 helper =====

    /** 把 BLOB 列读出的值取成字节数组：已是 byte[] 直接用，java.sql.Blob 需 getBytes 物化，其余类型不支持 */
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

    /**
     * 非 JSON/BLOB 的基础列值转换：目标类型与值已匹配则直接返回，否则按目标 javaType 逐档适配。
     * <p>顺序体现优先级：同类型短路 → 枚举按名反查 → Number 数值族收窄/放宽 → 布尔 → 时间族
     * （{@link #convertTemporal}）→ UUID/Character/String 兜底。无法识别时原样返回，把最终类型检查交给 setter。</p>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object convertBasicValue(Object value, ColumnInfo col) {
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
            if (javaType == BigInteger.class) return BigInteger.valueOf(num.longValue());
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
            if (value instanceof String s && !s.isEmpty()) return s.charAt(0);
            return value.toString().charAt(0);
        }
        if (javaType == String.class) {
            return value.toString();
        }
        return value;
    }

    /**
     * 时间族互转：先把各种来源统一收敛为 Timestamp 再按目标 javaType 派发。
     * <p>Time 单独短路（TIME 列只关心时分秒，不能被当成日期）；其余先归一到 Timestamp，
     * 再转 Timestamp/java.util.Date/java.sql.Date/LocalDateTime/LocalDate/Instant/ZonedDateTime/OffsetDateTime。
     * 跨时区档位（Zoned/Offset）依赖系统时区或 UTC，是与 JDBC 驱动时区配置相关的既有约定。
     * 无法归一时返回 null，交回调用方走后续兜底分支。</p>
     */
    private Object convertTemporal(Object value, Class<?> javaType) {
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
}
