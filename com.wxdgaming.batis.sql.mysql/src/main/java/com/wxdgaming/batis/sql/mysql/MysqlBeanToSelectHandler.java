package com.wxdgaming.batis.sql.mysql;

import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.TableInfo;
import com.wxdgaming.batis.sql.IBeanToSelectHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * MySQL 方言的 SELECT 处理器
 * <p>生成 MySQL 风格的 SELECT 语句（使用反引号引用标识符）。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-28
 **/
public class MysqlBeanToSelectHandler implements IBeanToSelectHandler {

    @Override
    public String buildSelectByIdSql(TableInfo tableInfo) {
        LinkedHashMap<String, ColumnInfo> columns = tableInfo.getColumns();
        List<String> whereCols = new ArrayList<>();
        for (ColumnInfo col : columns.values()) {
            if (col.isPrimaryKey()) {
                whereCols.add("`" + col.getColumnName().toLowerCase() + "` = ?");
            }
        }

        if (whereCols.isEmpty()) {
            throw new IllegalArgumentException(
                    "实体 " + tableInfo.getEntityClass().getSimpleName() + " 未定义主键，无法执行 queryById");
        }

        return "SELECT * FROM `" + tableInfo.getTableName().toLowerCase()
                + "` WHERE " + String.join(" AND ", whereCols);
    }

    @Override
    public String buildSelectAllSql(TableInfo tableInfo) {
        return "SELECT * FROM `" + tableInfo.getTableName().toLowerCase() + "`";
    }
}
