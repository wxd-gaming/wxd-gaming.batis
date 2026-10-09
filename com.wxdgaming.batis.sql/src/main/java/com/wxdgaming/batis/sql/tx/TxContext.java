package com.wxdgaming.batis.sql.tx;

import java.util.HashMap;
import java.util.Map;

/**
 * 线程级事务上下文
 * <p>通过 ThreadLocal 把 {@link com.wxdgaming.batis.sql.JdbcTemplate} → {@link ConnectionHolder}
 * 绑定到当前线程；一个线程可同时参与多个 JdbcTemplate 的事务。
 * 刻意不使用 InheritableThreadLocal，避免线程池场景下子线程误读父线程事务。</p>
 * <p>内部框架使用，业务代码通常只需要 {@link TransactionTemplate}。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public final class TxContext {

    private static final ThreadLocal<Map<Object, ConnectionHolder>> RESOURCES =
            ThreadLocal.withInitial(HashMap::new);

    private TxContext() {
    }

    /** 获取指定模板（key）当前线程绑定的连接持有者，无则返回 null */
    public static ConnectionHolder get(Object key) {
        return RESOURCES.get().get(key);
    }

    public static void bind(Object key, ConnectionHolder holder) {
        RESOURCES.get().put(key, holder);
    }

    public static void unbind(Object key) {
        Map<Object, ConnectionHolder> map = RESOURCES.get();
        map.remove(key);
        if (map.isEmpty()) {
            RESOURCES.remove();
        }
    }

    /** 指定模板当前线程是否处于事务中 */
    public static boolean isActive(Object key) {
        ConnectionHolder holder = get(key);
        return holder != null && holder.isTransactional();
    }

    /** 当前线程该模板是否处于只读事务中 */
    public static boolean isCurrentReadOnly(Object key) {
        ConnectionHolder holder = get(key);
        return holder != null && holder.isTransactional() && holder.isReadOnly();
    }

    /** 注册事务同步回调（挂在 holder 上，由开启事务的一层触发） */
    public static void register(ConnectionHolder holder, TransactionSynchronization sync) {
        holder.getSynchronizations().add(sync);
    }

}
