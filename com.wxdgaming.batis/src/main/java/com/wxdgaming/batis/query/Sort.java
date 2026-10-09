package com.wxdgaming.batis.query;

import java.util.ArrayList;
import java.util.List;

/**
 * 排序规格（不可变）
 * <p>属性名可以是数据库列名或 Java 字段名，由 sql 层解析为实际列。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public final class Sort {

    /** 排序方向 */
    public enum Direction { ASC, DESC }

    /** 单条排序规则：属性 + 方向 */
    public record Order(String property, Direction direction) {
    }

    private final List<Order> orders;

    private Sort(List<Order> orders) {
        this.orders = orders;
    }

    /** 按属性升序，可多列 */
    public static Sort by(String... properties) {
        List<Order> list = new ArrayList<>();
        for (String p : properties) {
            list.add(new Order(p, Direction.ASC));
        }
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

    public static Sort asc(String... properties) {
        return by(properties);
    }

    /** 组合排序 */
    public Sort and(Sort other) {
        List<Order> merged = new ArrayList<>(orders);
        merged.addAll(other.getOrders());
        return new Sort(List.copyOf(merged));
    }

    public List<Order> getOrders() {
        return orders;
    }

    public boolean isEmpty() {
        return orders.isEmpty();
    }

}
