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
 * <p>
 * 本类是"实体 ↔ 表"这一层的编排器，它刻意只做编排，四件事全部外包：
 * </p>
 * <ul>
 *   <li><b>连接与执行</b>：全部走内部 {@link QueryRunner}，因此实体操作自动继承事务绑定、
 *       只读写保护、语句超时和 debug 日志，本类不重复实现任何 JDBC 细节；</li>
 *   <li><b>SQL 长什么样</b>：交给 {@link BeanToInsertHandler} / {@link BeanToUpdateHandler} /
 *       {@link BeanToDeleteHandler} / {@link BeanToSelectHandler} 四个方言 SPI，
 *       所以同一份 BeanRunner 可以跑在 MySQL 或将来的 pgsql 上；未装配时
 *       {@code requireXxxHandler()} 抛出可读的 UnsupportedOperationException，而不是让人猜 NPE 从哪来；</li>
 *   <li><b>元数据</b>：由 {@code EntityParser.parse} 提供 TableInfo，本类只读不问；</li>
 *   <li><b>性能统计</b>：每个公开写方法向 {@code DbStats} 记一次。</li>
 * </ul>
 * <p>
 * 两种构造对应两种生命周期（见下方重载注释）：独立模式自己管连接，共享模式寄生在别人的
 * QueryRunner 上。事务与 {@link CachedBeanRunner} 必须用共享模式，否则实体操作会和事务脱节。
 * </p>
 * <p>
 * 批量方法（{@code *BeanList}）返回 {@link BatchResult} 而非抛异常：批量插入的真实诉求通常是
 * "尽可能写进去，把失败的那几条交给我处理"，所以失败以条目粒度收集，调用方用
 * {@link BatchResult#hasFailures()} 判断。与之相对，单条方法一律抛异常。
 * </p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-31
 **/
@Getter
@Slf4j
public class BeanRunner implements AutoCloseable {

    private final JdbcTemplate jdbcTemplate;
    /** 实际执行载体：本类不直接持有连接 */
    private final QueryRunner queryRunner;
    /** 是否需要在 close 时关闭内部的 QueryRunner（外部注入的不关闭） */
    private final boolean closeQueryRunner;
    /**
     * 批量提交大小，从 JdbcTemplate 继承默认值，可随时修改；&lt;=0 表示不分组一次性提交。
     * <p>分组不只是为了控制驱动侧 addBatch 的内存占用，更关键的是缩小锁持有窗口：
     * 一次 executeBatch 会把整批压成一次网络往返并一直持有到语句结束，
     * 分组后每组独立提交，死锁/锁超时的影响面被限制在一组之内。
     * 代价是"分组之间存在部分成功"，所以只有配合 BatchResult 的条目粒度报告才安全。</p>
     */
    @Setter
    private int batchSize;

    /**
     * 独立模式：自行创建 QueryRunner，close 时归还连接。
     * <p>适合一次性的实体操作。线程上若绑定了事务，内部 QueryRunner 会自动改用事务连接，
     * close() 也就不会真的归还池连接（判定在 QueryRunner 的 source 里）。</p>
     */
    public BeanRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.queryRunner = new QueryRunner(jdbcTemplate);
        this.closeQueryRunner = true;
        this.batchSize = jdbcTemplate.getBatchSize();
    }

    /**
     * 共享模式：复用外部 QueryRunner（事务场景），close 时不关闭连接。
     * <p>典型用法是 {@code TransactionTemplate} 回调内、以及 {@link CachedBeanRunner} 刷盘时
     * 复用同一条连接——共享 QueryRunner 才能保证"同一个事务、同一条连接"，
     * 否则批量插入会各借各的连接，事务语义直接失效。</p>
     */
    public BeanRunner(QueryRunner queryRunner) {
        this.jdbcTemplate = queryRunner.getJdbcTemplate();
        this.queryRunner = queryRunner;
        this.closeQueryRunner = false;
        this.batchSize = this.jdbcTemplate.getBatchSize();
    }

    /** 独立模式才释放内部 Runner；共享模式只丢弃引用，连接归注入方 */
    @Override
    public void close() {
        if (closeQueryRunner) {
            queryRunner.close();
        }
    }

    // ===== 实体插入/更新（委托给 BeanToInsertHandler / BeanToUpdateHandler） =====

    /**
     * 取插入方言实现，未注册时给出可定位的错误。
     * <p>这类"装配缺失"发生在 {@code MysqlTemplate.initDefaults()} 之外（例如自建 JdbcTemplate 忘了注入），
     * 抛带方法名的 UnsupportedOperationException 比让 NPE 在深处炸出来更容易排障。</p>
     */
    private BeanToInsertHandler requireInsertHandler() {
        BeanToInsertHandler handler = jdbcTemplate.getBeanToInsertHandler();
        if (handler == null) {
            throw new UnsupportedOperationException(
                    "未注册 beanToInsertHandler，请调用 JdbcTemplate#setBeanToInsertHandler()");
        }
        return handler;
    }

    /** 取更新方言实现，未注册时给出可定位的错误 */
    private BeanToUpdateHandler requireUpdateHandler() {
        BeanToUpdateHandler handler = jdbcTemplate.getBeanToUpdateHandler();
        if (handler == null) {
            throw new UnsupportedOperationException(
                    "未注册 beanToUpdateHandler，请调用 JdbcTemplate#setBeanToUpdateHandler()");
        }
        return handler;
    }

    /**
     * 插入单个实体：自动构建 INSERT SQL 并执行，回取自增主键并写回实体。
     * <p>
     * {@code writeGeneratedKey} 这一步让"插入后拿到 ID"成为框架保证的行为，
     * 调用方不需要再查一次（sql.mysql 测试 BeanRunnerTest 的用例①专门盯这条回写）。
     * 手工主键（如雪花 ID）实体没有自增列，genId 为 null，回写自然跳过。
     * </p>
     * <p>批量降级路径会逐条调用本方法，因此每条都会各记一次 stats；
     * 外层 {@link #insertBeanList} 又按整批记一次，降级发生时统计存在重复计数（偏高但不丢），
     * 这是为了保留"逐条各自的耗时"而接受的口径。</p>
     *
     * @param bean 实体对象
     * @return 自增主键，无自增列时返回 null
     */
    public Number insertBean(Object bean) {
        long start = System.currentTimeMillis();
        BeanToInsertHandler handler = requireInsertHandler();
        ColumnInfo identityColumn = handler.getIdentityColumn(bean);
        Number genId = queryRunner.executeInsert(handler.buildInsertSql(bean), handler.buildInsertParams(bean),
                identityColumn == null ? null : identityColumn.getColumnName().toLowerCase());
        handler.writeGeneratedKey(bean, genId);
        jdbcTemplate.getStats().recordInsert(System.currentTimeMillis() - start, 1);
        return genId;
    }

    /**
     * 更新单个实体：自动构建 UPDATE SQL 并按主键定位执行。
     * <p>影响行数 0 表示"主键没匹配到行"，不是失败；测试用例④因此要求回读字段值，
     * 光看 rows&gt;0 证明不了改的是对的那一行。</p>
     *
     * @param bean 实体对象（主键字段必须有值）
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
     * <p>
     * 两级结构：{@code insertBeanList} 负责按 {@link #batchSize} 切片，
     * {@code doInsertBatchSafe} 负责一组之内的"批量优先、失败降级"。
     * 切片时把批次起点 {@code baseIndex} 一路传下去，失败条目才能报出在<em>原始列表</em>中的下标，
     * 而不是组内下标（调用方拿下标回查原始数据，错位等于指错数据）。
     * </p>
     *
     * @param beans 实体列表（同一类型；null 或空直接返回空结果）
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
            // baseIndex 按 batchSize 步进：subList 是视图，组内下标必须换算回全局下标
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
     * <p>"失败一条就放弃整批"被刻意排除在降级之外：批量执行只给出"这一批炸了"的信号，
     * 逐条重跑才能把责任定位到具体实体，代价是慢——但它让 1 条脏数据不再能拖垮 10000 条好数据。</p>
     *
     * @param baseIndex 本组第一个元素在原始列表中的下标，用于失败条目的全局定位
     */
    private <T> void doInsertBatchSafe(List<T> beans, int baseIndex, BatchResult<T> result) {
        // Fast path: 批量插入
        try {
            int[] results = doInsertBatch(beans);
            for (int i = 0; i < beans.size(); i++) {
                // 插入语句的影响行数：驱动给 1 就记 1，给 SUCCESS_NO_INFO(-2)/缺失时按"这条确实插入了"记 1
                int affected = i < results.length && results[i] > 0 ? results[i] : 1;
                result.recordSuccess(affected);
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
     * 执行单批 INSERT：PreparedStatement 创建、参数填充、批量执行、自增键回写。
     * <p>
     * <b>为什么不走现成的 {@link QueryRunner#executeBatch}</b>：那条路径按普通 prepareStatement
     * 建语句，拿不到生成键。插入必须带回写的 ID，所以这里旁路直连 JDBC，
     * 代价是日志、事务双分支、autoCommit 恢复这些约定要在本方法里再实现一遍——
     * {@code SqlDebugLogTest} 的用例④之所以单独覆盖"批量插入旁路"，就是这个原因。
     * </p>
     * <p>
     * 事务双分支的理由与 executeBatch 完全一致（修复过的事故）：早期无条件
     * setAutoCommit(false)+commit，在外部事务里调用会把外层工作静默提前提交。
     * 现在事务内只 addBatch/executeBatch，提交与回滚都交回 {@code TransactionTemplate}。
     * </p>
     * <p>本方法不记 stats：耗时由 {@link #insertBeanList} 按整批记，降级逐条由 {@link #insertBean} 记，
     * 避免同一段工作在两处重复入账（降级路径的重复计数见 insertBean 注释）。</p>
     *
     * @return 每条语句影响的行数数组
     */
    private <T> int[] doInsertBatch(List<T> beans) {
        BeanToInsertHandler handler = jdbcTemplate.getBeanToInsertHandler();
        if (handler == null) {
            throw new UnsupportedOperationException(
                    "未注册 beanToInsertHandler，请调用 JdbcTemplate#setBeanToInsertHandler()");
        }

        // 同批实体必须同类型：SQL 只由第一个实体推导一次，后续共用同一语句只换参数
        Object firstBean = beans.getFirst();
        String sql = handler.buildInsertSql(firstBean);
        ColumnInfo identityColumn = handler.getIdentityColumn(firstBean);
        // 按列名向驱动索取生成键：pgjdbc 会把通用形态实现成 RETURNING *，
        // 结果集首列就是表的第一个字段而不是自增列，批量按序 zip 回写时会整体拿错值
        String identityColumnName = identityColumn == null ? null : identityColumn.getColumnName().toLowerCase();

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
                try (PreparedStatement stmt = QueryRunner.prepareForGeneratedKeys(conn, sql, identityColumnName)) {
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
                    // 事务内同样要先校验：失败条目会导致生成键按序 zip 错位，把 A 的自增 ID 写进 B
                    queryRunner.checkExecuteFailures(results, beans.size(), sql);
                    writeGeneratedKeys(stmt, beans, handler, identityColumn);
                    if (dbg) log.debug("[批量插入] 完成（事务内），耗时 {} ms，影响 {} 条",
                            (System.nanoTime() - t0) / 1_000_000, results.length);
                    return results;
                }
            }
            // 非事务：这段批量自身是一个小事务，finally 恢复 autoCommit 原值（池连接会被复用）
            boolean autoCommit = conn.getAutoCommit();
            try {
                conn.setAutoCommit(false);
                try (PreparedStatement stmt = QueryRunner.prepareForGeneratedKeys(conn, sql, identityColumnName)) {
                    for (Object[] args : batchArgs) {
                        if (args != null) {
                            for (int i = 0; i < args.length; i++) {
                                stmt.setObject(i + 1, args[i]);
                            }
                        }
                        stmt.addBatch();
                    }
                    int[] results = stmt.executeBatch();
                    // 与 QueryRunner.executeBatch 同一口径：驱动不抛异常、只用 EXECUTE_FAILED 报失败时
                    // 必须在 commit 之前中断并回滚；否则"部分成功"被永久落库，生成键还会整体错位
                    queryRunner.checkExecuteFailures(results, beans.size(), sql);
                    // 生成键必须在 commit 之前读（游标随语句关闭失效）
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
                throw queryRunner.translateBatchException(e, sql, beans.size());
            } catch (RuntimeException e) {
                // 本地检测到的部分失败：先回滚这一小批，否则 finally 恢复 autoCommit 会隐式提交它
                try {
                    conn.rollback();
                } catch (SQLException ignored) {
                }
                throw e;
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
     * 批量插入后回写自增主键（无自增列时跳过）。
     * <p>
     * 回写靠"顺序 zip"而不是按值匹配：驱动对 addBatch 的每条 INSERT 各回传一个生成键，
     * 结果集顺序与插入顺序一致，所以第 i 行键写给第 i 个实体。
     * {@code idx < beans.size()} 是防御性上界（驱动多回传时不多写）；
     * 单行取不到数值键时跳过该实体，宁可让 ID 缺失也不要把 null 塞进基本类型字段。
     * </p>
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
     * <p>与 {@link #insertBeanList} 同构。差别在于 UPDATE 失败通常意味着"行不存在"或
     * 唯一约束冲突，逐条重跑没有副作用风险（幂等性由主键定位保证），所以降级同样适用。</p>
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
            int[] results = doUpdateBatchInternal(beans);
            for (int i = 0; i < beans.size(); i++) {
                // 这里必须用驱动回传的真实行数："更新一条库里不存在的行"会得到 0，
                // 若按"没抛异常"记为成功，FlushResult 就再也发现不了这种 0 行更新
                result.recordSuccess(i < results.length ? results[i] : 0);
            }
            return;
        } catch (Exception batchEx) {
            rethrowWithoutFallback(batchEx);
            // 批量失败（数据类异常），降级到逐条
        }

        // Slow path: 逐条更新（无事务，每条 autocommit）
        for (int i = 0; i < beans.size(); i++) {
            try {
                int rows = updateBean(beans.get(i));
                result.recordSuccess(rows);
            } catch (Exception singleEx) {
                result.recordFailure(baseIndex + i, beans.get(i), singleEx);
            }
        }
    }

    /**
     * 构建批量 UPDATE 参数并执行（不记录 stats，内部使用）。
     * <p>
     * 这里只造一次 SQL、每组实体只换参数：UPDATE 语句形态由实体类型决定，与具体值无关，
     * 所以取第一个实体的 SQL 复用。真正的批量执行与事务感知交给
     * {@link QueryRunner#executeBatch}（UPDATE 不需要回生成键，走通用批量入口即可）。
     * </p>
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

    /** 取查询方言实现（列清单、引号风格、分页子句都由它决定），未注册时给出可定位的错误 */
    private BeanToSelectHandler requireSelectHandler() {
        BeanToSelectHandler handler = jdbcTemplate.getBeanToSelectHandler();
        if (handler == null) {
            throw new UnsupportedOperationException(
                    "未注册 beanToSelectHandler，请调用 JdbcTemplate#setBeanToSelectHandler()");
        }
        return handler;
    }

    /**
     * 按主键查询单个实体（基于实体类）。
     * <p>
     * 单主键和复合主键在参数形态上不同：单主键直接传值，复合主键传 {@code Object[]}。
     * 这里统计主键列数来做转换，是为了让调用方不用关心"我一个还是多个"——
     * 但代价是复合主键传错成单个值时会立刻抛出 {@code ClassCastException}，
     * 属于快速失败，比把整个实体对象当参数绑上去更清楚。
     * </p>
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
     * 查询全部实体（全表扫描，仅适合小表或测试场景）。
     * <p>结果一次性全部物化进 List，大表请改用 {@link #select(Class)} 加分页。</p>
     *
     * @param entityClass 实体类
     * @param <T>         实体类型
     * @return 实体列表，不存在时返回空 List（永不返回 null，调用方无需判空）
     */
    public <T> List<T> queryAll(Class<T> entityClass) {
        TableInfo tableInfo = EntityParser.parse(entityClass);
        BeanToSelectHandler handler = requireSelectHandler();
        String sql = handler.buildSelectAllSql(tableInfo);
        return queryRunner.query(sql, ResultSetHandlers.beanList(jdbcTemplate, entityClass));
    }

    /**
     * 创建查询 DSL 构建器（推荐的条件查询入口）。
     * <p>
     * 关键点：返回的 SelectQuery 复用<em>本 Runner 自身</em>的 QueryRunner，
     * 所以在事务里调用 {@code select(...)} 会自动落在事务连接上（读己未提交的写）。
     * 若换成"从 JdbcTemplate 新借一条连接"，事务内查询就会看不到本事务尚未提交的插入，
     * SelectQueryIT 的用例⑫正是用"事务内读回自己刚写的数据"来锁定这条行为。
     * </p>
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
     * 带条件查询单条实体（字符串拼 WHERE 的旧 API）。
     *
     * @param entityClass  实体类
     * @param whereSection WHERE 子句（不含 WHERE 关键字），例如 {@code "status = ? AND paid = ?"}
     * @param params       参数列表
     * @param <T>          实体类型
     * @return 实体对象，未找到时返回 null
     * @deprecated 字符串拼接条件有注入风险且无法复用类型体系，请改用 {@link #select(Class)} 查询 DSL。
     *             过渡期内 {@code whereSection} 会先过 {@link SqlFragments#guardWhereSection} 过滤危险片段。
     */
    @Deprecated(forRemoval = true)
    public <T> T queryOne(Class<T> entityClass, String whereSection, Object... params) {
        TableInfo tableInfo = EntityParser.parse(entityClass);
        BeanToSelectHandler handler = requireSelectHandler();
        String sql;
        Object[] args;
        if (whereSection == null || whereSection.trim().isEmpty()) {
            // 空条件退化成全表查询，与 queryAll 保持一致语义（不生成 "WHERE " 空尾巴）
            sql = handler.buildSelectAllSql(tableInfo);
            args = null;
        } else {
            // 拼接前过滤：分号/注释/UNION 等直接拒绝，并校验占位符与参数数量一致、
            // 拒绝"夹带字符串字面量却不传参"的拼接写法（属纵深防御，不替代参数化）
            sql = handler.buildSelectAllSql(tableInfo) + " WHERE "
                    + SqlFragments.guardWhereSection(whereSection, params);
            args = params.length > 0 ? params : null;
        }
        return queryRunner.query(sql, args, ResultSetHandlers.bean(jdbcTemplate, entityClass));
    }

    /**
     * 带条件查询实体列表（字符串拼 WHERE 的旧 API）。
     *
     * @param entityClass  实体类
     * @param whereSection WHERE 子句（不含 WHERE 关键字），例如 {@code "status = ?"}
     * @param params       参数列表
     * @param <T>          实体类型
     * @return 实体列表，不存在时返回空 List
     * @deprecated 字符串拼接条件有注入风险且无法复用类型体系，请改用 {@link #select(Class)} 查询 DSL。
     *             过渡期内 {@code whereSection} 会先过 {@link SqlFragments#guardWhereSection} 过滤危险片段。
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
            // 同 queryOne：黑名单 + 占位符/参数数量一致性 + 禁止无参字符串字面量
            sql = handler.buildSelectAllSql(tableInfo) + " WHERE "
                    + SqlFragments.guardWhereSection(whereSection, params);
            args = params.length > 0 ? params : null;
        }
        return queryRunner.query(sql, args, ResultSetHandlers.beanList(jdbcTemplate, entityClass));
    }

    // ===== DELETE =====

    /**
     * 清空表中所有数据（基于实体类）：解析实体得到表元数据后委托给 TableInfo 重载。
     */
    public void deleteAll(Class<?> cls) {
        TableInfo tableInfo = EntityParser.parse(cls);
        deleteAll(tableInfo);
    }

    /**
     * 清空表中所有数据（基于 TableInfo）。
     * <p>走 DELETE 而不是 TRUNCATE：保留事务可回滚性，也不会重置自增值——
     * 中间件不应该替业务做出"自增序列要不要归零"这种决定。</p>
     */
    public void deleteAll(TableInfo tableInfo) {
        if (jdbcTemplate.getBeanToDeleteHandler() == null) {
            throw new UnsupportedOperationException("未注册 deleteHandler");
        }
        queryRunner.update(jdbcTemplate.getBeanToDeleteHandler().buildDeleteAllSql(tableInfo));
    }

    /**
     * 根据主键删除单个实体。
     *
     * @param bean 实体对象（需包含主键字段值；主键为空会导致 WHERE 条件匹配不到行，返回 0）
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
     * 批量删除实体列表：按各实体主键构建 DELETE 并批量执行。
     * <p>所有实体必须为同一类型（SQL 只由第一个实体推导）。</p>
     * <p>当数据量超过 {@link #batchSize} 时自动分组提交。</p>
     * <p>
     * 与插入/更新不同，删除<b>没有</b>逐条降级：删不到行不是错误，
     * 结果数组里的 0 已经足够表达"这条没删到"，再套一层降级只会增加复杂度。
     * 分组后用 {@link #flattenResults} 按批次顺序拼回，保证下标仍对应原始列表位置。
     * </p>
     *
     * @param beans 实体列表
     * @return 各条语句影响的行数数组（下标与入参 beans 一一对应）
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
     *   <li>瞬时故障（死锁、锁超时、连接断开）——重试逐条只会放大故障；
     *       死锁时全表都在竞争，逐条重跑等于把一次冲突变成 N 次；</li>
     *   <li>事务内——批量中途失败时前面语句已生效，逐条补插会产生重复写，必须上抛由整个事务回滚。
     *       这条判断用 {@link JdbcTemplate#transactionActive()} 读线程事务状态，而不是
     *       {@link QueryRunner#isInTransaction()}：后者要等连接被惰性取过才准确，
     *       若异常发生在取连接之前（如 buildInsertParams 失败），事务内的批量会被误判为
     *       "非事务"而静默降级成逐条，破坏回滚语义。</li>
     * </ul>
     */
    private void rethrowWithoutFallback(Exception batchEx) {
        boolean retryable = batchEx instanceof DataAccessException dae && dae.isRetryable();
        if (retryable || jdbcTemplate.transactionActive()) {
            if (batchEx instanceof RuntimeException re) throw re;
            throw new IllegalStateException(batchEx);
        }
    }

    /**
     * 将多个分批结果数组合并为一个结果数组，保持批次先后顺序。
     * <p>调用方用返回数组的下标反查原始实体列表，所以这里必须按批次顺序拼接，
     * 乱序会让"第 i 条是否成功"错位到别的实体上。</p>
     *
     * @param resultsList 各批次的结果数组列表
     * @param totalSize   原始数据总条数（数组长度，尾部不足则留默认 0）
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
