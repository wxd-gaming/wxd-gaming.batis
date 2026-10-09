package com.wxdgaming.batis.sql;

import com.wxdgaming.batis.exception.DataAccessException;

import java.sql.SQLException;

/**
 * SQL 异常翻译接口（方言 SPI）
 * <p>把 JDBC 的 {@link SQLException} 翻译成统一的 {@link DataAccessException} 层次，
 * 由各数据库方言模块提供精确实现（如 MySQL 数字错误码映射），
 * 未注册方言实现时由 {@link DefaultSqlExceptionHandler} 按 SQLState/关键字兜底。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public interface SqlExceptionHandler {

    /**
     * 翻译 SQLException
     *
     * @param ex     原始 SQL 异常
     * @param sql    触发异常的 SQL（可为 null，仅用于诊断信息）
     * @param params 绑定参数（可为 null）
     * @return 统一的 DataAccessException 子类
     */
    DataAccessException translate(SQLException ex, String sql, Object[] params);

    /** 方言名，用于日志与诊断 */
    default String dialectName() {
        return "generic";
    }

}
