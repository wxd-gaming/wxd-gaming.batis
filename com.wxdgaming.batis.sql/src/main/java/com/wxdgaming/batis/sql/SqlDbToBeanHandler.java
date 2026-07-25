package com.wxdgaming.batis.sql;

import com.wxdgaming.batis.DbToBeanHandler;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * sql类型数据库 读取处理器接口，与 {@link BeanToSqlDbHandler} 对称
 * <p>bean / beanList 委托给具体数据库的 {@link #toBean(ResultSet, Class)} 方言实现，
 * 因此做成 default 方法，具体数据库实现只需关心行映射逻辑。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-25
 **/
public interface SqlDbToBeanHandler<T extends JdbcTemplate> extends DbToBeanHandler<T> {

    /** 数据库特定的行映射逻辑（JSON/枚举/时间/二进制等方言差异在此实现） */
    <E> E toBean(ResultSet rs, Class<E> entityClass) throws SQLException;

    /** 读取第一行映射为实体，无数据返回 null */
    default <E> ResultSetHandler<E> bean(Class<E> entityClass) {
        return rs -> rs.next() ? toBean(rs, entityClass) : null;
    }

    /** 读取所有行映射为实体列表 */
    default <E> ResultSetHandler<List<E>> beanList(Class<E> entityClass) {
        return rs -> {
            List<E> list = new ArrayList<>();
            while (rs.next()) {
                list.add(toBean(rs, entityClass));
            }
            return list;
        };
    }

}
