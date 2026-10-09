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
 * <p>两级判定的取舍：SQLState 是标准里唯一跨数据库可比的信号，优先按类别（前两位）分流；
 * 但不少驱动给的 state 粗糙甚至为空，于是再按消息关键字补救。
 * 关键字表是按"常见英文驱动消息"写的，换一套驱动或本地化消息就可能命中不到，
 * 那种情况下正确的做法是在方言模块按错误码精确映射，而不是往这里加方言专有字面量——
 * 本类是 sql 层唯一的兜底，一旦掺进某个数据库的私有错误文案，
 * 其它方言就会拿到被别的方言改写过的异常。</p>
 * <p>无状态设计（见 {@link #INSTANCE}）：翻译不依赖连接、配置或累积状态，
 * 因此可以安全地作为全局单例被所有线程与所有模板共用。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class DefaultSqlExceptionHandler implements SqlExceptionHandler {

    /** 无状态单例（省掉每次异常路径上的对象分配，也让方言委托时有唯一入口） */
    public static final DefaultSqlExceptionHandler INSTANCE = new DefaultSqlExceptionHandler();

    /**
     * 先按 SQLState 类别分流，未命中再按消息关键字补救，最后归入无法分类。
     * <p>判定前把消息转成小写（固定 Locale.ROOT，避免土耳其语之类的区域设置把 I 折成
     * 另一个字符导致关键字漏配）。返回的异常始终保留原始 SQLException 与 SQL/参数，
     * 保证上层拿到的是可诊断的信息，而不是一句重新编排的文案。</p>
     * <p>注意"关键字兜底"排在 SQLState 之后：同一消息在不同类别下含义不同
     * （比如 23xxx 的完整性失败与 42xxx 的语法失败都可能提到列名），
     * 顺序换了会改变归类结果。</p>
     *
     * @param ex     原始 SQL 异常
     * @param sql    触发异常的 SQL，可为 null
     * @param params 绑定参数，可为 null
     * @return 翻译后的统一异常，永不为 null
     */
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

    /**
     * 完整性约束类（SQLState 23/45 系列）的细分。
     * <p>这一族的消息措辞在各驱动间相对统一，用关键字就能把唯一键、外键、非空、截断分开，
     * 分开才有"改数据"还是"改语句"的判别依据：唯一键冲突通常是业务重复提交，
     * 外键违反则多半是写入顺序或引用数据有问题。</p>
     * <p>声明为 protected 是让方言子类复用这套细分，只在外层替换自己的错误码判定。</p>
     *
     * @param msg 已转小写的异常消息
     * @return 对应细类的统一异常；判不出具体约束时返回完整性违反的通用类型
     */
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

    /**
     * 语法/对象缺失类（SQLState 42 系列）的细分。
     * <p>把"列不存在"从泛化的语法错误里摘出来单独成类：它往往不是 SQL 写错，
     * 而是实体与库端结构漂移（改了字段没同步表结构），提示方向完全不同。
     * 关键字按多方言通用措辞列示，识别不到的情形退回语法错误。</p>
     *
     * @param msg 已转小写的异常消息
     * @return 未知列异常或语法异常
     */
    protected DataAccessException grammar(SQLException ex, String sql, Object[] params,
                                          int code, String state, String msg) {
        if (msg.contains("unknown column") || msg.contains("invalid column name") || msg.contains("undefined column")) {
            return new UnknownColumnException(describe(ex), sql, params, code, state, ex);
        }
        return new BadSqlGrammarException(describe(ex), sql, params, code, state, ex);
    }

    /**
     * 组装异常描述：原始消息 + SQLState + 错误码。
     * <p>把这两个数字固定带在消息里，是因为排障时最常被问的就是"到底是哪个码"；
     * 方言实现若自己拼消息，也应保持同样口径，否则日志检索规则会在换方言后失效。</p>
     *
     * @param ex 原始 SQL 异常
     * @return 供统一异常使用的描述文本
     */
    protected String describe(SQLException ex) {
        return "SQL 执行失败: " + ex.getMessage() + " [SQLState=" + ex.getSQLState()
                + ", errorCode=" + ex.getErrorCode() + "]";
    }

}
