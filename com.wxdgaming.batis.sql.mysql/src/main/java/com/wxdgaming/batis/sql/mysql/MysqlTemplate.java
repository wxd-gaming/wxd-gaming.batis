package com.wxdgaming.batis.sql.mysql;

import com.wxdgaming.batis.sql.JdbcTemplate;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * mysql JdbcTemplate
 * <p>自动装配 MysqlDbMetaProvider、MysqlTableCreateHandler、MysqlTableAlterHandler</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-24 20:11
 **/
public class MysqlTemplate extends JdbcTemplate {

    public MysqlTemplate(HikariConfig config) {
        super(config);
        initDefaults();
    }

    public MysqlTemplate(HikariDataSource dataSource) {
        super(dataSource);
        initDefaults();
    }

    private void initDefaults() {
        setDbMetaProvider(new MysqlDbMetaProvider());
        setTableCreateHandler(new MysqlTableCreateHandler());
        setTableAlterHandler(new MysqlTableAlterHandler());
    }

}
