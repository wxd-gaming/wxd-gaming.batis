package com.wxdgaming.batis.sql.partition;

/**
 * 分区管理接口（不同数据库实现各自的 SQL 生成逻辑）
 *
 * <p>与现有 {@code TableCreateHandler} 接口风格一致。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-08-08
 */
public interface PartitionManager {

    /**
     * 构建 ALTER TABLE ... PARTITION BY ... 语句（非分区表 → 分区表）
     */
    String buildCreatePartition(PartitionConfig config);

    /**
     * 构建 ALTER TABLE ... ADD PARTITION (...) 语句（追加分区）
     */
    String buildAddPartition(PartitionConfig config);

    /**
     * 构建 ALTER TABLE ... DROP PARTITION ... 语句（删除分区）
     */
    String buildDropPartition(PartitionConfig config);

    /**
     * 构建 ALTER TABLE ... TRUNCATE PARTITION ... 语句（清空分区）
     */
    String buildTruncatePartition(PartitionConfig config);

    /**
     * 构建 ALTER TABLE ... REORGANIZE PARTITION ... INTO (...) 语句（重组分区）
     */
    String buildReorganizePartition(PartitionConfig config);
}
