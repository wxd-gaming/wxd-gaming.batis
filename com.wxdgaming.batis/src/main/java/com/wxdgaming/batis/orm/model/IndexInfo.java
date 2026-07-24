package com.wxdgaming.batis.orm.model;

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

    public IndexInfo() {
    }

    public String getName() {
        return name;
    }

    public IndexInfo setName(String name) {
        this.name = name;
        return this;
    }

    public List<String> getColumns() {
        return columns;
    }

    public IndexInfo setColumns(List<String> columns) {
        this.columns = columns;
        return this;
    }

    public boolean isUnique() {
        return unique;
    }

    public IndexInfo setUnique(boolean unique) {
        this.unique = unique;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IndexInfo indexInfo)) return false;
        return Objects.equals(name, indexInfo.name);
    }

    @Override
    public int hashCode() {
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
