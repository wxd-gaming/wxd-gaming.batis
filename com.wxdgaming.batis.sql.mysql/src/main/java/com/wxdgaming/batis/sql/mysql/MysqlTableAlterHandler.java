package com.wxdgaming.batis.sql.mysql;

import com.wxdgaming.batis.annotation.GenerationType;
import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.IndexInfo;
import com.wxdgaming.batis.model.TableInfo;
import com.wxdgaming.batis.sql.ColumnTypeDefHandler;
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

    /**
     * 结构 diff 主流程：实体侧 TableInfo 对比库端缓存 TableInfo，把差异汇总成一条 ALTER TABLE 执行。
     * <p>库端结构来自 {@code template.getTableCache()}（由 MysqlDbMetaHandler 从 information_schema 回读）；
     * 缓存为 null 说明尚未加载，为 null 的表名说明库里还没有该表（应由 create 分支处理），两种情况本方法都直接返回。</p>
     * <p>所有变更合并进单条 ALTER 的多子句（逗号分隔）一次执行，减少一次表重建/加锁往返。
     * 关键约束：比对是否"改列"完全依赖 {@code typeDef.renderNormalized(实体列) == typeDef.normalize(库端类型)}
     * 这一等式（见 {@link #hasColumnChanged}），一旦 normalize/render 不同义，启动时就会对全表刷虚假 MODIFY
     * （回归护栏见 SchemaDriftIT）。</p>
     */
    @Override
    public void doAction(JdbcTemplate jdbcTemplate, TableInfo entityTable) {
        Map<String, TableInfo> cache = jdbcTemplate.getTableCache();
        if (cache == null) return;

        String tableName = entityTable.getTableName().toLowerCase();
        TableInfo dbTable = cache.get(tableName);
        if (dbTable == null) return;

        // 优先用注入的方言渲染器，未注入时回退 MySQL 默认，保证纯 MySQL 场景仍可比对
        ColumnTypeDefHandler typeDef = jdbcTemplate.getColumnTypeDefHandler() != null
                ? jdbcTemplate.getColumnTypeDefHandler() : MysqlColumnTypeDefHandler.INSTANCE;

        List<String> alterClauses = new ArrayList<>();

        // ===== 1. 列对比 =====
        for (ColumnInfo entityCol : entityTable.getColumns().values()) {
            String colName = entityCol.getColumnName().toLowerCase();
            ColumnInfo dbCol = dbTable.getColumn(colName);
            if (dbCol == null) {
                // 新增列
                alterClauses.add("ADD COLUMN " + buildColumnDef(entityCol, typeDef));
            } else if (hasColumnChanged(entityCol, dbCol, typeDef)) {
                // 修改列
                alterClauses.add("MODIFY COLUMN " + buildColumnDef(entityCol, typeDef));
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
                // 命中实体即从库端索引表移除；循环结束后剩下的就是"库里有、实体已不要"的索引
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

    /**
     * 构建列定义片段（用于 ADD COLUMN / MODIFY COLUMN）。
     * <p>必须与 {@code MysqlTableCreateHandler} 的同名片段逐字符一致：否则首次建表写成 A、下次同步
     * 又认为实体是 B，会对同一列反复 MODIFY，破坏幂等。</p>
     */
    private String buildColumnDef(ColumnInfo col, ColumnTypeDefHandler typeDef) {
        StringBuilder sb = new StringBuilder(64);
        sb.append("`").append(col.getColumnName().toLowerCase()).append("` ").append(typeDef.renderType(col));

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

    /**
     * 判断列是否发生变化：逐项比较规格化类型、可空性、默认值、注释。
     * <p>类型比较走 normalize 后的字符串而非原始字面量——实体侧渲染大写 {@code INT}、库端回读小写带宽度
     * {@code int(11)}，只有归一到同一形式才不会误判漂移；其余三项只要有一项不等即判定需 MODIFY。</p>
     */
    private boolean hasColumnChanged(ColumnInfo entityCol, ColumnInfo dbCol, ColumnTypeDefHandler typeDef) {
        if (!typeDef.renderNormalized(entityCol).equals(typeDef.normalize(dbCol.getType()))) return true;
        if (entityCol.isNullable() != dbCol.isNullable()) return true;
        if (!defaultValueEquals(entityCol.getDefaultValue(), dbCol.getDefaultValue())) return true;
        if (!Objects.equals(normalizeComment(entityCol.getComment()), normalizeComment(dbCol.getComment()))) return true;
        return false;
    }

    /**
     * 注释比对前的归一：null 与空串视为等价。
     * <p>实体侧零配置列（无 @Column 注解）comment 为 null，库端回读时 {@code MysqlDbMetaHandler}
     * 会把 COLUMN_COMMENT 为 null 归一成空串，两者若不归一就会 {@code Objects.equals(null, "")} 恒为 false，
     * 每次 checkTable 都对所有零配置列发虚假 MODIFY COLUMN（存量大表反复重建锁表）。</p>
     */
    private static String normalizeComment(String comment) {
        return comment == null ? "" : comment;
    }

    /**
     * 判断索引是否变化：唯一性、列数量、逐位列名（忽略大小写）任一不同即视为变化。
     * <p>复合索引按位比较，因为 (a,b) 与 (b,a) 是两个服务能力不同的索引，顺序变了必须重建而非放过。</p>
     */
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
