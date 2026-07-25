package com.wxdgaming.batis;

import java.util.List;

/**
 * 把 db 记录读取并映射为实体类的处理器，与 {@link BeanToDbHandler} 对称的反向接口
 * <p>面向接口编程，上层可依赖此接口而非具体数据库实现，从而适配不同版本（mysql / pgsql ...）。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-25
 **/
public interface DbToBeanHandler<T extends DbTemplate> {

    /** 按主键查询单条记录，未查询到返回 null */
    <E> E queryById(T dbTemplate, Class<E> entityClass, Object idValue);

    /** 自定义 SQL 查询单条记录，未查询到返回 null */
    <E> E query(T dbTemplate, Class<E> entityClass, String sql, Object... args);

    /** 自定义 SQL 查询多条记录 */
    <E> List<E> queryList(T dbTemplate, Class<E> entityClass, String sql, Object... args);

    /** 查询表中所有记录 */
    <E> List<E> queryAll(T dbTemplate, Class<E> entityClass);

}
