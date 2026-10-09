package com.wxdgaming.batis.sql.mysql;

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
import com.wxdgaming.batis.exception.UnknownColumnException;
import com.wxdgaming.batis.sql.DefaultSqlExceptionHandler;
import com.wxdgaming.batis.sql.SqlExceptionHandler;

import java.sql.SQLException;

/**
 * MySQL 异常翻译器
 * <p>按 {@link MysqlSqlCodes} 数字错误码精确映射到 DataAccessException 子类，
 * 未命中时委托 {@link DefaultSqlExceptionHandler} 按 SQLState/关键字兜底。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class MysqlSqlExceptionHandler implements SqlExceptionHandler {

    @Override
    public DataAccessException translate(SQLException ex, String sql, Object[] params) {
        String msg = ex.getMessage() == null ? "" : ex.getMessage();
        int code = ex.getErrorCode();
        switch (code) {
            case MysqlSqlCodes.ER_DUP_ENTRY:
            case MysqlSqlCodes.ER_DUP_AUTO_INCREMENT:
                return new DuplicateKeyException(msg, sql, params, code, ex.getSQLState(), ex);
            case MysqlSqlCodes.ER_ROW_IS_REFERENCED_2:
            case MysqlSqlCodes.ER_NO_REFERENCED_ROW_2:
                return new ForeignKeyConstraintViolationException(msg, sql, params, code, ex.getSQLState(), ex);
            case MysqlSqlCodes.ER_BAD_NULL_ERROR:
            case MysqlSqlCodes.ER_NO_DEFAULT:
                return new NotNullConstraintViolationException(msg, sql, params, code, ex.getSQLState(), ex);
            case MysqlSqlCodes.ER_DATA_TOO_LONG:
            case MysqlSqlCodes.ER_WARN_DATA_TRUNCATED:
            case MysqlSqlCodes.ER_WARN_DATA_OUT_OF_RANGE:
                return new DataTruncationException(msg, sql, params, code, ex.getSQLState(), ex);
            case MysqlSqlCodes.ER_LOCK_DEADLOCK:
                return new DeadlockLoserDataAccessException(msg, sql, params, code, ex.getSQLState(), ex);
            case MysqlSqlCodes.ER_LOCK_WAIT_TIMEOUT:
                return new CannotAcquireLockException(msg, sql, params, code, ex.getSQLState(), ex);
            case MysqlSqlCodes.ER_QUERY_TIMEOUT:
                return new QueryTimeoutException(msg, sql, params, code, ex.getSQLState(), ex);
            case MysqlSqlCodes.ER_CON_COUNT:
                return new CannotGetJdbcConnectionException(msg, sql, params, code, ex.getSQLState(), ex);
            case MysqlSqlCodes.ER_PARSE_ERROR:
            case MysqlSqlCodes.ER_NO_SUCH_TABLE:
            case MysqlSqlCodes.ER_NO_TABLES_USED:
            case MysqlSqlCodes.ER_NON_UNIQ_ERROR:
            case MysqlSqlCodes.ER_WRONG_FUNC_TO_ARG:
                return new BadSqlGrammarException(msg, sql, params, code, ex.getSQLState(), ex);
            case MysqlSqlCodes.ER_BAD_FIELD_ERROR:
                return new UnknownColumnException(msg, sql, params, code, ex.getSQLState(), ex);
            default:
                // 数字码未命中（如驱动侧异常 errorCode=0），按 SQLState/关键字兜底
                return DefaultSqlExceptionHandler.INSTANCE.translate(ex, sql, params);
        }
    }

    @Override
    public String dialectName() {
        return "mysql";
    }

}
