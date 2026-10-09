package com.wxdgaming.batis.sql.tx;

/**
 * 事务回调接口
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
@FunctionalInterface
public interface TransactionCallback<T> {

    /**
     * 在事务中执行的业务逻辑；抛出任何异常都会触发回滚，
     * 也可通过 {@link TransactionStatus#setRollbackOnly()} 主动标记回滚。
     */
    T doInTransaction(TransactionStatus status);

}
