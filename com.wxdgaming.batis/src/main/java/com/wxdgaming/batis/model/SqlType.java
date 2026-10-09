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
    /**
     * 中等整数（24 位）
     * <p>MySQL 专有的宽度档：Java 侧只有 int（→INT）与 long（→BIGINT），推断不出这一档，
     * 只有把列显式写成 {@code @Column(type = "MEDIUMINT")}，或从库端读回 mediumint 时才会出现。
     * 没有它，"实体声明 MEDIUMINT、库端也是 MEDIUMINT"在结构比对里会落到别的档位上，误报成漂移。</p>
     */
    MEDIUMINT,
    /** 四字节整数 */
    INT,
    /** 八字节整数 */
    BIGINT,
    /** 单精度浮点 */
    FLOAT,
    /** 双精度浮点 */
    DOUBLE,
    /** 定点数，用 length=总精度、scale=小数位；金额必须走这一档，FLOAT/DOUBLE 有二进制舍入误差 */
    DECIMAL,
    /** 变长字符串，使用 length；MySQL 对 VARCHAR(255) 这类长度只做上限校验，不做补位 */
    VARCHAR,
    /** 定长字符串，使用 length（未写长度时按 1）；MySQL 存取会补空格，比对时不能按"是否带长度"判漂移 */
    CHAR,
    /** 短文本（约 64K）：超过 VARCHAR 上限但不必上 LONGTEXT 的字符串落在这里 */
    TEXT,
    /** 中文本（约 16M） */
    MEDIUMTEXT,
    /** 长文本（约 4G） */
    LONGTEXT,
    /** 短二进制（约 64K）：byte[] 与序列化存储（Protostuff）走这一族 */
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
    /**
     * 时间戳
     * <p>预留档：目前没有任何路径产出它——EntityParser 不推断时间戳（Date/LocalDateTime 统一 DATETIME），
     * MySQL 侧也把库端的 {@code timestamp} 读回成 DATETIME（两者的零值/自动更新语义差异尚未建模）。
     * 将来要区分"跟随修改时间自动更新"这类行为时从这里补语义，并把方言的解析改回本档。</p>
     */
    TIMESTAMP,
    /**
     * JSON 文档
     * <p>MySQL 有原生 JSON 列，其它方言（如 pgsql 早期版本）常退化成 TEXT/VARCHAR 存字符串。
     * 无论落到哪种物理类型，读写两侧的序列化都必须一致——否则同一条记录的 JSON 列会被反复判定成"内容变了"。</p>
     */
    JSON

}
