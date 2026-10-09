package com.wxdgaming.batis.sql;

import com.wxdgaming.batis.exception.DataAccessException;
import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.TableInfo;
import com.wxdgaming.batis.parser.EntityParser;
import com.wxdgaming.batis.sql.query.SelectQuery;
import com.wxdgaming.batis.sql.query.SqlFragments;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Bean CRUD 操作器，封装实体级别的插入、查询、更新、删除操作
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-31
 **/
@Getter
@Slf4j
public class BeanRunner implements AutoCloseable {

    private final JdbcTemplate jdbcTemplate;
    private final QueryRunner queryRunner;
    /** 是否需要在 close 时关闭内部的 QueryRunner（外部注入的不关闭） */
    private final boolean closeQueryRunner;
    /** 批量提交大小，从 JdbcTemplate 继承默认值，可随时修改；&lt;=0 表示不分组一次性提交 */
    @Setter
    private int batchSize;

    /**
     * 独立模式：自行创建 QueryRunner，close 时归还连接
     */
    public BeanRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.queryRunner = new QueryRunner(jdbcTemplate);
        this.closeQueryRunner = true;
        this.batchSize = jdbcTemplate.getBatchSize();
    }

    /**
     * 共享模式：复用外部 QueryRunner（事务场景），close 时不关闭连接
     */
    public BeanRunner(QueryRunner queryRunner) {
        this.jdbcTemplate = queryRunner.getJdbcTemplate();
        this.queryRunner = queryRunner;
        this.closeQueryRunner = false;
        this.batchSize = this.jdbcTemplate.getBatchSize();
    }

    @Override
    public void close() {
        if (closeQueryRunner) {
            queryRunner.close();
        }
    }

    // ===== 实体插入/更新（委托给 BeanToInsertHandler / BeanToUpdateHandler） =====

    private BeanToInsertHandler requireInsertHandler() {
        BeanToInsertHandler handler = jdbcTemplate.getBeanToInsertHandler();
        if (handler == null) {
            throw new UnsupportedOperationException(
                    "未注册 beanToInsertHandler，请调用 JdbcTemplate#setBeanToInsertHandler()");
        }
        return handler;
    }

    private BeanToUpdateHandler requireUpdateHandler() {
        BeanToUpdateHandler handler = jdbcTemplate.getBeanToUpdateHandler();
        if (handler == null) {
            throw new UnsupportedOperationException(
                    "未注册 beanToUpdateHandler，请调用 JdbcTemplate#setBeanToUpdateHandler()");
        }
        return handler;
    }

    /**
     * 插入实体，自动构建 INSERT SQL 并执行，返回自增主键（无自增列返回 null）
     *
     * @param bean 实体对象
     * @return 自增主键，无自增列时返回 null
     */
    public Number insertBean(Object bean) {
        long start = System.currentTimeMillis();
        BeanToInsertHandler handler = requireInsertHandler();
        Number genId = queryRunner.executeInsert(handler.buildInsertSql(bean), handler.buildInsertParams(bean));
        handler.writeGeneratedKey(bean, genId);
        jdbcTemplate.getStats().recordInsert(System.currentTimeMillis() - start, 1);
        return genId;
    }

    /**
     * 更新实体，自动构建 UPDATE SQL 并按主键定位执行，返回影响行数
     *
     * @param bean 实体对象
     * @return 影响的行数
     */
    public int updateBean(Object bean) {
        long start = System.currentTimeMillis();
        BeanToUpdateHandler handler = requireUpdateHandler();
        int rows = queryRunner.update(handler.buildUpdateSql(bean), handler.buildUpdateParams(bean));
        jdbcTemplate.getStats().recordUpdate(System.currentTimeMillis() - start, 1);
        return rows;
    }

    /**
     * 安全批量插入：先尝试批量插入，失败后自动降级为逐条插入（无事务，每条 autocommit）。
     * <p>降级场景仅在数据异常时触发，虽慢但不常发生。</p>
     *
     * @param beans 实体列表
     * @param <T>   实体类型
     * @return 批量结果；调用方通过 {@link BatchResult#hasFailures()} 判断是否有失败
     */
    public <T> BatchResult<T> insertBeanList(List<T> beans) {
        BatchResult<T> result = new BatchResult<>(beans);
        if (beans == null || beans.isEmpty()) return result;

        long start = System.currentTimeMillis();

        if (batchSize <= 0 || beans.size() <= batchSize) {
            doInsertBatchSafe(beans, 0, result);
        } else {
            int baseIndex = 0;
            for (int offset = 0; offset < beans.size(); offset += batchSize) {
                int end = Math.min(offset + batchSize, beans.size());
                doInsertBatchSafe(beans.subList(offset, end), baseIndex, result);
                baseIndex += batchSize;
            }
        }

        jdbcTemplate.getStats().recordInsert(
                System.currentTimeMillis() - start, result.getSuccessCount());
        return result;
    }

    /**
     * 单组安全插入：先批量尝试，失败则逐条降级
     * <p>瞬时故障（死锁、锁超时、连接断开等）直接上抛，不进入逐条重试，避免放大故障。</p>
     */
    private <T> void doInsertBatchSafe(List<T> beans, int baseIndex, BatchResult<T> result) {
        // Fast path: 批量插入
        try {
            doInsertBatch(beans);
            for (int i = 0; i < beans.size(); i++) {
                result.recordSuccess();
            }
            return;
        } catch (Exception batchEx) {
            rethrowWithoutFallback(batchEx);
            // 批量失败（数据类异常），降级到逐条
        }

        // Slow path: 逐条插入（无事务，每条 autocommit）
        for (int i = 0; i < beans.size(); i++) {
            try {
                insertBean(beans.get(i));
                result.recordSuccess();
            } catch (Exception singleEx) {
                result.recordFailure(baseIndex + i, beans.get(i), singleEx);
            }
        }
    }

    /**
     * 执行单批 INSERT，负责 PreparedStatement 创建、参数填充、批量执行和自增键回写
     */
    private <T> int[] doInsertBatch(List<T> beans) {
        BeanToInsertHandler handler = jdbcTemplate.getBeanToInsertHandler();
        if (handler == null) {
            throw new UnsupportedOperationException(
                    "未注册 beanToInsertHandler，请调用 JdbcTemplate#setBeanToInsertHandler()");
        }

        Object firstBean = beans.getFirst();
        String sql = handler.buildInsertSql(firstBean);
        ColumnInfo identityColumn = handler.getIdentityColumn(firstBean);

        List<Object[]> batchArgs = new ArrayList<>(beans.size());
        for (Object bean : beans) {
            batchArgs.add(handler.buildInsertParams(bean));
        }

        queryRunner.checkWritable();
        final boolean dbg = log.isDebugEnabled();
        final long t0 = dbg ? System.nanoTime() : 0L;
        if (dbg) log.debug("[批量插入] {} -- 参数组数: {}",
                sql.replaceAll("\\s+", " ").trim(), batchArgs.size());
        try {
            Connection conn = queryRunner.getConnection();
            if (queryRunner.isInTransaction()) {
                // 事务内：不 commit 不 rollback、不动 autoCommit，由外层事务统一管理
                try (PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    queryRunner.applyQueryTimeout(stmt);
                    for (Object[] args : batchArgs) {
                        if (args != null) {
                            for (int i = 0; i < args.length; i++) {
                                stmt.setObject(i + 1, args[i]);
                            }
                        }
                        stmt.addBatch();
                    }
                    int[] results = stmt.executeBatch();
                    writeGeneratedKeys(stmt, beans, handler, identityColumn);
                    if (dbg) log.debug("[批量插入] 完成（事务内），耗时 {} ms，影响 {} 条",
                            (System.nanoTime() - t0) / 1_000_000, results.length);
                    return results;
                }
            }
            boolean autoCommit = conn.getAutoCommit();
            try {
                conn.setAutoCommit(false);
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
                    writeGeneratedKeys(stmt, beans, handler, identityColumn);
                    conn.commit();
                    if (dbg) log.debug("[批量插入] 完成，耗时 {} ms，影响 {} 条",
                            (System.nanoTime() - t0) / 1_000_000, results.length);
                    return results;
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

    /**
     * 批量插入后回写自增主键（无自增列时跳过）
     */
    private <T> void writeGeneratedKeys(PreparedStatement stmt, List<T> beans,
                                        BeanToInsertHandler handler, ColumnInfo identityColumn) throws SQLException {
        if (identityColumn == null) return;
        try (ResultSet rs = stmt.getGeneratedKeys()) {
            int idx = 0;
            while (rs.next() && idx < beans.size()) {
                Object val = rs.getObject(1);
                Number genId = val instanceof Number ? (Number) val : null;
                if (genId != null) {
                    handler.writeGeneratedKey(beans.get(idx), genId);
                }
                idx++;
            }
        }
    }

    /**
     * 安全批量更新：先尝试批量更新，失败后自动降级为逐条更新（无事务，每条 autocommit）。
     *
     * @param beans 实体列表
     * @param <T>   实体类型
     * @return 批量结果
     */
    public <T> BatchResult<T> updateBeanList(List<T> beans) {
        BatchResult<T> result = new BatchResult<>(beans);
        if (beans == null || beans.isEmpty()) return result;

        long start = System.currentTimeMillis();

        if (batchSize <= 0 || beans.size() <= batchSize) {
            doUpdateBatchSafe(beans, 0, result);
        } else {
            int baseIndex = 0;
            for (int offset = 0; offset < beans.size(); offset += batchSize) {
                int end = Math.min(offset + batchSize, beans.size());
                doUpdateBatchSafe(beans.subList(offset, end), baseIndex, result);
                baseIndex += batchSize;
            }
        }

        jdbcTemplate.getStats().recordUpdate(
                System.currentTimeMillis() - start, result.getSuccessCount());
        return result;
    }

    /**
     * 单组安全更新：先批量尝试，失败则逐条降级
     * <p>瞬时故障（死锁、锁超时、连接断开等）直接上抛，不进入逐条重试，避免放大故障。</p>
     */
    private <T> void doUpdateBatchSafe(List<T> beans, int baseIndex, BatchResult<T> result) {
        // Fast path: 批量更新
        try {
            doUpdateBatchInternal(beans);
            for (int i = 0; i < beans.size(); i++) {
                result.recordSuccess();
            }
            return;
        } catch (Exception batchEx) {
            rethrowWithoutFallback(batchEx);
            // 批量失败（数据类异常），降级到逐条
        }

        // Slow path: 逐条更新（无事务，每条 autocommit）
        for (int i = 0; i < beans.size(); i++) {
            try {
                updateBean(beans.get(i));
                result.recordSuccess();
            } catch (Exception singleEx) {
                result.recordFailure(baseIndex + i, beans.get(i), singleEx);
            }
        }
    }

    /**
     * 构建批量 UPDATE 参数并执行（不记录 stats，内部使用）
     */
    private int[] doUpdateBatchInternal(List<?> beans) {
        BeanToUpdateHandler handler = requireUpdateHandler();
        String sql = null;
        List<Object[]> batchArgs = new ArrayList<>(beans.size());
        for (Object bean : beans) {
            if (sql == null) {
                sql = handler.buildUpdateSql(bean);
            }
            batchArgs.add(handler.buildUpdateParams(bean));
        }
        return queryRunner.executeBatch(sql, batchArgs);
    }

    // ===== SELECT 实体便捷方法（委托给 BeanToSelectHandler） =====

    private BeanToSelectHandler requireSelectHandler() {
        BeanToSelectHandler handler = jdbcTemplate.getBeanToSelectHandler();
        if (handler == null) {
            throw new UnsupportedOperationException(
                    "未注册 beanToSelectHandler，请调用 JdbcTemplate#setBeanToSelectHandler()");
        }
        return handler;
    }

    /**
     * 按主键查询单个实体（基于实体类）
     * <p>单主键时 id 直接传值，复合主键时 id 传 Object[]。</p>
     *
     * @param entityClass 实体类
     * @param id          主键值（单主键）或 Object[]（复合主键）
     * @param <T>         实体类型
     * @return 实体对象，未找到时返回 null
     */
    public <T> T queryById(Class<T> entityClass, Object id) {
        TableInfo tableInfo = EntityParser.parse(entityClass);
        BeanToSelectHandler handler = requireSelectHandler();
        String sql = handler.buildSelectByIdSql(tableInfo);

        int pkCount = 0;
        for (ColumnInfo col : tableInfo.getColumns().values()) {
            if (col.isPrimaryKey()) pkCount++;
        }
        Object[] params = pkCount == 1 ? new Object[]{id} : (Object[]) id;

        return queryRunner.query(sql, params, ResultSetHandlers.bean(jdbcTemplate, entityClass));
    }

    /**
     * 查询全部实体
     *
     * @param entityClass 实体类
     * @param <T>         实体类型
     * @return 实体列表，不存在时返回空 List
     */
    public <T> List<T> queryAll(Class<T> entityClass) {
        TableInfo tableInfo = EntityParser.parse(entityClass);
        BeanToSelectHandler handler = requireSelectHandler();
        String sql = handler.buildSelectAllSql(tableInfo);
        return queryRunner.query(sql, ResultSetHandlers.beanList(jdbcTemplate, entityClass));
    }

    /**
     * 创建查询 DSL 构建器（推荐的条件查询入口）
     * <pre>{@code
     * List<Order> list = runner.select(Order.class)
     *         .where(Criteria.where("status").eq("PAID"))
     *         .orderBy(Sort.desc("createTime"))
     *         .page(Pageable.of(1, 20))
     *         .list();
     * }</pre>
     */
    public <T> SelectQuery<T> select(Class<T> entityClass) {
        return new SelectQuery<>(jdbcTemplate, entityClass);
    }

    /**
     * 带条件查询单条实体
     *
     * @param entityClass  实体类
     * @param whereSection WHERE 子句（不含 WHERE 关键字），例如 {@code "status = ? AND paid = ?"}
     * @param params       参数列表
     * @param <T>          实体类型
     * @return 实体对象，未找到时返回 null
     * @deprecated 字符串拼接条件有注入风险且无法复用类型体系，请改用 {@link #select(Class)} 查询 DSL
     */
    @Deprecated(forRemoval = true)
    public <T> T queryOne(Class<T> entityClass, String whereSection, Object... params) {
        TableInfo tableInfo = EntityParser.parse(entityClass);
        BeanToSelectHandler handler = requireSelectHandler();
        String sql;
        Object[] args;
        if (whereSection == null || whereSection.trim().isEmpty()) {
            sql = handler.buildSelectAllSql(tableInfo);
            args = null;
        } else {
            sql = handler.buildSelectAllSql(tableInfo) + " WHERE " + SqlFragments.guardWhereSection(whereSection);
            args = params.length > 0 ? params : null;
        }
        return queryRunner.query(sql, args, ResultSetHandlers.bean(jdbcTemplate, entityClass));
    }

    /**
     * 带条件查询实体列表
     *
     * @param entityClass  实体类
     * @param whereSection WHERE 子句（不含 WHERE 关键字），例如 {@code "status = ?"}
     * @param params       参数列表
     * @param <T>          实体类型
     * @return 实体列表，不存在时返回空 List
     * @deprecated 字符串拼接条件有注入风险且无法复用类型体系，请改用 {@link #select(Class)} 查询 DSL
     */
    @Deprecated(forRemoval = true)
    public <T> List<T> queryList(Class<T> entityClass, String whereSection, Object... params) {
        TableInfo tableInfo = EntityParser.parse(entityClass);
        BeanToSelectHandler handler = requireSelectHandler();
        String sql;
        Object[] args;
        if (whereSection == null || whereSection.trim().isEmpty()) {
            sql = handler.buildSelectAllSql(tableInfo);
            args = null;
        } else {
            sql = handler.buildSelectAllSql(tableInfo) + " WHERE " + SqlFragments.guardWhereSection(whereSection);
            args = params.length > 0 ? params : null;
        }
        return queryRunner.query(sql, args, ResultSetHandlers.beanList(jdbcTemplate, entityClass));
    }

    // ===== DELETE =====

    /**
     * 清空表中所有数据（基于实体类）
     */
    public void deleteAll(Class<?> cls) {
        TableInfo tableInfo = EntityParser.parse(cls);
        deleteAll(tableInfo);
    }

    /**
     * 清空表中所有数据（基于 TableInfo）
     */
    public void deleteAll(TableInfo tableInfo) {
        if (jdbcTemplate.getBeanToDeleteHandler() == null) {
            throw new UnsupportedOperationException("未注册 deleteHandler");
        }
        queryRunner.update(jdbcTemplate.getBeanToDeleteHandler().buildDeleteAllSql(tableInfo));
    }

    /**
     * 根据主键删除单个实体
     *
     * @param bean 实体对象（需包含主键字段值）
     * @return 影响的行数
     */
    public int deleteBean(Object bean) {
        long start = System.currentTimeMillis();
        if (jdbcTemplate.getBeanToDeleteHandler() == null) {
            throw new UnsupportedOperationException("未注册 deleteHandler");
        }
        int rows = queryRunner.update(
                jdbcTemplate.getBeanToDeleteHandler().buildDeleteByIdSql(bean),
                jdbcTemplate.getBeanToDeleteHandler().buildDeleteByIdParams(bean)
        );
        jdbcTemplate.getStats().recordDelete(System.currentTimeMillis() - start, 1);
        return rows;
    }

    /**
     * 批量删除实体列表，根据各实体的主键值构建 DELETE SQL 并批量执行
     * <p>所有实体必须为同一类型。</p>
     * <p>当数据量超过 {@link #batchSize} 时自动分组提交。</p>
     *
     * @param beans 实体列表
     * @return 各条语句影响的行数数组
     */
    public <T> int[] deleteListBean(List<T> beans) {
        if (beans == null || beans.isEmpty()) return new int[0];

        long start = System.currentTimeMillis();
        BeanToDeleteHandler handler = jdbcTemplate.getBeanToDeleteHandler();
        if (handler == null) {
            throw new UnsupportedOperationException("未注册 deleteHandler");
        }

        String sql = null;
        List<Object[]> batchArgs = new ArrayList<>(beans.size());

        for (Object bean : beans) {
            if (sql == null) {
                sql = handler.buildDeleteByIdSql(bean);
            }
            batchArgs.add(handler.buildDeleteByIdParams(bean));
        }

        int[] result;
        if (batchSize <= 0 || batchArgs.size() <= batchSize) {
            result = queryRunner.executeBatch(sql, batchArgs);
        } else {
            List<int[]> allResults = new ArrayList<>();
            for (int i = 0; i < batchArgs.size(); i += batchSize) {
                int end = Math.min(i + batchSize, batchArgs.size());
                int[] results = queryRunner.executeBatch(sql, batchArgs.subList(i, end));
                allResults.add(results);
            }
            result = flattenResults(allResults, batchArgs.size());
        }
        jdbcTemplate.getStats().recordDelete(System.currentTimeMillis() - start, beans.size());
        return result;
    }

    // ===== 内部工具方法 =====

    /**
     * 批量失败时判断是否直接上抛（不降级逐条）：
     * <ul>
     *   <li>瞬时故障（死锁、锁超时、连接断开）——重试逐条只会放大故障；</li>
     *   <li>事务内——批量中途失败时前面语句已生效，逐条补插会产生重复写，必须上抛由整个事务回滚。</li>
     * </ul>
     */
    private void rethrowWithoutFallback(Exception batchEx) {
        boolean retryable = batchEx instanceof DataAccessException dae && dae.isRetryable();
        if (retryable || queryRunner.isInTransaction()) {
            if (batchEx instanceof RuntimeException re) throw re;
            throw new IllegalStateException(batchEx);
        }
    }

    /**
     * 将多个分批结果数组合并为一个结果数组
     *
     * @param resultsList 各批次的结果数组列表
     * @param totalSize   原始数据总条数
     * @return 合并后的结果数组
     */
    private static int[] flattenResults(List<int[]> resultsList, int totalSize) {
        int[] result = new int[totalSize];
        int pos = 0;
        for (int[] arr : resultsList) {
            System.arraycopy(arr, 0, result, pos, arr.length);
            pos += arr.length;
        }
        return result;
    }

}
