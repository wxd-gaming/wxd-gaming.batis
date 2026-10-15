package com.wxdgaming.batis.sql.pgsql;

import com.wxdgaming.batis.annotation.GenerationType;
import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.IndexInfo;
import com.wxdgaming.batis.model.TableInfo;
import com.wxdgaming.batis.sql.ColumnTypeDefHandler;
import com.wxdgaming.batis.sql.DbMetaHandler;
import com.wxdgaming.batis.sql.JdbcTemplate;
import com.wxdgaming.batis.sql.QueryRunner;
import com.wxdgaming.batis.sql.TableAlterHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * PostgreSQL 表结构变更处理器
 * <p>
 * 对比实体类定义的 TableInfo 与数据库现有表结构，生成 ALTER TABLE 语句，
 * 自动处理列的新增/属性修改、索引的新增/删除/重建，以及表/列注释的更新。
 * </p>
 * <p><b>与 MySQL 版的实质差别：MySQL 用一条 {@code MODIFY COLUMN <完整列定义>} 覆盖一切，
 * PG 的 ALTER 是按属性拆开的</b>（{@code ALTER COLUMN ... TYPE / SET NOT NULL / SET DEFAULT}），
 * 所以本类逐项判定、只发真正变化的那几项。这不是啰嗦：PG 的
 * {@code ALTER COLUMN ... TYPE} 会<b>整表重写</b>，若照 MySQL 的习惯"有一项不同就重写列定义"，
 * 改一个可空性就要复制一遍全表。</p>
 * <p>同理，索引与注释在 PG 里是独立对象，不能塞进同一条 ALTER 的多子句，
 * 因此产出顺序固定为：一条多子句 ALTER（列级变更，仍合并以只取一次锁）→ 各索引语句 → 各注释语句。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-14
 **/
@Slf4j
public class PgsqlTableAlterHandler implements TableAlterHandler {

    /** 无状态处理器，供测试与 template 复用同一实例 */
    public static final PgsqlTableAlterHandler INSTANCE = new PgsqlTableAlterHandler();

    /**
     * PG 把布尔默认值回读成 true/false，实体侧常按 MySQL 习惯写 0/1，比对前先折成同一种拼写。
     * <p>带引号的形态（{@code 'false'}/{@code '0'}）也要收进来：布尔列的 {@code DEFAULT 'false'}
     * 回读是<b>不带引号</b>的 {@code false}（字面量按列类型求值后展示），只折裸写法的话
     * 两侧永远不等，每次启动都会白发一条 SET DEFAULT。</p>
     */
    private static final Map<String, String> BOOLEAN_DEFAULT_SYNONYMS = Map.of(
            "0", "false", "1", "true", "false", "false", "true", "true",
            "'0'", "false", "'1'", "true", "'false'", "false", "'true'", "true");

    /**
     * 结构 diff 主流程：实体侧 TableInfo 对比库端缓存 TableInfo，差异汇总后执行。
     * <p>库端结构来自 {@code template.getTableCache()}（由 {@link PgsqlDbMetaHandler} 从 pg_catalog 回读）；
     * 缓存为 null 说明尚未加载，取不到本表说明库里还没有它（应由 create 分支处理），两种情况都直接返回。</p>
     * <p>关键约束：判"类型是否变了"完全依赖 {@code typeDef.renderNormalized(实体列) == typeDef.normalize(库端类型)}
     * 这一等式，两侧任何一对同义词没折到同一个键，启动时就会对全表刷虚假的整表重写
     * （回归护栏见 PgSchemaDriftIT 与 {@link PgsqlColumnTypeDefHandlerTest}）。</p>
     */
    @Override
    public void doAction(JdbcTemplate jdbcTemplate, TableInfo entityTable) {
        Map<String, TableInfo> cache = jdbcTemplate.getTableCache();
        if (cache == null) return;

        String tableName = entityTable.getTableName().toLowerCase(Locale.ROOT);
        TableInfo dbTable = cache.get(tableName);
        if (dbTable == null) return;

        // 优先用注入的方言渲染器，未注入时回退 PG 默认，保证纯 PG 场景仍可比对
        ColumnTypeDefHandler typeDef = jdbcTemplate.getColumnTypeDefHandler() != null
                ? jdbcTemplate.getColumnTypeDefHandler() : PgsqlColumnTypeDefHandler.INSTANCE;

        List<String> alterClauses = new ArrayList<>();
        List<String> deferredStatements = new ArrayList<>();

        String quotedTable = PgsqlIdentifier.quote(tableName);

        // ===== 0. 表级注释 =====
        // 只处理"实体声明了注释且与库端不同"的情形：实体没写注释时不去清空库端注释，
        // 避免存量表接入框架时把 DBA 写的表注释抹掉
        String entityComment = normalizeComment(entityTable.getComment());
        if (!entityComment.isEmpty() && !entityComment.equals(normalizeComment(dbTable.getComment()))) {
            deferredStatements.add(PgsqlTableCreateHandler.buildTableComment(quotedTable, entityComment));
        }

        // ===== 1. 列对比 =====
        for (ColumnInfo entityCol : entityTable.getColumns().values()) {
            String colName = entityCol.getColumnName().toLowerCase(Locale.ROOT);
            ColumnInfo dbCol = dbTable.getColumn(colName);
            if (dbCol == null) {
                // 新增列：列定义片段与建表路径共用同一个方法，两条路径不能写出两种字面量
                alterClauses.add("ADD COLUMN "
                        + PgsqlTableCreateHandler.columnDefinition(entityCol, typeDef).strip());
                if (hasText(entityCol.getComment())) {
                    deferredStatements.add(PgsqlTableCreateHandler
                            .buildColumnComment(tableName, colName, entityCol.getComment()));
                }
                continue;
            }

            String quotedColumn = PgsqlIdentifier.quote(colName);
            if (!expectsIdentity(entityCol)) {
                warnUnexpectedIdentity(tableName, colName, dbCol);
            }

            // 1a. 物理类型：只有真的判不等才发 TYPE（这一条会整表重写，绝不能虚发）
            if (!typeDef.renderNormalized(entityCol).equals(typeDef.normalize(dbCol.getType()))) {
                String using = typeUsingClause(entityCol, dbCol, typeDef, quotedColumn);
                alterClauses.add("ALTER COLUMN " + quotedColumn + " TYPE "
                        + typeDef.renderType(entityCol) + (using == null ? "" : " " + using));
            }
            // 1b. 可空性
            if (entityCol.isNullable() != dbCol.isNullable()) {
                alterClauses.add("ALTER COLUMN " + quotedColumn + (entityCol.isNullable() ? " DROP NOT NULL" : " SET NOT NULL"));
            }
            // 1c. 默认值
            if (!defaultValueEquals(entityCol.getDefaultValue(), dbCol.getDefaultValue())) {
                if (hasText(entityCol.getDefaultValue())) {
                    alterClauses.add("ALTER COLUMN " + quotedColumn
                            + " SET DEFAULT " + PgsqlTableCreateHandler.defaultLiteral(entityCol));
                } else {
                    alterClauses.add("ALTER COLUMN " + quotedColumn + " DROP DEFAULT");
                }
            }
            // 1d. 自增：实体要而库端没有才修，反方向只告警
            if (expectsIdentity(entityCol) && !hasIdentity(dbCol)) {
                alterClauses.addAll(identityClauses(quotedColumn, dbCol));
            }
            // 1e. 列注释
            if (!Objects.equals(normalizeComment(entityCol.getComment()), normalizeComment(dbCol.getComment()))
                    && hasText(entityCol.getComment())) {
                deferredStatements.add(PgsqlTableCreateHandler
                        .buildColumnComment(tableName, colName, entityCol.getComment()));
            }
        }

        // ===== 2. 索引对比 =====
        diffIndexes(jdbcTemplate, quotedTable, tableName, entityTable, dbTable, deferredStatements);

        // ===== 3. 执行 =====
        try (QueryRunner queryRunner = new QueryRunner(jdbcTemplate)) {
            if (!alterClauses.isEmpty()) {
                String alterSql = "ALTER TABLE " + quotedTable + "\n  " + String.join(",\n  ", alterClauses);
                log.debug("修改表sql:\n{};", alterSql);
                queryRunner.update(alterSql);
            }
            // 索引与注释是独立对象，无法并入上面那条 ALTER；pgjdbc 的 prepared 路径也不吃多语句
            for (String statement : deferredStatements) {
                log.debug("修改表sql:\n{};", statement);
                queryRunner.update(statement);
            }
        }
    }

    /**
     * 索引 diff：新增/重建/删除分别落成独立的 {@code CREATE INDEX} 与 {@code DROP INDEX} 语句。
     * <p>命中实体即从库端索引表移除，循环结束后剩下的就是"库里有、实体已不要"的索引，
     * 与 MySQL 侧同一套判定；删除受 {@code dropRedundantIndexes} 开关控制并留 WARN 日志。</p>
     * <p>变化判定按 {@code (唯一性, 列数量, 逐位列名)} 逐项比：复合索引 (a,b) 与 (b,a) 服务能力不同，
     * 顺序变了必须重建而不是放过。重建必须"先删后建"，因为 PG 的索引名是 schema 级唯一的，
     * 不删就建会撞 42P07。</p>
     */
    private void diffIndexes(JdbcTemplate jdbcTemplate, String quotedTable, String tableName,
                             TableInfo entityTable, TableInfo dbTable,
                             List<String> deferredStatements) {
        List<IndexInfo> entityIndexes = entityTable.getIndexes();
        if (entityIndexes == null) return;

        Map<String, IndexInfo> dbIndexMap = new HashMap<>();
        if (dbTable.getIndexes() != null) {
            for (IndexInfo idx : dbTable.getIndexes()) {
                dbIndexMap.put(idx.getName().toLowerCase(Locale.ROOT), idx);
            }
        }

        for (IndexInfo entityIdx : entityIndexes) {
            String idxName = entityIdx.getName().toLowerCase(Locale.ROOT);
            IndexInfo dbIdx = dbIndexMap.get(idxName);
            if (dbIdx == null) {
                deferredStatements.add(PgsqlTableCreateHandler.buildCreateIndex(quotedTable, entityIdx));
            } else if (hasIndexChanged(entityIdx, dbIdx)) {
                deferredStatements.add(buildDropIndex(jdbcTemplate, dbIdx.getName()));
                deferredStatements.add(PgsqlTableCreateHandler.buildCreateIndex(quotedTable, entityIdx));
            }
            dbIndexMap.remove(idxName);
        }

        for (IndexInfo dropIdx : dbIndexMap.values()) {
            if (jdbcTemplate.isDropRedundantIndexes()) {
                deferredStatements.add(buildDropIndex(jdbcTemplate, dropIdx.getName()));
                log.warn("表 {} 的索引 {} 在实体中没有声明，将被 DROP（需要保留请补上注解，"
                                + "或设置 JdbcTemplate#setDropRedundantIndexes(false)）",
                        tableName, dropIdx.getName());
            } else {
                log.warn("表 {} 的索引 {} 在实体中没有声明，按配置跳过删除（dropRedundantIndexes=false）",
                        tableName, dropIdx.getName());
            }
        }
    }

    /**
     * 文本族 ↔ JSON 族互转时补出 {@code USING} 表达式，其余情况返回 null（不发 USING）。
     * <p><b>WHY</b>：PG 的 {@code ALTER COLUMN ... TYPE} 只接受存在隐式或赋值转换的类型对，
     * 而 {@code text}/{@code varchar} ↔ {@code json}/{@code jsonb} 之间没有这种转换（必须走 {@code ::}
     * 显式 cast）。不补 USING，"把存量 JSON 字符串列改成原生 jsonb"这条路径会当场 42846；
     * 反向（jsonb 列改回 text）同理。</p>
     * <p>统一经过 {@code ::text::} 中转而不是直连目标：pg_cast 里 json/jsonb 的显式 cast 只对接
     * {@code text}，{@code character(n)}（bpchar）与 json 两族之间连直接 cast 都没有，直写
     * {@code col::character(1)} 会 42846；而 bpchar→text 的中转对 text/varchar 是恒等
     * （varchar 与 text 二进制兼容），对 bpchar 顺带丢掉补位空格——那正是转 JSON 该有的语义。</p>
     * <p>只在两族之间补：数字改宽、varchar 改长度这些 PG 自己会处理的转换不加猜测的表达式——
     * 多写一层 USING 等于换掉转换语义（例如把隐式舍入换成字符串 cast）。</p>
     * <p>cast 目标用比较键（{@code renderNormalized}）而不是渲染字面量：实体写 {@code JSONB} 时
     * 比较键已是小写 {@code jsonb}，拼 cast 不必再操心大小写与写法差异。</p>
     */
    private static String typeUsingClause(ColumnInfo entityCol, ColumnInfo dbCol,
                                          ColumnTypeDefHandler typeDef, String quotedColumn) {
        String targetJson = PgsqlJsonType.of(entityCol);
        String currentJson = PgsqlJsonType.of(dbCol.getType());
        if (targetJson != null && PgsqlJsonType.isTextFamily(dbCol.getType())) {
            return "USING " + quotedColumn + "::text::" + targetJson;
        }
        String targetNormalized = typeDef.renderNormalized(entityCol);
        if (currentJson != null && targetJson == null && PgsqlJsonType.isTextFamily(targetNormalized)) {
            return "USING " + quotedColumn + "::text::" + targetNormalized;
        }
        return null;
    }

    /**
     * 把实体要求自增的列改成 IDENTITY。
     * <p>老式 {@code serial} 列（库端默认值是 {@code nextval(...)}）要先 {@code DROP DEFAULT}
     * 才能 {@code ADD GENERATED ... AS IDENTITY}，否则 PG 报"列已有默认值"；
     * 序列默认值在元数据回读里已经算"有自增"（{@link PgsqlDbMetaHandler} 的 extra 判定），
     * 所以正常路径上进不来这一支，留着是给"库里只建了序列却没绑上"这类手工状态的兜底。</p>
     */
    private static List<String> identityClauses(String quotedColumn, ColumnInfo dbCol) {
        List<String> clauses = new ArrayList<>(2);
        String rawDefault = dbCol.getDefaultValue();
        if (rawDefault != null && rawDefault.toLowerCase(Locale.ROOT).contains("nextval(")) {
            clauses.add("ALTER COLUMN " + quotedColumn + " DROP DEFAULT");
        }
        clauses.add("ALTER COLUMN " + quotedColumn + " ADD GENERATED BY DEFAULT AS IDENTITY");
        return clauses;
    }

    /**
     * DROP INDEX 语句（带 schema 限定）。
     * <p>WHY 限定 schema：MySQL 的索引名是表内唯一，{@code DROP INDEX x ON t} 天然指向本表；
     * PG 的索引是 schema 级对象，名字全局唯一，写法上也<b>不能</b>带表名。
     * 不限定就按 search_path 找第一个同名对象，万一别的表先占了这个名字，
     * 删掉的就是别人的索引——这是不可逆的破坏，所以宁可多查一次 {@code current_schema()}。</p>
     */
    private String buildDropIndex(JdbcTemplate jdbcTemplate, String indexName) {
        String schema = resolveSchema(jdbcTemplate);
        String quotedIndex = schema == null
                ? PgsqlIdentifier.quote(indexName)
                : PgsqlIdentifier.quote(schema, indexName);
        return "DROP INDEX IF EXISTS " + quotedIndex;
    }

    /** 取当前 schema，供 DROP INDEX 限定；拿不到就退回不限定（与建表同样的解析路径） */
    private static String resolveSchema(JdbcTemplate jdbcTemplate) {
        DbMetaHandler metaProvider = jdbcTemplate.getDbMetaProvider();
        return metaProvider == null ? null : metaProvider.getCatalog(jdbcTemplate);
    }

    /**
     * 实体是否要求该列为 IDENTITY（自增主键）。
     */
    private static boolean expectsIdentity(ColumnInfo col) {
        return col.isPrimaryKey() && col.getGenerationType() == GenerationType.IDENTITY;
    }

    /** 库端列是否已是自增（extra 形如 {@code identity}/{@code identity generated}，来自 pg_attribute） */
    private static boolean hasIdentity(ColumnInfo dbCol) {
        String extra = dbCol.getExtra();
        return extra != null && extra.toLowerCase(Locale.ROOT).contains("identity");
    }

    /**
     * 库端是自增列、实体却没声明 {@code IDENTITY} 时告警（不改动结构）。
     * <p>反方向（实体要自增、库端没有）会走 {@code ADD GENERATED ... AS IDENTITY} 修回来；
     * 这个方向只提示：存量表人工加过序列/IDENTITY 很常见，
     * 自动 {@code DROP IDENTITY} 会把主键生成能力摘掉，风险大于收益。</p>
     */
    private static void warnUnexpectedIdentity(String tableName, String colName, ColumnInfo dbCol) {
        if (hasIdentity(dbCol)) {
            log.warn("列 {}.{} 在库端是 IDENTITY/serial，实体没有声明 @GeneratedValue(strategy = IDENTITY)，"
                            + "框架不会自动摘除该属性；若确实不需要请手工 ALTER", tableName, colName);
        }
    }

    /**
     * 注释比对前的归一：null 与空串视为等价。
     * <p>实体侧零配置列 comment 为 null，库端 {@link PgsqlDbMetaHandler} 把无注释归一成空串，
     * 两者不归一就会每次 checkTable 都发一条 {@code COMMENT ON}。</p>
     */
    private static String normalizeComment(String comment) {
        return comment == null ? "" : comment;
    }

    /**
     * 判断索引是否变化：唯一性、列数量、逐位列名（忽略大小写）任一不同即视为变化。
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
     *   <li>布尔档的 {@code 0/1} 与 {@code false/true} 等价：写入侧会把 0/1 折成 false/true
     *       （PG 的 boolean 列不接受 {@code DEFAULT 0}，见 {@link PgsqlTableCreateHandler#defaultLiteral}），
     *       库端回读到的因此是 {@code false}；比对侧不跟着折的话，每次启动都发一条
     *       {@code SET DEFAULT}，而它改的其实是同一个值</li>
     * </ul>
     */
    boolean defaultValueEquals(String entityDefault, String dbDefault) {
        String left = foldDefault(entityDefault);
        String right = foldDefault(dbDefault);
        return Objects.equals(left, right);
    }

    /** 默认值比较用的归一形式：空串/"NULL" 都视作"无默认值"，布尔同义写法、时间族函数写法折叠 */
    private static String foldDefault(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isEmpty() || value.equalsIgnoreCase("NULL")) return null;
        String lowered = value.toLowerCase(Locale.ROOT);
        String folded = BOOLEAN_DEFAULT_SYNONYMS.getOrDefault(lowered, value);
        // 时间族与回读侧（PgsqlDbMetaHandler.normalizeDefaultValue）同一口径：
        // 库端把 now()/CURRENT_TIMESTAMP(6) 回读成 CURRENT_TIMESTAMP，实体侧不跟着折的话，
        // 写 now() 的列每次启动都会白发一条 SET DEFAULT 且永不收敛
        if (folded.equalsIgnoreCase("now()") || lowered.startsWith("current_timestamp")) {
            return "CURRENT_TIMESTAMP";
        }
        return folded;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isEmpty();
    }
}
