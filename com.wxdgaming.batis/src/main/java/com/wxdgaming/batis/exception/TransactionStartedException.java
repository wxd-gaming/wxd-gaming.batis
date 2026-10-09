package com.wxdgaming.batis.exception;

/**
 * 事务开启/提交/回滚自身失败
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class TransactionStartedException extends TransactionException {

    public TransactionStartedException(String message, Throwable cause) {
        super(message, cause);
    }

}
