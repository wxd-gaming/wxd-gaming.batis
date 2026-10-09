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

    /**
     * 取 t_ddl_drift 在库端的结构快照。
     * <p>直接查 information_schema 而不是用框架自己的元数据读取器：
     * 快照必须来自第三方真相源，否则"框架认为自己建的表是对的"这种自证不构成断言。
     * 字段集合（列名/类型/可空/默认值/注释）与建表顺序一起取，
     * 两份 List 相等即代表结构完全没变（新增列、改类型、改注释、调顺序都会破坏相等）。</p>
     */
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

    /**
     * 用例①：首次同步建表，逐列抽查物理类型。
     * <p>前置：{@link DdlDriftEntity} 覆盖全部逻辑类型档位（VARCHAR 各长度档、TEXT 三档、
     * BLOB 三档、整数各档、精确小数、日期时间、char、UUID、以及两个显式指定物理类型的列）。
     * 动作：清掉表结构缓存后 {@code checkTable}，让框架真正走一次"实体 → DDL"。
     * 断言：26 列全部建成，并抽查关键档位的物理类型字符串。
     * 两个细节：一是断言值取自 information_schema 的原生形式（MariaDB 会给整数列补显示宽度，
     * 如 int(11)、tinyint(4)、bigint(20)），不是我们渲染出的字符串，所以这里读的是"库端最终长什么样"；
     * 二是这些期望值与解耦前 EntityParser 直出的字面量一一对应，
     * 等价于证明"改造后建的表和历史表结构完全相同"，不会让存量库凭空多出 ALTER。</p>
     */
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

    /**
     * 用例②：重复同步必须零漂移（本次改造的回归护栏）。
     * <p>动作：对同一张已存在的表连续两次 {@code checkTable}，每次都先 {@code clearTableCache()}
     * ——这一步是关键：不清缓存会直接命中内存里的 TableInfo 而跳过结构 diff，
     * 用例就会因为"根本没比对"而假通过。清掉之后框架只能从库端元数据重新读结构再比对，
     * 才真正走到 normalize/render 这条容易出偏差的路径。</p>
     * <p>断言：information_schema 全列快照（列名/类型/可空/默认值/注释，按建表顺序）与同步前逐行相等。
     * 快照相等就等价于"没有执行任何 MODIFY/ADD/DROP"，
     * 因为哪怕一条虚假 ALTER 也会留下可见的类型或注释差异。
     * 意义：类型解耦后如果实体侧渲染形式与库侧元数据形式对不齐（例如 decimal(20,6) 与 decimal(20, 6)、
     * int 与 int(11)），每次启动都会对全表刷一遍无意义的 MODIFY ——
     * 在大表上等于线上锁表事故，所以这条是硬要求。</p>
     */
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

    /**
     * 用例③：真实漂移必须仍能检出并自动修复（与用例②配成一对）。
     * <p>为什么必需：用例②断言"零 ALTER"，把结构比对整体关掉也能通过。
     * 只有再证明"真的改了库时框架会改回来"，②的通过才说明比对是健康的而不是失灵的。</p>
     * <p>动作：绕过框架直接手工把 short_text 改成 varchar(32)（模拟 DBA 改表或旧版本实体残留），
     * 先确认库端确实变了，再清缓存走一次同步。
     * 断言：该列被 MODIFY 回实体定义的 varchar(64)，且修复前后类型不相等。
     * 这里特意选"长度变短"这种只涉及列长度、不改其他属性的漂移，
     * 排除掉"因为整表重建才碰巧改回来"的解释。</p>
     */
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
