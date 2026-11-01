package com.wxdgaming.batis.sql.mysql;

import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.IndexInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MySQL 元数据行映射测试（离线，用假 ResultSet）。
 *
 * <p>这一层是结构 diff 的"原料车间"：列的类型/可空/默认值/自增、索引的前缀长度/降序/索引类型/
 * 表达式标记，任何一项读错都会让上游 {@link MysqlTableAlterHandler} 做出错误判断——
 * 要么让漂移永远发现不了（静默），要么反复发无谓的 MODIFY/重建（锁表）。</p>
 *
 * <p>重点覆盖 H1/H2 修复引入的字段：{@code SUB_PART}、{@code INDEX_TYPE}、{@code COLLATION}、
 * {@code COLUMN_NAME IS NULL}（表达式索引）。这些形态在真库集成测试里不容易稳定构造，
 * 用假结果集反而能把每一列都点名断言。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-11-01
 */
class MysqlDbMetaHandlerTest {

    private final MysqlDbMetaHandler handler = new MysqlDbMetaHandler();

    /**
     * 行数据用 Map 表示，列标签即 key；{@code null} 值会同时让 getInt 返回 0 且 wasNull() 为 true。
     */
    private static ResultSet resultSet(List<Map<String, Object>> rows) {
        int[] cursor = {-1};
        Object[] lastValue = {null};
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "next":
                            cursor[0]++;
                            return cursor[0] < rows.size();
                        case "getString": {
                            Object value = valueAt(rows, cursor[0], args[0]);
                            lastValue[0] = value;
                            return value == null ? null : value.toString();
                        }
                        case "getInt": {
                            Object value = valueAt(rows, cursor[0], args[0]);
                            lastValue[0] = value;
                            return value == null ? 0 : ((Number) value).intValue();
                        }
                        case "wasNull":
                            return lastValue[0] == null;
                        case "close":
                            return null;
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
    }

    private static Object valueAt(List<Map<String, Object>> rows, int index, Object label) {
        if (index < 0 || index >= rows.size()) return null;
        return rows.get(index).get((String) label);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == void.class) return null;
        if (type == long.class) return 0L;
        if (type == double.class) return 0d;
        if (type == float.class) return 0f;
        if (type == char.class) return (char) 0;
        return 0;
    }

    private static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    // ==================== 列映射 ====================

    @Test
    @DisplayName("列映射：PRI/自增/字符集/NULL 默认值/空注释都按库端原义落到 ColumnInfo")
    void columnRowsMapToColumnInfo() throws Exception {
        List<Map<String, Object>> rows = List.of(
                row("COLUMN_NAME", "id",
                        "COLUMN_TYPE", "bigint(20)",
                        "IS_NULLABLE", "NO",
                        "COLUMN_DEFAULT", null,
                        "COLUMN_COMMENT", null,
                        "COLUMN_KEY", "PRI",
                        "EXTRA", "auto_increment",
                        "CHARACTER_SET_NAME", null,
                        "COLLATION_NAME", null),
                row("COLUMN_NAME", "name",
                        "COLUMN_TYPE", "varchar(64)",
                        "IS_NULLABLE", "YES",
                        "COLUMN_DEFAULT", "NULL",
                        "COLUMN_COMMENT", "昵称",
                        "COLUMN_KEY", "",
                        "EXTRA", null,
                        "CHARACTER_SET_NAME", "utf8mb4",
                        "COLLATION_NAME", "utf8mb4_general_ci"));

        List<ColumnInfo> columns = handler.columnListHandler().handle(resultSet(rows));

        assertEquals(2, columns.size());
        ColumnInfo id = columns.get(0);
        assertEquals("id", id.getColumnName());
        assertEquals("bigint(20)", id.getType(), "原始 COLUMN_TYPE 必须原样带出，交给 normalize 归一");
        assertTrue(id.isPrimaryKey());
        assertFalse(id.isNullable());
        assertNull(id.getDefaultValue(), "SQL NULL 默认值保持 null，不能变成字符串");
        assertEquals("", id.getComment(), "COLUMN_COMMENT 为 null 时归一为空串，避免 diff 判空分歧");
        assertEquals("auto_increment", id.getExtra());
        assertNull(id.getCharacterSet());

        ColumnInfo name = columns.get(1);
        assertFalse(name.isPrimaryKey());
        assertTrue(name.isNullable());
        assertEquals("NULL", name.getDefaultValue(), "MySQL 用字符串 NULL 表示 DEFAULT NULL，需保留语义");
        assertEquals("昵称", name.getComment());
        assertEquals("", name.getExtra(), "EXTRA 为 null 时归一为空串");
        assertEquals("utf8mb4", name.getCharacterSet());
        assertEquals("utf8mb4_general_ci", name.getCollation());
    }

    // ==================== 索引映射 ====================

    private static List<Map<String, Object>> indexRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        // PRIMARY 必须先出现，验证它会整体跳过
        rows.add(row("INDEX_NAME", "PRIMARY", "COLUMN_NAME", "id", "NON_UNIQUE", 0,
                "SEQ_IN_INDEX", 1, "SUB_PART", null, "INDEX_TYPE", "BTREE", "COLLATION", "A"));
        rows.add(row("INDEX_NAME", "idx_name", "COLUMN_NAME", "name", "NON_UNIQUE", 1,
                "SEQ_IN_INDEX", 1, "SUB_PART", null, "INDEX_TYPE", "BTREE", "COLLATION", "A"));
        rows.add(row("INDEX_NAME", "idx_name", "COLUMN_NAME", "age", "NON_UNIQUE", 1,
                "SEQ_IN_INDEX", 2, "SUB_PART", null, "INDEX_TYPE", "BTREE", "COLLATION", "A"));
        rows.add(row("INDEX_NAME", "idx_prefix", "COLUMN_NAME", "name", "NON_UNIQUE", 1,
                "SEQ_IN_INDEX", 1, "SUB_PART", 10, "INDEX_TYPE", "BTREE", "COLLATION", "A"));
        rows.add(row("INDEX_NAME", "idx_desc", "COLUMN_NAME", "name", "NON_UNIQUE", 1,
                "SEQ_IN_INDEX", 1, "SUB_PART", null, "INDEX_TYPE", "BTREE", "COLLATION", "A"));
        rows.add(row("INDEX_NAME", "idx_desc", "COLUMN_NAME", "updated_at", "NON_UNIQUE", 1,
                "SEQ_IN_INDEX", 2, "SUB_PART", null, "INDEX_TYPE", "BTREE", "COLLATION", "D"));
        rows.add(row("INDEX_NAME", "idx_expr", "COLUMN_NAME", null, "NON_UNIQUE", 1,
                "SEQ_IN_INDEX", 1, "SUB_PART", null, "INDEX_TYPE", "BTREE", "COLLATION", "A"));
        rows.add(row("INDEX_NAME", "idx_full", "COLUMN_NAME", "body", "NON_UNIQUE", 1,
                "SEQ_IN_INDEX", 1, "SUB_PART", null, "INDEX_TYPE", "FULLTEXT", "COLLATION", null));
        rows.add(row("INDEX_NAME", "idx_custom", "COLUMN_NAME", "code", "NON_UNIQUE", 0,
                "SEQ_IN_INDEX", 1, "SUB_PART", null, "INDEX_TYPE", "HASH", "COLLATION", null));
        return rows;
    }

    @Test
    @DisplayName("索引映射：PRIMARY 跳过、同一索引按 SEQ 聚合、SUB_PART/COLLATION/INDEX_TYPE 逐列收集")
    void indexRowsAreGroupedAndAnnotated() throws Exception {
        List<IndexInfo> indexes = handler.indexListHandler().handle(resultSet(indexRows()));

        assertEquals(List.of("idx_name", "idx_prefix", "idx_desc", "idx_expr", "idx_full", "idx_custom"),
                indexes.stream().map(IndexInfo::getName).toList(),
                "PRIMARY 必须跳过，其余保持查询顺序");

        IndexInfo name = indexes.get(0);
        assertEquals(List.of("name", "age"), name.getColumns(), "复合索引列必须按 SEQ_IN_INDEX 保序");
        assertEquals(List.of(0, 0), name.getPrefixLengths(), "SUB_PART 为 NULL 归一成 0");
        assertEquals(List.of(false, false), name.getDescending());
        assertEquals("BTREE", name.getIndexType());
        assertFalse(name.isUnique(), "NON_UNIQUE=1 是普通索引");
        assertFalse(name.isExpressionIndex());

        assertEquals(List.of(10), indexes.get(1).getPrefixLengths(), "前缀索引的 SUB_PART 必须保留");
        assertEquals(List.of(false, true), indexes.get(2).getDescending(), "COLLATION=D 标记降序列");

        IndexInfo expr = indexes.get(3);
        assertTrue(expr.isExpressionIndex(), "COLUMN_NAME 为 null 的行代表表达式（函数）索引");
        assertEquals(1, expr.getColumns().size());
        assertNull(expr.getColumns().get(0));

        assertEquals("FULLTEXT", indexes.get(4).getIndexType(), "非 BTREE 类型必须带出，供上层跳过自动重建");
        assertTrue(indexes.get(5).isUnique(), "NON_UNIQUE=0 是唯一索引");
        assertEquals("HASH", indexes.get(5).getIndexType());
    }

    @Test
    @DisplayName("空结果集：列与索引都返回空列表，不返回 null")
    void emptyResultSetsYieldEmptyLists() throws Exception {
        assertTrue(handler.columnListHandler().handle(resultSet(List.of())).isEmpty());
        assertTrue(handler.indexListHandler().handle(resultSet(List.of())).isEmpty());
    }

    @Test
    @DisplayName("表清单映射：TABLE_COMMENT 为 null 时归一为空串")
    void tableRowsMapToTableInfo() throws Exception {
        List<Map<String, Object>> rows = List.of(
                row("TABLE_NAME", "t_a", "TABLE_COMMENT", null),
                row("TABLE_NAME", "t_b", "TABLE_COMMENT", "注释"));

        var tables = handler.tableListHandler().handle(resultSet(rows));

        assertEquals(2, tables.size());
        assertEquals("t_a", tables.get(0).getTableName());
        assertEquals("", tables.get(0).getComment());
        assertEquals("注释", tables.get(1).getComment());
    }
}
