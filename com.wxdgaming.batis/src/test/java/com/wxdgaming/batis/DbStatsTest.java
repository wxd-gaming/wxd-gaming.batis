package com.wxdgaming.batis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * DbStats 统计口径测试。
 * <p>守的是"最小值统计"这条曾经的错误实现：早期用 0 当"尚未初始化"哨兵，
 * 一旦记录过一次 0ms（毫秒计时下很常见），下一次更大的耗时会直接覆盖 min，
 * 报出来的最小值偏大。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 */
class DbStatsTest {

    @Test
    void minKeepsZeroAfterLaterLargerRecords() {
        DbStats stats = new DbStats();
        stats.recordInsert(0, 1);
        stats.recordInsert(200, 1);
        stats.recordInsert(50, 1);
        assertEquals(0, stats.getInsertStats().getMinMs(), "0ms 记录之后最小值必须仍是 0");
        assertEquals(200, stats.getInsertStats().getMaxMs());
    }

    @Test
    void minTracksSmallestAfterFirstRecord() {
        DbStats stats = new DbStats();
        stats.recordUpdate(30, 1);
        stats.recordUpdate(7, 1);
        stats.recordUpdate(90, 1);
        assertEquals(7, stats.getUpdateStats().getMinMs());
        assertEquals(3, stats.getUpdateStats().getCallCount());
        assertEquals(127, stats.getUpdateStats().getTotalMs());
    }

    @Test
    void resetClearsMinInitialization() {
        DbStats stats = new DbStats();
        stats.recordDelete(0, 1);
        stats.reset();
        assertEquals(0, stats.getDeleteStats().getCallCount());
        // 重置之后第一条记录必须被当作"首次"，否则会拿重置前的值当基准
        stats.recordDelete(40, 1);
        assertEquals(40, stats.getDeleteStats().getMinMs());
    }

    @Test
    void disabledStatsRecordNothing() {
        DbStats stats = new DbStats();
        stats.setEnabled(false);
        stats.recordInsert(10, 5);
        assertEquals(0, stats.getTotalCalls());
        assertEquals(0, stats.getTotalRecords());
    }
}
