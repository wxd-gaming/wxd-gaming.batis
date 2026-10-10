package com.wxdgaming.batis.sql.mysql;

import com.wxdgaming.batis.sql.QueryRunner;
import com.wxdgaming.batis.sql.ResultSetHandlers;
import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QueryRunner 关闭语义测试
 *
 * <p>守护两件事：</p>
 * <ul>
 *   <li><b>close 幂等</b>：try-with-resources 与手工 close 并存（或误调两次）时不能二次归还连接；
 *       实现靠"归还后把 connection 置 null"来保证，这里用连续多次 close 不抛异常来锁住。</li>
 *   <li><b>关闭后 source 复位</b>：事务内 runner 的 source 是 TRANSACTION，close 只丢弃引用、
 *       不动事务连接；但若不复位 source，关闭后再问 isInTransaction() 会残留 true，
 *       让"这个 runner 还在事务里"成为假象。这里断言 close 后 isInTransaction() 为 false。</li>
 * </ul>
 * <p>两条用例都只用 {@code SELECT 1}，不依赖任何表，因此本类无需建表/清表。</p>
 */
class QueryRunnerCloseTest {

    @RegisterExtension
    static MariaDB4jExtension maria = new MariaDB4jExtension();

    static MysqlTemplate template;

    @BeforeAll
    static void setUp() {
        if (template != null) return;
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(maria.getJdbcUrl());
        config.setUsername("root");
        config.setPassword("");
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        template = new MysqlTemplate(config);
    }

    @Test
    void closeIsIdempotent() {
        QueryRunner qr = new QueryRunner(template);
        qr.query("SELECT 1", ResultSetHandlers.firstCell());
        assertTrue(qr.isCloseConnection(), "池借出的连接应标记为需由 close 归还");

        qr.close();
        qr.close(); // 重复 close 不应抛异常（try-with-resources 与手工 close 并存）
        qr.close();

        assertFalse(qr.isInTransaction(), "关闭后不应处于事务状态");
    }

    @Test
    void closeInsideTransactionResetsSource() {
        template.transactionTemplate().executeWithoutResult(status -> {
            QueryRunner qr = new QueryRunner(template);
            qr.query("SELECT 1", ResultSetHandlers.firstCell());
            assertTrue(qr.isInTransaction(), "事务内执行过 SQL 后 runner 应绑定事务连接");

            qr.close();
            assertFalse(qr.isInTransaction(), "close 后 source 应复位，不再报在事务中");
            // 事务本身不受影响：这里只丢 runner 的引用，连接仍归事务模板管理
        });
    }
}
