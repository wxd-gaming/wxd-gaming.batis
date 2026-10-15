package com.wxdgaming.batis.sql.pgsql;

import com.wxdgaming.batis.sql.JdbcTemplate;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PostgreSQL JdbcTemplate
 * <p>构造时经 {@link #initDefaults()} 自动装配 PG 方言的全部 SPI：元数据回读、建表/改表/删表/截断、
 * 列类型渲染、四类 Bean&lt;-&gt;SQL 处理器与异常翻译器（清单见该方法注释）。</p>
 * <p>构造时自动检查目标数据库是否存在，不存在则创建：先连 maintenance 库 {@code postgres}，
 * 查 {@code pg_database}，缺库才发 {@code CREATE DATABASE}。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-14
 **/
public class PgsqlTemplate extends JdbcTemplate {

    /** 用于提取 JDBC URL 中数据库名的正则 */
    private static final Pattern DB_NAME_PATTERN = Pattern.compile("jdbc:postgresql://[^/]+/([^?&#]+)");
    /** 库名白名单：它会被拼进标识符位置，必须限制字符集 */
    private static final Pattern DB_NAME_SAFE = Pattern.compile("[A-Za-z0-9_$]+");
    /** maintenance 库：PG 必须连到某个已存在的库才能发 CREATE DATABASE，约定用 postgres */
    private static final String MAINTENANCE_DB = "postgres";
    private static final Logger log = LoggerFactory.getLogger(PgsqlTemplate.class);

    /**
     * 由 HikariCP 配置构造：先走 {@link #ensureDatabaseAndCreateDataSource} 自动建库再建连接池。
     * <p>super(...) 必须先把 DataSource 造出来，因此自动建库这步只能作为 super 的参数表达式执行；
     * initDefaults 在 super 之后调用，保证方言 SPI 注入时 dataSource 已就绪。</p>
     */
    public PgsqlTemplate(HikariConfig config) {
        super(ensureDatabaseAndCreateDataSource(config));
        initDefaults();
    }

    /**
     * 由现成连接池构造：跳过自动建库（调用方自行保证目标库存在），只负责注入方言 SPI。
     */
    public PgsqlTemplate(HikariDataSource dataSource) {
        super(dataSource);
        initDefaults();
    }

    /**
     * PostgreSQL 方言所有 SPI 的唯一装配点。
     * <p>核心层 JdbcTemplate 只声明了一组可空的方言接口字段，自身不认识任何具体数据库；
     * 本方法把这些字段一次性填成 PG 实现。用 setter 注入而非在核心里 new 具体类的原因与 MySQL 侧相同
     * （依赖方向单向：核心 &lt;- sql &lt;- sql.pgsql），使用方因此还能在构造完成后单独替换某一项。</p>
     * <p>valueHandler 不是 SPI 字段而是被写读四类 handler 共享的内部协作者：同一个实例保证
     * INSERT 写入端与 SELECT 读取端对同一列使用完全对称的转换规则（尤其时间族的 JDBC 形态，
     * 一侧转 {@code java.sql.Date}、另一侧转 {@code Timestamp} 就会读出 42804 或类型错配）。</p>
     */
    private void initDefaults() {
        PgsqlBeanValueHandler valueHandler = new PgsqlBeanValueHandler();
        setDbMetaProvider(new PgsqlDbMetaHandler());
        // 无状态、纯函数的类型渲染/规格化器，用单例常量即可，无需每次 new
        setColumnTypeDefHandler(PgsqlColumnTypeDefHandler.INSTANCE);
        setTableCreateHandler(PgsqlTableCreateHandler.INSTANCE);
        setTableAlterHandler(PgsqlTableAlterHandler.INSTANCE);
        setTableDropHandler(PgsqlTableDropHandler.INSTANCE);
        setTableTruncateHandler(PgsqlTableTruncateHandler.INSTANCE);
        setSqlDbToBeanHandler(new PgsqlDbToBeanHandler(valueHandler));
        setBeanToInsertHandler(new PgsqlBeanToInsertHandler(valueHandler));
        // UPDATE/DELETE/SELECT 三方言差异只在标识符与分页写法上，合并为一个实现（见该类注释）
        PgsqlBeanToStatementHandler statementHandler = new PgsqlBeanToStatementHandler(valueHandler);
        setBeanToUpdateHandler(statementHandler);
        setBeanToDeleteHandler(statementHandler);
        setBeanToSelectHandler(statementHandler);
        setSqlExceptionHandler(new PgsqlSqlExceptionHandler());
    }

    /**
     * 确保目标数据库存在，再创建 HikariDataSource。
     * <p>HikariCP 在 {@code new HikariDataSource(config)} 时会立即连接，库不存在直接抛异常。
     * 本方法先连 maintenance 库确认/创建目标库，之后再创建正常连接池。</p>
     * <p>与 MySQL 版的两处必要差别：PG <b>没有</b> {@code CREATE DATABASE IF NOT EXISTS}，
     * 所以必须先查 {@code pg_database}；而 {@code CREATE DATABASE} 又不能在事务块里执行，
     * 因此走 DriverManager 的默认 autoCommit 连接，不借 Hikari 的连接（池里借出的连接可能被上层设成非自动提交）。</p>
     *
     * @param config HikariCP 配置（必须包含 jdbcUrl, username, password）
     * @return 可用的 HikariDataSource
     */
    private static HikariDataSource ensureDatabaseAndCreateDataSource(HikariConfig config) {
        String jdbcUrl = config.getJdbcUrl();
        String dbName = extractDbName(jdbcUrl);
        if (dbName != null) {
            String systemJdbcUrl = jdbcUrl.replaceFirst(
                    "(jdbc:postgresql://[^/]+/)[^?&#]+", "$1" + MAINTENANCE_DB
            );
            try (Connection conn = DriverManager.getConnection(
                    systemJdbcUrl, config.getUsername(), config.getPassword()
            )) {
                createDatabaseIfAbsent(conn, dbName);
            } catch (SQLException e) {
                log.warn(
                        "无法连接 {} 库检查数据库 '{}' 是否存在，跳过自动建库。" +
                                "若目标数据库不存在，HikariCP 连接时会抛出原始异常。jdbcUrl: {}",
                        MAINTENANCE_DB, dbName, jdbcUrl, e
                );
            }
        }
        return new HikariDataSource(config);
    }

    /**
     * 查 {@code pg_database}，缺库才建。
     * <p>存在性检查用<b>参数化</b>查询（{@code datname = ?}）：库名在这里只是普通字符串，没有拼接面。</p>
     * <p>建表语句里的标识符则只能拼接：PG 的 {@code CREATE DATABASE} 不接受参数占位符，
     * 所以库名先过 {@link #DB_NAME_SAFE} 白名单，再用双引号引用（PG 会把未引用的标识符折成小写，
     * 引用后保持原样，而 {@code pg_database} 查到的正是原样名字，两侧口径必须一致）。
     * {@code TEMPLATE template0} 是必须的：默认模板 {@code template1} 可能带非 UTF8 编码或用户对象，
     * 那时指定 {@code ENCODING 'UTF8'} 会被 PG 直接拒绝。</p>
     */
    private static void createDatabaseIfAbsent(Connection conn, String dbName) throws SQLException {
        boolean exists;
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?")) {
            ps.setString(1, dbName);
            try (ResultSet rs = ps.executeQuery()) {
                exists = rs.next();
            }
        }
        if (exists) return;
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE DATABASE \"" + dbName + "\" WITH ENCODING 'UTF8' TEMPLATE template0");
        } catch (SQLException e) {
            // 42P04 = duplicate_database：两个实例并发启动时都会走到这里，后到的那个撞上的
            // 不是错误而是"别人已经建好了"。不拦这条，外层 catch 会把它记成
            // "无法连接 postgres 库"这种与真实原因完全不符的日志
            if ("42P04".equals(e.getSQLState())) {
                log.info("数据库 '{}' 已被并发实例创建（42P04），无需重复建库", dbName);
                return;
            }
            throw e;
        }
        log.info("自动创建数据库 '{}'（ENCODING UTF8, TEMPLATE template0）", dbName);
    }

    /**
     * 从 JDBC URL 中提取数据库名。
     * <p>例如 {@code jdbc:postgresql://127.0.0.1:5433/test?sslmode=disable} → {@code test}。</p>
     * <p>只接受字母数字与 {@code _ $}：库名会被拼进 {@code CREATE DATABASE} 的标识符位置，
     * 含双引号之类的字符可以闭合标识符并注入后续 SQL。非法名字不抛异常而是返回 null
     * （跳过自动建库）——数据库可能本来就已存在，配置不合法不该演变成启动失败，
     * 但必须留一条 warn 让人看见。</p>
     *
     * @param jdbcUrl PostgreSQL JDBC 连接串
     * @return 数据库名；URL 中不包含或名字不合法时返回 null
     */
    private static String extractDbName(String jdbcUrl) {
        if (jdbcUrl == null) return null;
        Matcher m = DB_NAME_PATTERN.matcher(jdbcUrl);
        if (!m.find()) return null;
        String dbName = m.group(1);
        if (dbName.isEmpty()) return null;
        if (!DB_NAME_SAFE.matcher(dbName).matches()) {
            log.warn("JDBC URL 中的数据库名 '{}' 只允许字母数字与 _ $，已跳过自动建库（避免拼接出非法/可注入的 DDL）", dbName);
            return null;
        }
        return dbName;
    }

}
