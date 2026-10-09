package com.wxdgaming.batis.sql.mysql;

import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.SqlType;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MysqlColumnTypeDefHandler 单元测试
 * <p>锁定两条不变式：</p>
 * <ol>
 *   <li>逻辑类型渲染结果与解耦前 EntityParser 直出的物理字面量逐字节一致；</li>
 *   <li>normalize 与解耦前 MysqlTableAlterHandler#normalizeType 在历史出现过的一切输入上等价。</li>
 * </ol>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
class MysqlColumnTypeDefHandlerTest {

    private final MysqlColumnTypeDefHandler handler = MysqlColumnTypeDefHandler.INSTANCE;

    private static ColumnInfo col(SqlType sqlType, int length, int scale) {
        ColumnInfo c = new ColumnInfo();
        c.setColumnName("test_col");
        c.setSqlType(sqlType);
        c.setLength(length);
        c.setScale(scale);
        return c;
    }

    private static ColumnInfo explicit(String physical) {
        ColumnInfo c = new ColumnInfo();
        c.setColumnName("test_col");
        c.setType(physical);
        return c;
    }

    // ==================== renderType ====================

    @Test
    void renderMatchesLegacyLiterals() {
        assertEquals("TINYINT(1)", handler.renderType(col(SqlType.BOOLEAN, 255, 0)));
        assertEquals("TINYINT", handler.renderType(col(SqlType.BYTE, 255, 0)));
        assertEquals("SMALLINT", handler.renderType(col(SqlType.SHORT, 255, 0)));
        assertEquals("INT", handler.renderType(col(SqlType.INT, 255, 0)));
        assertEquals("BIGINT", handler.renderType(col(SqlType.BIGINT, 255, 0)));
        assertEquals("FLOAT", handler.renderType(col(SqlType.FLOAT, 255, 0)));
        assertEquals("DOUBLE", handler.renderType(col(SqlType.DOUBLE, 255, 0)));
        assertEquals("DECIMAL(20,6)", handler.renderType(col(SqlType.DECIMAL, 20, 6)));
        assertEquals("VARCHAR(64)", handler.renderType(col(SqlType.VARCHAR, 64, 0)));
        assertEquals("VARCHAR(255)", handler.renderType(col(SqlType.VARCHAR, 255, 0)));
        assertEquals("CHAR(1)", handler.renderType(col(SqlType.CHAR, 0, 0)));
        assertEquals("TEXT", handler.renderType(col(SqlType.TEXT, 20000, 0)));
        assertEquals("MEDIUMTEXT", handler.renderType(col(SqlType.MEDIUMTEXT, 70000, 0)));
        assertEquals("LONGTEXT", handler.renderType(col(SqlType.LONGTEXT, 0, 0)));
        assertEquals("BLOB", handler.renderType(col(SqlType.BLOB, 0, 0)));
        assertEquals("MEDIUMBLOB", handler.renderType(col(SqlType.MEDIUMBLOB, 0, 0)));
        assertEquals("LONGBLOB", handler.renderType(col(SqlType.LONGBLOB, 0, 0)));
        assertEquals("DATE", handler.renderType(col(SqlType.DATE, 0, 0)));
        assertEquals("TIME", handler.renderType(col(SqlType.TIME, 0, 0)));
        assertEquals("DATETIME", handler.renderType(col(SqlType.DATETIME, 0, 0)));
        assertEquals("TIMESTAMP", handler.renderType(col(SqlType.TIMESTAMP, 0, 0)));
        // JSON 存储模式解耦前即为 LONGTEXT
        assertEquals("LONGTEXT", handler.renderType(col(SqlType.JSON, 255, 0)));
        assertEquals("MEDIUMINT", handler.renderType(col(SqlType.MEDIUMINT, 0, 0)));
    }

    @Test
    void explicitPhysicalTypeWinsVerbatim() {
        assertEquals("DECIMAL(20,2)", handler.renderType(explicit("DECIMAL(20,2)")));
        assertEquals("int(11) unsigned", handler.renderType(explicit("int(11) unsigned")));
    }

    @Test
    void renderRejectsColumnWithoutAnyType() {
        ColumnInfo c = new ColumnInfo();
        c.setColumnName("x");
        assertThrows(IllegalArgumentException.class, () -> handler.renderType(c));
    }

    @Test
    void everySqlTypeIsRenderableAndClassifiable() {
        for (SqlType t : SqlType.values()) {
            String physical = handler.renderType(col(t, 255, 2));
            assertNotNull(physical);
            // 渲染出的物理类型必须能回 classify 到稳定规格形式（round-trip 幂等）
            String normalized = handler.normalize(physical);
            assertEquals(normalized, handler.normalize(normalized), "normalize 幂等: " + t);
        }
    }

    // ==================== normalize：与旧实现逐例等价 ====================

    /** 解耦前 MysqlTableAlterHandler#normalizeType 的原样拷贝，作为行为基准 */
    private static String legacyNormalize(String type) {
        if (type == null) return "";
        String t = type.toLowerCase().trim();
        if (t.matches("^(tinyint|smallint|mediumint|int|bigint)(\\(\\d+\\))?.*$")) {
            t = t.replaceAll("\\(\\d+\\)", "");
        }
        return t;
    }

    @Test
    void normalizeEquivalentToLegacyOnHistoricalInputs() {
        String[] inputs = {
                "int(11)", "bigint(20)", "tinyint(4)", "tinyint(1)", "smallint(6)", "mediumint(9)",
                "varchar(64)", "varchar(255)", "char(1)", "text", "mediumtext", "longtext",
                "blob", "longblob", "datetime", "date", "time", "timestamp",
                "DECIMAL(20,6)", "double", "float",
                "int(11) unsigned", "bigint(20) unsigned",
                "INT", "BIGINT", "TINYINT(1)", "VARCHAR(64)", "LONGTEXT", "DATETIME",
        };
        for (String input : inputs) {
            assertEquals(legacyNormalize(input), handler.normalize(input),
                    "normalize 偏离旧行为: " + input);
        }
        assertEquals("", handler.normalize(null));
        // 有意的改进：旧实现对 "decimal(20, 6)" vs "decimal(20,6)" 判不等（虚假 MODIFY），新实现折叠参数空格
        assertEquals(handler.normalize("decimal(20,6)"), handler.normalize("decimal(20, 6)"));
    }

    @Test
    void renderNormalizedMatchesLegacyPipeline() {
        // 旧管线 = legacyNormalize(EntityParser直出字面量)；新管线 = renderNormalized
        assertEquals(legacyNormalize("TINYINT(1)"), handler.renderNormalized(col(SqlType.BOOLEAN, 255, 0)));
        assertEquals(legacyNormalize("VARCHAR(255)"), handler.renderNormalized(col(SqlType.VARCHAR, 255, 0)));
        assertEquals(legacyNormalize("DECIMAL(20,6)"), handler.renderNormalized(col(SqlType.DECIMAL, 20, 6)));
        assertEquals(legacyNormalize("BIGINT"), handler.renderNormalized(col(SqlType.BIGINT, 255, 0)));
        assertEquals(legacyNormalize("LONGTEXT"), handler.renderNormalized(col(SqlType.JSON, 255, 0)));
        assertEquals(legacyNormalize("DECIMAL(12,2)"), handler.renderNormalized(explicit("DECIMAL(12,2)")));
    }

    @Test
    void dbMetadataFormsClassifyBack() {
        // 库端元数据（information_schema.COLUMN_TYPE）与实体端渲染规格化对齐
        assertEquals(handler.renderNormalized(col(SqlType.BIGINT, 255, 0)), handler.normalize("bigint(20)"));
        assertEquals(handler.renderNormalized(col(SqlType.INT, 255, 0)), handler.normalize("int(11)"));
        assertEquals(handler.renderNormalized(col(SqlType.BOOLEAN, 255, 0)), handler.normalize("tinyint(1)"));
        assertEquals(handler.renderNormalized(col(SqlType.LONGTEXT, 0, 0)), handler.normalize("longtext"));
        assertEquals("decimal(12,2)", handler.normalize("decimal(12, 2)"));
    }

}
