package com.wxdgaming.batis.sql.pgsql;

import com.wxdgaming.batis.annotation.GenerationType;
import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.IndexInfo;
import com.wxdgaming.batis.model.SqlType;
import com.wxdgaming.batis.model.TableInfo;
import com.wxdgaming.batis.sql.ColumnTypeDefHandler;
import com.wxdgaming.batis.sql.JdbcTemplate;
import com.wxdgaming.batis.sql.QueryRunner;
import com.wxdgaming.batis.sql.TableCreateHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL 表构建
 * <p>把实体解析出的 TableInfo 渲染成建表 DDL 并执行。与 MySQL 版最大的结构差别是
 * <b>一次建表对应多条语句</b>：PG 的 CREATE TABLE 不能内联索引，注释也是独立对象
 * （{@code COMMENT ON}），所以产出 {@code CREATE TABLE} + N 条 {@code CREATE INDEX}
 * + 表/列注释语句，由 {@link #buildStatements} 统一给出、{@link #doAction} 逐条执行。</p>
 * <p>逐条执行而非拼成一串的原因：pgjdbc 走 prepared 协议时不接受多语句（会直接报语法错误），
 * 而 {@code QueryRunner#update} 正是 PreparedStatement 路径。</p>
 * <p>列物理类型一律经 {@link ColumnTypeDefHandler} 渲染，使建表与
 * {@link PgsqlTableAlterHandler} 的结构 diff 使用同一套类型口径。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-14
 **/
@Slf4j
public class PgsqlTableCreateHandler implements TableCreateHandler {

    /** 无状态处理器，供测试与 template 复用同一实例 */
    public static final PgsqlTableCreateHandler INSTANCE = new PgsqlTableCreateHandler();

    /**
     * 生成并执行建表语句集。
     * <p>执行走 {@link QueryRunner} 的 try-with-resources，连接借出后必然归还。
     * 多条语句之间存在依赖（索引与注释都引用已存在的表），所以顺序固定为
     * 表 → 索引 → 注释，且不做"前面失败也继续"的容错：表都没建起来时后面条目的报错只是噪音。</p>
     */
    @Override
    public void doAction(JdbcTemplate jdbcTemplate, TableInfo tableInfo) {
        List<String> statements = buildStatements(tableInfo, typeDef(jdbcTemplate));
        log.debug("创建表sql:\n{}", joinForLog(statements));
        try (QueryRunner queryRunner = new QueryRunner(jdbcTemplate)) {
            for (String statement : statements) {
                queryRunner.update(statement);
            }
        }
    }

    /**
     * 无 template 上下文的纯构建入口（工具/测试用），回退 PG 默认类型渲染器。
     * <p>返回的是完整脚本（多条语句以 {@code ;} 分隔），可直接粘进 psql 执行；
     * 要逐条喂给 JDBC 请用 {@link #buildStatements}。</p>
     */
    @Override
    public String buildSql(TableInfo tableInfo) {
        return joinForLog(buildStatements(tableInfo, PgsqlColumnTypeDefHandler.INSTANCE));
    }

    /** 优先使用 template 注入的方言实现，未注入时回退 PG 默认实现 */
    private static ColumnTypeDefHandler typeDef(JdbcTemplate jdbcTemplate) {
        ColumnTypeDefHandler handler = jdbcTemplate.getColumnTypeDefHandler();
        return handler != null ? handler : PgsqlColumnTypeDefHandler.INSTANCE;
    }

    private static String joinForLog(List<String> statements) {
        StringBuilder sb = new StringBuilder(256);
        for (String statement : statements) {
            sb.append(statement).append(";\n");
        }
        return sb.toString();
    }

    /**
     * 建表所需的全部语句：{@code CREATE TABLE} → 各 {@code CREATE INDEX} → {@code COMMENT ON}。
     * <p>包可见是为了让 {@link PgsqlTableAlterHandler} 复用同一套索引/注释语句形状：
     * 首次建表与后续同步必须产出字面量一致的索引定义，否则同一实体在两条路径下建出的
     * 约束不同，下一次 diff 又会认为索引"变了"而反复 DROP/CREATE。</p>
     */
    List<String> buildStatements(TableInfo tableInfo, ColumnTypeDefHandler typeDef) {
        List<String> statements = new ArrayList<>();
        statements.add(buildCreateTable(tableInfo, typeDef));

        String quotedTable = PgsqlIdentifier.quote(tableInfo.getTableName());
        if (tableInfo.getIndexes() != null) {
            for (IndexInfo index : tableInfo.getIndexes()) {
                statements.add(buildCreateIndex(quotedTable, index));
            }
        }

        // 表注释与列注释在 PG 里是独立对象；实体没写注释时不发消息，避免清空 DBA 手工写的注释
        String tableComment = tableInfo.getComment();
        if (hasText(tableComment)) {
            statements.add(buildTableComment(quotedTable, tableComment));
        }
        for (ColumnInfo col : tableInfo.getColumns().values()) {
            if (hasText(col.getComment())) {
                statements.add(buildColumnComment(tableInfo.getTableName(), col.getColumnName(), col.getComment()));
            }
        }
        return statements;
    }

    /** 表注释语句（也供 {@link PgsqlTableAlterHandler} 复用，保证两条路径写的注释字面量同一） */
    static String buildTableComment(String quotedTable, String comment) {
        return "COMMENT ON TABLE " + quotedTable + " IS '" + PgsqlSqlLiteral.escapeString(comment) + "'";
    }

    /**
     * 列注释语句，形状同 {@link #buildTableComment}。
     * <p>参数是<b>未引用</b>的表名：{@code COMMENT ON COLUMN} 的目标必须写成
     * {@code "表"."列"} 两段，由 {@link PgsqlIdentifier#quote(String, String)} 一次性加引号，
     * 传入已引用的名字会被再套一层（{@code """t_role"""}）而成语法错误。</p>
     */
    static String buildColumnComment(String tableName, String columnName, String comment) {
        return "COMMENT ON COLUMN " + PgsqlIdentifier.quote(tableName, columnName)
                + " IS '" + PgsqlSqlLiteral.escapeString(comment) + "'";
    }

    /**
     * 拼 CREATE TABLE 语句。
     * <p>产出形如：{@code CREATE TABLE IF NOT EXISTS "t_role" ( 列..., PRIMARY KEY (...))}。</p>
     * <p>与 MySQL 版的三处口径差别，都是 PG 语法本身的要求：</p>
     * <ul>
     *   <li>没有 {@code ENGINE}/{@code DEFAULT CHARSET} 尾巴——PG 的存储与编码在集群/数据库级别决定
     *       （建库时 {@code ENCODING 'UTF8'}），表级书写只会报语法错误；</li>
     *   <li>索引不在这里内联，见 {@link #buildStatements}；</li>
     *   <li>注释不在列定义里写，PG 的列定义没有 COMMENT 子句。</li>
     * </ul>
     * <p>{@code IF NOT EXISTS} 让 checkTable 幂等，这是"启动即同步"能安全反复执行的前提；
     * 主键用表级 {@code PRIMARY KEY} 约束而不是内联在列上，因为复合主键只能这样表达。</p>
     */
    private String buildCreateTable(TableInfo tableInfo, ColumnTypeDefHandler typeDef) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("CREATE TABLE IF NOT EXISTS ").append(PgsqlIdentifier.quote(tableInfo.getTableName())).append(" (\n");

        List<String> columnDefs = new ArrayList<>();
        for (ColumnInfo col : tableInfo.getColumns().values()) {
            columnDefs.add(columnDefinition(col, typeDef));
        }

        List<String> pkColumns = new ArrayList<>();
        for (ColumnInfo col : tableInfo.getColumns().values()) {
            if (col.isPrimaryKey()) {
                pkColumns.add(PgsqlIdentifier.quote(col.getColumnName()));
            }
        }
        if (!pkColumns.isEmpty()) {
            columnDefs.add("  PRIMARY KEY (" + String.join(", ", pkColumns) + ")");
        }

        sb.append(String.join(",\n", columnDefs));
        sb.append("\n)");
        return sb.toString();
    }

    /**
     * 构建单列定义片段：{@code "col" 物理类型 [NOT NULL] [DEFAULT x] [GENERATED BY DEFAULT AS IDENTITY]}。
     * <p>属性拼接顺序与 {@link PgsqlTableAlterHandler} 的 {@code ADD COLUMN} 完全一致——本方法就是
     * 那条路径复用的同一个实现，保证两条路径不可能各自漂移。</p>
     * <p>自增写成 {@code GENERATED BY DEFAULT AS IDENTITY} 而不是 {@code ALWAYS}：
     * {@code ALWAYS} 会拒绝显式赋值，而框架的插入路径与数据迁移都可能需要手工指定主键
     * （MySQL 的 AUTO_INCREMENT 允许显式赋值，这是行为对齐）；也不写 {@code serial}，
     * 那是老式默认值语法，与 IDENTITY 的元数据回读口径不一致。</p>
     * <p>IDENTITY 只在"自增且是主键"时追加，与 MySQL 侧同一门槛：PG 要求 IDENTITY 列必须是
     * smallint/integer/bigint，非主键的整数列被加上 IDENTITY 会让普通业务列变成不可写。</p>
     */
    static String columnDefinition(ColumnInfo col, ColumnTypeDefHandler typeDef) {
        StringBuilder sb = new StringBuilder(64);
        sb.append("  ").append(PgsqlIdentifier.quote(col.getColumnName())).append(' ').append(typeDef.renderType(col));

        if (!col.isNullable()) {
            sb.append(" NOT NULL");
        }

        if (col.getDefaultValue() != null && !col.getDefaultValue().isEmpty()) {
            // 默认值按 SQL 字面量渲染：字符串形态只校验不二次转义，裸字面量走白名单校验
            sb.append(" DEFAULT ").append(defaultLiteral(col));
        }

        if (col.getGenerationType() == GenerationType.IDENTITY && col.isPrimaryKey()) {
            sb.append(" GENERATED BY DEFAULT AS IDENTITY");
        }

        return sb.toString();
    }

    /**
     * 建索引语句（也供 {@link PgsqlTableAlterHandler} 的新增索引分支使用）。
     * <p>{@code IF NOT EXISTS} 与建表保持同一幂等口径。名字用实体声明的原值（小写加引号）：
     * PG 的索引名是 <em>schema 级全局唯一</em>，两张表声明同名索引会在第二条语句上 42P07
     * （relation already exists）——这是声明约定问题，框架不改写名字，
     * 改写会让回读的索引名与实体对不上而反复重建。</p>
     */
    static String buildCreateIndex(String quotedTable, IndexInfo index) {
        List<String> quotedCols = new ArrayList<>();
        for (String colName : index.getColumns()) {
            quotedCols.add(PgsqlIdentifier.quote(colName));
        }
        return "CREATE " + (index.isUnique() ? "UNIQUE " : "") + "INDEX IF NOT EXISTS "
                + PgsqlIdentifier.quote(index.getName()) + " ON " + quotedTable
                + " (" + String.join(", ", quotedCols) + ")";
    }

    /**
     * 渲染列默认值字面量（建表、{@code ADD COLUMN}、{@code SET DEFAULT} 三条路径共用）。
     * <p>布尔列有一处必须的特例：实体侧的布尔默认值沿用 MySQL 习惯写成 {@code 0}/{@code 1}，
     * 而 PG <b>不会</b>把整数默认值隐式降格成布尔——{@code DEFAULT 0} 落在 boolean 列上是 42804
     * （"字段类型是 boolean，但默认表达式类型是 integer"），整条建表语句直接失败。
     * 这里折的是写法而不是语义：{@code 0} 与 {@code false} 在布尔列上是同一个值，
     * 库端回读到的也正是 {@code false}，所以折叠后与比对侧（改表处理器的默认值等价判断）口径一致。</p>
     * <p>{@code 'false'} 这种带引号的形态刻意不折：它在 PG 里是合法的 boolean 字面量
     * （字符串 {@code 'false'} 可以隐式转换成 boolean），按字符串透传即可，
     * 猜它的意图反而会把用户明确写下的东西改掉。</p>
     *
     * @param col 实体侧列元数据
     * @return 可安全拼接的默认值字面量
     * @throws IllegalArgumentException 非布尔形态的默认值不符合字面量约定时由 {@link PgsqlSqlLiteral} 抛出
     */
    static String defaultLiteral(ColumnInfo col) {
        String raw = col.getDefaultValue();
        if (isBooleanColumn(col)) {
            String value = raw.trim();
            if ("0".equals(value)) return "false";
            if ("1".equals(value)) return "true";
        }
        return PgsqlSqlLiteral.defaultValue(raw);
    }

    /** 该列是否是布尔档：优先看逻辑类型档位，显式物理类型（{@code type="BOOLEAN"}）时退回 Java 类型 */
    private static boolean isBooleanColumn(ColumnInfo col) {
        return col.getSqlType() == SqlType.BOOLEAN
                || col.getJavaType() == boolean.class || col.getJavaType() == Boolean.class;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isEmpty();
    }
}
