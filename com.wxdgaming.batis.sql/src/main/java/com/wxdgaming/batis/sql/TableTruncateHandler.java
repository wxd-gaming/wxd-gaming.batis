package com.wxdgaming.batis.sql;

import com.wxdgaming.batis.model.TableInfo;

/**
 * 截断表（TRUNCATE）的处理器（方言 SPI）
 * <p>只清空数据、保留表结构；与 {@link TableDropHandler}（结构一并删除）相对。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-28
 **/
public interface TableTruncateHandler {

    /**
     * 执行截断。
     * <p>选它而不是无 WHERE 的 DELETE 的理由要写清楚，因为两者的差异会直接影响调用方：
     * 多数数据库把 TRUNCATE 当 DDL 处理——隐式提交、不可回滚、通常会重置自增计数。
     * 所以它适合测试清场和"整表报废重来"，不适合放在需要原子性的业务事务里；
     * 那种场景应改用 {@link BeanToDeleteHandler#buildDeleteAllSql}，逐行删除、可回滚、不重置计数。</p>
     * <p>实现方不必改动表结构缓存——结构没变，缓存依旧有效；语句失败交由
     * {@link SqlExceptionHandler} 翻译成统一异常。</p>
     *
     * @param jdbcTemplate 执行语句的模板
     * @param tableInfo    目标表元数据
     */
    void doAction(JdbcTemplate jdbcTemplate, TableInfo tableInfo);

}
