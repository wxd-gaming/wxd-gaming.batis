package com.wxdgaming.batis.exception;

/**
 * 非法事务状态异常
 * <p>如只读事务内执行写操作、未开启事务却调用提交、传播行为不支持等。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class IllegalTransactionStateException extends TransactionException {

    public IllegalTransactionStateException(String message) {
        super(message);
    }

    public IllegalTransactionStateException(String message, Throwable cause) {
        super(message, cause);
    }

}
