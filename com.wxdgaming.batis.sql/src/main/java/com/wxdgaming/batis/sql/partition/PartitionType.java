package com.wxdgaming.batis.sql.partition;

/**
 * 分区类型（通用，与具体数据库无关）
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-08-08
 */
public enum PartitionType {

    RANGE,
    LIST,
    HASH,
    KEY,

    /** RANGE COLUMNS（MySQL 特化） */
    RANGE_COLUMNS,
    /** LIST COLUMNS（MySQL 特化） */
    LIST_COLUMNS,

    LINEAR_HASH,
    LINEAR_KEY
}
