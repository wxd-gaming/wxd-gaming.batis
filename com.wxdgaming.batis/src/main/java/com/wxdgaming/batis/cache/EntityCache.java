package com.wxdgaming.batis.cache;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 实体缓存接口
 * <p>定义缓存的基本操作，支持本地内存缓存、Redis 等不同实现。</p>
 * <p>
 * 这里的契约不是风格问题：CachedBeanRunner（Write-Behind）直接依赖它们来实现
 * "刷盘时摘除条目 + 淘汰时兜底落库"这套逻辑。任何一条被实现成了非原子操作，
 * 都会表现为极难复现的丢数据或重复写，而不是立刻报错。
 * </p>
 *
 * @param <K> 缓存键类型
 * @param <V> 缓存值类型
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-08-05
 */
public interface EntityCache<K, V> {

    /**
     * 获取缓存（不触发加载，返回 null 表示不存在）
     * <p>实现必须能区分"没有这个 key"和"值本身是那个哨兵对象"：
     * CachedBeanRunner 会往缓存里放一个 TOMBSTONE 占位值来表示"这行已删除"，
     * 若实现把任意非 null 值都当作有效条目返回，删除标记就会在读路径上"复活"成一条数据。</p>
     */
    V getIfPresent(K key);

    /**
     * 获取或加载缓存（防击穿）
     * <p>实现应保证同一 Key 同一时刻只有一个线程执行 loader。</p>
     * <p>loader 抛出的异常应原样上抛，不要吞成 null：null 只表示"记录不存在"，
     * 吞异常会把 DB 故障伪装成查无数据，调用方将按"不存在"走错误分支。</p>
     *
     * @param key    缓存键
     * @param loader 加载函数（查 DB 逻辑）
     * @return 缓存值，记录不存在时返回 null
     */
    V getOrLoad(K key, Function<K, V> loader);

    /** 写入缓存（覆盖已有值） */
    void put(K key, V value);

    /**
     * 移除缓存
     * <p>刷盘路径靠它摘除条目，所以"移除"与"淘汰回调"的分工必须清楚：
     * 主动 remove 表示"上层自己知道这条数据去了哪儿"（通常已经写进脏表或已落库），
     * 因此不应触发 {@link #onEvict}；只有被动淘汰（过期/容量挤出）才需要兜底回调，
     * 否则同一份改动会被刷两次。本地实现用淘汰原因区分这两类，换实现时请保持同样的区分。</p>
     */
    void remove(K key);

    /** 清空所有缓存（不保证触发 onEvict，因此未刷盘的脏数据不能只靠它收尾） */
    void clear();

    /** 缓存条目数（近似值，仅用于监控展示，不要用来做刷盘决策） */
    long size();

    /**
     * 注册缓存淘汰回调
     * <p>当缓存条目因过期 / 容量限制被移除时触发。</p>
     * <p>这是脏数据的<b>最后一道</b>落库机会：条目被容量挤掉时如果不再补写，
     * 那次修改就永久丢了。回调里应当同步刷库，且不能假定"之后还会再有别的回调"；
     * 回调抛出的异常不应导致缓存自身状态损坏（实现需自行兜住）。</p>
     *
     * @param listener 回调，entry 包含 key 和 value
     */
    void onEvict(Consumer<Map.Entry<K, V>> listener);
}
