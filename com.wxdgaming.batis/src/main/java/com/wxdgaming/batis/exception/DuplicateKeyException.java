package com.wxdgaming.batis.exception;

/**
 * 唯一键/主键冲突异常
 * <p>
 * 什么时候抛出（以翻译器实际映射为准）：
 * <ul>
 *   <li>MySQL 错误码 1062（ER_DUP_ENTRY，Duplicate entry）与 1467（ER_DUP_AUTO_INCREMENT，
 *       自增主键溢出）；</li>
 *   <li>SQLState 23/45 类别下消息含 "duplicate" 或 "unique"；</li>
 *   <li>消息含 "duplicate entry"、"duplicate key"、"unique constraint" 的关键字兜底
 *       （驱动未给出错误码时也能命中）。</li>
 * </ul>
 * </p>
 * <p>
 * 为什么单独立一个类：唯一键冲突几乎总是业务语义事件而不是程序错误——"这个账号已注册"
 * "这一对 (server_id, role_id) 已存在"。只有把它从约束家族里分出来，调用方才能安全地
 * catch 这一种并翻译成业务提示，而不用靠匹配异常消息字符串（消息随方言和版本变，不可靠）。
 * </p>
 * <p>
 * 上层怎么应对：不可重试。常见的正确处理有三种，取决于业务意图：改成 upsert
 * （INSERT ... ON DUPLICATE KEY UPDATE）、先查后插（仍需唯一键兜底，防并发），
 * 或者按幂等语义直接吞掉（重复提交同一份数据时"已存在"就是成功）。
 * 注意自增主键溢出（1467）也落在这里，它表示 ID 空间耗尽，属于必须人工介入的容量问题。
 * </p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class DuplicateKeyException extends DataIntegrityViolationException {

    /** 合成/包装场景，仅消息与 cause */
    public DuplicateKeyException(String message, Throwable cause) {
        super(message, cause);
    }

    /** 方言翻译器构造，携带原始 SQL/参数/错误码/SQLState */
    public DuplicateKeyException(String message, String sql, Object[] params,
                                 int errorCode, String sqlState, Throwable cause) {
        super(message, sql, params, errorCode, sqlState, cause);
    }

}
