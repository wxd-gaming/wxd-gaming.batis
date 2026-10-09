package com.wxdgaming.batis.query;

import java.util.ArrayList;
import java.util.List;

/**
 * 排序规格（不可变）
 * <p>属性名可以是数据库列名或 Java 字段名，由 sql 层解析为实际列。</p>
 * <p>
 * 之所以要"多条排序规则"而不是一个属性加一个方向：分页必须靠一个<b>唯一确定的</b>次序键，
 * 只按创建时间排会有大量同值行，MySQL 对同值行的返回顺序不保证稳定，
 * 于是同一条记录可能在第 1 页和第 2 页各出现一次、另一条则永远不出现。
 * 正确姿势是业务排序字段后面再追一个唯一列（通常是主键）。
 * </p>
 * <p>属性名不在这里校验：core 层不认识任何表结构，校验发生在渲染 SQL 时（未知列抛 UnknownColumnException）。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public final class Sort {

    /** 排序方向；渲染时直接取枚举名拼进 SQL，取值有限，因此不存在注入面 */
    public enum Direction { ASC, DESC }

    /** 单条排序规则：属性 + 方向 */
    public record Order(String property, Direction direction) {
    }

    private final List<Order> orders;

    private Sort(List<Order> orders) {
        this.orders = orders;
    }

    /** 按属性升序，可多列（列之间是"前者相同才看后者"的关系） */
    public static Sort by(String... properties) {
        List<Order> list = new ArrayList<>();
        for (String p : properties) {
            list.add(new Order(p, Direction.ASC));
        }
        // List.copyOf：交出去的实例不能被调用方回头改，否则共享的排序规格会被静默改写
        return new Sort(List.copyOf(list));
    }

    /** 按属性降序，可多列 */
    public static Sort desc(String... properties) {
        List<Order> list = new ArrayList<>();
        for (String p : properties) {
            list.add(new Order(p, Direction.DESC));
        }
        return new Sort(List.copyOf(list));
    }

    /** {@link #by} 的同义写法，便于与 desc 成对阅读 */
    public static Sort asc(String... properties) {
        return by(properties);
    }

    /** 组合排序：本对象的规则在前、other 在后；返回新实例，两者都不变 */
    public Sort and(Sort other) {
        List<Order> merged = new ArrayList<>(orders);
        merged.addAll(other.getOrders());
        return new Sort(List.copyOf(merged));
    }

    /** 排序规则列表（构造时已 copyOf，所以直接交出引用是安全的） */
    public List<Order> getOrders() {
        return orders;
    }

    /** 是否没有任何排序规则；为空意味着不生成 ORDER BY，分页时次序不确定 */
    public boolean isEmpty() {
        return orders.isEmpty();
    }

}
