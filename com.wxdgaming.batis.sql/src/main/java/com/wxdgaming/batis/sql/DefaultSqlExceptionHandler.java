package com.wxdgaming.batis.sql;

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
import com.wxdgaming.batis.exception.TypeMismatchDataAccessException;
import com.wxdgaming.batis.exception.UncategorizedDataAccessException;
import com.wxdgaming.batis.exception.UnknownColumnException;

import java.sql.SQLException;
import java.util.Locale;

/**
 * 默认 SQL 异常翻译器（方言无关兜底）
 * <p>不依赖任何驱动类，仅按 SQLState 类别 + 消息关键字识别，
 * 保证未注册方言实现时也有合理的异常类型；方言实现（如 MySQL 数字错误码）
 * 未命中精确映射时可委托本类继续兜底。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class DefaultSqlExceptionHandler implements SqlExceptionHandler {

    /** 无状态单例 */
    public static final DefaultSqlExceptionHandler INSTANCE = new DefaultSqlExceptionHandler();

    @Override
    public DataAccessException translate(SQLException ex, String sql, Object[] params) {
        String state = ex.getSQLState();
        int code = ex.getErrorCode();
        String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.ROOT);

        // SQLState 类别优先
        if (state != null) {
            String clazz = state.length() >= 2 ? state.substring(0, 2) : state;
            switch (clazz) {
                case "08": // 连接异常类
                    if ("08S01".equals(state) || msg.contains("communications link failure")) {
                        return new RecoverableDataAccessException(describe(ex), sql, params, code, state, ex);
                    }
                    return new CannotGetJdbcConnectionException(describe(ex), sql, params, code, state, ex);
                case "23": // 完整性约束违反
                case "45":
                    return integrity(ex, sql, params, code, state, msg);
                case "22": // 数据异常（类型/截断）
                    if (msg.contains("truncat") || msg.contains("too long") || msg.contains("out of range")) {
                        return new DataTruncationException(describe(ex), sql, params, code, state, ex);
                    }
                    return new TypeMismatchDataAccessException(describe(ex), sql, params, code, state, ex);
                case "40": // 事务回滚（死锁/序列化失败）
                    return new DeadlockLoserDataAccessException(describe(ex), sql, params, code, state, ex);
                case "42": // 语法/对象缺失
                    return grammar(ex, sql, params, code, state, msg);
                default:
                    break;
            }
        }

        // 关键字兜底
        if (msg.contains("duplicate entry") || msg.contains("duplicate key") || msg.contains("unique constraint")) {
            return new DuplicateKeyException(describe(ex), sql, params, code, state, ex);
        }
        if (msg.contains("foreign key")) {
            return new ForeignKeyConstraintViolationException(describe(ex), sql, params, code, state, ex);
        }
        if (msg.contains("cannot be null") || msg.contains("not null")) {
            return new NotNullConstraintViolationException(describe(ex), sql, params, code, state, ex);
        }
        if (msg.contains("deadlock found")) {
            return new DeadlockLoserDataAccessException(describe(ex), sql, params, code, state, ex);
        }
        if (msg.contains("lock wait timeout")) {
            return new CannotAcquireLockException(describe(ex), sql, params, code, state, ex);
        }
        if (msg.contains("communications link failure") || msg.contains("connection closed")) {
            return new RecoverableDataAccessException(describe(ex), sql, params, code, state, ex);
        }
        if (msg.contains("too many connections")) {
            return new CannotGetJdbcConnectionException(describe(ex), sql, params, code, state, ex);
        }
        if (msg.contains("query timeout") || msg.contains("max_execution_time")) {
            return new QueryTimeoutException(describe(ex), sql, params, code, state, ex);
        }
        if (msg.contains("unknown column")) {
            return new UnknownColumnException(describe(ex), sql, params, code, state, ex);
        }
        if (msg.contains("doesn't exist") || msg.contains("syntax error") || msg.contains("parse error")) {
            return new BadSqlGrammarException(describe(ex), sql, params, code, state, ex);
        }
        return new UncategorizedDataAccessException(describe(ex), sql, params, code, state, ex);
    }

    protected DataAccessException integrity(SQLException ex, String sql, Object[] params,
                                            int code, String state, String msg) {
        if (msg.contains("duplicate") || msg.contains("unique")) {
            return new DuplicateKeyException(describe(ex), sql, params, code, state, ex);
        }
        if (msg.contains("foreign key")) {
            return new ForeignKeyConstraintViolationException(describe(ex), sql, params, code, state, ex);
        }
        if (msg.contains("cannot be null") || msg.contains("not null")) {
            return new NotNullConstraintViolationException(describe(ex), sql, params, code, state, ex);
        }
        if (msg.contains("truncat") || msg.contains("too long")) {
            return new DataTruncationException(describe(ex), sql, params, code, state, ex);
        }
        return new DataIntegrityViolationException(describe(ex), sql, params, code, state, ex);
    }

    protected DataAccessException grammar(SQLException ex, String sql, Object[] params,
                                          int code, String state, String msg) {
        if (msg.contains("unknown column") || msg.contains("invalid column name") || msg.contains("undefined column")) {
            return new UnknownColumnException(describe(ex), sql, params, code, state, ex);
        }
        return new BadSqlGrammarException(describe(ex), sql, params, code, state, ex);
    }

    protected String describe(SQLException ex) {
        return "SQL 执行失败: " + ex.getMessage() + " [SQLState=" + ex.getSQLState()
                + ", errorCode=" + ex.getErrorCode() + "]";
    }

}
