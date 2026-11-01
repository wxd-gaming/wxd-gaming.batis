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

    /**
     * M12 回归：超时类 SQLState 必须翻译成可重试的 QueryTimeoutException。
     * <p>{@code HYT} 是三位 ODBC 风格类别，按前两位截取只会得到 {@code HY}，
     * 所以这条特意用 {@code HYT00}/{@code HYT01} 钉住"M12 之前必然落进未知错误"的位置；
     * {@code 57} 类是标准语句超时。判成不可重试会被批量降级放大成 N 次压库。</p>
     */
    @Test
    void state57AndHytAreTimeouts() {
        QueryTimeoutException hyt00 = assertInstanceOf(QueryTimeoutException.class,
                handler.translate(ex("HYT00", 0, "Timeout expired"), SQL, PARAMS));
        assertTrue(hyt00.isRetryable(), "超时是可重试的瞬时故障");

        assertInstanceOf(QueryTimeoutException.class,
                handler.translate(ex("HYT01", 0, "Connection timeout expired"), SQL, PARAMS));
        assertInstanceOf(QueryTimeoutException.class,
                handler.translate(ex("57014", 0, "canceling statement due to statement timeout"), SQL, PARAMS));
    }

    /**
     * SQLState 为空串/单字符时不得抛 StringIndexOutOfBounds：截类别码前必须判长度，
     * 否则驱动给出不规范状态码时，翻译层自己成为故障点。
     */
    @Test
    void shortOrEmptyStateDoesNotCrash() {
        assertInstanceOf(UncategorizedDataAccessException.class,
                handler.translate(ex("", 0, "something unusual happened"), SQL, PARAMS));
        assertInstanceOf(UncategorizedDataAccessException.class,
                handler.translate(ex("0", 0, "something unusual happened"), SQL, PARAMS));
        // 单字符状态码仍要走关键字兜底
        assertInstanceOf(QueryTimeoutException.class,
                handler.translate(ex("H", 0, "query timeout of 5000ms exceeded"), SQL, PARAMS));
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
