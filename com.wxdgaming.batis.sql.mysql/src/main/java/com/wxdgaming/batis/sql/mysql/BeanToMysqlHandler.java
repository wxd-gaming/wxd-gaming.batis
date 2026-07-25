package com.wxdgaming.batis.sql.mysql;

import com.alibaba.fastjson.JSON;
import com.wxdgaming.batis.annotation.GenerationType;
import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.StorageMode;
import com.wxdgaming.batis.model.TableInfo;
import com.wxdgaming.batis.parser.EntityParser;
import com.wxdgaming.batis.sql.BeanToSqlDbHandler;
import com.wxdgaming.batis.sql.QueryRunner;
import com.wxdgaming.batis.util.ProtostuffUtil;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 把实体类写入 mysql（INSERT / UPDATE）
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-24 18:37
 **/
@Slf4j
public class BeanToMysqlHandler implements BeanToSqlDbHandler<MysqlTemplate> {

    /** 默认行为：根据主键是否已赋值决定 INSERT 或 UPDATE */
    @Override
    public void doAction(MysqlTemplate mysqlTemplate, Object bean) {
        if (isPrimaryKeyAssigned(bean)) {
            update(mysqlTemplate, bean);
        } else {
            insert(mysqlTemplate, bean);
        }
    }

    /** 写入（INSERT），自增主键不写入，并回写生成的 id */
    public void insert(MysqlTemplate mysqlTemplate, Object bean) {
        TableInfo tableInfo = EntityParser.parse(bean.getClass());
        LinkedHashMap<String, ColumnInfo> columns = tableInfo.getColumns();

        List<String> colNames = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        for (ColumnInfo col : columns.values()) {
            if (isIdentityPk(col)) {
                continue; // 自增主键由数据库生成
            }
            colNames.add("`" + col.getColumnName().toLowerCase() + "`");
            params.add(convertToDbValue(readFieldValue(bean, col), col));
        }

        String sql = buildInsertSql(tableInfo.getTableName(), colNames);
        log.debug("写入实体 sql:\n{}", sql);
        try (QueryRunner qr = new QueryRunner(mysqlTemplate)) {
            Number genId = qr.executeInsert(sql, params.toArray());
            if (genId != null) {
                for (ColumnInfo col : columns.values()) {
                    if (isIdentityPk(col)) {
                        writeFieldValue(bean, col, genId);
                    }
                }
            }
        }
    }

    /** 更新（UPDATE），按主键定位，SET 非主键列，返回影响行数 */
    public int update(MysqlTemplate mysqlTemplate, Object bean) {
        TableInfo tableInfo = EntityParser.parse(bean.getClass());
        LinkedHashMap<String, ColumnInfo> columns = tableInfo.getColumns();

        List<String> setCols = new ArrayList<>();
        List<String> whereCols = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        for (ColumnInfo col : columns.values()) {
            Object dbValue = convertToDbValue(readFieldValue(bean, col), col);
            if (col.isPrimaryKey()) {
                whereCols.add("`" + col.getColumnName().toLowerCase() + "` = ?");
                params.add(dbValue);
            } else {
                setCols.add("`" + col.getColumnName().toLowerCase() + "` = ?");
                params.add(dbValue);
            }
        }

        if (whereCols.isEmpty()) {
            throw new IllegalArgumentException(
                    "实体 " + bean.getClass().getSimpleName() + " 未定义主键，无法执行 update");
        }

        String sql = "UPDATE `" + tableInfo.getTableName().toLowerCase() + "` SET "
                + String.join(", ", setCols) + " WHERE " + String.join(" AND ", whereCols);
        log.debug("更新实体 sql:\n{}", sql);
        try (QueryRunner qr = new QueryRunner(mysqlTemplate)) {
            return qr.update(sql, params.toArray());
        }
    }

    // ===== 判定与主键 =====

    private boolean isIdentityPk(ColumnInfo col) {
        return col.isPrimaryKey() && col.getGenerationType() == GenerationType.IDENTITY;
    }

    private boolean isPrimaryKeyAssigned(Object bean) {
        TableInfo tableInfo = EntityParser.parse(bean.getClass());
        boolean hasPk = false;
        for (ColumnInfo col : tableInfo.getColumns().values()) {
            if (!col.isPrimaryKey()) continue;
            hasPk = true;
            Object v = readFieldValue(bean, col);
            if (v == null) return false;
            if (v instanceof Number && ((Number) v).longValue() == 0L) return false;
        }
        return hasPk;
    }

    // ===== SQL 拼装 =====

    private String buildInsertSql(String tableName, List<String> colNames) {
        String cols = String.join(", ", colNames);
        String placeholders = colNames.stream().map(c -> "?").collect(Collectors.joining(", "));
        return "INSERT INTO `" + tableName.toLowerCase() + "` (" + cols + ") VALUES (" + placeholders + ")";
    }

    // ===== 值转换（Bean -> DB） =====

    private Object convertToDbValue(Object value, ColumnInfo col) {
        if (value == null) return null;
        StorageMode mode = col.getStorageMode();
        if (mode == StorageMode.JSON) {
            return JSON.toJSONString(value);
        }
        if (mode == StorageMode.BLOB || mode == StorageMode.MEDIUMBLOB || mode == StorageMode.LONGBLOB) {
            if (value instanceof byte[]) {
                return value; // byte[] 直接落库
            }
            return ProtostuffUtil.encode(value);
        }
        // AUTO：基础类型，做日期/枚举适配
        if (value instanceof java.util.Date) {
            return new Timestamp(((java.util.Date) value).getTime());
        }
        if (value instanceof LocalDateTime) {
            return Timestamp.valueOf((LocalDateTime) value);
        }
        if (value instanceof LocalDate) {
            return Timestamp.valueOf(((LocalDate) value).atStartOfDay());
        }
        if (value instanceof LocalTime) {
            return java.sql.Time.valueOf((LocalTime) value);
        }
        if (value instanceof OffsetDateTime) {
            return Timestamp.from(((OffsetDateTime) value).toInstant());
        }
        if (value instanceof Instant) {
            return Timestamp.from((Instant) value);
        }
        if (value instanceof Enum) {
            return ((Enum<?>) value).name();
        }
        return value;
    }

    // ===== 反射读写 =====

    private Object readFieldValue(Object bean, ColumnInfo col) {
        Field field = getDeclaredField(bean.getClass(), col.getFieldName());
        if (field == null) return null;
        field.setAccessible(true);
        try {
            return field.get(bean);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("读取字段 " + col.getFieldName() + " 失败", e);
        }
    }

    private void writeFieldValue(Object bean, ColumnInfo col, Number value) {
        Field field = getDeclaredField(bean.getClass(), col.getFieldName());
        if (field == null) return;
        field.setAccessible(true);
        try {
            Class<?> t = field.getType();
            if (t == long.class || t == Long.class) field.set(bean, value.longValue());
            else if (t == int.class || t == Integer.class) field.set(bean, value.intValue());
            else if (t == short.class || t == Short.class) field.set(bean, value.shortValue());
            else if (t == byte.class || t == Byte.class) field.set(bean, value.byteValue());
            else if (t == BigDecimal.class) field.set(bean, BigDecimal.valueOf(value.longValue()));
            else field.set(bean, value);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("写回字段 " + col.getFieldName() + " 失败", e);
        }
    }

    private Field getDeclaredField(Class<?> clazz, String fieldName) {
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
}
