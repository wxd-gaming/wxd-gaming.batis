package com.wxdgaming.batis.sql.mysql;

import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.TableInfo;
import com.wxdgaming.batis.sql.BeanToSelectHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * MySQL 方言的 SELECT 处理器
 * <p>生成 MySQL 风格的 SELECT 语句（使用反引号引用标识符）。</p>
 * <p>接口 {@link BeanToSelectHandler} 的分页（LIMIT ? OFFSET ?）、标识符引用与 COUNT 默认方法本就是
 * MySQL 语义，故本实现只覆盖按主键/全表两条查询语句，其余差异点无需重写——这也是方言 SPI 的约定：
 * 只实现有差异的方法。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-28
 **/
public class MysqlBeanToSelectHandler implements BeanToSelectHandler {

    /**
     * 构建按主键查询：{@code SELECT * FROM `t` WHERE `pk` = ?}（复合主键以 AND 串联）。
     * <p>无主键无法安全定位单行，抛异常拒绝，与 INSERT/UPDATE/DELETE 的主键约束口径一致。</p>
     */
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

    /** 构建全表查询：{@code SELECT * FROM `t`}；排序/分页由查询 DSL 经接口默认方法追加 */
    @Override
    public String buildSelectAllSql(TableInfo tableInfo) {
        return "SELECT * FROM `" + tableInfo.getTableName().toLowerCase() + "`";
    }
}
