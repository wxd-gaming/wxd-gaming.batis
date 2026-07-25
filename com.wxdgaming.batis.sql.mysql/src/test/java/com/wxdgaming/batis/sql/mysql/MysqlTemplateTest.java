package com.wxdgaming.batis.sql.mysql;

import com.wxdgaming.batis.model.*;
import com.wxdgaming.batis.parser.EntityParser;
import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSONObject;
import com.wxdgaming.batis.sql.Handlers;
import com.wxdgaming.batis.sql.QueryRunner;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * MysqlTemplate 集成测试
 * <p>测试表结构加载、建表、缓存、变更等全流程。</p>
 * <p>默认使用嵌入式 MariaDB4j（随测试自动拉起/关闭，无需外部 MySQL）。
 * 如需对照真实 MySQL，可设置系统属性：
 * {@code -Dwxd.batis.mysql.url=jdbc:mysql://host:3306/test}。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MysqlTemplateTest {

    static final Logger log = LoggerFactory.getLogger(MysqlTemplateTest.class);

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

    @Test
    @Order(6)
    void testCheckTable_noChanges() {
        // 表已存在且结构一致 → 不应报错
        template.clearTableCache();
        Assertions.assertDoesNotThrow(() -> template.checkTable(RoleEntity.class),
                "表已存在且结构一致时应正常运行");
    }

    @Test
    @Order(7)
    void testClearCache() {
        template.loadAllTables();
        Assertions.assertNotNull(template.getTableCache());
        template.clearTableCache();
        Assertions.assertNull(template.getTableCache(), "clearTableCache 后应为 null");
    }

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
                    Handlers.firstCell());
            Assertions.assertNotNull(idNum, "应能查到自增 id");
            long id = idNum.longValue();
            Assertions.assertTrue(id > 0, "自增 id 应 > 0");

            // ===== 2. 查询方式一：映射为 Bean（单行） =====
            RoleEntity byBean = qr.query(
                    "SELECT * FROM `" + TABLE_NAME + "` WHERE `id` = ?",
                    new Object[]{id},
                    Handlers.bean(RoleEntity.class));
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
                    Handlers.row());
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
                    Handlers.list());
            Assertions.assertNotNull(rows);
            Assertions.assertEquals(1, rows.size(), "list 应只有 1 行");
            JSONObject first = rows.get(0);
            Assertions.assertEquals(role.getName(), first.getString("name"));
            Assertions.assertEquals(role.getAccount(), first.getString("account"));
            Assertions.assertEquals(Integer.valueOf(role.getScore()), first.getInteger("score"));

            // ===== 5. 列表 Bean 映射（beanList） =====
            List<RoleEntity> beanList = qr.query(
                    "SELECT * FROM `" + TABLE_NAME + "` ORDER BY `id`",
                    Handlers.beanList(RoleEntity.class));
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
                    Handlers.bean(RoleEntity.class));
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
                    Handlers.firstCell());
            Assertions.assertEquals(0, remain.intValue(), "删除后 COUNT 应为 0");

            log.info("RoleEntity 写入 + 多种查询（bean / row / list / beanList）+ 更新 + 删除 全流程测试通过");
        }
    }

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
