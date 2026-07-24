package com.wxdgaming.batis.sql.mysql;

import com.wxdgaming.batis.annotation.GenerationType;
import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.IndexInfo;
import com.wxdgaming.batis.model.TableInfo;
import com.wxdgaming.batis.sql.TableCreateHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * mysql表构建
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-24 17:19
 **/
public class MysqlTableCreateHandler implements TableCreateHandler {

    @Override
    public void doAction(TableInfo tableInfo) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("CREATE TABLE IF NOT EXISTS `").append(tableInfo.getTableName()).append("` (\n");

        List<String> columnDefs = new ArrayList<>();

        // 1. 列定义
        for (ColumnInfo col : tableInfo.getColumns().values()) {
            columnDefs.add(buildColumnDef(col));
        }

        // 2. 主键
        List<String> pkColumns = new ArrayList<>();
        for (ColumnInfo col : tableInfo.getColumns().values()) {
            if (col.isPrimaryKey()) {
                pkColumns.add(col.getColumnName());
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
                    quotedCols.add("`" + colName + "`");
                }
                columnDefs.add("  " + indexType + " `" + index.getName() + "` (" + String.join(", ", quotedCols) + ")");
            }
        }

        sb.append(String.join(",\n", columnDefs));
        sb.append("\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

        if (tableInfo.getComment() != null && !tableInfo.getComment().isEmpty()) {
            sb.append(" COMMENT='").append(escapeComment(tableInfo.getComment())).append("'");
        }

        sb.append(";");
        System.out.println(sb.toString());
    }

    /** 构建单列定义 */
    private String buildColumnDef(ColumnInfo col) {
        StringBuilder sb = new StringBuilder(64);
        sb.append("  `").append(col.getColumnName()).append("` ").append(col.getType());

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
