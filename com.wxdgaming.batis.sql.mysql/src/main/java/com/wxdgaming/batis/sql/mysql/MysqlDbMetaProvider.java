package com.wxdgaming.batis.sql.mysql;

import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.IndexInfo;
import com.wxdgaming.batis.sql.IDbMetaProvider;
import com.wxdgaming.batis.sql.JdbcTemplate;
import com.wxdgaming.batis.sql.QueryRunner;
import com.wxdgaming.batis.sql.ResultSetHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * MySQL 数据库元数据提供者
 * <p>基于 information_schema 查询表结构、列信息、索引信息</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-24
 **/
public class MysqlDbMetaProvider implements IDbMetaProvider {

    @Override
    public String getCatalog(JdbcTemplate jdbcTemplate) {
        try (QueryRunner qr = new QueryRunner(jdbcTemplate)) {
            return (String) qr.query("SELECT DATABASE()", rs -> {
                if (rs.next()) return rs.getString(1);
                return null;
            });
        }
    }

    @Override
    public String tableListSql() {
        return "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = ?";
    }

    @Override
    public ResultSetHandler<List<String>> tableListHandler() {
        return rs -> {
            List<String> list = new ArrayList<>();
            while (rs.next()) {
                list.add(rs.getString("TABLE_NAME"));
            }
            return list;
        };
    }

    @Override
    public String columnListSql() {
        return "SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT, COLUMN_KEY, EXTRA "
                + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? "
                + "ORDER BY ORDINAL_POSITION";
    }

    @Override
    public ResultSetHandler<List<ColumnInfo>> columnListHandler() {
        return rs -> {
            List<ColumnInfo> columns = new ArrayList<>();
            while (rs.next()) {
                ColumnInfo col = new ColumnInfo();
                col.setColumnName(rs.getString("COLUMN_NAME"));
                col.setType(rs.getString("COLUMN_TYPE"));
                col.setNullable("YES".equals(rs.getString("IS_NULLABLE")));
                col.setDefaultValue(handleDefaultValue(rs.getString("COLUMN_DEFAULT")));
                col.setComment(rs.getString("COLUMN_COMMENT") != null ? rs.getString("COLUMN_COMMENT") : "");
                col.setPrimaryKey("PRI".equals(rs.getString("COLUMN_KEY")));
                columns.add(col);
            }
            return columns;
        };
    }

    @Override
    public String indexListSql() {
        return "SELECT INDEX_NAME, COLUMN_NAME, NON_UNIQUE, SEQ_IN_INDEX "
                + "FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? "
                + "ORDER BY INDEX_NAME, SEQ_IN_INDEX";
    }

    @Override
    public ResultSetHandler<List<IndexInfo>> indexListHandler() {
        return rs -> {
            LinkedHashMap<String, IndexInfo> indexMap = new LinkedHashMap<>();
            while (rs.next()) {
                String indexName = rs.getString("INDEX_NAME");
                // 主键索引已由 columnListHandler 通过 COLUMN_KEY=PRI 捕获，此处跳过
                if ("PRIMARY".equals(indexName)) continue;

                IndexInfo info = indexMap.computeIfAbsent(indexName, k -> {
                    IndexInfo i = new IndexInfo();
                    i.setName(indexName);
                    i.setColumns(new ArrayList<>());
                    return i;
                });
                info.getColumns().add(rs.getString("COLUMN_NAME"));
                // NON_UNIQUE = 0 表示唯一索引，1 表示非唯一
                info.setUnique(rs.getInt("NON_UNIQUE") == 0);
            }
            return new ArrayList<>(indexMap.values());
        };
    }

    /**
     * 处理 MySQL 的默认值：COLUMN_DEFAULT 为 SQL NULL 时返回 null，
     * 为字符串 "NULL" 时返回 "NULL"（表示 DEFAULT NULL）
     */
    private static String handleDefaultValue(String defaultValue) {
        if (defaultValue == null) return null;
        // MySQL 以字符串 "NULL" 表示 DEFAULT NULL
        if ("NULL".equals(defaultValue)) return "NULL";
        return defaultValue;
    }
}
