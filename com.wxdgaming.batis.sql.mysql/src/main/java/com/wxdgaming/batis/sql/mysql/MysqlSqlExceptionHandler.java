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

    /**
     * 按数字错误码精确翻译。
     * <p>WHY 用数字码而非关键字：数字码由服务端给出、稳定且语义唯一，靠 message 关键字匹配既受语言/版本
     * 影响又易误判；调用方据返回的具体子类决定"可重试（死锁、锁超时）还是直接失败（重复键、语法错误）"。
     * 真实码验证见 SqlExceptionIT（1062/1054/1064/1406 走真库，1213 用合成 SQLException 验证映射）。</p>
     *
     * @param ex     原始 SQLException，取其 getErrorCode 分派
     * @param sql    触发异常的 SQL，随结果异常携带以便排查
     * @param params 绑定参数，随结果异常携带
     * @return 对应的 DataAccessException 子类；码表未命中时委托默认实现按 SQLState/关键字兜底，绝不抛"翻译失败"
     */
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
            // 非空约束：MySQL 报 1048（列不能为 null），MariaDB 对无默认值的 NOT NULL 列报 1364（无默认值）；
            // 两厂商语义相同，故同归 NotNull 违反，调用方无需感知这个方言差异（SqlExceptionIT 断言 1048 或 1364 皆可）
            case MysqlSqlCodes.ER_BAD_NULL_ERROR:
            case MysqlSqlCodes.ER_NO_DEFAULT:
                return new NotNullConstraintViolationException(msg, sql, params, code, ex.getSQLState(), ex);
            // 数据截断族：过长(1406)/被截断(1265)/越界(1264)，均需业务修正数据而非重试
            case MysqlSqlCodes.ER_DATA_TOO_LONG:
            case MysqlSqlCodes.ER_WARN_DATA_TRUNCATED:
            case MysqlSqlCodes.ER_WARN_DATA_OUT_OF_RANGE:
                return new DataTruncationException(msg, sql, params, code, ex.getSQLState(), ex);
            // 死锁是可重试的瞬时故障（事务竞争），与"数据本身有问题"的截断类区分开
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
