package com.wxdgaming.batis;

import java.util.List;

/**
 * 把 db 记录读取并映射为实体类的处理器，与 {@link BeanToDbHandler} 对称的反向接口
 * <p>面向接口编程，上层可依赖此接口而非具体数据库实现，从而适配不同版本（mysql / pgsql ...）。</p>
 * <p>
 * 泛型参数 {@code T extends DbTemplate} 是让实现只接受"自己那一族"的模板：MySQL 的实现拿到
 * pgsql 模板时在编译期就通不过，而不是运行期才发现注入了错的处理器。
 * </p>
 * <p>
 * 注意本接口是 core 层的<b>读侧门面</b>，与 sql 层的 {@code SqlDbToBeanHandler}（单行值转换）不是一回事：
 * 前者管"怎么发起一次查询并把结果集变成实体"，后者管"一列的值怎么变成 Java 字段"。
 * </p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-25
 **/
public interface DbToBeanHandler<T extends DbTemplate> {

    /**
     * 按主键查询单条记录，未查询到返回 null
     * <p>单主键传值，复合主键传 {@code Object[]}（顺序需与实体主键声明顺序一致）。
     * 返回 null 而不是抛异常：按主键查不到是正常业务状态。</p>
     */
    <E> E queryById(T dbTemplate, Class<E> entityClass, Object idValue);

    /**
     * 自定义 SQL 查询单条记录，未查询到返回 null
     * <p>裸 SQL 入口，列名与占位符由调用方负责；框架不做 whereSection 那样的注入检查。</p>
     */
    <E> E query(T dbTemplate, Class<E> entityClass, String sql, Object... args);

    /** 自定义 SQL 查询多条记录；无结果时返回空 List，不返回 null */
    <E> List<E> queryList(T dbTemplate, Class<E> entityClass, String sql, Object... args);

    /** 查询表中所有记录（全表扫描，只适合小表或启动期加载） */
    <E> List<E> queryAll(T dbTemplate, Class<E> entityClass);

}
