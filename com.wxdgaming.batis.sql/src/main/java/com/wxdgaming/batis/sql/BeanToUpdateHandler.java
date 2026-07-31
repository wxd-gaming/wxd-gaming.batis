package com.wxdgaming.batis.sql;

/**
 * UPDATE SQL 构建与参数提取接口
 * <p>各数据库方言通过此接口提供差异化的 UPDATE 实现（如标识符引用风格）。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-28
 **/
public interface BeanToUpdateHandler {

    /** 构建 UPDATE 语句 */
    String buildUpdateSql(Object bean);

    /** 提取 UPDATE 预编译参数数组（SET 参数在前，WHERE 参数在后） */
    Object[] buildUpdateParams(Object bean);
}
