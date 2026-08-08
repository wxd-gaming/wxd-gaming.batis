package com.wxdgaming.batis.sql.partition;

import com.wxdgaming.batis.sql.JdbcTemplate;

import java.util.List;

/**
 * 分区元数据读取接口（不同数据库实现各自的查询逻辑）
 *
 * <p>用于从数据库中查询某张表已有的分区列表，供 {@link PartitionCache} 缓存。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-08-08
 */
public interface PartitionMetaReader {

    /**
     * 获取指定表的所有分区名
     *
     * @param jdbcTemplate JDBC 操作器
     * @param tableName    表名
     * @return 分区名列表；表不存在或未分区时返回空 List
     */
    List<String> getPartitionNames(JdbcTemplate jdbcTemplate, String tableName);
}
