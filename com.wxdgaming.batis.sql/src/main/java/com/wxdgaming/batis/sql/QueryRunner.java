package com.wxdgaming.batis.sql;

import lombok.Getter;
import org.apache.commons.lang3.exception.ExceptionUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * 执行器
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-24 17:33
 **/
@Getter
public class QueryRunner {

    private JdbcTemplate jdbcTemplate;
    private Connection connection;

    public QueryRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public QueryRunner(Connection connection) {
        this.connection = connection;
    }

    /**
     * 获取数据库连接，优先使用外部注入的 connection，否则从 JdbcTemplate 的 DataSource 获取
     */
    public Connection getConnection() throws SQLException {
        if (connection != null) {
            return connection;
        }
        return jdbcTemplate.getDataSource().getConnection();
    }

    public <R> R query(String sql, Object[] args, ResultSetHandler<R> handler) {
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            if (args != null) {
                for (int i = 0; i < args.length; i++) {
                    stmt.setObject(i + 1, args[i]);
                }
            }
            try (ResultSet rs = stmt.executeQuery()) {
                return handler.handle(rs);
            }
        } catch (SQLException e) {
            throw ExceptionUtils.asRuntimeException(e);
        }
    }

    /**
     * 执行 SQL（DDL / DML），返回是否执行成功
     *
     * @param sql 任意 SQL 语句
     */
    public boolean execute(String sql) {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            return stmt.execute(sql);
        } catch (SQLException e) {
            throw ExceptionUtils.asRuntimeException(e);
        }
    }

    /**
     * 执行带参数的 SQL（DDL / DML），返回是否执行成功
     *
     * @param sql  带占位符的 SQL
     * @param args 参数数组
     */
    public boolean execute(String sql, Object[] args) {
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            if (args != null) {
                for (int i = 0; i < args.length; i++) {
                    stmt.setObject(i + 1, args[i]);
                }
            }
            return stmt.execute();
        } catch (SQLException e) {
            throw ExceptionUtils.asRuntimeException(e);
        }
    }

    /**
     * 执行 DML（INSERT / UPDATE / DELETE），返回影响的行数
     *
     * @param sql DML 语句
     */
    public int update(String sql) {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            return stmt.executeUpdate(sql);
        } catch (SQLException e) {
            throw ExceptionUtils.asRuntimeException(e);
        }
    }

    /**
     * 执行带参数的 DML（INSERT / UPDATE / DELETE），返回影响的行数
     *
     * @param sql  带占位符的 DML 语句
     * @param args 参数数组
     */
    public int update(String sql, Object[] args) {
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            if (args != null) {
                for (int i = 0; i < args.length; i++) {
                    stmt.setObject(i + 1, args[i]);
                }
            }
            return stmt.executeUpdate();
        } catch (SQLException e) {
            throw ExceptionUtils.asRuntimeException(e);
        }
    }

    /**
     * 批量执行 SQL
     *
     * @param sql      带占位符的 SQL
     * @param batchArgs 批量参数列表，每个元素是一组参数
     * @return 每条语句影响的行数数组
     */
    public int[] executeBatch(String sql, List<Object[]> batchArgs) {
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (Object[] args : batchArgs) {
                if (args != null) {
                    for (int i = 0; i < args.length; i++) {
                        stmt.setObject(i + 1, args[i]);
                    }
                }
                stmt.addBatch();
            }
            return stmt.executeBatch();
        } catch (SQLException e) {
            throw ExceptionUtils.asRuntimeException(e);
        }
    }

}
