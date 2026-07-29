package com.wxdgaming.batis.sql;

import com.wxdgaming.batis.model.TableInfo;

/**
 * 删除数据构建接口
 * <p>各数据库方言通过此接口提供差异化的 DELETE 实现（如标识符引用风格）。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-28
 **/
public interface IDeleteHandler {

    /** 构建 DELETE 语句（按当前 bean 的主键值定位删除） */
    String buildDeleteByIdSql(Object bean);

    /** 提取 DELETE 预编译参数数组（主键值） */
    Object[] buildDeleteByIdParams(Object bean);

    /** 构建清空全表的 DELETE 语句 */
    String buildDeleteAllSql(TableInfo tableInfo);

}
