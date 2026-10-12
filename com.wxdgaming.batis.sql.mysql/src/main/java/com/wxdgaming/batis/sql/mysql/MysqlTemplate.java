package com.wxdgaming.batis.sql.mysql;

import com.wxdgaming.batis.sql.JdbcTemplate;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * mysql JdbcTemplate
 * <p>构造时经 initDefaults 自动装配 MySQL 方言的全部 SPI：元数据回读、建表/改表/删表/截断、
 * 列类型渲染、四类 Bean&lt;-&gt;SQL 处理器与异常翻译器（清单见该方法注释）。</p>
 * <p>构造时自动检查目标数据库是否存在，不存在则创建（通过连接 mysql 系统库执行 CREATE DATABASE IF NOT EXISTS）。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-24 20:11
 **/
public class MysqlTemplate extends JdbcTemplate {

    /** 用于提取 JDBC URL 中数据库名的正则 */
    private static final Pattern DB_NAME_PATTERN = Pattern.compile("jdbc:(?:mysql|mariadb)://[^/]+/([^?&#]+)");
    /** 库名白名单：它会被拼进标识符位置，必须限制字符集 */
    private static final Pattern DB_NAME_SAFE = Pattern.compile("[A-Za-z0-9_$]+");
    private static final Logger log = LoggerFactory.getLogger(MysqlTemplate.class);

    /**
     * 由 HikariCP 配置构造：先走 {@link #ensureDatabaseAndCreateDataSource} 自动建库再建连接池。
     * <p>super(...) 必须先把 DataSource 造出来，因此自动建库这步只能作为 super 的参数表达式执行；
     * initDefaults 在 super 之后调用，保证方言 SPI 注入时 dataSource 已就绪。</p>
     */
    public MysqlTemplate(HikariConfig config) {
        super(ensureDatabaseAndCreateDataSource(config));
        initDefaults();
    }

    /**
     * 由现成连接池构造：跳过自动建库（调用方自行保证目标库存在），只负责注入方言 SPI。
     */
    public MysqlTemplate(HikariDataSource dataSource) {
        super(dataSource);
        initDefaults();
    }

    /**
     * MySQL 方言所有 SPI 的唯一装配点。
     * <p>核心层 JdbcTemplate 只声明了一组可空的方言接口字段（dbMetaProvider、columnTypeDefHandler、
     * beanToInsertHandler 等），自身不认识任何具体数据库；本方法把这些字段一次性填成 MySQL 实现。
     * 之所以用 setter 注入而非在核心里 new 具体类：核心模块不依赖本 mysql 模块（依赖方向是单向的，
     * 核心 &lt;- sql &lt;- sql.mysql），核心根本看不到 Mysql*Handler 这些类，也就无从硬编码；
     * 换成 setter 后，使用方还能在构造完成后单独替换某一项（例如自定义异常翻译器）来覆盖默认装配。</p>
     * <p>valueHandler 是唯一的例外，它不是 SPI 字段而是被下面四个 handler 共享的内部协作者：
     * 值转换（JSON/BLOB/日期/枚举）逻辑各方言只有一份实现，用同一个实例可避免重复构造，
     * 也保证 INSERT 写入端与 SELECT 读取端对同一列使用完全对称的转换规则。</p>
     */
    private void initDefaults() {
        MysqlBeanValueHandler valueHandler = new MysqlBeanValueHandler();
        setDbMetaProvider(new MysqlDbMetaHandler());
        // 无状态、纯函数的类型渲染/规格化器，用单例常量即可，无需每次 new
        setColumnTypeDefHandler(MysqlColumnTypeDefHandler.INSTANCE);
        setTableCreateHandler(new MysqlTableCreateHandler());
        setTableAlterHandler(new MysqlTableAlterHandler());
        setTableDropHandler(new MysqlTableDropHandler());
        setTableTruncateHandler(new MysqlTableTruncateHandler());
        setSqlDbToBeanHandler(new MysqlDbToBeanHandler(valueHandler));
        setBeanToInsertHandler(new MysqlBeanToInsertHandler(valueHandler));
        setBeanToUpdateHandler(new MysqlBeanToUpdateHandler(valueHandler));
        setBeanToDeleteHandler(new MysqlBeanToDeleteHandler(valueHandler));
        setBeanToSelectHandler(new MysqlBeanToSelectHandler());
        setSqlExceptionHandler(new MysqlSqlExceptionHandler());
    }

    /**
     * 确保目标数据库存在，再创建 HikariDataSource
     * <p>HikariCP 在 new HikariDataSource(config) 时会立即连接，如果数据库不存在则抛出异常。
     * 此方法先连接 MySQL 的 mysql 系统库，执行 {@code CREATE DATABASE IF NOT EXISTS}，
     * 之后再创建正常的 HikariDataSource 连接池。</p>
     *
     * @param config HikariCP 配置（必须包含 jdbcUrl, username, password）
     * @return 可用的 HikariDataSource
     */
    private static HikariDataSource ensureDatabaseAndCreateDataSource(HikariConfig config) {
        String jdbcUrl = config.getJdbcUrl();
        String dbName = extractDbName(jdbcUrl);
        if (dbName != null) {
            // 将 URL 中的目标库替换为 mysql 系统库，用于执行 CREATE DATABASE
            String systemJdbcUrl = jdbcUrl.replaceFirst(
                    "(jdbc:(?:mysql|mariadb)://[^/]+/)[^?&#]+", "$1mysql"
            );
            try (Connection conn = DriverManager.getConnection(
                    systemJdbcUrl, config.getUsername(), config.getPassword()
            )) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(
                            "CREATE DATABASE IF NOT EXISTS `" + dbName
                                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
                    );
                }
            } catch (SQLException e) {
                log.warn(
                        "无法连接 mysql 系统库检查数据库 '{}' 是否存在，跳过自动建库。" +
                                "若目标数据库不存在，HikariCP 连接时会抛出原始异常。jdbcUrl: {}",
                        dbName, jdbcUrl, e
                );
            }
        }
        return new HikariDataSource(config);
    }

    /**
     * 从 JDBC URL 中提取数据库名（同时兼容 {@code jdbc:mysql://} 与 {@code jdbc:mariadb://}）。
     * <p>例如 {@code jdbc:mysql://10.28.0.26:13306/test?serverTimezone=Asia/Shanghai} → {@code test}</p>
     * <p>只接受字母数字与 {@code _ $}：库名会被拼进 {@code CREATE DATABASE} 的标识符位置，
     * 含反引号之类的字符可以闭合标识符并注入后续 SQL。非法名字不抛异常而是返回 null
     * （跳过自动建库）——数据库可能本来就已存在，配置不合法不该演变成启动失败，
     * 但必须留一条 warn 让人看见。</p>
     *
     * @param jdbcUrl MySQL JDBC 连接串
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
