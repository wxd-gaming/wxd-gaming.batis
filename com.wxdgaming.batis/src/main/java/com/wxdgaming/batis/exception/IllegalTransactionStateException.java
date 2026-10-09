package com.wxdgaming.batis.exception;

/**
 * 非法事务状态异常
 * <p>如只读事务内执行写操作、未开启事务却调用提交、传播行为不支持等。</p>
 * <p>
 * 实际抛出点（都是框架主动检查，不来自 SQLException 翻译）：
 * <ul>
 *   <li>QueryRunner 的写保护 checkWritable()：当前线程处于只读事务时，
 *       execute/update/executeBatch/executeInsert 一律拒绝——驱动和 MySQL 的只读连接
 *       并不阻止所有写，所以"只读"必须靠这里变成硬约束；</li>
 *   <li>事务模板的 MANDATORY 传播行为：当前线程没有事务；</li>
 *   <li>事务模板遇到不支持的传播行为枚举值；</li>
 *   <li>缓存执行器在事务中把延迟写的 FlushMode 配成 TX_SUSPEND；</li>
 *   <li>在非事务执行体上调用 setRollbackOnly()（没有 holder 可以标记）。</li>
 * </ul>
 * </p>
 * <p>
 * 它表示调用方的用法与事务语义相矛盾，属于必须改代码的编程错误：重试不会让状态变合法，
 * 换连接也没用。排查方向是传播行为、readOnly 标志和 FlushMode 的组合，
 * 而不是这条 SQL 本身。另外它只有 message，没有 sql/errorCode——失败发生在拿连接之前或
 * 语句根本没执行，没有可归属的 SQL。
 * </p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class IllegalTransactionStateException extends TransactionException {

    /** 主用法：状态检查不通过，没有底层异常 */
    public IllegalTransactionStateException(String message) {
        super(message);
    }

    /** 需要保留底层异常时使用 */
    public IllegalTransactionStateException(String message, Throwable cause) {
        super(message, cause);
    }

}
