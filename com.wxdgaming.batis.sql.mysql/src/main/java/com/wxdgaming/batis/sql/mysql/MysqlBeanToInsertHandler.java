package com.wxdgaming.batis.sql.mysql;

import com.wxdgaming.batis.annotation.GenerationType;
import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.TableInfo;
import com.wxdgaming.batis.parser.EntityParser;
import com.wxdgaming.batis.sql.IBeanToInsertHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

/**
 * MySQL 方言的 INSERT 处理器
 * <p>表名和列名使用反引号 `` ` `` 引用，全小写。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-28
 **/
@Slf4j
public class MysqlBeanToInsertHandler implements IBeanToInsertHandler {

    @Override
    public String buildInsertSql(Object bean) {
        TableInfo tableInfo = EntityParser.parse(bean.getClass());
        LinkedHashMap<String, ColumnInfo> columns = tableInfo.getColumns();

        List<String> colNames = new ArrayList<>();
        for (ColumnInfo col : columns.values()) {
            if (isIdentityPk(col)) continue;
            colNames.add("`" + col.getColumnName().toLowerCase() + "`");
        }

        String cols = String.join(", ", colNames);
        String placeholders = colNames.stream()
                .map(c -> "?")
                .collect(Collectors.joining(", "));

        String sql = "INSERT INTO `" + tableInfo.getTableName().toLowerCase() + "` ("
                + cols + ") VALUES (" + placeholders + ")";
        log.debug("构建 INSERT sql:\n{}", sql);
        return sql;
    }

    @Override
    public Object[] buildInsertParams(Object bean) {
        TableInfo tableInfo = EntityParser.parse(bean.getClass());
        List<Object> params = new ArrayList<>();
        for (ColumnInfo col : tableInfo.getColumns().values()) {
            if (isIdentityPk(col)) continue;
            params.add(BeanValueUtil.convertToDbValue(
                    BeanValueUtil.readFieldValue(bean, col), col));
        }
        return params.toArray();
    }

    @Override
    public ColumnInfo getIdentityColumn(Object bean) {
        TableInfo tableInfo = EntityParser.parse(bean.getClass());
        for (ColumnInfo col : tableInfo.getColumns().values()) {
            if (isIdentityPk(col)) return col;
        }
        return null;
    }

    @Override
    public void writeGeneratedKey(Object bean, Number genId) {
        ColumnInfo identityColumn = getIdentityColumn(bean);
        if (identityColumn != null && genId != null) {
            BeanValueUtil.writeGeneratedKey(bean, identityColumn, genId);
        }
    }

    private boolean isIdentityPk(ColumnInfo col) {
        return col.isPrimaryKey() && col.getGenerationType() == GenerationType.IDENTITY;
    }
}
