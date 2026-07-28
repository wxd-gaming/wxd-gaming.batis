package com.wxdgaming.batis.sql;

import com.wxdgaming.batis.model.ColumnInfo;

/**
 * INSERT SQL 构建与参数提取接口
 * <p>各数据库方言通过此接口提供差异化的 INSERT 实现（如标识符引用风格）。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-28
 **/
public interface IBeanToInsertHandler {

    /** 构建 INSERT INTO 语句 */
    String buildInsertSql(Object bean);

    /** 提取 INSERT 预编译参数数组 */
    Object[] buildInsertParams(Object bean);

    /** 获取自增主键列，没有自增主键时返回 null */
    ColumnInfo getIdentityColumn(Object bean);

    /** 将自增主键值回写到 bean */
    void writeGeneratedKey(Object bean, Number genId);
}
