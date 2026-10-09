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

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Function;

/**
 * jdbc 操作
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-24 17:32
 **/
@Getter
@Setter
public class JdbcTemplate extends DbTemplate {

    private final HikariDataSource dataSource;

    /** 表结构缓存，key 为小写表名 */
    private HashMap<String, TableInfo> tableCache;
    /** 数据库元数据处理器（方言差异在此注入） */
    private DbMetaHandler dbMetaProvider;
    /** 创建表的处理器 */
    private TableCreateHandler tableCreateHandler;
    /** 修改表的处理器 */
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
    /** SELECT 实体处理器（由各数据库方言模块注入） */
    private BeanToSelectHandler beanToSelectHandler;
    /** SQL 异常翻译器（方言注入，未设置时使用 DefaultSqlExceptionHandler 兜底） */
    private SqlExceptionHandler sqlExceptionHandler;

    public JdbcTemplate(HikariConfig config) {
        this(new HikariDataSource(config));
    }

    public JdbcTemplate(HikariDataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 从数据库加载所有表结构到缓存
     * <p>使用 {@link #dbMetaProvider} 提供的 SQL 和 Handler 分三步查询：表列表 → 列信息 → 索引信息</p>
     */
    public void loadAllTables() {
        if (dbMetaProvider == null) return;
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
                String key = tableName.toLowerCase();

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
                tableInfo.setColumns(columnMap);
                tableInfo.setIndexes(indexes);

                cache.put(key, tableInfo);
            }
        }

        this.tableCache = cache;
    }

    public QueryRunner queryRunner() {
        return new QueryRunner(this);
    }

    /**
     * 借出连接的唯一入口：当前线程绑定了本模板的事务时返回事务连接，否则从连接池借出
     */
    public Connection getConnection() throws SQLException {
        ConnectionHolder holder = TxContext.get(this);
        if (holder != null && holder.isTransactional()) {
            return holder.getConnection();
        }
        return dataSource.getConnection();
    }

    /** 当前线程是否处于本模板的事务中 */
    public boolean transactionActive() {
        return TxContext.isActive(this);
    }

    /** 创建绑定本模板的事务模板 */
    public TransactionTemplate transactionTemplate() {
        return new TransactionTemplate(this);
    }

    /** 以 REQUIRED 传播执行一段事务逻辑 */
    public <T> T inTransaction(Function<TransactionStatus, T> work) {
        return transactionTemplate().execute(status -> work.apply(status));
    }

    /** 按指定传播行为执行一段事务逻辑 */
    public <T> T inTransaction(Propagation propagation, Function<TransactionStatus, T> work) {
        return transactionTemplate().execute(propagation, status -> work.apply(status));
    }

    // ===== 查询 DSL 便捷入口 =====

    /** 创建实体查询 DSL 构建器 */
    public <T> SelectQuery<T> select(Class<T> entityClass) {
        return new SelectQuery<>(this, entityClass);
    }

    /** 条件查询列表 */
    public <T> List<T> queryList(Class<T> entityClass, Criteria where) {
        return select(entityClass).where(where).list();
    }

    /** 条件查询唯一记录（多于 1 条抛 TooManyResultsException） */
    public <T> T queryOne(Class<T> entityClass, Criteria where) {
        return select(entityClass).where(where).one();
    }

    /** 条件计数 */
    public <T> long count(Class<T> entityClass, Criteria where) {
        return select(entityClass).where(where).count();
    }

    /** 分页查询 */
    public <T> Page<T> queryPage(Class<T> entityClass, Criteria where, Pageable pageable) {
        return select(entityClass).where(where).pageResult(pageable);
    }

    /**
     * 把 SQLException 翻译成统一的 DataAccessException
     * <p>优先使用方言注册的 {@link SqlExceptionHandler}，未注册时按 SQLState/关键字兜底。</p>
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

    public BeanRunner beanRunner() {
        return new BeanRunner(this);
    }

    /**
     * 创建带缓存的 BeanRunner（默认 Caffeine 本地缓存 + 延迟刷盘模式）
     * <p>单例复用即可，线程安全。</p>
     */
    public CachedBeanRunner cachedBeanRunner() {
        return new CachedBeanRunner(this);
    }

    /**
     * 创建带自定义缓存的 BeanRunner
     */
    public CachedBeanRunner cachedBeanRunner(EntityCache<String, Object> cache) {
        return new CachedBeanRunner(this, cache);
    }

    /**
     * 检查并同步表结构
     * <p>先解析实体类得到 {@link TableInfo}，再委托给 {@link #checkTable(TableInfo)}</p>
     *
     * @param cls 实体类
     */
    public void checkTable(Class<?> cls) {
        TableInfo tableInfo = EntityParser.parse(cls);
        checkTable(tableInfo);
    }

    /**
     * 检查并同步表结构
     * <p>缓存不存在时自动加载；表不存在则调用 {@link TableCreateHandler} 创建；
     * 表存在则调用 {@link TableAlterHandler} 对比更新（变更后刷新缓存）。</p>
     *
     * @param tableInfo 实体解析得到的表元数据
     */
    public void checkTable(TableInfo tableInfo) {
        if (tableCache == null) {
            loadAllTables();
        }
        String tableName = tableInfo.getTableName().toLowerCase();
        TableInfo dbTable = tableCache != null ? tableCache.get(tableName) : null;
        if (dbTable == null) {
            if (tableCreateHandler != null) {
                tableCreateHandler.doAction(this, tableInfo);
                // 创建后刷新缓存
                loadAllTables();
            }
        } else {
            if (tableAlterHandler != null) {
                tableAlterHandler.doAction(this, tableInfo);
                // 变更后刷新缓存
                loadAllTables();
            }
        }
    }

    /** 清空表结构缓存，下次调用 checkTable 时会重新加载 */
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
     * 删除表（基于 TableInfo）
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
     * 截断表（基于 TableInfo）
     */
    public void truncateTable(TableInfo tableInfo) {
        if (tableTruncateHandler != null) {
            tableTruncateHandler.doAction(this, tableInfo);
        }
    }

}
