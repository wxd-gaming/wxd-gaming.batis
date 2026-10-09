package com.wxdgaming.batis.exception;

/**
 * 未知列异常
 * <p>SQL 引用了不存在的列；查询 DSL 本地校验失败时同样抛出此异常。</p>
 * <p>
 * 两个抛出时机，一个在库外一个在库内：
 * <ol>
 *   <li><b>执行前本地校验</b>：查询 DSL 把条件/排序里的名字解析成 ColumnInfo 时
 *       （SelectSqlBuilder 的列名解析，先按数据库列名忽略大小写匹配，再按 Java 字段名与其驼峰转下划线结果匹配），
 *       名字为空或表里没有这一列，直接构造本异常——此时 SQL 还没发给数据库，属于纯编程错误；</li>
 *   <li><b>执行后翻译</b>：MySQL 错误码 1054（ER_BAD_FIELD_ERROR，Unknown column），
 *       以及 SQLState 42 类别下消息含 "unknown column"、"invalid column name"、"undefined column"
 *       的关键字兜底。</li>
 * </ol>
 * 能在本地就发现列名问题，是为了让开发期直接看到"表 X 中不存在列/字段: Y"，
 * 而不是等一条拼好的 SQL 被数据库拒绝。
 * </p>
 * <p>
 * 归到不可重试（经由 {@link BadSqlGrammarException}）：列名写错不会因为重试而变对。
 * 上层排查顺序建议是——先看是不是 {@code Column.name()} 与真实 DDL 不一致，
 * 再看是不是 DSL 里误用了 Java 字段名而该列做了重命名，最后才是数据库缺列（DDL 未同步）。
 * </p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class UnknownColumnException extends BadSqlGrammarException {

    /** 本地校验失败专用：没有 SQLException 可包装，故显式传入空的 cause */
    public UnknownColumnException(String message) {
        super(message, (Throwable) null);
    }

    /** 包装场景：有底层异常但没有 SQL 上下文 */
    public UnknownColumnException(String message, Throwable cause) {
        super(message, cause);
    }

    /** 方言翻译器构造（MySQL 1054 等），携带原始 SQL/参数/错误码/SQLState */
    public UnknownColumnException(String message, String sql, Object[] params,
                                  int errorCode, String sqlState, Throwable cause) {
        super(message, sql, params, errorCode, sqlState, cause);
    }

}
