package com.wxdgaming.batis.sql.partition;

/**
 * 分区类型（通用，与具体数据库无关）
 *
 * <p>枚举本身只回答"按什么语义切数据"，不关心任何具体 SQL 关键字；
 * 把这些语义渲染成 DDL 是各方言 {@link PartitionManager} 的职责
 * （见 {@code MysqlPartitionManager}），这也是先有接口再有枚举常量、而不是直接在
 * MySQL 实现里写字符串的原因——将来接 pgsql 的声明式分区时，本枚举可以复用。</p>
 *
 * <h3>两类语义的区别决定了后面所有分支</h3>
 * <ul>
 *   <li><b>需要边界值</b>：RANGE / LIST（以及 MySQL 的 *_COLUMNS 变体）。
 *   每个分区要写"LESS THAN 某个值"或"IN 某组值"，分区名由调用方指定，
 *   数据按边界落到具体分区，因此查询可以按边界做分区裁剪。</li>
 *   <li><b>只需要分区数</b>：HASH / KEY / LINEAR_HASH / LINEAR_KEY。
 *   只写 {@code PARTITIONS n}，分区名由数据库自己生成（MySQL 是 p0…p7），
 *   目的是散列写入压力而不是按时间裁剪，所以这几种类型不参与
 *   {@link PartitionBuilder} 的按日/按月批量生成分支。</li>
 * </ul>
 *
 * <p>注意分层上的一个既有妥协：{@link #RANGE_COLUMNS} 与 {@link #LIST_COLUMNS}
 * 是 MySQL 独有的写法（RANGE 只接受整数表达式，RANGE COLUMNS 才接受日期/字符串列），
 * 严格来说属于方言概念却放进了方言无关的枚举里。目前渲染逻辑仍全部在 sql.mysql 层，
 * 新增方言时不要误把这两个常量当作通用能力。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-08-08
 */
public enum PartitionType {

    // ---- 需要边界值的类型：分区定义里必须带 VALUES LESS THAN / VALUES IN ----
    RANGE,
    LIST,

    // ---- 只需要分区数的类型：DDL 只写 PARTITIONS n，分区名由库端生成（MySQL 为 p0…p7） ----
    HASH,
    KEY,

    /** RANGE COLUMNS（MySQL 特化） */
    RANGE_COLUMNS,
    /** LIST COLUMNS（MySQL 特化） */
    LIST_COLUMNS,

    // 线性散列：分区数变化时只重分布相邻分区，扩缩容代价低于 HASH/KEY，语义仍归"只需要分区数"
    LINEAR_HASH,
    LINEAR_KEY
}
