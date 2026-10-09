package com.wxdgaming.batis.sql.partition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 分区配置（通用数据模型，与具体数据库无关）
 *
 * <p>描述一张表的分区策略：类型、表达式、各分区定义等。</p>
 *
 * <h3>在本链路中的位置</h3>
 * <p>它是 {@link PartitionBuilder}（链式收集意图）和 {@link PartitionManager}（渲染 DDL）之间的
 * 唯一交接物：Builder 只写这个对象，Manager 只读这个对象。这样两边可以各自独立扩展
 * （加一种分区粒度不必改 Manager，加一种方言不必改 Builder），
 * 也让"脱库只看 SQL 文本"的用法成立（{@code PartitionBuilder.of(manager)} 不传缓存时，
 * 仍然只是往本对象填数据再由 Manager 渲染）。</p>
 *
 * <h3>字段按"一次 DDL 动的是哪一组"分组</h3>
 * <ul>
 *   <li>{@code type} + {@code expression}：这次 ALTER 的分区语义，二者共同决定渲染分支。</li>
 *   <li>{@code partitions}：要建/要追加的分区定义列表（RANGE/LIST 用），也是缓存去重的作用对象。</li>
 *   <li>{@code partitionCount}：分区个数（HASH/KEY 用），与 partitions 互斥，Manager 据此选分支。</li>
 *   <li>{@code dropNames}：<b>列表</b>——MySQL 允许一条 ALTER 里连写多个 DROP PARTITION，
 *   合并成一条可少一次表元数据变更。</li>
 *   <li>{@code truncateName}：<b>单个</b>——按当前 API 一次只清一个分区。</li>
 *   <li>{@code reorganizeOldName} + {@code reorganizeDefs}：被拆的原分区名 → 拆成的新分区列表。</li>
 * </ul>
 *
 * <p>注意这里<b>不做任何正确性校验</b>：RANGE 分区要求边界值严格单调递增，
 * 这个约束只由调用方（以及 {@code MysqlPartitionExpr} 的数字编码）保证，
 * 传进来的顺序错了本类也不会拒绝，最终表现为 MySQL 报
 * "Values are not monotonic" 或数据落错分区。</p>
 *
 * <p>可变对象且非线程安全：一个实例对应"一次待执行的 DDL"，
 * {@link PartitionBuilder#resetConfig()} 会在切换表时整体清空复用。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-08-08
 */
public class PartitionConfig {

    // 表名在 Builder 里已统一转小写，这里的字段就是渲染 DDL 的最终口径
    private String tableName;
    private PartitionType type;
    private String expression;
    private int partitionCount;
    private final List<PartitionDef> partitions = new ArrayList<>();
    private final List<String> dropNames = new ArrayList<>();
    private String truncateName;
    private String reorganizeOldName;
    private final List<PartitionDef> reorganizeDefs = new ArrayList<>();

    // 读侧（Manager 渲染时）：返回不可修改视图，避免方言实现反向污染配置
    public String getTableName() { return tableName; }
    public PartitionType getType() { return type; }
    public String getExpression() { return expression; }
    public int getPartitionCount() { return partitionCount; }
    public List<PartitionDef> getPartitions() { return Collections.unmodifiableList(partitions); }
    public List<String> getDropNames() { return Collections.unmodifiableList(dropNames); }
    public String getTruncateName() { return truncateName; }
    public String getReorganizeOldName() { return reorganizeOldName; }
    public List<PartitionDef> getReorganizeDefs() { return Collections.unmodifiableList(reorganizeDefs); }

    // 写侧：跨模块被 PartitionBuilder（sql 层）与各方言 Manager 直接使用，故保持公开
    public void setTableName(String tableName) { this.tableName = tableName; }
    public void setType(PartitionType type) { this.type = type; }
    public void setExpression(String expression) { this.expression = expression; }
    public void setPartitionCount(int partitionCount) { this.partitionCount = partitionCount; }
    // 只追加、不去重、不校验顺序：去重发生在 PartitionBuilder（那里才有缓存可查）
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
