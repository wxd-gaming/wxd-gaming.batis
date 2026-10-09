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
 * <p>每个用例都用真库（MariaDB4j）触发一次原生错误，断言被翻译成异常体系里正确的子类，
 * 并携带原始 SQL、参数与数据库错误码。测试点不是"能不能抛异常"，而是
 * <b>抛的是哪一类</b>：调用方据此决定重试、还是直接失败回滚。</p>
 * <p>分工说明：本类只用真库覆盖可稳定复现的错误码（1062/1054/1064/1406/1048 与 1364）；
 * 死锁、锁超时这类难以稳定复现的瞬时故障，用合成 SQLException 直接验证方言映射器；
 * 真实并发死锁则交给 {@code TransactionTemplateIT#concurrentTransfersConserveBalance} 端到端兜住。</p>
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

    /**
     * 唯一键冲突 1062 → {@link DuplicateKeyException}。
     * <p>前置：{@code t_account.account_name} 是唯一索引，先插入 dup-user。
     * 动作：用同名再插一次，由真库抛出原生 1062。
     * 断言三层：异常子类型正确；{@code errorCode == 1062}（说明走的是 MySQL 数字码精确映射，
     * 不是靠 message 关键字猜的）；{@code isRetryable() == false}
     * （重复键是数据问题，重试永远失败，业务必须直接处理而不是被无限重试拖住）；
     * 最后断言异常携带原始 SQL 与参数（{@code params[0]} 仍是 dup-user），
     * 排查线上问题时不用复现就能知道是哪条数据撞了键。</p>
     */
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

    /**
     * 未知列 1054 → {@link UnknownColumnException}。
     * <p>动作：SELECT 一个不存在的列 ghost_col。断言 errorCode = 1054。
     * 意义：与"语法错误"区分开——1054 通常来自实体字段名/列名映射写错，
     * 归到 UnknownColumn 后调用方可以按"映射问题"处理，而不是当作 SQL 拼错。</p>
     */
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

    /**
     * 语法错误 1064 → {@link BadSqlGrammarException}。
     * <p>动作：执行故意写错的语句 {@code SELCT 1}。断言映射到语法类异常且 errorCode = 1064。
     * 意义：证明兜底分支能覆盖最常见的一类编码错误；不可重试，应尽快在开发期暴露。</p>
     */
    @Test
    @Order(3)
    void badGrammarMapsToBadSqlGrammarException() {
        try (QueryRunner qr = new QueryRunner(template)) {
            BadSqlGrammarException ex = assertThrows(BadSqlGrammarException.class, () ->
                    qr.update("SELCT 1"));
            assertEquals(1064, ex.getErrorCode());
        }
    }

    /**
     * 非空约束违反 → {@link NotNullConstraintViolationException}。
     * <p>动作：INSERT 时不给 NOT NULL 的 account_name 赋值。
     * 断言故意写成"1048 或 1364 都可"：MySQL 报 1048（列不能为 null），
     * 而本测试跑在 MariaDB 上，对无默认值的 NOT NULL 列报的是 1364（无默认值）。
     * 两个码在 {@link com.wxdgaming.batis.sql.mysql.MysqlSqlCodes} 里都归入 NotNull 违反，
     * 所以这里锁住的是"归类正确"，而不是某个厂商的具体数字。</p>
     */
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

    /**
     * 数据过长 1406 → {@link DataTruncationException}（需在严格模式下才会报错）。
     * <p>前置：先 {@code SET SESSION sql_mode = 'STRICT_TRANS_TABLES'}。
     * 原因：默认非严格模式下，超长字符串只被静默截断并产生 warning，根本不抛异常，
     * 用例就会因为"没异常"而失败——所以必须显式开启严格模式，这是本测试的前置条件而非被测行为。</p>
     * <p>动作：向 varchar(64) 列写入 200 个字符。断言：抛截断类异常且 errorCode = 1406。
     * finally 里必须把 sql_mode 复位为空：连接是从 HikariCP 借出的，
     * 会话级设置会跟着连接回到池里污染后续用例。</p>
     */
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

    /**
     * 方言 handler 的纯单测分支：合成码映射 + 未命中委托兜底。
     * <p>为什么不走真库：死锁（1213）需要精确构造两个事务的加锁环，在单次测试里不稳定、
     * 也难以保证每次必现，而这里要验证的只是"码表映射逻辑"本身，
     * 因此直接手工构造 {@link java.sql.SQLException} 喂给 handler，确定且零成本。
     * （真实死锁的可重试性由 TransactionTemplateIT 的并发转账用例从端到端覆盖。）</p>
     * <p>断言两点：1213 → {@link DeadlockLoserDataAccessException} 且 isRetryable 为真；
     * 未登记在码表里的数字码（999999）不应抛"翻译失败"，而是委托
     * {@code DefaultSqlExceptionHandler} 按 SQLState/关键字给出一个 DataAccessException 子类
     * ——保证任何数据库异常都落到统一异常体系里，调用方永远只需 catch DataAccessException。</p>
     */
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
