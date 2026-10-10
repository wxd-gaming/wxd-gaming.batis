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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 执行过程 debug 日志测试：QueryRunner 五个执行入口 + BeanRunner 批量插入旁路
 * <p>日志开关 = logger 级别（不引入第二套框架开关）：实现里统一用 isDebugEnabled 短路，
 * 级别不是 DEBUG 时既不拼字符串也不记录耗时，所以生产默认零开销。
 * 本类用 logback 的 ListAppender 把日志截获到内存里，从"日志文本"这个外部可见的行为来断言，
 * 而不是去测内部计时方法。</p>
 * <p>断言的正反两面同样重要：该输出的必须输出（SQL、参数、结果规模、耗时），
 * 不该输出的必须没有（批量不能逐条打印参数，否则一次 5 万条的批量会把日志刷爆，
 * 也可能把敏感数据整批落到日志里）。</p>
 * <p>BeanRunner 的批量插入走的是绕过 QueryRunner 的裸 JDBC 旁路，
 * 若只给 QueryRunner 埋点，这条最常用的写入路径在 debug 下反而是静默的，故单独覆盖。</p>
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

    /**
     * 挂上内存 appender，并把两个被测 logger 临时调到 DEBUG。
     * <p>三处必要的收尾动作都在 {@link #detachAppender()} 里：摘 appender、恢复原级别、停止 appender。
     * 级别必须恢复是因为 logger 是 JVM 全局单例，留下 DEBUG 会让同进程后续测试类的日志全部炸出来。
     * 末尾清空 appender 列表：建表和清表自身也会打 debug 日志，
     * 不清掉就会污染"某条日志是否出现"这类断言。</p>
     */
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

    /**
     * 用例①：query / execute / update / executeInsert 四个入口都要输出可调试的单行日志。
     * <p>动作：依次执行四类语句（查计数、建表、插入一行、插入并取自增键）。
     * 断言每条日志都带齐四要素：语句类型标签、原始 SQL、绑定参数、结果与耗时
     * ——例如 update 那条必须能同时看到 {@code 参数: [42]} 和 {@code 结果: 1}，
     * executeInsert 那条要看到回传的自增主键。
     * 这四要素就是"复现一条 SQL 所需的全部信息"，缺任何一项调试时都得回去加日志再跑一遍。
     * finally 里 DROP 探针表，避免污染持久化数据目录里的 test 库。</p>
     */
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

    /**
     * 用例②：执行失败同样要留下日志（异常向上抛之前先记录）。
     * <p>动作：查询一张不存在的表，异常被 catch 住只关心日志。
     * 断言：出现一条"失败"日志，且里面带有出问题的表名。
     * 意义：失败路径的日志价值高于成功路径——线上排查看的就是"哪条 SQL 在什么参数下炸了"。
     * 如果实现只在成功分支打日志，抛异常时调用栈里的异常信息虽然带 SQL，
     * 但没开 debug 的日志文件里就一条痕迹都没有，事后无法定位。</p>
     */
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

    /**
     * 用例③：批量日志只报"组数 + 耗时 + 影响行数"，绝不逐条打印参数。
     * <p>动作：一次 3 组参数的 executeBatch。断言三件事：
     * 开始前那条带"参数组数: 3"；结束后有一条"完成"并带耗时；
     * 整份日志里检索不到任何一行的参数值（用第一组的 b1 作为探针）。
     * 最后这条是这个用例的核心：批量的参数动辄成千上万条，
     * 逐条打印会瞬间刷爆日志盘，并把本不该落盘的业务数据整批写进日志文件。
     * 用"数量 + 耗时"表达批量执行信息，才是可运维的做法。</p>
     */
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

    /**
     * 用例④：BeanRunner 的批量插入旁路也有日志（埋点不能只覆盖 QueryRunner）。
     * <p>背景：{@code insertBeanList} 为了性能直接用裸 JDBC 批处理，绕开了 QueryRunner 的五个入口，
     * 因此它不会自动获得埋点。这条路径恰恰是写入量最大的路径，
     * 如果 debug 下静默，就会出现"看得到查询 SQL、看不到实际写了什么"的错误印象。</p>
     * <p>动作：插入 2 条实体。断言：有一条"参数组数: 2"的批量插入日志和一条"完成"日志，
     * 并且同样不出现每条实体的具体值（用第一条的账号名 wb-1 作探针）。</p>
     */
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

    /**
     * 用例⑤：char[] 参数在 debug 日志里必须脱敏为 {@code [***]}。
     * <p>背景：char[] 是密码等敏感数据的惯用载体（JDBC 的 DataSource 密码就是 char[]），
     * 逐字符打印等于把明文写进日志文件。实现里 {@code formatParams} 对 char[] 打占位符。
     * 本用例把真实密码字符当作探针，断言整份日志里检索不到它、且能检索到脱敏占位。
     * char[] 不是 JDBC {@code setObject} 支持的类型，驱动可能直接报错——本用例只关心日志文本，
     * 所以把执行异常吞掉，成功/失败两条路径都要满足同样的脱敏断言。</p>
     */
    @Test
    void charArrayParamIsMaskedInLog() {
        char[] secret = "s3cret-pw".toCharArray();
        try (QueryRunner qr = new QueryRunner(template)) {
            try {
                qr.query("SELECT * FROM `t_account` WHERE `account_name` = ?",
                        new Object[]{secret}, ResultSetHandlers.firstCell());
            } catch (RuntimeException expected) {
                // char[] 非 JDBC 标准参数类型，驱动可能拒绝；本用例只关心日志脱敏
            }
        }
        assertNull(firstMatching("s3cret-pw"), "日志中不应出现明文密码字符");
        assertNotNull(firstMatching("[***]"), "日志参数中应出现脱敏占位 [***]");
    }

}
