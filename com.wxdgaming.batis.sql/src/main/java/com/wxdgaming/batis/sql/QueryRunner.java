package com.wxdgaming.batis.sql;

import com.wxdgaming.batis.model.ColumnInfo;
import lombok.Getter;
import org.apache.commons.lang3.exception.ExceptionUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 执行器
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-24 17:33
 **/
@Getter
public class QueryRunner implements AutoCloseable {

    private final JdbcTemplate jdbcTemplate;
    private Connection connection;
    /** 是否需要关闭连接（内部获取的连接需要关闭，外部注入的连接由调用方管理） */
    private final boolean closeConnection;

    public QueryRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.closeConnection = true;
    }

    public QueryRunner(JdbcTemplate jdbcTemplate, Connection connection) {
        this.jdbcTemplate = jdbcTemplate;
        this.connection = connection;
        this.closeConnection = false;
    }

    /**
     * 获取数据库连接，优先使用外部注入的 connection，否则从 JdbcTemplate 的 DataSource 惰性获取
     */
    public Connection getConnection() throws SQLException {
        if (connection != null) {
            return connection;
        }
        // 惰性从 DataSource 借用连接；是否由 close() 归还由构造函数决定
        // （JdbcTemplate 构造 -> closeConnection=true 需归还；外部 Connection 构造 -> 保持 false 不归还款）
        connection = jdbcTemplate.getDataSource().getConnection();
        return connection;
    }

    @Override
    public void close() {
        if (connection != null && closeConnection) {
            try {
                connection.close();
            } catch (SQLException e) {
                throw ExceptionUtils.asRuntimeException(e);
            }
        }
        connection = null;
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
        try {
            Connection conn = getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                if (args != null) {
                    for (int i = 0; i < args.length; i++) {
                        stmt.setObject(i + 1, args[i]);
                    }
                }
                try (ResultSet rs = stmt.executeQuery()) {
                    return handler.handle(rs);
                }
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
        return execute(sql, null);
    }

    /**
     * 执行带参数的 SQL（DDL / DML），返回是否执行成功
     *
     * @param sql  带占位符的 SQL
     * @param args 参数数组
     */
    public boolean execute(String sql, Object[] args) {
        try {
            Connection conn = getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                if (args != null) {
                    for (int i = 0; i < args.length; i++) {
                        stmt.setObject(i + 1, args[i]);
                    }
                }
                return stmt.execute();
            }
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
        return update(sql, null);
    }

    /**
     * 执行带参数的 DML（INSERT / UPDATE / DELETE），返回影响的行数
     *
     * @param sql  带占位符的 DML 语句
     * @param args 参数数组
     */
    public int update(String sql, Object[] args) {
        try {
            Connection conn = getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                if (args != null) {
                    for (int i = 0; i < args.length; i++) {
                        stmt.setObject(i + 1, args[i]);
                    }
                }
                return stmt.executeUpdate();
            }
        } catch (SQLException e) {
            throw ExceptionUtils.asRuntimeException(e);
        }
    }

    /**
     * 执行 INSERT 并返回自增主键（单主键列），无自增列时返回 null
     *
     * @param sql  INSERT 语句
     * @param args 参数数组
     * @return 自增主键，无自增列或无法获取时返回 null
     */
    public Number executeInsert(String sql, Object[] args) {
        try {
            Connection conn = getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                if (args != null) {
                    for (int i = 0; i < args.length; i++) {
                        stmt.setObject(i + 1, args[i]);
                    }
                }
                stmt.executeUpdate();
                try (ResultSet rs = stmt.getGeneratedKeys()) {
                    if (rs.next()) {
                        return rs.getObject(1, Number.class);
                    }
                }
            }
        } catch (SQLException e) {
            throw ExceptionUtils.asRuntimeException(e);
        }
        return null;
    }

    /**
     * 批量执行 SQL
     *
     * @param sql       带占位符的 SQL
     * @param batchArgs 批量参数列表，每个元素是一组参数
     * @return 每条语句影响的行数数组
     */
    public int[] executeBatch(String sql, List<Object[]> batchArgs) {
        try {
            Connection conn = getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                for (Object[] args : batchArgs) {
                    if (args != null) {
                        for (int i = 0; i < args.length; i++) {
                            stmt.setObject(i + 1, args[i]);
                        }
                    }
                    stmt.addBatch();
                }
                return stmt.executeBatch();
            }
        } catch (SQLException e) {
            throw ExceptionUtils.asRuntimeException(e);
        }
    }

    // ===== 实体快捷方法（委托给 IBeanToInsertHandler / IBeanToUpdateHandler） =====

    private IBeanToInsertHandler requireInsertHandler() {
        Supplier<IBeanToInsertHandler> supplier = jdbcTemplate.getBeanToInsertHandlerSupplier();
        if (supplier == null) {
            throw new UnsupportedOperationException(
                    "未注册 beanToInsertHandlerSupplier，请调用 JdbcTemplate#setBeanToInsertHandlerSupplier()");
        }
        return supplier.get();
    }

    private IBeanToUpdateHandler requireUpdateHandler() {
        Supplier<IBeanToUpdateHandler> supplier = jdbcTemplate.getBeanToUpdateHandlerSupplier();
        if (supplier == null) {
            throw new UnsupportedOperationException(
                    "未注册 beanToUpdateHandlerSupplier，请调用 JdbcTemplate#setBeanToUpdateHandlerSupplier()");
        }
        return supplier.get();
    }

    /**
     * 插入实体，自动构建 INSERT SQL 并执行，返回自增主键（无自增列返回 null）
     *
     * @param bean 实体对象
     * @return 自增主键，无自增列时返回 null
     */
    public Number insertBean(Object bean) {
        IBeanToInsertHandler handler = requireInsertHandler();
        Number genId = executeInsert(handler.buildInsertSql(bean), handler.buildInsertParams(bean));
        handler.writeGeneratedKey(bean, genId);
        return genId;
    }

    /**
     * 更新实体，自动构建 UPDATE SQL 并按主键定位执行，返回影响行数
     *
     * @param bean 实体对象
     * @return 影响的行数
     */
    public int updateBean(Object bean) {
        IBeanToUpdateHandler handler = requireUpdateHandler();
        return update(handler.buildUpdateSql(bean), handler.buildUpdateParams(bean));
    }

    /**
     * 批量插入实体列表，自动构建 INSERT SQL，返回各条影响行数
     * <p>所有实体必须为同一类型；自增主键会自动回写到对应实体。</p>
     *
     * @param beans 实体列表
     * @return 各条语句影响的行数数组
     */
    public int[] insertBeanList(List<?> beans) {
        if (beans == null || beans.isEmpty()) return new int[0];

        Supplier<IBeanToInsertHandler> supplier = jdbcTemplate.getBeanToInsertHandlerSupplier();
        if (supplier == null) {
            throw new UnsupportedOperationException(
                    "未注册 beanToInsertHandlerSupplier，请调用 JdbcTemplate#setBeanToInsertHandlerSupplier()");
        }

        Object firstBean = beans.get(0);
        IBeanToInsertHandler template = supplier.get();
        String sql = template.buildInsertSql(firstBean);
        ColumnInfo identityColumn = template.getIdentityColumn(firstBean);

        List<Object[]> batchArgs = new ArrayList<>(beans.size());
        for (Object bean : beans) {
            batchArgs.add(supplier.get().buildInsertParams(bean));
        }

        try {
            Connection conn = getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                for (Object[] args : batchArgs) {
                    if (args != null) {
                        for (int i = 0; i < args.length; i++) {
                            stmt.setObject(i + 1, args[i]);
                        }
                    }
                    stmt.addBatch();
                }
                int[] results = stmt.executeBatch();

                if (identityColumn != null) {
                    try (ResultSet rs = stmt.getGeneratedKeys()) {
                        int idx = 0;
                        while (rs.next() && idx < beans.size()) {
                            Number genId = rs.getObject(1, Number.class);
                            if (genId != null) {
                                template.writeGeneratedKey(beans.get(idx), genId);
                            }
                            idx++;
                        }
                    }
                }
                return results;
            }
        } catch (SQLException e) {
            throw ExceptionUtils.asRuntimeException(e);
        }
    }

    /**
     * 批量更新实体列表，自动构建 UPDATE SQL，返回各条影响行数
     * <p>所有实体必须为同一类型。</p>
     *
     * @param beans 实体列表
     * @return 各条语句影响的行数数组
     */
    public int[] updateBeanList(List<?> beans) {
        if (beans == null || beans.isEmpty()) return new int[0];

        Supplier<IBeanToUpdateHandler> supplier = jdbcTemplate.getBeanToUpdateHandlerSupplier();
        if (supplier == null) {
            throw new UnsupportedOperationException(
                    "未注册 beanToUpdateHandlerSupplier，请调用 JdbcTemplate#setBeanToUpdateHandlerSupplier()");
        }

        String sql = null;
        List<Object[]> batchArgs = new ArrayList<>(beans.size());

        for (Object bean : beans) {
            IBeanToUpdateHandler handler = supplier.get();
            if (sql == null) {
                sql = handler.buildUpdateSql(bean);
            }
            batchArgs.add(handler.buildUpdateParams(bean));
        }

        return executeBatch(sql, batchArgs);
    }

}
