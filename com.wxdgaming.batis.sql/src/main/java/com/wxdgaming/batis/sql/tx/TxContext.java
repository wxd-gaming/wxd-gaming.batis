package com.wxdgaming.batis.sql.tx;

import java.util.HashMap;
import java.util.Map;

/**
 * 线程级事务上下文
 * <p>通过 ThreadLocal 把 {@link com.wxdgaming.batis.sql.JdbcTemplate} → {@link ConnectionHolder}
 * 绑定到当前线程；一个线程可同时参与多个 JdbcTemplate 的事务。
 * 刻意不使用 InheritableThreadLocal，避免线程池场景下子线程误读父线程事务。</p>
 * <p>
 * <b>为什么 key 是 JdbcTemplate 而不是全局一个槽位</b>：多数据源/读写分离下每个模板有各自的连接与事务，
 * 只有一个槽位时，模板 A 的事务会被模板 B 的 Runner 误判成"我已经在事务里"，
 * 于是既不借连接也不归还，表现为莫名的连接泄漏。
 * </p>
 * <p>内部框架使用，业务代码通常只需要 {@link TransactionTemplate}。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public final class TxContext {

    /** key = 模板实例，value = 该模板在当前线程上的事务持有者 */
    private static final ThreadLocal<Map<Object, ConnectionHolder>> RESOURCES =
            ThreadLocal.withInitial(HashMap::new);

    private TxContext() {
    }

    /**
     * 获取指定模板（key）当前线程绑定的连接持有者，无则返回 null
     * <p>注意"非 null"不等于"在事务中"：判断是否真的要参与事务请看
     * {@link #isActive(Object)}，它额外检查了 {@code isTransactional()}。</p>
     */
    public static ConnectionHolder get(Object key) {
        return RESOURCES.get().get(key);
    }

    /**
     * 绑定事务持有者。
     * <p>同一个 key 重复绑定会<b>覆盖</b>旧值——REQUIRES_NEW 靠"先 unbind 再 bind 新的、
     * 结束后把 suspended 的 bind 回来"实现挂起与恢复，所以调用方必须成对使用，
     * 漏一次 unbind 就会让外层事务的连接永久丢失（表现为提交后数据不见或连接泄漏到池外）。</p>
     */
    public static void bind(Object key, ConnectionHolder holder) {
        RESOURCES.get().put(key, holder);
    }

    /**
     * 解绑。
     * <p>map 空了必须 {@code RESOURCES.remove()}：线程池的线程不会退出，
     * 留着 ThreadLocalMap 条目等于给下一个复用该线程的请求留下"还在事务里"的假象，
     * 也会让这个 HashMap 实例永久挂在强可达链上。</p>
     */
    public static void unbind(Object key) {
        Map<Object, ConnectionHolder> map = RESOURCES.get();
        map.remove(key);
        if (map.isEmpty()) {
            RESOURCES.remove();
        }
    }

    /**
     * 指定模板当前线程是否处于事务中
     * <p>判空之外还要看 {@code isTransactional()}：SUPPORTS/NOT_SUPPORTED 会以非事务身份执行回调，
     * 那种情况下不应被误判为"我在事务里"。</p>
     */
    public static boolean isActive(Object key) {
        ConnectionHolder holder = get(key);
        return holder != null && holder.isTransactional();
    }

    /**
     * 当前线程该模板是否处于只读事务中
     * <p>写操作前的闸门（{@code QueryRunner#checkWritable}）只看这个布尔，
     * 不看连接是否真的设了 readOnly——只读事务可能还没借到连接。</p>
     */
    public static boolean isCurrentReadOnly(Object key) {
        ConnectionHolder holder = get(key);
        return holder != null && holder.isTransactional() && holder.isReadOnly();
    }

    /** 注册事务同步回调（挂在 holder 上，由开启事务的一层触发） */
    public static void register(ConnectionHolder holder, TransactionSynchronization sync) {
        holder.getSynchronizations().add(sync);
    }

}
