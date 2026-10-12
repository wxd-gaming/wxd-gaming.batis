package com.wxdgaming.batis.sql;

import com.wxdgaming.batis.DbTemplate;
import com.wxdgaming.batis.cache.EntityCache;
import com.wxdgaming.batis.exception.DataAccessException;
import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.IndexInfo;
import com.wxdgaming.batis.model.TableInfo;
import com.wxdgaming.batis.parser.EntityParser;
import com.wxdgaming.batis.query.Criteria;
import com.wxdgaming.batis.query.Page;
import com.wxdgaming.batis.query.Pageable;
import com.wxdgaming.batis.sql.query.SelectQuery;
import com.wxdgaming.batis.sql.tx.ConnectionHolder;
import com.wxdgaming.batis.sql.tx.Propagation;
import com.wxdgaming.batis.sql.tx.TransactionStatus;
import com.wxdgaming.batis.sql.tx.TransactionTemplate;
import com.wxdgaming.batis.sql.tx.TxContext;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Function;

/**
 * jdbc 操作：模块对外的门面，同时充当方言 SPI 的装配容器
 * <p>
 * 一个 JdbcTemplate = 一个连接池 + 一套方言实现 + 一份表结构缓存。它自己几乎不干活，
 * 职责是三件事：
 * </p>
 * <ol>
 *   <li><b>装配</b>：持有 {@code dbMetaProvider}/{@code tableCreateHandler}/... 等一批方言处理器字段，
 *       由方言模块（如 {@code MysqlTemplate.initDefaults()}）用 setter 注入。之所以用 setter 而不是硬编码，
 *       是为了让 sql 层不依赖任何具体数据库：换方言只换注入，上层 Runner/DSL/事务代码一行不动。
 *       未注入的处理器对应的操作会抛可读的 UnsupportedOperationException；</li>
 *   <li><b>入口</b>：{@link #queryRunner()} / {@link #beanRunner()} / {@link #cachedBeanRunner()} /
 *       {@link #transactionTemplate()} / {@link #select(Class)} 是业务代码该拿的五个把手；</li>
 *   <li><b>元数据与 DDL</b>：{@link #loadAllTables()} 读库端结构进缓存，{@link #checkTable(Class)}
 *       做"实体 → 建表/结构 diff 同步"。</li>
 * </ol>
 * <p>
 * 线程安全：本实例可长期单例复用（字段除 tableCache 外都是启动期注入的只读引用）。
 * Runner 不是：每次操作新建、用完 close，见各类 Runner 的注释。
 * </p>
 * <p>
 * 事务连接的唯一借出口是 {@link #getConnection()}——将来读写分离/多数据源路由也收敛在这个点上，
 * 不要再在别处直接调 {@code dataSource.getConnection()}。
 * </p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-24 17:32
 **/
@Getter
@Setter
@Slf4j
public class JdbcTemplate extends DbTemplate {

    /**
     * 表结构缓存与 DDL 同步的互斥锁。
     * <p>{@link #checkTable(TableInfo)} 是"读缓存 → 可能建表/改表 → 刷新缓存"的复合操作，
     * 无锁时并发调用（多个线程同时启动同步、或同一张表被两个实体触发）会对同一张表重复下发 DDL。</p>
     */
    private final Object tableLock = new Object();

    /**
     * 连接池。final 是当前的简化：单数据源假设，读写分离（P2-1）会把它放宽成按 key 路由的集合，
     * 届时只需改 {@link #getConnection()} 内部，调用方无感。
     */
    private final HikariDataSource dataSource;

    /**
     * 表结构缓存，key 为小写表名。
     * <p>MySQL 在 Windows/Linux 下大小写敏感性不一致（lower_case_table_names），
     * 统一小写作 key 才能让"缓存命中"跨平台一致。置为 null 表示"未加载"，
     * 与空表区别开：null 会触发重新读库，空 map 表示"这个库里确实没有表"。</p>
     */
    private volatile HashMap<String, TableInfo> tableCache;
    /** 数据库元数据处理器（方言差异在此注入）：提供表/列/索引三段查询 SQL 与行映射 */
    private DbMetaHandler dbMetaProvider;
    /** 创建表的处理器 */
    private TableCreateHandler tableCreateHandler;
    /** 修改表的处理器：负责实体与库端结构的 diff 同步 */
    private TableAlterHandler tableAlterHandler;
    /** 列类型定义处理器（逻辑类型 → 物理类型渲染与规格化，由方言模块注入） */
    private ColumnTypeDefHandler columnTypeDefHandler;
    /** 删除表的处理器 */
    private TableDropHandler tableDropHandler;
    /** 截断表的处理器 */
    private TableTruncateHandler tableTruncateHandler;
    /** 读取实体的处理器（SELECT 行映射，由各数据库方言模块注入） */
    private SqlDbToBeanHandler sqlDbToBeanHandler;
    /** INSERT 实体处理器（由各数据库方言模块注入） */
    private BeanToInsertHandler beanToInsertHandler;
    /** UPDATE 实体处理器（由各数据库方言模块注入） */
    private BeanToUpdateHandler beanToUpdateHandler;
    /** 删除实体数据的处理器 */
    private BeanToDeleteHandler beanToDeleteHandler;
    /** SELECT 实体处理器（由各数据库方言模块注入，含引号风格与分页子句的默认实现） */
    private BeanToSelectHandler beanToSelectHandler;
    /** SQL 异常翻译器（方言注入，未设置时使用 DefaultSqlExceptionHandler 兜底） */
    private SqlExceptionHandler sqlExceptionHandler;

    /**
     * 是否允许 {@link #checkTable(TableInfo)} 删除"实体里没有声明"的索引，默认 true（既有语义）。
     * <p>
     * 关掉它的场景很具体：存量表首次接入框架时，数据库里往往有 DBA 手工建的索引（含全文索引、
     * 覆盖索引）而实体注解还没补齐，默认语义会在一次 checkTable 里把它们删掉且不留日志。
     * 关闭后框架只 warn 列出将被删除的索引名，不动库。
     * </p>
     */
    private boolean dropRedundantIndexes = true;

    /** 由池配置自建连接池：适用于独立使用；已持有 DataSource 时请用下面的重载，避免重复建池 */
    public JdbcTemplate(HikariConfig config) {
        this(new HikariDataSource(config));
    }

    /**
     * 复用外部 DataSource。
     * <p>构造不建表、不连库（Hikari 首次 getConnection 才真正建连），所以"启动时 new 一个模板"
     * 在没有数据库的环境里也不会失败——第一个 SQL 或 checkTable 才是真正接触库的时刻。</p>
     */
    public JdbcTemplate(HikariDataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 从数据库加载所有表结构到缓存。
     * <p>
     * 分三步查询（表列表 → 列信息 → 索引信息）而不是一条 JOIN 大 SQL：列和索引是两条独立的一对多，
     * 拼在一起会产生笛卡尔积并且很难让方言实现只覆盖差异。三段 SQL 与三段行映射都由
     * {@link #dbMetaProvider} 提供，所以 pgsql 将来只需换一个 provider。
     * </p>
     * <p>
     * 实现细节：整个加载共用一个 QueryRunner（一条连接读完所有表，避免 N 张表借 N 次连接）；
     * 列按库端顺序放进 LinkedHashMap，<b>顺序即库中的列序</b>——后续 ALTER 生成和 diff 依赖它；
     * 未注入 dbMetaProvider 或取不到 catalog 时静默返回，缓存保持原状。
     * </p>
     * <p>注意这是"全量替换"语义：构造新 map 后一次性赋值，不做增量合并，
     * 因此库端被手工删掉的表也会从缓存里消失。</p>
     */
    public void loadAllTables() {
        if (dbMetaProvider == null) return;
        synchronized (tableLock) {
            HashMap<String, TableInfo> cache = new HashMap<>();

            try (QueryRunner qr = new QueryRunner(this)) {
                String catalog = dbMetaProvider.getCatalog(this);
                if (catalog == null) return;

                List<TableInfo> tables = qr.query(
                        dbMetaProvider.tableListSql(),
                        new Object[]{catalog},
                        dbMetaProvider.tableListHandler()
                );

                for (TableInfo tableInfo : tables) {
                    String tableName = tableInfo.getTableName();
                    cache.put(tableName.toLowerCase(), readTable(qr, catalog, tableName, tableInfo));
                }
            }

            this.tableCache = cache;
        }
    }

    /**
     * 读取单张表的列与索引并填进给定的 TableInfo。
     * <p>列按库端顺序放进 LinkedHashMap，<b>顺序即库中的列序</b>——后续 ALTER 生成与 diff 依赖它；
     * key 统一小写，因为库端列名大小写与实体字段派生出的列名可能不一致，diff 时靠小写对齐。</p>
     */
    private TableInfo readTable(QueryRunner qr, String catalog, String tableName, TableInfo target) {
        List<ColumnInfo> columns = qr.query(
                dbMetaProvider.columnListSql(),
                new Object[]{catalog, tableName},
                dbMetaProvider.columnListHandler()
        );

        List<IndexInfo> indexes = qr.query(
                dbMetaProvider.indexListSql(),
                new Object[]{catalog, tableName},
                dbMetaProvider.indexListHandler()
        );

        LinkedHashMap<String, ColumnInfo> columnMap = new LinkedHashMap<>();
        for (ColumnInfo col : columns) {
            columnMap.put(col.getColumnName().toLowerCase(), col);
        }
        target.setColumns(columnMap);
        target.setIndexes(indexes);
        return target;
    }

    /**
     * 单表增量刷新：只重读这一张表并并进缓存（copy-on-write，不动其他线程正在读的旧 map）。
     * <p>
     * 为什么不全量 {@link #loadAllTables()}：checkTable 常在启动期被逐实体调用，而全量加载是
     * "1 条表列表 + 每表 2 条（列/索引）"，N 个实体就是 N×(1+2N) 条元数据查询。
     * 单表刷新把这一步压回常数级（3 条查询）。
     * </p>
     * <p>表已从库里消失时把它从缓存摘掉；刷新过程出错则清空缓存，让下一次走全量加载兜底。</p>
     */
    private void refreshTableCache(String tableNameLower) {
        if (dbMetaProvider == null) return;
        synchronized (tableLock) {
            try (QueryRunner qr = new QueryRunner(this)) {
                String catalog = dbMetaProvider.getCatalog(this);
                if (catalog == null) {
                    clearTableCache();
                    return;
                }
                List<TableInfo> tables = qr.query(
                        dbMetaProvider.tableListSql(),
                        new Object[]{catalog},
                        dbMetaProvider.tableListHandler()
                );
                TableInfo fresh = null;
                for (TableInfo ti : tables) {
                    if (ti.getTableName() != null && ti.getTableName().equalsIgnoreCase(tableNameLower)) {
                        fresh = ti;
                        break;
                    }
                }
                HashMap<String, TableInfo> copy = tableCache != null
                        ? new HashMap<>(tableCache) : new HashMap<>();
                if (fresh == null) {
                    copy.remove(tableNameLower);
                } else {
                    copy.put(tableNameLower, readTable(qr, catalog, fresh.getTableName(), fresh));
                }
                this.tableCache = copy;
            } catch (RuntimeException e) {
                log.warn("单表结构刷新失败，回退为清空缓存（下次 checkTable 走全量加载）: {}", e.getMessage());
                clearTableCache();
            }
        }
    }

    /** 新建一个执行器（独立持有连接，必须 close）。事务内使用也走这里：Runner 会自动改用事务连接 */
    public QueryRunner queryRunner() {
        return new QueryRunner(this);
    }

    /**
     * 借出连接的唯一入口：当前线程绑定了本模板的事务时返回事务连接，否则从连接池借出。
     * <p>
     * "同线程同事务同连接"这条不变式全靠这里收敛实现——BeanRunner/CachedBeanRunner/DSL
     * 的 JDBC 旁路都调它而不是直接找池要。事务内绕过本方法去借连接，会读到旧数据（另一条连接的视图），
     * 这是最难查的一类 bug。
     * </p>
     * <p>返回的连接是否需要在用完后 close，取决于来源：事务连接绝不能 close（等于把事务连接还给池）。
     * 因此业务代码更应优先用 {@link #queryRunner()}，把归还判断交给它。</p>
     */
    public Connection getConnection() throws SQLException {
        ConnectionHolder holder = TxContext.get(this);
        if (holder != null && holder.isTransactional()) {
            return holder.getConnection();
        }
        return dataSource.getConnection();
    }

    /** 当前线程是否处于本模板的事务中（注意：与 QueryRunner.isInTransaction 不同，本方法看线程，不看某个 Runner 是否已取过连接） */
    public boolean transactionActive() {
        return TxContext.isActive(this);
    }

    /** 创建绑定本模板的事务模板（轻量对象，可每次现建） */
    public TransactionTemplate transactionTemplate() {
        return new TransactionTemplate(this);
    }

    /**
     * 以 REQUIRED 传播执行一段事务逻辑（推荐写法，省掉 new TransactionTemplate）。
     * <p>回调抛出的任何异常都会导致回滚并原样上抛；需要"提交后才知道结果"的场景请配合
     * {@code TransactionSynchronization}，不要在回调里直接发外部通知。</p>
     */
    public <T> T inTransaction(Function<TransactionStatus, T> work) {
        return transactionTemplate().execute(status -> work.apply(status));
    }

    /** 按指定传播行为执行一段事务逻辑（REQUIRES_NEW 会额外占用一条连接，注意池大小） */
    public <T> T inTransaction(Propagation propagation, Function<TransactionStatus, T> work) {
        return transactionTemplate().execute(propagation, status -> work.apply(status));
    }

    // ===== 查询 DSL 便捷入口 =====
    // 以下四个方法都是 select(Class) 的语法糖：内部各建一个 SelectQuery，不缓存语句，
    // 每次调用重新解析实体与展平条件——好处是绝无"条件对象被跨查询复用"的串味问题。

    /** 创建实体查询 DSL 构建器 */
    public <T> SelectQuery<T> select(Class<T> entityClass) {
        return new SelectQuery<>(this, entityClass);
    }

    /** 条件查询列表（where 传 null 等价于全表，注意数据量） */
    public <T> List<T> queryList(Class<T> entityClass, Criteria where) {
        return select(entityClass).where(where).list();
    }

    /** 条件查询唯一记录（多于 1 条抛 TooManyResultsException，而不是静默取第一条） */
    public <T> T queryOne(Class<T> entityClass, Criteria where) {
        return select(entityClass).where(where).one();
    }

    /** 条件计数（自动生成 COUNT 语句，复用同一份 WHERE 参数） */
    public <T> long count(Class<T> entityClass, Criteria where) {
        return select(entityClass).where(where).count();
    }

    /** 分页查询（一次 count + 一次取页，返回带总数/页码的 Page） */
    public <T> Page<T> queryPage(Class<T> entityClass, Criteria where, Pageable pageable) {
        return select(entityClass).where(where).pageResult(pageable);
    }

    /**
     * 把 SQLException 翻译成统一的 DataAccessException
     * <p>优先使用方言注册的 {@link SqlExceptionHandler}，未注册时按 SQLState/关键字兜底。</p>
     * <p>放在这里而不是每个 Runner 里，是为了让"翻译策略"成为可注入的方言行为：
     * 数字错误码只有 MySQL 认得，而兜底逻辑（SQLState 类别 + message 关键字）是通用的。
     * 翻译结果携带 sql/params，排障时不需要再从别处拼上下文。</p>
     *
     * @param ex     原始 SQL 异常
     * @param sql    触发异常的 SQL（可为 null）
     * @param params 绑定参数（可为 null）
     */
    public DataAccessException translateException(SQLException ex, String sql, Object[] params) {
        SqlExceptionHandler handler = sqlExceptionHandler != null
                ? sqlExceptionHandler : DefaultSqlExceptionHandler.INSTANCE;
        return handler.translate(ex, sql, params);
    }

    /** 新建实体操作器（独立模式，用完 close） */
    public BeanRunner beanRunner() {
        return new BeanRunner(this);
    }

    /**
     * 创建带缓存的 BeanRunner（默认 Caffeine 本地缓存 + 延迟刷盘模式）
     * <p>单例复用即可，线程安全。</p>
     * <p>提示：Write-Behind 的脏表是<b>进程全局</b>的，不是每个实例一份；
     * 反复 new 出来的多个 CachedBeanRunner 共享同一份待刷数据，
     * 所以刷盘/统计要在业务层收敛成一个入口，别指望"我这个实例的脏数据"。</p>
     */
    public CachedBeanRunner cachedBeanRunner() {
        return new CachedBeanRunner(this);
    }

    /**
     * 创建带自定义缓存的 BeanRunner（换缓存实现不影响脏表与刷盘逻辑，
     * 只要 {@link EntityCache} 的 remove/putIfAbsent 语义保持原子）
     */
    public CachedBeanRunner cachedBeanRunner(EntityCache<String, Object> cache) {
        return new CachedBeanRunner(this, cache);
    }

    /**
     * 检查并同步表结构
     * <p>先解析实体类得到 {@link TableInfo}，再委托给 {@link #checkTable(TableInfo)}</p>
     * <p>解析用的是 EntityParser 的全局缓存：同一实体重复调用不会重复反射，
     * 但注意它缓存的是"上次解析结果"，运行期改注解字段不会自动失效。</p>
     *
     * @param cls 实体类
     */
    public void checkTable(Class<?> cls) {
        TableInfo tableInfo = EntityParser.parse(cls);
        checkTable(tableInfo);
    }

    /**
     * 检查并同步表结构（启动期建表与运行期结构演进的唯一入口）。
     * <p>三条路径：</p>
     * <ol>
     *   <li>缓存为 null → 先 {@link #loadAllTables()} 全量读库；</li>
     *   <li>缓存里没有这张表 → 交给 {@link TableCreateHandler} 建表，建完只<b>单表增量刷新</b>，
     *       让后续操作能立刻读到新表；</li>
     *   <li>缓存里有 → 交给 {@link TableAlterHandler} 逐列 diff，只在真的不同时才发 ALTER，
     *       执行后同样只刷新这一张表。</li>
     * </ol>
     * <p>整段以 {@code tableLock} 互斥：并发调用不会对同一张表重复下发 DDL。</p>
     * <p>
     * <b>本方法必须幂等</b>：同一实体反复调用应当零 ALTER。它常在应用启动时被批量调用，
     * 一旦渲染/规格化不等价就会产生"虚假漂移"，每次启动都刷一遍表结构（锁表 + binlog 膨胀），
     * 这个回归由 SchemaDriftIT 的用例②锁住；而"真实漂移必须被修回来"由用例③锁住——
     * 两者成对，缺一个另一个就可能空跑通过。
     * </p>
     * <p>未注入对应处理器时静默跳过（不报错）：允许"只用查询、不管 DDL"的轻量装配。</p>
     *
     * @param tableInfo 实体解析得到的表元数据
     */
    public void checkTable(TableInfo tableInfo) {
        String tableName = tableInfo.getTableName().toLowerCase();
        // 整段互斥：并发调用同一模板时，避免对同一张表重复建表/重复 ALTER，也避免刷新缓存与 diff 交叉
        synchronized (tableLock) {
            if (tableCache == null) {
                loadAllTables();
            }
            TableInfo dbTable = tableCache != null ? tableCache.get(tableName) : null;
            if (dbTable == null) {
                if (tableCreateHandler != null) {
                    tableCreateHandler.doAction(this, tableInfo);
                    // 只刷新这一张表：否则 N 个实体各调一次 checkTable 就是 N×(1+2N) 条元数据查询
                    refreshTableCache(tableName);
                }
            } else {
                if (tableAlterHandler != null) {
                    tableAlterHandler.doAction(this, tableInfo);
                    // 变更后刷新缓存：diff 的基准必须是库端最新状态
                    refreshTableCache(tableName);
                }
            }
        }
    }

    /**
     * 清空表结构缓存，下次调用 checkTable 时会重新加载。
     * <p>除了"让 checkTable 重新走库端 diff"之外，它还是测试里证明"确实比对了库端而非框架内存"的开关；
     * 库端被手工改过结构（含分区、外部 DDL）后也要靠它让框架重新认识库。</p>
     */
    public void clearTableCache() {
        this.tableCache = null;
    }

    // ===== Drop / Truncate / Delete 便捷方法 =====

    /**
     * 删除表（基于实体类）
     */
    public void dropTable(Class<?> cls) {
        TableInfo tableInfo = EntityParser.parse(cls);
        dropTable(tableInfo);
    }

    /**
     * 删除表（基于 TableInfo）。
     * <p>drop 之后必须 {@link #clearTableCache()}：缓存里留着这张表，
     * 后续 checkTable 会走"表存在 → ALTER"分支去改一张已经不存在的表。</p>
     */
    public void dropTable(TableInfo tableInfo) {
        if (tableDropHandler != null) {
            tableDropHandler.doAction(this, tableInfo);
            clearTableCache();
        }
    }

    /**
     * 截断表（基于实体类）
     */
    public void truncateTable(Class<?> cls) {
        TableInfo tableInfo = EntityParser.parse(cls);
        truncateTable(tableInfo);
    }

    /**
     * 截断表（基于 TableInfo）。
     * <p>TRUNCATE 是 DDL：多数数据库里隐式提交、不可回滚，因此不要在事务里指望它跟事务一起撤销。
     * 结构未变所以不刷缓存；但会重置自增计数，与 {@link BeanRunner#deleteAll} 的 DELETE 语义不同。</p>
     */
    public void truncateTable(TableInfo tableInfo) {
        if (tableTruncateHandler != null) {
            tableTruncateHandler.doAction(this, tableInfo);
        }
    }

}
