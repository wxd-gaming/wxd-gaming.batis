package com.wxdgaming.batis.sql.partition;

/**
 * 单个分区定义（通用，与具体数据库无关）
 *
 * <p>一条定义 = 分区名 + 边界子句，是 {@link PartitionConfig} 里的最小单元，
 * 最终被方言层拼成 {@code PARTITION 名字 边界子句}。</p>
 *
 * <h3>为什么 value 是一个"整句子符串"而不是一个数字</h3>
 * <p>这里刻意不做结构化：value 存的是从 {@code "VALUES LESS THAN (20260802)"}
 * 到 {@code "VALUES IN (1, 2, 3)"} 这样的完整片段，由
 * {@link PartitionBuilder#addPartition(String, String)} 的调用方（或按日/按月快捷方法）生成，
 * 方言层只负责拼接不负责理解其含义。好处是新增分区语义（如 VALUES LESS THAN MAXVALUE、
 * 未来的 pgsql FOR VALUES）不需要改动本类和 PartitionConfig 的字段；
 * 代价是 value 的语法正确性没有编译期保障，写错只能在执行 DDL 时才暴露——
 * 这也是测试用例⑥要先只断言"生成的 SQL 文本"再谈执行的原因之一。</p>
 *
 * <p>不可变对象：字段全 final，构造后不能改，可以安全地被 {@link PartitionConfig}
 * 的列表长期持有。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-08-08
 */
public class PartitionDef {

    private final String name;
    private final String value;

    public PartitionDef(String name, String value) {
        this.name = name;
        this.value = value;
    }

    public String getName() { return name; }
    public String getValue() { return value; }

    @Override
    public String toString() {
        return name + ": " + value;
    }
}
