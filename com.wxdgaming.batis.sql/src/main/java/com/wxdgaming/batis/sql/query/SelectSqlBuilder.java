package com.wxdgaming.batis.sql.query;

import com.wxdgaming.batis.exception.UnknownColumnException;
import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.TableInfo;
import com.wxdgaming.batis.query.Criteria;
import com.wxdgaming.batis.query.Criterion;
import com.wxdgaming.batis.query.CriterionOperator;
import com.wxdgaming.batis.query.Sort;
import com.wxdgaming.batis.sql.BeanToSelectHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 查询 DSL 的 SQL 渲染器（方言无关骨架 + handler 提供的差异点）
 * <p>参数顺序不变式在 {@link #render} 单点实现：
 * {@code [WHERE 展平参数..., LIMIT, OFFSET]}，count 渲染只含 WHERE 参数。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public final class SelectSqlBuilder {

    /** 渲染产物：SQL 文本 + 按序参数 */
    public record Plan(String sql, Object[] params) {
    }

    private SelectSqlBuilder() {
    }

    /** 渲染 SELECT（含列解析、条件树、排序、分页） */
    public static Plan build(BeanToSelectHandler handler, TableInfo table, SelectQuerySpec spec) {
        return render(handler, table, spec, false);
    }

    /** 渲染 SELECT COUNT(*)（忽略排序与分页） */
    public static Plan buildCount(BeanToSelectHandler handler, TableInfo table, SelectQuerySpec spec) {
        return render(handler, table, spec, true);
    }

    private static Plan render(BeanToSelectHandler handler, TableInfo table, SelectQuerySpec spec, boolean countOnly) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder();
        if (countOnly) {
            sql.append(handler.buildCountSql(table));
        } else {
            sql.append("SELECT ");
            if (spec.columns() == null || spec.columns().isEmpty()) {
                sql.append('*');
            } else {
                boolean first = true;
                for (String col : spec.columns()) {
                    if (!first) sql.append(", ");
                    sql.append(handler.quote(resolveColumn(table, col).getColumnName()));
                    first = false;
                }
            }
            sql.append(" FROM ").append(handler.quote(table.getTableName()));
        }
        if (spec.where() != null) {
            Renderer renderer = new Renderer(handler, table, params);
            Frag frag = spec.where().accept(renderer);
            sql.append(" WHERE ").append(frag.sql());
        }
        if (!countOnly) {
            Sort sort = spec.sort();
            if (sort != null && !sort.isEmpty()) {
                sql.append(" ORDER BY ");
                boolean first = true;
                for (Sort.Order order : sort.getOrders()) {
                    if (!first) sql.append(", ");
                    sql.append(handler.quote(resolveColumn(table, order.property()).getColumnName()))
                            .append(' ').append(order.direction().name());
                    first = false;
                }
            }
            handler.appendPaging(sql, params, spec.limit(), spec.offset());
        }
        return new Plan(sql.toString(), params.toArray());
    }

    /**
     * 解析列名：数据库列名（忽略大小写）或 Java 字段名（原样或驼峰转下划线）
     */
    public static ColumnInfo resolveColumn(TableInfo table, String name) {
        if (name == null || name.isEmpty()) {
            throw new UnknownColumnException("列名不能为空");
        }
        String lower = name.toLowerCase(Locale.ROOT);
        ColumnInfo col = table.getColumns().get(lower);
        if (col != null) return col;
        for (ColumnInfo c : table.getColumns().values()) {
            if (c.getFieldName() != null &&
                    (c.getFieldName().equals(name) || toUnderline(c.getFieldName()).equals(lower))) {
                return c;
            }
        }
        throw new UnknownColumnException(
                "表 " + table.getTableName() + " 中不存在列/字段: " + name);
    }

    /** 驼峰转下划线 */
    static String toUnderline(String camel) {
        StringBuilder sb = new StringBuilder(camel.length() + 4);
        for (int i = 0; i < camel.length(); i++) {
            char ch = camel.charAt(i);
            if (Character.isUpperCase(ch)) {
                if (i > 0) sb.append('_');
                sb.append(Character.toLowerCase(ch));
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    /** 渲染片段 + 来源节点类型（用于括号判断） */
    record Frag(String sql, Criteria.Kind kind) {
    }

    /** Criteria 树访问器：叶子渲染为条件片段，按序收集参数 */
    private static final class Renderer implements Criteria.Visitor<Frag> {
        private final BeanToSelectHandler handler;
        private final TableInfo table;
        private final List<Object> params;

        Renderer(BeanToSelectHandler handler, TableInfo table, List<Object> params) {
            this.handler = handler;
            this.table = table;
            this.params = params;
        }

        @Override
        public Frag visitLeaf(Criterion c) {
            if (c.op() == CriterionOperator.RAW) {
                params.addAll(c.values());
                return new Frag(c.column(), Criteria.Kind.LEAF);
            }
            String qc = handler.quote(resolveColumn(table, c.column()).getColumnName());
            return switch (c.op()) {
                case IS_NULL -> new Frag(qc + " IS NULL", Criteria.Kind.LEAF);
                case IS_NOT_NULL -> new Frag(qc + " IS NOT NULL", Criteria.Kind.LEAF);
                case EQ_COL -> {
                    String other = handler.quote(
                            resolveColumn(table, (String) c.values().getFirst()).getColumnName());
                    yield new Frag(qc + " = " + other, Criteria.Kind.LEAF);
                }
                case IN, NOT_IN -> {
                    StringBuilder ph = new StringBuilder(qc).append(' ').append(c.op().getSql()).append(" (");
                    for (int i = 0; i < c.values().size(); i++) {
                        ph.append(i > 0 ? ", ?" : "?");
                    }
                    ph.append(')');
                    params.addAll(c.values());
                    yield new Frag(ph.toString(), Criteria.Kind.LEAF);
                }
                case BETWEEN -> {
                    params.addAll(c.values());
                    yield new Frag(qc + " BETWEEN ? AND ?", Criteria.Kind.LEAF);
                }
                default -> {
                    params.addAll(c.values());
                    yield new Frag(qc + " " + c.op().getSql() + " ?", Criteria.Kind.LEAF);
                }
            };
        }

        @Override
        public Frag visitLogical(Criteria.Kind kind, List<Frag> children) {
            String separator = kind == Criteria.Kind.AND ? " AND " : " OR ";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < children.size(); i++) {
                Frag child = children.get(i);
                if (i > 0) sb.append(separator);
                // AND 下的 OR 子节点需要括号，保证优先级
                boolean wrap = kind == Criteria.Kind.AND && child.kind() == Criteria.Kind.OR;
                if (wrap) sb.append('(').append(child.sql()).append(')');
                else sb.append(child.sql());
            }
            return new Frag(sb.toString(), kind);
        }

        @Override
        public Frag visitNot(Frag child) {
            return new Frag("NOT (" + child.sql() + ")", Criteria.Kind.NOT);
        }
    }

    /**
     * SelectQuery 的渲染视图（包内接口，避免暴露可变内部状态）
     */
    public interface SelectQuerySpec {
        Criteria where();

        List<String> columns();

        Sort sort();

        Integer limit();

        Integer offset();
    }

}
