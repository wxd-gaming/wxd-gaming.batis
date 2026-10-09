package com.wxdgaming.batis.sql;

import com.wxdgaming.batis.model.TableInfo;

/**
 * 删除表的处理器（方言 SPI）
 * <p>结构与数据一起消失，属于不可逆操作，因此只由显式调用触发，
 * 不参与 {@code checkTable} 的自动同步流程。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-28
 **/
public interface TableDropHandler {

    /**
     * 执行删表。
     * <p>实现方必须保证表不存在时不报错（幂等），否则重复调用或并发调用会把一次
     * 正常的清理变成异常。</p>
     * <p>执行后调用方会清空表结构缓存，实现里不要自行改动缓存。</p>
     * <p>与 {@link BeanToDeleteHandler#buildDeleteAllSql} 的分工：那才是"清数据留结构"，
     * 本方法把结构（含索引、自增值）一并销毁。</p>
     *
     * @param jdbcTemplate 执行 DDL 的模板
     * @param tableInfo    目标表元数据
     */
    void doAction(JdbcTemplate jdbcTemplate, TableInfo tableInfo);

}
