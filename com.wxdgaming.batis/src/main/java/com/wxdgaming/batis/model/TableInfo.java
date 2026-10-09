package com.wxdgaming.batis.model;

import com.wxdgaming.batis.parser.EntityParser;
import lombok.Getter;
import lombok.Setter;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * 运行时表元数据，由 {@link EntityParser} 解析实体类得到。
 * <p>由 EntityParser 缓存并全局共享，解析完成后视为只读——各字段用 {@code @Setter} 只是为了解析期装配，
 * 运行期不应再修改，否则会影响所有并发读者。</p>
 *
 * @author wxd-gaming
 */
@Getter
@Setter
public class TableInfo {

    /** 实体类 */
    private Class<?> entityClass;
    /** 表名 */
    private String tableName;
    /** 表注释 */
    private String comment;
    /**
     * 列信息，key 为数据库列名，保持声明顺序
     * -- GETTER --
     * 返回所有列信息（key=列名，value=列元数据），保持声明顺序
     */
    private LinkedHashMap<String, ColumnInfo> columns;
    /** 索引信息列表（已去重并按名字典序稳定排序） */
    private List<IndexInfo> indexes;

    /** 按列名（数据库列名，区分大小写用调用方保证）快速查找列元数据，缺失返回 null */
    public ColumnInfo getColumn(String columnName) {
        return columns != null ? columns.get(columnName) : null;
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
