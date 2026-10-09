package com.wxdgaming.batis.sql.partition;

import com.wxdgaming.batis.sql.JdbcTemplate;

import java.util.List;

/**
 * 分区元数据读取接口（不同数据库实现各自的查询逻辑）
 *
 * <p>用于从数据库中查询某张表已有的分区列表，供 {@link PartitionCache} 缓存。</p>
 *
 * <h3>为什么分区状态必须从库里读</h3>
 * <p>分区是<b>库端状态</b>而不是应用端配置：DBA 可能手工加过分区、别的实例删过分区、
 * 应用也可能带着旧缓存重启。只看内存里的配置无法判断"这个分区到底在不在"，
 * 于是重复 ADD 会直接报错。本接口就是那个"以库为准"的真相来源，
 * 三方言各自实现查询（MySQL 走 {@code information_schema.PARTITIONS}），
 * 与 {@link PartitionManager}（写侧）构成读写一对。</p>
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
