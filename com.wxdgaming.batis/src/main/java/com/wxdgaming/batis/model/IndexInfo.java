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
                '}';
    }
}
