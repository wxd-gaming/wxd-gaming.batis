package com.wxdgaming.batis.sql.mysql;

import com.wxdgaming.batis.model.TableInfo;
import com.wxdgaming.batis.sql.JdbcTemplate;
import com.wxdgaming.batis.sql.QueryRunner;
import com.wxdgaming.batis.sql.TableDropHandler;
import lombok.extern.slf4j.Slf4j;

/**
 * MySQL 删除表处理器
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-28
 **/
@Slf4j
public class MysqlTableDropHandler implements TableDropHandler {

    @Override
    public void doAction(JdbcTemplate jdbcTemplate, TableInfo tableInfo) {
        String sql = "DROP TABLE IF EXISTS `" + tableInfo.getTableName().toLowerCase() + "`;";
        log.debug("删除表sql:\n{}", sql);
        try (QueryRunner queryRunner = new QueryRunner(jdbcTemplate)) {
            queryRunner.execute(sql);
        }
    }

}
