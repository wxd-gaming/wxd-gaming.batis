package com.wxdgaming.batis.sql.partition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 分区配置（通用数据模型，与具体数据库无关）
 *
 * <p>描述一张表的分区策略：类型、表达式、各分区定义等。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-08-08
 */
public class PartitionConfig {

    private String tableName;
    private PartitionType type;
    private String expression;
    private int partitionCount;
    private final List<PartitionDef> partitions = new ArrayList<>();
    private final List<String> dropNames = new ArrayList<>();
    private String truncateName;
    private String reorganizeOldName;
    private final List<PartitionDef> reorganizeDefs = new ArrayList<>();

    public String getTableName() { return tableName; }
    public PartitionType getType() { return type; }
    public String getExpression() { return expression; }
    public int getPartitionCount() { return partitionCount; }
    public List<PartitionDef> getPartitions() { return Collections.unmodifiableList(partitions); }
    public List<String> getDropNames() { return Collections.unmodifiableList(dropNames); }
    public String getTruncateName() { return truncateName; }
    public String getReorganizeOldName() { return reorganizeOldName; }
    public List<PartitionDef> getReorganizeDefs() { return Collections.unmodifiableList(reorganizeDefs); }

    public void setTableName(String tableName) { this.tableName = tableName; }
    public void setType(PartitionType type) { this.type = type; }
    public void setExpression(String expression) { this.expression = expression; }
    public void setPartitionCount(int partitionCount) { this.partitionCount = partitionCount; }
    public void addPartition(PartitionDef def) { this.partitions.add(def); }
    public void addDropName(String name) { this.dropNames.add(name); }
    public void setTruncateName(String name) { this.truncateName = name; }
    public void setReorganizeOldName(String name) { this.reorganizeOldName = name; }
    public void addReorganizeDef(PartitionDef def) { this.reorganizeDefs.add(def); }

    /** 清空分区定义列表 */
    public void clearPartitions() { this.partitions.clear(); }
    /** 清空删除列表 */
    public void clearDropNames() { this.dropNames.clear(); }
    /** 清空重组定义 */
    public void clearReorganize() {
        this.reorganizeOldName = null;
        this.reorganizeDefs.clear();
    }
}
