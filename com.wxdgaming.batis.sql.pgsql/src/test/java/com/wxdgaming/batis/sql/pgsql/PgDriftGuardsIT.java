package com.wxdgaming.batis.sql.pgsql;

import com.alibaba.fastjson2.JSONObject;
import com.wxdgaming.batis.annotation.Column;
import com.wxdgaming.batis.annotation.GeneratedValue;
import com.wxdgaming.batis.annotation.GenerationType;
import com.wxdgaming.batis.annotation.Id;
import com.wxdgaming.batis.annotation.Index;
import com.wxdgaming.batis.annotation.Table;
import com.wxdgaming.batis.sql.QueryRunner;
import com.wxdgaming.batis.sql.ResultSetHandlers;
import com.zaxxer.hikari.HikariConfig;
import lombok.Getter;
import lombok.Setter;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PG 结构守护回归测试（真库：嵌入式 PostgreSQL）—— 锁定一批"静默失败型"缺陷的修复行为。
 * <p>与 {@code PgSchemaDriftIT} 的分工：那边锁"全类型档位的零漂移"，本类锁具体的边界场景，
 * 每个用例对应一个曾经真实存在过的缺陷：</p>
 * <ol>
 *   <li>{@code @Column(type="timestamptz")} 短写与 format_type 长写折到同一比较键，重复同步不重写整表；</li>
 *   <li>timestamp → timestamptz 的 ALTER 带 {@code USING ... AT TIME ZONE}，存量行按会话时区换算、可收敛；</li>
 *   <li>实体声明的索引名被<b>别的表</b>占用时告警并跳过，而不是被 IF NOT EXISTS 静默吞掉；</li>
 *   <li>部分索引（回读时被排除）不受 dropRedundantIndexes 波及，同步后仍在；</li>
 *   <li>列级非默认 collation 只告警不"修回"，同步后保持原样、零虚假 ALTER。</li>
 * </ol>
 * <p>relfilenode 是"整表是否被重写"的硬证据：PG 的 {@code ALTER COLUMN TYPE} 会重建堆文件，
 * relfilenode 跟着变；零漂移断言因此直接比对 relfilenode，比"快照没变化"更有区分度。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-15
 **/
@TestMethodOrder(OrderAnnotation.class)
class PgDriftGuardsIT {

    @RegisterExtension
    static PgsqlExtension pg = new PgsqlExtension();

    static PgsqlTemplate template;

    static PgsqlTemplate template() {
        if (template != null) return template;
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(pg.getJdbcUrl());
        config.setUsername(pg.getUsername());
        config.setPassword(pg.getPassword());
        config.setDriverClassName("org.postgresql.Driver");
        template = new PgsqlTemplate(config);
        return template;
    }

    /** 整表重写的硬证据：ALTER COLUMN TYPE 会重建堆文件，relfilenode 跟着变 */
    private static long relfilenode(String table) {
        try (QueryRunner qr = new QueryRunner(template())) {
            Long node = qr.query(
                    "SELECT c.relfilenode FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                            + "WHERE n.nspname = current_schema() AND c.relname = ?",
                    new Object[]{table}, rs -> rs.next() ? rs.getLong(1) : null);
            if (node == null) throw new AssertionError("表不存在: " + table);
            return node;
        }
    }

    /** 某列在 information_schema 里的 data_type（库端规范拼写） */
    private static String dataTypeOf(String table, String column) {
        try (QueryRunner qr = new QueryRunner(template())) {
            return qr.query(
                    "SELECT data_type FROM information_schema.columns "
                            + "WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?",
                    new Object[]{table, column}, rs -> rs.next() ? rs.getString(1) : null);
        }
    }

    /** 索引的属主表名；索引不存在返回 null（PG 索引名 schema 级唯一，按名即可定位） */
    private static String indexOwner(String indexName) {
        try (QueryRunner qr = new QueryRunner(template())) {
            return qr.query(
                    "SELECT t.relname FROM pg_index ix "
                            + "JOIN pg_class ic ON ic.oid = ix.indexrelid "
                            + "JOIN pg_class t ON t.oid = ix.indrelid "
                            + "JOIN pg_namespace n ON n.oid = ic.relnamespace "
                            + "WHERE n.nspname = current_schema() AND ic.relname = ?",
                    new Object[]{indexName}, rs -> rs.next() ? rs.getString(1) : null);
        }
    }

    /** 本表当前的普通索引名清单（不含主键支撑索引；部分/表达式索引按名称可见） */
    private static List<String> indexNames(String table) {
        try (QueryRunner qr = new QueryRunner(template())) {
            List<JSONObject> rows = qr.query(
                    "SELECT ic.relname AS index_name FROM pg_index ix "
                            + "JOIN pg_class t ON t.oid = ix.indrelid "
                            + "JOIN pg_class ic ON ic.oid = ix.indexrelid "
                            + "JOIN pg_namespace n ON n.oid = t.relnamespace "
                            + "WHERE n.nspname = current_schema() AND t.relname = ? AND NOT ix.indisprimary "
                            + "ORDER BY ic.relname",
                    new Object[]{table}, ResultSetHandlers.list());
            return rows.stream().map(row -> row.getString("index_name")).collect(Collectors.toList());
        }
    }

    /**
     * 用例①：{@code @Column(type="timestamptz")} 建表后重复同步零漂移。
     * <p>曾经的缺陷：实体侧比较键是短写 {@code timestamptz}，库端 format_type 回读是长写
     * {@code timestamp with time zone}，两键永不相等——每次 checkTable 都虚发一条
     * ALTER COLUMN TYPE，而 PG 的改类型是整表重写，等于每次启动复制一遍全表。
     * relfilenode 不变是"没有发生整表重写"的最硬证据。</p>
     */
    @Test
    @Order(1)
    void timestamptzShortFormConvergesWithoutRewrite() {
        PgsqlTemplate tpl = template();
        tpl.dropTable(PgTzGuardEntity.class);
        tpl.checkTable(PgTzGuardEntity.class);

        assertEquals("timestamp with time zone", dataTypeOf("t_pg_tz_guard", "seen_at"),
                "短写 timestamptz 落库后回读为 format_type 长写");

        long filenode = relfilenode("t_pg_tz_guard");
        tpl.clearTableCache();
        tpl.checkTable(PgTzGuardEntity.class);
        tpl.clearTableCache();
        tpl.checkTable(PgTzGuardEntity.class);
        assertEquals(filenode, relfilenode("t_pg_tz_guard"),
                "短写与长写必须折到同一比较键：重复同步不应重写整表");
    }

    /**
     * 用例②：timestamp → timestamptz 的真实漂移带 USING 转换，存量行按会话时区换算，且可收敛。
     * <p>曾经的缺陷：两个方向（timestamp↔timestamptz）都不存在隐式赋值转换，
     * 不带 USING 的 ALTER 直接被 PG 拒绝（42804），漂移永远修不回来。
     * 断言三层：① ALTER 真的发生了（relfilenode 变了）；② 存量行的瞬间没变
     * （按会话时区解释原值）；③ 第二次同步收敛（relfilenode 不再变）。</p>
     */
    @Test
    @Order(2)
    void timestampToTimestamptzAltersWithUsing() {
        PgsqlTemplate tpl = template();
        try (QueryRunner qr = new QueryRunner(tpl)) {
            qr.update("DROP TABLE IF EXISTS \"t_pg_tz_migrate\"");
            qr.update("CREATE TABLE \"t_pg_tz_migrate\" (id BIGINT PRIMARY KEY, seen_at TIMESTAMP)");
            qr.update("INSERT INTO \"t_pg_tz_migrate\" (id, seen_at) VALUES (1, '2026-01-01 12:00:00')");
        }
        assertEquals("timestamp without time zone", dataTypeOf("t_pg_tz_migrate", "seen_at"));

        long before = relfilenode("t_pg_tz_migrate");
        tpl.clearTableCache();
        tpl.checkTable(PgTzMigrateEntity.class);

        assertEquals("timestamp with time zone", dataTypeOf("t_pg_tz_migrate", "seen_at"),
                "真实漂移必须被检出并改回实体声明");
        long migrated = relfilenode("t_pg_tz_migrate");
        assertTrue(migrated != before, "改类型是整表重写，relfilenode 必须变（证明 ALTER 真的执行了）");

        // 存量行的瞬间保持：原值按会话时区解释（与 USING ... AT TIME ZONE current_setting('TimeZone') 同口径）
        try (QueryRunner qr = new QueryRunner(tpl)) {
            Boolean preserved = qr.query(
                    "SELECT seen_at = ('2026-01-01 12:00:00'::timestamp AT TIME ZONE current_setting('TimeZone')) "
                            + "FROM \"t_pg_tz_migrate\" WHERE id = 1",
                    new Object[]{}, rs -> rs.next() && rs.getBoolean(1));
            assertEquals(Boolean.TRUE, preserved, "USING 换算必须保持行值所指的瞬间不变");
        }

        tpl.clearTableCache();
        tpl.checkTable(PgTzMigrateEntity.class);
        assertEquals(migrated, relfilenode("t_pg_tz_migrate"), "转换完成后再次同步必须收敛（零重写）");
    }

    /**
     * 用例③：实体声明的索引名被别的表占用时，告警并跳过 CREATE，绝不抛错也绝不抢名字。
     * <p>曾经的缺陷：{@code CREATE INDEX IF NOT EXISTS} 在名字被别表占用时只发 NOTICE 静默跳过，
     * 本表索引永远建不上、每次启动重试、没有任何日志。修复后冲突必须显式可见（WARN），
     * 且流程不中断——这是部署期问题，不该把应用启动炸掉。</p>
     */
    @Test
    @Order(3)
    void crossTableIndexNameConflictIsSkippedWithWarning() {
        PgsqlTemplate tpl = template();
        tpl.dropTable(PgIndexGuardEntity.class);
        try (QueryRunner qr = new QueryRunner(tpl)) {
            qr.update("DROP TABLE IF EXISTS \"t_pg_idx_guard_b\"");
        }
        tpl.checkTable(PgIndexGuardEntity.class);
        assertEquals("t_pg_idx_guard", indexOwner("t_pg_idx_guard_code_idx"), "建表路径应为本表建出声明索引");

        // 拆掉本表索引、把同名索引挂到另一张表上：构造出"CREATE INDEX IF NOT EXISTS 会静默吞掉"的现场
        try (QueryRunner qr = new QueryRunner(tpl)) {
            qr.update("DROP INDEX \"t_pg_idx_guard_code_idx\"");
            qr.update("CREATE TABLE \"t_pg_idx_guard_b\" (id INTEGER PRIMARY KEY)");
            qr.update("CREATE INDEX \"t_pg_idx_guard_code_idx\" ON \"t_pg_idx_guard_b\" (id)");
        }

        tpl.clearTableCache();
        tpl.checkTable(PgIndexGuardEntity.class);

        assertEquals("t_pg_idx_guard_b", indexOwner("t_pg_idx_guard_code_idx"),
                "冲突时绝不能抢别表的索引名，也不能 DROP 别表对象");
        assertFalse(indexNames("t_pg_idx_guard").contains("t_pg_idx_guard_code_idx"),
                "本表索引建不上是既定事实（WARN 已给出），但不能假装它建上了");
    }

    /**
     * 用例④：部分索引不受结构同步波及。
     * <p>部分索引/表达式索引没有可比的裸列清单，回读时被排除（见 PgsqlDbMetaHandler#indexListSql），
     * 因此也不应出现在"库里有、实体没声明"的删除清单里——dropRedundantIndexes 只作用于
     * 框架能看见的索引。顺带把用例③留下的名字冲突清掉，验证冲突解除后声明索引能补建回来。</p>
     */
    @Test
    @Order(4)
    void partialIndexSurvivesSync() {
        PgsqlTemplate tpl = template();
        try (QueryRunner qr = new QueryRunner(tpl)) {
            qr.update("DROP TABLE \"t_pg_idx_guard_b\"");
        }

        // 名字冲突解除后，声明索引应能补建回本表
        tpl.clearTableCache();
        tpl.checkTable(PgIndexGuardEntity.class);
        assertEquals("t_pg_idx_guard", indexOwner("t_pg_idx_guard_code_idx"), "冲突解除后应补建声明索引");

        try (QueryRunner qr = new QueryRunner(tpl)) {
            qr.update("CREATE INDEX \"idx_pg_guard_partial\" ON \"t_pg_idx_guard\" (code) WHERE code IS NOT NULL");
        }
        assertTrue(indexNames("t_pg_idx_guard").contains("idx_pg_guard_partial"));

        tpl.clearTableCache();
        tpl.checkTable(PgIndexGuardEntity.class);

        assertTrue(indexNames("t_pg_idx_guard").contains("idx_pg_guard_partial"),
                "部分索引不受框架管理，dropRedundantIndexes 也不能碰它");
        assertEquals("t_pg_idx_guard", indexOwner("t_pg_idx_guard_code_idx"), "声明索引不应被重复重建");
    }

    /**
     * 用例⑤：列级非默认 collation 只告警、不"修回"。
     * <p>实体注解表达不了 collation，框架能比对的只有类型键（format_type 不含 collation），
     * 所以正确行为是：回读让人看见（WARN）、结构比对判等、绝不动列。
     * 曾经的缺陷是 collation 根本不回读——静默漂移查无可查。</p>
     */
    @Test
    @Order(5)
    void nonDefaultCollationIsKeptAndConverged() {
        PgsqlTemplate tpl = template();
        try (QueryRunner qr = new QueryRunner(tpl)) {
            qr.update("ALTER TABLE \"t_pg_idx_guard\" ALTER COLUMN \"code\" TYPE varchar(32) COLLATE \"C\"");
        }

        tpl.clearTableCache();
        tpl.checkTable(PgIndexGuardEntity.class);

        try (QueryRunner qr = new QueryRunner(tpl)) {
            String collation = qr.query(
                    "SELECT co.collname FROM pg_attribute a "
                            + "JOIN pg_class c ON c.oid = a.attrelid "
                            + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                            + "JOIN pg_collation co ON co.oid = a.attcollation "
                            + "WHERE n.nspname = current_schema() AND c.relname = 't_pg_idx_guard' "
                            + "AND a.attname = 'code'",
                    new Object[]{}, rs -> rs.next() ? rs.getString(1) : null);
            assertEquals("C", collation, "框架不参与 collation 变更，手工设置的 COLLATE \"C\" 必须原样保留");
        }
        // 收敛判据：同步后列结构仍是实体声明的形态，且整个过程不抛异常（collation 差异不产生任何 ALTER）
        assertEquals("character varying", dataTypeOf("t_pg_idx_guard", "code"));
    }

    /** 时区守护表：timestamptz 短写声明（用例①） */
    @Setter
    @Getter
    @Table(name = "t_pg_tz_guard")
    public static class PgTzGuardEntity {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private long id;
        @Column(type = "timestamptz")
        private OffsetDateTime seenAt;
    }

    /** 迁移守护表：实体声明 timestamptz，库端手工建成 timestamp（用例②） */
    @Setter
    @Getter
    @Table(name = "t_pg_tz_migrate")
    public static class PgTzMigrateEntity {
        @Id
        private long id;
        @Column(type = "timestamptz")
        private OffsetDateTime seenAt;
    }

    /** 索引守护表：一个声明索引 + 一个可挂部分索引的文本列（用例③④⑤） */
    @Setter
    @Getter
    @Table(name = "t_pg_idx_guard")
    public static class PgIndexGuardEntity {
        @Id
        private long id;
        @Index
        @Column(length = 32)
        private String code;
    }

}
