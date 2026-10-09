package com.wxdgaming.batis.sql.mysql;

import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.IndexInfo;
import com.wxdgaming.batis.model.TableInfo;
import com.wxdgaming.batis.sql.DbMetaHandler;
import com.wxdgaming.batis.sql.JdbcTemplate;
import com.wxdgaming.batis.sql.QueryRunner;
import com.wxdgaming.batis.sql.ResultSetHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * MySQL 数据库元数据处理器
 * <p>基于 information_schema 查询表结构、列信息、索引信息。</p>
 * <p>直查 information_schema 而非 JDBC DatabaseMetaData：COLUMN_TYPE 能拿到 MySQL 原生物理形态
 * （含显示宽度、unsigned 等修饰），且列顺序/索引列序可控（ORDER BY），这些正是结构 diff 需要的比较原料；
 * 读回的原始类型字符串最终都交给 {@link MysqlColumnTypeDefHandler#normalize} 归一后才参与比对。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-24
 **/
public class MysqlDbMetaHandler implements DbMetaHandler {

    /**
     * 取当前连接所在库名，作为后续 information_schema 查询的 TABLE_SCHEMA 过滤条件。
     * <p>用 {@code SELECT DATABASE()} 而非 JDBC catalog，能拿到连接串实际选中的库，与建库/建表口径一致。</p>
     */
    @Override
    public String getCatalog(JdbcTemplate jdbcTemplate) {
        try (QueryRunner qr = new QueryRunner(jdbcTemplate)) {
            return (String) qr.query("SELECT DATABASE()", rs -> {
                if (rs.next()) return rs.getString(1);
                return null;
            });
        }
    }

    /** 表清单查询：information_schema.TABLES，按当前库过滤，附带表注释 */
    @Override
    public String tableListSql() {
        return "SELECT TABLE_NAME, TABLE_COMMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA = ?";
    }

    /** 表清单 ResultSet → TableInfo 列表；TABLE_COMMENT 为 null 时归一为空串，避免下游判空分歧 */
    @Override
    public ResultSetHandler<List<TableInfo>> tableListHandler() {
        return rs -> {
            List<TableInfo> list = new ArrayList<>();
            while (rs.next()) {
                TableInfo tableInfo = new TableInfo();
                tableInfo.setTableName(rs.getString("TABLE_NAME"));
                tableInfo.setComment(rs.getString("TABLE_COMMENT") != null ? rs.getString("TABLE_COMMENT") : "");
                list.add(tableInfo);
            }
            return list;
        };
    }

    /**
     * 列信息查询：从 information_schema.COLUMNS 取回列定义所需字段。
     * <p>关键列 COLUMN_TYPE 会带回 MySQL 原生形态（整数列含显示宽度，如 int(11)），这正是
     * normalize 必须剥宽度才能与实体侧对齐的源头；{@code ORDER BY ORDINAL_POSITION} 保证列按建表顺序返回，
     * 与实体侧的列顺序可比（SchemaDriftIT 的快照正是按此顺序逐行对拍）。</p>
     */
    @Override
    public String columnListSql() {
        return "SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT, COLUMN_KEY, EXTRA "
                + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? "
                + "ORDER BY ORDINAL_POSITION";
    }

    /**
     * 列 ResultSet → ColumnInfo：只填库端可读到的属性（类型字符串、可空、默认值、注释、是否主键）。
     * <p>注意此处 {@code setType} 存的是原生 COLUMN_TYPE（带显示宽度），逻辑类型 sqlType 留空——
     * 库端只有物理形态，判定漂移时靠 normalize 而非 sqlType 渲染。主键识别用 {@code COLUMN_KEY='PRI'}。</p>
     */
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

    /**
     * 索引查询：information_schema.STATISTICS 每个索引列一行，按 INDEX_NAME、SEQ_IN_INDEX 排序，
     * 便于 handler 依序把同一索引的多个列聚合成有序列表。
     */
    @Override
    public String indexListSql() {
        return "SELECT INDEX_NAME, COLUMN_NAME, NON_UNIQUE, SEQ_IN_INDEX "
                + "FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? "
                + "ORDER BY INDEX_NAME, SEQ_IN_INDEX";
    }

    /**
     * 索引 ResultSet → IndexInfo 列表：STATISTICS 一列一行，用 LinkedHashMap 按索引名分组并保留列顺序。
     * <p>主键索引（PRIMARY）在此跳过：它已由 columnListHandler 通过 COLUMN_KEY='PRI' 表达，
     * 若这里再收一遍会让 diff 把主键误当成普通索引处理。</p>
     */
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
