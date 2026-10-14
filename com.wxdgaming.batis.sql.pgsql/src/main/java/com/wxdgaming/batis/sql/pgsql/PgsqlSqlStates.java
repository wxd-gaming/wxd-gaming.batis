package com.wxdgaming.batis.sql.pgsql;

/**
 * PostgreSQL 的 SQLState 常量表。
 * <p>与 {@code MysqlSqlCodes} 的对应物，但形态完全不同：MySQL 给的是厂商私有数字码，
 * PG 的 {@code SQLException.getErrorCode()} 恒为 0，唯一可靠的信号是 SQLSTATE 字符串
 * （PG 直接采用标准的 5 字符 state，见服务端 errcodes 表）。因此本表是字符串常量而不是整数，
 * 翻译器也必须按 state 分派——照抄 {@code switch (getErrorCode())} 会一个 case 都不命中，
 * 所有异常都落进兜底分支，"可重试 / 直接失败"的判别随之全部失效。</p>
 * <p>只收录框架会区别对待的码；未列出的 state 交给
 * {@code DefaultSqlExceptionHandler} 按类别前缀与关键字兜底。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-14
 */
public final class PgsqlSqlStates {

    // ===== 连接类 =====
    /** 连接失败（数据库未启动/网络不通） */
    public static final String CONNECTION_EXCEPTION = "08000";
    /** 无法建立 SQL-JAVA 连接 */
    public static final String CONNECTION_FAILURE = "08006";
    /** 管理员关闭连接：连接抖动，可重连后重试 */
    public static final String ADMIN_SHUTDOWN = "57P01";
    /** 崩溃导致的关闭 */
    public static final String CRASH_SHUTDOWN = "57P02";
    /** 数据库正在启动，暂时不能连接 */
    public static final String CANNOT_CONNECT_NOW = "57P03";
    /** 系统错误（底层故障，通常可重试） */
    public static final String SYSTEM_ERROR = "58000";
    /** too_many_connections：连接数打满 */
    public static final String TOO_MANY_CONNECTIONS = "53300";
    public static final String CONFIGURATION_LIMIT_EXCEEDED = "53400";

    // ===== 数据完整性 =====
    /** unique_violation（对应 MySQL 1062） */
    public static final String UNIQUE_VIOLATION = "23505";
    /** not_null_violation（对应 MySQL 1048） */
    public static final String NOT_NULL_VIOLATION = "23502";
    /** foreign_key_violation（对应 MySQL 1451/1452） */
    public static final String FOREIGN_KEY_VIOLATION = "23503";
    /** check_violation：MySQL 老版本没有真正的 CHECK，PG 会独立报这一档 */
    public static final String CHECK_VIOLATION = "23514";

    // ===== 数据取值 =====
    /** string_data_right_truncation：值比列宽长（对应 MySQL 1406） */
    public static final String STRING_DATA_RIGHT_TRUNCATION = "22001";
    /** numeric_value_out_of_range（对应 MySQL 1264） */
    public static final String NUMERIC_VALUE_OUT_OF_RANGE = "22003";

    // ===== 语句与对象 =====
    /** undefined_column：列不存在，多半是实体与表结构漂移（对应 MySQL 1054） */
    public static final String UNDEFINED_COLUMN = "42703";
    /** undefined_table：表不存在（对应 MySQL 1146） */
    public static final String UNDEFINED_TABLE = "42P01";
    /** syntax_error（对应 MySQL 1064） */
    public static final String SYNTAX_ERROR = "42601";
    /** duplicate_table：CREATE TABLE 撞已存在表 */
    public static final String DUPLICATE_TABLE = "42P07";
    /** duplicate_object：索引名等 schema 级对象重名 */
    public static final String DUPLICATE_OBJECT = "42710";

    // ===== 并发与超时 =====
    /** serialization_failure：可重试的瞬时冲突 */
    public static final String SERIALIZATION_FAILURE = "40001";
    /** deadlock_detected（对应 MySQL 1213） */
    public static final String DEADLOCK_DETECTED = "40P01";
    /** lock_not_available：锁等待超时（对应 MySQL 1205） */
    public static final String LOCK_NOT_AVAILABLE = "55P03";
    /** query_canceled：语句超时被取消 */
    public static final String QUERY_CANCELED = "57014";

    private PgsqlSqlStates() {
    }
}
