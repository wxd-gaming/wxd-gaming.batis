package com.wxdgaming.batis.sql.pgsql;

/**
 * 多态 JSON 列回归用的形状接口。
 * <p>刻意只留一个方法：多态列要验的是"运行时子类能不能还原"，字段越少越容易看出
 * 读回来的到底是接口代理还是具体实现类。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 **/
public interface PgShape {

    double area();

    String getColor();
}
