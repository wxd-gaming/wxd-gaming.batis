package com.wxdgaming.batis.sql;

import com.wxdgaming.batis.model.TableInfo;

/**
 * 建表处理器（方言 SPI）
 * <p>把实体解析出的 {@link TableInfo} 变成一张真实存在的表。</p>
 * <p>四个 DDL 处理器里只有本接口额外暴露 {@link #buildSql(TableInfo)}：建表语句完全由
 * 实体元数据推导，不依赖库端现状，所以"预览语句"和"执行语句"能分开，测试与日志
 * 可以直接拿到待执行的 DDL 文本核对。ALTER 做不到这点——它必须先看到库端的表结构
 * 才能算出差异，语句只能连着连接一起产生，因此那三个接口只留 doAction。</p>
 * <p>调用方约定：{@code JdbcTemplate#checkTable} 在建表成功后会重新全量加载表结构缓存，
 * 实现方不必自己维护缓存，但必须保证语句可安全重复执行。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-24 17:17
 **/
public interface TableCreateHandler {

    /**
     * 执行建表
     * <p>实现方必须保证：语句幂等（表已存在时不报错也不覆盖既有结构，
     * 因为 checkTable 在缓存缺失时会重新走这条路径）；列物理类型一律向
     * {@link ColumnTypeDefHandler} 索取，不要在本接口里内联类型字面量——
     * 那样每接一种数据库都要改一遍建表代码。</p>
     * <p>执行失败交给 {@link SqlExceptionHandler} 翻译，实现自身不再包装异常。</p>
     *
     * @param jdbcTemplate 执行 DDL 的模板（连接与事务由它统一管理）
     * @param tableInfo    实体解析得到的目标表结构
     */
    void doAction(JdbcTemplate jdbcTemplate, TableInfo tableInfo);

    /**
     * 只构建语句、不执行，用于预览、日志与测试断言。
     * <p>没有模板可用，因此实现方要退回自己方言的默认 {@link ColumnTypeDefHandler}；
     * 这也意味着本方法的产物不一定等于 {@code doAction} 实际执行的语句——
     * 调用方若注入了别的类型处理器，只有 doAction 会跟随。</p>
     *
     * @param tableInfo 实体解析得到的目标表结构
     * @return 完整建表语句
     */
    String buildSql(TableInfo tableInfo);
}
