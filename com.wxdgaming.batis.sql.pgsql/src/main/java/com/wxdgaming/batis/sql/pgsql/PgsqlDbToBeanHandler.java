package com.wxdgaming.batis.sql.pgsql;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 从 PostgreSQL 读取数据并映射为实体类（SELECT），与 {@link PgsqlBeanToInsertHandler} 的写入方向对应
 * <p>值转换委托给 {@link BeanValueHandler}，自身只负责 SQL 构建与行映射。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-14
 **/
@Slf4j
public class PgsqlDbToBeanHandler implements SqlDbToBeanHandler {

    private final BeanValueHandler valueHandler;

    /**
     * 值转换协作者由外部注入（PgsqlTemplate 中的共享单例），本类不自行 new，
     * 保证读端与写端（Insert/Update/Delete）使用同一个转换实例。
     */
    public PgsqlDbToBeanHandler(BeanValueHandler valueHandler) {
        this.valueHandler = valueHandler;
    }

    /**
     * 按主键查询单条记录，未定义主键时抛异常，未查询到返回 null。
     * <p>复合主键以 AND 串联全部主键列，参数形态为：单主键传单个值、复合主键传 {@code Object[]}。
     * 主键列数与提供的值数不符时快速失败抛异常，避免静默只绑第一个主键列、查回错误行。</p>
     */
    @Override
    public <T> T queryById(JdbcTemplate pgsqlTemplate, Class<T> entityClass, Object idValue) {
        TableInfo tableInfo = EntityParser.parse(entityClass);
        List<ColumnInfo> pks = new ArrayList<>();
        for (ColumnInfo col : tableInfo.getColumns().values()) {
            if (col.isPrimaryKey()) pks.add(col);
        }
        if (pks.isEmpty()) {
            throw new IllegalArgumentException(
                    "实体 " + entityClass.getSimpleName() + " 未定义主键，无法执行 queryById");
        }
        Object[] args = pks.size() == 1 ? new Object[]{idValue} : (Object[]) idValue;
        if (args.length != pks.size()) {
            throw new IllegalArgumentException(
                    "实体 " + entityClass.getSimpleName() + " 有 " + pks.size()
                            + " 个主键列，但只提供了 " + args.length + " 个主键值");
        }
        StringBuilder where = new StringBuilder();
        for (int i = 0; i < pks.size(); i++) {
            if (i > 0) where.append(" AND ");
            where.append(PgsqlIdentifier.quote(pks.get(i).getColumnName())).append(" = ?");
        }
        String sql = "SELECT * FROM " + PgsqlIdentifier.quote(tableInfo.getTableName()) + " WHERE " + where;
        return query(pgsqlTemplate, entityClass, sql, args);
    }

    /** 自定义 SQL 查询单条记录，未查询到返回 null */
    @Override
    public <T> T query(JdbcTemplate pgsqlTemplate, Class<T> entityClass, String sql, Object... args) {
        log.debug("查询实体 sql:\n{}", sql);
        try (QueryRunner qr = new QueryRunner(pgsqlTemplate)) {
            return qr.query(sql, args, bean(entityClass));
        }
    }

    /** 自定义 SQL 查询多条记录 */
    @Override
    public <T> List<T> queryList(JdbcTemplate pgsqlTemplate, Class<T> entityClass, String sql, Object... args) {
        log.debug("查询实体列表 sql:\n{}", sql);
        try (QueryRunner qr = new QueryRunner(pgsqlTemplate)) {
            return qr.query(sql, args, beanList(entityClass));
        }
    }

    /** 查询表中所有记录 */
    @Override
    public <T> List<T> queryAll(JdbcTemplate pgsqlTemplate, Class<T> entityClass) {
        TableInfo tableInfo = EntityParser.parse(entityClass);
        String sql = "SELECT * FROM " + PgsqlIdentifier.quote(tableInfo.getTableName());
        return queryList(pgsqlTemplate, entityClass, sql);
    }

    // ===== 行映射（DB -> Bean） =====

    /**
     * 将 ResultSet 当前行映射为实体：逐列按标签找实体列元数据，值经 {@link BeanValueHandler} 方言转换后 setter 写入。
     * <p>结果集比实体多出的列（如手写 SELECT 附加的表达式列）直接跳过，映射只对实体声明的字段负责；
     * 库端 NULL 也跳过写入，让字段保持 Java 默认值（null/0），避免把 NULL 硬塞进基本类型 setter 报错。</p>
     * <p>列标签的匹配口径是"小写优先、大小写不敏感兜底"：PG 给未加引号的别名一律折成小写，
     * 所以精确匹配在正常路径上就够用；实体写驼峰列名（{@code accountName}）时，
     * 库端标签是 {@code accountname}，走兜底索引才不会静默丢字段。</p>
     */
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
        // 精确匹配优先（实体列名与库端一致时零开销）；大小写不敏感的索引按需惰性构建，
        // 只在出现大小写不匹配时才建一次，避免把 O(cols²) 的线性回退摊到每一行
        Map<String, ColumnInfo> caseInsensitive = null;
        for (int i = 1; i <= columnCount; i++) {
            String columnLabel = metaData.getColumnLabel(i);
            ColumnInfo col = tableInfo.getColumn(columnLabel);
            if (col == null) {
                if (caseInsensitive == null) {
                    caseInsensitive = indexByLower(tableInfo);
                }
                col = caseInsensitive.get(columnLabel.toLowerCase(Locale.ROOT));
            }
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

    // ===== 列查找 =====

    /** 构建"列名(小写) → 列元数据"索引，供大小写不敏感回退时一次建、多次查 */
    private Map<String, ColumnInfo> indexByLower(TableInfo tableInfo) {
        Map<String, ColumnInfo> index = new HashMap<>();
        for (ColumnInfo c : tableInfo.getColumns().values()) {
            index.put(c.getColumnName().toLowerCase(Locale.ROOT), c);
        }
        return index;
    }
}
