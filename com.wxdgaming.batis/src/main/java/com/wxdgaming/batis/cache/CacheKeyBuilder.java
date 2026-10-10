package com.wxdgaming.batis.cache;

import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.TableInfo;
import com.wxdgaming.batis.parser.EntityParser;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

/**
 * 缓存 Key 构建工具
 * <p>Key 格式: {@code tableName:pkValue}，通过表名前缀天然隔离不同实体类型。</p>
 * <p>表名统一转小写，消除不同数据库/连接对表名大小写敏感性差异带来的"同实体不同 Key"问题；
 * 前缀（含结尾冒号）供上层按表批量摘取脏数据复用，故临时 Key 也必须保留该前缀才能命中前缀过滤。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-08-05
 */
public final class CacheKeyBuilder {

    /**
     * 根据实体类和主键值生成缓存 Key
     */
    public static String buildKey(Class<?> entityClass, Object id) {
        TableInfo ti = EntityParser.parse(entityClass);
        return ti.getTableName().toLowerCase() + ":" + idToString(id);
    }

    /**
     * 从实体 bean 中提取主键值生成缓存 Key
     */
    public static String buildKey(Object bean) {
        TableInfo ti = EntityParser.parse(bean.getClass());
        Object pk = extractPk(ti, bean);
        return ti.getTableName().toLowerCase() + ":" + idToString(pk);
    }

    /**
     * 获取实体类对应的缓存 Key 前缀
     * <p>用于按表过滤脏数据。格式: {@code tablename:}</p>
     */
    public static String keyPrefix(Class<?> entityClass) {
        TableInfo ti = EntityParser.parse(entityClass);
        return ti.getTableName().toLowerCase() + ":";
    }

    /**
     * 从实体 bean 中提取主键值（供上层判断主键是否已生成）。
     *
     * @return 主键值；单主键直接返回，复合主键返回 {@code Object[]}；实体未定义主键时返回 null
     */
    public static Object extractPkValue(Object bean) {
        TableInfo ti = EntityParser.parse(bean.getClass());
        List<Object> values = new ArrayList<>();
        for (ColumnInfo col : ti.getColumns().values()) {
            if (col.isPrimaryKey() && col.getReadMethod() != null) {
                try {
                    values.add(col.getReadMethod().invoke(bean));
                } catch (IllegalAccessException | InvocationTargetException e) {
                    throw new RuntimeException("提取主键失败: " + col.getFieldName(), e);
                }
            }
        }
        if (values.isEmpty()) return null;
        return values.size() == 1 ? values.getFirst() : values.toArray();
    }

    /** 从实体 bean 中提取所有主键字段的值 */
    private static Object extractPk(TableInfo ti, Object bean) {
        List<Object> values = new ArrayList<>();
        for (ColumnInfo col : ti.getColumns().values()) {
            if (col.isPrimaryKey() && col.getReadMethod() != null) {
                try {
                    values.add(col.getReadMethod().invoke(bean));
                } catch (IllegalAccessException | InvocationTargetException e) {
                    throw new RuntimeException("提取主键失败: " + col.getFieldName(), e);
                }
            }
        }
        if (values.isEmpty())
            throw new IllegalArgumentException(bean.getClass().getName() + " 未定义主键");
        return values.size() == 1 ? values.getFirst() : values.toArray();
    }

    /**
     * 主键值转字符串。
     * <p>复合主键逐分量"长度:值"前缀拼接：单纯用 {@code _} 连接会因分量本身含下划线、
     * 或分量数量不同而歧义（如 {@code [1,23]} 与 {@code [1,2,3]}、{@code ["1_2",3]} 都拼成 {@code 1_23}）。
     * 长度前缀让分量边界无歧义；单主键仍为 {@code id.toString()} 原样，与复合形态不互通。</p>
     * <p>主键为 null 时直接抛异常：null 无法定位缓存行，静默映射成字符串 "null"
     * 会与真实主键值 "null" 撞车，还把"主键未生成"的错误藏成一次缓存命中。</p>
     */
    private static String idToString(Object id) {
        if (id == null) throw new IllegalArgumentException("主键值不能为 null，无法构建缓存 Key");
        if (id instanceof Object[] arr) {
            StringBuilder sb = new StringBuilder();
            for (Object component : arr) {
                String s = String.valueOf(component);
                sb.append(s.length()).append(':').append(s);
            }
            return sb.toString();
        }
        return id.toString();
    }

    private CacheKeyBuilder() {}
}
