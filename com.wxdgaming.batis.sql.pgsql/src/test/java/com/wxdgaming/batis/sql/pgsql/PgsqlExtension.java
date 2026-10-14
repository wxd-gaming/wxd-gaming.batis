package com.wxdgaming.batis.sql.pgsql;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * JUnit 5 扩展：在测试类运行前拉起嵌入式 PostgreSQL（zonky embedded-postgres），测试结束后停止。
 * <p>默认行为：固定端口 {@code 5433}、数据目录 {@code ${user.home}/.wxd-batis/pgsql-data}（持久化，
 * 重启不丢，与 {@code MariaDB4jExtension} 同一取向——DDL 漂移类用例必须能看到上一次留下的表），
 * 建 {@code test} 库、超级用户 {@code postgres} 空密码（initdb 以 {@code -A trust} 初始化），
 * 对外暴露 {@code jdbc:postgresql://localhost:<port>/test?user=postgres}。</p>
 * <p>可配置系统属性：</p>
 * <ul>
 *   <li>{@code -Dwxd.batis.pgsql.dataDir=<path>} 数据目录</li>
 *   <li>{@code -Dwxd.batis.pgsql.port=<port>} 端口（默认 5433，避开本机 5432 上的正式 PG）</li>
 *   <li>{@code -Dwxd.batis.pgsql.clean=true} 启动前清空数据目录重新 initdb</li>
 *   <li>{@code -Dwxd.batis.pgsql.url=jdbc:postgresql://host:5432/db} 使用外部 PG，跳过嵌入式实例
 *       （配套 {@code wxd.batis.pgsql.user} / {@code wxd.batis.pgsql.password}）</li>
 * </ul>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-14
 */
public class PgsqlExtension implements BeforeAllCallback, AfterAllCallback {

    /** 外部 PG 连接串属性名 */
    public static final String EXTERNAL_URL_PROPERTY = "wxd.batis.pgsql.url";
    /** 外部 PG 用户名属性名 */
    public static final String EXTERNAL_USER_PROPERTY = "wxd.batis.pgsql.user";
    /** 外部 PG 密码属性名 */
    public static final String EXTERNAL_PASSWORD_PROPERTY = "wxd.batis.pgsql.password";
    /** 数据目录属性名（持久化，重启不丢） */
    public static final String DATA_DIR_PROPERTY = "wxd.batis.pgsql.dataDir";
    /** 端口属性名 */
    public static final String PORT_PROPERTY = "wxd.batis.pgsql.port";
    /** 清空数据目录开关属性名 */
    public static final String CLEAN_PROPERTY = "wxd.batis.pgsql.clean";

    /** 测试库名 */
    public static final String DB_NAME = "test";
    /** 嵌入式实例的超级用户（initdb -U postgres，trust 认证无需密码） */
    public static final String DEFAULT_USER = "postgres";
    /** 默认端口：与本机常见的 5432 错开，避免打断开发机上正在跑的 PG */
    public static final int DEFAULT_PORT = 5433;

    private EmbeddedPostgres postgres;
    private String jdbcUrl;
    private String username = DEFAULT_USER;
    private String password = "";
    private boolean embedded = true;

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        String external = System.getProperty(EXTERNAL_URL_PROPERTY);
        if (external != null && !external.isBlank()) {
            // 外部 PG：连接串由使用方给出，用户名/密码可选（PG 驱动也接受 URL 内 user= 参数）
            this.embedded = false;
            this.jdbcUrl = external.trim();
            this.username = System.getProperty(EXTERNAL_USER_PROPERTY, DEFAULT_USER);
            this.password = System.getProperty(EXTERNAL_PASSWORD_PROPERTY, "");
            return;
        }

        Path home = Path.of(System.getProperty("user.home"), ".wxd-batis");
        Path dataDir = resolveDir(DATA_DIR_PROPERTY, home.resolve("pgsql-data"));
        int port = resolvePort();

        if (Boolean.parseBoolean(System.getProperty(CLEAN_PROPERTY, "false"))) {
            deleteRecursively(dataDir);
            Files.createDirectories(dataDir);
        }

        // 上次进程被强杀时 PG 来不及清理 postmaster.pid，本次启动会判定"已有实例在运行"而拒绝启动；
        // 这里只删 pid 文件，绝不碰 global/pg_internal 之类的真实数据文件（碰到就得重新 initdb，表全丢）
        deleteIfExists(dataDir.resolve("postmaster.pid"));

        try {
            postgres = EmbeddedPostgres.builder()
                    .setPort(port)
                    .setDataDirectory(dataDir)
                    // 持久化数据目录：close() 不删目录，否则每次启动都要重新 initdb（约十秒）且表全丢
                    .setCleanDataDirectory(false)
                    // 首次 initdb + 共享内存在 Windows 上可能超过默认的 10 秒等待
                    .setPGStartupWait(Duration.ofSeconds(60))
                    .start();
        } catch (IOException | RuntimeException e) {
            // 故意不做"启动失败就跳过用例"：静默跳过会让"pgsql 方言从未被真库验证过"这件事看不出来，
            // 而集成测试正是这个模块唯一的验收手段
            throw new IllegalStateException("嵌入式 PostgreSQL 启动失败（数据目录: " + dataDir
                    + "，可用 -D" + CLEAN_PROPERTY + "=true 重新初始化，或用 -D" + EXTERNAL_URL_PROPERTY
                    + "=jdbc:postgresql://host:5432/db 指向外部 PG）", e);
        }

        this.username = DEFAULT_USER;
        this.password = "";
        // 管理连接连的是 postgres 库：CREATE DATABASE 不能在事务里执行，用 autocommit 的裸连接做
        createDatabaseIfAbsent(port);
        this.jdbcUrl = "jdbc:postgresql://localhost:" + port + "/" + DB_NAME
                + "?user=" + DEFAULT_USER + "&sslmode=disable";
    }

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        if (embedded && postgres != null) {
            postgres.close();
        }
    }

    /** 幂等建库：{@code CREATE DATABASE} 没有 IF NOT EXISTS 形式，先查 pg_database 再建 */
    private static void createDatabaseIfAbsent(int port) throws IOException {
        String adminUrl = "jdbc:postgresql://localhost:" + port + "/postgres?user="
                + DEFAULT_USER + "&sslmode=disable";
        try (Connection conn = DriverManager.getConnection(adminUrl, DEFAULT_USER, "")) {
            boolean exists;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + DB_NAME + "'")) {
                exists = rs.next();
            }
            if (!exists) {
                try (Statement st = conn.createStatement()) {
                    st.execute("CREATE DATABASE \"" + DB_NAME + "\" ENCODING 'UTF8'");
                }
            }
        } catch (java.sql.SQLException e) {
            throw new IOException("创建测试库 " + DB_NAME + " 失败", e);
        }
    }

    /** 解析目录：优先取系统属性，否则使用默认值（自动创建） */
    private static Path resolveDir(String property, Path def) throws IOException {
        String value = System.getProperty(property);
        Path dir = (value != null && !value.isBlank()) ? Path.of(value) : def;
        Files.createDirectories(dir);
        return dir;
    }

    /** 解析端口：默认 5433，可被系统属性覆盖 */
    private static int resolvePort() {
        String value = System.getProperty(PORT_PROPERTY);
        if (value != null && !value.isBlank()) {
            return Integer.parseInt(value.trim());
        }
        return DEFAULT_PORT;
    }

    private static void deleteIfExists(Path path) throws IOException {
        Files.deleteIfExists(path);
    }

    /** 递归清空目录（仅在显式开启 clean 开关时调用） */
    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.sorted(Comparator.comparingInt(Path::getNameCount).reversed())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // 被占用时忽略：随后 createDirectories 会复用现有内容，必要时由 initdb 报错暴露
                        }
                    });
        }
    }
}
