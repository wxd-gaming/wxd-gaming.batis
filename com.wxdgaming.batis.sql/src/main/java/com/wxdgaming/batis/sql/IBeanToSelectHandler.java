package com.wxdgaming.batis.sql;

import com.wxdgaming.batis.model.TableInfo;

/**
 * SELECT SQL 构建接口
 * <p>各数据库方言通过此接口提供差异化的 SELECT 构建（如标识符引用风格）。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-28
 **/
public interface IBeanToSelectHandler {

    /** 构建按主键查询的 SQL（形如 SELECT * FROM `table` WHERE `pk` = ?） */
    String buildSelectByIdSql(TableInfo tableInfo);

    /** 构建查询全部记录的 SQL（形如 SELECT * FROM `table`） */
    String buildSelectAllSql(TableInfo tableInfo);
}
