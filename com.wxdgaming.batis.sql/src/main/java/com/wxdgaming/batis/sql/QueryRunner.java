package com.wxdgaming.batis.sql;

import com.wxdgaming.batis.exception.BatchUpdateFailedException;
import com.wxdgaming.batis.exception.DataAccessException;
import com.wxdgaming.batis.exception.IllegalTransactionStateException;
import com.wxdgaming.batis.sql.tx.ConnectionHolder;
import com.wxdgaming.batis.sql.tx.TxContext;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/**
 * 执行器：框架内唯一面向 JDBC 的出口
 * <p>
 * 本类是"一次数据库交互"的载体，所有 SQL（业务 SQL、DDL、批量）最终都从这里落到连接上，
 * 因此它集中承担了四件横切职责，上层的 {@link BeanRunner}、{@link CachedBeanRunner}、
 * DSL、事务模板都不再各自重复实现：
 * </p>
 * <ol>
 *   <li><b>连接来源判定</b>：构造时不借连接，首次真正执行 SQL 才调用 {@link #getConnection()}，
 *       按"显式注入 &gt; 当前线程事务绑定 &gt; 连接池借出"的优先级决定连接来自哪里（见 {@link Source}）；</li>
 *   <li><b>归还语义</b>：只有池借出的连接才在 {@link #close()} 时归还，
 *       事务连接归还给 {@code TransactionTemplate}、外部连接归还给调用方，避免 Runner 误提交/误断事务；</li>
 *   <li><b>异常翻译</b>：所有 {@code SQLException} 一律经 {@link JdbcTemplate#translateException}
 *       换成带 sql/参数/错误码的 {@code DataAccessException} 体系再上抛；</li>
 *   <li><b>可观测性</b>：五个执行入口统一走 {@link #timed}，debug 级别打开即输出 SQL/参数/结果/耗时，
 *       关闭则完全短路（不取时间、不格式化参数）。</li>
 * </ol>
 * <p>
 * 用法约定：一个 Runner 对应一段逻辑操作，推荐 try-with-resources；它不是线程安全的，
 * 不要跨线程共享，也不要长期持有（长期持有会把一条池连接占死）。事务内使用时直接
 * {@code new QueryRunner(jdbcTemplate)} 即可——线程上绑定了事务连接时会自动复用，
 * 这正是 {@link #getConnection()} 存在的意义（同事务同连接，将来读写分离也在这个点收敛）。
 * </p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-24 17:33
 **/
@Getter
@Slf4j
public class QueryRunner implements AutoCloseable {

    /**
     * 连接来源：决定 {@link #close()} 到底要不要动这条连接。
     * <ul>
     *   <li>{@code EXTERNAL}：构造时外部注入，连接生死归调用方，Runner 一律不碰；</li>
     *   <li>{@code TRANSACTION}：当前线程有事务，连接归 {@code TransactionTemplate} 管，
     *       Runner 若在这里 close() 等于把事务的连接还给池，外层后续 SQL 会踩到已归还连接（假提交/串数据）；</li>
     *   <li>{@code POOLED}：自己从池借的，只有这一种才在 close() 时归还。</li>
     * </ul>
     */
    public enum Source { EXTERNAL, TRANSACTION, POOLED }

    private final JdbcTemplate jdbcTemplate;
    /** 实际使用的连接；惰性赋值，从未执行过 SQL 时为 null，close() 因此可以安全空跑 */
    private Connection connection;
    /** 当前绑定的事务连接持有者，source == TRANSACTION 时非空，用于取事务级配置（如语句超时） */
    private ConnectionHolder txHolder;
    private Source source;

    /**
     * 常规构造：不立即借连接，只记录"将来若要借，从哪个池借"。
     * <p>真正的来源判定推迟到首次 {@link #getConnection()}，这样"new 了但没执行"的路径
     * （例如条件短路、纯缓存命中）不会占用池连接。</p>
     */
    public QueryRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.source = Source.POOLED;
    }

    /**
     * 外部连接构造：把已经握在手里的连接交给 Runner 代跑 SQL。
     * <p>用于调用方自己管理事务/连接的场景（测试、和第三方 JDBC 代码共用同一连接）。
     * 代价是 Runner 失去事务感知能力——它不知道这条连接是否在事务里，
     * {@link #executeBatch} 会按"非事务"分支自己 setAutoCommit/commit，所以业务代码请优先用无参形式。</p>
     */
    public QueryRunner(JdbcTemplate jdbcTemplate, Connection connection) {
        this.jdbcTemplate = jdbcTemplate;
        this.connection = connection;
        this.source = Source.EXTERNAL;
    }

    /**
     * 取得本次执行使用的连接（全框架唯一的借出口）。
     * <p>
     * 优先级：显式注入 &gt; 当前线程事务绑定 &gt; 池借出。判定只做一次并缓存到字段上，
     * 之后 {@link #close()} 依 {@link #source} 决定归还方式，所以这里"选中事务连接"这一件事
     * 必须同时把 source 改成 TRANSACTION —— 漏改会让 close() 把别人的事务连接还给池，
     * 是本类最容易造成数据事故的一处。
     * </p>
     * <p>{@code holder.isTransactional()} 的过滤是必要的：SUPPORTS 传播在无事务时也会挂一个
     * 非事务的 holder 在线程上，那种连接没有"事务"可依附，不该被 Runner 当作事务连接复用。</p>
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

    /**
     * 当前 Runner 是否已绑定到线程事务。
     * <p>注意它是"执行过 SQL 之后"才准确的：连接未惰性获取时为 false。
     * 因此需要事务感知的分支判断（如 {@link #executeBatch}）都先调 {@code getConnection()} 再问它。</p>
     */
    public boolean isInTransaction() {
        return source == Source.TRANSACTION;
    }

    /** 是否需要由 close() 归还连接（外部注入和事务绑定的不归还） */
    public boolean isCloseConnection() {
        return source == Source.POOLED;
    }

    /**
     * 只读事务写保护。
     * <p>刻意从 {@link TxContext} 直接读线程状态，而不是看本 Runner 的 source：
     * 只读事务里"还没执行过 SQL 的 Runner"也必须被拦住，否则保护取决于调用顺序而非意图。
     * 目的是把"只读"从一句注释变成硬约束——驱动和 MySQL 的只读连接并不阻止所有写。</p>
     */
    public void checkWritable() {
        if (TxContext.isCurrentReadOnly(jdbcTemplate)) {
            throw new IllegalTransactionStateException("只读事务中不允许执行写操作");
        }
    }

    /**
     * 为事务内语句应用语句级超时。
     * <p>只对 TRANSACTION 来源生效：非事务的单条语句由调用方自己控制生命周期，
     * 而事务里一条慢 SQL 会长期占住连接和行锁，必须由事务配置的 timeout 兜住。</p>
     */
    public void applyQueryTimeout(Statement stmt) throws SQLException {
        if (source == Source.TRANSACTION && txHolder != null && txHolder.getQueryTimeoutSeconds() > 0) {
            stmt.setQueryTimeout(txHolder.getQueryTimeoutSeconds());
        }
    }

    /**
     * 释放本 Runner 占用的资源。
     * <p>
     * 只有 {@code POOLED} 连接才真正 close（Hikari 语义下即归还池）；
     * {@code TRANSACTION} / {@code EXTERNAL} 只丢弃引用，连接生死归事务模板或调用方。
     * 字段随后置为 null，因此重复 close 是幂等的（try-with-resources 与手工 close 并存也不会二次归还）。
     * </p>
     */
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
        // 复位来源，避免关闭后 isInTransaction() 仍按旧 source 返回 true（残留的事务状态）
        source = Source.POOLED;
    }

    /**
     * 执行查询（无参版本，委托给带参重载，保证日志/异常/超时链路只有一个实现）。
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
     * 执行查询：五个入口中唯一不做 {@link #checkWritable()} 的（读操作在只读事务里合法）。
     * <p>
     * 结果形态完全由 handler 决定，本方法不关心也不加工；{@code stmt}/{@code rs} 都在
     * try-with-resources 里关闭，因此 handler 抛异常也不会漏游标。代价是 handler 只能在
     * {@code handle} 内把结果读成普通对象，不能把 ResultSet 逃逸出去（游标已关）。
     * </p>
     *
     * @param sql     查询语句（占位符顺序必须与 args 一致）
     * @param args    参数数组，null 视为无参
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
     * 执行 SQL（DDL / DML），返回 JDBC 的"是否第一个结果是ResultSet"标记。
     * <p>注意返回值语义弱：DDL 与影响 0 行的 DML 都返回 false，不能用来判断成败，
     * 失败一律以异常形式上抛。需要影响行数请用 {@link #update}。</p>
     *
     * @param sql 任意 SQL 语句
     */
    public boolean execute(String sql) {
        return execute(sql, null);
    }

    /**
     * 执行带参数的 SQL（DDL / DML），适合"不需要知道结果"的语句。
     * <p>DDL 同样走写保护：只读事务里不允许改表结构，避免把结构变更混进业务只读事务。</p>
     *
     * @param sql  带占位符的 SQL
     * @param args 参数数组，null 视为无参
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
     * 执行 DML（INSERT / UPDATE / DELETE），返回影响行数。
     * <p>返回 0 不表示失败，只表示"没行匹配"——DSL 层的乐观锁（{@code @Version}）
     * 正是把 0 行解释成冲突，调用方要按语义判断而不是按大于 0 判断成功。</p>
     *
     * @param sql DML 语句
     */
    public int update(String sql) {
        return update(sql, null);
    }

    /**
     * 执行带参数的 DML（INSERT / UPDATE / DELETE），返回影响行数。
     * <p>事务内调用时不会自行提交，提交时机归 {@code TransactionTemplate}；
     * 非事务时由连接的 autoCommit 决定即时落库。</p>
     *
     * @param sql  带占位符的 DML 语句
     * @param args 参数数组，null 视为无参
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
     * 执行 INSERT 并回取自增主键（仅支持单主键列）。
     * <p>
     * 让驱动记录生成键，插入后从 {@code getGeneratedKeys()} 取第一列。返回 null 的三种合法情形：表无自增列、
     * 驱动未回传、回传值不是数值（如字符串主键）——所以调用方（{@link BeanRunner}）
     * 必须容忍 null，不能把"没拿到 ID"当成失败。
     * </p>
     * <p>不指定自增列名的便捷形态；实体侧调用请用
     * {@link #executeInsert(String, Object[], String)}，理由见该方法。</p>
     *
     * @param sql  INSERT 语句
     * @param args 参数数组
     * @return 自增主键，无自增列或无法获取时返回 null
     */
    public Number executeInsert(String sql, Object[] args) {
        return executeInsert(sql, args, null);
    }

    /**
     * 执行 INSERT 并回取自增主键，可按列名向驱动索取生成键。
     * <p>WHY 需要 {@code identityColumn} 这一形态：pgjdbc 把 {@code RETURN_GENERATED_KEYS}
     * 实现成 {@code RETURNING *}，生成键结果集的第一列因此是表的第一个字段而非自增列——
     * 主键不在首列的实体会把别的列的值当成 ID 回写。指定列名后两种驱动都只回传自增列。</p>
     *
     * @param sql            INSERT 语句
     * @param args           参数数组
     * @param identityColumn 自增列名，null 表示沿用 {@code RETURN_GENERATED_KEYS}
     * @return 自增主键，无自增列或无法获取时返回 null
     */
    public Number executeInsert(String sql, Object[] args, String identityColumn) {
        checkWritable();
        return timed("插入", sql, args, () -> {
            try {
                Connection conn = getConnection();
                // 声明需要生成键：必须在 prepareStatement 时提出，事后无法补取
                try (PreparedStatement stmt = prepareForGeneratedKeys(conn, sql, identityColumn)) {
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
     * 为"插入后取生成键"预编译语句：给了列名就按列名索取，否则退回通用的 {@code RETURN_GENERATED_KEYS}。
     * <p>包内可见，让 {@link BeanRunner} 的批量插入走同一套取键口径。</p>
     */
    static PreparedStatement prepareForGeneratedKeys(Connection conn, String sql, String identityColumn)
            throws SQLException {
        return identityColumn != null && !identityColumn.isEmpty()
                ? conn.prepareStatement(sql, new String[]{identityColumn})
                : conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
    }

    /**
     * 批量执行同一 SQL 的多组参数。
     * <p>
     * <b>两条分支的区别是修复过的正确性事故点</b>：早期实现无条件
     * {@code setAutoCommit(false) + commit}，一旦在外部事务里调用，就会把整条连接切成
     * 手动提交再 commit——等于把外层事务尚未完成的工作<em>静默提前提交</em>，
     * 外层随后 rollback 也只能回滚到批量之后，破坏"全有或全无"。
     * 现在先取连接再判 {@link #isInTransaction()}：
     * </p>
     * <ol>
     *   <li>事务内：只 addBatch/executeBatch，既不 commit 也不 rollback，也不碰 autoCommit，
     *       提交时机交给外层 {@code TransactionTemplate}；</li>
     *   <li>非事务：把这段批量自身包成一个小事务（关 autoCommit → executeBatch → commit），
     *       失败回滚并把 autoCommit 恢复原值，避免污染池中连接的后续使用者。</li>
     * </ol>
     * <p>日志只打参数组数与耗时，逐组参数不落日志（批量常达数万条，会刷爆磁盘并把业务数据整份复制进日志）。</p>
     *
     * @param sql       带占位符的 SQL
     * @param batchArgs 批量参数列表，每个元素是一组参数
     * @return 每条语句影响的行数数组（驱动可能返回 {@code Statement.SUCCESS_NO_INFO}，
     *         求和时按非负处理，见 {@link #sumRows}）
     */
    public int[] executeBatch(String sql, List<Object[]> batchArgs) {
        // 空批次直接短路：既避免裸 NPE（batchArgs == null），也不为"什么都不做"去借一条连接
        if (batchArgs == null || batchArgs.isEmpty()) {
            return new int[0];
        }
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
                    addBatch(stmt, batchArgs);
                    int[] result = stmt.executeBatch();
                    checkExecuteFailures(result, batchArgs.size(), sql);
                    if (dbg) log.debug("[批量] 完成，耗时 {} ms，影响 {} 行",
                            (System.nanoTime() - t0) / 1_000_000, sumRows(result));
                    return result;
                }
            }
            // 非事务分支：先记住原值，finally 一定恢复——池连接是被复用的，
            // 留下 autoCommit=false 会让下一个使用者莫名"提交不了"
            boolean autoCommit = conn.getAutoCommit();
            try {
                conn.setAutoCommit(false);
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    addBatch(stmt, batchArgs);
                    int[] result = stmt.executeBatch();
                    // 驱动以 EXECUTE_FAILED 表达失败而不抛异常时，绝不能继续 commit：
                    // 那会把"部分成功"永久落库，而调用方以为整批都成了
                    checkExecuteFailures(result, batchArgs.size(), sql);
                    conn.commit();
                    if (dbg) log.debug("[批量] 完成，耗时 {} ms，影响 {} 行",
                            (System.nanoTime() - t0) / 1_000_000, sumRows(result));
                    return result;
                }
            } catch (SQLException e) {
                // 回滚失败不覆盖原始异常：真正的病因是 executeBatch 抛出的那个
                rollbackQuietly(conn);
                throw translateBatchException(e, sql, batchArgs.size());
            } catch (RuntimeException e) {
                // 本地检测到的部分失败：先把这一小批回滚掉，
                // 否则 finally 里恢复 autoCommit 会把它隐式提交
                rollbackQuietly(conn);
                throw e;
            } finally {
                restoreAutoCommitQuietly(conn, autoCommit);
            }
        } catch (SQLException e) {
            throw translateBatchException(e, sql, batchArgs.size());
        }
    }

    /** 绑定并累积一批参数（executeBatch 的两个分支共用，避免下标与空值处理分叉） */
    private static void addBatch(PreparedStatement stmt, List<Object[]> batchArgs) throws SQLException {
        for (Object[] args : batchArgs) {
            bind(stmt, args);
            stmt.addBatch();
        }
    }

    /**
     * 校验批量返回值：驱动可能不抛异常，而是用 {@code Statement.EXECUTE_FAILED(-3)} 表达"该条失败"
     * （如 {@code continueBatchOnError=true}）。这种"部分成功"必须当场中断，否则
     * {@code BatchResult} 会把失败条目记成成功，插入路径还会因生成键按序 zip 而写错主键。
     *
     * @param results  驱动返回的逐条结果
     * @param expected 本次提交的条目数
     * @param sql      触发批量的 SQL（进异常上下文）
     */
    // 包内可见：BeanRunner 的批量插入旁路（要回填生成键，不能走本类）复用同一套校验口径
    void checkExecuteFailures(int[] results, int expected, String sql) {
        int failedIndex = firstExecuteFailure(results, expected);
        if (failedIndex >= 0) {
            throw new BatchUpdateFailedException(
                    "批量执行存在失败条目（首个失败下标 " + failedIndex + "，本批共 " + expected
                            + " 条）：驱动以 EXECUTE_FAILED 返回而未抛异常，已中断本批以避免部分提交",
                    sql, null, 0, null, failedIndex, null);
        }
    }

    /**
     * 定位首个失败条目下标。
     * <p>
     * 两种来源都适用：① 返回值里显式的 {@code EXECUTE_FAILED}；② 返回数组比提交条数短
     * —— 驱动在抛 {@code BatchUpdateException} 时（默认 {@code continueBatchOnError=false}），
     * {@code getUpdateCounts()} 只给到失败之前的语句，缺的那个位置就是失败点。
     * </p>
     *
     * @return 失败下标；全部成功或无信息时返回 -1
     */
    private static int firstExecuteFailure(int[] results, int expected) {
        if (results == null) return -1;
        for (int i = 0; i < expected; i++) {
            if (i >= results.length) return i;
            if (results[i] == Statement.EXECUTE_FAILED) return i;
        }
        return -1;
    }

    /**
     * 批量异常翻译：{@link BatchUpdateException} 携带的 {@code getUpdateCounts()}
     * 是唯一能定位"第几条失败"的信息，不能像普通异常那样一译了之。
     */
    // 包内可见：与 checkExecuteFailures 一样供 BeanRunner 的批量插入旁路复用
    DataAccessException translateBatchException(SQLException e, String sql, int expected) {
        if (e instanceof BatchUpdateException bue) {
            int failedIndex = firstExecuteFailure(bue.getUpdateCounts(), expected);
            return new BatchUpdateFailedException(
                    "批量执行失败"
                            + (failedIndex >= 0 ? "（首个失败下标 " + failedIndex + "）" : "（驱动未提供可靠下标）"),
                    sql, null, bue.getErrorCode(), bue.getSQLState(), failedIndex, bue);
        }
        return jdbcTemplate.translateException(e, sql, null);
    }

    /** 回滚并吞掉回滚自身的异常：调用点正在上抛真正的病因 */
    private static void rollbackQuietly(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException ignored) {
        }
    }

    /** 恢复 autoCommit：失败也吞掉，池会在归还连接时重置 */
    private static void restoreAutoCommitQuietly(Connection conn, boolean autoCommit) {
        try {
            conn.setAutoCommit(autoCommit);
        } catch (SQLException ignored) {
        }
    }

    // ============== 执行过程 debug 日志 ==============
    // 下列私有方法只为日志与参数绑定服务，不参与业务语义；开关即 logger 级别，无第二套框架开关。

    /**
     * 绑定参数（null 安全）。
     * <p>JDBC 占位符下标从 1 开始，这里统一 +1，避免每个入口各写一遍下标换算。
     * 值一律用 {@code setObject} 交给驱动推断类型，自定义类型（JSON/BLOB/枚举）
     * 在进入本方法前已由 {@code BeanValueHandler} 转成 JDBC 认识的形态。</p>
     */
    private static void bind(PreparedStatement stmt, Object[] args) throws SQLException {
        if (args == null) return;
        for (int i = 0; i < args.length; i++) {
            stmt.setObject(i + 1, args[i]);
        }
    }

    /**
     * 统一执行埋点：debug 关闭时零额外开销（不取时间、不格式化参数），
     * debug 打开时输出"入参 → 结果/失败 + 耗时"三件套。
     * <p>
     * 之所以做成包裹 lambda 而不是前后各打一行：失败路径也必须带同一条 SQL 和同一份参数，
     * 否则线上只有一句堆栈、拿不到当时究竟执行了什么。{@code body} 内部已把
     * {@code SQLException} 翻译成 {@code DataAccessException}，所以这里只捕获运行时异常。
     * </p>
     * <p>参数经 {@link #formatParams} 只做 char[] 脱敏（char[] 是密码等敏感数据的惯用载体，
     * 逐字符打印等于明文外泄），其余直接 toString：只用于人眼排查，不做截断，
     * 因此这条日志属于"排障时临时打开"的级别，不建议生产常开 DEBUG。</p>
     */
    private static <R> R timed(String kind, String sql, Object[] args, SupplierWithException<R> body) {
        if (!log.isDebugEnabled()) {
            return body.get();
        }
        long t0 = System.nanoTime();
        String params = formatParams(args);
        try {
            R result = body.get();
            log.debug("[{}] {} -- 参数: {} -- 结果: {} -- 耗时 {} ms",
                    kind, oneLine(sql), params, describe(result),
                    (System.nanoTime() - t0) / 1_000_000);
            return result;
        } catch (RuntimeException | Error e) {
            // 失败日志保留原始异常信息但不打整段堆栈（堆栈由上层调用方按需记录）
            log.debug("[{}] 失败 {} -- 参数: {} -- 耗时 {} ms -- {}",
                    kind, oneLine(sql), params,
                    (System.nanoTime() - t0) / 1_000_000, e.getMessage());
            throw e;
        }
    }

    /** timed 的入参形态：允许内部抛运行时异常，因此不需要受检异常签名 */
    @FunctionalInterface
    private interface SupplierWithException<R> {
        R get();
    }

    /**
     * 参数数组转日志字符串：char[] 打占位符，其余照原样。
     * <p>只脱敏 char[] 是因为它在 Java 生态里是"敏感数据"的约定载体（如 JDBC 密码），
     * 没有一套字段级敏感标注机制前，对普通 String 逐值猜测关键字反而会漏报或误报。</p>
     */
    private static String formatParams(Object[] args) {
        if (args == null) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            Object a = args[i];
            if (a instanceof char[]) {
                sb.append("[***]");
            } else if (a instanceof byte[]) {
                sb.append(Arrays.toString((byte[]) a));
            } else {
                sb.append(a);
            }
        }
        return sb.append(']').toString();
    }

    /**
     * 结果摘要：数值直接展示（影响行数/生成键），集合展示条数，其余省略。
     * <p>刻意不打印实体内容——查询结果可能是上万行对象，日志的目的在于"跑没跑、跑了多久、动了多少行"，
     * 不在于复述数据。</p>
     */
    private static String describe(Object result) {
        if (result instanceof Number || result instanceof Boolean) return String.valueOf(result);
        if (result instanceof List<?> list) return list.size() + " 条";
        if (result instanceof int[] arr) return arr.length + " 条";
        return result == null ? "null" : result.getClass().getSimpleName();
    }

    /**
     * 批量影响行数求和。
     * <p>{@code Math.max(r, 0)} 是因为驱动对可跳过统计的语句返回
     * {@code Statement.SUCCESS_NO_INFO}（-2），直接相加会得到负数这种荒谬结论；
     * 同理 -1 也可能来自不支持行数的方言驱动，这里统一按"不计入"处理。</p>
     */
    private static long sumRows(int[] rows) {
        long total = 0;
        for (int r : rows) total += Math.max(r, 0);
        return total;
    }

    /**
     * SQL 压成单行，便于日志检索（多行 SQL 在 grep/ELK 里会断成若干条无关记录）。
     * <p>只做空白折叠，不改内容，所以日志里看到的仍是可直接复制执行的语句。</p>
     */
    private static String oneLine(String sql) {
        return sql == null ? "" : sql.replaceAll("\\s+", " ").trim();
    }

}
