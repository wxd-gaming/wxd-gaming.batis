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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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

        String tableName = entityTable.getTableName().toLowerCase(Locale.ROOT);
        TableInfo dbTable = cache.get(tableName);
        if (dbTable == null) return;
        // 库端真实表名（可能含大写）：建表用的是原值，同步也必须用原值，否则
        // lower_case_table_names=0 的库上 ALTER TABLE 会指向一张不存在的表（H7）
        String physicalTableName = dbTable.getTableName() != null ? dbTable.getTableName() : tableName;

        // 优先用注入的方言渲染器，未注入时回退 MySQL 默认，保证纯 MySQL 场景仍可比对
        ColumnTypeDefHandler typeDef = jdbcTemplate.getColumnTypeDefHandler() != null
                ? jdbcTemplate.getColumnTypeDefHandler() : MysqlColumnTypeDefHandler.INSTANCE;

        List<String> alterClauses = new ArrayList<>();

        // ===== 0. 表级注释 =====
        // 只处理"实体声明了注释且与库端不同"的情形：实体没写注释时不去清空库端注释，
        // 避免存量表接入框架时把 DBA 写的表注释抹掉
        String entityComment = normalizeComment(entityTable.getComment());
        if (!entityComment.isEmpty() && !entityComment.equals(normalizeComment(dbTable.getComment()))) {
            alterClauses.add("COMMENT='" + MysqlSqlLiteral.escapeString(entityComment) + "'");
        }

        // ===== 1. 列对比 =====
        for (ColumnInfo entityCol : entityTable.getColumns().values()) {
            String colName = entityCol.getColumnName().toLowerCase(Locale.ROOT);
            ColumnInfo dbCol = dbTable.getColumn(colName);
            if (dbCol == null) {
                // 新增列
                alterClauses.add("ADD COLUMN " + buildColumnDef(entityCol, typeDef));
            } else {
                warnCharacterSetDrift(tableName, colName, dbCol);
                if (!expectsAutoIncrement(entityCol)) {
                    warnUnexpectedAutoIncrement(tableName, colName, dbCol);
                }
                if (hasColumnChanged(entityCol, dbCol, typeDef)) {
                    // 修改列
                    alterClauses.add("MODIFY COLUMN " + buildColumnDef(entityCol, typeDef));
                }
            }
        }

        // ===== 2. 索引对比 =====
        List<IndexInfo> entityIndexes = entityTable.getIndexes();
        List<IndexInfo> dbIndexes = dbTable.getIndexes();

        if (entityIndexes != null) {
            Map<String, IndexInfo> dbIndexMap = new HashMap<>();
            if (dbIndexes != null) {
                for (IndexInfo idx : dbIndexes) {
                    dbIndexMap.put(idx.getName().toLowerCase(Locale.ROOT), idx);
                }
            }

            for (IndexInfo entityIdx : entityIndexes) {
                String idxName = entityIdx.getName().toLowerCase(Locale.ROOT);
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
            // 外键约束依赖的索引不能删：MySQL 会以 1553 拒绝，整条 ALTER 因此失败；
            // 即使驱动吞掉错误，删掉之后外键也会失去可用索引。只在真要删时才查（懒加载）
            Set<String> fkIndexes = jdbcTemplate.isDropRedundantIndexes() && !dbIndexMap.isEmpty()
                    ? foreignKeyIndexNames(jdbcTemplate, physicalTableName) : Set.of();
            for (IndexInfo dropIdx : dbIndexMap.values()) {
                if (fkIndexes.contains(dropIdx.getName().toLowerCase(Locale.ROOT))) {
                    log.warn("表 {} 的索引 {} 是外键约束依赖的索引，跳过删除（实体未声明但外键需要它）",
                            physicalTableName, dropIdx.getName());
                    continue;
                }
                if (jdbcTemplate.isDropRedundantIndexes()) {
                    alterClauses.add("DROP INDEX `" + dropIdx.getName() + "`");
                    log.warn("表 {} 的索引 {} 在实体中没有声明，将被 DROP（需要保留请补上注解，"
                                    + "或设置 JdbcTemplate#setDropRedundantIndexes(false)）",
                            physicalTableName, dropIdx.getName());
                } else {
                    log.warn("表 {} 的索引 {} 在实体中没有声明，按配置跳过删除（dropRedundantIndexes=false）",
                            physicalTableName, dropIdx.getName());
                }
            }
        }

        // ===== 3. 执行 ALTER =====
        if (!alterClauses.isEmpty()) {
            StringBuilder sb = new StringBuilder(512);
            sb.append("ALTER TABLE `").append(physicalTableName).append("`\n");
            sb.append(String.join(",\n", alterClauses));
            sb.append(";");
            log.debug("修改表sql:\n{}", sb.toString());
            // 独立连接执行：DDL 不得复用线程事务连接（MySQL 的 DDL 会隐式提交事务），
            // 且带语句超时，避免等待元数据锁时无限期挂起（H6）
            jdbcTemplate.executeDdl(sb.toString());
        }
    }

    /**
     * 查询"外键约束依赖的索引名"（小写）。
     * <p>MySQL 不会单独暴露"FK 用的是哪个索引"，这里按"索引包含外键列"做上界判断：
     * 宁可多保护几个索引，也不能把外键依赖的那个删掉。查询失败时返回空集合并 warn——
     * 结构同步不应因为一条元数据查询失败而整表跳过，但要让人知道这次没有保护。
     */
    private static Set<String> foreignKeyIndexNames(JdbcTemplate jdbcTemplate, String tableName) {
        String sql = "SELECT DISTINCT s.INDEX_NAME FROM information_schema.STATISTICS s "
                + "JOIN information_schema.KEY_COLUMN_USAGE k "
                + "  ON k.TABLE_SCHEMA = s.TABLE_SCHEMA AND k.TABLE_NAME = s.TABLE_NAME "
                + " AND k.COLUMN_NAME = s.COLUMN_NAME AND k.REFERENCED_TABLE_NAME IS NOT NULL "
                + "WHERE s.TABLE_SCHEMA = DATABASE() AND s.TABLE_NAME = ?";
        try (QueryRunner queryRunner = new QueryRunner(jdbcTemplate)) {
            List<String> names = queryRunner.query(sql, new Object[]{tableName}, rs -> {
                List<String> list = new ArrayList<>();
                while (rs.next()) {
                    String name = rs.getString(1);
                    if (name != null) list.add(name.toLowerCase(Locale.ROOT));
                }
                return list;
            });
            return new HashSet<>(names);
        } catch (RuntimeException e) {
            log.warn("读取表 {} 的外键索引失败，本次不保护外键索引: {}", tableName, e.getMessage());
            return Set.of();
        }
    }

    /**
     * 构建列定义片段（用于 ADD COLUMN / MODIFY COLUMN）。
     * <p>必须与 {@code MysqlTableCreateHandler} 的同名片段逐字符一致：否则首次建表写成 A、下次同步
     * 又认为实体是 B，会对同一列反复 MODIFY，破坏幂等。</p>
     */
    private String buildColumnDef(ColumnInfo col, ColumnTypeDefHandler typeDef) {
        StringBuilder sb = new StringBuilder(64);
        sb.append("`").append(col.getColumnName().toLowerCase(Locale.ROOT)).append("` ").append(typeDef.renderType(col));

        if (!col.isNullable()) {
            sb.append(" NOT NULL");
        }

        if (col.getDefaultValue() != null && !col.getDefaultValue().isEmpty()) {
            // 默认值按 SQL 字面量渲染：已带引号的转义后拼接，裸字面量走白名单校验，
            // 越界输入在这里快速失败，而不是把半截 DDL 发到库端
            sb.append(" DEFAULT ").append(MysqlSqlLiteral.defaultValue(col.getDefaultValue()));
        }

        if (col.getGenerationType() == GenerationType.IDENTITY && col.isPrimaryKey()) {
            sb.append(" AUTO_INCREMENT");
        }

        if (col.getComment() != null && !col.getComment().isEmpty()) {
            sb.append(" COMMENT '").append(MysqlSqlLiteral.escapeString(col.getComment())).append("'");
        }

        return sb.toString();
    }

    /** 构建索引列列表片段 */
    private String buildIndexColumns(IndexInfo index) {
        List<String> quotedCols = new ArrayList<>();
        for (String colName : index.getColumns()) {
            quotedCols.add("`" + colName.toLowerCase(Locale.ROOT) + "`");
        }
        return String.join(", ", quotedCols);
    }

    /** 返回索引类型前缀（UNIQUE 或空） */
    private String indexType(IndexInfo index) {
        return index.isUnique() ? "UNIQUE " : "";
    }

    /**
     * 判断列是否发生变化：逐项比较规格化类型、可空性、默认值、注释、自增属性。
     * <p>类型比较走 normalize 后的字符串而非原始字面量——实体侧渲染大写 {@code INT}、库端回读小写带宽度
     * {@code int(11)}，只有归一到同一形式才不会误判漂移；其余各项只要有一项不等即判定需 MODIFY。</p>
     */
    private boolean hasColumnChanged(ColumnInfo entityCol, ColumnInfo dbCol, ColumnTypeDefHandler typeDef) {
        if (!typeDef.renderNormalized(entityCol).equals(typeDef.normalize(dbCol.getType()))) return true;
        if (entityCol.isNullable() != dbCol.isNullable()) return true;
        if (!defaultValueEquals(entityCol.getDefaultValue(), dbCol.getDefaultValue())) return true;
        if (!Objects.equals(normalizeComment(entityCol.getComment()), normalizeComment(dbCol.getComment()))) return true;
        // 实体声明了 IDENTITY 而库端列不是 AUTO_INCREMENT：插入拿不到生成键（主键回写为 null），
        // 属于功能缺失，必须修回来。反方向（库端有自增而实体没声明）只告警不改动：
        // 存量表人工加过自增的情况很常见，直接 MODIFY 会把自增属性摘掉。
        if (expectsAutoIncrement(entityCol) && !hasAutoIncrement(dbCol)) {
            return true;
        }
        return false;
    }

    /** 实体是否要求该列为 AUTO_INCREMENT（自增主键） */
    private static boolean expectsAutoIncrement(ColumnInfo col) {
        return col.isPrimaryKey() && col.getGenerationType() == GenerationType.IDENTITY;
    }

    /** 库端列是否带 auto_increment（来自 information_schema.COLUMNS.EXTRA） */
    private static boolean hasAutoIncrement(ColumnInfo dbCol) {
        String extra = dbCol.getExtra();
        return extra != null && extra.toLowerCase(Locale.ROOT).contains("auto_increment");
    }

    /**
     * 字符集漂移只告警不修复。
     * <p>
     * 库端列是 latin1、实体期望 utf8mb4 时，{@code COLUMN_TYPE} 两边都是 {@code varchar(64)}，
     * 类型比对因此"一致"，框架会一直宣称结构没问题，而写入 emoji 时严格模式报 1366、非严格模式被截断成 ?。
     * </p>
     * <p>之所以只告警：实体注解目前无法表达 charset/collation，自动发 MODIFY 等于替用户决定
     * （可能把有意的 latin1 列改掉）。给出明确日志让人来决定。
     * </p>
     */
    private static void warnCharacterSetDrift(String tableName, String colName, ColumnInfo dbCol) {
        String charset = dbCol.getCharacterSet();
        if (charset == null || charset.isEmpty() || "utf8mb4".equalsIgnoreCase(charset)) return;
        String type = dbCol.getType() == null ? "" : dbCol.getType().toLowerCase(Locale.ROOT);
        if (!type.contains("char") && !type.contains("text")) return;
        log.warn("列 {}.{} 的库端字符集是 {}，框架建表统一用 utf8mb4，"
                        + "字符集不会被自动修复（实体无对应注解），如需统一请手工执行 ALTER TABLE ... CONVERT TO CHARACTER SET utf8mb4",
                tableName, colName, charset);
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
     * <p>库端形态超出实体注解表达能力的索引（表达式索引、FULLTEXT/SPATIAL、前缀索引、DESC 索引）
     * 一律只告警、不自动重建：{@code @Index} 只能表达"普通 B-tree 全列升序索引"，
     * 拿实体定义去 DROP + CREATE 会把 DBA 手工建的函数索引/全文索引/前缀索引覆盖成普通索引——
     * 这是不可逆的性能事故，而"检测到并提示"已经消除了原来的静默盲区。</p>
     */
    private boolean hasIndexChanged(IndexInfo entityIdx, IndexInfo dbIdx) {
        if (dbIdx.isExpressionIndex()) {
            log.warn("索引 {} 在库端是表达式（函数）索引，实体 @Index 无法表达，跳过自动比对与重建；"
                    + "如需变更请手工 ALTER", dbIdx.getName());
            return false;
        }
        if (dbIdx.getIndexType() != null && !"BTREE".equalsIgnoreCase(dbIdx.getIndexType())) {
            log.warn("索引 {} 在库端类型为 {}，实体 @Index 只能表达 BTREE，跳过自动比对与重建；"
                    + "如需变更请手工 ALTER", dbIdx.getName(), dbIdx.getIndexType());
            return false;
        }
        if (hasPrefixColumn(dbIdx)) {
            log.warn("索引 {} 在库端含前缀列（SUB_PART>0），实体 @Index 无法表达前缀长度，"
                    + "跳过自动比对与重建；如需全列索引请手工先 DROP 再交给框架创建", dbIdx.getName());
            return false;
        }
        if (hasDescendingColumn(dbIdx)) {
            log.warn("索引 {} 在库端含降序列（COLLATION=D），实体 @Index 无法表达排序方向，"
                    + "跳过自动比对与重建；如需变更请手工 ALTER", dbIdx.getName());
            return false;
        }
        if (entityIdx.isUnique() != dbIdx.isUnique()) return true;
        if (entityIdx.getColumns() == null || dbIdx.getColumns() == null) return true;
        if (entityIdx.getColumns().size() != dbIdx.getColumns().size()) return true;
        for (int i = 0; i < entityIdx.getColumns().size(); i++) {
            if (!entityIdx.getColumns().get(i).equalsIgnoreCase(dbIdx.getColumns().get(i))) return true;
        }
        return false;
    }

    /** 库端索引是否含前缀列（SUB_PART &gt; 0） */
    private static boolean hasPrefixColumn(IndexInfo idx) {
        List<Integer> prefixes = idx.getPrefixLengths();
        if (prefixes == null) return false;
        for (Integer prefix : prefixes) {
            if (prefix != null && prefix > 0) return true;
        }
        return false;
    }

    /** 库端索引是否含降序列（COLLATION=D） */
    private static boolean hasDescendingColumn(IndexInfo idx) {
        List<Boolean> descending = idx.getDescending();
        if (descending == null) return false;
        for (Boolean desc : descending) {
            if (Boolean.TRUE.equals(desc)) return true;
        }
        return false;
    }

    /**
     * 比较默认值是否等价。
     * <p>两侧的书写形态本来就不同：实体注解写的是完整 DDL 字面量（字符串必须带引号，见
     * {@link MysqlSqlLiteral#defaultValue}），而 {@code information_schema.COLUMN_DEFAULT} 返回的是
     * <b>不带引号</b>的值——{@code 'abc'} 返回 {@code abc}、{@code CURRENT_TIMESTAMP} 返回
     * {@code current_timestamp()}、{@code 0} 在 DECIMAL(10,2) 上返回 {@code 0.00}。
     * 直接 {@code Objects.equals} 会把这些永久判成不同，每次启动都发 MODIFY，大表上等于每次启动锁表。</p>
     * <p>折叠只消除"同一语义的书写差异"：字符串去外层引号后<b>区分大小写</b>比较；
     * 时间函数统一小写并去掉空括号；纯数字按 {@link BigDecimal} 比较（0 == 0.00）；
     * TRUE/FALSE 折叠为 1/0。</p>
     */
    private boolean defaultValueEquals(String entityDefault, String dbDefault) {
        String entity = normalizeDefaultLiteral(entityDefault);
        String db = normalizeDefaultLiteral(dbDefault);
        if (entity == null) {
            return db == null || "null".equals(db);
        }
        return entity.equals(db);
    }

    /** 把默认值字面量折叠成可比较键；null/空串表示"未设置默认值" */
    private static String normalizeDefaultLiteral(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isEmpty()) return null;

        boolean quoted = value.length() >= 2
                && value.charAt(0) == '\'' && value.charAt(value.length() - 1) == '\'';
        if (quoted) {
            // 实体侧是 SQL 字面量，'' 表示一个单引号；库端返回的是解转义后的真实值
            return value.substring(1, value.length() - 1).replace("''", "'");
        }

        String lower = value.toLowerCase(Locale.ROOT);
        if ("null".equals(lower)) return "null";
        if ("true".equals(lower)) return "1";
        if ("false".equals(lower)) return "0";
        String temporal = normalizeTemporalFunction(lower);
        if (temporal != null) return temporal;
        if (lower.matches("[+-]?\\d+(\\.\\d+)?([eE][+-]?\\d+)?")) {
            try {
                return new BigDecimal(lower).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException ignored) {
                // 落到下面的原样返回
            }
        }
        // 未识别：按原始大小写比较。裸词（如枚举/字符串默认值）在 MySQL 里同样合法
        return value;
    }

    /** 时间函数族折叠：current_timestamp() / CURRENT_TIMESTAMP / now() 视为同一语义，保留精度后缀 */
    private static String normalizeTemporalFunction(String lower) {
        for (String fn : new String[]{"current_timestamp", "localtimestamp", "current_date", "current_time", "localtime"}) {
            if (lower.startsWith(fn)) {
                String suffix = lower.substring(fn.length()).trim();
                if (suffix.isEmpty() || suffix.equals("()")) {
                    return fn;
                }
                return fn + suffix;
            }
        }
        if (lower.equals("now()") || lower.equals("now")) {
            return "current_timestamp";
        }
        return null;
    }

    /**
     * 库端是自增列、实体却没声明 {@code IDENTITY} 时告警（不改动结构）。
     * <p>反方向（实体要自增、库端没有）会走 MODIFY COLUMN 修回来；这个方向只提示，
     * 因为存量表人工加过自增很常见，直接摘掉自增属性风险大于收益。</p>
     */
    private static void warnUnexpectedAutoIncrement(String tableName, String colName, ColumnInfo dbCol) {
        if (hasAutoIncrement(dbCol)) {
            log.warn("列 {}.{} 在库端是 AUTO_INCREMENT，实体没有声明 @GeneratedValue(strategy = IDENTITY)，"
                            + "框架不会自动摘除该属性；若确实不需要请手工 ALTER", tableName, colName);
        }
    }
}
