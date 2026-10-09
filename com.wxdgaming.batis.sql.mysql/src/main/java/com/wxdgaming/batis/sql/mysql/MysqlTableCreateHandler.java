package com.wxdgaming.batis.sql.mysql;

import com.wxdgaming.batis.annotation.GenerationType;
import com.wxdgaming.batis.model.*;
import com.wxdgaming.batis.sql.*;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * mysql表构建
 * <p>把实体解析出的 TableInfo 渲染成 {@code CREATE TABLE IF NOT EXISTS} DDL 并执行。
 * 列物理类型一律经 {@link ColumnTypeDefHandler} 渲染（本方言实现只覆盖差异点），
 * 使建表与 {@link MysqlTableAlterHandler} 的结构 diff 使用同一套类型口径。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-24 17:19
 **/
@Slf4j
public class MysqlTableCreateHandler implements TableCreateHandler {

    /** 无状态处理器，供测试与 template 复用同一实例（见 QueryRunnerTest 直接引用 INSTANCE） */
    public static final MysqlTableCreateHandler INSTANCE = new MysqlTableCreateHandler();

    /**
     * 生成并执行建表 DDL。
     * <p>列物理类型通过 template 注入的 {@link ColumnTypeDefHandler} 渲染，从而支持使用方替换方言；
     * 执行走 {@link QueryRunner} 的 try-with-resources，连接借出后必然归还。</p>
     */
    @Override
    public void doAction(JdbcTemplate jdbcTemplate, TableInfo tableInfo) {
        String buildSql = buildSql(tableInfo, typeDef(jdbcTemplate));
        log.debug("创建表sql:\n{}", buildSql);
        try (QueryRunner queryRunner = new QueryRunner(jdbcTemplate)) {
            queryRunner.update(buildSql);
        }
    }

    /**
     * 无 template 上下文的纯构建入口（工具/测试用），回退 MySQL 默认类型渲染器。
     */
    @Override
    public String buildSql(TableInfo tableInfo) {
        return buildSql(tableInfo, MysqlColumnTypeDefHandler.INSTANCE);
    }

    /** 优先使用 template 注入的方言实现，未注入时回退 MySQL 默认实现 */
    private static ColumnTypeDefHandler typeDef(JdbcTemplate jdbcTemplate) {
        ColumnTypeDefHandler handler = jdbcTemplate.getColumnTypeDefHandler();
        return handler != null ? handler : MysqlColumnTypeDefHandler.INSTANCE;
    }

    /**
     * 拼接完整 CREATE TABLE 语句。
     * <p>产出形如：{@code CREATE TABLE IF NOT EXISTS `t_role` ( 列..., PRIMARY KEY (...), INDEX ...)}
     * 收尾固定 {@code ENGINE=InnoDB DEFAULT CHARSET=utf8mb4}，表注释存在时再追加 {@code COMMENT='...'}。</p>
     * <p>几个 MySQL 专有约定的 WHY：表名/列名/索引名一律小写并用反引号包裹，规避保留字与大小写敏感
     * （lower_case_table_names 在不同平台行为不一，统一小写最稳）；{@code IF NOT EXISTS} 让 checkTable
     * 幂等——重复启动或表已存在都不报错，这是"启动即同步"能安全反复执行的前提；InnoDB + utf8mb4
     * 是事务与完整 Unicode（含 emoji）的保障，utf8 老编码只到 3 字节会截断。</p>
     */
    private String buildSql(TableInfo tableInfo, ColumnTypeDefHandler typeDef) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("CREATE TABLE IF NOT EXISTS `").append(tableInfo.getTableName().toLowerCase()).append("` (\n");

        List<String> columnDefs = new ArrayList<>();

        // 1. 列定义：物理类型委托方言渲染器，本类不猜类型字面量
        for (ColumnInfo col : tableInfo.getColumns().values()) {
            columnDefs.add(buildColumnDef(col, typeDef));
        }

        // 2. 主键：可能为复合主键，单独成子句而非内联在列定义里
        List<String> pkColumns = new ArrayList<>();
        for (ColumnInfo col : tableInfo.getColumns().values()) {
            if (col.isPrimaryKey()) {
                pkColumns.add(col.getColumnName().toLowerCase());
            }
        }
        if (!pkColumns.isEmpty()) {
            columnDefs.add("  PRIMARY KEY (`" + String.join("`, `", pkColumns) + "`)");
        }

        // 3. 索引：唯一/普通分别落成 UNIQUE INDEX 与 INDEX，列顺序按声明顺序保留
        if (tableInfo.getIndexes() != null) {
            for (IndexInfo index : tableInfo.getIndexes()) {
                String indexType = index.isUnique() ? "UNIQUE INDEX" : "INDEX";
                List<String> quotedCols = new ArrayList<>();
                for (String colName : index.getColumns()) {
                    quotedCols.add("`" + colName.toLowerCase() + "`");
                }
                columnDefs.add("  " + indexType + " `" + index.getName().toLowerCase() + "` (" + String.join(", ", quotedCols) + ")");
            }
        }

        sb.append(String.join(",\n", columnDefs));
        sb.append("\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

        if (tableInfo.getComment() != null && !tableInfo.getComment().isEmpty()) {
            sb.append(" COMMENT='").append(escapeComment(tableInfo.getComment())).append("'");
        }

        sb.append(";");
        return sb.toString();
    }

    /**
     * 构建单列定义片段：{@code `col` 物理类型 [NOT NULL] [DEFAULT x] [AUTO_INCREMENT] [COMMENT '...']}。
     * <p>属性拼接顺序与 {@link MysqlTableAlterHandler} 的 MODIFY COLUMN 完全一致——同一列在两条路径
     * 生成的字面量必须相同，否则首次建表与后续同步会对同一实体产生不同结果，破坏零漂移不变式。</p>
     */
    private String buildColumnDef(ColumnInfo col, ColumnTypeDefHandler typeDef) {
        StringBuilder sb = new StringBuilder(64);
        sb.append("  `").append(col.getColumnName().toLowerCase()).append("` ").append(typeDef.renderType(col));

        if (!col.isNullable()) {
            sb.append(" NOT NULL");
        }

        if (col.getDefaultValue() != null && !col.getDefaultValue().isEmpty()) {
            sb.append(" DEFAULT ").append(col.getDefaultValue());
        }

        // AUTO_INCREMENT 是 MySQL 专有语法：仅自增主键才加，且一个表至多一个 AUTO_INCREMENT 列
        // （InnoDB 要求其为某个索引首列，主键天然满足）；非 IDENTITY 生成的主键（如手工赋值）不加
        if (col.getGenerationType() == GenerationType.IDENTITY && col.isPrimaryKey()) {
            sb.append(" AUTO_INCREMENT");
        }

        if (col.getComment() != null && !col.getComment().isEmpty()) {
            sb.append(" COMMENT '").append(escapeComment(col.getComment())).append("'");
        }

        return sb.toString();
    }

    /**
     * 转义 SQL 注释中的特殊字符：单引号会提前终结 COMMENT 字符串，换行会截断语句，均需反斜杠转义。
     */
    private static String escapeComment(String comment) {
        return comment.replace("'", "\\'").replace("\n", "\\n");
    }
}
