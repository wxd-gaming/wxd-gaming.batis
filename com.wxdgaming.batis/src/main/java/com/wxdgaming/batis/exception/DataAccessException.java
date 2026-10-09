package com.wxdgaming.batis.exception;

/**
 * 数据库访问异常基类
 * <p>所有由框架抛出的数据库相关异常都继承此类，携带触发异常的 SQL、绑定参数、
 * 数据库原生错误码与 SQLState，便于上层做重试/降级/告警决策。</p>
 * <p>
 * <b>为什么是 RuntimeException</b>：SQL 失败的形态（语法、约束、死锁、连接断开）在编译期无法预知，
 * 受检异常只会逼每个调用点写一遍无意义的 catch；统一成非受检异常后，业务代码只在真正需要补偿的
 * 边界（批量降级、事务回滚、对外接口错误码）捕获。
 * </p>
 * <p>
 * <b>分层依据是可重试性，不是错误细类</b>：往下只有两条主干，
 * {@link TransientDataAccessException}（瞬时故障，{@link #isRetryable()} 为 true）与
 * {@link NonTransientDataAccessException}（数据或语句本身有问题，重试必然再失败）。
 * 上层只要问一句 isRetryable() 就能决定"换个时机重来"还是"直接把错误抛给用户/回滚"，
 * 不需要维护一张不断膨胀的错误码白名单。
 * </p>
 * <p>
 * <b>实例从哪来</b>：正常路径下业务代码不会自己 new 子类，而是由 sql 模块的
 * SqlExceptionHandler SPI 把 {@code SQLException} 翻译出来
 * （DefaultSqlExceptionHandler 按 SQLState 类别与消息关键字兜底，
 * MysqlSqlExceptionHandler 按 MySQL 数字错误码精确映射）。
 * 少数例外是框架本地校验：查询 DSL 解析列名、one() 命中多条结果、事务状态检查，
 * 这些没有 SQLException 可翻译，由对应组件直接构造。
 * </p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class DataAccessException extends RuntimeException {

    /** 触发异常的 SQL 语句，可为 null */
    private final String sql;
    /** 绑定参数，可为 null */
    private final Object[] params;
    /** 数据库原生错误码，未知为 0 */
    private final int errorCode;
    /** SQLState，未知为 null */
    private final String sqlState;

    /**
     * 纯描述型构造：框架本地校验失败（没有 SQL、没有驱动上下文）时使用，
     * 例如查询 DSL 的列名解析、期望唯一却命中多条。
     */
    public DataAccessException(String message) {
        this(message, null, null, 0, null, null);
    }

    /**
     * 包装非 SQL 来源的底层异常（序列化失败、反射失败等），只有消息与 cause，诊断字段全为空。
     */
    public DataAccessException(String message, Throwable cause) {
        this(message, null, null, 0, null, cause);
    }

    /**
     * 完整形态：方言翻译器专用，五个诊断字段一次给齐。
     * <p>各字段允许缺省（sql/params 可为 null，errorCode 未知为 0，sqlState 未知为 null），
     * 因为驱动侧异常（如超时由 JDBC 而非 MySQL 抛出）常常只有消息没有错误码。
     * 这里对参数数组不做拷贝，构造后调用方不应再修改它。</p>
     *
     * @param message   人类可读的失败描述
     * @param sql       触发异常的 SQL，可为 null
     * @param params    绑定的占位符参数，可为 null
     * @param errorCode 数据库原生错误码，未知为 0
     * @param sqlState  SQLState，未知为 null
     * @param cause     原始 SQLException，保留它才能继续向下排查驱动细节
     */
    public DataAccessException(String message, String sql, Object[] params,
                               int errorCode, String sqlState, Throwable cause) {
        super(message, cause);
        this.sql = sql;
        this.params = params;
        this.errorCode = errorCode;
        this.sqlState = sqlState;
    }

    /** 触发异常的 SQL 语句；本地校验类异常返回 null。仅供日志/告警定位，不要拿它重新执行 */
    public String getSql() {
        return sql;
    }

    /**
     * 绑定的占位符参数；无参或本地校验类异常返回 null。
     * <p>返回的是内部数组引用（不拷贝，避免异常构造路径上的额外分配），
     * 且参数可能含业务敏感数据 —— 打进日志等于把数据复制一份出去。</p>
     */
    public Object[] getParams() {
        return params;
    }

    /** 数据库原生错误码（如 MySQL 的 1062），未知为 0；告警归类时比 message 字符串可靠 */
    public int getErrorCode() {
        return errorCode;
    }

    /** SQLState（如 MySQL 的 23000、08S01），未知为 null；跨方言归类用它，数字错误码只对方言内有效 */
    public String getSqlState() {
        return sqlState;
    }

    /**
     * 是否值得重试。
     * <p>基类返回 false，即"未知即不重试"——只有明确继承
     * {@link TransientDataAccessException} 的瞬时故障才为 true。
     * 批量降级逻辑（BeanRunner）正是靠这个判断决定"上抛"还是"逐条补写"，
     * 因此新增子类时务必选对主干，选错会让本该快速失败的错误被无限重试。</p>
     */
    public boolean isRetryable() {
        return false;
    }

}
