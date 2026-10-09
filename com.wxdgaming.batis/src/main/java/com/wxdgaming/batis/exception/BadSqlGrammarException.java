package com.wxdgaming.batis.exception;

/**
 * SQL 语法/对象错误异常
 * <p>语法解析失败、表/列不存在等。</p>
 * <p>
 * 什么时候抛出（以翻译器实际映射为准）：
 * <ul>
 *   <li>MySQL 错误码 1064（ER_PARSE_ERROR，语法错误）、1146（ER_NO_SUCH_TABLE，表不存在）、
 *       1096（ER_NO_TABLES_USED，SELECT 无 FROM）、1052（ER_NON_UNIQ_ERROR，列/表引用有歧义）、
 *       1110（ER_WRONG_FUNC_TO_ARG，函数不存在）；</li>
 *   <li>SQLState 以 42 开头（语法/对象缺失类）中未被列名识别命中的部分；</li>
 *   <li>消息含 "doesn't exist"、"syntax error"、"parse error" 的关键字兜底。</li>
 * </ul>
 * 列不存在单独拆出去：{@link UnknownColumnException} 继承本类，语义更精确且能在执行前本地校验。
 * </p>
 * <p>
 * 上层怎么应对：属于不可重试（isRetryable() 为 false）——这是代码或拼写错误，
 * 重试一万次结果一样。正确动作是让请求快速失败、打全 sql 与参数日志便于复现，
 * 修 SQL 或修映射；把它当瞬时故障重试只会拖垮线程池。生产环境看到它通常是发布带上了
 * 未同步的 DDL（表/列改名），应优先核对表结构而不是改语句。
 * </p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class BadSqlGrammarException extends NonTransientDataAccessException {

    /** 合成/包装场景，仅消息与 cause */
    public BadSqlGrammarException(String message, Throwable cause) {
        super(message, cause);
    }

    /** 方言翻译器构造，携带原始 SQL/参数/错误码/SQLState */
    public BadSqlGrammarException(String message, String sql, Object[] params,
                                  int errorCode, String sqlState, Throwable cause) {
        super(message, sql, params, errorCode, sqlState, cause);
    }

}
