package com.wxdgaming.batis.exception;

/**
 * 瞬时故障异常基类
 * <p>连接不可用、死锁、锁等待超时、查询超时等场景，重试可能成功。</p>
 * <p>
 * 本类存在的唯一理由是把 {@link #isRetryable()} 翻成 true：它不新增任何字段，
 * 只是给异常树打一个"换个时机重来有意义"的标记。因此继承哪一支不是分类美学问题，
 * 而是行为契约——批量写入的降级判断（BeanRunner#rethrowWithoutFallback）见到 true 就直接上抛，
 * 不做逐条补写，避免把一次数据库抖动放大成上万条语句。
 * </p>
 * <p>具体子类：{@link CannotGetJdbcConnectionException}、{@link RecoverableDataAccessException}、
 * {@link DeadlockLoserDataAccessException}、{@link CannotAcquireLockException}、
 * {@link QueryTimeoutException}。全部来自 SQLState 08/40 类别与对应方言错误码的翻译结果。</p>
 * <p>"瞬时"是就失败原因而言：连接数超限（1040）根子上可能是池配置过小，但仍按瞬时处理，
 * 因为峰值过后重试就会成功；重试仍需自行加上限与退避，框架不提供无限重试。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class TransientDataAccessException extends DataAccessException {

    /** 无 SQL 上下文的瞬时故障，通常由事务模板等框架组件直接构造 */
    public TransientDataAccessException(String message) {
        super(message);
    }

    /** 包装底层异常（不含 SQL/参数），用于连接准备阶段失败 */
    public TransientDataAccessException(String message, Throwable cause) {
        super(message, cause);
    }

    /** 方言翻译器使用的完整形态，字段含义见 {@link DataAccessException} */
    public TransientDataAccessException(String message, String sql, Object[] params,
                                        int errorCode, String sqlState, Throwable cause) {
        super(message, sql, params, errorCode, sqlState, cause);
    }

    /** 恒为 true：这是本类与 {@link NonTransientDataAccessException} 的全部区别 */
    @Override
    public boolean isRetryable() {
        return true;
    }

}
