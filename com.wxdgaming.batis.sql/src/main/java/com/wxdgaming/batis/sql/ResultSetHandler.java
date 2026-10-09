package com.wxdgaming.batis.sql;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 结果集处理器
 * <p>执行层与映射层之间唯一的接缝：QueryRunner 负责借连接、执行语句、收尾关闭，
 * 本接口负责"把结果集变成调用方要的形状"。这样新增方言时只换映射实现，
 * 不用碰任何 JDBC 生命周期代码。</p>
 * <p>实现方的契约边界：</p>
 * <ul>
 *   <li>拿到的是尚未推进游标的活结果集，是否 {@code next()} 由实现自己决定，
 *       但不得调用 {@code close()}——语句与连接归执行层管；</li>
 *   <li>抛出的 SQLException 由执行层交给 {@link SqlExceptionHandler} 翻译成统一的
 *       数据访问异常，实现里不必也不应自行包装；</li>
 *   <li>空结果的形态由实现定义（单条语义返回 null、列表语义返回空集合），
 *       执行层不做补充判断，因此两侧要选一致。</li>
 * </ul>
 *
 * @param <R> 处理结果类型
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-24 17:33
 **/
@FunctionalInterface
public interface ResultSetHandler<R> {

    /**
     * 消费结果集并产出调用方所需对象
     *
     * @param rs 已执行、游标位于首行之前的结果集
     * @return 映射结果，形态由实现决定（可为 null）
     * @throws SQLException 读取失败，交由执行层翻译
     */
    R handle(ResultSet rs) throws SQLException;

}
