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
import com.wxdgaming.batis.sql.DefaultSqlExceptionHandler;
import com.wxdgaming.batis.sql.SqlExceptionHandler;

import java.sql.SQLException;

/**
 * PostgreSQL 异常翻译器
 * <p>按 {@link PgsqlSqlStates} 的 SQLState 精确映射到 DataAccessException 子类，
 * 未命中时委托 {@link DefaultSqlExceptionHandler} 按 state 类别 + 关键字兜底。</p>
 * <p>WHY 不照抄 MySQL 的 {@code switch (getErrorCode())}：PG 驱动给出的 errorCode 恒为 0，
 * 数字码那条路在这里永远走不通；SQLState 才是 PG 唯一稳定且语义唯一的信号。</p>
 * <p>只列默认兜底会<em>判错或判粗</em>的码，而不是重复一遍默认逻辑。默认类已知的问题（本类逐条修掉）：
 * 它的完整性细分靠消息关键字，而 PG 的消息是 {@code violates not-null constraint}（带连字符），
 * 匹配不上 {@code "not null"}，会被归成通用的完整性违反而不是 NotNull；
 * {@code column "x" does not exist} 同理匹配不上 {@code "unknown column"}，被当成语法错误而不是未知列；
 * 它把 57 类一律判成查询超时，而 57P01/P02（管理员/崩溃关闭）实际是"重连即可"的可恢复故障；
 * 53300（连接数打满）与 55P03（锁等待）落在默认分支只会得到无法分类。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-14
 **/
public class PgsqlSqlExceptionHandler implements SqlExceptionHandler {

    /**
     * 按 SQLState 精确翻译。
     * <p>调用方据返回的具体子类决定"可重试（死锁、锁等待、连接中断）还是直接失败（唯一键、非空、超长）"，
     * 分错一类的代价是让重试补偿去反复压一个必然失败的语句。</p>
     *
     * @param ex     原始 SQLException，取其 {@code getSQLState()} 分派
     * @param sql    触发异常的 SQL，随结果异常携带以便排查
     * @param params 绑定参数，随结果异常携带
     * @return 对应的 DataAccessException 子类；state 为空或表未命中时委托默认实现兜底，绝不抛"翻译失败"
     */
    @Override
    public DataAccessException translate(SQLException ex, String sql, Object[] params) {
        String msg = ex.getMessage() == null ? "" : ex.getMessage();
        int code = ex.getErrorCode();
        String state = ex.getSQLState();
        if (state != null) {
            switch (state) {
                case PgsqlSqlStates.UNIQUE_VIOLATION:
                    return new DuplicateKeyException(msg, sql, params, code, state, ex);
                case PgsqlSqlStates.NOT_NULL_VIOLATION:
                    return new NotNullConstraintViolationException(msg, sql, params, code, state, ex);
                case PgsqlSqlStates.FOREIGN_KEY_VIOLATION:
                    return new ForeignKeyConstraintViolationException(msg, sql, params, code, state, ex);
                // CHECK 违反既不是唯一键也不是外键，也没有更细的子类，停在完整性违反这一档
                case PgsqlSqlStates.CHECK_VIOLATION:
                    return new DataIntegrityViolationException(msg, sql, params, code, state, ex);
                // 数据取值族：过长与越界都要业务改数据，重试无意义
                case PgsqlSqlStates.STRING_DATA_RIGHT_TRUNCATION:
                case PgsqlSqlStates.NUMERIC_VALUE_OUT_OF_RANGE:
                    return new DataTruncationException(msg, sql, params, code, state, ex);
                // 死锁与串行化冲突都是可重试的瞬时故障（事务竞争）
                case PgsqlSqlStates.DEADLOCK_DETECTED:
                case PgsqlSqlStates.SERIALIZATION_FAILURE:
                    return new DeadlockLoserDataAccessException(msg, sql, params, code, state, ex);
                case PgsqlSqlStates.LOCK_NOT_AVAILABLE:
                    return new CannotAcquireLockException(msg, sql, params, code, state, ex);
                case PgsqlSqlStates.QUERY_CANCELED:
                    return new QueryTimeoutException(msg, sql, params, code, state, ex);
                case PgsqlSqlStates.UNDEFINED_COLUMN:
                    return new UnknownColumnException(msg, sql, params, code, state, ex);
                case PgsqlSqlStates.UNDEFINED_TABLE:
                case PgsqlSqlStates.SYNTAX_ERROR:
                case PgsqlSqlStates.DUPLICATE_TABLE:
                case PgsqlSqlStates.DUPLICATE_OBJECT:
                    return new BadSqlGrammarException(msg, sql, params, code, state, ex);
                // 连接数/配置上限：拿不到连接，属于池的问题而不是语句的问题
                case PgsqlSqlStates.TOO_MANY_CONNECTIONS:
                case PgsqlSqlStates.CONFIGURATION_LIMIT_EXCEEDED:
                case PgsqlSqlStates.CANNOT_CONNECT_NOW:
                case PgsqlSqlStates.CONNECTION_EXCEPTION:
                    return new CannotGetJdbcConnectionException(msg, sql, params, code, state, ex);
                // 管理员/崩溃关闭与系统错误："重连就能好"，必须可重试，
                // 否则批量降级路径会把一次抖动放大成整批失败
                case PgsqlSqlStates.ADMIN_SHUTDOWN:
                case PgsqlSqlStates.CRASH_SHUTDOWN:
                case PgsqlSqlStates.CONNECTION_FAILURE:
                case PgsqlSqlStates.SYSTEM_ERROR:
                    return new RecoverableDataAccessException(msg, sql, params, code, state, ex);
                default:
                    break;
            }
        }
        // state 为空或未收录（如 22 类各细分、08S01）：按类别前缀与关键字兜底
        return DefaultSqlExceptionHandler.INSTANCE.translate(ex, sql, params);
    }

    @Override
    public String dialectName() {
        return "pgsql";
    }

}
