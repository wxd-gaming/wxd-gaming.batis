package com.wxdgaming.batis.sql.pgsql;

import com.alibaba.fastjson2.JSONObject;
import com.wxdgaming.batis.sql.QueryRunner;
import com.wxdgaming.batis.sql.ResultSetHandlers;
import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PG 结构漂移回归测试（真库：嵌入式 PostgreSQL）
 * <ol>
 *   <li>{@link PgsqlTemplate} 装配后能建表，逐列物理类型符合 PG 档位；</li>
 *   <li>同一实体反复 checkTable 零漂移（无虚假 ALTER）；</li>
 *   <li>真实漂移（改类型、改可空、删列、多余索引）仍能检出并修回。</li>
 * </ol>
 * <p>与 MySQL 的 {@code SchemaDriftIT} 同一套方法学：快照取自 information_schema/pg_catalog
 * 这两个"第三方真相源"，不用框架自己的元数据读取器——否则就是拿框架的输出去证明框架的输出。
 * 用例②和③必须成对读：只看②可以把比对整体关掉也能通过，只有③证明"真改了库会改回来"，
 * ②的零漂移才是健康的而不是失灵的。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-14
 **/
@TestMethodOrder(OrderAnnotation.class)
class PgSchemaDriftIT {

    @RegisterExtension
    static PgsqlExtension pg = new PgsqlExtension();

    static PgsqlTemplate template;

    /** 表名固定，快照与手工漂移都围绕它 */
    private static final String TABLE = "t_pg_ddl_drift";

    /**
     * 模板按需构造：本类所有用例共用一个 HikariPool，嵌入式 PG 由 {@link PgsqlExtension} 提供。
     * <p>走的是 {@code PgsqlTemplate(HikariConfig)} 这条构造路径，因此本用例顺带覆盖了
     * "连 maintenance 库确认/创建目标库" 的自动建库分支。</p>
     */
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

    /**
     * 列结构快照：取自 information_schema（列名/类型/长度/精度/可空/默认值，按定义顺序）。
     * <p>刻意不用 {@link PgsqlDbMetaHandler}：它是被测方之一，用它读回来的结构去证明
     * "结构没变"是自证。</p>
     */
    private static List<JSONObject> columnSnapshot() {
        try (QueryRunner qr = new QueryRunner(template())) {
            return qr.query(
                    "SELECT column_name, data_type, character_maximum_length, numeric_precision, numeric_scale, "
                            + "is_nullable, column_default, identity_generation "
                            + "FROM information_schema.columns "
                            + "WHERE table_schema = current_schema() AND table_name = ? "
                            + "ORDER BY ordinal_position",
                    new Object[]{TABLE}, ResultSetHandlers.list());
        }
    }

    /** 注释快照：information_schema 里没有注释，只能查 pg_catalog 的 description */
    private static List<JSONObject> commentSnapshot() {
        try (QueryRunner qr = new QueryRunner(template())) {
            return qr.query(
                    "SELECT a.attname AS column_name, col_description(c.oid, a.attnum) AS comment "
                            + "FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid "
                            + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                            + "WHERE n.nspname = current_schema() AND c.relname = ? "
                            + "AND a.attnum > 0 AND NOT a.attisdropped ORDER BY a.attnum",
                    new Object[]{TABLE}, ResultSetHandlers.list());
        }
    }

    /** 本表当前的普通索引名清单（不含主键约束的支撑索引） */
    private static List<String> indexNames() {
        try (QueryRunner qr = new QueryRunner(template())) {
            List<JSONObject> rows = qr.query(
                    "SELECT ic.relname AS index_name FROM pg_index ix "
                            + "JOIN pg_class t ON t.oid = ix.indrelid "
                            + "JOIN pg_class ic ON ic.oid = ix.indexrelid "
                            + "JOIN pg_namespace n ON n.oid = t.relnamespace "
                            + "WHERE n.nspname = current_schema() AND t.relname = ? AND NOT ix.indisprimary "
                            + "ORDER BY ic.relname",
                    new Object[]{TABLE}, ResultSetHandlers.list());
            return rows.stream().map(row -> row.getString("index_name")).collect(Collectors.toList());
        }
    }

    /** 取某一列的快照行 */
    private static JSONObject column(List<JSONObject> snapshot, String columnName) {
        return snapshot.stream()
                .filter(row -> columnName.equals(row.getString("column_name")))
                .findFirst().orElse(null);
    }

    /**
     * 用例①：首次同步建表，逐档抽查物理类型。
     * <p>期望值全部是 PG 的规范拼写（{@code character varying}、{@code double precision}、
     * {@code timestamp without time zone}…），也就是 {@code format_type()} 与 information_schema
     * 给出的形态，而不是框架渲染出的写法——这样才同时验证了"渲染出的 DDL 能被 PG 接受"
     * 和"回读口径与库端一致"。</p>
     * <p>两处档位收敛是 PG 的固有事实，不是本框架的简化：TEXT 三档同为 {@code text}、
     * BLOB 三档同为 {@code bytea}（PG 按 TOAST 自动扩展），{@code byte} 与 {@code short} 同为
     * {@code smallint}（PG 没有 tinyint）。</p>
     */
    @Test
    @Order(1)
    void initialSyncCreatesTable() {
        PgsqlTemplate tpl = template();
        tpl.dropTable(PgDdlDriftEntity.class);
        tpl.checkTable(PgDdlDriftEntity.class);

        List<JSONObject> snap = columnSnapshot();
        assertEquals(PgDdlDriftEntity.class.getDeclaredFields().length - countStaticFields(), snap.size(),
                "实体字段应全部建成列: " + snap.stream().map(r -> r.getString("column_name")).toList());

        assertEquals("character varying", column(snap, "plain").getString("data_type"));
        assertEquals(255, column(snap, "plain").getIntValue("character_maximum_length"));
        assertEquals(64, column(snap, "short_text").getIntValue("character_maximum_length"));
        assertEquals("NO", column(snap, "short_text").getString("is_nullable"));
        assertEquals("text", column(snap, "article").getString("data_type"));
        assertEquals("text", column(snap, "big_article").getString("data_type"));
        assertEquals("text", column(snap, "novel").getString("data_type"));
        assertEquals("text", column(snap, "tags").getString("data_type"));
        assertEquals("bytea", column(snap, "payload_small").getString("data_type"));
        assertEquals("bytea", column(snap, "payload_medium").getString("data_type"));
        assertEquals("boolean", column(snap, "flag").getString("data_type"));
        assertEquals("smallint", column(snap, "tiny").getString("data_type"));
        assertEquals("smallint", column(snap, "small").getString("data_type"));
        assertEquals("integer", column(snap, "num").getString("data_type"));
        assertEquals("bigint", column(snap, "big").getString("data_type"));
        assertEquals("real", column(snap, "real").getString("data_type"));
        assertEquals("double precision", column(snap, "dbl").getString("data_type"));
        assertEquals("numeric", column(snap, "exact").getString("data_type"));
        assertEquals(20, column(snap, "exact").getIntValue("numeric_precision"));
        assertEquals(6, column(snap, "exact").getIntValue("numeric_scale"));
        assertEquals("numeric", column(snap, "huge_number").getString("data_type"));
        assertEquals(65, column(snap, "huge_number").getIntValue("numeric_precision"));
        assertEquals("date", column(snap, "date_only").getString("data_type"));
        assertEquals("time without time zone", column(snap, "time_only").getString("data_type"));
        assertEquals("timestamp without time zone", column(snap, "date_time").getString("data_type"));
        assertEquals("timestamp without time zone", column(snap, "util_date").getString("data_type"));
        assertEquals("character", column(snap, "letter").getString("data_type"));
        assertEquals(1, column(snap, "letter").getIntValue("character_maximum_length"));
        assertEquals("character varying", column(snap, "uid").getString("data_type"));
        assertEquals(36, column(snap, "uid").getIntValue("character_maximum_length"));
        // 显式物理类型走的是逃生舱：书写形态被 PG 规范化成它自己的拼写
        assertEquals("numeric", column(snap, "explicit_decimal").getString("data_type"));
        assertEquals(12, column(snap, "explicit_decimal").getIntValue("numeric_precision"));
        assertEquals("integer", column(snap, "explicit_int").getString("data_type"));

        // 自增主键：IDENTITY 与主键约束分别落在 identity_generation 与 pg_constraint 上
        assertEquals("BY DEFAULT", column(snap, "id").getString("identity_generation"));
        // 默认值形态（PG 存的是表达式，回读会带 ::类型 转换）
        assertEquals("'2026-01-01'::date", column(snap, "start_date").getString("column_default"));
        assertEquals("false", column(snap, "frozen").getString("column_default"));
        assertEquals("1", column(snap, "explicit_int").getString("column_default"));
        assertEquals("0.00", column(snap, "explicit_decimal").getString("column_default"));

        // 注释是独立对象，必须确认它们真的发出去了
        assertEquals("短字符串", commentSnapshot().stream()
                .filter(row -> "short_text".equals(row.getString("column_name")))
                .map(row -> row.getString("comment")).findFirst().orElse(null));
    }

    /**
     * 用例②：重复同步零漂移。
     * <p>动作：连续两次 {@code clearTableCache() + checkTable}——不清缓存会直接命中内存里的 TableInfo
     * 而跳过结构 diff，用例就会因为"根本没比对"而假通过。
     * 断言：列快照与注释快照与同步前逐行相等。哪怕一条虚假 ALTER 也会留下可见差异。</p>
     * <p>这条是 PG 方言最硬的回归护栏：PG 的 {@code ALTER COLUMN ... TYPE} 会整表重写，
     * 虚假漂移在 MySQL 上表现为反复 MODIFY，在 PG 上等同于每次启动复制一遍全表。</p>
     */
    @Test
    @Order(2)
    void reSyncProducesZeroAlter() {
        PgsqlTemplate tpl = template();
        List<JSONObject> beforeColumns = columnSnapshot();
        List<JSONObject> beforeComments = commentSnapshot();

        tpl.clearTableCache();
        tpl.checkTable(PgDdlDriftEntity.class);
        assertEquals(beforeColumns, columnSnapshot(), "重复 checkTable 不应产生任何列结构变化（零 ALTER）");
        assertEquals(beforeComments, commentSnapshot(), "重复 checkTable 不应重发注释");

        tpl.clearTableCache();
        tpl.checkTable(PgDdlDriftEntity.class);
        assertEquals(beforeColumns, columnSnapshot(), "多次 checkTable 仍应零漂移");
    }

    /**
     * 用例③：真实的类型漂移必须检出并修回。
     * <p>动作：绕过框架把 short_text 改窄成 varchar(32)，先确认库端确实变了，再清缓存走同步。
     * 断言：该列被改回实体定义的 varchar(64)，且修复前后不相等。
     * 选"只改长度"这一形态，排除掉"因为整表重写才碰巧改回来"的解释。</p>
     */
    @Test
    @Order(3)
    void realTypeDriftIsDetectedAndRepaired() {
        try (QueryRunner qr = new QueryRunner(template())) {
            qr.update("ALTER TABLE " + PgsqlIdentifier.quote(TABLE) + " ALTER COLUMN \"short_text\" TYPE varchar(32)");
        }
        assertEquals(32, column(columnSnapshot(), "short_text").getIntValue("character_maximum_length"),
                "手工漂移应生效于库端");

        template().clearTableCache();
        template().checkTable(PgDdlDriftEntity.class);

        assertEquals(64, column(columnSnapshot(), "short_text").getIntValue("character_maximum_length"),
                "真实漂移必须被检测并改回实体定义");
    }

    /**
     * 用例④：可空性漂移走的是另一条子句（SET NOT NULL），必须单独覆盖。
     * <p>PG 的 ALTER 是按属性拆开的：类型、可空性、默认值各自一条子句。只测类型漂移的话，
     * "可空性判定没接上"这种缺陷不会被发现，而它会让 NOT NULL 列在库里一直是可空的。</p>
     */
    @Test
    @Order(4)
    void nullabilityDriftIsRepaired() {
        try (QueryRunner qr = new QueryRunner(template())) {
            qr.update("ALTER TABLE " + PgsqlIdentifier.quote(TABLE) + " ALTER COLUMN \"short_text\" DROP NOT NULL");
        }
        assertEquals("YES", column(columnSnapshot(), "short_text").getString("is_nullable"));

        template().clearTableCache();
        template().checkTable(PgDdlDriftEntity.class);

        assertEquals("NO", column(columnSnapshot(), "short_text").getString("is_nullable"),
                "实体声明 nullable=false，库端可空必须被 SET NOT NULL 修回");
    }

    /**
     * 用例⑤：库里缺列时补列，且补出的列与首次建表的列字面量一致。
     * <p>这一条锁的是"建表路径与改表路径共用同一个列定义片段"：动作是先删掉 article 列，
     * 再让框架 ADD COLUMN，断言补齐后的形态与从未删过（用例①）时完全相同。
     * 若两条路径各写一份列定义，这里就会出现"同样的实体，两次同步建出不同的表"。</p>
     */
    @Test
    @Order(5)
    void missingColumnIsAddedWithSameDefinition() {
        try (QueryRunner qr = new QueryRunner(template())) {
            qr.update("ALTER TABLE " + PgsqlIdentifier.quote(TABLE) + " DROP COLUMN \"article\"");
        }
        assertFalse(columnSnapshot().stream().anyMatch(r -> "article".equals(r.getString("column_name"))));

        template().clearTableCache();
        template().checkTable(PgDdlDriftEntity.class);

        List<JSONObject> snap = columnSnapshot();
        JSONObject article = column(snap, "article");
        assertEquals("text", article.getString("data_type"));
        assertEquals("YES", article.getString("is_nullable"), "实体未声明 nullable=false，补列应为可空");
        // 补到表尾：列顺序与首次建表不同是 PG ADD COLUMN 的固有行为，这里只断言结构属性一致
        assertEquals(snap.size(), PgDdlDriftEntity.class.getDeclaredFields().length - countStaticFields());
    }

    /**
     * 用例⑥：库端多余的索引会被删掉，主键约束不受波及。
     * <p>这是 DROP INDEX 唯一的真库验证机会，而它的写法风险最高：PG 的索引是 schema 级对象，
     * 删除语句必须带 schema 限定（{@code DROP INDEX "public"."x"}）——不带的话按 search_path
     * 找第一个同名对象，可能删掉别的表的索引。同时 {@code pg_index} 回读刻意排除了主键，
     * 所以本表在同步后仍然有主键，这一条也在断言里。</p>
     */
    @Test
    @Order(6)
    void redundantIndexIsDroppedAndPrimaryKeySurvives() {
        try (QueryRunner qr = new QueryRunner(template())) {
            qr.update("CREATE INDEX idx_pg_drift_extra ON " + PgsqlIdentifier.quote(TABLE)
                    + " (" + PgsqlIdentifier.quote("plain") + ")");
        }
        assertTrue(indexNames().contains("idx_pg_drift_extra"));

        template().clearTableCache();
        template().checkTable(PgDdlDriftEntity.class);

        assertFalse(indexNames().contains("idx_pg_drift_extra"), "实体未声明的索引应被 DROP");
        try (QueryRunner qr = new QueryRunner(template())) {
            Integer pkCount = qr.query("SELECT count(*) FROM pg_constraint WHERE contype = 'p' "
                    + "AND conrelid = ?::regclass", new Object[]{TABLE}, rs -> rs.next() ? rs.getInt(1) : 0);
            assertEquals(1, pkCount, "索引同步不能碰主键约束");
        }
    }

    /**
     * 用例⑦：模板装配完整——方言 SPI 全部注入且指向 PG 实现。
     * <p>{@link PgsqlTemplate} 是本次交付的装配点，漏注入任何一项都会在运行期以
     * "未注册 xxxHandler" 的形式炸出来；这里直接在装配层面锁住，避免只有端到端用例才能发现缺一项。</p>
     */
    @Test
    @Order(7)
    void templateWiresEveryDialectSpi() {
        PgsqlTemplate tpl = template();
        assertTrue(tpl.getDbMetaProvider() instanceof PgsqlDbMetaHandler);
        assertTrue(tpl.getColumnTypeDefHandler() instanceof PgsqlColumnTypeDefHandler);
        assertTrue(tpl.getTableCreateHandler() instanceof PgsqlTableCreateHandler);
        assertTrue(tpl.getTableAlterHandler() instanceof PgsqlTableAlterHandler);
        assertTrue(tpl.getTableDropHandler() instanceof PgsqlTableDropHandler);
        assertTrue(tpl.getTableTruncateHandler() instanceof PgsqlTableTruncateHandler);
        assertTrue(tpl.getSqlDbToBeanHandler() instanceof PgsqlDbToBeanHandler);
        assertTrue(tpl.getBeanToInsertHandler() instanceof PgsqlBeanToInsertHandler);
        assertTrue(tpl.getBeanToUpdateHandler() instanceof PgsqlBeanToStatementHandler);
        assertTrue(tpl.getBeanToDeleteHandler() instanceof PgsqlBeanToStatementHandler);
        assertTrue(tpl.getBeanToSelectHandler() instanceof PgsqlBeanToStatementHandler);
        assertTrue(tpl.getSqlExceptionHandler() instanceof PgsqlSqlExceptionHandler);
        assertEquals("pgsql", tpl.getColumnTypeDefHandler().dialectName());
    }

    /**
     * {@code getDeclaredFields()} 会把 lombok 生成的 {@code $jacocoData} 之类合成字段也算进来，
     * 这里按实体声明的实例字段数校正，避免用例①的数量断言随构建工具变化而抖动。
     */
    private static int countStaticFields() {
        int synthetic = 0;
        for (var field : PgDdlDriftEntity.class.getDeclaredFields()) {
            if (field.isSynthetic() || java.lang.reflect.Modifier.isStatic(field.getModifiers())) synthetic++;
        }
        return synthetic;
    }

}
