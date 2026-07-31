package com.wxdgaming.batis.sql.mysql;

import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.TableInfo;
import com.wxdgaming.batis.parser.EntityParser;
import com.wxdgaming.batis.sql.BeanToUpdateHandler;
import com.wxdgaming.batis.sql.BeanValueUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * MySQL 方言的 UPDATE 处理器
 * <p>表名和列名使用反引号 `` ` `` 引用，全小写。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-28
 **/
@Slf4j
public class MysqlBeanToUpdateHandler implements BeanToUpdateHandler {

    @Override
    public String buildUpdateSql(Object bean) {
        TableInfo tableInfo = EntityParser.parse(bean.getClass());
        LinkedHashMap<String, ColumnInfo> columns = tableInfo.getColumns();

        List<String> setCols = new ArrayList<>();
        List<String> whereCols = new ArrayList<>();

        for (ColumnInfo col : columns.values()) {
            if (col.isPrimaryKey()) {
                whereCols.add("`" + col.getColumnName().toLowerCase() + "` = ?");
            } else {
                setCols.add("`" + col.getColumnName().toLowerCase() + "` = ?");
            }
        }

        if (whereCols.isEmpty()) {
            throw new IllegalArgumentException(
                    "实体 " + bean.getClass().getSimpleName() + " 未定义主键，无法执行 update");
        }

        String sql = "UPDATE `" + tableInfo.getTableName().toLowerCase() + "` SET "
                + String.join(", ", setCols) + " WHERE " + String.join(" AND ", whereCols);
        log.debug("构建 UPDATE sql:\n{}", sql);
        return sql;
    }

    @Override
    public Object[] buildUpdateParams(Object bean) {
        TableInfo tableInfo = EntityParser.parse(bean.getClass());
        LinkedHashMap<String, ColumnInfo> columns = tableInfo.getColumns();

        List<Object> setParams = new ArrayList<>();
        List<Object> whereParams = new ArrayList<>();

        for (ColumnInfo col : columns.values()) {
            Object dbValue = BeanValueUtil.convertToDbValue(
                    BeanValueUtil.readFieldValue(bean, col), col);
            if (col.isPrimaryKey()) {
                whereParams.add(dbValue);
            } else {
                setParams.add(dbValue);
            }
        }

        // SET 参数在前，WHERE 参数在后
        setParams.addAll(whereParams);
        return setParams.toArray();
    }
}
