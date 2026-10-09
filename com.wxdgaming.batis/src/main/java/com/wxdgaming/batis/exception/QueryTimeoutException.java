package com.wxdgaming.batis.exception;

/**
 * 查询超时异常
 * <p>
 * 什么时候抛出（以翻译器实际映射为准）：
 * <ul>
 *   <li>MySQL 错误码 3024（ER_QUERY_TIMEOUT，MAX_EXECUTION_TIME 超时被服务端中断）；</li>
 *   <li>消息含 "query timeout" 或 "max_execution_time" 的关键字兜底。</li>
 * </ul>
 * 注意超时也可能由 JDBC 侧而非服务端触发：事务配置了语句级超时时，
 * QueryRunner#applyQueryTimeout 会对 Statement 设置 setQueryTimeout，触发后驱动抛出的
 * SQLException 的 errorCode 取决于驱动实现（常常为 0），此时精确映射不命中，
 * 依赖 DefaultSqlExceptionHandler 的关键字兜底——这也是本类仍留在瞬时支的原因。
 * </p>
 * <p>
 * 上层怎么应对：只读查询可以按 isRetryable() 重试（瞬时故障），但要先想清楚重试是否只是把
 * 同一条慢 SQL 再压给数据库一遍；更常见的正确做法是补索引、收窄条件或分页。
 * 事务内的语句超时说明这条 SQL 已经占住连接和行锁超过预算，重试点应当是"整个事务重来"，
 * 而不是单独重跑这一条。
 * </p>
 * <p>写操作超时不能假定失败：超时只表示"没在期限内拿到结果"，语句可能已经生效，
 * 直接重试会造成重复写，必须先校验数据状态。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class QueryTimeoutException extends TransientDataAccessException {

    /** 合成/非 SQL 场景，仅消息与 cause */
    public QueryTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }

    /** 方言翻译器构造，携带原始 SQL/参数/错误码/SQLState */
    public QueryTimeoutException(String message, String sql, Object[] params,
                                 int errorCode, String sqlState, Throwable cause) {
        super(message, sql, params, errorCode, sqlState, cause);
    }

}
