package com.wxdgaming.batis.sql;

import com.wxdgaming.batis.DbTemplate;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.Getter;

/**
 * jdbc 操作
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-24 17:32
 **/
@Getter
public class JdbcTemplate extends DbTemplate {

    private final HikariDataSource dataSource;

    public JdbcTemplate(HikariConfig config) {
        this(new HikariDataSource(config));
    }

    public JdbcTemplate(HikariDataSource dataSource) {
        this.dataSource = dataSource;
    }

}
