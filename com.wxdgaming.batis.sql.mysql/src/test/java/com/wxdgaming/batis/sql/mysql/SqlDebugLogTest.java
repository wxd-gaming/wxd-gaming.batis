package com.wxdgaming.batis.sql.mysql;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.wxdgaming.batis.sql.BeanRunner;
import com.wxdgaming.batis.sql.QueryRunner;
import com.wxdgaming.batis.sql.ResultSetHandlers;
import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 执行过程 debug 日志测试：QueryRunner 五个执行入口 + BeanRunner 批量插入旁路
 * <p>日志开关 = logger 级别（不引入第二套框架开关）。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
class SqlDebugLogTest {

    @RegisterExtension
    static MariaDB4jExtension maria = new MariaDB4jExtension();

    static MysqlTemplate template;

    private ListAppender<ILoggingEvent> appender;
    private Logger runnerLogger;
    private Logger beanLogger;
    private Level prevRunner;
    private Level prevBean;

    @BeforeAll
    static void setUp() {
        if (template != null) return;
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(maria.getJdbcUrl());
        config.setUsername("root");
        config.setPassword("");
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        template = new MysqlTemplate(config);
        template.checkTable(AccountEntity.class);
    }

    @BeforeEach
    void attachAppender() {
        runnerLogger = (Logger) LoggerFactory.getLogger(QueryRunner.class);
        beanLogger = (Logger) LoggerFactory.getLogger(BeanRunner.class);
        prevRunner = runnerLogger.getLevel();
        prevBean = beanLogger.getLevel();
        runnerLogger.setLevel(Level.DEBUG);
        beanLogger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        runnerLogger.addAppender(appender);
        beanLogger.addAppender(appender);
        try (QueryRunner qr = new QueryRunner(template)) {
            qr.update("DELETE FROM `t_account`");
        }
        appender.list.clear();
    }

    @AfterEach
    void detachAppender() {
        runnerLogger.detachAppender(appender);
        beanLogger.detachAppender(appender);
        runnerLogger.setLevel(prevRunner);
        beanLogger.setLevel(prevBean);
        appender.stop();
    }

    private List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private String firstMatching(String needle) {
        return messages().stream().filter(m -> m.contains(needle)).findFirst().orElse(null);
    }

    @Test
    void queryExecuteUpdateInsertLogsContainSqlParamsAndResult() {
        try (QueryRunner qr = new QueryRunner(template)) {
            qr.query("SELECT COUNT(*) FROM `t_account`", ResultSetHandlers.firstCell());
            String q = firstMatching("SELECT COUNT(*)");
            assertNotNull(q, "query 应输出 debug 日志");
            assertTrue(q.contains("[查询]") && q.contains("参数") && q.contains("耗时"), q);

            qr.execute("CREATE TABLE IF NOT EXISTS `t_debug_log_probe` (`v` INT)");
            assertNotNull(firstMatching("[执行]"), "execute 应输出 debug 日志");

            try {
                qr.update("INSERT INTO `t_debug_log_probe` (`v`) VALUES (?)", new Object[]{42});
                String u = firstMatching("INSERT INTO `t_debug_log_probe`");
                assertNotNull(u, "update 应输出 debug 日志");
                assertTrue(u.contains("参数: [42]") && u.contains("结果: 1"), u);

                Number key = qr.executeInsert(
                        "INSERT INTO `t_account` (`account_name`, `balance`) VALUES (?, ?)",
                        new Object[]{"logger-probe", BigDecimal.ONE});
                assertNotNull(key, "自增键应回传");
                String i = firstMatching("[插入]");
                assertNotNull(i, "executeInsert 应输出 debug 日志");
                assertTrue(i.contains("结果: " + key), i);
            } finally {
                qr.update("DROP TABLE IF EXISTS `t_debug_log_probe`");
            }
        }
    }

    @Test
    void failureIsLoggedWithSqlContext() {
        try (QueryRunner qr = new QueryRunner(template)) {
            try {
                qr.query("SELECT * FROM `no_such_table_xyz`", ResultSetHandlers.firstCell());
            } catch (RuntimeException expected) {
                // 异常携带 sql，debug 日志应记录失败行
            }
            String fail = firstMatching("失败");
            assertNotNull(fail, "执行失败应输出 [x] 失败 日志");
            assertTrue(fail.contains("no_such_table_xyz"), fail);
        }
    }

    @Test
    void batchLogsShowSizeAndElapsedNotEveryRow() {
        try (QueryRunner qr = new QueryRunner(template)) {
            List<Object[]> args = List.of(
                    new Object[]{"b1", BigDecimal.ONE},
                    new Object[]{"b2", BigDecimal.TWO},
                    new Object[]{"b3", new BigDecimal("3")});
            qr.executeBatch("INSERT INTO `t_account` (`account_name`, `balance`) VALUES (?, ?)", args);
            String start = firstMatching("[批量]");
            assertNotNull(start, "executeBatch 应输出 debug 日志");
            assertTrue(start.contains("参数组数: 3"), start);
            assertTrue(messages().stream().anyMatch(m -> m.startsWith("[批量] 完成") && m.contains("耗时")),
                    "应有批量完成日志（含耗时）");
            assertEquals(0, messages().stream().filter(m -> m.contains("b1")).count(),
                    "不应逐条打印每组参数");
        }
    }

    @Test
    void beanRunnerBatchInsertBypassIsLogged() {
        try (BeanRunner br = new BeanRunner(template)) {
            AccountEntity a1 = new AccountEntity();
            a1.setAccountName("wb-1");
            a1.setBalance(BigDecimal.ONE);
            AccountEntity a2 = new AccountEntity();
            a2.setAccountName("wb-2");
            a2.setBalance(BigDecimal.TWO);
            br.insertBeanList(List.of(a1, a2));
            String msg = firstMatching("[批量插入]");
            assertNotNull(msg, "BeanRunner 直连旁路也应输出 debug 日志");
            assertTrue(msg.contains("参数组数: 2"), msg);
            assertTrue(messages().stream().anyMatch(m -> m.startsWith("[批量插入] 完成")), "应有完成日志");
            assertEquals(0, messages().stream().filter(m -> m.contains("wb-1")).count(),
                    "不应打印每条实体参数");
        }
    }

}
