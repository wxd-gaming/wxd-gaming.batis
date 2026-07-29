package com.wxdgaming.batis.sql;

import com.wxdgaming.batis.model.TableInfo;

/**
 * 截断表（TRUNCATE）的处理器
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-28
 **/
public interface TableTruncateHandler {

    void doAction(JdbcTemplate jdbcTemplate, TableInfo tableInfo);

}
