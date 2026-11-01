package com.wxdgaming.batis.sql;

import com.wxdgaming.batis.exception.BadSqlGrammarException;
import com.wxdgaming.batis.exception.DataAccessException;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JdbcTemplate#executeDdl} 的离线验收测试。
 *
 * <p>这一条链路正是 H6 修复的核心，四个行为都用假连接钉住，不依赖任何数据库：</p>
 * <ol>
 *   <li><b>独立连接</b>：DDL 不走 QueryRunner / 线程事务连接（MySQL 的 DDL 会隐式提交业务事务）；</li>
 *   <li><b>语句超时</b>：每条 DDL 都设置 {@value JdbcTemplate#DDL_QUERY_TIMEOUT_SECONDS} 秒上限，
 *   避免等待元数据锁时无限期挂起；</li>
 *   <li><b>同一连接、同一事务</b>：多条 DDL 依次在同一条连接上执行，成功后一次性 commit；</li>
 *   <li><b>失败回滚 + 状态还原</b>：任一条失败则 rollback，且无论成败都把 autoCommit 恢复为原值，
 *   否则一条 autoCommit=false 的连接回池后，下一个借用者的写入会悬在未提交状态。</li>
 * </ol>
 *
 * <p>用 {@link HikariDataSource} 子类 + {@link Proxy} 造连接，不引入 Mockito/H2 等新依赖，
 * 保证离线环境（Maven Central 不可达）也能运行。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-11-01
 */
class JdbcTemplateExecuteDdlTest {

    /** 只拦截 outbound 调用的假池：记录借出次数，把同一个假连接交给模板 */
    private static final class FakeDataSource extends HikariDataSource {
        private final Connection connection;
        private int borrowed;

        FakeDataSource(Connection connection) {
            this.connection = connection;
        }

        @Override
        public Connection getConnection() {
            borrowed++;
            return connection;
        }
    }

    /** 假连接的调用记录；executeUpdate 可按 SQL 注入失败 */
    private static final class FakeConnection {
        final List<String> events = new ArrayList<>();
        final List<String> preparedSqls = new ArrayList<>();
        final List<Integer> timeouts = new ArrayList<>();
        boolean autoCommit = true;
        String failOnSql;

        Connection proxy() {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    new Handler());
        }

        private final class Handler implements InvocationHandler {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                switch (method.getName()) {
                    case "getAutoCommit":
                        return autoCommit;
                    case "setAutoCommit":
                        autoCommit = (Boolean) args[0];
                        events.add("setAutoCommit:" + autoCommit);
                        return null;
                    case "prepareStatement":
                        return statement((String) args[0]);
                    case "commit":
                        events.add("commit");
                        return null;
                    case "rollback":
                        events.add("rollback");
                        return null;
                    case "close":
                        events.add("close");
                        return null;
                    default:
                        return defaultValue(method.getReturnType());
                }
            }
        }

        private PreparedStatement statement(String sql) {
            preparedSqls.add(sql);
            return (PreparedStatement) Proxy.newProxyInstance(
                    PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "setQueryTimeout":
                                timeouts.add((Integer) args[0]);
                                return null;
                            case "executeUpdate":
                                events.add("execute:" + sql);
                                if (sql.equals(failOnSql)) {
                                    throw new SQLException("You have an error in your SQL syntax", "42000", 1064);
                                }
                                return 0;
                            case "close":
                                return null;
                            default:
                                return defaultValue(method.getReturnType());
                        }
                    });
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == void.class) return null;
        if (type == long.class) return 0L;
        if (type == double.class) return 0d;
        if (type == float.class) return 0f;
        if (type == char.class) return (char) 0;
        return 0;
    }

    @Test
    @DisplayName("多条 DDL 复用一个独立连接：逐条设置 30 秒超时、成功后 commit、归还前恢复 autoCommit")
    void executesStatementsOnOneIndependentConnectionAndCommits() {
        FakeConnection conn = new FakeConnection();
        FakeDataSource ds = new FakeDataSource(conn.proxy());
        JdbcTemplate template = new JdbcTemplate(ds);

        int affected = template.executeDdl(List.of("ALTER TABLE a ADD COLUMN x INT", "CREATE INDEX i ON a (x)"));

        assertEquals(0, affected);
        assertEquals(1, ds.borrowed, "整组 DDL 只应借一条连接");
        assertEquals(List.of("ALTER TABLE a ADD COLUMN x INT", "CREATE INDEX i ON a (x)"), conn.preparedSqls);
        assertEquals(List.of(JdbcTemplate.DDL_QUERY_TIMEOUT_SECONDS, JdbcTemplate.DDL_QUERY_TIMEOUT_SECONDS), conn.timeouts,
                "每条 DDL 都必须带语句级超时");
        assertEquals(List.of(
                "setAutoCommit:false",
                "execute:ALTER TABLE a ADD COLUMN x INT",
                "execute:CREATE INDEX i ON a (x)",
                "commit",
                "setAutoCommit:true",
                "close"), conn.events);
    }

    @Test
    @DisplayName("中途失败：回滚、异常按方言翻译、控制台带出触发语句，并恢复 autoCommit")
    void rollsBackAndTranslatesOnFailure() {
        FakeConnection conn = new FakeConnection();
        conn.failOnSql = "BAD DDL";
        FakeDataSource ds = new FakeDataSource(conn.proxy());
        JdbcTemplate template = new JdbcTemplate(ds);

        DataAccessException ex = assertThrows(DataAccessException.class,
                () -> template.executeDdl(List.of("GOOD DDL", "BAD DDL")));

        assertInstanceOf(BadSqlGrammarException.class, ex, "SQLState 42 应翻译成语法类异常");
        assertEquals("GOOD DDL", ex.getSql(), "异常携带的是本组第一条语句（回滚语句），便于定位整组 DDL");
        assertTrue(conn.events.contains("rollback"), "失败必须回滚: " + conn.events);
        assertFalse(conn.events.contains("commit"), "失败路径不能提交: " + conn.events);
        assertEquals("setAutoCommit:true", conn.events.get(conn.events.size() - 2),
                "归还连接前必须恢复 autoCommit: " + conn.events);
        assertEquals("close", conn.events.get(conn.events.size() - 1));
    }

    @Test
    @DisplayName("连接原本 autoCommit=false 时，归还前恢复成 false 而不是无脑置 true")
    void restoresPreviousAutoCommitValue() {
        FakeConnection conn = new FakeConnection();
        conn.autoCommit = false;
        FakeDataSource ds = new FakeDataSource(conn.proxy());
        JdbcTemplate template = new JdbcTemplate(ds);

        template.executeDdl("ALTER TABLE a ADD COLUMN x INT");

        assertFalse(conn.autoCommit, "必须恢复为借用时的原值");
        assertEquals("setAutoCommit:false", conn.events.get(conn.events.size() - 2));
    }

    @Test
    @DisplayName("空集合直接返回 0，不借连接（启动期没有要同步的 DDL 是常态）")
    void emptyListBorrowsNoConnection() {
        FakeDataSource ds = new FakeDataSource(new FakeConnection().proxy());
        JdbcTemplate template = new JdbcTemplate(ds);

        assertEquals(0, template.executeDdl(List.of()));
        assertEquals(0, template.executeDdl((List<String>) null));
        assertEquals(0, ds.borrowed, "没有语句就不该触发建连/借连接");
    }

    @Test
    @DisplayName("单条 DDL 的异常同样携带该语句本身")
    void singleStatementCarriesItsSql() {
        FakeConnection conn = new FakeConnection();
        conn.failOnSql = "CREATE TABLE t_x (";
        FakeDataSource ds = new FakeDataSource(conn.proxy());
        JdbcTemplate template = new JdbcTemplate(ds);

        DataAccessException ex = assertThrows(DataAccessException.class,
                () -> template.executeDdl("CREATE TABLE t_x ("));

        assertEquals("CREATE TABLE t_x (", ex.getSql());
        assertTrue(conn.events.contains("rollback"));
        assertTrue(conn.events.contains("setAutoCommit:true"));
    }
}
