package com.wxdgaming.batis.orm.model;

import com.wxdgaming.batis.orm.annotation.GenerationType;

/**
 * 运行时列元数据
 *
 * @author wxd-gaming
 */
public class ColumnInfo {

    /** 列名 */
    private String columnName;
    /** 数据库列类型 */
    private String type;
    /** 列长度 */
    private int length;
    /** 小数精度 */
    private int scale;
    /** 列注释 */
    private String comment;
    /** 默认值（SQL 字面量） */
    private String defaultValue;
    /** 是否允许为空 */
    private boolean nullable;
    /** 是否唯一 */
    private boolean unique;
    /** 是否主键 */
    private boolean primaryKey;
    /** 主键生成策略 */
    private GenerationType generationType = GenerationType.NONE;
    /** 对应的 Java 字段名 */
    private String fieldName;
    /** 对应的 Java 类型 */
    private Class<?> javaType;

    public ColumnInfo() {
    }

    public String getColumnName() {
        return columnName;
    }

    public ColumnInfo setColumnName(String columnName) {
        this.columnName = columnName;
        return this;
    }

    public String getType() {
        return type;
    }

    public ColumnInfo setType(String type) {
        this.type = type;
        return this;
    }

    public int getLength() {
        return length;
    }

    public ColumnInfo setLength(int length) {
        this.length = length;
        return this;
    }

    public int getScale() {
        return scale;
    }

    public ColumnInfo setScale(int scale) {
        this.scale = scale;
        return this;
    }

    public String getComment() {
        return comment;
    }

    public ColumnInfo setComment(String comment) {
        this.comment = comment;
        return this;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    public ColumnInfo setDefaultValue(String defaultValue) {
        this.defaultValue = defaultValue;
        return this;
    }

    public boolean isNullable() {
        return nullable;
    }

    public ColumnInfo setNullable(boolean nullable) {
        this.nullable = nullable;
        return this;
    }

    public boolean isUnique() {
        return unique;
    }

    public ColumnInfo setUnique(boolean unique) {
        this.unique = unique;
        return this;
    }

    public boolean isPrimaryKey() {
        return primaryKey;
    }

    public ColumnInfo setPrimaryKey(boolean primaryKey) {
        this.primaryKey = primaryKey;
        return this;
    }

    public GenerationType getGenerationType() {
        return generationType;
    }

    public ColumnInfo setGenerationType(GenerationType generationType) {
        this.generationType = generationType;
        return this;
    }

    public String getFieldName() {
        return fieldName;
    }

    public ColumnInfo setFieldName(String fieldName) {
        this.fieldName = fieldName;
        return this;
    }

    public Class<?> getJavaType() {
        return javaType;
    }

    public ColumnInfo setJavaType(Class<?> javaType) {
        this.javaType = javaType;
        return this;
    }

    @Override
    public String toString() {
        return "ColumnInfo{" +
                "columnName='" + columnName + '\'' +
                ", type='" + type + '\'' +
                ", length=" + length +
                ", comment='" + comment + '\'' +
                ", primaryKey=" + primaryKey +
                ", unique=" + unique +
                '}';
    }
}
