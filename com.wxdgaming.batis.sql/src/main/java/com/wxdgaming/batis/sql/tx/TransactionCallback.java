package com.wxdgaming.batis.sql.tx;

/**
 * 事务回调接口
 * <p>之所以不抛受检异常：业务代码里 {@code throws SQLException} 的事务回调只会逼人在每层套 try/catch，
 * 而本框架已把所有 JDBC 异常翻译成 {@code DataAccessException} 家族（RuntimeException），
 * 让异常自然穿透即可正确触发回滚。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
@FunctionalInterface
public interface TransactionCallback<T> {

    /**
     * 在事务中执行的业务逻辑；抛出任何异常都会触发回滚，
     * 也可通过 {@link TransactionStatus#setRollbackOnly()} 主动标记回滚。
     * <p>
     * <b>"捕获异常后不重抛"等于"提交"</b>：想要回滚就必须让异常穿透，或者显式标记 rollbackOnly。
     * 这是本接口最容易踩的坑——callback 内部 catch 掉一个约束冲突然后正常返回，
     * 框架只会看到"这段逻辑跑成功了"，于是把已经写坏的数据提交掉。
     * </p>
     * <p>回调里请通过 {@code template.beanRunner()} 等工厂拿 Runner（它们会自动复用事务连接）；
     * 自己 {@code dataSource.getConnection()} 的那条连接不在事务上，写了也不会被回滚。</p>
     *
     * @param status 事务状态句柄，用于标记回滚与查询当前事务属性
     * @return 业务返回值，原样透传给 {@code TransactionTemplate#execute}
     */
    T doInTransaction(TransactionStatus status);

}
