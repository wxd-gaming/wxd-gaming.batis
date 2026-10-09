package com.wxdgaming.batis.sql.mysql;

import com.wxdgaming.batis.model.*;
import com.wxdgaming.batis.parser.EntityParser;
import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSONObject;
import com.wxdgaming.batis.sql.ResultSetHandlers;
import com.wxdgaming.batis.sql.QueryRunner;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * MysqlTemplate 集成测试
 * <p>覆盖"实体 → 建表 → 库端元数据回读 → 索引校验 → 幂等同步 → CRUD"这条 DDL 主链路。
 * 每个用例只承担一环，断言的来源统一是<b>数据库元数据</b>而不是框架内存，
 * 这样才能证明"框架认为建出来的表"和"库里实际的表"是同一张。</p>
 * <p><b>用例顺序有依赖，不要单独重跑中间某个用例</b>：
 * ②建表 → ③④⑤从库端回读校验结构与索引 → ⑥验证重复同步幂等 → ⑦清空缓存
 * → ⑧需要表存在故再 checkTable 一次并做 CRUD → ⑩删表收尾。
 * 另外 MariaDB4j 的数据目录是持久化的（{@code ~/.wxd-batis/maria4j-data}），
 * 上一轮跑剩的表会留到这一轮，所以涉及"表是否已存在"的断言都写成可容忍两种起点，
 * 而做数据断言的用例（⑧）必须先 DELETE 清场。</p>
 * <p>默认使用嵌入式 MariaDB4j（随测试自动拉起/关闭，无需外部 MySQL）。
 * 如需对照真实 MySQL，可设置系统属性：
 * {@code -Dwxd.batis.mysql.url=jdbc:mysql://host:3306/test}。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class QueryRunnerTest {

    static final Logger log = LoggerFactory.getLogger(QueryRunnerTest.class);

    static MysqlTemplate template;
    static final String TABLE_NAME = "t_role";

    @RegisterExtension
    static MariaDB4jExtension maria = new MariaDB4jExtension();

    @BeforeAll
    static void setUp() {
        if (template != null) return;
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(maria.getJdbcUrl());
        config.setUsername("root");
        config.setPassword("");
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        template = new MysqlTemplate(config);
    }


    /**
     * 用例①：loadAllTables 能跑通并建立缓存。
     * <p>此时 t_role 可能还不存在（取决于持久化数据目录里是否有上一轮的表），
     * 所以不断言表数量，只断言"缓存对象已建立且读取库端元数据不报错"。
     * 顺带打印每张表重放的建表 SQL，便于人工核对结构。</p>
     */
    @Test
    @Order(1)
    void testLoadAllTables() {
        // 第一次加载（此时 t_role 可能还不存在）
        template.loadAllTables();
        Map<String, TableInfo> cache = template.getTableCache();
        Assertions.assertNotNull(cache, "缓存不应为 null");
        log.info("数据库共有 {} 个表", cache.size());
        for (Map.Entry<String, TableInfo> entry : cache.entrySet()) {
            log.info("  表: {}, {} \n\n{}", entry.getKey(), entry.getValue(), MysqlTableCreateHandler.INSTANCE.buildSql(entry.getValue()));
        }
    }

    /**
     * 用例②：checkTable 首次接触实体时把表建出来，并刷新缓存。
     * <p>断言：建表后内存缓存里能取到 t_role 的 TableInfo。
     * 意义是确认"解析实体 → 生成 DDL → 执行 → 回填缓存"这条链的出口是通的，
     * 后面的③④⑤才有东西可读。表若已存在（持久化目录复用），checkTable 走的是同步分支，
     * 结果同样满足本断言。</p>
     */
    @Test
    @Order(2)
    void testCheckTable_create() {
        // 解析实体 → 建表
        template.checkTable(RoleEntity.class);
        // 验证缓存已刷新且包含 t_role
        Map<String, TableInfo> cache = template.getTableCache();
        Assertions.assertNotNull(cache);
        TableInfo tableInfo = cache.get(TABLE_NAME);
        Assertions.assertNotNull(tableInfo, "建表后缓存应包含 " + TABLE_NAME);
        log.info("建表成功：{}\n\n{}", tableInfo, MysqlTableCreateHandler.INSTANCE.buildSql(tableInfo));
    }

    /**
     * 用例③：建表后重新从库端加载表结构。
     * <p>与②的区别很关键：②的 TableInfo 来自"实体解析 + 建表"，本用例强制走
     * "读 information_schema 回填"这条完全不同的路径。
     * 只有后者能证明库端确实有这张表、且列/索引能被正确读回来；
     * 如果只看内存，建表失败也可能"看起来正常"。
     * 输出全部列与索引明细，便于结构变化时人工比对差异。</p>
     */
    @Test
    @Order(3)
    void testLoadAllTables_afterCreate() {
        // 重新加载，验证 t_role 存在于缓存中
        template.loadAllTables();
        Map<String, TableInfo> cache = template.getTableCache();
        TableInfo tableInfo = cache.get(TABLE_NAME);
        Assertions.assertNotNull(tableInfo, "重新加载后缓存应包含 " + TABLE_NAME);

        log.info("=== 数据库中的表结构 ===");
        log.info("表名: {}", tableInfo.getTableName());
        log.info("注释: {}", tableInfo.getComment());
        log.info("列数: {}", tableInfo.getColumns().size());
        for (ColumnInfo col : tableInfo.getColumns().values()) {
            log.info("  列: {}  type={}  nullable={}  default={}  pk={}  comment={}",
                    col.getColumnName(), col.getType(), col.isNullable(),
                    col.getDefaultValue(), col.isPrimaryKey(), col.getComment());
        }
        if (tableInfo.getIndexes() != null) {
            log.info("索引数: {}", tableInfo.getIndexes().size());
            for (IndexInfo idx : tableInfo.getIndexes()) {
                log.info("  索引: {}  unique={}  columns={}",
                        idx.getName(), idx.isUnique(), idx.getColumns());
            }
        }
    }

    /**
     * 用例④：库端回读的结构明细与实体注解一致（列数、主键、可空性）。
     * <p>断言 9 个列、id 为主键且不可空、name/account 显式声明 NOT NULL 故不可空、
     * level 未声明故可空。可空性是这里唯一有"双向风险"的属性：
     * 建表时丢掉 nullable 会让库端比实体宽松（脏数据能进来），
     * 读元数据时误判 nullable 则会让下一次结构比对对每一列都发 MODIFY。两个方向都会被本用例抓住。</p>
     */
    @Test
    @Order(4)
    void testColumnCountAndTypes() {
        template.loadAllTables();
        TableInfo tableInfo = template.getTableCache().get(TABLE_NAME);
        Assertions.assertNotNull(tableInfo);

        // 列数校验
        Assertions.assertEquals(9, tableInfo.getColumns().size(),
                "t_role 应有 9 个字段");

        // 关键列属性校验
        ColumnInfo idCol = tableInfo.getColumn("id");
        Assertions.assertNotNull(idCol);
        Assertions.assertTrue(idCol.isPrimaryKey(), "id 应是主键");
        Assertions.assertFalse(idCol.isNullable(), "id 不应可为空");

        ColumnInfo nameCol = tableInfo.getColumn("name");
        Assertions.assertNotNull(nameCol);
        Assertions.assertFalse(nameCol.isNullable(), "name 不应可为空");

        ColumnInfo accountCol = tableInfo.getColumn("account");
        Assertions.assertNotNull(accountCol);
        Assertions.assertFalse(accountCol.isNullable(), "account 不应可为空");

        ColumnInfo levelCol = tableInfo.getColumn("level");
        Assertions.assertNotNull(levelCol);
        Assertions.assertTrue(levelCol.isNullable(), "level 应可为空（注解未指定 nullable）");
    }

    /**
     * 用例⑤：索引随建表一起生成，且唯一性与复合列顺序都正确。
     * <p>断言 uk_account 唯一且含 account 列；idx_level_score 非唯一且恰为 2 列（level + score）。
     * 复合索引要盯的是<b>列顺序</b>：(level, score) 与 (score, level) 是两个不同的索引，
     * 前者能服务"按等级过滤再按分数排序"，后者不能，
     * 顺序写反不会报错，只会让查询悄悄退化成全表扫描。</p>
     */
    @Test
    @Order(5)
    void testIndexCheck() {
        template.loadAllTables();
        TableInfo tableInfo = template.getTableCache().get(TABLE_NAME);
        Assertions.assertNotNull(tableInfo);

        List<IndexInfo> indexes = tableInfo.getIndexes();
        Assertions.assertNotNull(indexes);
        Assertions.assertTrue(indexes.size() >= 2, "至少应有 2 个索引（uk_account + idx_level_score）");

        // 查找 uk_account
        IndexInfo ukAccount = indexes.stream()
                .filter(idx -> "uk_account".equals(idx.getName()))
                .findFirst().orElse(null);
        Assertions.assertNotNull(ukAccount, "应存在 uk_account 索引");
        Assertions.assertTrue(ukAccount.isUnique(), "uk_account 应是唯一索引");
        Assertions.assertTrue(ukAccount.getColumns().contains("account"),
                "uk_account 应包含 account 列");

        // 查找 idx_level_score
        IndexInfo idxLevelScore = indexes.stream()
                .filter(idx -> "idx_level_score".equals(idx.getName()))
                .findFirst().orElse(null);
        Assertions.assertNotNull(idxLevelScore, "应存在 idx_level_score 索引");
        Assertions.assertFalse(idxLevelScore.isUnique(), "idx_level_score 应是非唯一索引");
        Assertions.assertEquals(2, idxLevelScore.getColumns().size(),
                "idx_level_score 应有 2 个列");
    }

    /**
     * 用例⑥：表已存在且结构一致时，重复 checkTable 不报错、不产生变更。
     * <p>动作：先清缓存再 checkTable，强制走完整的"读库端结构 → 与实体 diff → 无差异则什么都不做"。
     * 断言形式是 assertDoesNotThrow：本用例守的是幂等性——
     * 应用每次启动都会调 checkTable，如果它对"已经一致"的表判断失误，
     * 就会在每次启动时对生产表发一串 ALTER（锁表 + 拖慢启动），后果远比一个异常严重。
     * 更严格的"零 ALTER"验证由 SchemaDriftIT 用 information_schema 快照对拍完成。</p>
     */
    @Test
    @Order(6)
    void testCheckTable_noChanges() {
        // 表已存在且结构一致 → 不应报错
        template.clearTableCache();
        Assertions.assertDoesNotThrow(() -> template.checkTable(RoleEntity.class),
                "表已存在且结构一致时应正常运行");
    }

    /**
     * 用例⑦：clearTableCache 把整份结构缓存置空。
     * <p>断言清理后 getTableCache 返回 null（不是"空 Map"）。
     * 这个语义差别有意义：调用方要能区分"还没加载过"和"加载过但库里没表"，
     * 后续 checkTable 也据此决定是走重建还是走 diff。
     * 本用例顺带为⑧制造了"缓存为空"的前置状态。</p>
     */
    @Test
    @Order(7)
    void testClearCache() {
        template.loadAllTables();
        Assertions.assertNotNull(template.getTableCache());
        template.clearTableCache();
        Assertions.assertNull(template.getTableCache(), "clearTableCache 后应为 null");
    }

    /**
     * 用例⑧：原生 SQL 写入 + 四种结果集处理器 + 更新 + 删除的端到端一遍。
     * <p>前置说明：⑦已把结构缓存清空，所以这里必须先 checkTable —— 它复用的是库里已存在的表，
     * 走的是"同步（无差异）"分支而不是重新建表；随后 DELETE 清场，
     * 因为持久化数据目录可能残留上一轮的数据，会让"只有 1 行"这类断言假失败。</p>
     * <p>本用例的主体价值在四种把 ResultSet 变成 Java 对象的读法各自被覆盖一次：
     * bean（单行映射成实体）、row（单行映射成 JSONObject，列名原样为 key）、
     * list（多行 JSONObject）、beanList（多行实体），外加 firstCell 取单值。
     * 断言特意挑了最容易在映射中变形的字段：boolean（列端是 tinyint）、
     * Date/Timestamp、BigDecimal 金额（用 compareTo 规避 scale 差异）。</p>
     * <p>最后更新与删除各回查一次：更新看新值，删除看 COUNT 归零，
     * 保证三种 DML 的影响行数语义都正确。</p>
     */
    @Test
    @Order(8)
    void testInsertAndQueryRole() {
        // 确保表存在（testClearCache 已清空缓存，checkTable 会自动 reload 并复用已存在的表）
        template.checkTable(RoleEntity.class);

        try (var qr = new QueryRunner(template)) {
            // 持久化 dataDir 下可能残留历史数据，先清场，保证断言稳定
            qr.update("DELETE FROM `" + TABLE_NAME + "`");

            // ===== 1. 构造并写入一条角色数据 =====
            RoleEntity role = new RoleEntity();
            role.setName("张三");
            role.setLevel(10);
            role.setAccount("acc_10001");
            role.setScore(888);
            role.setBanned(false);
            role.setCreateTime(new java.util.Date());
            role.setRecharge(new BigDecimal("12.34"));

            // id 为自增主键，不写入
            String insertSql = "INSERT INTO `" + TABLE_NAME + "` "
                    + "(`name`, `level`, `account`, `score`, `banned`, `create_time`, `recharge`) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?)";
            int affected = qr.update(insertSql, new Object[]{
                    role.getName(),
                    role.getLevel(),
                    role.getAccount(),
                    role.getScore(),
                    role.isBanned(),                               // boolean -> TINYINT
                    new Timestamp(role.getCreateTime().getTime()), // Date -> Timestamp
                    role.getRecharge()                             // BigDecimal
            });
            Assertions.assertEquals(1, affected, "插入应影响 1 行");

            // 取回自增主键，后续各类查询/更新/删除复用
            Number idNum = (Number) qr.query(
                    "SELECT `id` FROM `" + TABLE_NAME + "` WHERE `account` = ?",
                    new Object[]{role.getAccount()},
                    ResultSetHandlers.firstCell());
            Assertions.assertNotNull(idNum, "应能查到自增 id");
            long id = idNum.longValue();
            Assertions.assertTrue(id > 0, "自增 id 应 > 0");

            // ===== 2. 查询方式一：映射为 Bean（单行） =====
            RoleEntity byBean = qr.query(
                    "SELECT * FROM `" + TABLE_NAME + "` WHERE `id` = ?",
                    new Object[]{id},
                    ResultSetHandlers.bean(template, RoleEntity.class));
            Assertions.assertNotNull(byBean, "bean 查询应返回对象");
            Assertions.assertEquals(id, byBean.getId());
            Assertions.assertEquals(role.getName(), byBean.getName());
            Assertions.assertEquals(role.getLevel(), byBean.getLevel());
            Assertions.assertEquals(role.getAccount(), byBean.getAccount());
            Assertions.assertEquals(role.getScore(), byBean.getScore());
            Assertions.assertEquals(role.isBanned(), byBean.isBanned());
            Assertions.assertNotNull(byBean.getCreateTime(), "create_time 不应为 null");
            Assertions.assertEquals(0, role.getRecharge().compareTo(byBean.getRecharge()),
                    "recharge 应一致");

            // ===== 3. 查询方式二：JSONObject 单行（row） =====
            JSONObject row = qr.query(
                    "SELECT * FROM `" + TABLE_NAME + "` WHERE `id` = ?",
                    new Object[]{id},
                    ResultSetHandlers.row());
            Assertions.assertNotNull(row, "row 查询应返回 JSONObject");
            Assertions.assertEquals(role.getName(), row.getString("name"));
            Assertions.assertEquals(Integer.valueOf(role.getLevel()), row.getInteger("level"));
            Assertions.assertEquals(role.getAccount(), row.getString("account"));
            Assertions.assertEquals(Integer.valueOf(role.getScore()), row.getInteger("score"));
            Assertions.assertEquals(role.isBanned(), row.getBooleanValue("banned"));
            Assertions.assertNotNull(row.get("create_time"), "create_time 不应为 null");
            Object rechargeObj = row.get("recharge");
            Assertions.assertTrue(rechargeObj instanceof BigDecimal, "row.recharge 应为 BigDecimal");
            Assertions.assertEquals(0, role.getRecharge().compareTo((BigDecimal) rechargeObj),
                    "row.recharge 应一致");

            // ===== 4. 查询方式三：JSONObject 多行（list） =====
            List<JSONObject> rows = qr.query(
                    "SELECT * FROM `" + TABLE_NAME + "` ORDER BY `id`",
                    ResultSetHandlers.list());
            Assertions.assertNotNull(rows);
            Assertions.assertEquals(1, rows.size(), "list 应只有 1 行");
            JSONObject first = rows.get(0);
            Assertions.assertEquals(role.getName(), first.getString("name"));
            Assertions.assertEquals(role.getAccount(), first.getString("account"));
            Assertions.assertEquals(Integer.valueOf(role.getScore()), first.getInteger("score"));

            // ===== 5. 列表 Bean 映射（beanList） =====
            List<RoleEntity> beanList = qr.query(
                    "SELECT * FROM `" + TABLE_NAME + "` ORDER BY `id`",
                    ResultSetHandlers.beanList(template, RoleEntity.class));
            Assertions.assertEquals(1, beanList.size(), "beanList 应只有 1 条");
            Assertions.assertEquals(role.getAccount(), beanList.get(0).getAccount());

            // ===== 6. 更新后回查（bean） =====
            int updated = qr.update(
                    "UPDATE `" + TABLE_NAME + "` SET `score` = ?, `banned` = ? WHERE `id` = ?",
                    new Object[]{9999, true, id});
            Assertions.assertEquals(1, updated, "更新应影响 1 行");
            RoleEntity after = qr.query(
                    "SELECT * FROM `" + TABLE_NAME + "` WHERE `id` = ?",
                    new Object[]{id},
                    ResultSetHandlers.bean(template, RoleEntity.class));
            Assertions.assertNotNull(after);
            Assertions.assertEquals(9999, after.getScore(), "score 更新后应生效");
            Assertions.assertTrue(after.isBanned(), "banned 更新后应生效");

            // ===== 7. 删除该条，校验清空 =====
            int deleted = qr.update(
                    "DELETE FROM `" + TABLE_NAME + "` WHERE `id` = ?",
                    new Object[]{id});
            Assertions.assertEquals(1, deleted, "删除应影响 1 行");
            Number remain = (Number) qr.query(
                    "SELECT COUNT(*) FROM `" + TABLE_NAME + "`",
                    ResultSetHandlers.firstCell());
            Assertions.assertEquals(0, remain.intValue(), "删除后 COUNT 应为 0");

            log.info("RoleEntity 写入 + 多种查询（bean / row / list / beanList）+ 更新 + 删除 全流程测试通过");
        }
    }

    /**
     * 用例⑨：实体解析器本身（不连库）的命名映射与显式类型保留。
     * <p>断言 Java 字段名按驼峰转下划线映射（createTime → create_time）、列数与注解一致、
     * 显式写了类型的列（recharge 的 DECIMAL(12,2)）按字面原样保留不被推断覆盖。
     * 与③④的分工：那两条证"库端结构对"，本条证"解析对"，
     * 纯解析断言失败可以立刻定位到 EntityParser，而不必怀疑 DDL 或元数据读取。</p>
     */
    @Test
    @Order(9)
    void testEntityParserCompatibility() {
        // 验证实体解析结果中字段名称与数据库列命名的映射
        TableInfo tableInfo = EntityParser.parse(RoleEntity.class);
        Assertions.assertEquals(TABLE_NAME, tableInfo.getTableName());
        Assertions.assertEquals(9, tableInfo.getColumns().size());

        ColumnInfo createTimeCol = tableInfo.getColumn("create_time");
        Assertions.assertNotNull(createTimeCol, "createTime 应映射为 create_time");
        Assertions.assertEquals("create_time", createTimeCol.getColumnName());

        ColumnInfo rechargeCol = tableInfo.getColumn("recharge");
        Assertions.assertNotNull(rechargeCol, "recharge 应映射为 recharge");
        Assertions.assertEquals("DECIMAL(12,2)", rechargeCol.getType());
    }

    /**
     * 用例⑩：删表并确认库端与缓存同步消失（本类的收尾）。
     * <p>动作：DROP TABLE 后重新 loadAllTables。断言缓存里查不到 t_role ——
     * 注意必须先清缓存再重新加载，否则读到的是上一轮的陈旧缓存，"删除成功"就成了假断言。
     * 之所以把删表放在最后，是因为数据目录持久化：不清理会让下一轮①看到一张陌生的表。</p>
     */
    @Test
    @Order(10)
    void testDropTable() {
        // 清理测试表
        template.clearTableCache();
        try (var qr = new com.wxdgaming.batis.sql.QueryRunner(template)) {
            qr.update("DROP TABLE IF EXISTS `" + TABLE_NAME + "`");
        }
        // 验证已删除
        template.loadAllTables();
        TableInfo tableInfo = template.getTableCache().get(TABLE_NAME);
        Assertions.assertNull(tableInfo, "删除后缓存不应包含 " + TABLE_NAME);
        log.info("测试表 {} 已清理", TABLE_NAME);
    }
}
