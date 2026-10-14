package com.wxdgaming.batis.sql.pgsql;

import com.wxdgaming.batis.exception.BadSqlGrammarException;
import com.wxdgaming.batis.exception.CannotAcquireLockException;
import com.wxdgaming.batis.exception.CannotGetJdbcConnectionException;
import com.wxdgaming.batis.exception.DataAccessException;
import com.wxdgaming.batis.exception.DataTruncationException;
import com.wxdgaming.batis.exception.DeadlockLoserDataAccessException;
import com.wxdgaming.batis.exception.DuplicateKeyException;
import com.wxdgaming.batis.exception.NotNullConstraintViolationException;
import com.wxdgaming.batis.exception.QueryTimeoutException;
import com.wxdgaming.batis.exception.RecoverableDataAccessException;
import com.wxdgaming.batis.exception.UnknownColumnException;
import com.wxdgaming.batis.sql.QueryRunner;
import com.wxdgaming.batis.sql.ResultSetHandlers;
import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.math.BigDecimal;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PG 异常翻译集成测试（真库：嵌入式 PostgreSQL）
 * <p>每个用例都用真 PG 触发一次原生错误，断言被翻译成异常体系里正确的子类。
 * 测试点不是"能不能抛异常"，而是<b>抛的是哪一类</b>：调用方据此决定重试还是直接失败回滚。</p>
 * <p>与 MySQL 的 {@code SqlExceptionIT} 最大的差别在信号来源：PG 驱动的
 * {@code SQLException.getErrorCode()} 恒为 0，唯一可靠的信号是 SQLSTATE 字符串。
 * 所以本类的断言全部落在 {@code getSqlState()} 上，并且特意保留一条
 * {@code errorCode == 0} 的断言作为反面证据——照抄 MySQL 的数字码分派在这里一个 case 都不会命中，
 * 所有异常会落进兜底分支，"可重试 / 直接失败"的判别随之整体失效。</p>
 * <p>分工：本类只用真库覆盖可稳定复现的 state（23505/23502/22001/22003/42703/42601/42P01）；
 * 死锁、串行化冲突、锁等待、语句超时这类瞬时故障难以稳定复现，改用合成 SQLException 验证映射器。
 * 事务语义本身与方言无关（由核心层的 TransactionTemplateIT 覆盖），这里不再重复。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-14
 **/
@TestMethodOrder(OrderAnnotation.class)
class PgSqlExceptionIT {

    @RegisterExtension
    static PgsqlExtension pg = new PgsqlExtension();

    static PgsqlTemplate template;

    static PgsqlTemplate template() {
        if (template != null) return template;
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(pg.getJdbcUrl());
        config.setUsername(pg.getUsername());
        config.setPassword(pg.getPassword());
        config.setDriverClassName("org.postgresql.Driver");
        template = new PgsqlTemplate(config);
        return template;
    }

    @BeforeAll
    static void setUp() {
        template().checkTable(PgAccountEntity.class);
        template().checkTable(PgDdlDriftEntity.class);
    }

    @BeforeEach
    void cleanTables() {
        try (QueryRunner qr = new QueryRunner(template())) {
            qr.update("DELETE FROM \"t_pg_account\"");
            qr.update("DELETE FROM \"t_pg_ddl_drift\"");
        }
    }

    /**
     * 用例①：唯一键冲突 23505 → {@link DuplicateKeyException}。
     * <p>前置：先插入 dup-user（account_name 上有唯一索引）。
     * 动作：同名再插一次，由真库报 23505。
     * 断言四层：子类正确；{@code sqlState == "23505"}；{@code errorCode == 0}
     * （PG 不给数字码，这条是"翻译器必须按 state 分派"的直接证据）；
     * {@code isRetryable() == false}——重复键是数据问题，重试永远失败，
     * 不该被补偿逻辑反复拖住。最后确认异常携带原始 SQL 与参数，
     * 排查线上撞键时不必复现就知道是哪条数据。</p>
     */
    @Test
    @Order(1)
    void uniqueViolation23505MapsToDuplicateKeyException() {
        try (QueryRunner qr = new QueryRunner(template())) {
            qr.update("INSERT INTO \"t_pg_account\" (\"account_name\", \"balance\") VALUES (?, ?)",
                    new Object[]{"dup-user", new BigDecimal("1.00")});
            String sql = "INSERT INTO \"t_pg_account\" (\"account_name\", \"balance\") VALUES (?, ?)";
            DuplicateKeyException ex = assertThrows(DuplicateKeyException.class, () ->
                    qr.update(sql, new Object[]{"dup-user", new BigDecimal("2.00")}));
            assertEquals("23505", ex.getSqlState());
            assertEquals(0, ex.getErrorCode(), "PG 驱动不填 errorCode，信号只在 SQLState 上");
            assertFalse(ex.isRetryable());
            assertEquals(sql, ex.getSql());
            assertEquals(2, ex.getParams().length);
            assertEquals("dup-user", ex.getParams()[0]);
        }
    }

    /**
     * 用例②：非空违反 23502 → {@link NotNullConstraintViolationException}。
     * <p>动作：给 NOT NULL 的 account_name 绑 null。
     * 意义：默认兜底实现靠消息关键字判完整性细分，而 PG 的消息是
     * {@code null value in column "account_name" violates not-null constraint}（带连字符），
     * 匹配不上 "not null"，只会得到一个笼统的 DataIntegrityViolation。
     * 精确映射到 NotNull 子类之后，调用方才能把它归到"实体漏字段"而不是"外键/唯一键问题"。</p>
     */
    @Test
    @Order(2)
    void notNullViolation23502Maps() {
        try (QueryRunner qr = new QueryRunner(template())) {
            NotNullConstraintViolationException ex =
                    assertThrows(NotNullConstraintViolationException.class, () ->
                            qr.update("INSERT INTO \"t_pg_account\" (\"account_name\", \"balance\") VALUES (?, ?)",
                                    new Object[]{null, new BigDecimal("1.00")}));
            assertEquals("23502", ex.getSqlState());
        }
    }

    /**
     * 用例③：值过长 22001 → {@link DataTruncationException}。
     * <p>与 MySQL 的对照：MySQL 默认非严格模式只给 warning 并静默截断，测试必须先
     * {@code SET SESSION sql_mode='STRICT_TRANS_TABLES'}；PG 没有这个开关，
     * varchar(64) 装不下就是 22001，所以本用例无需任何前置设置。
     * 断言"写入端不会被悄悄裁短"这件事在两种库里都能报错——静默截断是数据丢失，不是兼容性。</p>
     */
    @Test
    @Order(3)
    void valueTooLong22001MapsToTruncation() {
        try (QueryRunner qr = new QueryRunner(template())) {
            DataTruncationException ex = assertThrows(DataTruncationException.class, () ->
                    qr.update("INSERT INTO \"t_pg_account\" (\"account_name\", \"balance\") VALUES (?, ?)",
                            new Object[]{"x".repeat(200), new BigDecimal("1.00")}));
            assertEquals("22001", ex.getSqlState());
        }
    }

    /**
     * 用例④：数值越界 22003 → {@link DataTruncationException}。
     * <p>动作：向 {@code t_pg_ddl_drift.tiny}（Java byte 折叠成的 smallint）写 100000。
     * 这条同时验证了两件事：PG 确实用 smallint 承接了 byte 字段（列类型侧 {@code PgSchemaDriftIT} 已断言，
     * 这里从取值范围反证），以及 22 类越界与 22001 一样归入"业务改数据、重试无意义"的那一档。</p>
     */
    @Test
    @Order(4)
    void numericOutOfRange22003MapsToTruncation() {
        try (QueryRunner qr = new QueryRunner(template())) {
            DataTruncationException ex = assertThrows(DataTruncationException.class, () ->
                    qr.update("INSERT INTO \"t_pg_ddl_drift\" (\"short_text\", \"tiny\") VALUES (?, ?)",
                            new Object[]{"ok", 100000}));
            assertEquals("22003", ex.getSqlState());
        }
    }

    /**
     * 用例⑤：未知列 42703 → {@link UnknownColumnException}。
     * <p>意义：与"语法错误"区分开——42703 通常来自实体字段与表结构漂移，
     * 归到 UnknownColumn 后调用方按"映射问题"处理。默认兜底会把
     * {@code column "ghost_col" does not exist} 认成语法错误（它只认 MySQL 的 "unknown column" 措辞）。</p>
     */
    @Test
    @Order(5)
    void undefinedColumn42703MapsToUnknownColumnException() {
        try (QueryRunner qr = new QueryRunner(template())) {
            UnknownColumnException ex = assertThrows(UnknownColumnException.class, () ->
                    qr.query("SELECT \"ghost_col\" FROM \"t_pg_account\"", new Object[0],
                            ResultSetHandlers.list()));
            assertEquals("42703", ex.getSqlState());
        }
    }

    /**
     * 用例⑥：语法错误 42601 → {@link BadSqlGrammarException}。
     * <p>动作：故意写错的 {@code SELCT 1}。不可重试，应尽快在开发期暴露。</p>
     */
    @Test
    @Order(6)
    void syntaxError42601MapsToBadSqlGrammar() {
        try (QueryRunner qr = new QueryRunner(template())) {
            BadSqlGrammarException ex = assertThrows(BadSqlGrammarException.class,
                    () -> qr.update("SELCT 1"));
            assertEquals("42601", ex.getSqlState());
        }
    }

    /**
     * 用例⑦：表不存在 42P01 → {@link BadSqlGrammarException}。
     * <p>动作：查询一张没建的表。归到语法/对象名这一档而不是"未知列"，
     * 因为它的修法完全不同（建表或 checkTable，而不是改列名映射）。
     * 这也是 pgsql 模块"启动时表还没建好"最常见的现场。</p>
     */
    @Test
    @Order(7)
    void undefinedTable42P01MapsToBadSqlGrammar() {
        try (QueryRunner qr = new QueryRunner(template())) {
            BadSqlGrammarException ex = assertThrows(BadSqlGrammarException.class, () ->
                    qr.query("SELECT 1 FROM \"t_pg_no_such_table\"", new Object[0],
                            ResultSetHandlers.list()));
            assertEquals("42P01", ex.getSqlState());
        }
    }

    /**
     * 用例⑧：瞬时故障用合成 SQLException 验证映射（死锁 / 串行化 / 锁等待 / 超时 / 连接）。
     * <p>为什么不走真库：40P01 死锁要精确构造两个事务的加锁环，40001 要 REPEATABLE READ 下的写写冲突，
     * 在单次测试里都不稳定；而这里要验证的只是"state 表映射逻辑"，直接喂 SQLException 确定且零成本。</p>
     * <p>逐条断言的取舍：40P01 与 40001 都归 {@link DeadlockLoserDataAccessException} 且可重试
     * （PG 在串行化隔离级别下用 40001 而不是死锁码报冲突，漏掉它就会把一个"重试即可"的失败升级成业务异常）；
     * 55P03 → {@link CannotAcquireLockException}；57014 → {@link QueryTimeoutException}；
     * 57P01/57P02 这类"重连即可"的关闭归 {@link RecoverableDataAccessException} 而不是默认实现给的超时类；
     * 53300 连接数打满归 {@link CannotGetJdbcConnectionException}（是池的问题，不是语句的问题）。</p>
     */
    @Test
    @Order(8)
    void transientStatesMapToRetryableClasses() {
        PgsqlSqlExceptionHandler h = new PgsqlSqlExceptionHandler();

        var deadlock = h.translate(new SQLException("deadlock detected", "40P01", 0), "UPDATE x", null);
        assertInstanceOf(DeadlockLoserDataAccessException.class, deadlock);
        assertTrue(deadlock.isRetryable(), "死锁应可重试");

        var serialization = h.translate(
                new SQLException("could not serialize access due to concurrent update", "40001", 0),
                "UPDATE x", null);
        assertInstanceOf(DeadlockLoserDataAccessException.class, serialization);
        assertTrue(serialization.isRetryable(), "串行化冲突应可重试");

        assertInstanceOf(CannotAcquireLockException.class,
                h.translate(new SQLException("lock timeout", "55P03", 0), "SELECT 1", null));
        assertInstanceOf(QueryTimeoutException.class,
                h.translate(new SQLException("canceling statement due to statement timeout", "57014", 0),
                        "SELECT 1", null));
        assertInstanceOf(RecoverableDataAccessException.class,
                h.translate(new SQLException("admin shutdown", "57P01", 0), "SELECT 1", null));
        assertInstanceOf(CannotGetJdbcConnectionException.class,
                h.translate(new SQLException("too many connections", "53300", 0), "SELECT 1", null));
        // 完整性族的其他细分：外键 23503、CHECK 23514 各有归属，不能塌成一个
        assertInstanceOf(com.wxdgaming.batis.exception.ForeignKeyConstraintViolationException.class,
                h.translate(new SQLException("violates foreign key", "23503", 0), "DELETE x", null));
        assertInstanceOf(com.wxdgaming.batis.exception.DataIntegrityViolationException.class,
                h.translate(new SQLException("violates check constraint", "23514", 0), "INSERT x", null));
    }

    /**
     * 用例⑨：未收录的 state 与空 state 都必须落到统一异常体系，且不能因"翻译失败"再抛一层。
     * <p>动作：给一个码表里没有的 state（"ZZ999"）和 null state 各翻译一次。
     * 断言：返回的仍是 {@link DataAccessException} 子类。
     * 意义：调用方只需 catch DataAccessException 就能兜住任何数据库异常；
     * 如果映射器在未知码上抛异常，故障就从"可分类的数据库错误"升级成"翻译器自己坏了"。</p>
     */
    @Test
    @Order(9)
    void unknownStateDelegatesToFallbackWithoutThrowing() {
        PgsqlSqlExceptionHandler h = new PgsqlSqlExceptionHandler();
        var unmapped = h.translate(new SQLException("boom", "ZZ999", 0), "SELECT 1", null);
        assertInstanceOf(DataAccessException.class, unmapped);
        var noState = h.translate(new SQLException("boom", null, 0), "SELECT 1", null);
        assertInstanceOf(DataAccessException.class, noState);
        assertEquals("pgsql", h.dialectName());
    }

}
