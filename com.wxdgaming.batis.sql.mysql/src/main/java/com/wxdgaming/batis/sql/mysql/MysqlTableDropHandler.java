package com.wxdgaming.batis.sql.mysql;

import com.wxdgaming.batis.model.TableInfo;
import com.wxdgaming.batis.sql.JdbcTemplate;
import com.wxdgaming.batis.sql.QueryRunner;
import com.wxdgaming.batis.sql.TableDropHandler;
import lombok.extern.slf4j.Slf4j;

/**
 * MySQL 删除表处理器
 * <p>删除整张表的结构与数据；与 {@link MysqlTableTruncateHandler}（只清数据保留结构）相对。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-28
 **/
@Slf4j
public class MysqlTableDropHandler implements TableDropHandler {

    /**
     * 执行 {@code DROP TABLE IF EXISTS `t`}。
     * <p>IF EXISTS 让删除幂等：表本就不存在时不报错，重复调用安全（框架启动/测试清场都会走这里）；
     * 表名与建表口径一致地小写加反引号。缓存失效由 JdbcTemplate 上层负责，本处理器只管执行 SQL。</p>
     */
    @Override
    public void doAction(JdbcTemplate jdbcTemplate, TableInfo tableInfo) {
        String sql = "DROP TABLE IF EXISTS `" + tableInfo.getTableName().toLowerCase() + "`;";
        log.debug("删除表sql:\n{}", sql);
        try (QueryRunner queryRunner = new QueryRunner(jdbcTemplate)) {
            queryRunner.execute(sql);
        }
    }

}
