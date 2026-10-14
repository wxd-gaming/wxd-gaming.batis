package com.wxdgaming.batis.sql.pgsql;

import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.TableInfo;
import com.wxdgaming.batis.parser.EntityParser;
import com.wxdgaming.batis.sql.BeanToDeleteHandler;
import com.wxdgaming.batis.sql.BeanToSelectHandler;
import com.wxdgaming.batis.sql.BeanToUpdateHandler;
import com.wxdgaming.batis.sql.BeanValueHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * PostgreSQL 方言的 UPDATE / DELETE / SELECT 处理器
 * <p>三者都只做同一件事：把 MySQL 侧内联的反引号换成 {@link PgsqlIdentifier} 的双引号口径，
 * 外加 SELECT 的分页写法差异。合成一个类而不是照 MySQL 拆三个文件，是因为拆出来之后
 * 三个类体加起来仍不到三十行、且没有任何独立演化点（值转换一律委托 {@link BeanValueHandler}），
 * 拆分的成本（装配三个实例、三处注释）大于收益。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-14
 **/
@Slf4j
public class PgsqlBeanToStatementHandler implements BeanToUpdateHandler, BeanToDeleteHandler, BeanToSelectHandler {

    private final BeanValueHandler valueHandler;

    /** 注入与读端共享的值转换协作者（主键参数也需按方言转换后再绑定） */
    public PgsqlBeanToStatementHandler(BeanValueHandler valueHandler) {
        this.valueHandler = valueHandler;
    }

    // ===== UPDATE =====

    /**
     * 构建 UPDATE 语句：非主键列进 SET、主键列进 WHERE，形如
     * {@code UPDATE "t" SET "a" = ?, "b" = ? WHERE "id" = ?}。
     * <p>主键只作定位条件、不出现在 SET 里，天然避免"改到自己主键"；复合主键以 AND 串联全部主键列。
     * 无主键实体无法安全定位目标行，直接抛异常拒绝，好过退化成全表更新。</p>
     */
    @Override
    public String buildUpdateSql(Object bean) {
        TableInfo tableInfo = EntityParser.parse(bean.getClass());

        List<String> setCols = new ArrayList<>();
        List<String> whereCols = new ArrayList<>();

        for (ColumnInfo col : tableInfo.getColumns().values()) {
            String condition = PgsqlIdentifier.quote(col.getColumnName()) + " = ?";
            if (col.isPrimaryKey()) {
                whereCols.add(condition);
            } else {
                setCols.add(condition);
            }
        }

        if (whereCols.isEmpty()) {
            throw new IllegalArgumentException(
                    "实体 " + bean.getClass().getSimpleName() + " 未定义主键，无法执行 update");
        }

        String sql = "UPDATE " + PgsqlIdentifier.quote(tableInfo.getTableName()) + " SET "
                + String.join(", ", setCols) + " WHERE " + String.join(" AND ", whereCols);
        log.debug("构建 UPDATE sql:\n{}", sql);
        return sql;
    }

    /**
     * 提取 UPDATE 预编译参数：SET 段参数在前、WHERE 段主键参数在后。
     * <p>这是与 {@link #buildUpdateSql} 配套的参数顺序不变式——SQL 文本里 SET 的 ? 先于 WHERE 的 ?，
     * 参数数组必须同序拼接，错位会把主键值绑到业务列上。</p>
     */
    @Override
    public Object[] buildUpdateParams(Object bean) {
        TableInfo tableInfo = EntityParser.parse(bean.getClass());

        List<Object> setParams = new ArrayList<>();
        List<Object> whereParams = new ArrayList<>();

        for (ColumnInfo col : tableInfo.getColumns().values()) {
            Object dbValue = valueHandler.convertToDbValue(
                    valueHandler.readFieldValue(bean, col), col);
            if (col.isPrimaryKey()) {
                whereParams.add(dbValue);
            } else {
                setParams.add(dbValue);
            }
        }

        // SET 参数在前，WHERE 参数在后
        setParams.addAll(whereParams);
        return setParams.toArray();
    }

    // ===== DELETE =====

    /**
     * 构建按主键删除语句：{@code DELETE FROM "t" WHERE "id" = ?}（复合主键以 AND 串联）。
     * <p>无主键实体直接抛异常：没有 WHERE 的 DELETE 等于清表，绝不能静默生成。</p>
     */
    @Override
    public String buildDeleteByIdSql(Object bean) {
        TableInfo tableInfo = EntityParser.parse(bean.getClass());

        List<String> whereCols = new ArrayList<>();
        for (ColumnInfo col : tableInfo.getColumns().values()) {
            if (col.isPrimaryKey()) {
                whereCols.add(PgsqlIdentifier.quote(col.getColumnName()) + " = ?");
            }
        }

        if (whereCols.isEmpty()) {
            throw new IllegalArgumentException(
                    "实体 " + bean.getClass().getSimpleName() + " 未定义主键，无法执行 deleteById");
        }

        String sql = "DELETE FROM " + PgsqlIdentifier.quote(tableInfo.getTableName())
                + " WHERE " + String.join(" AND ", whereCols);
        log.debug("构建 DELETE BY ID sql:\n{}", sql);
        return sql;
    }

    /** 提取 WHERE 主键参数：与 buildDeleteByIdSql 的 ? 同序（按列声明顺序收集主键列），并做方言值转换 */
    @Override
    public Object[] buildDeleteByIdParams(Object bean) {
        TableInfo tableInfo = EntityParser.parse(bean.getClass());

        List<Object> whereParams = new ArrayList<>();
        for (ColumnInfo col : tableInfo.getColumns().values()) {
            if (col.isPrimaryKey()) {
                whereParams.add(valueHandler.convertToDbValue(
                        valueHandler.readFieldValue(bean, col), col));
            }
        }
        return whereParams.toArray();
    }

    /**
     * 构建无 WHERE 的全表 DELETE（逐行清空数据）。
     * <p>与 TRUNCATE 的分工见 {@link PgsqlTableTruncateHandler}：DELETE 逐行删除、事务内可回滚、
     * 触发行级触发器，且<b>不重置序列</b>；TRUNCATE 是 DDL、隐式提交并归零 IDENTITY。</p>
     */
    @Override
    public String buildDeleteAllSql(TableInfo tableInfo) {
        String sql = "DELETE FROM " + PgsqlIdentifier.quote(tableInfo.getTableName());
        log.debug("构建 DELETE ALL sql:\n{}", sql);
        return sql;
    }

    // ===== SELECT =====

    /**
     * 构建按主键查询：{@code SELECT * FROM "t" WHERE "pk" = ?}（复合主键以 AND 串联）。
     * <p>无主键无法安全定位单行，抛异常拒绝，与 INSERT/UPDATE/DELETE 的主键约束口径一致。</p>
     */
    @Override
    public String buildSelectByIdSql(TableInfo tableInfo) {
        List<String> whereCols = new ArrayList<>();
        for (ColumnInfo col : tableInfo.getColumns().values()) {
            if (col.isPrimaryKey()) {
                whereCols.add(PgsqlIdentifier.quote(col.getColumnName()) + " = ?");
            }
        }

        if (whereCols.isEmpty()) {
            throw new IllegalArgumentException(
                    "实体 " + tableInfo.getEntityClass().getSimpleName() + " 未定义主键，无法执行 queryById");
        }

        return "SELECT * FROM " + PgsqlIdentifier.quote(tableInfo.getTableName())
                + " WHERE " + String.join(" AND ", whereCols);
    }

    /** 构建全表查询：{@code SELECT * FROM "t"}；排序/分页由查询 DSL 经本类接口方法追加 */
    @Override
    public String buildSelectAllSql(TableInfo tableInfo) {
        return "SELECT * FROM " + PgsqlIdentifier.quote(tableInfo.getTableName());
    }

    /**
     * 标识符引用：查询 DSL（{@code SelectSqlBuilder}）拼 SELECT 列表、WHERE、ORDER BY 时都走这里，
     * 所以方言只需覆盖这一处就能把整条 DSL 的引号口径换掉。
     */
    @Override
    public String quote(String identifier) {
        return PgsqlIdentifier.quote(identifier);
    }

    /**
     * 分页子句：{@code LIMIT ? OFFSET ?}，两个子句各自独立可选。
     * <p>接口默认实现是 MySQL 语义（LIMIT 必须先于 OFFSET，只有偏移时补一个
     * {@code LIMIT 18446744073709551615}）。这个数字在 PG 会直接 22001 out of range
     * （PG 的 LIMIT 是 bigint），而 PG 本来就允许只写 OFFSET，所以这里改成"按需追加"：</p>
     * <ul>
     *   <li>只有 limit → {@code LIMIT ?}</li>
     *   <li>只有 offset → {@code OFFSET ?}（不补哨兵 LIMIT）</li>
     *   <li>两者都有 → {@code LIMIT ? OFFSET ?}</li>
     * </ul>
     * <p>参数顺序与子句出现顺序一致，这也是"只有偏移"时不再往参数表里塞那个巨大数字的原因——
     * 绑进去就等于把一个越界值交给驱动。</p>
     */
    @Override
    public void appendPaging(StringBuilder sql, List<Object> params, Integer limit, Integer offset) {
        boolean hasLimit = limit != null && limit > 0;
        boolean hasOffset = offset != null && offset > 0;
        if (hasLimit) {
            sql.append(" LIMIT ?");
            params.add(limit);
        }
        if (hasOffset) {
            sql.append(" OFFSET ?");
            params.add(offset);
        }
    }
}
