package com.wxdgaming.batis.sql.tx;

/**
 * 事务隔离级别
 * <p>DEFAULT 表示沿用数据库连接默认级别，不主动调用 setTransactionIsolation。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public enum Isolation {

    /** 使用数据库默认隔离级别 */
    DEFAULT,
    READ_COMMITTED,
    REPEATABLE_READ,
    SERIALIZABLE;

    /** 转换为 JDBC Connection 的隔离级别常量，DEFAULT 返回 null */
    public Integer toJdbcConstant() {
        return switch (this) {
            case DEFAULT -> null;
            case READ_COMMITTED -> java.sql.Connection.TRANSACTION_READ_COMMITTED;
            case REPEATABLE_READ -> java.sql.Connection.TRANSACTION_REPEATABLE_READ;
            case SERIALIZABLE -> java.sql.Connection.TRANSACTION_SERIALIZABLE;
        };
    }

}
