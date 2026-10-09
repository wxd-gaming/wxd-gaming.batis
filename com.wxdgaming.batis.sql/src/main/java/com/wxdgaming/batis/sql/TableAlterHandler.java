package com.wxdgaming.batis.sql;

import com.wxdgaming.batis.model.TableInfo;

/**
 * 表结构同步处理器（方言 SPI）
 * <p>把实体定义与库端现状做 diff，生成并执行必要的 ALTER 语句。</p>
 * <p>与 {@link TableCreateHandler} 的区别是它没有"只构建不执行"的入口：
 * 差异必须读库端结构才算得出来，语句和连接天然绑在一起。</p>
 * <p>实现方必须遵守的幂等约定：本方法会在每次 {@code checkTable} 时被调用，
 * 结构没变化时必须一条语句都不发（包括不发空 ALTER）。否则每次启动都跑一遍 DDL，
 * 既拖慢启动，又可能在运行中的表上造成不必要的元数据锁。</p>
 * <p>关于删除口径的约定（实现方要与调用方一致理解）：实体里已移除的<em>列</em>不做 DROP，
 * 保留原样以防误删生产数据；实体里已移除的<em>索引</em>则会 DROP，因为索引不承载数据，
 * 留着只会拖慢写入。这一取舍是行为契约的一部分，改动它等于改动所有调用方的数据预期。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-24 17:17
 **/
public interface TableAlterHandler {

    /**
     * 比对实体表结构与库端缓存并执行必要的变更。
     * <p>库端现状从 {@code jdbcTemplate} 的表结构缓存读取；缓存为空或表不存在时
     * 直接返回——那种情况下建表流程（{@link TableCreateHandler}）会接手。</p>
     * <p>列是否变化要用 {@link ColumnTypeDefHandler} 的规格化结果比较，不能比原始字符串，
     * 否则同义写法（方言的显示宽度、大小写、空格）会被判成变更。</p>
     * <p>执行完成后调用方会重新加载表结构缓存，因此实现里无需自行同步缓存。</p>
     *
     * @param jdbcTemplate 提供库端结构缓存与执行连接的模板
     * @param tableInfo    实体侧解析出的目标结构
     */
    void doAction(JdbcTemplate jdbcTemplate, TableInfo tableInfo);

}
