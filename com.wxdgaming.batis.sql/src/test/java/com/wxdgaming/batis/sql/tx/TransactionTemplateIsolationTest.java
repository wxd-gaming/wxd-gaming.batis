package com.wxdgaming.batis.sql.tx;

import com.wxdgaming.batis.sql.JdbcTemplate;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事务连接的隔离级别恢复测试（离线，不连库）。
 *
 * <p>隔离级别是<b>连接级</b>状态：池化连接会被下一个线程复用，所以显式指定 isolation 的事务
 * 结束时必须把原值写回去。漏恢复的表现是"同池后续事务莫名继承了一个更严格的隔离级别"，
 * 既没有异常也没有日志，只有偶发的锁等待或死锁——正是 `ConnectionHolder.previousIsolation`
 * 与 `release` 里那两行恢复代码要守的东西。</p>
 *
 * <p>用一个记录调用的假连接断言"调了哪些方法、按什么顺序"，而不是断言最终状态：
 * 只置 true/false 的假对象无法区分"设置过又恢复"和"从未设置"，
 * 而 DEFAULT 档的语义恰恰是"不发起任何隔离级别调用"。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-11-01
 */
class TransactionTemplateIsolationTest {

    private static final int DB_DEFAULT_ISOLATION = Connection.TRANSACTION_REPEATABLE_READ;

    private static final class FakeDataSource extends HikariDataSource {
        private final Connection connection;

        FakeDataSource(Connection connection) {
            this.connection = connection;
        }

        @Override
        public Connection getConnection() {
            return connection;
        }
    }

    private static final class FakeConnection {
        final List<String> events = new ArrayList<>();
        boolean autoCommit = true;
        int isolation = DB_DEFAULT_ISOLATION;
        boolean readOnly;

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
                    case "getTransactionIsolation":
                        return isolation;
                    case "setTransactionIsolation":
                        isolation = (Integer) args[0];
                        events.add("setIsolation:" + isolation);
                        return null;
                    case "setReadOnly":
                        readOnly = (Boolean) args[0];
                        events.add("setReadOnly:" + readOnly);
                        return null;
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

    private static TransactionTemplate templateWith(FakeConnection conn) {
        return new TransactionTemplate(new JdbcTemplate(new FakeDataSource(conn.proxy())));
    }

    @Test
    @DisplayName("显式 READ_COMMITTED：事务内设置、结束后恢复连接原级别")
    void explicitIsolationIsRestoredAfterCommit() {
        FakeConnection conn = new FakeConnection();
        TransactionTemplate tx = templateWith(conn);
        tx.setIsolation(Isolation.READ_COMMITTED);

        String result = tx.execute(status -> {
            assertEquals(Connection.TRANSACTION_READ_COMMITTED, conn.isolation, "事务内应已是 READ_COMMITTED");
            return "ok";
        });

        assertEquals("ok", result);
        assertEquals(DB_DEFAULT_ISOLATION, conn.isolation, "归还前必须恢复为库/池的原级别");
        assertEquals(List.of(
                "setIsolation:" + Connection.TRANSACTION_READ_COMMITTED,
                "setAutoCommit:false",
                "commit",
                "setAutoCommit:true",
                "setIsolation:" + DB_DEFAULT_ISOLATION,
                "close"), conn.events);
    }

    @Test
    @DisplayName("DEFAULT 档不主动设置隔离级别，也不读取它")
    void defaultIsolationTouchesNothing() {
        FakeConnection conn = new FakeConnection();
        TransactionTemplate tx = templateWith(conn);

        tx.execute(status -> null);

        assertFalse(conn.events.stream().anyMatch(e -> e.startsWith("setIsolation")),
                "DEFAULT 不应发起隔离级别设置: " + conn.events);
        assertFalse(conn.events.contains("getTransactionIsolation"), "DEFAULT 连读取都不需要");
        assertEquals(DB_DEFAULT_ISOLATION, conn.isolation);
    }

    @Test
    @DisplayName("回调抛异常：隔离级别与 autoCommit 仍要恢复（回滚不等于可以跳过清理）")
    void isolationIsRestoredOnRollbackPath() {
        FakeConnection conn = new FakeConnection();
        TransactionTemplate tx = templateWith(conn);
        tx.setIsolation(Isolation.SERIALIZABLE);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> tx.execute(status -> {
                    throw new IllegalStateException("boom");
                }));

        assertEquals("boom", ex.getMessage());
        assertTrue(conn.events.contains("rollback"), "异常路径必须回滚: " + conn.events);
        assertFalse(conn.events.contains("commit"), "异常路径不能提交: " + conn.events);
        assertEquals(DB_DEFAULT_ISOLATION, conn.isolation, "回滚路径同样要恢复隔离级别");
        assertTrue(conn.autoCommit, "回滚路径同样要恢复 autoCommit");
        assertEquals("close", conn.events.get(conn.events.size() - 1));
    }

    @Test
    @DisplayName("只读事务：readOnly 在归还前复位，避免污染池中连接")
    void readOnlyFlagIsReset() {
        FakeConnection conn = new FakeConnection();
        TransactionTemplate tx = templateWith(conn);
        tx.setReadOnly(true);

        tx.execute(status -> null);

        assertFalse(conn.readOnly, "归还前必须复位 readOnly");
        assertTrue(conn.events.contains("setReadOnly:true"));
        assertTrue(conn.events.contains("setReadOnly:false"));
    }

    @Test
    @DisplayName("REQUIRES_NEW 内层结束后，外层事务的隔离级别不受影响")
    void requiresNewKeepsOuterIsolation() {
        FakeConnection conn = new FakeConnection();
        TransactionTemplate tx = templateWith(conn);
        tx.setIsolation(Isolation.READ_COMMITTED);

        tx.execute(Propagation.REQUIRED, outer -> {
            assertEquals(Connection.TRANSACTION_READ_COMMITTED, conn.isolation);
            tx.execute(Propagation.REQUIRES_NEW, inner -> {
                assertEquals(Connection.TRANSACTION_READ_COMMITTED, conn.isolation,
                        "内层新事务沿用同一模板配置");
                return null;
            });
            assertEquals(Connection.TRANSACTION_READ_COMMITTED, conn.isolation,
                    "内层结束不应改动外层连接状态");
            return null;
        });

        assertEquals(DB_DEFAULT_ISOLATION, conn.isolation);
    }
}
