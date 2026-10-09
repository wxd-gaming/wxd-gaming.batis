package com.wxdgaming.batis.sql.partition;

/**
 * 分区管理接口（不同数据库实现各自的 SQL 生成逻辑）
 *
 * <p>与现有 {@code TableCreateHandler} 接口风格一致。</p>
 *
 * <h3>分层边界</h3>
 * <p>本接口位于方言无关的 sql 层，是"分区 DDL 怎么拼"这件事的唯一方言插入点：
 * 全部 MySQL 专有语法（反引号、{@code RANGE COLUMNS}、{@code VALUES LESS THAN MAXVALUE}、
 * {@code PARTITIONS n}）都只出现在 {@code MysqlPartitionManager} 里。
 * 新增 pgsql 时只需再实现一个 Manager（pg 的声明式分区是
 * {@code CREATE TABLE ... PARTITION OF} 另建子表，连"ALTER 一张表加分区"的形状都不一样），
 * {@link PartitionBuilder} 和上层业务代码都不用动——测试用例⑬用 assertInstanceOf 钉住了这条约束。</p>
 *
 * <p>五个方法都是纯函数式的"生成文本"，不碰数据库、不吞异常，因此可以脱库单测
 * （测试用例⑥⑫即走这条路，先看 SQL 再决定执行，方便生产 DDL 走人工评审）。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-08-08
 */
public interface PartitionManager {

    /**
     * 构建 ALTER TABLE ... PARTITION BY ... 语句（非分区表 → 分区表）
     * <p>这是破坏性最大的一条：MySQL 会重建整表数据并按新分区规则重分布，
     * 大表上等价于一次全表拷贝。实现内需区分 RANGE/LIST（逐个分区带边界值）
     * 与 HASH/KEY（只写分区数，分区名由库端生成）两种形态。</p>
     *
     * @param config 分区配置，取表名、类型、表达式、分区定义列表或分区数
     * @return 完整的 DDL 文本
     */
    String buildCreatePartition(PartitionConfig config);

    /**
     * 构建 ALTER TABLE ... ADD PARTITION (...) 语句（追加分区）
     * <p>日常滚动补分区的入口，只往分区列表尾部追加、不动已有数据，因此代价远小于
     * {@link #buildCreatePartition}。注意库端硬限制：若表尾已存在 MAXVALUE 兜底分区，
     * 就不能再用 ADD 往中间插入分区（测试用例②因此刻意不在前置建表时加 pmax）。</p>
     *
     * @param config 分区配置，取待新增的分区定义列表
     * @return 完整的 DDL 文本
     */
    String buildAddPartition(PartitionConfig config);

    /**
     * 构建 ALTER TABLE ... DROP PARTITION ... 语句（删除分区）
     * <p>按<b>分区名</b>删除而不是按位置/序号，所以支持一次点名多个；
     * 语义是"分区定义连同其中的数据一起消失"，用于数据保留期到期的清理。
     * 与 {@link #buildTruncatePartition} 的分工就是"删不删定义"。</p>
     *
     * @param config 分区配置，取 dropNames 列表
     * @return 完整的 DDL 文本
     */
    String buildDropPartition(PartitionConfig config);

    /**
     * 构建 ALTER TABLE ... TRUNCATE PARTITION ... 语句（清空分区）
     * <p>只清数据、<b>保留分区定义与边界</b>。如果误写成 DROP，边界消失后新数据会全部
     * 堆进兜底分区，按时间查询失去分区裁剪能力而退化成全表扫——这类性能退化不报错，
     * 所以测试用例⑪专门断言"清空后分区定义仍在"。</p>
     *
     * @param config 分区配置，取 truncateName
     * @return 完整的 DDL 文本
     */
    String buildTruncatePartition(PartitionConfig config);

    /**
     * 构建 ALTER TABLE ... REORGANIZE PARTITION ... INTO (...) 语句（重组分区）
     * <p>把一个（组）已有分区拆成新的若干分区，是<b>唯一能在已建分区之间搬数据</b>的手段，
     * 典型用途是把一个粗粒度分区按月拆开，或给 MAXVALUE 兜底分区腾出空间。
     * 注意它是重分布操作，代价接近一次局部全表重写，不适合放在高频定时任务里。</p>
     *
     * @param config 分区配置，取被重组的原分区名与重组后的新分区定义列表
     * @return 完整的 DDL 文本
     */
    String buildReorganizePartition(PartitionConfig config);
}
