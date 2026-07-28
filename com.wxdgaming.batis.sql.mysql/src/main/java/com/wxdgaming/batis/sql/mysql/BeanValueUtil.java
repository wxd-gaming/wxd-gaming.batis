package com.wxdgaming.batis.sql.mysql;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.StorageMode;
import com.wxdgaming.batis.util.ProtostuffUtil;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;

/**
 * Bean ←→ DB 值转换工具，供本包内的 Handler 使用
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-28
 **/
public class BeanValueUtil {

    /** 从 bean 中读取字段值 */
    static Object readFieldValue(Object bean, ColumnInfo col) {
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

    /** 将 Java 值转换为数据库可接受的类型 */
    static Object convertToDbValue(Object value, ColumnInfo col) {
        if (value == null) return null;
        StorageMode mode = col.getStorageMode();
        if (mode == StorageMode.JSON) {
            return JSON.toJSONString(value, SerializerFeature.WriteClassName);
        }
        if (mode == StorageMode.BLOB || mode == StorageMode.MEDIUMBLOB || mode == StorageMode.LONGBLOB) {
            if (value instanceof byte[]) return value;
            return ProtostuffUtil.encode(value);
        }
        if (value instanceof java.util.Date)
            return new Timestamp(((java.util.Date) value).getTime());
        if (value instanceof LocalDateTime)
            return Timestamp.valueOf((LocalDateTime) value);
        if (value instanceof LocalDate)
            return Timestamp.valueOf(((LocalDate) value).atStartOfDay());
        if (value instanceof LocalTime)
            return java.sql.Time.valueOf((LocalTime) value);
        if (value instanceof OffsetDateTime)
            return Timestamp.from(((OffsetDateTime) value).toInstant());
        if (value instanceof Instant)
            return Timestamp.from((Instant) value);
        if (value instanceof Enum)
            return ((Enum<?>) value).name();
        return value;
    }

    /** 将自增主键 Number 回写到 bean 的指定字段 */
    static void writeGeneratedKey(Object bean, ColumnInfo col, Number value) {
        if (value == null) return;
        Method setter = col.getWriteMethod();
        if (setter == null) {
            throw new RuntimeException("实体 " + bean.getClass().getSimpleName()
                    + " 缺少字段 " + col.getFieldName() + " 的 setter 方法，请为其添加 setter");
        }
        Class<?> paramType = setter.getParameterTypes()[0];
        Object arg;
        if (paramType == long.class || paramType == Long.class) arg = value.longValue();
        else if (paramType == int.class || paramType == Integer.class) arg = value.intValue();
        else if (paramType == short.class || paramType == Short.class) arg = value.shortValue();
        else if (paramType == byte.class || paramType == Byte.class) arg = value.byteValue();
        else if (paramType == double.class || paramType == Double.class) arg = value.doubleValue();
        else if (paramType == float.class || paramType == Float.class) arg = value.floatValue();
        else if (paramType == BigDecimal.class) arg = BigDecimal.valueOf(value.longValue());
        else arg = value;
        try {
            setter.invoke(bean, arg);
        } catch (Exception e) {
            throw new RuntimeException("通过 setter 写回字段 " + col.getFieldName() + " 失败", e);
        }
    }
}
