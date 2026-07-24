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
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-24 17:33
 **/
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class Handlers {

    /**
     * 读取第一个单元格的值（第一行第一列），返回 Object
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
     * 读取所有行，每行用列标签作为 key 包装为 JSONObject，返回 List<JSONObject>
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

    /**
     * 将当前行转换为 JSONObject，key 使用 ResultSetMetaData.getColumnLabel()
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
