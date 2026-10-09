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
 * <p>
 * 为什么渲染与执行要分成两个类：渲染是纯函数（给定 handler + TableInfo + 规格，产出 SQL 与参数），
 * 因此可以被单测直接断言文本、被 toSql() 用于排障、将来被拦截器改写；
 * 一旦把它并进 SelectQuery，就必须连库才能验证 SQL 长得对不对。
 * </p>
 * <p>
 * 三段"方言味道"通过 handler 的默认方法注入：{@code quote()} 引号风格、
 * {@code appendPaging()} 分页子句、{@code buildCountSql()} 计数语句。
 * 默认实现按 MySQL 口径（反引号 + {@code LIMIT ? OFFSET ?}），换方言只需覆盖这三个方法。
 * </p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public final class SelectSqlBuilder {

    /**
     * 渲染产物：SQL 文本 + 按序参数
     * <p>params 与 SQL 里的 {@code ?} 严格同序同数，调用方直接整组绑给 PreparedStatement。
     * 之所以不返回 {@code List}：数组更适合直接传参，且 Plan 一旦产出就视为不可变。</p>
     */
    public record Plan(String sql, Object[] params) {
    }

    private SelectSqlBuilder() {
    }

    /** 渲染 SELECT（含列解析、条件树、排序、分页） */
    public static Plan build(BeanToSelectHandler handler, TableInfo table, SelectQuerySpec spec) {
        return render(handler, table, spec, false);
    }

    /**
     * 渲染 SELECT COUNT(*)（忽略排序与分页）
     * <p>排序对计数无意义，分页会让计数变成本页条数——所以这两段在 countOnly 分支里整块跳过，
     * 而不是"照常渲染再删掉"，避免参数顺序被分页参数污染。</p>
     */
    public static Plan buildCount(BeanToSelectHandler handler, TableInfo table, SelectQuerySpec spec) {
        return render(handler, table, spec, true);
    }

    private static Plan render(BeanToSelectHandler handler, TableInfo table, SelectQuerySpec spec, boolean countOnly) {
        // 参数列表在这里创建，交给条件渲染器和分页子句按出现顺序追加——单点保证顺序不变式
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
                    // 投影列同样要过 resolveColumn：写非法列名时报 UnknownColumnException，而不是把裸字符串拼进 SQL
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
                            // 方向来自 Sort.Direction 枚举，不是用户字符串，无注入面
                            .append(' ').append(order.direction().name());
                    first = false;
                }
            }
            // 分页参数必须最后追加：不变式 [WHERE..., LIMIT, OFFSET] 全靠这一步的位置
            handler.appendPaging(sql, params, spec.limit(), spec.offset());
        }
        return new Plan(sql.toString(), params.toArray());
    }

    /**
     * 解析列名：数据库列名（忽略大小写）或 Java 字段名（原样或驼峰转下划线）
     * <p>
     * 三种写法都要认，否则业务代码就得记住"实体字段 createTime 在库里叫 create_time"这种映射；
     * 但解析失败必须抛 {@link UnknownColumnException} 而不是原样拼进去——
     * 这里是 DSL 唯一的"标识符白名单"，放行未知列等于把列名变成注入点，
     * 也会让拼错的字段名一路带到数据库才报 1054，栈信息离真正的调用点更远。
     * </p>
     * <p>查找顺序：先按小写列名命中缓存 map（O(1)，最常见路径），再线性扫字段名。</p>
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

    /** 驼峰转下划线（与建表时的列名推导同一套规则，否则 Java 字段名永远解析不到列） */
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

    /**
     * 渲染片段 + 来源节点类型（用于括号判断）
     * <p>kind 不是装饰：只有知道子节点是不是 OR，父节点才知道要不要加括号。</p>
     */
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
                // raw 的 column 槽位存的是 SQL 片段本身：逃生舱，不引用、不校验、值仍走占位符绑定
                params.addAll(c.values());
                return new Frag(c.column(), Criteria.Kind.LEAF);
            }
            String qc = handler.quote(resolveColumn(table, c.column()).getColumnName());
            return switch (c.op()) {
                case IS_NULL -> new Frag(qc + " IS NULL", Criteria.Kind.LEAF);
                case IS_NOT_NULL -> new Frag(qc + " IS NOT NULL", Criteria.Kind.LEAF);
                case EQ_COL -> {
                    // 列=列比较：右值也是标识符，同样必须解析+引用，不能当参数绑定
                    String other = handler.quote(
                            resolveColumn(table, (String) c.values().getFirst()).getColumnName());
                    yield new Frag(qc + " = " + other, Criteria.Kind.LEAF);
                }
                case IN, NOT_IN -> {
                    StringBuilder ph = new StringBuilder(qc).append(' ').append(c.op().getSql()).append(" (");
                    // 占位符个数由值列表决定，二者必须同步增长，否则绑定阶段错位
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
                // AND 下的 OR 子节点必须加括号：SQL 里 AND 优先级高于 OR，
                // 漏括号会让 (a OR b) AND c 变成 a OR (b AND c)，结果集静默扩大
                boolean wrap = kind == Criteria.Kind.AND && child.kind() == Criteria.Kind.OR;
                if (wrap) sb.append('(').append(child.sql()).append(')');
                else sb.append(child.sql());
            }
            return new Frag(sb.toString(), kind);
        }

        @Override
        public Frag visitNot(Frag child) {
            // NOT 一律带括号：既是安全写法，也让返回的 kind=NOT 在上层 AND 下不会被误判成需再包一层
            return new Frag("NOT (" + child.sql() + ")", Criteria.Kind.NOT);
        }
    }

    /**
     * SelectQuery 的渲染视图（包内接口，避免暴露可变内部状态）
     * <p>渲染器只读这五个值，因此 SelectQuery 可以安全地被反复终端执行，
     * 也允许 buildPlan 用不可变 record 造一个"临时改了 limit"的快照而不污染原构建器。</p>
     */
    public interface SelectQuerySpec {
        Criteria where();

        List<String> columns();

        Sort sort();

        Integer limit();

        Integer offset();
    }

}
