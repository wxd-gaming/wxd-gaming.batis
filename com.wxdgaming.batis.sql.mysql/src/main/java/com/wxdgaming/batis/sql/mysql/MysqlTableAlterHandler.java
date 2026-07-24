package com.wxdgaming.batis.sql.mysql;

import com.wxdgaming.batis.annotation.GenerationType;
import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.IndexInfo;
import com.wxdgaming.batis.model.TableInfo;
import com.wxdgaming.batis.sql.JdbcTemplate;
import com.wxdgaming.batis.sql.QueryRunner;
import com.wxdgaming.batis.sql.TableAlterHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * MySQL 表结构变更处理器
 * <p>
 * 对比实体类定义的 TableInfo 与数据库现有表结构，生成 ALTER TABLE 语句，
 * 自动处理列的新增/修改、索引的新增/删除/重建。
 * </p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-24
 **/
@Slf4j
public class MysqlTableAlterHandler implements TableAlterHandler {

    @Override
    public void doAction(JdbcTemplate jdbcTemplate, TableInfo entityTable) {
        Map<String, TableInfo> cache = jdbcTemplate.getTableCache();
        if (cache == null) return;

        String tableName = entityTable.getTableName().toLowerCase();
        TableInfo dbTable = cache.get(tableName);
        if (dbTable == null) return;

        List<String> alterClauses = new ArrayList<>();

        // ===== 1. 列对比 =====
        for (ColumnInfo entityCol : entityTable.getColumns().values()) {
            String colName = entityCol.getColumnName().toLowerCase();
            ColumnInfo dbCol = dbTable.getColumn(colName);
            if (dbCol == null) {
                // 新增列
                alterClauses.add("ADD COLUMN " + buildColumnDef(entityCol));
            } else if (hasColumnChanged(entityCol, dbCol)) {
                // 修改列
                alterClauses.add("MODIFY COLUMN " + buildColumnDef(entityCol));
            }
        }

        // ===== 2. 索引对比 =====
        List<IndexInfo> entityIndexes = entityTable.getIndexes();
        List<IndexInfo> dbIndexes = dbTable.getIndexes();

        if (entityIndexes != null) {
            Map<String, IndexInfo> dbIndexMap = new HashMap<>();
            if (dbIndexes != null) {
                for (IndexInfo idx : dbIndexes) {
                    dbIndexMap.put(idx.getName().toLowerCase(), idx);
                }
            }

            for (IndexInfo entityIdx : entityIndexes) {
                String idxName = entityIdx.getName().toLowerCase();
                IndexInfo dbIdx = dbIndexMap.get(idxName);
                if (dbIdx == null) {
                    // 新增索引
                    alterClauses.add("ADD " + indexType(entityIdx) + "INDEX `" + entityIdx.getName() + "` (" + buildIndexColumns(entityIdx) + ")");
                } else if (hasIndexChanged(entityIdx, dbIdx)) {
                    // 索引发生了变化：先删除再重建
                    alterClauses.add("DROP INDEX `" + dbIdx.getName() + "`");
                    alterClauses.add("ADD " + indexType(entityIdx) + "INDEX `" + entityIdx.getName() + "` (" + buildIndexColumns(entityIdx) + ")");
                }
                dbIndexMap.remove(idxName);
            }

            // 删除实体中已移除的索引
            for (IndexInfo dropIdx : dbIndexMap.values()) {
                alterClauses.add("DROP INDEX `" + dropIdx.getName() + "`");
            }
        }

        // ===== 3. 执行 ALTER =====
        if (!alterClauses.isEmpty()) {
            StringBuilder sb = new StringBuilder(512);
            sb.append("ALTER TABLE `").append(tableName).append("`\n");
            sb.append(String.join(",\n", alterClauses));
            sb.append(";");
            log.debug("修改表sql:\n{}", sb.toString());
            try (QueryRunner queryRunner = new QueryRunner(jdbcTemplate)) {
                queryRunner.update(sb.toString());
            }
        }
    }

    /** 构建列定义片段（用于 ADD COLUMN / MODIFY COLUMN） */
    private String buildColumnDef(ColumnInfo col) {
        StringBuilder sb = new StringBuilder(64);
        sb.append("`").append(col.getColumnName().toLowerCase()).append("` ").append(col.getType());

        if (!col.isNullable()) {
            sb.append(" NOT NULL");
        }

        if (col.getDefaultValue() != null && !col.getDefaultValue().isEmpty()) {
            sb.append(" DEFAULT ").append(col.getDefaultValue());
        }

        if (col.getGenerationType() == GenerationType.IDENTITY && col.isPrimaryKey()) {
            sb.append(" AUTO_INCREMENT");
        }

        if (col.getComment() != null && !col.getComment().isEmpty()) {
            sb.append(" COMMENT '").append(escapeComment(col.getComment())).append("'");
        }

        return sb.toString();
    }

    /** 构建索引列列表片段 */
    private String buildIndexColumns(IndexInfo index) {
        List<String> quotedCols = new ArrayList<>();
        for (String colName : index.getColumns()) {
            quotedCols.add("`" + colName.toLowerCase() + "`");
        }
        return String.join(", ", quotedCols);
    }

    /** 返回索引类型前缀（UNIQUE 或空） */
    private String indexType(IndexInfo index) {
        return index.isUnique() ? "UNIQUE " : "";
    }

    /** 判断列是否发生了变化 */
    private boolean hasColumnChanged(ColumnInfo entityCol, ColumnInfo dbCol) {
        if (!normalizeType(entityCol.getType()).equals(normalizeType(dbCol.getType()))) return true;
        if (entityCol.isNullable() != dbCol.isNullable()) return true;
        if (!defaultValueEquals(entityCol.getDefaultValue(), dbCol.getDefaultValue())) return true;
        if (!Objects.equals(entityCol.getComment(), dbCol.getComment())) return true;
        return false;
    }

    /** 判断索引是否发生了变化 */
    private boolean hasIndexChanged(IndexInfo entityIdx, IndexInfo dbIdx) {
        if (entityIdx.isUnique() != dbIdx.isUnique()) return true;
        if (entityIdx.getColumns() == null || dbIdx.getColumns() == null) return true;
        if (entityIdx.getColumns().size() != dbIdx.getColumns().size()) return true;
        for (int i = 0; i < entityIdx.getColumns().size(); i++) {
            if (!entityIdx.getColumns().get(i).equalsIgnoreCase(dbIdx.getColumns().get(i))) return true;
        }
        return false;
    }

    /**
     * 标准化列类型以便比较
     * <ul>
     *   <li>转小写</li>
     *   <li>去掉整数类型的显示宽度（int(11) → int）</li>
     * </ul>
     */
    private String normalizeType(String type) {
        if (type == null) return "";
        String t = type.toLowerCase().trim();
        // int(11) → int, bigint(20) → bigint 等
        if (t.matches("^(tinyint|smallint|mediumint|int|bigint)(\\(\\d+\\))?.*$")) {
            t = t.replaceAll("\\(\\d+\\)", "");
        }
        return t;
    }

    /**
     * 比较默认值是否等价
     * <ul>
     *   <li>实体端 null/空字符串 均表示"未设置默认值"</li>
     *   <li>数据库端 null 表示"没有默认值"，字符串 "NULL" 表示 "DEFAULT NULL"</li>
     * </ul>
     */
    private boolean defaultValueEquals(String entityDefault, String dbDefault) {
        // 实体未设置默认值 && 数据库也无默认值 → 相等
        if ((entityDefault == null || entityDefault.isEmpty()) && dbDefault == null) return true;
        // 空字符串视为 null
        if (entityDefault != null && entityDefault.isEmpty()) entityDefault = null;
        // 实体 null == 数据库 "NULL"（即 DEFAULT NULL）
        if (entityDefault == null && "NULL".equals(dbDefault)) return true;
        return Objects.equals(entityDefault, dbDefault);
    }

    /** 转义 SQL 注释中的特殊字符 */
    private static String escapeComment(String comment) {
        return comment.replace("'", "\\'").replace("\n", "\\n");
    }
}
