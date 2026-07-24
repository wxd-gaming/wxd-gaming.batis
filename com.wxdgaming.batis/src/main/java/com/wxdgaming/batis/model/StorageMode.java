package com.wxdgaming.batis.model;

/**
 * 列存储模式
 * <p>
 * 控制实体字段与数据库列之间的序列化/反序列化方式。
 * 可以显式指定基础类型（{@link #INT}、{@link #VARCHAR} 等），
 * 由解析器自动映射到对应的数据库列类型。
 *
 * <pre>
 * {@code
 * // AUTO（默认）：基础类型直接映射，复杂类型（List/Map/自定义POJO）自动升级为 JSON
 * private String name;
 *
 * // 显式指定基础类型
 * @Column(storageMode = StorageMode.INT)
 * private int level;
 *
 * @Column(storageMode = StorageMode.VARCHAR, length = 64)
 * private String account;
 *
 * // 显式 JSON 存储 -> LONGTEXT 列
 * @Column(storageMode = StorageMode.JSON)
 * private List<Item> items;
 *
 * // 显式二进制存储 -> BLOB/MEDIUMBLOB/LONGBLOB 列，使用 ProtostuffUtil 序列化
 * @Column(storageMode = StorageMode.LONGBLOB)
 * private BattleSnapshot snapshot;
 * }
 * </pre>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-24 18:17
 **/
public enum StorageMode {

    /** 自动推断：基础类型直接映射，复杂类型自动升级为 JSON */
    AUTO,
    /** boolean / Boolean → TINYINT(1) */
    BOOL,
    /** byte / Byte → TINYINT */
    BYTE,
    /** short / Short → SMALLINT */
    SHORT,
    /** int / Integer → INT */
    INT,
    /** long / Long → BIGINT */
    LONG,
    /** float / Float → FLOAT */
    FLOAT,
    /** double / Double → DOUBLE */
    DOUBLE,
    /** BigDecimal → DECIMAL(20,6) */
    DECIMAL,
    /** String → VARCHAR */
    VARCHAR,
    /** String → TEXT（长度 ≥ 16384） */
    TEXT,
    /** String → MEDIUMTEXT（长度 ≥ 65536） */
    MEDIUMTEXT,
    /** String → LONGTEXT（长度 ≥ 16777216） */
    LONGTEXT,
    /** byte[] → BLOB */
    BLOB,
    /** byte[] → MEDIUMBLOB */
    MEDIUMBLOB,
    /** byte[] → LONGBLOB */
    LONGBLOB,
    /** LocalDate → DATE */
    DATE,
    /** Date / LocalDateTime → DATETIME */
    DATETIME,
    /** LocalTime → TIME */
    TIME,
    /** 以 JSON 字符串存储 / 原生 JSON 类型 */
    JSON

}
