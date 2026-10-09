package com.wxdgaming.batis.sql.mysql;

import com.wxdgaming.batis.model.TableInfo;
import com.wxdgaming.batis.sql.JdbcTemplate;
import com.wxdgaming.batis.sql.QueryRunner;
import com.wxdgaming.batis.sql.TableTruncateHandler;
import lombok.extern.slf4j.Slf4j;

/**
 * MySQL 截断表处理器
 * <p>只清空数据、保留表结构；与 {@link MysqlTableDropHandler}（结构一并删除）相对。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-28
 **/
@Slf4j
public class MysqlTableTruncateHandler implements TableTruncateHandler {

    /**
     * 执行 {@code TRUNCATE TABLE `t`}。
     * <p>MySQL 中 TRUNCATE 是 DDL：隐式提交、不可回滚、通过删表重建实现，因此比无 WHERE 的 DELETE
     * 快得多且会重置 AUTO_INCREMENT 计数器——测试清场常用它；需要在事务里逐行删除并保留计数时
     * 应改用 {@code MysqlBeanToDeleteHandler#buildDeleteAllSql}。</p>
     */
    @Override
    public void doAction(JdbcTemplate jdbcTemplate, TableInfo tableInfo) {
        String sql = "TRUNCATE TABLE `" + tableInfo.getTableName().toLowerCase() + "`;";
        log.debug("截断表sql:\n{}", sql);
        try (QueryRunner queryRunner = new QueryRunner(jdbcTemplate)) {
            queryRunner.execute(sql);
        }
    }

}
