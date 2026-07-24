package com.wxdgaming.batis.sql;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 结果集处理器
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-24 17:33
 **/
@FunctionalInterface
public interface ResultSetHandler<R> {

    R handle(ResultSet rs) throws SQLException;

}
