package com.wxdgaming.batis.sql;

import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.IndexInfo;

import java.util.List;

/**
 * 数据库元数据提供者接口
 * <p>各数据库方言通过实现此接口提供表结构查询的 SQL 和 ResultSet 解析逻辑</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-24
 **/
public interface IDbMetaProvider {

    /** 获取当前数据库 catalog（schema）名 */
    String getCatalog(JdbcTemplate jdbcTemplate);

    /** SQL：查询当前 catalog 下所有表名 */
    String tableListSql();

    /** Handler：解析表列表 ResultSet → 表名列表 */
    ResultSetHandler<List<String>> tableListHandler();

    /** SQL：查询指定表的列信息 */
    String columnListSql();

    /** Handler：解析列信息 ResultSet → ColumnInfo 列表 */
    ResultSetHandler<List<ColumnInfo>> columnListHandler();

    /** SQL：查询指定表的索引信息 */
    String indexListSql();

    /** Handler：解析索引信息 ResultSet → IndexInfo 列表 */
    ResultSetHandler<List<IndexInfo>> indexListHandler();
}
