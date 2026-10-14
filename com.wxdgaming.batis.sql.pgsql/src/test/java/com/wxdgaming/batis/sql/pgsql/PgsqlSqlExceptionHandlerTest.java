package com.wxdgaming.batis.sql.pgsql;

import com.wxdgaming.batis.exception.BadSqlGrammarException;
import com.wxdgaming.batis.exception.CannotAcquireLockException;
import com.wxdgaming.batis.exception.CannotGetJdbcConnectionException;
import com.wxdgaming.batis.exception.DataAccessException;
import com.wxdgaming.batis.exception.DataIntegrityViolationException;
import com.wxdgaming.batis.exception.DataTruncationException;
import com.wxdgaming.batis.exception.DeadlockLoserDataAccessException;
import com.wxdgaming.batis.exception.DuplicateKeyException;
import com.wxdgaming.batis.exception.ForeignKeyConstraintViolationException;
import com.wxdgaming.batis.exception.NotNullConstraintViolationException;
import com.wxdgaming.batis.exception.QueryTimeoutException;
import com.wxdgaming.batis.exception.RecoverableDataAccessException;
import com.wxdgaming.batis.exception.UnknownColumnException;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PgsqlSqlExceptionHandler} 单元测试。
 * <p>用合成的 {@link SQLException} 而不是真库：这里要验证的是"SQLState → 异常子类"这张映射表本身，
 * 而 40001（死锁）、53300（连接打满）、57P01（服务端崩溃关闭）这类状态在单进程里无法稳定复现。
 * 真库能稳定触发的几个码由 {@code PgSqlExceptionIT} 从端到端覆盖。</p>
 * <p>每条断言都同时看两点：<b>子类</b>（决定调用方能catch到多细的语义）与
 * <b>{@code isRetryable()}</b>（决定重试还是快速失败）。分错一类的代价是 asymmetry 的：
 * 把唯一键冲突判成可重试，会让补偿逻辑反复压一条必然失败的语句；
 * 把服务端崩溃关闭判成不可重试，会把一次几秒的抖动放大成整批业务失败。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-14
 **/
class PgsqlSqlExceptionHandlerTest {

    private final PgsqlSqlExceptionHandler handler = new PgsqlSqlExceptionHandler();

    /**
     * 构造一个"像 PG 给的"异常。
     * <p>errorCode 一律留 0——这正是 PG 与 MySQL 的关键差别（MySQL 给 1062 这类厂商数字码），
     * 如果映射表里还残留按数字码分派的分支，这组用例就会失败。</p>
     */
    private static SQLException pg(String message, String sqlState) {
        return new SQLException(message, sqlState);
    }

    /** 用例①：完整性四兄弟各归其位，且都是不可重试 */
    @Test
    void integrityViolationsMapToNarrowSubclasses() {
        var dup = handler.translate(pg("duplicate key value violates unique constraint \"t_role_name_key\"",
                PgsqlSqlStates.UNIQUE_VIOLATION), "INSERT INTO t_role (name) VALUES (?)", new Object[]{"a"});
        assertInstanceOf(DuplicateKeyException.class, dup);
        assertFalse(dup.isRetryable());

        var notNull = handler.translate(pg("null value in column \"name\" violates not-null constraint",
                PgsqlSqlStates.NOT_NULL_VIOLATION), "INSERT", null);
        assertInstanceOf(NotNullConstraintViolationException.class, notNull);

        var fk = handler.translate(pg("insert or update on table \"t_role\" violates foreign key constraint",
                PgsqlSqlStates.FOREIGN_KEY_VIOLATION), "INSERT", null);
        assertInstanceOf(ForeignKeyConstraintViolationException.class, fk);

        var check = handler.translate(pg("new row violates check constraint",
                PgsqlSqlStates.CHECK_VIOLATION), "INSERT", null);
        assertInstanceOf(DataIntegrityViolationException.class, check);
    }

    /**
     * 用例②：非空违反必须落到 {@link NotNullConstraintViolationException} 这个窄子类。
     * <p>这是本类存在的理由之一：默认兜底实现靠消息关键字细分完整性违反，而 PG 的消息写的是
     * {@code violates not-null constraint}（带连字符），匹配不上它找的 {@code "not null"}，
     * 只会给出一个笼统的 DataIntegrityViolation。这里断言的是"没退化成宽类型"。</p>
     */
    @Test
    void notNullIsNotReducedToGenericIntegrity() {
        DataAccessException ex = handler.translate(
                pg("null value in column \"balance\" violates not-null constraint", PgsqlSqlStates.NOT_NULL_VIOLATION),
                "UPDATE", null);
        assertInstanceOf(NotNullConstraintViolationException.class, ex);
        // NotNull 是 DataIntegrityViolation 的子类，反过来不成立：宽类型判不出这条断言
        assertFalse(NotNullConstraintViolationException.class.isAssignableFrom(DataIntegrityViolationException.class));
    }

    /**
     * 用例③：未知列必须落到 {@link UnknownColumnException}，不能停在语法错误。
     * <p>同上一条的理由：{@code column "x" does not exist} 匹配不上默认实现找的
     * {@code "unknown column"}。区分开的收益是归因——42703 几乎总是实体字段名与列名映射写错，
     * 而不是 SQL 拼错，前者要改注解、后者要改语句。</p>
     */
    @Test
    void undefinedColumnMapsToUnknownColumnNotGrammar() {
        var ex = handler.translate(pg("column \"ghost_col\" does not exist", PgsqlSqlStates.UNDEFINED_COLUMN),
                "SELECT ghost_col FROM t_role", null);
        assertInstanceOf(UnknownColumnException.class, ex);
    }

    /** 用例④：语句与对象问题归语法类，调用方应当快速失败 */
    @Test
    void grammarAndMissingObjectMapToBadSqlGrammar() {
        assertInstanceOf(BadSqlGrammarException.class,
                handler.translate(pg("relation \"t_ghost\" does not exist", PgsqlSqlStates.UNDEFINED_TABLE),
                        "SELECT * FROM t_ghost", null));
        assertInstanceOf(BadSqlGrammarException.class,
                handler.translate(pg("syntax error at or near \"SELCT\"", PgsqlSqlStates.SYNTAX_ERROR),
                        "SELCT 1", null));
        assertInstanceOf(BadSqlGrammarException.class,
                handler.translate(pg("relation already exists", PgsqlSqlStates.DUPLICATE_TABLE), "CREATE TABLE", null));
        assertInstanceOf(BadSqlGrammarException.class,
                handler.translate(pg("already exists", PgsqlSqlStates.DUPLICATE_OBJECT), "CREATE INDEX", null));
    }

    /**
     * 用例⑤：取值族（过长、越界）都是"业务改数据"，重试无意义。
     * <p>{@code 22003} 单独列出来测：MySQL 侧把越界当成 warning 静默收窄成边界值（写入 200 存成 127），
     * PG 直接报 22003。同一份实体在两种方言上的行为差异只能靠异常翻译暴露出来。</p>
     */
    @Test
    void valueRangeViolationsMapToTruncation() {
        var tooLong = handler.translate(pg("value too long for type character varying(64)",
                PgsqlSqlStates.STRING_DATA_RIGHT_TRUNCATION), "INSERT", null);
        assertInstanceOf(DataTruncationException.class, tooLong);
        assertFalse(tooLong.isRetryable());

        var outOfRange = handler.translate(pg("integer out of range", PgsqlSqlStates.NUMERIC_VALUE_OUT_OF_RANGE),
                "INSERT", null);
        assertInstanceOf(DataTruncationException.class, outOfRange);
    }

    /**
     * 用例⑥：事务竞争与锁等待都是可重试的瞬时故障。
     * <p>40001（死锁）与 40P01（串行化冲突）折到同一类：两者的处置动作一样——整个事务回滚后重放。
     * 55P03（lock_not_available，配合 {@code lock_timeout}）落在"拿不到锁"，
     * 默认实现里没有这一码，不显式列出就只能得到无法分类。</p>
     */
    @Test
    void contentionIsRetryable() {
        var deadlock = handler.translate(pg("deadlock detected", PgsqlSqlStates.DEADLOCK_DETECTED), "UPDATE", null);
        assertInstanceOf(DeadlockLoserDataAccessException.class, deadlock);
        assertTrue(deadlock.isRetryable());

        var serial = handler.translate(pg("could not serialize access due to read/write dependence",
                PgsqlSqlStates.SERIALIZATION_FAILURE), "UPDATE", null);
        assertInstanceOf(DeadlockLoserDataAccessException.class, serial);
        assertTrue(serial.isRetryable());

        var lock = handler.translate(pg("canceling statement due to lock timeout",
                PgsqlSqlStates.LOCK_NOT_AVAILABLE), "SELECT FOR UPDATE", null);
        assertInstanceOf(CannotAcquireLockException.class, lock);
        assertTrue(lock.isRetryable());
    }

    /**
     * 用例⑦：主动取消（statement_timeout）是查询超时，与"事务竞争"要分开。
     * <p>调用方对超时的处置通常是调大超时或改写语句，而不是无脑重放，所以不能折进死锁那一类。</p>
     */
    @Test
    void queryCancelMapsToTimeout() {
        var ex = handler.translate(pg("canceling statement due to statement timeout", PgsqlSqlStates.QUERY_CANCELED),
                "SELECT", null);
        assertInstanceOf(QueryTimeoutException.class, ex);
        assertTrue(ex.isRetryable());
    }

    /**
     * 用例⑧：连接层故障分两档——"池满了"与"重连就能好"。
     * <p>默认实现把 57 类一律判成查询超时，这是判错：57P01/57P02（管理员关闭、崩溃关闭）语句本身没问题，
     * 换一个连接重放就会成功。这里断言它们落到 {@link RecoverableDataAccessException}，
     * 而 53300（连接数打满）落到 {@link CannotGetJdbcConnectionException}——它不是语句的问题，
     * 是池的问题，重试要带退避而不是立刻重来。</p>
     */
    @Test
    void connectionFailuresSplitByRecoveryStrategy() {
        var adminShutdown = handler.translate(pg("terminating connection due to administrator command",
                PgsqlSqlStates.ADMIN_SHUTDOWN), "SELECT", null);
        assertInstanceOf(RecoverableDataAccessException.class, adminShutdown);
        assertTrue(adminShutdown.isRetryable());

        assertInstanceOf(RecoverableDataAccessException.class,
                handler.translate(pg("database system is shutting down", PgsqlSqlStates.CRASH_SHUTDOWN),
                        "SELECT", null));
        assertInstanceOf(RecoverableDataAccessException.class,
                handler.translate(pg("could not receive data", PgsqlSqlStates.CONNECTION_FAILURE), "SELECT", null));

        var tooMany = handler.translate(pg("too many connections for role", PgsqlSqlStates.TOO_MANY_CONNECTIONS),
                "SELECT", null);
        assertInstanceOf(CannotGetJdbcConnectionException.class, tooMany);
        assertInstanceOf(CannotGetJdbcConnectionException.class,
                handler.translate(pg("the database system is starting up", PgsqlSqlStates.CANNOT_CONNECT_NOW),
                        "SELECT", null));
    }

    /**
     * 用例⑨：未收录的 SQLState 与 state 为空的异常都必须落到统一异常体系里。
     * <p>断言的对象是"调用方永远只需 catch DataAccessException"这条契约：
     * 翻译器本身不许抛"翻译失败"，也不许原样漏出 SQLException。
     * 取样 22012（除零，本类未列出，走默认兜底）与 state 为 null 的合成异常。</p>
     */
    @Test
    void unknownStatesDelegateAndNeverThrow() {
        var division = handler.translate(pg("division by zero", "22012"), "SELECT 1/0", null);
        assertInstanceOf(DataAccessException.class, division);

        var stateless = handler.translate(new SQLException("boom", null, 0), "SELECT 1", null);
        assertInstanceOf(DataAccessException.class, stateless);

        var messageless = handler.translate(new SQLException((String) null, PgsqlSqlStates.UNIQUE_VIOLATION),
                "INSERT", null);
        assertInstanceOf(DuplicateKeyException.class, messageless);
    }

    /**
     * 用例⑩：翻译结果必须携带原始上下文。
     * <p>断言 sql 与 params 原样随异常出来：线上只有一段堆栈时，
     * 没有这两个字段就复现不出是哪条数据撞了键，而这正是异常翻译层唯一的价值。</p>
     */
    @Test
    void carriesSqlAndParams() {
        Object[] params = {"dup", 7};
        var ex = handler.translate(pg("duplicate key", PgsqlSqlStates.UNIQUE_VIOLATION),
                "INSERT INTO t_role (name, age) VALUES (?, ?)", params);
        assertEquals("INSERT INTO t_role (name, age) VALUES (?, ?)", ex.getSql());
        assertEquals(2, ex.getParams().length);
        assertEquals("dup", ex.getParams()[0]);
        assertEquals(PgsqlSqlStates.UNIQUE_VIOLATION, ex.getSqlState());
        // PG 的数字码恒为 0：断言它被如实携带，而不是翻译层自己造了一个
        assertEquals(0, ex.getErrorCode());
    }

    /** 用例⑪：方言名参与日志与错误信息，多方言排查时不能认错对象 */
    @Test
    void dialectNameIsStable() {
        assertEquals("pgsql", handler.dialectName());
    }
}
