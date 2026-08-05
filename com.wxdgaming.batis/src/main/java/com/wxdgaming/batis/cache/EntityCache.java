package com.wxdgaming.batis.cache;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 实体缓存接口
 * <p>定义缓存的基本操作，支持本地内存缓存、Redis 等不同实现。</p>
 *
 * @param <K> 缓存键类型
 * @param <V> 缓存值类型
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-08-05
 */
public interface EntityCache<K, V> {

    /** 获取缓存（不触发加载，返回 null 表示不存在） */
    V getIfPresent(K key);

    /**
     * 获取或加载缓存（防击穿）
     * <p>实现应保证同一 Key 同一时刻只有一个线程执行 loader。</p>
     *
     * @param key    缓存键
     * @param loader 加载函数（查 DB 逻辑）
     * @return 缓存值，加载失败返回 null
     */
    V getOrLoad(K key, Function<K, V> loader);

    /** 写入缓存 */
    void put(K key, V value);

    /** 移除缓存 */
    void remove(K key);

    /** 清空所有缓存 */
    void clear();

    /** 缓存条目数（近似值） */
    long size();

    /**
     * 注册缓存淘汰回调
     * <p>当缓存条目因过期 / 容量限制被移除时触发。</p>
     *
     * @param listener 回调，entry 包含 key 和 value
     */
    void onEvict(Consumer<Map.Entry<K, V>> listener);
}
