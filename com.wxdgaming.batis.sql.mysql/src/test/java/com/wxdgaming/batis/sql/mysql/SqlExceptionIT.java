package com.wxdgaming.batis.sql.mysql;

import com.wxdgaming.batis.exception.BadSqlGrammarException;
import com.wxdgaming.batis.exception.DataAccessException;
import com.wxdgaming.batis.exception.DataTruncationException;
import com.wxdgaming.batis.exception.DeadlockLoserDataAccessException;
import com.wxdgaming.batis.exception.DuplicateKeyException;
import com.wxdgaming.batis.exception.NotNullConstraintViolationException;
import com.wxdgaming.batis.exception.UnknownColumnException;
import com.wxdgaming.batis.sql.QueryRunner;
import com.wxdgaming.batis.sql.ResultSetHandlers;
import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigDecimal;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MySQL 方言异常翻译集成测试（真库触发原生错误码，断言映射到正确的异常子类）
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
@TestMethodOrder(OrderAnnotation.class)
class SqlExceptionIT {

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
        template.checkTable(AccountEntity.class);
    }

    @BeforeEach
    @AfterEach
    void cleanTable() {
        try (QueryRunner qr = new QueryRunner(template)) {
            qr.update("DELETE FROM `t_account`");
        }
    }

    @Test
    @Order(1)
    void duplicateKey1062MapsToDuplicateKeyException() {
        try (QueryRunner qr = new QueryRunner(template)) {
            qr.update("INSERT INTO `t_account` (`account_name`, `balance`) VALUES (?, ?)",
                    new Object[]{"dup-user", new BigDecimal("1.00")});
            DuplicateKeyException ex = assertThrows(DuplicateKeyException.class, () ->
                    qr.update("INSERT INTO `t_account` (`account_name`, `balance`) VALUES (?, ?)",
                            new Object[]{"dup-user", new BigDecimal("2.00")}));
            assertEquals(1062, ex.getErrorCode());
            assertFalse(ex.isRetryable());
            // 上下文携带：SQL 与参数原样随异常抛出
            assertEquals("INSERT INTO `t_account` (`account_name`, `balance`) VALUES (?, ?)", ex.getSql());
            assertEquals(2, ex.getParams().length);
            assertEquals("dup-user", ex.getParams()[0]);
        }
    }

    @Test
    @Order(2)
    void unknownColumn1054MapsToUnknownColumnException() {
        try (QueryRunner qr = new QueryRunner(template)) {
            UnknownColumnException ex = assertThrows(UnknownColumnException.class, () ->
                    qr.query("SELECT `ghost_col` FROM `t_account`", new Object[0],
                            ResultSetHandlers.list()));
            assertEquals(1054, ex.getErrorCode());
        }
    }

    @Test
    @Order(3)
    void badGrammarMapsToBadSqlGrammarException() {
        try (QueryRunner qr = new QueryRunner(template)) {
            BadSqlGrammarException ex = assertThrows(BadSqlGrammarException.class, () ->
                    qr.update("SELCT 1"));
            assertEquals(1064, ex.getErrorCode());
        }
    }

    @Test
    @Order(4)
    void notNullViolationMaps() {
        try (QueryRunner qr = new QueryRunner(template)) {
            NotNullConstraintViolationException ex = assertThrows(NotNullConstraintViolationException.class, () ->
                    qr.update("INSERT INTO `t_account` (`balance`) VALUES (?)", new Object[]{new BigDecimal("1")}));
            // MySQL: 1048（列不能为 null）；MariaDB 对无默认值列给 1364（无默认值），两者均映射为 NotNull 违反
            assertTrue(ex.getErrorCode() == 1048 || ex.getErrorCode() == 1364,
                    "unexpected errorCode=" + ex.getErrorCode());
        }
    }

    @Test
    @Order(5)
    void dataTooLongMapsToTruncationUnderStrictMode() {
        try (QueryRunner qr = new QueryRunner(template)) {
            // 会话级开启严格模式，截断由 warning 变 error（连接为池化复用，测试后恢复）
            qr.update("SET SESSION sql_mode = 'STRICT_TRANS_TABLES'");
            try {
                DataTruncationException ex = assertThrows(DataTruncationException.class, () ->
                        qr.update("INSERT INTO `t_account` (`account_name`, `balance`) VALUES (?, ?)",
                                new Object[]{"x".repeat(200), new BigDecimal("1")}));
                assertEquals(1406, ex.getErrorCode());
            } finally {
                qr.update("SET SESSION sql_mode = ''");
            }
        }
    }

    @Test
    @Order(6)
    void dialectHandlerMapsSyntheticCodeAndDelegatesUnknown() {
        MysqlSqlExceptionHandler handler = new MysqlSqlExceptionHandler();
        // 1213 死锁 → Transient 可重试
        var deadlock = handler.translate(new SQLException("Deadlock found", "40001", 1213), "UPDATE x", null);
        assertInstanceOf(DeadlockLoserDataAccessException.class, deadlock);
        assertTrue(deadlock.isRetryable());
        // 数字码未命中 → 委托默认实现兜底，仍返回 DataAccessException
        var unknown = handler.translate(new SQLException("boom", null, 999999), "SELECT 1", null);
        assertInstanceOf(DataAccessException.class, unknown);
    }

}
