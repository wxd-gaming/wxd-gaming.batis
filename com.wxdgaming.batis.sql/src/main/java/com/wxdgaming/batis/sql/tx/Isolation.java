package com.wxdgaming.batis.sql.tx;

/**
 * 事务隔离级别
 * <p>DEFAULT 表示沿用数据库连接默认级别，不主动调用 setTransactionIsolation。</p>
 * <p>
 * <b>为什么优先用 DEFAULT</b>：MySQL 默认是 REPEATABLE_READ，而多数业务真正需要的是
 * READ_COMMITTED 语义；把级别写死在框架里等于替所有项目做决定。级别属于库的部署配置
 * （也影响主从一致性方案），只在确有并发正确性诉求时才在这里显式指定。
 * </p>
 * <p>没有提供 READ_UNCOMMITTED：脏读会读到别的线程尚未提交、随后回滚的幽灵数据，
 * 一个中间件不该给这种档位留入口。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public enum Isolation {

    /** 使用数据库默认隔离级别（不发起 setTransactionIsolation，因此不会额外打断连接池的连接复用假设） */
    DEFAULT,
    /** 读已提交：避免脏读，仍有不可重复读 */
    READ_COMMITTED,
    /** 可重复读：MySQL/MariaDB 的默认级别 */
    REPEATABLE_READ,
    /** 串行化：并发最低、锁范围最大，只在必须绝对一致的统计/对账场景使用 */
    SERIALIZABLE;

    /**
     * 转换为 JDBC Connection 的隔离级别常量，DEFAULT 返回 null
     * <p>返回 null 而不是抛错：{@code TransactionTemplate.doBegin} 用"null 就跳过设置"来表达
     * "不动库端默认"，这样 DEFAULT 与显式指定走同一条代码路径。</p>
     */
    public Integer toJdbcConstant() {
        return switch (this) {
            case DEFAULT -> null;
            case READ_COMMITTED -> java.sql.Connection.TRANSACTION_READ_COMMITTED;
            case REPEATABLE_READ -> java.sql.Connection.TRANSACTION_REPEATABLE_READ;
            case SERIALIZABLE -> java.sql.Connection.TRANSACTION_SERIALIZABLE;
        };
    }

}
