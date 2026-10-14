package com.wxdgaming.batis.sql.pgsql;

/**
 * {@link PgShape} 的实现类，用于多态 JSON 列回归。
 * <p>{@code area()} 不是 getter，不参与序列化；被写进列的只有 {@code color} 与 {@code radius}，
 * 加上框架追加的类型标记。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 **/
public class PgCircle implements PgShape {

    private String color;
    private double radius;

    public PgCircle() {
    }

    public PgCircle(String color, double radius) {
        this.color = color;
        this.radius = radius;
    }

    @Override
    public double area() {
        return Math.PI * radius * radius;
    }

    @Override
    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public double getRadius() {
        return radius;
    }

    public void setRadius(double radius) {
        this.radius = radius;
    }
}
