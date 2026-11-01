package com.wxdgaming.batis.model;

import java.util.List;
import java.util.Objects;

/**
 * 运行时索引元数据
 *
 * @author wxd-gaming
 */
public class IndexInfo {

    /** 索引名 */
    private String name;
    /** 索引列名列表 */
    private List<String> columns;
    /** 是否唯一索引 */
    private boolean unique;
    /**
     * 库端索引类型（BTREE / FULLTEXT / SPATIAL / HASH），实体解析侧为 null。
     * <p>实体注解只能用 {@code @Index} 表达普通 B-tree 索引，读回类型才能发现
     * "同名索引在库里其实是 FULLTEXT"这类实体表达不了的差异。</p>
     */
    private String indexType;
    /** 各列前缀长度（与 {@link #columns} 同序），0 表示整列；实体解析侧为 null */
    private List<Integer> prefixLengths;
    /** 各列是否降序（与 {@link #columns} 同序）；实体解析侧为 null */
    private List<Boolean> descending;
    /** 是否含表达式列（函数索引，STATISTICS.COLUMN_NAME 为 NULL）；实体解析侧恒为 false */
    private boolean expressionIndex;

    public String getName() { return name; }

    public IndexInfo setName(String name) {
        this.name = name;
        return this;
    }

    public List<String> getColumns() { return columns; }

    public IndexInfo setColumns(List<String> columns) {
        this.columns = columns;
        return this;
    }

    public boolean isUnique() { return unique; }

    public IndexInfo setUnique(boolean unique) {
        this.unique = unique;
        return this;
    }

    public String getIndexType() { return indexType; }

    public IndexInfo setIndexType(String indexType) {
        this.indexType = indexType;
        return this;
    }

    public List<Integer> getPrefixLengths() { return prefixLengths; }

    public IndexInfo setPrefixLengths(List<Integer> prefixLengths) {
        this.prefixLengths = prefixLengths;
        return this;
    }

    public List<Boolean> getDescending() { return descending; }

    public IndexInfo setDescending(List<Boolean> descending) {
        this.descending = descending;
        return this;
    }

    public boolean isExpressionIndex() { return expressionIndex; }

    public IndexInfo setExpressionIndex(boolean expressionIndex) {
        this.expressionIndex = expressionIndex;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IndexInfo indexInfo)) return false;
        // 仅按索引名判等：实体解析末尾用 distinct() 去重，同名索引只保留一条
        // （索引名是"表+列+唯一性"的纯函数，同名即同定义——字段级索引与 @Column(unique)
        // 自动派生的唯一索引会生成同一个名字，靠名字收敛避免重复建索引）
        return Objects.equals(name, indexInfo.name);
    }

    @Override
    public int hashCode() {
        // 与 equals 保持一致：只用 name 参与散列
        return Objects.hashCode(name);
    }

    @Override
    public String toString() {
        return "IndexInfo{" +
                "name='" + name + '\'' +
                ", columns=" + columns +
                ", unique=" + unique +
                ", indexType='" + indexType + '\'' +
                ", prefixLengths=" + prefixLengths +
                ", descending=" + descending +
                ", expressionIndex=" + expressionIndex +
                '}';
    }
}
