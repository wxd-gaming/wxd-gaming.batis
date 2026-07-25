package com.wxdgaming.batis.sql;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.StorageMode;
import com.wxdgaming.batis.model.TableInfo;
import com.wxdgaming.batis.parser.EntityParser;
import com.wxdgaming.batis.util.ProtostuffUtil;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 内置的 ResultSetHandler 工厂方法
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-24 17:33
 **/
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class Handlers {

    /**
     * 读取第一个单元格的值（第一行第一列），返回 Object
     */
    public static ResultSetHandler<Object> firstCell() {
        return rs -> {
            if (rs.next()) {
                return rs.getObject(1);
            }
            return null;
        };
    }

    /**
     * 读取第一个单元格的值（第一行第一列），按指定类型返回
     */
    public static <T> ResultSetHandler<T> firstCell(Class<T> type) {
        return rs -> {
            if (rs.next()) {
                return rs.getObject(1, type);
            }
            return null;
        };
    }

    /**
     * 读取一行，用列标签（ColumnLabel）作为 key 包装为 JSONObject
     */
    public static ResultSetHandler<JSONObject> row() {
        return rs -> {
            if (rs.next()) {
                return toJsonObject(rs);
            }
            return null;
        };
    }

    /**
     * 读取所有行，每行用列标签作为 key 包装为 JSONObject，返回 List<JSONObject>
     */
    public static ResultSetHandler<List<JSONObject>> list() {
        return rs -> {
            List<JSONObject> list = new ArrayList<>();
            while (rs.next()) {
                list.add(toJsonObject(rs));
            }
            return list;
        };
    }

    // ======================== Bean 映射 ========================

    /**
     * 读取一行，映射为指定实体类的 Bean 对象
     * <p>
     * 通过 {@link EntityParser} 解析实体类得到 {@link TableInfo}，
     * 按 ResultSet 列名（ColumnLabel）匹配数据库列名，再通过反射设值到 Java 字段。
     *
     * @param entityClass 实体类
     * @param <T>         实体类型
     */
    public static <T> ResultSetHandler<T> bean(Class<T> entityClass) {
        return rs -> {
            if (rs.next()) {
                return toBean(rs, entityClass);
            }
            return null;
        };
    }

    /**
     * 读取所有行，每行映射为指定实体类的 Bean 对象，返回 List&lt;T&gt;
     *
     * @param entityClass 实体类
     * @param <T>         实体类型
     */
    public static <T> ResultSetHandler<List<T>> beanList(Class<T> entityClass) {
        return rs -> {
            List<T> list = new ArrayList<>();
            while (rs.next()) {
                list.add(toBean(rs, entityClass));
            }
            return list;
        };
    }

    /** 将 ResultSet 当前行映射为实体 Bean */
    private static <T> T toBean(ResultSet rs, Class<T> entityClass) throws SQLException {
        TableInfo tableInfo = EntityParser.parse(entityClass);
        try {
            T instance = entityClass.getDeclaredConstructor().newInstance();
            ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();
            for (int i = 1; i <= columnCount; i++) {
                String columnLabel = metaData.getColumnLabel(i);
                ColumnInfo colInfo = tableInfo.getColumn(columnLabel);
                if (colInfo == null) {
                    continue;
                }
                String fieldName = colInfo.getFieldName();
                Object value = rs.getObject(i);
                if (value == null) {
                    continue;
                }
                Field field = getDeclaredField(entityClass, fieldName);
                if (field == null) {
                    continue;
                }
                field.setAccessible(true);
                Object converted = convertValue(value, field, colInfo);
                field.set(instance, converted);
            }
            return instance;
        } catch (ReflectiveOperationException e) {
            throw new SQLException("Failed to map ResultSet to " + entityClass.getSimpleName(), e);
        }
    }

    /** 根据存储模式将数据库值转换为 Java 字段类型 */
    private static Object convertValue(Object value, Field field, ColumnInfo colInfo) throws SQLException {
        StorageMode storageMode = colInfo.getStorageMode();
        if (storageMode == StorageMode.JSON) {
            // JSON 反序列化：数据库返回 String（或原生 JSON 类型），解析为目标类型
            String jsonStr = value.toString();
            Type genericType = field.getGenericType();
            try {
                return JSON.parseObject(jsonStr, genericType);
            } catch (Exception e) {
                throw new SQLException("Failed to parse JSON for field '" + field.getName()
                        + "', value=" + jsonStr, e);
            }
        }
        if (storageMode == StorageMode.BLOB
                || storageMode == StorageMode.MEDIUMBLOB
                || storageMode == StorageMode.LONGBLOB) {
            // 二进制反序列化：数据库返回 byte[]，使用 ProtostuffUtil 解码
            byte[] bytes;
            if (value instanceof byte[]) {
                bytes = (byte[]) value;
            } else if (value instanceof java.sql.Blob) {
                java.sql.Blob blob = (java.sql.Blob) value;
                bytes = blob.getBytes(1, (int) blob.length());
            } else {
                throw new SQLException("Unsupported binary type for field '" + field.getName()
                        + "': " + value.getClass());
            }
            try {
                return ProtostuffUtil.decode(bytes, field.getType());
            } catch (Exception e) {
                throw new SQLException("Failed to decode Protostuff for field '" + field.getName()
                        + "', bytes length=" + bytes.length, e);
            }
        }
        // AUTO 模式：做常见类型适配后返回原值
        Object result = value;
        Class<?> fieldType = field.getType();
        if (fieldType == java.util.Date.class
                || fieldType == java.sql.Timestamp.class
                || fieldType == java.sql.Date.class
                || fieldType == java.sql.Time.class) {
            if (value instanceof java.time.LocalDateTime) {
                result = java.sql.Timestamp.valueOf((java.time.LocalDateTime) value);
            } else if (value instanceof java.time.LocalDate) {
                result = java.sql.Timestamp.valueOf(((java.time.LocalDate) value).atStartOfDay());
            } else if (value instanceof java.time.OffsetDateTime) {
                result = java.sql.Timestamp.from(((java.time.OffsetDateTime) value).toInstant());
            } else if (value instanceof java.time.Instant) {
                result = java.sql.Timestamp.from((java.time.Instant) value);
            }
        }
        return result;
    }

    /** 从类及其继承链上查找字段 */
    private static Field getDeclaredField(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    // ======================== 内部工具 ========================

    /**
     * 将当前行转换为 JSONObject，key 使用 ResultSetMetaData.getColumnLabel()
     */
    private static JSONObject toJsonObject(ResultSet rs) throws SQLException {
        ResultSetMetaData metaData = rs.getMetaData();
        int columnCount = metaData.getColumnCount();
        JSONObject json = new JSONObject(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            String label = metaData.getColumnLabel(i);
            Object value = rs.getObject(i);
            json.put(label, value);
        }
        return json;
    }

}
