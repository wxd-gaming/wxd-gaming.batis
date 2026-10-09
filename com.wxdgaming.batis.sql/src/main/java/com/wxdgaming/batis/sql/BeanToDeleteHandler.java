package com.wxdgaming.batis.sql;

import com.wxdgaming.batis.model.TableInfo;

/**
 * 删除数据构建接口（方言 SPI）
 * <p>各数据库方言通过此接口提供差异化的 DELETE 实现（如标识符引用风格）。</p>
 * <p>本接口只负责"删数据、留结构"。清空全表走 {@link #buildDeleteAllSql} 的 DELETE 语义，
 * 逐行触发约束检查且不重置自增序列；需要 TRUNCATE 那种"复位结构级统计"的快路径时，
 * 用的是 {@link TableTruncateHandler}，两者不能互相替代。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-28
 **/
public interface BeanToDeleteHandler {

    /**
     * 构建 DELETE 语句（按当前 bean 的主键值定位删除）
     * <p>实现方必须保证：WHERE 覆盖全部主键列、只出现占位符，并且主键列顺序与
     * {@code getColumns()} 迭代顺序一致——参数由调用方按同一顺序绑定。
     * 实体没有主键时必须快速失败（MySQL 实现抛 IllegalArgumentException），
     * 无条件 DELETE 等于删全表。</p>
     *
     * @param bean 待删除的实体对象，仅用于推导实体类型与主键列
     * @return 参数化 DELETE 语句，不含主键字面量
     */
    String buildDeleteByIdSql(Object bean);

    /**
     * 提取 DELETE 预编译参数数组（主键值）
     * <p>数量与顺序必须与 {@link #buildDeleteByIdSql} 的占位符严格对应；
     * 值要经过 {@link BeanValueHandler#convertToDbValue}，保证复合主键里的
     * 日期、枚举等类型也能被驱动绑定。</p>
     *
     * @param bean 待删除的实体对象（主键字段必须有值）
     * @return 与占位符同序的主键值数组
     */
    Object[] buildDeleteByIdParams(Object bean);

    /**
     * 构建清空全表的 DELETE 语句
     * <p>返回值不带 WHERE、不带分号，也不接受任何参数——它是无条件语句，
     * 因此调用方（{@code BeanRunner#deleteAll}）会把它交给写保护与日志检查过的执行路径。
     * 表名引用风格同样由方言决定，但注意此处由实现自行拼接，不经过 {@link BeanToSelectHandler#quote}。</p>
     *
     * @param tableInfo 目标表元数据
     * @return 清空全表的 DELETE 语句
     */
    String buildDeleteAllSql(TableInfo tableInfo);

}
