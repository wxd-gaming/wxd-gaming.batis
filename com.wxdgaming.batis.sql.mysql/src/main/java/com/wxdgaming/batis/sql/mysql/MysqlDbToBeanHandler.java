package com.wxdgaming.batis.sql.mysql;

import com.alibaba.fastjson.JSON;
import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.StorageMode;
import com.wxdgaming.batis.model.TableInfo;
import com.wxdgaming.batis.parser.EntityParser;
import com.wxdgaming.batis.sql.JdbcTemplate;
import com.wxdgaming.batis.sql.QueryRunner;
import com.wxdgaming.batis.sql.SqlDbToBeanHandler;
import com.wxdgaming.batis.util.ProtostuffUtil;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.sql.Blob;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 从 mysql 读取数据并映射为实体类（SELECT），与 {@code BeanToInsertHandler}/{@code BeanToUpdateHandler} 对应的反向操作
 * <p>映射统一走 {@link ColumnInfo} 缓存的 setter 方法，JSON 字段结合 genericType 与 @type 自动推断类型。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-25
 **/
@Slf4j
public class MysqlDbToBeanHandler implements SqlDbToBeanHandler {

    /** 按主键查询单条记录，未定义主键时抛异常，未查询到返回 null */
    @Override
    public <T> T queryById(JdbcTemplate mysqlTemplate, Class<T> entityClass, Object idValue) {
        TableInfo tableInfo = EntityParser.parse(entityClass);
        ColumnInfo pk = findPrimaryKey(tableInfo, entityClass);
        String sql = "SELECT * FROM `" + tableInfo.getTableName().toLowerCase()
                + "` WHERE `" + pk.getColumnName().toLowerCase() + "` = ?";
        return query(mysqlTemplate, entityClass, sql, idValue);
    }

    /** 自定义 SQL 查询单条记录，未查询到返回 null */
    @Override
    public <T> T query(JdbcTemplate mysqlTemplate, Class<T> entityClass, String sql, Object... args) {
        log.debug("查询实体 sql:\n{}", sql);
        try (QueryRunner qr = new QueryRunner(mysqlTemplate)) {
            return qr.query(sql, args, bean(entityClass));
        }
    }

    /** 自定义 SQL 查询多条记录 */
    @Override
    public <T> List<T> queryList(JdbcTemplate mysqlTemplate, Class<T> entityClass, String sql, Object... args) {
        log.debug("查询实体列表 sql:\n{}", sql);
        try (QueryRunner qr = new QueryRunner(mysqlTemplate)) {
            return qr.query(sql, args, beanList(entityClass));
        }
    }

    /** 查询表中所有记录 */
    @Override
    public <T> List<T> queryAll(JdbcTemplate mysqlTemplate, Class<T> entityClass) {
        TableInfo tableInfo = EntityParser.parse(entityClass);
        String sql = "SELECT * FROM `" + tableInfo.getTableName().toLowerCase() + "`";
        return queryList(mysqlTemplate, entityClass, sql);
    }

    // ===== 行映射（DB -> Bean） =====

    /** 将 ResultSet 当前行映射为实体，列标签与列名大小写不敏感匹配 */
    @Override
    public <T> T toBean(ResultSet rs, Class<T> entityClass) throws SQLException {
        TableInfo tableInfo = EntityParser.parse(entityClass);
        T instance;
        try {
            instance = entityClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new SQLException("实例化实体 " + entityClass.getSimpleName() + " 失败，请确认存在无参构造", e);
        }
        ResultSetMetaData metaData = rs.getMetaData();
        int columnCount = metaData.getColumnCount();
        for (int i = 1; i <= columnCount; i++) {
            String columnLabel = metaData.getColumnLabel(i);
            ColumnInfo col = lookupColumn(tableInfo, columnLabel);
            if (col == null) {
                continue;
            }
            Object value = rs.getObject(i);
            if (value == null) {
                continue;
            }
            writeFieldValue(instance, col, convertFromDbValue(value, col));
        }
        return instance;
    }

    // ===== 列查找与主键 =====

    /** 按列名查找列元数据（大小写不敏感） */
    private ColumnInfo lookupColumn(TableInfo tableInfo, String columnLabel) {
        ColumnInfo col = tableInfo.getColumn(columnLabel);
        if (col != null) {
            return col;
        }
        for (ColumnInfo c : tableInfo.getColumns().values()) {
            if (c.getColumnName().equalsIgnoreCase(columnLabel)) {
                return c;
            }
        }
        return null;
    }

    private ColumnInfo findPrimaryKey(TableInfo tableInfo, Class<?> entityClass) {
        for (ColumnInfo col : tableInfo.getColumns().values()) {
            if (col.isPrimaryKey()) {
                return col;
            }
        }
        throw new IllegalArgumentException(
                "实体 " + entityClass.getSimpleName() + " 未定义主键，无法执行 queryById");
    }

    // ===== 值转换（DB -> Bean） =====

    private Object convertFromDbValue(Object value, ColumnInfo col) throws SQLException {
        StorageMode mode = col.getStorageMode();
        if (mode == StorageMode.JSON) {
            // 优先使用 genericType 还原泛型（如 List<Xxx>）；字符串含 @type 时 fastjson 自动推断实际类型
            Type genericType = col.getGenericType();
            String jsonStr = value.toString();
            try {
                return JSON.parseObject(jsonStr, genericType != null ? genericType : col.getJavaType());
            } catch (Exception e) {
                throw new SQLException("JSON 反序列化字段 " + col.getFieldName() + " 失败，value=" + jsonStr, e);
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
                throw new SQLException("Protostuff 解码字段 " + col.getFieldName() + " 失败，bytes length=" + bytes.length, e);
            }
        }
        // AUTO：基础类型，做日期/枚举适配
        return convertBasicValue(value, col);
    }

    private byte[] toBytes(Object value, ColumnInfo col) throws SQLException {
        if (value instanceof byte[]) {
            return (byte[]) value;
        }
        if (value instanceof Blob) {
            Blob blob = (Blob) value;
            return blob.getBytes(1, (int) blob.length());
        }
        throw new SQLException("字段 " + col.getFieldName() + " 不支持的二进制类型: " + value.getClass());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object convertBasicValue(Object value, ColumnInfo col) {
        Class<?> javaType = col.getJavaType();
        if (javaType.isInstance(value)) {
            return value;
        }
        // 枚举：数据库存 name()
        if (javaType.isEnum() && value instanceof String) {
            return Enum.valueOf((Class<? extends Enum>) javaType, (String) value);
        }
        // 数字类型适配（driver 可能返回与字段不一致的 Number 子类）
        if (value instanceof Number) {
            Number num = (Number) value;
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
        // 时间类型适配
        Object temporal = convertTemporal(value, javaType);
        if (temporal != null) {
            return temporal;
        }
        if (javaType == String.class) {
            return value.toString();
        }
        return value;
    }

    /** 时间类型适配，无法适配时返回 null */
    private Object convertTemporal(Object value, Class<?> javaType) {
        // 统一转为 Timestamp 中转
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

    // ===== 反射写入（统一走 setter，Method 在解析时缓存） =====

    private void writeFieldValue(Object bean, ColumnInfo col, Object value) {
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
}
