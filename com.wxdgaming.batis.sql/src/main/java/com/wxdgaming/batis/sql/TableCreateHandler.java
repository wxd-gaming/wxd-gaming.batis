package com.wxdgaming.batis.sql;

import com.wxdgaming.batis.model.TableInfo;

/**
 * sql构建器
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-24 17:17
 **/
public interface TableCreateHandler {

    void doAction(JdbcTemplate jdbcTemplate, TableInfo tableInfo);

}
