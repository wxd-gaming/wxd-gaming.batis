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
 * <p>自动装配 MysqlDbMetaProvider、MysqlTableCreateHandler、MysqlTableAlterHandler</p>
 * <p>构造时自动检查目标数据库是否存在，不存在则创建（通过连接 mysql 系统库执行 CREATE DATABASE IF NOT EXISTS）。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-24 20:11
 **/
public class MysqlTemplate extends JdbcTemplate {

    /** 用于提取 JDBC URL 中数据库名的正则 */
    private static final Pattern DB_NAME_PATTERN = Pattern.compile("jdbc:mysql://[^/]+/([^?&#]+)");
    private static final Logger log = LoggerFactory.getLogger(MysqlTemplate.class);

    public MysqlTemplate(HikariConfig config) {
        super(ensureDatabaseAndCreateDataSource(config));
        initDefaults();
    }

    public MysqlTemplate(HikariDataSource dataSource) {
        super(dataSource);
        initDefaults();
    }

    private void initDefaults() {
        setDbMetaProvider(new MysqlDbMetaProvider());
        setTableCreateHandler(new MysqlTableCreateHandler());
        setTableAlterHandler(new MysqlTableAlterHandler());
        setSqlDbToBeanHandlerSupplier(MysqlToBeanHandler::new);
        setBeanToInsertHandlerSupplier(MysqlBeanToInsertHandler::new);
        setBeanToUpdateHandlerSupplier(MysqlBeanToUpdateHandler::new);
        setTableDropHandler(new MysqlTableDropHandler());
        setTableTruncateHandler(new MysqlTableTruncateHandler());
        setDeleteHandler(new MysqlDeleteHandler());
        setBeanToSelectHandlerSupplier(MysqlBeanToSelectHandler::new);
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
                    "(jdbc:mysql://[^/]+/)[^?&#]+", "$1mysql"
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
     * 从 JDBC URL 中提取数据库名
     * <p>例如 {@code jdbc:mysql://10.28.0.26:13306/test?serverTimezone=Asia/Shanghai} → {@code test}</p>
     *
     * @param jdbcUrl MySQL JDBC 连接串
     * @return 数据库名，若 URL 中不包含则返回 null
     */
    private static String extractDbName(String jdbcUrl) {
        if (jdbcUrl == null) return null;
        Matcher m = DB_NAME_PATTERN.matcher(jdbcUrl);
        if (!m.find()) return null;
        String dbName = m.group(1);
        return dbName.isEmpty() ? null : dbName;
    }

}
