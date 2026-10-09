package com.wxdgaming.batis.exception;

/**
 * 无法获取数据库连接
 * <p>连接池耗尽、数据库不可达、连接数超限等。</p>
 * <p>
 * 什么时候抛出（以翻译器实际映射为准）：
 * <ul>
 *   <li>MySQL 错误码 1040（ER_CON_COUNT，Too many connections）；</li>
 *   <li>SQLState 以 08 开头（连接异常类）且不等于 08S01 —— 后者是连接中断，
 *       归 {@link RecoverableDataAccessException}；</li>
 *   <li>消息含 "too many connections" 的关键字兜底；</li>
 *   <li>事务模板 doBegin 向池借连接失败时直接构造（此时只有 message + cause，没有 SQL）。</li>
 * </ul>
 * </p>
 * <p>
 * 上层怎么应对：属瞬时故障（isRetryable() 为 true），短暂退避后重借连接通常能成功；
 * 但如果持续出现，说明是容量或泄漏问题——池最大连接数过小、QueryRunner 被长期持有占死连接、
 * 或服务端 max_connections 不足，重试只会让排队更长，应转向告警而不是无限重试。
 * </p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class CannotGetJdbcConnectionException extends TransientDataAccessException {

    /** 连接准备阶段失败（事务开启路径常见），不带 SQL 上下文 */
    public CannotGetJdbcConnectionException(String message, Throwable cause) {
        super(message, cause);
    }

    /** SQL 执行阶段失败，由方言翻译器构造，携带原始 SQL/参数/错误码/SQLState */
    public CannotGetJdbcConnectionException(String message, String sql, Object[] params,
                                            int errorCode, String sqlState, Throwable cause) {
        super(message, sql, params, errorCode, sqlState, cause);
    }

}
