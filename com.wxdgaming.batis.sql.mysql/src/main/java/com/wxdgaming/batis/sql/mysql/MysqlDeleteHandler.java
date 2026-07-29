package com.wxdgaming.batis.sql.mysql;

import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.TableInfo;
import com.wxdgaming.batis.parser.EntityParser;
import com.wxdgaming.batis.sql.IDeleteHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * MySQL 方言的 DELETE 处理器
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-28
 **/
@Slf4j
public class MysqlDeleteHandler implements IDeleteHandler {

    @Override
    public String buildDeleteByIdSql(Object bean) {
        TableInfo tableInfo = EntityParser.parse(bean.getClass());
        LinkedHashMap<String, ColumnInfo> columns = tableInfo.getColumns();

        List<String> whereCols = new ArrayList<>();
        for (ColumnInfo col : columns.values()) {
            if (col.isPrimaryKey()) {
                whereCols.add("`" + col.getColumnName().toLowerCase() + "` = ?");
            }
        }

        if (whereCols.isEmpty()) {
            throw new IllegalArgumentException(
                    "实体 " + bean.getClass().getSimpleName() + " 未定义主键，无法执行 deleteById");
        }

        String sql = "DELETE FROM `" + tableInfo.getTableName().toLowerCase()
                + "` WHERE " + String.join(" AND ", whereCols);
        log.debug("构建 DELETE BY ID sql:\n{}", sql);
        return sql;
    }

    @Override
    public Object[] buildDeleteByIdParams(Object bean) {
        TableInfo tableInfo = EntityParser.parse(bean.getClass());
        LinkedHashMap<String, ColumnInfo> columns = tableInfo.getColumns();

        List<Object> whereParams = new ArrayList<>();
        for (ColumnInfo col : columns.values()) {
            if (col.isPrimaryKey()) {
                whereParams.add(BeanValueUtil.convertToDbValue(
                        BeanValueUtil.readFieldValue(bean, col), col));
            }
        }
        return whereParams.toArray();
    }

    @Override
    public String buildDeleteAllSql(TableInfo tableInfo) {
        String sql = "DELETE FROM `" + tableInfo.getTableName().toLowerCase() + "`";
        log.debug("构建 DELETE ALL sql:\n{}", sql);
        return sql;
    }

}
