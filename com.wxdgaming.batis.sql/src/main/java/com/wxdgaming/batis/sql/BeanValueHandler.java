package com.wxdgaming.batis.sql;

import com.wxdgaming.batis.model.ColumnInfo;

/**
 * Bean ↔ DB 双向值转换处理器接口
 * <p>每个数据库方言提供对应的实现，隔离类型转换差异（如 JSONB/TIMESTAMPTZ/UUID 等）。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-31
 */
public interface BeanValueHandler {

    /** Bean → DB：将 Java 值转为 JDBC 参数 */
    Object convertToDbValue(Object javaValue, ColumnInfo col);

    /** DB → Bean：将 ResultSet 读出的值转为 Java 对象 */
    Object convertFromDbValue(Object dbValue, ColumnInfo col);

    /** 通过 getter 读取 bean 字段值（纯反射，所有方言相同） */
    Object readFieldValue(Object bean, ColumnInfo col);

    /** 通过 setter 写入 bean 字段值（纯反射，所有方言相同） */
    void writeFieldValue(Object bean, ColumnInfo col, Object value);

    /** 将自增主键 Number 回写到 bean（纯反射，所有方言相同） */
    void writeGeneratedKey(Object bean, ColumnInfo col, Number genId);
}
