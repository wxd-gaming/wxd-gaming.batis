package com.wxdgaming.batis.sql.mysql;

import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.TableInfo;
import com.wxdgaming.batis.parser.EntityParser;
import com.wxdgaming.batis.sql.BeanValueHandler;
import com.wxdgaming.batis.sql.JdbcTemplate;
import com.wxdgaming.batis.sql.QueryRunner;
import com.wxdgaming.batis.sql.SqlDbToBeanHandler;
import lombok.extern.slf4j.Slf4j;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.List;

/**
 * 从 mysql 读取数据并映射为实体类（SELECT），与 {@code BeanToInsertHandler}/{@code BeanToUpdateHandler} 对应的反向操作
 * <p>值转换委托给 {@link BeanValueHandler}，自身只负责 SQL 构建与行映射。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-25
 **/
@Slf4j
public class MysqlDbToBeanHandler implements SqlDbToBeanHandler {

    private final BeanValueHandler valueHandler;

    public MysqlDbToBeanHandler(BeanValueHandler valueHandler) {
        this.valueHandler = valueHandler;
    }

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
            valueHandler.writeFieldValue(instance, col, valueHandler.convertFromDbValue(value, col));
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
}
