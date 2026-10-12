package com.wxdgaming.batis.cache;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LocalEntityCache 单测（不连 DB）。
 * <p>覆盖两条被 CachedBeanRunner 依赖的契约：</p>
 * <ol>
 *   <li><b>淘汰回调是同步的</b>：本地实现用 Caffeine 的 {@code evictionListener} 而不是
 *       {@code removalListener}（后者默认丢到 ForkJoinPool 上异步执行）。异步意味着
 *       脏数据"最后一道落库机会"可能还没轮到执行进程就退出了。</li>
 *   <li><b>按前缀失效属于主动移除</b>：{@code deleteAll} 依赖它清掉整表缓存，
 *       且不能触发淘汰回调（否则会把已确定丢弃的数据再刷回库里）。</li>
 * </ol>
 *
 * @author wxd-gaming(無心道, 15388152619)
 */
class LocalEntityCacheTest {

    @Test
    void evictionListenerFiresSynchronouslyOnCapacityEviction() {
        LocalEntityCache<String, Object> cache = new LocalEntityCache<>(1, 1, TimeUnit.HOURS);
        List<String> evicted = new ArrayList<>();
        cache.onEvict(entry -> evicted.add(entry.getKey()));

        cache.put("a", "A");
        cache.put("b", "B");   // 容量 1：写入 b 必然把 a 挤出去
        // put 返回时回调已经跑完 —— 这正是"同步"的含义；异步实现这里会是空列表
        assertEquals(List.of("a"), evicted, "淘汰回调必须在 put 返回前同步执行");
        assertEquals("B", cache.getIfPresent("b"));
        assertNull(cache.getIfPresent("a"));
    }

    @Test
    void explicitRemoveDoesNotTriggerEvictionListener() {
        LocalEntityCache<String, Object> cache = new LocalEntityCache<>(100, 1, TimeUnit.HOURS);
        List<String> evicted = new ArrayList<>();
        cache.onEvict(entry -> evicted.add(entry.getKey()));

        cache.put("k", "V");
        cache.remove("k");
        assertTrue(evicted.isEmpty(), "主动 remove 不应触发淘汰回调（脏数据由上层就地处理）");
    }

    @Test
    void invalidateByPrefixRemovesOnlyMatchingKeys() {
        LocalEntityCache<String, Object> cache = new LocalEntityCache<>(100, 1, TimeUnit.HOURS);
        List<String> evicted = new ArrayList<>();
        cache.onEvict(entry -> evicted.add(entry.getKey()));

        cache.put("t_order:1", "o1");
        cache.put("t_order:2", "o2");
        cache.put("t_player:1", "p1");

        cache.invalidateByPrefix("t_order:");
        assertNull(cache.getIfPresent("t_order:1"));
        assertNull(cache.getIfPresent("t_order:2"));
        assertEquals("p1", cache.getIfPresent("t_player:1"), "前缀不匹配的条目必须保留");
        assertTrue(evicted.isEmpty(), "前缀失效是主动移除，不应触发淘汰回调");
    }

    @Test
    void putIgnoresNullValue() {
        LocalEntityCache<String, Object> cache = new LocalEntityCache<>(100, 1, TimeUnit.HOURS);
        cache.put("k", null);
        assertNull(cache.getIfPresent("k"), "null 不入缓存，穿透防护交给上层 Tombstone");
    }

    @Test
    void getOrLoadIsSingleFlightAndDoesNotCacheNull() {
        LocalEntityCache<String, Object> cache = new LocalEntityCache<>(100, 1, TimeUnit.HOURS);
        int[] loads = {0};
        assertEquals("V", cache.getOrLoad("k", k -> {
            loads[0]++;
            return "V";
        }));
        assertEquals("V", cache.getOrLoad("k", k -> {
            loads[0]++;
            return "V";
        }));
        assertEquals(1, loads[0], "第二次应命中缓存，不再执行 loader");

        // loader 返回 null 表示"记录不存在"：既不缓存，也不抛异常
        assertNull(cache.getOrLoad("missing", k -> null));
        assertNull(cache.getIfPresent("missing"));
    }

    @Test
    void defaultInvalidateByPrefixOnCustomCacheIsExplicitlyUnsupported() {
        EntityCache<String, Object> minimal = new EntityCache<>() {
            @Override
            public Object getIfPresent(String key) {
                return null;
            }

            @Override
            public Object getOrLoad(String key, java.util.function.Function<String, Object> loader) {
                return loader.apply(key);
            }

            @Override
            public void put(String key, Object value) {
            }

            @Override
            public void remove(String key) {
            }

            @Override
            public void clear() {
            }

            @Override
            public long size() {
                return 0;
            }

            @Override
            public void onEvict(Consumer<Map.Entry<String, Object>> listener) {
            }
        };
        // 默认实现显式拒绝（而不是静默什么都不做），让"缓存不会自动失效"这件事被调用方看见
        assertThrows(UnsupportedOperationException.class, () -> minimal.invalidateByPrefix("t:"));
    }
}
