package com.wxdgaming.batis.model;

import com.wxdgaming.batis.parser.EntityParser;

import java.util.List;

/**
 * 运行时表元数据，由 {@link EntityParser} 解析实体类得到
 *
 * @author wxd-gaming
 */
public class TableInfo {

    /** 实体类 */
    private Class<?> entityClass;
    /** 表名 */
    private String tableName;
    /** 表注释 */
    private String comment;
    /** 列信息列表 */
    private List<ColumnInfo> columns;
    /** 索引信息列表 */
    private List<IndexInfo> indexes;

    public Class<?> getEntityClass() { return entityClass; }

    public TableInfo setEntityClass(Class<?> entityClass) {
        this.entityClass = entityClass;
        return this;
    }

    public String getTableName() { return tableName; }

    public TableInfo setTableName(String tableName) {
        this.tableName = tableName;
        return this;
    }

    public String getComment() { return comment; }

    public TableInfo setComment(String comment) {
        this.comment = comment;
        return this;
    }

    public List<ColumnInfo> getColumns() { return columns; }

    public TableInfo setColumns(List<ColumnInfo> columns) {
        this.columns = columns;
        return this;
    }

    public List<IndexInfo> getIndexes() { return indexes; }

    public TableInfo setIndexes(List<IndexInfo> indexes) {
        this.indexes = indexes;
        return this;
    }

    @Override
    public String toString() {
        return "TableInfo{" +
                "tableName='" + tableName + '\'' +
                ", comment='" + comment + '\'' +
                ", columns=" + columns +
                ", indexes=" + indexes +
                '}';
    }
}
