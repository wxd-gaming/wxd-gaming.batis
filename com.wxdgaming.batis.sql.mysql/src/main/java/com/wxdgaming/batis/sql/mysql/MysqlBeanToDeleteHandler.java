package com.wxdgaming.batis.sql.mysql;

import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.TableInfo;
import com.wxdgaming.batis.parser.EntityParser;
import com.wxdgaming.batis.sql.BeanToDeleteHandler;
import com.wxdgaming.batis.sql.BeanValueHandler;
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
public class MysqlBeanToDeleteHandler implements BeanToDeleteHandler {

    private final BeanValueHandler valueHandler;

    /** 注入与写/读端共享的值转换协作者（主键参数也需按方言转换后再绑定） */
    public MysqlBeanToDeleteHandler(BeanValueHandler valueHandler) {
        this.valueHandler = valueHandler;
    }

    /**
     * 构建按主键删除语句：{@code DELETE FROM `t` WHERE `id` = ?}（复合主键以 AND 串联）。
     * <p>无主键实体直接抛异常：没有 WHERE 的 DELETE 等于清表，绝不能静默生成。</p>
     */
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

    /** 提取 WHERE 主键参数：与 buildDeleteByIdSql 的 ? 同序（按列声明顺序收集主键列），并做方言值转换 */
    @Override
    public Object[] buildDeleteByIdParams(Object bean) {
        TableInfo tableInfo = EntityParser.parse(bean.getClass());
        LinkedHashMap<String, ColumnInfo> columns = tableInfo.getColumns();

        List<Object> whereParams = new ArrayList<>();
        for (ColumnInfo col : columns.values()) {
            if (col.isPrimaryKey()) {
                whereParams.add(valueHandler.convertToDbValue(
                        valueHandler.readFieldValue(bean, col), col));
            }
        }
        return whereParams.toArray();
    }

    /**
     * 构建无 WHERE 的全表 DELETE（逐行清空数据）。
     * <p>与 TRUNCATE 的分工见 {@link MysqlTableTruncateHandler}：DELETE 逐行删除、事务内可回滚、
     * 触发行级触发器；TRUNCATE 是 DDL、隐式提交且通常重置自增计数器。由调用方按需选用，本方法只负责生成 SQL 文本。</p>
     */
    @Override
    public String buildDeleteAllSql(TableInfo tableInfo) {
        String sql = "DELETE FROM `" + tableInfo.getTableName().toLowerCase() + "`";
        log.debug("构建 DELETE ALL sql:\n{}", sql);
        return sql;
    }

}
