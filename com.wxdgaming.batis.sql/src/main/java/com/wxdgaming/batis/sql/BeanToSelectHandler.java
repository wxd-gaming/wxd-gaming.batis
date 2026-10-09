package com.wxdgaming.batis.sql;

import com.wxdgaming.batis.model.TableInfo;

import java.util.List;

/**
 * SELECT SQL 构建接口
 * <p>各数据库方言通过此接口提供差异化的 SELECT 构建（如标识符引用风格）。</p>
 * <p>默认方法按 MySQL 风格实现（反引号小写标识符 + LIMIT ? OFFSET ?），
 * 其他方言只需覆盖有差异的方法即可支撑查询 DSL 的分页/计数构建。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public interface BeanToSelectHandler {

    /** 构建按主键查询的 SQL（形如 SELECT * FROM `table` WHERE `pk` = ?） */
    String buildSelectByIdSql(TableInfo tableInfo);

    /** 构建查询全部记录的 SQL（形如 SELECT * FROM `table`） */
    String buildSelectAllSql(TableInfo tableInfo);

    /** 标识符引用（默认 MySQL 反引号 + 小写；pgsql 覆盖为双引号） */
    default String quote(String identifier) {
        return "`" + identifier.toLowerCase() + "`";
    }

    /**
     * 追加分页子句并把分页值按顺序加入 params
     * <p>参数顺序不变式的一部分：limit 在 offset 之前。</p>
     */
    default void appendPaging(StringBuilder sql, List<Object> params, Integer limit, Integer offset) {
        if (limit != null && limit > 0) {
            sql.append(" LIMIT ?");
            params.add(limit);
        }
        if (offset != null && offset > 0) {
            sql.append(" OFFSET ?");
            params.add(offset);
        }
    }

    /** 构建计数语句主体（不含 WHERE 后缀），默认 SELECT COUNT(*) FROM 表 */
    default String buildCountSql(TableInfo tableInfo) {
        return "SELECT COUNT(*) FROM " + quote(tableInfo.getTableName());
    }

}
