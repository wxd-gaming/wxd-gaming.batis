package com.wxdgaming.batis.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.RemovalCause;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 基于 Caffeine 的高性能本地缓存实现
 * <ul>
 *   <li><b>固定过期</b>：{@code expireAfterWrite} —— 写入后经过指定时间即过期，无论期间是否被访问；
 *       保证热 key 也会定期回源刷新，跨进程/跨节点的陈旧数据有界</li>
 *   <li><b>防击穿</b>：{@link #getOrLoad} 单飞加载，Caffeine 保证同一 Key 仅一个线程执行 loader</li>
 *   <li><b>防穿透</b>：配合 {@link com.wxdgaming.batis.sql.CachedBeanRunner} 的 Tombstone 机制</li>
 *   <li><b>淘汰兜底</b>：通过 {@link #onEvict} 注册回调，缓存过期/容量挤出时<b>同步</b>通知上层刷脏数据
 *       （用 evictionListener 而非 removalListener，见构造器注释）</li>
 *   <li><b>整表失效</b>：{@link #invalidateByPrefix} 按 key 前缀清理，供 deleteAll 这类场景使用</li>
 * </ul>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-08-05
 */
public class LocalEntityCache<K, V> implements EntityCache<K, V> {

    private static final Logger log = LoggerFactory.getLogger(LocalEntityCache.class);

    private final LoadingCache<K, V> cache;
    private volatile Consumer<Map.Entry<K, V>> evictionListener;

    /**
     * 默认配置：最大 100000 条，写入 30 分钟后过期
     */
    public LocalEntityCache() {
        this(100_000, 30, TimeUnit.MINUTES);
    }

    /**
     * @param maxSize          最大缓存条目数
     * @param expireAfterWrite 写入后过期时间（固定过期：热 key 也会到期回源刷新，陈旧数据有界）
     * @param timeUnit         时间单位
     */
    public LocalEntityCache(long maxSize,
                            long expireAfterWrite,
                            TimeUnit timeUnit) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(expireAfterWrite, timeUnit)
                .recordStats()
                // 关键：维护任务（含淘汰与回调）直接在调用线程上执行。
                // Caffeine 默认把维护丢到 ForkJoinPool.commonPool，那样即使换成 evictionListener，
                // 淘汰通知仍是"另一个线程稍后执行"——脏数据可能还没轮到落库进程就退出了。
                // 代价是容量/过期淘汰发生时，put/get 所在线程会承担一次兜底刷盘（见 EntityCache#onEvict 契约）。
                .executor(Runnable::run)
                // 用 evictionListener（只在被动淘汰时同步回调）而不是 removalListener：
                // 显式 remove/invalidate 属主动移除，其脏数据由 CachedBeanRunner 就地处理，不能重复刷盘
                .evictionListener((K key, V value, RemovalCause cause) -> {
                    if (value != null && evictionListener != null) {
                        try {
                            evictionListener.accept(Map.entry(key, value));
                        } catch (Exception e) {
                            log.warn("淘汰回调异常 key={}", key, e);
                        }
                    }
                })
                .build(k -> {
                    // 故意不提供全局 loader：本缓存的加载语义是"每 Key 携带各自的查库函数"，
                    // 直接调 Caffeine 的 get(key) 会走到这里并报错，强制使用方走 getOrLoad(key, loader)
                    throw new IllegalStateException(
                            "请使用 getOrLoad(key, loader)，不要直接调用 get(key)");
                });
    }

    @Override
    public V getIfPresent(K key) {
        return cache.getIfPresent(key);
    }

    /**
     * 防击穿的单飞加载：同一 Key 并发下 Caffeine 只放行一个线程执行 loader，其余等待复用结果。
     * <p>loader 抛出的异常<b>原样上抛</b>，不降级为 null：DB 故障与"记录不存在"是两回事，
     * 把前者吞成 null 会让调用方把数据库宕机误判成"查无此数据"，进而按"不存在"走错误分支。
     * null 只由"记录确实不存在"的 loader 正常返回产生。</p>
     */
    @Override
    public V getOrLoad(K key, Function<K, V> loader) {
        return cache.get(key, loader::apply);
    }

    @Override
    public void put(K key, V value) {
        // 忽略 null 值：不把"无数据"写进缓存（穿透防护交给上层 CachedBeanRunner 的 Tombstone 哨兵，
        // 哨兵本身是非 null 引用，因此能通过此处的过滤被正常缓存）
        if (value != null) {
            cache.put(key, value);
        }
    }

    @Override
    public void remove(K key) {
        cache.invalidate(key);
    }

    /**
     * 按 key 前缀失效（整表失效用）。
     * <p>
     * Caffeine 没有原生前缀失效，这里遍历 {@code asMap().keySet()} 逐个失效。迭代是弱一致的、
     * 不阻塞读写；遍历期间新写入的同前缀条目可能漏掉，属于可接受语义（整表清空本身就该与业务写互斥）。
     * </p>
     * <p>走 {@code invalidate} 而不是 {@code asMap().remove} 是为了保持"主动移除不触发淘汰回调"
     * 这一契约：{@link #onEvict} 只应对被动淘汰（过期/容量）做出反应。</p>
     */
    @Override
    public void invalidateByPrefix(K keyPrefix) {
        if (keyPrefix == null) return;
        String prefix = String.valueOf(keyPrefix);
        cache.asMap().keySet().removeIf(k -> String.valueOf(k).startsWith(prefix));
    }

    @Override
    public void clear() {
        cache.invalidateAll();
    }

    @Override
    public long size() {
        return cache.estimatedSize();
    }

    @Override
    public void onEvict(Consumer<Map.Entry<K, V>> listener) {
        this.evictionListener = listener;
    }

    /** 获取缓存统计信息（命中率、加载耗时等，用于监控） */
    public String stats() {
        return cache.stats().toString();
    }
}
