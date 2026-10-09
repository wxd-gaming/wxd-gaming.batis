package com.wxdgaming.batis.sql;

import com.wxdgaming.batis.exception.IllegalTransactionStateException;
import com.wxdgaming.batis.sql.tx.ConnectionHolder;
import com.wxdgaming.batis.sql.tx.TxContext;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/**
 * 执行器
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-24 17:33
 **/
@Getter
@Slf4j
public class QueryRunner implements AutoCloseable {

    /** 连接来源：外部注入（不归还）/ 事务绑定（不归还，由 TransactionTemplate 管理）/ 池借出（close 时归还） */
    public enum Source { EXTERNAL, TRANSACTION, POOLED }

    private final JdbcTemplate jdbcTemplate;
    private Connection connection;
    /** 当前绑定的事务连接持有者，source == TRANSACTION 时非空 */
    private ConnectionHolder txHolder;
    private Source source;

    public QueryRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.source = Source.POOLED;
    }

    public QueryRunner(JdbcTemplate jdbcTemplate, Connection connection) {
        this.jdbcTemplate = jdbcTemplate;
        this.connection = connection;
        this.source = Source.EXTERNAL;
    }

    /**
     * 获取数据库连接，来源优先级：显式注入 &gt; 当前线程事务绑定 &gt; 连接池借出
     */
    public Connection getConnection() throws SQLException {
        if (connection != null) {
            return connection;
        }
        ConnectionHolder holder = TxContext.get(jdbcTemplate);
        if (holder != null && holder.isTransactional()) {
            // 事务绑定：close() 时绝不归还、绝不 setAutoCommit，归还由 TransactionTemplate 负责
            this.txHolder = holder;
            this.source = Source.TRANSACTION;
            connection = holder.getConnection();
            return connection;
        }
        // 惰性从 DataSource 借用连接；close() 归还
        connection = jdbcTemplate.getDataSource().getConnection();
        this.source = Source.POOLED;
        return connection;
    }

    /** 当前 Runner 是否已绑定到线程事务 */
    public boolean isInTransaction() {
        return source == Source.TRANSACTION;
    }

    /** 是否需要由 close() 归还连接（外部注入和事务绑定的不归还） */
    public boolean isCloseConnection() {
        return source == Source.POOLED;
    }

    /** 只读事务写保护：执行前校验（不依赖连接是否已惰性获取） */
    public void checkWritable() {
        if (TxContext.isCurrentReadOnly(jdbcTemplate)) {
            throw new IllegalTransactionStateException("只读事务中不允许执行写操作");
        }
    }

    /** 为事务内语句应用语句级超时 */
    public void applyQueryTimeout(Statement stmt) throws SQLException {
        if (source == Source.TRANSACTION && txHolder != null && txHolder.getQueryTimeoutSeconds() > 0) {
            stmt.setQueryTimeout(txHolder.getQueryTimeoutSeconds());
        }
    }

    @Override
    public void close() {
        if (connection != null && source == Source.POOLED) {
            try {
                connection.close();
            } catch (SQLException e) {
                throw jdbcTemplate.translateException(e, null, null);
            }
        }
        // source == TRANSACTION / EXTERNAL：绝不 close，归还由事务模板或调用方负责
        connection = null;
        txHolder = null;
    }

    /**
     * 执行查询
     *
     * @param sql     查询语句
     * @param handler 结果集处理器
     * @param <R>     处理结果类型
     * @return 处理结果
     */
    public <R> R query(String sql, ResultSetHandler<R> handler) {
        return query(sql, null, handler);
    }

    /**
     * 执行查询
     *
     * @param sql     查询语句
     * @param args    参数数组
     * @param handler 结果集处理器
     * @param <R>     处理结果类型
     * @return 处理结果
     */
    public <R> R query(String sql, Object[] args, ResultSetHandler<R> handler) {
        return timed("查询", sql, args, () -> {
            try {
                Connection conn = getConnection();
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    applyQueryTimeout(stmt);
                    bind(stmt, args);
                    try (ResultSet rs = stmt.executeQuery()) {
                        return handler.handle(rs);
                    }
                }
            } catch (SQLException e) {
                throw jdbcTemplate.translateException(e, sql, args);
            }
        });
    }

    /**
     * 执行 SQL（DDL / DML），返回是否执行成功
     *
     * @param sql 任意 SQL 语句
     */
    public boolean execute(String sql) {
        return execute(sql, null);
    }

    /**
     * 执行带参数的 SQL（DDL / DML），返回是否执行成功
     *
     * @param sql  带占位符的 SQL
     * @param args 参数数组
     */
    public boolean execute(String sql, Object[] args) {
        checkWritable();
        return timed("执行", sql, args, () -> {
            try {
                Connection conn = getConnection();
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    applyQueryTimeout(stmt);
                    bind(stmt, args);
                    return stmt.execute();
                }
            } catch (SQLException e) {
                throw jdbcTemplate.translateException(e, sql, args);
            }
        });
    }

    /**
     * 执行 DML（INSERT / UPDATE / DELETE），返回影响的行数
     *
     * @param sql DML 语句
     */
    public int update(String sql) {
        return update(sql, null);
    }

    /**
     * 执行带参数的 DML（INSERT / UPDATE / DELETE），返回影响的行数
     *
     * @param sql  带占位符的 DML 语句
     * @param args 参数数组
     */
    public int update(String sql, Object[] args) {
        checkWritable();
        return timed("更新", sql, args, () -> {
            try {
                Connection conn = getConnection();
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    applyQueryTimeout(stmt);
                    bind(stmt, args);
                    return stmt.executeUpdate();
                }
            } catch (SQLException e) {
                throw jdbcTemplate.translateException(e, sql, args);
            }
        });
    }

    /**
     * 执行 INSERT 并返回自增主键（单主键列），无自增列时返回 null
     *
     * @param sql  INSERT 语句
     * @param args 参数数组
     * @return 自增主键，无自增列或无法获取时返回 null
     */
    public Number executeInsert(String sql, Object[] args) {
        checkWritable();
        return timed("插入", sql, args, () -> {
            try {
                Connection conn = getConnection();
                try (PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    applyQueryTimeout(stmt);
                    bind(stmt, args);
                    stmt.executeUpdate();
                    try (ResultSet rs = stmt.getGeneratedKeys()) {
                        if (rs.next()) {
                            Object val = rs.getObject(1);
                            return val instanceof Number ? (Number) val : null;
                        }
                    }
                }
            } catch (SQLException e) {
                throw jdbcTemplate.translateException(e, sql, args);
            }
            return null;
        });
    }

    /**
     * 批量执行 SQL
     *
     * @param sql       带占位符的 SQL
     * @param batchArgs 批量参数列表，每个元素是一组参数
     * @return 每条语句影响的行数数组
     */
    public int[] executeBatch(String sql, List<Object[]> batchArgs) {
        checkWritable();
        final boolean dbg = log.isDebugEnabled();
        final long t0 = dbg ? System.nanoTime() : 0L;
        if (dbg) log.debug("[批量] {} -- 参数组数: {}", oneLine(sql), batchArgs.size());
        try {
            Connection conn = getConnection();
            if (isInTransaction()) {
                // 事务内：只 addBatch/executeBatch，不 commit 不 rollback，
                // 提交/回滚由外层 TransactionTemplate 统一管理（全有或全无）
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    applyQueryTimeout(stmt);
                    for (Object[] args : batchArgs) {
                        bind(stmt, args);
                        stmt.addBatch();
                    }
                    int[] result = stmt.executeBatch();
                    if (dbg) log.debug("[批量] 完成，耗时 {} ms，影响 {} 行",
                            (System.nanoTime() - t0) / 1_000_000, sumRows(result));
                    return result;
                }
            }
            boolean autoCommit = conn.getAutoCommit();
            try {
                conn.setAutoCommit(false);
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    for (Object[] args : batchArgs) {
                        bind(stmt, args);
                        stmt.addBatch();
                    }
                    int[] result = stmt.executeBatch();
                    conn.commit();
                    if (dbg) log.debug("[批量] 完成，耗时 {} ms，影响 {} 行",
                            (System.nanoTime() - t0) / 1_000_000, sumRows(result));
                    return result;
                }
            } catch (SQLException e) {
                try {
                    conn.rollback();
                } catch (SQLException ignored) {
                }
                throw jdbcTemplate.translateException(e, sql, null);
            } finally {
                try {
                    conn.setAutoCommit(autoCommit);
                } catch (SQLException ignored) {
                }
            }
        } catch (SQLException e) {
            throw jdbcTemplate.translateException(e, sql, null);
        }
    }

    // ============== 执行过程 debug 日志 ==============

    /** 绑定参数（null 安全） */
    private static void bind(PreparedStatement stmt, Object[] args) throws SQLException {
        if (args == null) return;
        for (int i = 0; i < args.length; i++) {
            stmt.setObject(i + 1, args[i]);
        }
    }

    /** 统一执行埋点：debug 关闭时零额外开销（不取时间、不格式化） */
    private static <R> R timed(String kind, String sql, Object[] args, SupplierWithException<R> body) {
        if (!log.isDebugEnabled()) {
            return body.get();
        }
        long t0 = System.nanoTime();
        String params = args == null ? "[]" : Arrays.toString(args);
        try {
            R result = body.get();
            log.debug("[{}] {} -- 参数: {} -- 结果: {} -- 耗时 {} ms",
                    kind, oneLine(sql), params, describe(result),
                    (System.nanoTime() - t0) / 1_000_000);
            return result;
        } catch (RuntimeException | Error e) {
            log.debug("[{}] 失败 {} -- 参数: {} -- 耗时 {} ms -- {}",
                    kind, oneLine(sql), params,
                    (System.nanoTime() - t0) / 1_000_000, e.getMessage());
            throw e;
        }
    }

    @FunctionalInterface
    private interface SupplierWithException<R> {
        R get();
    }

    /** 结果摘要：数值直接展示（影响行数/生成键），集合展示条数，其余省略 */
    private static String describe(Object result) {
        if (result instanceof Number || result instanceof Boolean) return String.valueOf(result);
        if (result instanceof List<?> list) return list.size() + " 条";
        if (result instanceof int[] arr) return arr.length + " 条";
        return result == null ? "null" : result.getClass().getSimpleName();
    }

    private static long sumRows(int[] rows) {
        long total = 0;
        for (int r : rows) total += Math.max(r, 0);
        return total;
    }

    /** SQL 压成单行，便于日志检索 */
    private static String oneLine(String sql) {
        return sql == null ? "" : sql.replaceAll("\\s+", " ").trim();
    }

}
