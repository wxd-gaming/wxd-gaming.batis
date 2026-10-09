package com.wxdgaming.batis.exception;

/**
 * 数据截断/越界异常
 * <p>列长度不足、数值超出列类型范围等。</p>
 * <p>
 * 什么时候抛出（以翻译器实际映射为准）：
 * <ul>
 *   <li>MySQL 错误码 1406（ER_DATA_TOO_LONG，Data too long for column）、
 *       1265（ER_WARN_DATA_TRUNCATED，值被截断）、1264（ER_WARN_DATA_OUT_OF_RANGE，值超出列范围）；</li>
 *   <li>SQLState 22 类别（数据异常）下消息含 "truncat"、"too long"、"out of range"；</li>
 *   <li>SQLState 23/45 类别下消息含 "truncat" 或 "too long"。</li>
 * </ul>
 * 注意同属 22 类别但不含上述关键字的（比如字符串转数字失败）会归到
 * {@link TypeMismatchDataAccessException} —— 区别是"装得下但类型不对"还是"类型对但装不下"。
 * </p>
 * <p>
 * 上层怎么应对：不可重试，且必须认定写入失败（严格模式下不会被静默截断）。
 * 处理方式二选一：数据本身超限应拒绝并提示用户；列定义偏小则改
 * {@code Column.length()} 或 {@code Column.type()}，随后同步 DDL。
 * 这类错误往往在压测或真实用户数据（超长昵称、富文本、大金额）下才暴露，
 * 出现一次就应该回看整张表的宽度设定。
 * </p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class DataTruncationException extends DataIntegrityViolationException {

    /** 合成/包装场景，仅消息与 cause */
    public DataTruncationException(String message, Throwable cause) {
        super(message, cause);
    }

    /** 方言翻译器构造，携带原始 SQL/参数/错误码/SQLState */
    public DataTruncationException(String message, String sql, Object[] params,
                                   int errorCode, String sqlState, Throwable cause) {
        super(message, sql, params, errorCode, sqlState, cause);
    }

}
