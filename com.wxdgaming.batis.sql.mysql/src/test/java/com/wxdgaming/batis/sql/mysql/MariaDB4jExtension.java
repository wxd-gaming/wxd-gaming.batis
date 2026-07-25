package com.wxdgaming.batis.sql.mysql;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * JUnit 5 扩展：在测试类运行前拉起嵌入式 MariaDB4j，测试结束后停止。
 * <p>默认行为：使用固定端口、数据目录位于用户目录下（持久化，重启不丢）、建 {@code test} 库、
 * root 空密码，对外暴露 {@code jdbc:mysql://localhost:<port>/test} 连接串
 * （供 MySQL Connector/J 使用）。</p>
 * <p><b>持久化说明</b>：数据目录固定在 {@code ${user.home}/.wxd-batis/maria4j-data}，
 * 多次运行复用同一数据目录，因此创建的表在测试进程重启后依然存在。
 * 进程退出（{@code afterAll} 的 {@code db.stop()}）不会删除该目录。</p>
 * <p>可配置系统属性：</p>
 * <ul>
 *   <li>{@code -Dwxd.batis.mariadb4j.dataDir=<path>} 数据目录（默认 {@code ~/.wxd-batis/maria4j-data}）</li>
 *   <li>{@code -Dwxd.batis.mariadb4j.baseDir=<path>} 二进制/解压目录（默认 {@code ~/.wxd-batis/maria4j-base}）</li>
 *   <li>{@code -Dwxd.batis.mariadb4j.port=<port>} 端口（默认 {@code 3307}）</li>
 *   <li>{@code -Dwxd.batis.mariadb4j.clean=true} 启动时清空 dataDir 重新初始化</li>
 *   <li>{@code -Dwxd.batis.mysql.url=jdbc:mysql://host:3306/test} 使用外部 MySQL，跳过嵌入式实例</li>
 * </ul>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-25 11:00
 */
public class MariaDB4jExtension implements BeforeAllCallback, AfterAllCallback {

    /** 外部 MySQL 回退连接串属性名 */
    public static final String EXTERNAL_URL_PROPERTY = "wxd.batis.mysql.url";

    /** 数据目录属性名（持久化，重启不丢） */
    public static final String DATA_DIR_PROPERTY = "wxd.batis.mariadb4j.dataDir";
    /** 二进制/解压目录属性名 */
    public static final String BASE_DIR_PROPERTY = "wxd.batis.mariadb4j.baseDir";
    /** 端口属性名 */
    public static final String PORT_PROPERTY = "wxd.batis.mariadb4j.port";
    /** 清空数据目录开关属性名 */
    public static final String CLEAN_PROPERTY = "wxd.batis.mariadb4j.clean";

    private DB db;
    private String jdbcUrl;
    private boolean embedded = true;

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        String external = System.getProperty(EXTERNAL_URL_PROPERTY);
        if (external != null && !external.isBlank()) {
            // 使用外部 MySQL，不启动嵌入式实例
            this.embedded = false;
            this.jdbcUrl = external.trim();
            return;
        }

        Path home = Path.of(System.getProperty("user.home"), ".wxd-batis");
        Path baseDir = resolveDir(BASE_DIR_PROPERTY, home.resolve("maria4j-base"));
        Path dataDir = resolveDir(DATA_DIR_PROPERTY, home.resolve("maria4j-data"));
        int port = resolvePort();

        if (Boolean.parseBoolean(System.getProperty(CLEAN_PROPERTY, "false"))) {
            cleanupDirectory(dataDir);
        }

        cleanupStaleLockFiles(dataDir);

        DBConfigurationBuilder builder = DBConfigurationBuilder.newBuilder();
        builder.setPort(port);
        builder.setBaseDir(baseDir.toFile().getAbsolutePath());
        builder.setDataDir(dataDir.toFile().getAbsolutePath());

        // 反射构造 DB 并复用已初始化的数据目录（跳过 install 以避免 "目录非空" 失败）
        db = startEmbedded(builder.build(), dataDir);

        // 幂等建库：db.createDB("test") 执行的是不带 IF NOT EXISTS 的 CREATE DATABASE，
        // 在 dataDir 复用时库已存在会抛 "database exists"，故改为幂等方式。
        // 注意使用 jdbc:mysql:// 前缀以匹配 MySQL Connector/J（与 MysqlTemplate 一致）。
        String createUrl = "jdbc:mysql://localhost:" + port
                + "/?user=root&useSSL=false&serverTimezone=Asia/Shanghai";
        try (Connection conn = DriverManager.getConnection(createUrl);
             Statement st = conn.createStatement()) {
            st.execute("CREATE DATABASE IF NOT EXISTS `test`");
        }

        // 使用 jdbc:mysql:// 前缀以匹配 MysqlTemplate 固定的 com.mysql.cj.jdbc.Driver
        this.jdbcUrl = "jdbc:mysql://localhost:" + port
                + "/test?serverTimezone=Asia/Shanghai&useSSL=false";
    }

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        if (embedded && db != null) {
            db.stop();
        }
    }

    /** 解析目录：优先取系统属性，否则使用默认值（自动创建父目录） */
    private static Path resolveDir(String property, Path def) throws IOException {
        String value = System.getProperty(property);
        Path dir = (value != null && !value.isBlank()) ? Path.of(value) : def;
        Files.createDirectories(dir);
        return dir;
    }

    /** 解析端口：默认 3307，可被系统属性覆盖（避免与本地 3306 冲突） */
    private static int resolvePort() {
        String value = System.getProperty(PORT_PROPERTY);
        if (value != null && !value.isBlank()) {
            return Integer.parseInt(value.trim());
        }
        return 3307;
    }

    /**
     * 构造并启动嵌入式数据库，支持复用已初始化的持久化数据目录。
     * <p>MariaDB4j 的 {@code DB.newEmbeddedDB()} 会无条件执行 {@code mariadb-install-db}，
     * 而该命令拒绝非空数据目录，导致无法在多次运行间复用同一数据目录。
     * 因此这里通过反射构造 {@code DB} 实例，仅当数据目录尚未初始化
     * （不存在 {@code mysql} 系统库）时才执行 {@code install()}，否则直接 {@code start()} 复用。</p>
     *
     * @param config  DB 配置（已设置 port / baseDir / dataDir）
     * @param dataDir 数据目录路径（用于判断是否已初始化）
     * @return 已启动的 DB 实例
     */
    private static DB startEmbedded(ch.vorburger.mariadb4j.DBConfiguration config, Path dataDir)
            throws Exception {
        Constructor<DB> ctor = DB.class.getDeclaredConstructor(ch.vorburger.mariadb4j.DBConfiguration.class);
        ctor.setAccessible(true);
        DB database = ctor.newInstance(config);

        // prepareDirectories()：设置 baseDir/libDir/tmpDir/dataDir 字段并确保目录存在
        Method prepareDirectories = DB.class.getDeclaredMethod("prepareDirectories");
        prepareDirectories.setAccessible(true);
        prepareDirectories.invoke(database);

        // unpackEmbeddedDb()：解压 MariaDB 二进制到 baseDir（幂等覆盖）
        Method unpack = DB.class.getDeclaredMethod("unpackEmbeddedDb");
        unpack.setAccessible(true);
        unpack.invoke(database);

        // 仅当 mysql 系统库不存在时才初始化数据目录
        boolean initialized = Files.exists(dataDir.resolve("mysql"));
        if (!initialized) {
            Method install = DB.class.getDeclaredMethod("install");
            install.setAccessible(true);
            install.invoke(database);
        }

        database.start();
        return database;
    }

    /**
     * 删除上次异常退出遗留的 pid / socket 文件，避免复用 dataDir 时启动报错。
     * <p>注意：<b>绝不能删除 {@code aria_log_control}</b>，该文件是 Aria 引擎的真实控制文件，
     * 同时被 MariaDB4j 用作"数据库已安装"的标记；误删会导致二次启动误判为未安装而重新
     * 执行 install，进而因"目录非空"失败。</p>
     */
    private static void cleanupStaleLockFiles(Path dataDir) throws IOException {
        if (!Files.isDirectory(dataDir)) {
            return;
        }
        try (var stream = Files.newDirectoryStream(dataDir)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (name.endsWith(".pid") || name.endsWith(".sock")) {
                    try {
                        Files.deleteIfExists(entry);
                    } catch (IOException ignored) {
                        // 删除失败不影响启动（多半是被占用），交由 MariaDB 自行处理
                    }
                }
            }
        }
    }

    /** 递归清空目录内容（仅在显式开启 clean 开关时调用） */
    private static void cleanupDirectory(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // 文件被占用时忽略，下次仍可重建
                        }
                    });
        }
    }
}
