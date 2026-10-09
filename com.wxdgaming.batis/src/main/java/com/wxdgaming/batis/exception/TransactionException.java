package com.wxdgaming.batis.exception;

/**
 * 事务操作异常基类
 * <p>
 * 覆盖的是事务生命周期本身的失败（开启、提交、回滚、传播行为与状态检查），
 * 而不是事务里某条 SQL 的失败——后者由 {@link DataAccessException} 的另外两支按可重试性分类。
 * 区分开是有用的："提交失败"意味着业务语句可能已经执行成功，只是没落库，
 * 此时盲目重试整段逻辑会造成重复写，必须先查清数据实际状态。
 * </p>
 * <p>
 * 之所以仍继承 {@link DataAccessException} 而不是另起一支：绝大多数上层只需要"这次数据操作没成"
 * 这一个结论，用一个 catch 就能兜住；需要细分时再按事务/瞬时/非瞬时判断。
 * </p>
 * <p>
 * 本支异常多由事务模板（TransactionTemplate）与缓存执行器（CachedBeanRunner）主动构造，
 * 因此常见形态只有 message/cause，errorCode 为 0、sql 为 null：失败发生在连接层而非语句层，
 * 没有可归属的 SQL。具体子类：{@link IllegalTransactionStateException}、
 * {@link TransactionStartedException}。
 * </p>
 * <p>它不重写 {@link #isRetryable()}，因而始终为 false：事务边界上的失败一律要求人工判定数据状态，
 * 框架不鼓励"再跑一遍整段逻辑"。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class TransactionException extends DataAccessException {

    /** 状态/前置条件不满足（如传播行为不允许、只读事务里写），一般没有 cause */
    public TransactionException(String message) {
        super(message);
    }

    /** 包装连接层抛出的底层异常 */
    public TransactionException(String message, Throwable cause) {
        super(message, cause);
    }

    /** 带 SQL 上下文的完整形态，保留给"事务边界上的语句也失败"的场景 */
    public TransactionException(String message, String sql, Object[] params,
                                int errorCode, String sqlState, Throwable cause) {
        super(message, sql, params, errorCode, sqlState, cause);
    }

}
