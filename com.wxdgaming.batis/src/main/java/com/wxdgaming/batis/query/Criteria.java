package com.wxdgaming.batis.query;

import java.util.ArrayList;
import java.util.List;

/**
 * 查询条件表达式树（不可变，线程安全，可作为常量复用）
 * <p>用法：</p>
 * <pre>{@code
 * Criteria c = Criteria.where("status").eq("PAID")
 *         .and(Criteria.where("amount").gt(100))
 *         .or(Criteria.allOf(
 *                 Criteria.where("channel").in("A", "B"),
 *                 Criteria.where("vip").isNotNull()));
 * List<Order> list = template.beanRunner().select(Order.class).where(c).list();
 * }</pre>
 * <p>核心不变式：叶子条件的访问顺序 = SQL 占位符顺序 = 参数绑定顺序；
 * SQL 渲染（列引用、括号、占位符）由 sql 层 SelectSqlBuilder 通过 {@link Visitor} 单点完成。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public final class Criteria {

    /** 节点类型，渲染层用于判断括号优先级 */
    public enum Kind { LEAF, AND, OR, NOT }

    /**
     * 条件树访问器：S 为渲染产物类型
     */
    public interface Visitor<S> {
        S visitLeaf(Criterion criterion);

        S visitLogical(Kind kind /* AND 或 OR */, List<S> children);

        S visitNot(S child);
    }

    private final Kind kind;
    private final Criterion leaf;
    private final List<Criteria> children;

    private Criteria(Kind kind, Criterion leaf, List<Criteria> children) {
        this.kind = kind;
        this.leaf = leaf;
        this.children = children;
    }

    public Kind getKind() {
        return kind;
    }

    /** LEAF 节点的叶子条件，其他节点返回 null */
    public Criterion getLeaf() {
        return leaf;
    }

    /** 由叶子条件创建 Criteria（包内：CriteriaBuilder 使用） */
    static Criteria of(Criterion criterion) {
        return new Criteria(Kind.LEAF, criterion, null);
    }

    /** 访问树结构（渲染入口） */
    public <S> S accept(Visitor<S> visitor) {
        return switch (kind) {
            case LEAF -> visitor.visitLeaf(leaf);
            case AND, OR -> visitor.visitLogical(kind,
                    children.stream().map(c -> c.accept(visitor)).toList());
            case NOT -> visitor.visitNot(children.getFirst().accept(visitor));
        };
    }

    /** 开始构建列条件，如 {@code Criteria.where("status").eq("PAID")} */
    public static CriteriaBuilder where(String column) {
        return new CriteriaBuilder(column);
    }

    /** 全部子条件 AND；单个条件直接返回自身 */
    public static Criteria allOf(Criteria... conditions) {
        return composite(Kind.AND, conditions);
    }

    /** 任一子条件 OR；单个条件直接返回自身 */
    public static Criteria anyOf(Criteria... conditions) {
        return composite(Kind.OR, conditions);
    }

    /** 取反 */
    public static Criteria not(Criteria condition) {
        return new Criteria(Kind.NOT, null, List.of(condition));
    }

    /**
     * 原始 SQL 片段逃生舱（有注入风险，仅限受信输入），如 {@code Criteria.raw("JSON_EXTRACT(meta, '$.k') = ?", v)}
     */
    public static Criteria raw(String fragment, Object... params) {
        return new Criteria(Kind.LEAF,
                new Criterion(fragment, CriterionOperator.RAW, List.of(params)), null);
    }

    private static Criteria composite(Kind kind, Criteria... conditions) {
        List<Criteria> list = new ArrayList<>();
        for (Criteria c : conditions) {
            if (c != null) list.add(c);
        }
        if (list.isEmpty()) {
            throw new IllegalArgumentException("条件组合不能为空");
        }
        if (list.size() == 1) return list.getFirst();
        return new Criteria(kind, null, List.copyOf(list));
    }

    /** 与另一条件 AND */
    public Criteria and(Criteria other) {
        return allOf(this, other);
    }

    /** 与另一条件 OR */
    public Criteria or(Criteria other) {
        return anyOf(this, other);
    }

    /** 取反（实例风格） */
    public Criteria negate() {
        return not(this);
    }

    /** 叶子条件序列，顺序即占位符顺序 */
    public List<Criterion> flatten() {
        List<Criterion> result = new ArrayList<>();
        collect(result);
        return result;
    }

    private void collect(List<Criterion> out) {
        switch (kind) {
            case LEAF -> out.add(leaf);
            case AND, OR -> children.forEach(c -> c.collect(out));
            case NOT -> children.getFirst().collect(out);
        }
    }

}
