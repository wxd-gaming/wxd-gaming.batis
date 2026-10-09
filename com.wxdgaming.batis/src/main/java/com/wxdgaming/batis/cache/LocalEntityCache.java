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
 *   <li><b>滑动过期</b>：{@code expireAfterAccess} —— 只要持续访问就不会过期，无人访问才淘汰</li>
 *   <li><b>防击穿</b>：{@link #getOrLoad} 单飞加载，Caffeine 保证同一 Key 仅一个线程执行 loader</li>
 *   <li><b>防雪崩</b>：{@code refreshAfterWrite} 在过期前异步刷新，热数据不集体过期</li>
 *   <li><b>防穿透</b>：配合 {@link com.wxdgaming.batis.sql.CachedBeanRunner} 的 Tombstone 机制</li>
 *   <li><b>淘汰兜底</b>：通过 {@link #onEvict} 注册回调，缓存过期时通知上层刷脏数据</li>
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
     * 默认配置：最大 100000 条，30 分钟无人访问后过期，写入 25 分钟后访问触发异步刷新
     */
    public LocalEntityCache() {
        this(100_000, 30, 25, TimeUnit.MINUTES);
    }

    /**
     * @param maxSize            最大缓存条目数
     * @param expireAfterAccess  访问后过期时间（滑动过期：一直访问则不过期，无人访问后才过期）
     * @param refreshAfterWrite  写入后刷新时间（返回旧值 + 异步加载新值）
     * @param timeUnit           时间单位
     */
    public LocalEntityCache(long maxSize,
                            long expireAfterAccess,
                            long refreshAfterWrite,
                            TimeUnit timeUnit) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterAccess(expireAfterAccess, timeUnit)
                .refreshAfterWrite(refreshAfterWrite, timeUnit)
                .recordStats()
                .removalListener((K key, V value, RemovalCause cause) -> {
                    // 只在"被动淘汰"（过期/容量溢出）时回调兜底刷盘；显式 remove/invalidate 属主动移除，
                    // 其脏数据由 CachedBeanRunner 就地处理，不能在这里重复触发刷盘
                    if (cause.wasEvicted() && value != null && evictionListener != null) {
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
     * <p>loader 抛异常时降级返回 null（不缓存失败），避免瞬时 DB 抖动污染缓存。</p>
     */
    @Override
    public V getOrLoad(K key, Function<K, V> loader) {
        try {
            return cache.get(key, loader::apply);
        } catch (Exception e) {
            log.warn("缓存加载失败 key={}", key, e);
            return null;
        }
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
