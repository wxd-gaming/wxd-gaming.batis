package com.wxdgaming.batis.sql;

import com.wxdgaming.batis.exception.BadSqlGrammarException;
import com.wxdgaming.batis.exception.CannotAcquireLockException;
import com.wxdgaming.batis.exception.CannotGetJdbcConnectionException;
import com.wxdgaming.batis.exception.DataAccessException;
import com.wxdgaming.batis.exception.DataTruncationException;
import com.wxdgaming.batis.exception.DeadlockLoserDataAccessException;
import com.wxdgaming.batis.exception.DuplicateKeyException;
import com.wxdgaming.batis.exception.ForeignKeyConstraintViolationException;
import com.wxdgaming.batis.exception.NotNullConstraintViolationException;
import com.wxdgaming.batis.exception.QueryTimeoutException;
import com.wxdgaming.batis.exception.RecoverableDataAccessException;
import com.wxdgaming.batis.exception.TypeMismatchDataAccessException;
import com.wxdgaming.batis.exception.UncategorizedDataAccessException;
import com.wxdgaming.batis.exception.UnknownColumnException;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 默认异常翻译器单测：合成 SQLException，验证 SQLState 类别 + 关键字兜底的翻译结果
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
class DefaultSqlExceptionHandlerTest {

    private static final String SQL = "SELECT * FROM t_x WHERE id = ?";
    private static final Object[] PARAMS = new Object[]{1L};

    private final DefaultSqlExceptionHandler handler = DefaultSqlExceptionHandler.INSTANCE;

    private SQLException ex(String state, int code, String msg) {
        return new SQLException(msg, state, code);
    }

    // ===== SQLState 类别优先 =====

    @Test
    void state08ConnectionException() {
        assertInstanceOf(CannotGetJdbcConnectionException.class,
                handler.translate(ex("08001", 0, "Unable to connect"), SQL, PARAMS));
    }

    @Test
    void state08S01Recoverable() {
        assertInstanceOf(RecoverableDataAccessException.class,
                handler.translate(ex("08S01", 0, "link failure"), SQL, PARAMS));
    }

    @Test
    void state23IntegrityByMessage() {
        assertInstanceOf(DuplicateKeyException.class,
                handler.translate(ex("23000", 0, "Duplicate entry 'x' for key 'uk'"), SQL, PARAMS));
        assertInstanceOf(ForeignKeyConstraintViolationException.class,
                handler.translate(ex("23000", 0, "a foreign key constraint fails"), SQL, PARAMS));
        assertInstanceOf(NotNullConstraintViolationException.class,
                handler.translate(ex("23000", 0, "Column 'name' cannot be null"), SQL, PARAMS));
    }

    @Test
    void state22DataException() {
        assertInstanceOf(DataTruncationException.class,
                handler.translate(ex("22001", 0, "value too long for column"), SQL, PARAMS));
        assertInstanceOf(TypeMismatchDataAccessException.class,
                handler.translate(ex("22003", 0, "invalid datetime format"), SQL, PARAMS));
    }

    @Test
    void state40Deadlock() {
        assertInstanceOf(DeadlockLoserDataAccessException.class,
                handler.translate(ex("40001", 0, "serialization failure"), SQL, PARAMS));
    }

    @Test
    void state42Grammar() {
        assertInstanceOf(UnknownColumnException.class,
                handler.translate(ex("42S22", 1054, "Unknown column 'foo'"), SQL, PARAMS));
        assertInstanceOf(BadSqlGrammarException.class,
                handler.translate(ex("42000", 0, "syntax error near 'SELCT'"), SQL, PARAMS));
    }

    // ===== 关键字兜底（无 SQLState） =====

    @Test
    void keywordFallbackWithoutState() {
        assertInstanceOf(CannotAcquireLockException.class,
                handler.translate(ex(null, 0, "Lock wait timeout exceeded; try restarting transaction"), SQL, PARAMS));
        assertInstanceOf(DeadlockLoserDataAccessException.class,
                handler.translate(ex(null, 0, "Deadlock found when trying to get lock"), SQL, PARAMS));
        assertInstanceOf(QueryTimeoutException.class,
                handler.translate(ex(null, 0, "query timeout of 5000ms exceeded"), SQL, PARAMS));
        assertInstanceOf(CannotGetJdbcConnectionException.class,
                handler.translate(ex(null, 0, "Too many connections"), SQL, PARAMS));
    }

    @Test
    void unknownErrorFallsToUncategorized() {
        assertInstanceOf(UncategorizedDataAccessException.class,
                handler.translate(ex(null, 0, "something unusual happened"), SQL, PARAMS));
    }

    // ===== 上下文携带与重试语义 =====

    @Test
    void carriesSqlParamsAndCode() {
        DataAccessException dae = handler.translate(ex("23000", 1062, "Duplicate entry"), SQL, PARAMS);
        assertEquals(SQL, dae.getSql());
        assertSame(PARAMS, dae.getParams());
        assertEquals(1062, dae.getErrorCode());
        assertEquals("23000", dae.getSqlState());
    }

    @Test
    void retryableOnlyForTransient() {
        assertTrue(handler.translate(ex("40001", 1213, "Deadlock found"), SQL, PARAMS).isRetryable());
        assertTrue(handler.translate(ex("08S01", 0, " Communications link failure"), SQL, PARAMS).isRetryable());
        assertFalse(handler.translate(ex("23000", 1062, "Duplicate entry"), SQL, PARAMS).isRetryable());
        assertFalse(handler.translate(ex(null, 0, "something unusual"), SQL, PARAMS).isRetryable());
    }

}
