package com.wxdgaming.batis.exception;

/**
 * 死锁牺牲者异常
 * <p>数据库检测到死锁并回滚了本事务，重试整个事务通常可以成功。</p>
 * <p>
 * 什么时候抛出（以翻译器实际映射为准）：
 * <ul>
 *   <li>MySQL 错误码 1213（ER_LOCK_DEADLOCK，Deadlock found when trying to get lock）；</li>
 *   <li>SQLState 以 40 开头（事务回滚/序列化失败类）——翻译器把它统一归到本类；</li>
 *   <li>消息含 "deadlock found" 的关键字兜底。</li>
 * </ul>
 * </p>
 * <p>
 * <b>重试单位是整个事务，不是这条语句</b>：数据库已经回滚了本事务的全部改动，
 * 单独重跑失败的那一条只会拿到回滚后的数据。正确姿势是"重开事务、从头执行整段业务"，
 * 并限制次数与退避。批量写入路径遇到它（isRetryable() 为 true）会直接上抛，
 * 不会降级成逐条补写，避免把一次锁冲突放大成上千条语句。
 * </p>
 * <p>频繁出现说明加锁顺序不一致，应从业务侧统一按同一顺序访问多行（如按主键升序），
 * 或缩小事务范围，而不是加大重试次数。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class DeadlockLoserDataAccessException extends TransientDataAccessException {

    /** 合成/非 SQL 场景，仅消息与 cause */
    public DeadlockLoserDataAccessException(String message, Throwable cause) {
        super(message, cause);
    }

    /** 方言翻译器构造，携带原始 SQL/参数/错误码/SQLState */
    public DeadlockLoserDataAccessException(String message, String sql, Object[] params,
                                            int errorCode, String sqlState, Throwable cause) {
        super(message, sql, params, errorCode, sqlState, cause);
    }

}
