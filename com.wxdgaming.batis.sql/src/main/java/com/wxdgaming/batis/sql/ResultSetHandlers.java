package com.wxdgaming.batis.sql;

import com.alibaba.fastjson.JSONObject;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 内置的 ResultSetHandler 工厂方法
 * <p>分成两类，选择依据是"调用方有没有实体类型"：</p>
 * <ul>
 *   <li><b>方言无关</b>：{@code firstCell} / {@code row} / {@code list} 直接把列值装进
 *       JSONObject 或取原始 Object，不经过任何类型转换，适合统计、元数据查询与临时排查；</li>
 *   <li><b>方言相关</b>：{@code bean} / {@code beanList} 只是入口，真正的行映射委托给
 *       {@link JdbcTemplate} 上注册的 {@link SqlDbToBeanHandler}，因此 JSON、枚举、时间等
 *       差异不会漏进这个工厂类。</li>
 * </ul>
 * <p>方言无关那一类拿到的是驱动原样返回的对象（数值可能是 Long 或 BigDecimal、
 * 时间是 Timestamp），不要把它当成实体来用——需要类型化字段就走 bean 映射路径。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-24 17:33
 **/
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ResultSetHandlers {

    /**
     * 读取第一个单元格的值（第一行第一列），返回 Object
     * <p>计数、求和之类"一个数"的查询都走这里；无行返回 null，
     * 调用方要区分"没有数据"和"数据本身就是 null"。</p>
     */
    public static ResultSetHandler<Object> firstCell() {
        return rs -> {
            if (rs.next()) {
                return rs.getObject(1);
            }
            return null;
        };
    }

    /**
     * 读取第一个单元格的值（第一行第一列），按指定类型返回
     * <p>类型转换交由驱动完成，跨方言不一定成立（同一列在不同驱动下可绑定的类型不同），
     * 因此只用于确定支持的类型；需要宽松取值时用无类型的重载。</p>
     *
     * @param type 期望的 Java 类型
     * @param <T>  期望类型
     */
    public static <T> ResultSetHandler<T> firstCell(Class<T> type) {
        return rs -> {
            if (rs.next()) {
                return rs.getObject(1, type);
            }
            return null;
        };
    }

    /**
     * 读取一行，用列标签（ColumnLabel）作为 key 包装为 JSONObject
     * <p>key 取的是列标签而非列名，所以 SQL 里写的别名会直接决定 JSON 的键；
     * 同名列必须用别名区分，否则后者覆盖前者。</p>
     */
    public static ResultSetHandler<JSONObject> row() {
        return rs -> {
            if (rs.next()) {
                return toJsonObject(rs);
            }
            return null;
        };
    }

    /**
     * 读取所有行，每行用列标签作为 key 包装为 JSONObject，返回 List&lt;JSONObject&gt;
     * <p>全量物化，适合小结果集；大表请用实体查询加分页。</p>
     */
    public static ResultSetHandler<List<JSONObject>> list() {
        return rs -> {
            List<JSONObject> list = new ArrayList<>();
            while (rs.next()) {
                list.add(toJsonObject(rs));
            }
            return list;
        };
    }

    // ======================== Bean 映射 ========================

    /**
     * 读取一行，映射为指定实体类的 Bean 对象（使用 JdbcTemplate 上注册的方言处理器）
     * <p>装配缺失在<em>取 handler 的那一刻</em>就报出可读的
     * UnsupportedOperationException，而不是等结果集回来了才抛难懂的映射错误；
     * 这一层"检查提前"是所有 {@code requireXxx} 的共同理由。</p>
     * <p>多于一条时只取第一条且不报错，唯一性语义请交给查询 DSL 的 {@code one()}。</p>
     *
     * @param template    JdbcTemplate 实例（从中获取 {@link SqlDbToBeanHandler}）
     * @param entityClass 实体类
     * @param <T>         实体类型
     */
    public static <T> ResultSetHandler<T> bean(JdbcTemplate template, Class<T> entityClass) {
        SqlDbToBeanHandler handler = template.getSqlDbToBeanHandler();
        if (handler == null) {
            throw new UnsupportedOperationException(
                    "未注册 sqlDbToBeanHandler，请调用 JdbcTemplate#setSqlDbToBeanHandler()");
        }
        return rs -> {
            if (rs.next()) {
                return (T) handler.toBean(rs, entityClass);
            }
            return null;
        };
    }

    /**
     * 读取所有行，每行映射为指定实体类的 Bean 对象（使用 JdbcTemplate 上注册的方言处理器）
     * <p>空结果返回空 List；值转换完全依赖注册的方言 {@link SqlDbToBeanHandler}，
     * 本方法不做任何类型猜测，因此换数据库不需要改这里。</p>
     *
     * @param template    JdbcTemplate 实例（从中获取 {@link SqlDbToBeanHandler}）
     * @param entityClass 实体类
     * @param <T>         实体类型
     */
    public static <T> ResultSetHandler<List<T>> beanList(JdbcTemplate template, Class<T> entityClass) {
        SqlDbToBeanHandler handler = template.getSqlDbToBeanHandler();
        if (handler == null) {
            throw new UnsupportedOperationException(
                    "未注册 sqlDbToBeanHandler，请调用 JdbcTemplate#setSqlDbToBeanHandler()");
        }
        return rs -> {
            List<T> list = new ArrayList<>();
            while (rs.next()) {
                list.add((T) handler.toBean(rs, entityClass));
            }
            return list;
        };
    }

    // ======================== 内部工具 ========================

    /**
     * 将当前行转换为 JSONObject，key 使用 ResultSetMetaData.getColumnLabel()
     * <p>只搬运不加工：值保持驱动原样返回的类型，方言差异留给实体映射路径处理。</p>
     */
    private static JSONObject toJsonObject(ResultSet rs) throws SQLException {
        ResultSetMetaData metaData = rs.getMetaData();
        int columnCount = metaData.getColumnCount();
        JSONObject json = new JSONObject(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            String label = metaData.getColumnLabel(i);
            Object value = rs.getObject(i);
            json.put(label, value);
        }
        return json;
    }

}
