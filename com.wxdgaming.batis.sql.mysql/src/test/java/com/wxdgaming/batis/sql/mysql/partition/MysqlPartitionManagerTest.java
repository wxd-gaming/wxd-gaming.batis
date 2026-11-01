package com.wxdgaming.batis.sql.mysql.partition;

import com.wxdgaming.batis.sql.partition.PartitionConfig;
import com.wxdgaming.batis.sql.partition.PartitionDef;
import com.wxdgaming.batis.sql.partition.PartitionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MySQL 分区 DDL 渲染与校验的离线验收测试。
 *
 * <p>DDL 无法参数化，所有标识符、表达式、边界值都是字符串拼接，因此这一层是框架里
 * 唯一需要自己动手做注入防护的地方。本类把三道闸门分别钉住：</p>
 * <ul>
 *   <li><b>标识符白名单</b>：表名/分区名只收字母数字与 {@code _$}，反引号、分号、空白一律拒绝；</li>
 *   <li><b>表达式白名单 + 括号配平 + 关键字拒绝</b>：挡住 {@code id) PARTITIONS 8} 这种
 *   "闭合括号再追加子句"的越界写法，也挡住 {@code select}/{@code union} 之类的语句注入；</li>
 *   <li><b>边界子句整串匹配</b>：只接受完整的 {@code VALUES LESS THAN (...)} /
 *   {@code VALUES LESS THAN MAXVALUE} / {@code VALUES IN (...)}，多一个分号都不放过。</li>
 * </ul>
 *
 * <p>渲染侧的断言只看关键字与参数是否真的进了 SQL，不做整串比对——换行与缩进是为可读性服务的，
 * 不该让它们成为测试的耦合点。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-11-01
 */
class MysqlPartitionManagerTest {

    private final MysqlPartitionManager manager = MysqlPartitionManager.INSTANCE;

    private static PartitionConfig config(String tableName, PartitionType type, String expression) {
        PartitionConfig config = new PartitionConfig();
        config.setTableName(tableName);
        config.setType(type);
        config.setExpression(expression);
        return config;
    }

    // ==================== 标识符 ====================

    @Test
    @DisplayName("表名/分区名含反引号、分号、空白或点号时拒绝渲染")
    void unsafeIdentifiersAreRejected() {
        for (String bad : new String[]{"t`x", "t;drop", "t x", "t.x", "", null}) {
            assertThrows(IllegalArgumentException.class,
                    () -> manager.buildTruncatePartition(config(bad, PartitionType.RANGE, "id")),
                    "非法表名必须被拒绝: " + bad);
        }

        PartitionConfig cfg = config("t", PartitionType.RANGE, "id");
        cfg.addPartition(new PartitionDef("p`1", "VALUES LESS THAN (10)"));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> manager.buildCreatePartition(cfg));
        assertTrue(ex.getMessage().contains("分区名"), ex.getMessage());
    }

    // ==================== 表达式 ====================

    @Test
    @DisplayName("表达式为空白、含非法字符、括号不配对或命中 SQL 关键字时拒绝")
    void unsafeExpressionsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> manager.buildCreatePartition(config("t", PartitionType.HASH, " ")));

        assertThrows(IllegalArgumentException.class,
                () -> manager.buildCreatePartition(config("t", PartitionType.HASH, "id) PARTITIONS 8")));

        assertThrows(IllegalArgumentException.class,
                () -> manager.buildCreatePartition(config("t", PartitionType.HASH, "YEAR(col")));

        assertThrows(IllegalArgumentException.class,
                () -> manager.buildCreatePartition(config("t", PartitionType.HASH, "id) -- 注释")));

        IllegalArgumentException keyword = assertThrows(IllegalArgumentException.class,
                () -> manager.buildCreatePartition(config("t", PartitionType.RANGE, "select")));
        assertTrue(keyword.getMessage().contains("关键字"), keyword.getMessage());

        // 下划线列名不能被词边界误伤
        String sql = manager.buildCreatePartition(config("t", PartitionType.HASH, "update_time"));
        assertTrue(sql.contains("HASH(update_time)"), sql);
    }

    @Test
    @DisplayName("合法的日期算式与函数表达式应通过校验")
    void ordinaryExpressionsPass() {
        String sql = manager.buildCreatePartition(config("t", PartitionType.RANGE,
                "YEAR(log_time)*10000 + MONTH(log_time)*100 + DAY(log_time)"));
        assertTrue(sql.contains("PARTITION BY RANGE(YEAR(log_time)*10000 + MONTH(log_time)*100 + DAY(log_time))"), sql);
    }

    // ==================== 边界子句 ====================

    @Test
    @DisplayName("边界子句必须是完整的 VALUES 形态：追加分号/注释/裸数字都拒绝")
    void unsafePartitionValuesAreRejected() {
        for (String bad : new String[]{
                "VALUES LESS THAN (1); DROP TABLE t",
                "VALUES LESS THAN (1) -- x",
                "1",
                "VALUES LESS THAN ()",
                "VALUES IN ('a\\\\b')"}) {
            PartitionConfig cfg = config("t", PartitionType.RANGE, "id");
            cfg.addPartition(new PartitionDef("p1", bad));
            assertThrows(IllegalArgumentException.class,
                    () -> manager.buildCreatePartition(cfg),
                    "非法边界子句必须被拒绝: " + bad);
        }
    }

    @Test
    @DisplayName("合法边界形态：数字、MAXVALUE、带转义单引号的字符串、LIST 枚举")
    void ordinaryPartitionValuesPass() {
        PartitionConfig range = config("t", PartitionType.RANGE, "id");
        range.addPartition(new PartitionDef("p1", "VALUES LESS THAN (20260802)"));
        range.addPartition(new PartitionDef("pmax", "VALUES LESS THAN MAXVALUE"));
        String rangeSql = manager.buildCreatePartition(range);
        assertTrue(rangeSql.contains("PARTITION `p1` VALUES LESS THAN (20260802)"), rangeSql);
        assertTrue(rangeSql.contains("PARTITION `pmax` VALUES LESS THAN MAXVALUE"), rangeSql);

        PartitionConfig list = config("t", PartitionType.LIST, "region");
        list.addPartition(new PartitionDef("p_east", "VALUES IN ('a''b', 'c')"));
        String listSql = manager.buildCreatePartition(list);
        assertTrue(listSql.contains("PARTITION BY LIST(region)"), listSql);
        assertTrue(listSql.contains("PARTITION `p_east` VALUES IN ('a''b', 'c')"), listSql);
    }

    // ==================== 渲染分支 ====================

    @Test
    @DisplayName("HASH/KEY/LINEAR_* 只用分区数分支；RANGE/LIST/COLUMNS 走逐定义分支")
    void renderingBranchesAreDistinct() {
        PartitionConfig linear = config("t", PartitionType.LINEAR_HASH, "id");
        linear.setPartitionCount(4);
        String linearSql = manager.buildCreatePartition(linear);
        assertTrue(linearSql.contains("PARTITION BY LINEAR HASH(id)"), linearSql);
        assertTrue(linearSql.contains("PARTITIONS 4"), linearSql);
        assertFalse(linearSql.contains("PARTITION `"), "HASH 分支不应渲染出分区定义");

        PartitionConfig columns = config("t", PartitionType.LIST_COLUMNS, "region,sub_region");
        columns.addPartition(new PartitionDef("p1", "VALUES IN ('east')"));
        String columnsSql = manager.buildCreatePartition(columns);
        assertTrue(columnsSql.contains("PARTITION BY LIST COLUMNS (region,sub_region)"), columnsSql);
    }

    @Test
    @DisplayName("缺少分区类型时拒绝渲染，而不是拼出不完整的 PARTITION BY")
    void nullTypeIsRejected() {
        PartitionConfig cfg = new PartitionConfig();
        cfg.setTableName("t");
        cfg.setExpression("id");
        assertThrows(IllegalArgumentException.class, () -> manager.buildCreatePartition(cfg));
    }

    @Test
    @DisplayName("DROP 多个分区合并进一条 ALTER；TRUNCATE 保留分区定义")
    void maintenanceRendering() {
        PartitionConfig drop = new PartitionConfig();
        drop.setTableName("t");
        drop.addDropName("p1");
        drop.addDropName("p2");
        assertTrue(manager.buildDropPartition(drop)
                        .contains("DROP PARTITION `p1`, DROP PARTITION `p2`"),
                manager.buildDropPartition(drop));

        PartitionConfig truncate = new PartitionConfig();
        truncate.setTableName("t");
        truncate.setTruncateName("p1");
        assertTrue(manager.buildTruncatePartition(truncate).endsWith("TRUNCATE PARTITION `p1`;"));
    }

    @Test
    @DisplayName("REORGANIZE 把原分区名与新定义都安全地拼进 DDL")
    void reorganizeRendering() {
        PartitionConfig cfg = new PartitionConfig();
        cfg.setTableName("t");
        cfg.setReorganizeOldName("pmax");
        cfg.addReorganizeDef(new PartitionDef("p202609", "VALUES LESS THAN (202610)"));
        cfg.addReorganizeDef(new PartitionDef("pmax", "VALUES LESS THAN MAXVALUE"));

        String sql = manager.buildReorganizePartition(cfg);

        assertTrue(sql.contains("REORGANIZE PARTITION `pmax` INTO ("), sql);
        assertTrue(sql.contains("PARTITION `p202609` VALUES LESS THAN (202610)"), sql);
        assertTrue(sql.contains("PARTITION `pmax` VALUES LESS THAN MAXVALUE"), sql);
    }
}
