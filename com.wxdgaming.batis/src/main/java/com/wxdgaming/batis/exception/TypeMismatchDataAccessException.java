package com.wxdgaming.batis.exception;

/**
 * Java 类型与数据库类型转换失败异常
 * <p>
 * 这一支有两个来源，读代码时容易只看到其中一个：
 * <ol>
 *   <li><b>值转换阶段</b>（主要来源，SQL 还没发出去就抛）：MySQL 方言的 BeanValueHandler 在做
 *       JSON 序列化/反序列化、Protostuff 二进制编解码、Blob 读取、自定义 TypeConverter 回调失败时构造，
 *       只有 message + cause，没有 sql/errorCode；JSON 与 Protostuff 序列化会先重试若干次
 *       （应对集合被多线程并发修改），重试耗尽才升级成本异常。</li>
 *   <li><b>结果翻译阶段</b>：SQLState 以 22 开头（数据异常类）且消息不含 truncat/too long/out of range
 *       时归本类——即"类型对不上"而不是"长度装不下"（后者是 {@link DataTruncationException}）。</li>
 * </ol>
 * </p>
 * <p>
 * 为什么归到不可重试：转换失败是定义问题——实体字段类型与列类型/存储模式不匹配、
 * JSON 结构变更后旧数据读不回来、转换器抛了业务异常。同样的输入重试一万次结果一样。
 * </p>
 * <p>
 * 上层怎么应对：优先看 cause 里的原始异常定位是写侧还是读侧。写侧改 {@code Column.storageMode()}
 * 或字段类型；读侧（历史数据反序列化失败）通常是数据版本与代码版本不一致，需要迁移或兼容分支，
 * 重试和改 SQL 都无济于事。
 * </p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class TypeMismatchDataAccessException extends NonTransientDataAccessException {

    /** 值转换阶段常用：只有描述与底层 cause，没有 SQL 上下文 */
    public TypeMismatchDataAccessException(String message) {
        super(message);
    }

    /** 值转换阶段常用：包装序列化/转换器抛出的原始异常 */
    public TypeMismatchDataAccessException(String message, Throwable cause) {
        super(message, cause);
    }

    /** 结果翻译阶段由方言翻译器构造，携带原始 SQL/参数/错误码/SQLState */
    public TypeMismatchDataAccessException(String message, String sql, Object[] params,
                                           int errorCode, String sqlState, Throwable cause) {
        super(message, sql, params, errorCode, sqlState, cause);
    }

}
