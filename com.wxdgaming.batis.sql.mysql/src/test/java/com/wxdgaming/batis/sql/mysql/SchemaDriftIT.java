package com.wxdgaming.batis.sql.mysql;

import com.alibaba.fastjson.JSONObject;
import com.wxdgaming.batis.sql.QueryRunner;
import com.wxdgaming.batis.sql.ResultSetHandlers;
import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-4 列类型方言解耦回归测试
 * <ol>
 *   <li>建表/同步零漂移：同一实体反复 checkTable，information_schema 快照必须一致（无虚假 ALTER）；</li>
 *   <li>真实漂移仍可检出修复：手工把列改窄后，checkTable 必须 MODIFY 回实体定义。</li>
 * </ol>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
@TestMethodOrder(OrderAnnotation.class)
class SchemaDriftIT {

    @RegisterExtension
    static MariaDB4jExtension maria = new MariaDB4jExtension();

    static MysqlTemplate template;

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

    private static List<JSONObject> snapshot() {
        try (QueryRunner qr = new QueryRunner(template)) {
            return qr.query(
                    "SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT "
                            + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ddl_drift' "
                            + "ORDER BY ORDINAL_POSITION",
                    ResultSetHandlers.list());
        }
    }

    private static String columnType(List<JSONObject> snap, String columnName) {
        return snap.stream()
                .filter(row -> columnName.equals(row.getString("COLUMN_NAME")))
                .map(row -> row.getString("COLUMN_TYPE"))
                .findFirst().orElse(null);
    }

    @Test
    @Order(1)
    void initialSyncCreatesTable() {
        template.clearTableCache();
        template.checkTable(DdlDriftEntity.class);
        List<JSONObject> snap = snapshot();
        assertTrue(snap.size() >= 20, "全档位列应全部建成，实际 " + snap.size());
        // 关键档位抽查：渲染必须与解耦前的字面量产出一致（MariaDB 元数据对整数列报告原生显示宽度）
        assertEquals("varchar(255)", columnType(snap, "plain"));
        assertEquals("varchar(64)", columnType(snap, "short_text"));
        assertEquals("text", columnType(snap, "article"));
        assertEquals("mediumtext", columnType(snap, "big_article"));
        assertEquals("longtext", columnType(snap, "novel"));
        assertEquals("longtext", columnType(snap, "tags"));
        assertEquals("blob", columnType(snap, "payload_small"));
        assertEquals("mediumblob", columnType(snap, "payload_medium"));
        assertEquals("longblob", columnType(snap, "payload_large"));
        assertEquals("tinyint(1)", columnType(snap, "flag"));
        assertEquals("tinyint(4)", columnType(snap, "tiny"));
        assertEquals("smallint(6)", columnType(snap, "small"));
        assertEquals("int(11)", columnType(snap, "num"));
        assertEquals("bigint(20)", columnType(snap, "big"));
        assertEquals("decimal(20,6)", columnType(snap, "exact"));
        assertEquals("datetime", columnType(snap, "date_time"));
        assertEquals("char(1)", columnType(snap, "letter"));
        assertEquals("varchar(36)", columnType(snap, "uid"));
        assertEquals("decimal(12,2)", columnType(snap, "explicit_decimal"));
        assertEquals("int(11)", columnType(snap, "explicit_int"));
    }

    @Test
    @Order(2)
    void reSyncProducesZeroAlter() {
        List<JSONObject> before = snapshot();
        // 第二次同步：清出 template 表缓存，强制走 DB 元数据 diff 路径；若存在虚假漂移将改变快照
        template.clearTableCache();
        template.checkTable(DdlDriftEntity.class);
        assertEquals(before, snapshot(), "重复 checkTable 不应产生任何结构变化（零 ALTER）");

        // 第三次亦然（幂等性）
        template.clearTableCache();
        template.checkTable(DdlDriftEntity.class);
        assertEquals(before, snapshot(), "多次 checkTable 仍应零漂移");
    }

    @Test
    @Order(3)
    void realDriftIsDetectedAndRepaired() {
        try (QueryRunner qr = new QueryRunner(template)) {
            qr.update("ALTER TABLE `t_ddl_drift` MODIFY COLUMN `short_text` varchar(32) NOT NULL COMMENT '短字符串'");
        }
        String drifted = columnType(snapshot(), "short_text");
        assertEquals("varchar(32)", drifted, "手工漂移应生效于库端");

        template.clearTableCache();
        template.checkTable(DdlDriftEntity.class);

        String repaired = columnType(snapshot(), "short_text");
        assertEquals("varchar(64)", repaired, "真实漂移必须被检测并 MODIFY 回实体定义");
        assertNotEquals(drifted, repaired);
    }

}
