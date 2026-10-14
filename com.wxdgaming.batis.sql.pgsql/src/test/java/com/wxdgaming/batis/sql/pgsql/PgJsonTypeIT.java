package com.wxdgaming.batis.sql.pgsql;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.wxdgaming.batis.exception.DataAccessException;
import com.wxdgaming.batis.sql.BeanRunner;
import com.wxdgaming.batis.sql.QueryRunner;
import com.wxdgaming.batis.sql.ResultSetHandlers;
import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PG 原生 JSON 列回归测试（真库：嵌入式 PostgreSQL）
 * <p>覆盖五件互相依赖的事，缺一不可：</p>
 * <ol>
 *   <li>物理类型：{@code @Column(type = "json"/"JSONB")} 真落成 PG 的 json/jsonb 列，
 *       默认（不带 type）仍是 text；</li>
 *   <li>读写往返：集合/Map/裸字符串三种形态都能写进 JSON 列并读回，值转换层不出 42804；</li>
 *   <li>零虚假 ALTER：重复同步既不刷结构快照，也不<b>重写表文件</b>
 *       （PG 的 {@code ALTER COLUMN ... TYPE} 是整表重写，只看列快照查不出"重复做一次同样的改写"）；</li>
 *   <li>迁移：存量文本列改声明成 jsonb 后，{@code ALTER ... TYPE jsonb USING} 能把数据搬过去，
 *       反向 jsonb → text 同理；</li>
 *   <li>多态：{@code @Column(polymorphic = true)} 追加的类型标记仍是标准 JSON，
 *       因此 jsonb 收得下、PG 算子读得到，而框架读回来的是具体子类。</li>
 * </ol>
 * <p>用例④（裸字符串参数直接失败）是给②③的包装动作留的<em>反面</em>证据：
 * 如果没有 {@code PGobject} 包装，这条 SQL 会以 42804 失败。删掉包装后②会立刻红，
 * 而这条用例把"为什么需要包装"写成了可执行的说明，不必去翻 pgjdbc 的源码。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-14
 **/
@TestMethodOrder(OrderAnnotation.class)
class PgJsonTypeIT {

    @RegisterExtension
    static PgsqlExtension pg = new PgsqlExtension();

    static PgsqlTemplate template;

    private static final String TABLE = "t_pg_json";
    private static final String MIGRATE_TABLE = "t_pg_json_migrate";

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

    /** 列的库端形态：data_type + 默认值，取自 information_schema（第三方真相源，不用框架自己的读取器） */
    private static List<JSONObject> columnSnapshot() {
        try (QueryRunner qr = new QueryRunner(template())) {
            return qr.query(
                    "SELECT column_name, data_type, column_default FROM information_schema.columns "
                            + "WHERE table_schema = current_schema() AND table_name = ? ORDER BY ordinal_position",
                    new Object[]{TABLE}, ResultSetHandlers.list());
        }
    }

    private static JSONObject column(List<JSONObject> snapshot, String columnName) {
        return snapshot.stream()
                .filter(row -> columnName.equals(row.getString("column_name")))
                .findFirst().orElse(null);
    }

    /**
     * 表文件号：PG 每次整表重写（含 {@code ALTER COLUMN ... TYPE}）都会换 relfilenode。
     * <p>用它来证明"没有虚假 ALTER"：列快照在重复改写前后是<em>一样</em>的，光比快照会把
     * "每次启动都重写一遍全表"这种最贵的错误漏掉。</p>
     */
    private static long relfilenode(String table) {
        try (QueryRunner qr = new QueryRunner(template())) {
            List<JSONObject> rows = qr.query(
                    "SELECT relfilenode FROM pg_class WHERE relname = ?",
                    new Object[]{table}, ResultSetHandlers.list());
            return rows.isEmpty() ? -1 : rows.getFirst().getLongValue("relfilenode");
        }
    }

    /** 造一条覆盖全部 JSON 档的实体 */
    private static PgJsonEntity newEntity(String bizKey) {
        PgJsonEntity e = new PgJsonEntity();
        e.setBizKey(bizKey);
        e.setTags(new ArrayList<>(List.of("a", "b")));
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("k", "v");
        doc.put("n", 1);
        doc.put("list", List.of(1, 2, 3));
        e.setDoc(doc);
        e.setRawJson("{\"x\":[1,2]}");
        e.setAsText(new ArrayList<>(List.of("t1", "t2")));
        // withDefault 留 null：列默认值由 PG 在建表时给出，不该由写入侧代劳
        return e;
    }

    /**
     * 用例①：显式声明的物理类型被 PG 接受，且落成的正是那一档；默认档仍是 text。
     * <p>期望值用 information_schema 的规范拼写（{@code jsonb}/{@code json}/{@code text}），
     * 而不是框架渲染出的大小写混合字面量——这样同时验证了"DDL 能被 PG 接受"与"回读口径一致"。
     * {@code doc} 故意在注解里写成小写 {@code json}，用来证明大小写不参与判定。</p>
     */
    @Test
    @Order(1)
    void nativeJsonTypesAreCreated() {
        PgsqlTemplate tpl = template();
        tpl.dropTable(PgJsonEntity.class);
        tpl.checkTable(PgJsonEntity.class);

        List<JSONObject> snap = columnSnapshot();
        assertEquals("jsonb", column(snap, "tags").getString("data_type"));
        assertEquals("json", column(snap, "doc").getString("data_type"));
        assertEquals("jsonb", column(snap, "raw_json").getString("data_type"));
        assertEquals("jsonb", column(snap, "with_default").getString("data_type"));
        assertEquals("text", column(snap, "as_text").getString("data_type"),
                "不带 type 的 JSON 存储模式必须仍是 text（存量实体口径不能变）");
        // 默认值表达式：PG 给 jsonb 列补上了 ::jsonb 转换，回读侧要能折回实体写法
        assertEquals("'[]'::jsonb", column(snap, "with_default").getString("column_default"));
    }

    /**
     * 用例②：三种 Java 形态（集合 / Map / 裸 JSON 字符串）都能写进 JSON 列并读回。
     * <p>断言按<b>结构</b>而不是字符串比对：jsonb 会把键序与空白规范化（{@code {"x":[1,2]}} 存成
     * {@code {"x": [1, 2]}}），这正是用户接受 jsonb "更严格"的代价；对上层来说读回来的对象图不变，
     * 才是框架要保证的事。</p>
     * <p>同时确认 {@code with_default} 列在实体没给值时是 NULL 而不是空数组：框架的 INSERT 会为
     * 每个非自增列绑定参数，绑定 NULL 就盖掉了列上的 DEFAULT——默认值只在"列出现在 DDL 里、
     * 且写入时被省略"时才生效（同一口径见 {@code PgBeanRunnerIT} 用例④）。</p>
     */
    @Test
    @Order(2)
    void jsonColumnsRoundTrip() {
        PgJsonEntity bean = newEntity("round-trip");
        try (BeanRunner br = template().beanRunner()) {
            Number id = br.insertBean(bean);
            assertNotNull(id);

            PgJsonEntity back = br.queryById(PgJsonEntity.class, id);
            assertNotNull(back);
            assertEquals(List.of("a", "b"), back.getTags());
            assertEquals("v", back.getDoc().get("k"));
            assertEquals(1, ((Number) back.getDoc().get("n")).intValue());
            assertEquals(List.of(1, 2, 3), toIntList(back.getDoc().get("list")));
            assertEquals(List.of(1, 2), toIntList(JSONObject.parseObject(back.getRawJson()).get("x")));
            assertEquals(List.of("t1", "t2"), back.getAsText());
            assertNull(back.getWithDefault(),
                    "实体未赋值的列被绑定为 NULL，列默认值不会代劳（DEFAULT 只在省略该列时生效）");

            // UPDATE 走的是另一条参数装配路径（SET 段在前、主键在后），包装必须同样生效。
            // 刻意用第二行做更新：用例③要拿第一行的 tags 做包含查询，不能被这里改写
            PgJsonEntity second = newEntity("update-path");
            Number secondId = br.insertBean(second);
            PgJsonEntity toUpdate = br.queryById(PgJsonEntity.class, secondId);
            assertNotNull(toUpdate);
            toUpdate.setTags(new ArrayList<>(List.of("c")));
            toUpdate.setRawJson("{\"x\":3}");
            assertEquals(1, br.updateBean(toUpdate), "按主键更新应影响 1 行");
            PgJsonEntity updated = br.queryById(PgJsonEntity.class, secondId);
            assertEquals(List.of("c"), updated.getTags());
            assertEquals(3, JSONObject.parseObject(updated.getRawJson()).getIntValue("x"));
        }
        // 绕过框架插入（省略该列）才让 PG 用默认值填出空数组
        try (QueryRunner qr = new QueryRunner(template())) {
            qr.update("INSERT INTO " + PgsqlIdentifier.quote(TABLE) + " (\"tags\", \"biz_key\") VALUES ('[\"z\"]', ?)",
                    new Object[]{"default-applied"});
            List<JSONObject> rows = qr.query(
                    "SELECT \"with_default\"::text AS v FROM " + PgsqlIdentifier.quote(TABLE)
                            + " WHERE \"biz_key\" = ?",
                    new Object[]{"default-applied"}, ResultSetHandlers.list());
            assertEquals("[]", rows.getFirst().getString("v"), "省略该列时 DEFAULT '[]'::jsonb 必须真的落在列上");
        }
    }

    /** 把 fastjson 读回来的任意数字集合折成 int 列表，避免 Integer/Long 形态差异污染断言 */
    private static List<Integer> toIntList(Object value) {
        return ((List<?>) value).stream().map(v -> ((Number) v).intValue()).collect(Collectors.toList());
    }

    /**
     * 用例③：JSON 列上的 PG 原生算子真的可用（{@code @>} 包含、{@code ->>} 取文本）。
     * <p>这一条证明"列是 jsonb"不是快照里的一个字符串：text 列上 {@code @>} 会 42883（没有这个算子）。
     * 也顺手给出查询侧的写法——需要 jsonb 语义时按 {@code 列 @> '...'::jsonb} 参数化拼，
     * 框架不做 JSON 条件 DSL（那是索引与查询那一期的事）。</p>
     */
    @Test
    @Order(3)
    void jsonOperatorsWorkOnRealColumn() {
        try (QueryRunner qr = new QueryRunner(template())) {
            List<JSONObject> hits = qr.query(
                    "SELECT \"biz_key\" AS k FROM " + PgsqlIdentifier.quote(TABLE)
                            + " WHERE \"tags\" @> '[\"a\"]'::jsonb",
                    new Object[]{}, ResultSetHandlers.list());
            assertEquals(1, hits.size());
            assertEquals("round-trip", hits.getFirst().getString("k"));

            List<JSONObject> extracted = qr.query(
                    "SELECT \"doc\" ->> 'k' AS v FROM " + PgsqlIdentifier.quote(TABLE)
                            + " WHERE \"biz_key\" = ?",
                    new Object[]{"round-trip"}, ResultSetHandlers.list());
            assertEquals("v", extracted.getFirst().getString("v"));
        }
    }

    /**
     * 用例④：裸字符串参数进 jsonb 列必然失败，说明 {@code PGobject} 包装是承重的。
     * <p>pgjdbc 的 {@code setObject(String)} 按 varchar 送参，而 PG 没有 varchar→jsonb 的隐式赋值转换，
     * 报错就是 42804。框架侧走的是包装后的路径（用例②），这里用一条手写 SQL 把差别固定下来：
     * 如果哪天包装被删掉，②会失败，而这条用例会指出失败的原因是类型送错而不是 SQL 写错。</p>
     */
    @Test
    @Order(4)
    void rawStringParameterIntoJsonbColumnIsRejected() {
        try (QueryRunner qr = new QueryRunner(template())) {
            DataAccessException ex = assertThrows(DataAccessException.class, () ->
                    qr.update("INSERT INTO " + PgsqlIdentifier.quote(TABLE)
                                    + " (\"tags\", \"biz_key\") VALUES (?, ?)",
                            new Object[]{"[\"a\"]", "raw-string-param"}));
            assertEquals("42804", ex.getSqlState(), "varchar 参数落 jsonb 列的固定报错");
        }
    }

    /**
     * 用例⑤：重复同步零漂移，且没有整表重写。
     * <p>两次 {@code clearTableCache() + checkTable}：不清缓存会命中内存 TableInfo 而跳过 diff，
     * 用例就会因为"根本没比对"而假通过。断言两层——列快照逐行相等（结构没被动过）、
     * relfilenode 不变（表文件没有被重写）。后者才是 PG 侧真正贵的那件事。</p>
     */
    @Test
    @Order(5)
    void reSyncProducesZeroAlterAndNoRewrite() {
        List<JSONObject> before = columnSnapshot();
        long nodeBefore = relfilenode(TABLE);
        assertTrue(nodeBefore > 0, "relfilenode 应能取到（取不到说明这条断言根本没在守任何东西）");

        PgsqlTemplate tpl = template();
        tpl.clearTableCache();
        tpl.checkTable(PgJsonEntity.class);
        assertEquals(before, columnSnapshot(), "重复 checkTable 不应改变任何列类型");
        assertEquals(nodeBefore, relfilenode(TABLE), "重复 checkTable 不应整表重写 JSON 列");

        tpl.clearTableCache();
        tpl.checkTable(PgJsonEntity.class);
        assertEquals(before, columnSnapshot(), "多次 checkTable 仍应零漂移");
        assertEquals(nodeBefore, relfilenode(TABLE), "多次 checkTable 仍不应重写表文件");
    }

    /**
     * 用例⑥：存量文本列改成 jsonb 走 {@code ALTER ... TYPE jsonb USING}，数据保留。
     * <p>没有 USING 时 PG 直接 42846（text 与 jsonb 之间没有隐式赋值转换），这条路径就会卡死：
     * 用户加了 {@code type = "jsonb"} 之后每次启动 checkTable 都报错。用例先按文本实体建表灌一行
     * 合法 JSON 文本，再按 jsonb 实体同步，断言列类型确实变成 jsonb、原来的行还能按 JSON 读回来。</p>
     */
    @Test
    @Order(6)
    void textColumnMigratesIntoJsonbKeepingRows() {
        PgsqlTemplate tpl = template();
        tpl.dropTable(PgJsonMigrateEntity.class);
        tpl.checkTable(PgJsonMigrateEntity.class);
        try (BeanRunner br = tpl.beanRunner()) {
            PgJsonMigrateEntity text = new PgJsonMigrateEntity();
            text.setPayload("[1,2,3]");
            br.insertBean(text);
        }
        assertEquals("text", dataTypeOf(MIGRATE_TABLE, "payload"));

        tpl.clearTableCache();
        tpl.checkTable(PgJsonMigrateJsonbEntity.class);
        assertEquals("jsonb", dataTypeOf(MIGRATE_TABLE, "payload"),
                "声明改成 jsonb 后必须真的把列改过来（靠 USING 才走得通）");

        try (BeanRunner br = tpl.beanRunner()) {
            PgJsonMigrateJsonbEntity back = br.queryById(PgJsonMigrateJsonbEntity.class, 1L);
            assertNotNull(back, "迁移不能丢行");
            assertEquals(List.of(1, 2, 3), back.getPayload());
        }
    }

    /**
     * 用例⑦：反方向（jsonb 列改回文本）同样要能过。
     * <p>接着用例⑥的状态：此时库里是 jsonb，实体退回 {@code type = "TEXT"}。
     * 这一向更容易被漏——它发生在"用户试过了原生列又决定回退"的时候，
     * 而如果只有正向带 USING，回退就会在启动期把整个应用卡住。
     * 数据侧同样断言：读回来的字符串仍是一条合法的 JSON 文本。</p>
     */
    @Test
    @Order(7)
    void jsonbColumnMigratesBackToText() {
        PgsqlTemplate tpl = template();
        tpl.clearTableCache();
        tpl.checkTable(PgJsonMigrateEntity.class);

        assertEquals("text", dataTypeOf(MIGRATE_TABLE, "payload"));
        try (BeanRunner br = tpl.beanRunner()) {
            PgJsonMigrateEntity back = br.queryById(PgJsonMigrateEntity.class, 1L);
            assertNotNull(back, "反向迁移不能丢行");
            assertEquals(List.of(1, 2, 3), toIntList(JSON.parseArray(back.getPayload())));
        }
    }

    /**
     * 用例⑧：多态列能落进 PG 原生 jsonb，并且读回来还是具体子类。
     * <p>"开启多态后写出的仍然是合法 JSON"这句承诺，只有真库能证明：类型标记若带上
     * {@code 9L}、{@code Set[...]} 这类私有字面量，Java 侧的单测照样通过，而 PG 会在写入阶段
     * 就以非法输入拒绝整条 INSERT。所以这里两头都断言——
     * 用 jsonb 自己的算子把 {@code @type} 取出来（证明它是列里的一个普通键，不是别的什么形态），
     * 再用框架读回对象图（证明子类没有静默退化成接口代理）。</p>
     */
    @Test
    @Order(8)
    void polymorphicColumnLandsInJsonbAndReadsBackAsSubtype() {
        PgJsonEntity bean = newEntity("polymorphic-jsonb");
        // 改掉标签值：用例③按 tags @> '["a"]' 统计命中行数，这一行不能混进那个谓词
        bean.setTags(new ArrayList<>(List.of("poly")));
        bean.setShape(new PgCircle("red", 2.0));
        Number id;
        try (BeanRunner br = template().beanRunner()) {
            id = br.insertBean(bean);
            PgJsonEntity back = br.queryById(PgJsonEntity.class, id);
            assertNotNull(back);
            assertInstanceOf(PgCircle.class, back.getShape(),
                    "多态列必须还原成具体子类，不能是接口的动态代理");
            assertEquals("red", back.getShape().getColor());
            assertEquals(Math.PI * 4, ((PgCircle) back.getShape()).area(), 1e-9);
        }
        // 库端口径：jsonb 算子能直接读到类型标记键，说明它是标准 JSON 结构的一部分
        try (QueryRunner qr = new QueryRunner(template())) {
            List<JSONObject> rows = qr.query(
                    "SELECT \"shape\" ->> '@type' AS t, \"shape\" ->> 'radius' AS r FROM "
                            + PgsqlIdentifier.quote(TABLE) + " WHERE \"biz_key\" = ?",
                    new Object[]{"polymorphic-jsonb"}, ResultSetHandlers.list());
            assertEquals(PgCircle.class.getName(), rows.getFirst().getString("t"));
            assertEquals("2.0", rows.getFirst().getString("r"));
        }
    }

    /** 直接问 information_schema 要某一列的物理类型，绕开框架的元数据读取器 */
    private static String dataTypeOf(String table, String column) {
        try (QueryRunner qr = new QueryRunner(template())) {
            List<JSONObject> rows = qr.query(
                    "SELECT data_type FROM information_schema.columns "
                            + "WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?",
                    new Object[]{table, column}, ResultSetHandlers.list());
            return rows.isEmpty() ? null : rows.getFirst().getString("data_type");
        }
    }

}
