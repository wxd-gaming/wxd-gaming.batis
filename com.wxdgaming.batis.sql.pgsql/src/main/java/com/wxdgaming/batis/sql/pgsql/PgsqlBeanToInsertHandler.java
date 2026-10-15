package com.wxdgaming.batis.sql.pgsql;

import com.wxdgaming.batis.annotation.GenerationType;
import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.TableInfo;
import com.wxdgaming.batis.parser.EntityParser;
import com.wxdgaming.batis.sql.BeanToInsertHandler;
import com.wxdgaming.batis.sql.BeanValueHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

/**
 * PostgreSQL 方言的 INSERT 处理器
 * <p>表名和列名使用双引号 {@code "} 引用，全小写（见 {@link PgsqlIdentifier}）。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-14
 **/
@Slf4j
public class PgsqlBeanToInsertHandler implements BeanToInsertHandler {

    private final BeanValueHandler valueHandler;

    public PgsqlBeanToInsertHandler(BeanValueHandler valueHandler) {
        this.valueHandler = valueHandler;
    }

    /**
     * 构建 INSERT 语句：{@code INSERT INTO "t" ("c1", "c2", ...) VALUES (?, ?, ...)}，全参数化防注入。
     * <p>自增主键列被跳过——它的值由 PG 的 IDENTITY 生成，写入反而易撞键或占号。</p>
     * <p>与 {@link #buildInsertParams} 共享同一次 {@code tableInfo.getColumns()} 的遍历顺序，
     * 占位符与参数才能一一对应；两边都必须用同一个 isIdentityPk 谓词过滤。</p>
     */
    @Override
    public String buildInsertSql(Object bean) {
        TableInfo tableInfo = EntityParser.parse(bean.getClass());
        LinkedHashMap<String, ColumnInfo> columns = tableInfo.getColumns();

        List<String> colNames = new ArrayList<>();
        for (ColumnInfo col : columns.values()) {
            if (isIdentityPk(col)) continue;
            colNames.add(PgsqlIdentifier.quote(col.getColumnName()));
        }

        // 全部列都是自增主键（或只有一列 IDENTITY）时没有可绑定列：
        // "() VALUES ()" 在 PG 是语法错误，标准写法是 DEFAULT VALUES
        if (colNames.isEmpty()) {
            String sql = "INSERT INTO " + PgsqlIdentifier.quote(tableInfo.getTableName()) + " DEFAULT VALUES";
            log.debug("构建 INSERT sql:\n{}", sql);
            return sql;
        }

        String cols = String.join(", ", colNames);
        String placeholders = colNames.stream()
                .map(c -> "?")
                .collect(Collectors.joining(", "));

        String sql = "INSERT INTO " + PgsqlIdentifier.quote(tableInfo.getTableName())
                + " (" + cols + ") VALUES (" + placeholders + ")";
        log.debug("构建 INSERT sql:\n{}", sql);
        return sql;
    }

    /**
     * 提取 INSERT 预编译参数数组，顺序与 {@link #buildInsertSql} 生成的占位符严格对应。
     * <p>同样跳过自增主键列，每个字段值先经 {@code readFieldValue} 反射取值，再经
     * {@link BeanValueHandler#convertToDbValue} 做方言值转换（日期/枚举/JSON/BLOB）。</p>
     */
    @Override
    public Object[] buildInsertParams(Object bean) {
        TableInfo tableInfo = EntityParser.parse(bean.getClass());
        List<Object> params = new ArrayList<>();
        for (ColumnInfo col : tableInfo.getColumns().values()) {
            if (isIdentityPk(col)) continue;
            params.add(valueHandler.convertToDbValue(
                    valueHandler.readFieldValue(bean, col), col));
        }
        return params.toArray();
    }

    /**
     * 返回自增主键列（供执行后按列名取回生成键），无自增主键返回 null。
     * <p>这一列名在 PG 上是必需的而不是便利的：pgjdbc 把
     * {@code RETURN_GENERATED_KEYS} 实现成 {@code RETURNING *}，结果集第一列是<b>表的第一个字段</b>，
     * 主键不在首列的实体就会把别的列的值当成 ID 回写。</p>
     */
    @Override
    public ColumnInfo getIdentityColumn(Object bean) {
        TableInfo tableInfo = EntityParser.parse(bean.getClass());
        for (ColumnInfo col : tableInfo.getColumns().values()) {
            if (isIdentityPk(col)) return col;
        }
        return null;
    }

    /**
     * 将数据库回写的自增主键写回 bean 的 id 字段（委托 valueHandler 做类型收窄后 setter 写入）。
     * <p>回写不可缺：否则调用方手里是一条"没有 ID 的实体"，紧接着的 update/delete 全部失效。</p>
     */
    @Override
    public void writeGeneratedKey(Object bean, Number genId) {
        ColumnInfo identityColumn = getIdentityColumn(bean);
        if (identityColumn != null && genId != null) {
            valueHandler.writeGeneratedKey(bean, identityColumn, genId);
        }
    }

    /** 判定"由数据库生成"的自增主键：既是主键、生成策略又是 IDENTITY，此谓词被本类多处复用 */
    private static boolean isIdentityPk(ColumnInfo col) {
        return col.isPrimaryKey() && col.getGenerationType() == GenerationType.IDENTITY;
    }
}
