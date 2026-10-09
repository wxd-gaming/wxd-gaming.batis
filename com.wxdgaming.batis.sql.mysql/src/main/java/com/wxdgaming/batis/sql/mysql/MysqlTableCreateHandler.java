package com.wxdgaming.batis.sql.mysql;

import com.wxdgaming.batis.annotation.GenerationType;
import com.wxdgaming.batis.model.*;
import com.wxdgaming.batis.sql.*;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * mysql表构建
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-24 17:19
 **/
@Slf4j
public class MysqlTableCreateHandler implements TableCreateHandler {

    public static final MysqlTableCreateHandler INSTANCE = new MysqlTableCreateHandler();

    @Override
    public void doAction(JdbcTemplate jdbcTemplate, TableInfo tableInfo) {
        String buildSql = buildSql(tableInfo, typeDef(jdbcTemplate));
        log.debug("创建表sql:\n{}", buildSql);
        try (QueryRunner queryRunner = new QueryRunner(jdbcTemplate)) {
            queryRunner.update(buildSql);
        }
    }

    @Override
    public String buildSql(TableInfo tableInfo) {
        return buildSql(tableInfo, MysqlColumnTypeDefHandler.INSTANCE);
    }

    /** 优先使用 template 注入的方言实现，未注入时回退 MySQL 默认实现 */
    private static ColumnTypeDefHandler typeDef(JdbcTemplate jdbcTemplate) {
        ColumnTypeDefHandler handler = jdbcTemplate.getColumnTypeDefHandler();
        return handler != null ? handler : MysqlColumnTypeDefHandler.INSTANCE;
    }

    private String buildSql(TableInfo tableInfo, ColumnTypeDefHandler typeDef) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("CREATE TABLE IF NOT EXISTS `").append(tableInfo.getTableName().toLowerCase()).append("` (\n");

        List<String> columnDefs = new ArrayList<>();

        // 1. 列定义
        for (ColumnInfo col : tableInfo.getColumns().values()) {
            columnDefs.add(buildColumnDef(col, typeDef));
        }

        // 2. 主键
        List<String> pkColumns = new ArrayList<>();
        for (ColumnInfo col : tableInfo.getColumns().values()) {
            if (col.isPrimaryKey()) {
                pkColumns.add(col.getColumnName().toLowerCase());
            }
        }
        if (!pkColumns.isEmpty()) {
            columnDefs.add("  PRIMARY KEY (`" + String.join("`, `", pkColumns) + "`)");
        }

        // 3. 索引
        if (tableInfo.getIndexes() != null) {
            for (IndexInfo index : tableInfo.getIndexes()) {
                String indexType = index.isUnique() ? "UNIQUE INDEX" : "INDEX";
                List<String> quotedCols = new ArrayList<>();
                for (String colName : index.getColumns()) {
                    quotedCols.add("`" + colName.toLowerCase() + "`");
                }
                columnDefs.add("  " + indexType + " `" + index.getName().toLowerCase() + "` (" + String.join(", ", quotedCols) + ")");
            }
        }

        sb.append(String.join(",\n", columnDefs));
        sb.append("\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

        if (tableInfo.getComment() != null && !tableInfo.getComment().isEmpty()) {
            sb.append(" COMMENT='").append(escapeComment(tableInfo.getComment())).append("'");
        }

        sb.append(";");
        return sb.toString();
    }

    /** 构建单列定义 */
    private String buildColumnDef(ColumnInfo col, ColumnTypeDefHandler typeDef) {
        StringBuilder sb = new StringBuilder(64);
        sb.append("  `").append(col.getColumnName().toLowerCase()).append("` ").append(typeDef.renderType(col));

        if (!col.isNullable()) {
            sb.append(" NOT NULL");
        }

        if (col.getDefaultValue() != null && !col.getDefaultValue().isEmpty()) {
            sb.append(" DEFAULT ").append(col.getDefaultValue());
        }

        if (col.getGenerationType() == GenerationType.IDENTITY && col.isPrimaryKey()) {
            sb.append(" AUTO_INCREMENT");
        }

        if (col.getComment() != null && !col.getComment().isEmpty()) {
            sb.append(" COMMENT '").append(escapeComment(col.getComment())).append("'");
        }

        return sb.toString();
    }

    /** 转义 SQL 注释中的特殊字符 */
    private static String escapeComment(String comment) {
        return comment.replace("'", "\\'").replace("\n", "\\n");
    }
}
