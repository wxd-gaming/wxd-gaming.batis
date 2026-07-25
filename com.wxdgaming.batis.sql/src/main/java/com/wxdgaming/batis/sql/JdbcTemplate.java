package com.wxdgaming.batis.sql;

import com.wxdgaming.batis.DbTemplate;
import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.IndexInfo;
import com.wxdgaming.batis.model.TableInfo;
import com.wxdgaming.batis.parser.EntityParser;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * jdbc 操作
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-24 17:32
 **/
@Getter
@Setter
public class JdbcTemplate extends DbTemplate {

    private final HikariDataSource dataSource;

    /** 表结构缓存，key 为小写表名 */
    private HashMap<String, TableInfo> tableCache;
    /** 数据库元数据提供者（方言差异在此注入） */
    private IDbMetaProvider dbMetaProvider;
    /** 创建表的处理器 */
    private TableCreateHandler tableCreateHandler;
    /** 修改表的处理器 */
    private TableAlterHandler tableAlterHandler;

    public JdbcTemplate(HikariConfig config) {
        this(new HikariDataSource(config));
    }

    public JdbcTemplate(HikariDataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 从数据库加载所有表结构到缓存
     * <p>使用 {@link #dbMetaProvider} 提供的 SQL 和 Handler 分三步查询：表列表 → 列信息 → 索引信息</p>
     */
    public void loadAllTables() {
        if (dbMetaProvider == null) return;
        HashMap<String, TableInfo> cache = new HashMap<>();

        try (QueryRunner qr = new QueryRunner(this)) {
            String catalog = dbMetaProvider.getCatalog(this);
            if (catalog == null) return;

            List<TableInfo> tables = qr.query(
                    dbMetaProvider.tableListSql(),
                    new Object[]{catalog},
                    dbMetaProvider.tableListHandler()
            );

            for (TableInfo tableInfo : tables) {
                String tableName = tableInfo.getTableName();
                String key = tableName.toLowerCase();

                List<ColumnInfo> columns = qr.query(
                        dbMetaProvider.columnListSql(),
                        new Object[]{catalog, tableName},
                        dbMetaProvider.columnListHandler()
                );

                List<IndexInfo> indexes = qr.query(
                        dbMetaProvider.indexListSql(),
                        new Object[]{catalog, tableName},
                        dbMetaProvider.indexListHandler()
                );

                LinkedHashMap<String, ColumnInfo> columnMap = new LinkedHashMap<>();
                for (ColumnInfo col : columns) {
                    columnMap.put(col.getColumnName().toLowerCase(), col);
                }
                tableInfo.setColumns(columnMap);
                tableInfo.setIndexes(indexes);

                cache.put(key, tableInfo);
            }
        }

        this.tableCache = cache;
    }

    /**
     * 检查并同步表结构
     * <p>先解析实体类得到 {@link TableInfo}，再委托给 {@link #checkTable(TableInfo)}</p>
     *
     * @param cls 实体类
     */
    public void checkTable(Class<?> cls) {
        TableInfo tableInfo = EntityParser.parse(cls);
        checkTable(tableInfo);
    }

    /**
     * 检查并同步表结构
     * <p>缓存不存在时自动加载；表不存在则调用 {@link TableCreateHandler} 创建；
     * 表存在则调用 {@link TableAlterHandler} 对比更新（变更后刷新缓存）。</p>
     *
     * @param tableInfo 实体解析得到的表元数据
     */
    public void checkTable(TableInfo tableInfo) {
        if (tableCache == null) {
            loadAllTables();
        }
        String tableName = tableInfo.getTableName().toLowerCase();
        TableInfo dbTable = tableCache != null ? tableCache.get(tableName) : null;
        if (dbTable == null) {
            if (tableCreateHandler != null) {
                tableCreateHandler.doAction(this, tableInfo);
                // 创建后刷新缓存
                loadAllTables();
            }
        } else {
            if (tableAlterHandler != null) {
                tableAlterHandler.doAction(this, tableInfo);
                // 变更后刷新缓存
                loadAllTables();
            }
        }
    }

    /** 清空表结构缓存，下次调用 checkTable 时会重新加载 */
    public void clearTableCache() {
        this.tableCache = null;
    }

}
