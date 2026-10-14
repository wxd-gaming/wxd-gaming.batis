package com.wxdgaming.batis.sql.pgsql;

import com.wxdgaming.batis.model.TableInfo;
import com.wxdgaming.batis.sql.JdbcTemplate;
import com.wxdgaming.batis.sql.QueryRunner;
import com.wxdgaming.batis.sql.TableTruncateHandler;
import lombok.extern.slf4j.Slf4j;

/**
 * PostgreSQL 截断表处理器
 * <p>只清空数据、保留表结构；与 {@link PgsqlTableDropHandler}（结构一并删除）相对。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-14
 **/
@Slf4j
public class PgsqlTableTruncateHandler implements TableTruncateHandler {

    /** 无状态处理器，供测试与 template 复用同一实例 */
    public static final PgsqlTableTruncateHandler INSTANCE = new PgsqlTableTruncateHandler();

    /**
     * 执行 {@code TRUNCATE TABLE "t" RESTART IDENTITY}。
     * <p>与 MySQL 的 TRUNCATE 一样是 DDL：隐式提交、比无 WHERE 的 DELETE 快，测试清场常用。</p>
     * <p>{@code RESTART IDENTITY} 是为了对齐 MySQL 行为而<b>必须</b>带的：MySQL 的 TRUNCATE 会把
     * AUTO_INCREMENT 归零，PG 默认只清行、把序列留在原值上。不带这一句的话，同一套"截断后重插、
     * 断言 id 从 1 开始"的用例在两个方言上结果不同，业务侧也会看到 id 莫名跳号。</p>
     * <p>刻意不加 {@code CASCADE}：PG 的 TRUNCATE 遇到被外键引用的表会直接报错，加 CASCADE 等于
     * 替调用者连带清空别人家的表——这是不可逆的数据损失，宁可让调用方显式处理。</p>
     */
    @Override
    public void doAction(JdbcTemplate jdbcTemplate, TableInfo tableInfo) {
        String sql = "TRUNCATE TABLE " + PgsqlIdentifier.quote(tableInfo.getTableName()) + " RESTART IDENTITY";
        log.debug("截断表sql:\n{}", sql);
        try (QueryRunner queryRunner = new QueryRunner(jdbcTemplate)) {
            queryRunner.execute(sql);
        }
    }

}
