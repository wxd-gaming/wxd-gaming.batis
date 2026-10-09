package com.wxdgaming.batis.exception;

/**
 * 非瞬时故障异常基类
 * <p>SQL 语法错误、约束冲突、数据截断等场景，重试无意义，需要修正数据或语句。</p>
 * <p>
 * 这一支是异常树的"快速失败"侧：继承它即等于对外声明 isRetryable() 为 false，
 * 上层正确做法是把错误原样暴露（回滚事务、给调用方返回参数/SQL 错误），
 * 而不是重试。批量写入遇这一支且不在事务内时，才会降级为逐条执行以定位脏数据。
 * </p>
 * <p>
 * 另一个关键约定是兜底方向：方言错误码表和 SQLState/关键字规则都没命中时，
 * DefaultSqlExceptionHandler 抛出 {@link UncategorizedDataAccessException}，它继承本类 ——
 * 也就是"分类不明的异常默认按不可重试处理"。宁可让问题暴露，也不要盲目重试把故障放大。
 * </p>
 * <p>具体子类：{@link BadSqlGrammarException}（及其子 {@link UnknownColumnException}）、
 * {@link DataIntegrityViolationException} 家族、{@link TypeMismatchDataAccessException}、
 * {@link TooManyResultsException}、{@link BatchUpdateFailedException}、
 * {@link UncategorizedDataAccessException}。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class NonTransientDataAccessException extends DataAccessException {

    /** 纯描述型：框架本地校验失败，无 SQL/驱动上下文 */
    public NonTransientDataAccessException(String message) {
        super(message);
    }

    /** 包装底层异常（不含 SQL/参数） */
    public NonTransientDataAccessException(String message, Throwable cause) {
        super(message, cause);
    }

    /** 方言翻译器使用的完整形态，字段含义见 {@link DataAccessException} */
    public NonTransientDataAccessException(String message, String sql, Object[] params,
                                           int errorCode, String sqlState, Throwable cause) {
        super(message, sql, params, errorCode, sqlState, cause);
    }

}
