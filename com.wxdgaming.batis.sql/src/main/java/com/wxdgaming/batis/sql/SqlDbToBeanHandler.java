package com.wxdgaming.batis.sql;

import com.wxdgaming.batis.DbToBeanHandler;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * sql 类型数据库的读取处理器接口（方言 SPI）
 * <p>bean / beanList 委托给具体数据库的 {@link #toBean(ResultSet, Class)} 方言实现，
 * 因此做成 default 方法，具体数据库实现只需关心行映射逻辑。</p>
 * <p>它同时是 core 侧 {@link com.wxdgaming.batis.DbToBeanHandler} 在 JDBC 世界的落点：
 * 上层依赖 queryById/query/queryList/queryAll 这些与 SQL 无关的动作，
 * 由本接口把它们翻译成具体语句并消费结果集。这样 core 里一行 SQL 字面量都不会出现，
 * 方言信息也就不会顺着方法签名渗进缓存模型或实体解析层。</p>
 * <p>游标推进的责任划分要看清：{@link #toBean} 处理的是<em>当前行</em>，
 * 不调用 {@code rs.next()}；是否前进由 {@link #bean} / {@link #beanList} 这些 default
 * 包装方法控制。方言若误进游标，就会跳过记录或读漏记录，而且只在列表查询时暴露。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-25
 **/
public interface SqlDbToBeanHandler extends DbToBeanHandler<JdbcTemplate> {

    /**
     * 数据库特定的行映射逻辑（JSON/枚举/时间/二进制等方言差异在此实现）
     * <p>实现方必须保证：</p>
     * <ul>
     *   <li>只读当前行，不推进游标、不关闭 ResultSet；</li>
     *   <li>按列标签匹配实体列且大小写不敏感——查询里的别名、驱动的返回形态都可能不同；</li>
     *   <li>结果集中匹配不到实体的列直接跳过（投影查询与联表场景常见），
     *       值为 NULL 的列也跳过，让字段保持 Java 默认值而不是写入 null；</li>
     *   <li>实体需要无参构造，缺失时以 SQLException 明确报出，别让反射异常裸奔。</li>
     * </ul>
     *
     * @param rs          已定位到目标行的结果集
     * @param entityClass 实体类
     * @param <E>         实体类型
     * @return 映射后的实体
     * @throws SQLException 读取列值或实例化实体失败
     */
    <E> E toBean(ResultSet rs, Class<E> entityClass) throws SQLException;

    /**
     * 读取第一行映射为实体，无数据返回 null
     * <p>注意是"取第一行"而非"必须唯一"，多于一条时不报错；
     * 需要唯一性约束的语义在查询 DSL 的 {@code one()} 里实现。</p>
     *
     * @param entityClass 实体类
     * @param <E>         实体类型
     * @return 交给执行层消费的结果集处理器
     */
    default <E> ResultSetHandler<E> bean(Class<E> entityClass) {
        return rs -> rs.next() ? toBean(rs, entityClass) : null;
    }

    /**
     * 读取所有行映射为实体列表
     * <p>空结果返回空 List 而非 null，调用方无需判空；
     * 全量物化在内存里，大表请走分页而不是本方法。</p>
     *
     * @param entityClass 实体类
     * @param <E>         实体类型
     * @return 交给执行层消费的结果集处理器
     */
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
