package com.wxdgaming.batis.model;

/**
 * 逻辑列类型
 * <p>
 * 描述"这一列存什么"，不描述"某种数据库怎么写这一列"。
 * 实体解析（EntityParser）只产出逻辑类型 + 长度/精度，
 * 由 sql 层 {@code ColumnTypeDefHandler} SPI 按方言渲染成物理列类型字符串
 * （如 MySQL 的 {@code VARCHAR(255)}、PostgreSQL 的 {@code varchar(255)}）。
 * <p>
 * 枚举常量按容量档位命名（TEXT &lt; MEDIUMTEXT &lt; LONGTEXT 等），
 * 方言可将多个逻辑类型映射到同一物理类型（如 pgsql 的 JSON/TEXT 档统一为 text）。
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public enum SqlType {

    /** 布尔，两态值 */
    BOOLEAN,
    /** 单字节整数 */
    BYTE,
    /** 双字节整数 */
    SHORT,
    /** 中等整数（24 位） */
    MEDIUMINT,
    /** 四字节整数 */
    INT,
    /** 八字节整数 */
    BIGINT,
    /** 单精度浮点 */
    FLOAT,
    /** 双精度浮点 */
    DOUBLE,
    /** 定点数，使用 length=精度、scale=小数位 */
    DECIMAL,
    /** 变长字符串，使用 length */
    VARCHAR,
    /** 定长字符串，使用 length */
    CHAR,
    /** 短文本（约 64K） */
    TEXT,
    /** 中文本（约 16M） */
    MEDIUMTEXT,
    /** 长文本（约 4G） */
    LONGTEXT,
    /** 短二进制（约 64K） */
    BLOB,
    /** 中二进制（约 16M） */
    MEDIUMBLOB,
    /** 长二进制（约 4G） */
    LONGBLOB,
    /** 日期（无时间） */
    DATE,
    /** 时间（无日期） */
    TIME,
    /** 日期时间 */
    DATETIME,
    /** 时间戳 */
    TIMESTAMP,
    /** JSON 文档 */
    JSON

}
