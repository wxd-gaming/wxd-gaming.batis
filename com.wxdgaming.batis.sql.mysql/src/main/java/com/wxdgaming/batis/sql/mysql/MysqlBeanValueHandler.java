package com.wxdgaming.batis.sql.mysql;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.parser.Feature;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.wxdgaming.batis.converter.TypeConverter;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MySQL 方言的 Bean ↔ DB 双向值转换实现
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-31
 */
@Slf4j
public class MysqlBeanValueHandler extends AbstractBeanValueHandler {

    /** JSON/Protostuff 序列化最大重试次数，应对多线程并发修改集合的并发问题 */
    private static final int MAX_SERIALIZE_RETRIES = 3;

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
                throw new RuntimeException(
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
                        throw new RuntimeException("JSON序列化字段" + col.getFieldName() + "失败，已重试" + MAX_SERIALIZE_RETRIES + "次", e);
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
                        throw new RuntimeException("Protostuff序列化字段" + col.getFieldName() + "失败，已重试" + MAX_SERIALIZE_RETRIES + "次", e);
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
            case Instant instant -> Timestamp.from(instant);
            case Enum<?> anEnum -> anEnum.name();
            default -> value;
        };
    }

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
                throw new RuntimeException(
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
                throw new RuntimeException("JSON 反序列化字段 " + col.getFieldName() + " 失败，value=" + jsonStr, e);
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
                throw new RuntimeException("Protostuff 解码字段 " + col.getFieldName() + " 失败，bytes length=" + bytes.length, e);
            }
        }
        return convertBasicValue(value, col);
    }

    // ===== 私有 helper =====

    private byte[] toBytes(Object value, ColumnInfo col) {
        if (value instanceof byte[]) {
            return (byte[]) value;
        }
        if (value instanceof Blob blob) {
            try {
                return blob.getBytes(1, (int) blob.length());
            } catch (SQLException e) {
                throw new RuntimeException("读取 Blob 字段 " + col.getFieldName() + " 失败", e);
            }
        }
        throw new RuntimeException("字段 " + col.getFieldName() + " 不支持的二进制类型: " + value.getClass());
    }

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
        }
        if (value instanceof Boolean && (javaType == boolean.class || javaType == Boolean.class)) {
            return value;
        }
        Object temporal = convertTemporal(value, javaType);
        if (temporal != null) {
            return temporal;
        }
        if (javaType == String.class) {
            return value.toString();
        }
        return value;
    }

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
        return null;
    }
}
