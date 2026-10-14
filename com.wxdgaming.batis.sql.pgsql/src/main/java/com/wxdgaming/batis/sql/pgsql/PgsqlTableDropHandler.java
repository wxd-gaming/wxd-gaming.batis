package com.wxdgaming.batis.sql.pgsql;

import com.wxdgaming.batis.model.TableInfo;
import com.wxdgaming.batis.sql.JdbcTemplate;
import com.wxdgaming.batis.sql.QueryRunner;
import com.wxdgaming.batis.sql.TableDropHandler;
import lombok.extern.slf4j.Slf4j;

/**
 * PostgreSQL 删除表处理器
 * <p>删除整张表的结构与数据；与 {@link PgsqlTableTruncateHandler}（只清数据保留结构）相对。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-14
 **/
@Slf4j
public class PgsqlTableDropHandler implements TableDropHandler {

    /** 无状态处理器，供测试与 template 复用同一实例 */
    public static final PgsqlTableDropHandler INSTANCE = new PgsqlTableDropHandler();

    /**
     * 执行 {@code DROP TABLE IF EXISTS "t"}。
     * <p>IF EXISTS 让删除幂等：表本就不存在时不报错，重复调用安全（框架启动/测试清场都会走这里）；
     * 表名与建表口径一致地小写加双引号，缓存失效由 JdbcTemplate 上层负责，本处理器只管执行 SQL。</p>
     * <p>同样不带 {@code CASCADE}：PG 会拒绝删除被外键引用的表（2BP01），而 CASCADE 是连带删除
     * 依赖对象（别人的表、视图、触发器），不该由框架代做决定。</p>
     */
    @Override
    public void doAction(JdbcTemplate jdbcTemplate, TableInfo tableInfo) {
        String sql = "DROP TABLE IF EXISTS " + PgsqlIdentifier.quote(tableInfo.getTableName());
        log.debug("删除表sql:\n{}", sql);
        try (QueryRunner queryRunner = new QueryRunner(jdbcTemplate)) {
            queryRunner.execute(sql);
        }
    }

}
