package com.wxdgaming.batis.sql.mysql;

/**
 * MySQL 错误码常量表
 * <p>来自 MySQL 服务端 errmsg 定义，用于 {@link MysqlSqlExceptionHandler} 精确翻译。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public final class MysqlSqlCodes {

    /** 连接数超限 Too many connections */
    public static final int ER_CON_COUNT = 1040;
    /** 语法错误 You have an error in your SQL syntax */
    public static final int ER_PARSE_ERROR = 1064;
    /** 表不存在 Table doesn't exist */
    public static final int ER_NO_SUCH_TABLE = 1146;
    /** 列不存在 Unknown column */
    public static final int ER_BAD_FIELD_ERROR = 1054;
    /** 函数不存在 This function has no */
    public static final int ER_WRONG_FUNC_TO_ARG = 1110;
    /** SELECT 无 FROM */
    public static final int ER_NO_TABLES_USED = 1096;
    /** 非唯一列/表模糊 */
    public static final int ER_NON_UNIQ_ERROR = 1052;
    /** 主键/唯一索引冲突 Duplicate entry */
    public static final int ER_DUP_ENTRY = 1062;
    /** 自增主键溢出 */
    public static final int ER_DUP_AUTO_INCREMENT = 1467;
    /** 列值越界 Out of range */
    public static final int ER_WARN_DATA_OUT_OF_RANGE = 1264;
    /** null 值写入非空列 */
    public static final int ER_BAD_NULL_ERROR = 1048;
    /** 无默认值 */
    public static final int ER_NO_DEFAULT = 1364;
    /** 外键被引用，无法更新/删除 */
    public static final int ER_ROW_IS_REFERENCED_2 = 1451;
    /** 外键无对应父行 */
    public static final int ER_NO_REFERENCED_ROW_2 = 1452;
    /** 数据过长 Data too long for column */
    public static final int ER_DATA_TOO_LONG = 1406;
    /** 数据被截断 */
    public static final int ER_WARN_DATA_TRUNCATED = 1265;
    /** 锁等待超时 Lock wait timeout exceeded */
    public static final int ER_LOCK_WAIT_TIMEOUT = 1205;
    /** 死锁 Deadlock found */
    public static final int ER_LOCK_DEADLOCK = 1213;
    /** 查询被中断 */
    public static final int ER_QUERY_INTERRUPTED = 1317;
    /** 查询超时 MAX_EXECUTION_TIME */
    public static final int ER_QUERY_TIMEOUT = 3024;
    /** 只读对象上执行写操作 */
    public static final int ER_OPTION_PREVENTS_STATEMENT = 1290;
    /** 事务过大/回滚段不足 */
    public static final int ER_TOO_BIG_TRANSACTION = 1073;

    private MysqlSqlCodes() {
    }

}
