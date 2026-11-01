package com.wxdgaming.batis.sql.partition;

import com.wxdgaming.batis.sql.JdbcTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分区缓存的离线验收测试。
 *
 * <p>缓存存在的唯一理由是让"每天补一次分区"的作业可以幂等重跑，所以本类守两件事：</p>
 * <ol>
 *   <li><b>命中后不再读库</b>：一表一次元数据查询，后续 add/drop 增量维护；</li>
 *   <li><b>读取失败不写缓存</b>：这是审核修复的核心——旧实现把失败结果（空集合）固化进缓存，
 *   一次瞬时抖动会变成进程生命周期内"这张表永远没有分区"，之后每次补分区都重复 ADD、
 *   库端报分区已存在，而日志里只有当初一条 warn。失败必须是<b>可重试</b>的。</li>
 * </ol>
 *
 * <p>用假 {@link PartitionMetaReader} 控制返回值与失败次数，不连库。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-11-01
 */
class PartitionCacheTest {

    /** 可编排返回值/失败的元数据源 */
    private static final class FakeReader implements PartitionMetaReader {
        final AtomicInteger calls = new AtomicInteger();
        final List<String> tables = new ArrayList<>();
        List<String> next = List.of();
        RuntimeException failure;

        @Override
        public List<String> getPartitionNames(JdbcTemplate jdbcTemplate, String tableName) {
            calls.incrementAndGet();
            tables.add(tableName);
            if (failure != null) throw failure;
            return next;
        }
    }

    @Test
    @DisplayName("首次访问读库并缓存；后续命中不再读库")
    void partitionsAreLoadedOnce() {
        FakeReader reader = new FakeReader();
        reader.next = List.of("p20260801", "pmax");
        PartitionCache cache = new PartitionCache(null, reader);

        Set<String> first = cache.getPartitions("T_LOG");
        Set<String> second = cache.getPartitions("t_log");

        assertEquals(Set.of("p20260801", "pmax"), first);
        assertSame(first, second, "第二次必须命中同一份缓存");
        assertEquals(1, reader.calls.get(), "同一张表只应读一次库");
        assertEquals(List.of("t_log"), reader.tables, "表名按小写查询/缓存");
    }

    @Test
    @DisplayName("读取失败返回临时空集合但不写缓存：下一次访问必须重试")
    void failedLoadIsNotCached() {
        FakeReader reader = new FakeReader();
        reader.failure = new RuntimeException("information_schema unavailable");
        PartitionCache cache = new PartitionCache(null, reader);

        Set<String> degraded = cache.getPartitions("t_log");
        assertTrue(degraded.isEmpty(), "失败时按'无分区'降级，保证 DDL 不被元数据查询直接打断");
        assertEquals(1, reader.calls.get());

        reader.failure = null;
        reader.next = List.of("p20260801");
        Set<String> recovered = cache.getPartitions("t_log");

        assertEquals(2, reader.calls.get(), "失败状态不能被固化，第二次必须重新读库");
        assertEquals(Set.of("p20260801"), recovered, "恢复后应拿到库端真实分区名");
    }

    @Test
    @DisplayName("分区名比对大小写不敏感（不同库/人工书写的大小写不一致）")
    void containsIsCaseInsensitive() {
        FakeReader reader = new FakeReader();
        reader.next = List.of("P20260801");
        PartitionCache cache = new PartitionCache(null, reader);

        assertTrue(cache.contains("t_log", "p20260801"));
        assertTrue(cache.contains("t_log", "P20260801"));
        assertFalse(cache.contains("t_log", "p20260802"));
    }

    @Test
    @DisplayName("add/remove 增量维护缓存；refresh 强制重读库端")
    void incrementalUpdatesAndRefresh() {
        FakeReader reader = new FakeReader();
        reader.next = List.of("p1");
        PartitionCache cache = new PartitionCache(null, reader);

        cache.getPartitions("t_log");
        cache.add("t_log", "p2");
        assertTrue(cache.contains("t_log", "p2"));

        cache.remove("t_log", "p1");
        assertFalse(cache.contains("t_log", "p1"));

        reader.next = List.of("p1", "p2", "p3");
        cache.refresh("t_log");
        assertEquals(Set.of("p1", "p2", "p3"), cache.getPartitions("t_log"));
        assertEquals(2, reader.calls.get(), "refresh 之后必须重新读库");
    }

    @Test
    @DisplayName("refreshAll 清空全部表的缓存")
    void refreshAllClearsEveryTable() {
        FakeReader reader = new FakeReader();
        reader.next = List.of("p1");
        PartitionCache cache = new PartitionCache(null, reader);

        cache.getPartitions("t_a");
        cache.getPartitions("t_b");
        assertEquals(2, reader.calls.get());

        cache.refreshAll();
        cache.getPartitions("t_a");
        cache.getPartitions("t_b");
        assertEquals(4, reader.calls.get(), "清空后两张表都应重新加载");
    }

    @Test
    @DisplayName("preload 批量预热：每张表各读一次")
    void preloadWarmsEveryTable() {
        FakeReader reader = new FakeReader();
        PartitionCache cache = new PartitionCache(null, reader);

        cache.preload("t_a", "t_b", "t_c");

        assertEquals(List.of("t_a", "t_b", "t_c"), reader.tables);
    }
}
