package com.wxdgaming.batis.exception;

/**
 * 未分类 SQL 异常（兜底）
 * <p>方言错误码表与通用规则都无法识别时抛出，保留原始 SQLException 为 cause。</p>
 * <p>
 * 这是翻译链路的最后一站：MysqlSqlExceptionHandler 数字码未命中时委托
 * DefaultSqlExceptionHandler，后者按 SQLState 类别和消息关键字再试一轮，
 * 仍然没有任何规则命中时才落在这里。
 * </p>
 * <p>
 * 为什么兜底选择"不可重试"：继承 {@link NonTransientDataAccessException} 意味着
 * isRetryable() 为 false——分类未知的故障宁可快速失败、把原始 SQLException 完整交给上层排查，
 * 也不要被重试逻辑当成瞬时故障反复压库。同理，上层不要写 {@code catch (UncategorizedDataAccessException)}
 * 来兜住"所有其他错误"，那样新方言或新错误码出现时会静默走错分支，应该 catch
 * {@link DataAccessException} 再看 getSqlState() 与 getErrorCode()。
 * </p>
 * <p>出现它通常意味着错误码表该补了：确认场景后在对应方言的 MysqlSqlCodes 与翻译器里加精确映射，
 * 让下一次同类失败能落到有意义的子类上。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class UncategorizedDataAccessException extends NonTransientDataAccessException {

    /** 包装场景：只有描述与原始异常 */
    public UncategorizedDataAccessException(String message, Throwable cause) {
        super(message, cause);
    }

    /** 方言翻译器兜底路径，携带原始 SQL/参数/错误码/SQLState */
    public UncategorizedDataAccessException(String message, String sql, Object[] params,
                                            int errorCode, String sqlState, Throwable cause) {
        super(message, sql, params, errorCode, sqlState, cause);
    }

}
