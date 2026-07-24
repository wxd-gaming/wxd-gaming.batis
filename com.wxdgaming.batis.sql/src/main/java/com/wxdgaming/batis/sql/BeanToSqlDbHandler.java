package com.wxdgaming.batis.sql;

import com.wxdgaming.batis.BeanToDbHandler;

/**
 * sql类型数据库
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-24 20:10
 **/
public interface BeanToSqlDbHandler<T extends JdbcTemplate> extends BeanToDbHandler<T> {
}
