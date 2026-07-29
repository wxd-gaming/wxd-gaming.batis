package com.wxdgaming.batis.sql.mysql;

import com.wxdgaming.batis.model.TableInfo;
import com.wxdgaming.batis.sql.JdbcTemplate;
import com.wxdgaming.batis.sql.QueryRunner;
import com.wxdgaming.batis.sql.TableTruncateHandler;
import lombok.extern.slf4j.Slf4j;

/**
 * MySQL 截断表处理器
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-28
 **/
@Slf4j
public class MysqlTableTruncateHandler implements TableTruncateHandler {

    @Override
    public void doAction(JdbcTemplate jdbcTemplate, TableInfo tableInfo) {
        String sql = "TRUNCATE TABLE `" + tableInfo.getTableName().toLowerCase() + "`;";
        log.debug("截断表sql:\n{}", sql);
        try (QueryRunner queryRunner = new QueryRunner(jdbcTemplate)) {
            queryRunner.execute(sql);
        }
    }

}
