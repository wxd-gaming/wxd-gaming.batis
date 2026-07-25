package com.wxdgaming.batis.sql.mysql;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;

/**
 * JUnit 5 扩展：在测试类运行前拉起嵌入式 MariaDB4j，测试结束后停止。
 * <p>默认行为：使用随机空闲端口、数据目录位于临时目录、建 {@code test} 库、root 空密码，
 * 对外暴露 {@code jdbc:mysql://localhost:<port>/test} 连接串（供 MySQL Connector/J 使用）。</p>
 * <p>若设置系统属性 {@code -Dwxd.batis.mysql.url=jdbc:mysql://host:3306/test}，
 * 则跳过嵌入式数据库，直接使用该外部 MySQL（便于在已有 MySQL 环境对照测试）。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-25 10:00
 */
public class MariaDB4jExtension implements BeforeAllCallback, AfterAllCallback {

    /** 外部 MySQL 回退连接串属性名 */
    public static final String EXTERNAL_URL_PROPERTY = "wxd.batis.mysql.url";

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

        int port = findFreePort();
        DBConfigurationBuilder builder = DBConfigurationBuilder.newBuilder();
        builder.setPort(port);
        builder.setBaseDir(Files.createTempDirectory("maria4j-base").toFile().getAbsolutePath());
        builder.setDataDir(Files.createTempDirectory("maria4j-data").toFile().getAbsolutePath());

        db = DB.newEmbeddedDB(builder.build());
        db.start();
        db.createDB("test");

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

    /** 申请一个当前未占用的 TCP 端口 */
    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }
}
