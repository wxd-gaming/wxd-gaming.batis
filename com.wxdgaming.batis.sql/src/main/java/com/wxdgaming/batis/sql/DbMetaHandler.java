package com.wxdgaming.batis.sql;

import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.IndexInfo;
import com.wxdgaming.batis.model.TableInfo;

import java.util.List;

/**
 * 数据库元数据处理器接口（方言 SPI）
 * <p>各数据库方言通过实现此接口提供表结构查询的 SQL 和 ResultSet 解析逻辑。</p>
 * <p>拆成"SQL 归 SQL、解析归解析"的三对方法，是因为调用方（{@code JdbcTemplate#loadAllTables}）
 * 要自己控制执行时机、参数与连接，方言只交出语句和读取方式。这里有一条容易踩空的约定：
 * <b>绑定参数的个数与顺序由调用方固定</b>——{@link #tableListSql()} 接一个参数（catalog），
 * {@link #columnListSql()} 与 {@link #indexListSql()} 接两个参数（catalog、表名）。
 * 方言若改成别的占位符数量或顺序，加载就会静默查错表或抛参数越界，而且只在换数据库时暴露。</p>
 * <p>本接口读回的结构是全表加载的结果，会被缓存成"库端快照"供 DDL 漂移比对，
 * 因此解析必须<em>只填库端能读到的字段</em>（物理类型串、可空、默认值、注释、是否主键），
 * 不要顺手补上推断值——一旦掺进猜测，diff 就会把猜测当成库端事实而反复变更。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-24
 **/
public interface DbMetaHandler {

    /**
     * 获取当前数据库 catalog（schema）名
     * <p>它同时充当后续三个查询的过滤条件，所以必须与建库建表用的是同一口径，
     * 否则会读到另一个库的结构。</p>
     *
     * @param jdbcTemplate 执行查询的模板（可能已绑定当前事务连接）
     * @return catalog 名；取不到时返回 null，调用方会直接放弃加载而不建空缓存
     */
    String getCatalog(JdbcTemplate jdbcTemplate);

    /**
     * SQL：查询当前 catalog 下所有表名及表注释
     *
     * @return 带一个占位符（catalog）的语句
     */
    String tableListSql();

    /**
     * Handler：解析表列表 ResultSet → TableInfo 列表（填充 tableName 与 comment）
     * <p>实现方要保证 comment 的空值归一（null 与空串只留一种），否则漂移比较里
     * "无注释"会因两侧归一方式不同而判成变更。</p>
     *
     * @return 表列表解析器
     */
    ResultSetHandler<List<TableInfo>> tableListHandler();

    /**
     * SQL：查询指定表的列信息
     * <p>必须按列在表中的定义顺序返回（如按 ORDINAL_POSITION 排序）：
     * 漂移检测是逐行按顺序对拍的，顺序不稳会导致整张表被误判为结构变化。</p>
     *
     * @return 带两个占位符（catalog、表名）的语句
     */
    String columnListSql();

    /**
     * Handler：解析列信息 ResultSet → ColumnInfo 列表
     * <p>关键约定：{@code type} 存库端原生物理形态（可能带方言的显示宽度等修饰），
     * 逻辑类型留空——库端只有物理形态，判定漂移时靠 {@link ColumnTypeDefHandler#normalize}
     * 与实体侧渲染结果对齐，不能在这里反推逻辑类型。</p>
     *
     * @return 列信息解析器
     */
    ResultSetHandler<List<ColumnInfo>> columnListHandler();

    /**
     * SQL：查询指定表的索引信息
     * <p>多数元数据视图是"一个索引列一行"，因此要按索引名与列内序号排序，
     * 让 handler 能依序把同一索引的多个列聚成有序列表（列顺序对复合索引是有语义的）。</p>
     *
     * @return 带两个占位符（catalog、表名）的语句
     */
    String indexListSql();

    /**
     * Handler：解析索引信息 ResultSet → IndexInfo 列表
     * <p>主键不要在这里重复收录：它已经由 {@link #columnListHandler()} 的主键标记表达，
     * 再收一遍会让 diff 把主键当成普通索引，进而生成删主键的语句。</p>
     *
     * @return 索引信息解析器
     */
    ResultSetHandler<List<IndexInfo>> indexListHandler();
}
