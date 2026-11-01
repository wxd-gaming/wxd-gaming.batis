package com.wxdgaming.batis.sql.mysql;

import com.wxdgaming.batis.annotation.GenerationType;
import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.IndexInfo;
import com.wxdgaming.batis.model.TableInfo;
import com.wxdgaming.batis.sql.JdbcTemplate;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MySQL 结构 diff 的离线验收测试。
 *
 * <p>覆盖审核修复的几处关键行为，全部用"只记录 DDL 文本"的模板桩完成，不连库：</p>
 * <ol>
 *   <li><b>H7 物理表名</b>：lower_case_table_names=0 的库上，库端表名可能含大写；
 *   缓存 key 用小写查找，但 ALTER 必须拼库端原值，否则会指向一张不存在的表；</li>
 *   <li><b>H1/H2 索引形态</b>：表达式索引、FULLTEXT/SPATIAL、前缀索引、降序索引都超出
 *   {@code @Index} 的表达能力，只能告警跳过，不能拿实体定义去 DROP + 重建（不可逆覆盖）；</li>
 *   <li><b>默认值折叠</b>：实体字面量与 information_schema 的书写形态不同
 *   （{@code 'abc'} vs {@code abc}、{@code 0} vs {@code 0.00}、{@code CURRENT_TIMESTAMP} vs
 *   {@code current_timestamp()}），不折叠就会每次启动对全表刷虚假 MODIFY；</li>
 *   <li><b>自增属性</b>：实体要求 IDENTITY 而库端没有 → 修回来；反过来只告警不动结构。</li>
 * </ol>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-11-01
 */
class MysqlTableAlterHandlerTest {

    /** 只记录 DDL 的模板桩：结构 diff 的全部输出都从这里出来 */
    private static final class RecordingTemplate extends JdbcTemplate {
        final List<String> ddl = new ArrayList<>();

        RecordingTemplate() {
            super(new HikariDataSource());
        }

        @Override
        public int executeDdl(String sql) {
            ddl.add(sql);
            return 0;
        }
    }

    private final MysqlTableAlterHandler handler = new MysqlTableAlterHandler();
    private RecordingTemplate template;

    @BeforeEach
    void setUp() {
        template = new RecordingTemplate();
    }

    // ==================== 夹具 ====================

    private static ColumnInfo col(String name, String type) {
        return new ColumnInfo().setColumnName(name).setFieldName(name).setType(type);
    }

    private static ColumnInfo id(String type, GenerationType generation, String extra) {
        return new ColumnInfo().setColumnName("id").setFieldName("id").setType(type)
                .setPrimaryKey(true).setGenerationType(generation).setNullable(false).setExtra(extra);
    }

    private static TableInfo table(String tableName, String comment, List<ColumnInfo> columns,
                                   List<IndexInfo> indexes) {
        LinkedHashMap<String, ColumnInfo> map = new LinkedHashMap<>();
        for (ColumnInfo column : columns) {
            map.put(column.getColumnName().toLowerCase(java.util.Locale.ROOT), column);
        }
        TableInfo info = new TableInfo();
        info.setTableName(tableName);
        info.setComment(comment);
        info.setColumns(map);
        info.setIndexes(indexes);
        return info;
    }

    private void cache(TableInfo dbTable) {
        HashMap<String, TableInfo> cache = new HashMap<>();
        cache.put(dbTable.getTableName().toLowerCase(java.util.Locale.ROOT), dbTable);
        template.setTableCache(cache);
    }

    private static IndexInfo index(String name, boolean unique, String... columns) {
        return new IndexInfo().setName(name).setUnique(unique).setColumns(List.of(columns));
    }

    private static IndexInfo dbIndex(String name, boolean unique, String indexType,
                                     List<Integer> prefixes, List<Boolean> descending, String... columns) {
        return new IndexInfo().setName(name).setUnique(unique).setIndexType(indexType)
                .setPrefixLengths(prefixes).setDescending(descending)
                .setColumns(new ArrayList<>(java.util.Arrays.asList(columns)));
    }

    private static IndexInfo expressionIndex(String name) {
        List<String> columns = new ArrayList<>();
        columns.add(null);
        return new IndexInfo().setName(name).setColumns(columns).setIndexType("BTREE")
                .setPrefixLengths(List.of(0)).setDescending(List.of(false)).setExpressionIndex(true);
    }

    // ==================== H7：物理表名 ====================

    @Test
    @DisplayName("H7：缓存按小写查找，但 ALTER 必须使用库端真实表名（含大写）")
    void alterUsesPhysicalTableNameFromDatabase() {
        ColumnInfo entityId = id("INT", GenerationType.IDENTITY, null);
        ColumnInfo dbId = id("int(11)", GenerationType.NONE, "auto_increment");
        TableInfo entityTable = table("t_user", "用户表", List.of(entityId), List.of());
        TableInfo dbTable = table("T_User", null, List.of(dbId), List.of());
        cache(dbTable);

        handler.doAction(template, entityTable);

        assertEquals(1, template.ddl.size(), "表注释差异应产生一条 ALTER: " + template.ddl);
        String sql = template.ddl.get(0);
        assertTrue(sql.contains("ALTER TABLE `T_User`"), "必须拼库端原值: " + sql);
        assertFalse(sql.contains("ALTER TABLE `t_user`"), "小写表名会指向不存在的表: " + sql);
        assertTrue(sql.contains("COMMENT='用户表'"), "表级注释应同步: " + sql);
        assertFalse(sql.contains("MODIFY COLUMN"), "自增与类型都等价时不该改列: " + sql);
    }

    @Test
    @DisplayName("完全一致时一条 DDL 都不发（幂等的底线）")
    void identicalStructureProducesNoDdl() {
        ColumnInfo entityId = id("INT", GenerationType.IDENTITY, null);
        ColumnInfo dbId = id("int(11)", GenerationType.NONE, "auto_increment");
        TableInfo entityTable = table("t_user", null, List.of(entityId), List.of());
        TableInfo dbTable = table("t_user", null, List.of(dbId), List.of());
        cache(dbTable);

        handler.doAction(template, entityTable);

        assertTrue(template.ddl.isEmpty(), "无差异不应下发 DDL: " + template.ddl);
    }

    @Test
    @DisplayName("实体要求 IDENTITY、库端没有自增：MODIFY 修回来（否则主键回写为 null）")
    void missingAutoIncrementIsRepaired() {
        ColumnInfo entityId = id("BIGINT", GenerationType.IDENTITY, null);
        ColumnInfo dbId = id("bigint(20)", GenerationType.NONE, "");
        TableInfo entityTable = table("t_a", null, List.of(entityId), List.of());
        cache(table("t_a", null, List.of(dbId), List.of()));

        handler.doAction(template, entityTable);

        assertEquals(1, template.ddl.size());
        assertTrue(template.ddl.get(0).contains("MODIFY COLUMN `id` BIGINT NOT NULL AUTO_INCREMENT"),
                template.ddl.get(0));
    }

    @Test
    @DisplayName("库端有自增、实体没声明：只告警，不摘除存量表的自增属性")
    void unexpectedAutoIncrementIsNotRemoved() {
        ColumnInfo entityId = id("BIGINT", GenerationType.NONE, null);
        ColumnInfo dbId = id("bigint(20)", GenerationType.NONE, "auto_increment");
        TableInfo entityTable = table("t_a", null, List.of(entityId), List.of());
        cache(table("t_a", null, List.of(dbId), List.of()));

        handler.doAction(template, entityTable);

        assertTrue(template.ddl.isEmpty(), "反方向不应产生 MODIFY: " + template.ddl);
    }

    @Test
    @DisplayName("默认值书写形态差异全部折叠：不再对未变化的列反复刷 MODIFY")
    void equivalentDefaultLiteralsAreFolded() {
        List<ColumnInfo> entityCols = List.of(
                col("id", "INT"),
                new ColumnInfo().setColumnName("s").setFieldName("s").setType("VARCHAR(64)").setDefaultValue("'abc'"),
                new ColumnInfo().setColumnName("d").setFieldName("d").setType("DECIMAL(10,2)").setDefaultValue("0"),
                new ColumnInfo().setColumnName("t").setFieldName("t").setType("DATETIME").setDefaultValue("CURRENT_TIMESTAMP"),
                new ColumnInfo().setColumnName("b").setFieldName("b").setType("TINYINT(1)").setDefaultValue("true"));
        List<ColumnInfo> dbCols = List.of(
                col("id", "int(11)"),
                new ColumnInfo().setColumnName("s").setFieldName("s").setType("varchar(64)").setDefaultValue("abc"),
                new ColumnInfo().setColumnName("d").setFieldName("d").setType("decimal(10,2)").setDefaultValue("0.00"),
                new ColumnInfo().setColumnName("t").setFieldName("t").setType("datetime").setDefaultValue("current_timestamp()"),
                new ColumnInfo().setColumnName("b").setFieldName("b").setType("tinyint(1)").setDefaultValue("1"));
        TableInfo entityTable = table("t_defaults", null, entityCols, List.of());
        cache(table("t_defaults", null, dbCols, List.of()));

        handler.doAction(template, entityTable);

        assertTrue(template.ddl.isEmpty(), "等价默认值不应触发 MODIFY: " + template.ddl);
    }

    // ==================== H1/H2：索引形态 ====================

    @Test
    @DisplayName("H1/H2：表达式/前缀/降序/全文索引一律告警跳过，不拿实体定义覆盖库端")
    void specialIndexFormsAreNeverRebuilt() {
        IndexInfo entityExpr = index("idx_expr", false, "name");
        IndexInfo entityPrefix = index("idx_prefix", false, "name");
        IndexInfo entityDesc = index("idx_desc", false, "name");
        IndexInfo entityFull = index("idx_full", false, "body");

        TableInfo entityTable = table("t_a", null,
                List.of(id("INT", GenerationType.IDENTITY, null), col("name", "VARCHAR(64)"), col("body", "TEXT")),
                List.of(entityExpr, entityPrefix, entityDesc, entityFull));

        TableInfo dbTable = table("t_a", null,
                List.of(id("int(11)", GenerationType.NONE, "auto_increment"), col("name", "varchar(64)"), col("body", "text")),
                List.of(
                        expressionIndex("idx_expr"),
                        dbIndex("idx_prefix", false, "BTREE", List.of(10), List.of(false), "name"),
                        dbIndex("idx_desc", false, "BTREE", List.of(0), List.of(true), "name"),
                        dbIndex("idx_full", false, "FULLTEXT", List.of(0), List.of(false), "body")));
        cache(dbTable);

        handler.doAction(template, entityTable);

        String all = String.join("\n", template.ddl);
        assertFalse(all.contains("DROP INDEX"), "超表达能力的索引不能被删: " + all);
        assertFalse(all.contains("ADD INDEX"), "也不能被重建: " + all);
        assertTrue(template.ddl.isEmpty(), "除索引外无差异，不应有 DDL: " + all);
    }

    @Test
    @DisplayName("普通 BTREE 索引定义变化仍然 DROP + 重建")
    void ordinaryIndexChangeIsRebuilt() {
        TableInfo entityTable = table("t_a", null,
                List.of(id("INT", GenerationType.IDENTITY, null), col("name", "VARCHAR(64)")),
                List.of(index("idx_name", false, "name")));
        TableInfo dbTable = table("t_a", null,
                List.of(id("int(11)", GenerationType.NONE, "auto_increment"), col("name", "varchar(64)"),
                        col("old_col", "VARCHAR(64)")),
                List.of(dbIndex("idx_name", false, "BTREE", List.of(0), List.of(false), "old_col")));
        // 库端多出来的 old_col 会触发 ADD COLUMN，这里只关心索引子句
        cache(dbTable);

        handler.doAction(template, entityTable);

        assertEquals(1, template.ddl.size());
        String sql = template.ddl.get(0);
        assertTrue(sql.contains("DROP INDEX `idx_name`"), sql);
        assertTrue(sql.contains("ADD INDEX `idx_name` (`name`)"), sql);
    }

    @Test
    @DisplayName("dropRedundantIndexes=false（默认）：实体没声明的索引只告警、不删除")
    void undeclaredIndexesAreKeptByDefault() {
        TableInfo entityTable = table("t_a", null,
                List.of(id("INT", GenerationType.IDENTITY, null)), List.of());
        TableInfo dbTable = table("t_a", null,
                List.of(id("int(11)", GenerationType.NONE, "auto_increment")),
                List.of(dbIndex("idx_legacy", false, "BTREE", List.of(0), List.of(false), "id")));
        cache(dbTable);

        handler.doAction(template, entityTable);

        assertTrue(template.ddl.isEmpty(), "默认配置下不应删除实体未声明的索引: " + template.ddl);
    }

    @Test
    @DisplayName("库端没有这张表（缓存未命中）时直接返回，不生成 ALTER")
    void missingDatabaseTableIsSkipped() {
        TableInfo entityTable = table("t_new", null,
                List.of(id("INT", GenerationType.IDENTITY, null)), List.of());
        template.setTableCache(new HashMap<>());

        handler.doAction(template, entityTable);

        assertTrue(template.ddl.isEmpty());
    }
}
