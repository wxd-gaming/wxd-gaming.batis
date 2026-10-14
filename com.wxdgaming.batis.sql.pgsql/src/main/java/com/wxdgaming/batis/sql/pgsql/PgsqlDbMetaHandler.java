package com.wxdgaming.batis.sql.pgsql;

import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.IndexInfo;
import com.wxdgaming.batis.model.TableInfo;
import com.wxdgaming.batis.sql.DbMetaHandler;
import com.wxdgaming.batis.sql.JdbcTemplate;
import com.wxdgaming.batis.sql.QueryRunner;
import com.wxdgaming.batis.sql.ResultSetHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PostgreSQL 数据库元数据处理器
 * <p>基于 pg_catalog 回读表、列、索引结构，供启动期结构漂移比对使用。</p>
 * <p>直查 pg_catalog 而非 JDBC DatabaseMetaData，也不用 information_schema，理由有三：</p>
 * <ul>
 *   <li>{@code format_type()} 给出 PG 官方的规范拼写（{@code character varying(255)}、
 *       {@code double precision}），与 {@code \d} 所见一致，比较键因此可预测；
 *       information_schema 把同一列拆成 DATA_TYPE + 长度/精度三列，要自己拼回形态，多一处写错的机会；</li>
 *   <li>注释只有 pg_catalog 拿得到：{@code col_description}/{@code obj_description}
 *       对应 {@code COMMENT ON}，information_schema 里没有这一列；</li>
 *   <li>{@code information_schema.columns} 会按当前用户的权限过滤，权限不足时列会<em>静默消失</em>，
 *       漂移比对就会把"读不到"当成"库里没有这列"而反复 ADD COLUMN。pg_class/pg_attribute 不受此限。</li>
 * </ul>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-14
 **/
public class PgsqlDbMetaHandler implements DbMetaHandler {

    /**
     * 带引号的默认值字面量：{@code '内容'::类型}。
     * <p>贪婪匹配到最后一个 {@code '}::}，好让 {@code 'O''Brien'::text} 的 group(1) 取到完整内容。</p>
     */
    private static final Pattern QUOTED_CAST = Pattern.compile("^'(.*)'::(.+)$");

    /**
     * PG 把负数与部分数值默认值存成"带引号的数值字面量"（{@code '-1'::integer}）。
     * <p>实体侧书写数字默认值是不带引号的裸写法（见 {@code PgsqlSqlLiteral} 的白名单），
     * 所以这些档位在比对前要剥掉引号；反过来 text/date/timestamp 的默认值在实体侧本来就带引号，
     * 保留引号才能对上。这一组类型名就是按这个区分列出来的。</p>
     */
    private static final List<String> NUMERIC_CASTS = List.of(
            "smallint", "integer", "bigint", "numeric", "decimal", "real",
            "double precision", "float4", "float8", "int2", "int4", "int8", "money");

    /**
     * 取当前 schema，作为后续三个查询的过滤条件。
     * <p>用 {@code current_schema()} 而不是 catalog：PG 的 JDBC catalog 是数据库名（如 test），
     * 而表实际归属于 search_path 里的 schema（默认 public）。建表也落在 current_schema，
     * 两边同一口径才不会读到别的 schema 的同名表。</p>
     */
    @Override
    public String getCatalog(JdbcTemplate jdbcTemplate) {
        try (QueryRunner qr = new QueryRunner(jdbcTemplate)) {
            return (String) qr.query("SELECT current_schema()", rs -> {
                if (rs.next()) return rs.getString(1);
                return null;
            });
        }
    }

    /**
     * 表清单查询：relkind 只要 {@code r}（普通表）与 {@code p}（分区父表）；
     * 分区叶子表（relispartition）与视图/物化视图/TOAST 表都不入选——它们是别的对象的投影，
     * 当成独立表比对会凭空多出结构差异。
     */
    @Override
    public String tableListSql() {
        return "SELECT c.relname AS table_name, obj_description(c.oid) AS table_comment "
                + "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE n.nspname = ? AND c.relkind IN ('r', 'p') AND NOT c.relispartition "
                + "ORDER BY c.relname";
    }

    /** 表清单 ResultSet → TableInfo 列表；无注释归一为空串，与实体侧比对口径一致 */
    @Override
    public ResultSetHandler<List<TableInfo>> tableListHandler() {
        return rs -> {
            List<TableInfo> list = new ArrayList<>();
            while (rs.next()) {
                TableInfo tableInfo = new TableInfo();
                tableInfo.setTableName(rs.getString("table_name"));
                tableInfo.setComment(nullToEmpty(rs.getString("table_comment")));
                list.add(tableInfo);
            }
            return list;
        };
    }

    /**
     * 列信息查询：pg_attribute 为主表，注释取 {@code col_description}，
     * 默认值取 {@code pg_get_expr(adbin, adrelid)}（与 information_schema.COLUMN_DEFAULT 同源表达式）。
     * <p>主键判定用 {@code conkey @> ARRAY[attnum]}：PG 的主键可以是多列，
     * 若只按"第一列是主键"读，复合主键实体的第二列会被当成普通列，插入/更新按 id 定位时就会走错条件。
     * {@code attnum > 0} 过滤系统列（ctid/xmin 等），{@code attisdropped} 过滤 DROP COLUMN 留下的墓碑列。</p>
     * <p>排序按 attnum（列的物理定义顺序），漂移比对是逐行按序对拍的，顺序不稳会整表误判。</p>
     */
    @Override
    public String columnListSql() {
        return "SELECT a.attname AS column_name, "
                + "format_type(a.atttypid, a.atttypmod) AS physical_type, "
                + "NOT a.attnotnull AS is_nullable, "
                + "pg_get_expr(d.adbin, d.adrelid) AS column_default, "
                + "col_description(c.oid, a.attnum) AS column_comment, "
                + "(a.attidentity <> '') AS is_identity, "
                + "(a.attgenerated <> '') AS is_generated, "
                + "EXISTS (SELECT 1 FROM pg_constraint con "
                + "        WHERE con.conrelid = c.oid AND con.contype = 'p' "
                + "          AND con.conkey @> ARRAY[a.attnum]::smallint[]) AS is_primary_key "
                + "FROM pg_attribute a "
                + "JOIN pg_class c ON c.oid = a.attrelid "
                + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum "
                + "WHERE n.nspname = ? AND c.relname = ? AND a.attnum > 0 AND NOT a.attisdropped "
                + "ORDER BY a.attnum";
    }

    /**
     * 列 ResultSet → ColumnInfo：只填库端读得到的属性，{@code type} 存 format_type 的原样输出。
     * <p>逻辑类型 sqlType 留空：库端只有物理形态，判漂移时由
     * {@link PgsqlColumnTypeDefHandler#normalize} 与实体侧渲染结果对齐。</p>
     * <p>自增信息写在 {@code extra} 里（值形如 {@code identity}/{@code generated}），
     * 与 MySQL 侧把 {@code auto_increment} 写进 EXTRA 的做法保持同一约定，
     * 这样改表处理器判"实体要自增、库端没有"时两边读同一个字段。</p>
     */
    @Override
    public ResultSetHandler<List<ColumnInfo>> columnListHandler() {
        return rs -> {
            List<ColumnInfo> columns = new ArrayList<>();
            while (rs.next()) {
                ColumnInfo col = new ColumnInfo();
                String columnType = rs.getString("physical_type");
                col.setColumnName(rs.getString("column_name"));
                col.setType(columnType);
                col.setNullable(rs.getBoolean("is_nullable"));
                col.setComment(nullToEmpty(rs.getString("column_comment")));
                col.setPrimaryKey(rs.getBoolean("is_primary_key"));
                col.setExtra(buildExtra(rs.getBoolean("is_identity"), rs.getBoolean("is_generated"),
                        rs.getString("column_default")));
                col.setDefaultValue(normalizeDefaultValue(rs.getString("column_default"),
                        rs.getBoolean("is_identity")));
                columns.add(col);
            }
            return columns;
        };
    }

    /**
     * 索引查询：pg_index 的 {@code indkey} 是"一个索引一行的列号数组"，
     * 用 {@code unnest ... WITH ORDINALITY} 炸成一列一行并带出列内序号，再按 (索引名, 序号) 排序，
     * handler 才能依序还原复合索引的列顺序（(a,b) 与 (b,a) 服务能力不同，顺序是有语义的）。
     * <p>排除三类不是"框架声明的普通索引"的对象：主键（已由列元数据表达）、
     * 约束背后的支撑索引（UNIQUE/EXCLUDE：删它要用 DROP CONSTRAINT，DROP INDEX 会失败）、
     * 表达式索引与部分索引（indpred/indexprs 非空：它们没有可比的裸列清单，报出来会生成跑不通的重建语句）。</p>
     */
    @Override
    public String indexListSql() {
        return "SELECT ic.relname AS index_name, ix.indisunique AS is_unique, a.attname AS column_name "
                + "FROM pg_index ix "
                + "JOIN pg_class t ON t.oid = ix.indrelid "
                + "JOIN pg_class ic ON ic.oid = ix.indexrelid "
                + "JOIN pg_namespace n ON n.oid = t.relnamespace "
                + "JOIN LATERAL unnest(ix.indkey::int[]) WITH ORDINALITY AS k(attnum, ord) ON TRUE "
                + "JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum "
                + "WHERE n.nspname = ? AND t.relname = ? "
                + "AND NOT ix.indisprimary AND ix.indexprs IS NULL AND ix.indpred IS NULL "
                + "AND NOT EXISTS (SELECT 1 FROM pg_constraint con "
                + "                 WHERE con.conrelid = t.oid AND con.conindid = ix.indexrelid) "
                + "ORDER BY ic.relname, k.ord";
    }

    /**
     * 索引 ResultSet → IndexInfo：按索引名分组保留列顺序。
     * <p>PG 的索引名在整个 schema 内唯一（不像 MySQL 是表内唯一），
     * 这里回读的已经是"限定到本表"的结果，名字与表一一对应，不会串表。</p>
     */
    @Override
    public ResultSetHandler<List<IndexInfo>> indexListHandler() {
        return rs -> {
            LinkedHashMap<String, IndexInfo> indexMap = new LinkedHashMap<>();
            while (rs.next()) {
                String indexName = rs.getString("index_name");
                IndexInfo info = indexMap.computeIfAbsent(indexName, k -> {
                    IndexInfo i = new IndexInfo();
                    i.setName(indexName);
                    i.setColumns(new ArrayList<>());
                    return i;
                });
                info.getColumns().add(rs.getString("column_name"));
                info.setUnique(rs.getBoolean("is_unique"));
            }
            return new ArrayList<>(indexMap.values());
        };
    }

    /**
     * 组装 EXTRA：标记这一列在库端是否是自增/生成列。
     * <p>三种来源都要认：{@code attidentity} 是 PG 标准的 IDENTITY（本框架建表用的形态）；
     * {@code attgenerated} 是生成列；老式 {@code serial} 列两个标记都为空，只有
     * {@code nextval('...')} 形式的默认值——它同样能回写生成键，若判成"非自增"，
     * 存量 serial 表接入框架时会被认为需要补自增属性而反复发 ALTER。</p>
     */
    private static String buildExtra(boolean identity, boolean generated, String rawDefault) {
        StringBuilder sb = new StringBuilder(16);
        if (identity || isSequenceDefault(rawDefault)) {
            sb.append("identity");
        }
        if (generated) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append("generated");
        }
        return sb.toString();
    }

    /**
     * 把 PG 的默认值表达式折成与实体侧可比较的形式。
     * <ul>
     *   <li>{@code null}：库里没写 DEFAULT（包括 IDENTITY 列，它的值来自序列而不是默认值表达式）；</li>
     *   <li>{@code NULL::xxx} / 字面 {@code NULL}：显式写了 DEFAULT NULL，折成 "NULL" 哨兵，
     *       与 MySQL 侧同一约定（实体侧"未设置"与库侧"显式 NULL"都算等价，见改表处理器的默认值比对）；</li>
     *   <li>{@code nextval(...)}：序列默认值，属于自增机制而不是列默认值，返回 null；</li>
     *   <li>{@code now()}：PG 把 {@code DEFAULT CURRENT_TIMESTAMP} 存成 {@code now()}，
     *       不折回去的话，实体写 CURRENT_TIMESTAMP 会被判成漂移，每次启动都发一次
     *       {@code ALTER COLUMN ... SET DEFAULT}；</li>
     *   <li>{@code 'x'::类型}：数值类型剥掉引号（实体侧数字是裸写法），其余保留引号（实体侧字符串带引号）。</li>
     * </ul>
     *
     * @param rawDefault pg_get_expr 的原始输出，可能为 null
     * @param identity   该列是否为 IDENTITY 列
     * @return 比较用默认值；无默认值返回 null
     */
    private static String normalizeDefaultValue(String rawDefault, boolean identity) {
        if (rawDefault == null || identity) return null;
        String value = rawDefault.trim();
        if (value.isEmpty()) return null;
        if (value.equalsIgnoreCase("NULL") || value.regionMatches(true, 0, "NULL::", 0, 6)) {
            return "NULL";
        }
        if (isSequenceDefault(value)) return null;
        if (value.equalsIgnoreCase("now()") || value.toLowerCase(Locale.ROOT).startsWith("current_timestamp")) {
            return "CURRENT_TIMESTAMP";
        }
        Matcher m = QUOTED_CAST.matcher(value);
        if (m.matches()) {
            String castType = m.group(2).trim().toLowerCase(Locale.ROOT);
            // 去掉可能带的长度/精度尾巴（如 character varying(20)）再判档位
            int paren = castType.indexOf('(');
            if (paren > 0) castType = castType.substring(0, paren).trim();
            return NUMERIC_CASTS.contains(castType) ? m.group(1) : "'" + m.group(1) + "'";
        }
        return value;
    }

    /** 是否是序列默认值（老式 serial 或手工 SET DEFAULT nextval(...)） */
    private static boolean isSequenceDefault(String rawDefault) {
        return rawDefault != null && rawDefault.toLowerCase(Locale.ROOT).contains("nextval(");
    }

    /** 注释列的空值归一：null 与空串只留空串，避免漂移比对里两侧口径不一 */
    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
