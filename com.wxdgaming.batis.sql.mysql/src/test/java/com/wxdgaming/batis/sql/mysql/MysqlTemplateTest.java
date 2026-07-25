package com.wxdgaming.batis.sql.mysql;

import com.wxdgaming.batis.model.*;
import com.wxdgaming.batis.parser.EntityParser;
import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        Assertions.assertEquals(8, tableInfo.getColumns().size(),
                "t_role 应有 8 个字段");

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
    void testEntityParserCompatibility() {
        // 验证实体解析结果中字段名称与数据库列命名的映射
        TableInfo tableInfo = EntityParser.parse(RoleEntity.class);
        Assertions.assertEquals(TABLE_NAME, tableInfo.getTableName());
        Assertions.assertEquals(8, tableInfo.getColumns().size());

        ColumnInfo createTimeCol = tableInfo.getColumn("create_time");
        Assertions.assertNotNull(createTimeCol, "createTime 应映射为 create_time");
        Assertions.assertEquals("create_time", createTimeCol.getColumnName());

        ColumnInfo rechargeCol = tableInfo.getColumn("recharge");
        Assertions.assertNotNull(rechargeCol, "recharge 应映射为 recharge");
        Assertions.assertEquals("DECIMAL(12,2)", rechargeCol.getType());
    }

    @Test
    @Order(9)
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
