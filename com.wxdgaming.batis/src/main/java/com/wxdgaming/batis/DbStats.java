package com.wxdgaming.batis;

import lombok.Getter;
import lombok.Setter;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * 数据库操作性能统计，线程安全。
 *
 * <h3>每个操作类型收集的指标</h3>
 * <ul>
 *   <li><b>单次</b>：最近一次操作的耗时和记录条数</li>
 *   <li><b>累计</b>：调用次数、记录总条数、总耗时、最大/最小/平均耗时</li>
 *   <li><b>5段耗时分布</b>：≤50ms / 50~500ms / 500~1000ms / 1000~5000ms / &gt;5000ms</li>
 * </ul>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-08-07
 */
@Getter
public class DbStats {

    /** 是否启用统计；关闭后 record() 为空操作，零开销 */
    @Setter private volatile boolean enabled = true;

    /** 5段耗时分布的默认边界 */
    private static final long[] DEFAULT_BOUNDARIES = {50, 500, 1000, 5000};

    /** INSERT 统计 */
    private final OpStats insertStats = new OpStats(DEFAULT_BOUNDARIES);
    /** UPDATE 统计 */
    private final OpStats updateStats = new OpStats(DEFAULT_BOUNDARIES);
    /** DELETE 统计 */
    private final OpStats deleteStats = new OpStats(DEFAULT_BOUNDARIES);

    // ==================== 便捷记录方法 ====================

    /**
     * 记录一次 INSERT 操作（仅成功时调用）
     *
     * @param elapsedMs 耗时 (ms)
     * @param records   记录条数
     */
    public void recordInsert(long elapsedMs, int records) {
        if (enabled) insertStats.record(elapsedMs, records);
    }

    /**
     * 记录一次 UPDATE 操作（仅成功时调用）
     *
     * @param elapsedMs 耗时 (ms)
     * @param records   记录条数
     */
    public void recordUpdate(long elapsedMs, int records) {
        if (enabled) updateStats.record(elapsedMs, records);
    }

    /**
     * 记录一次 DELETE 操作（仅成功时调用）
     *
     * @param elapsedMs 耗时 (ms)
     * @param records   记录条数
     */
    public void recordDelete(long elapsedMs, int records) {
        if (enabled) deleteStats.record(elapsedMs, records);
    }

    // ==================== 汇总指标 ====================

    /** 总调用次数 */
    public long getTotalCalls() {
        return insertStats.getCallCount() + updateStats.getCallCount() + deleteStats.getCallCount();
    }

    /** 总记录条数 */
    public long getTotalRecords() {
        return insertStats.getRecordCount() + updateStats.getRecordCount() + deleteStats.getRecordCount();
    }

    /** 总耗时 (ms) */
    public long getTotalMs() {
        return insertStats.getTotalMs() + updateStats.getTotalMs() + deleteStats.getTotalMs();
    }

    /** 整体累计平均吞吐量 (records/sec) = 总记录数 × 1000 / 总耗时(ms) */
    public double getCumulativeQps() {
        long totalRecords = getTotalRecords();
        long totalMsVal = getTotalMs();
        if (totalRecords == 0 || totalMsVal == 0) return 0;
        return totalRecords * 1000.0 / totalMsVal;
    }

    /** 整体单次吞吐量 = 三种操作各自单次QPS之和 */
    public double getInstantQps() {
        return insertStats.getInstantQps() + updateStats.getInstantQps() + deleteStats.getInstantQps();
    }

    /** 重置所有统计 */
    public void reset() {
        insertStats.reset();
        updateStats.reset();
        deleteStats.reset();
    }

    // ==================== 格式化输出 ====================

    /** 以可读字符串返回所有统计 */
    public String toPrettyString() {
        StringBuilder sb = new StringBuilder(512);
        sb.append("=== DB Performance Stats ===\n");

        appendOpStats(sb, "INSERT", insertStats);
        appendOpStats(sb, "UPDATE", updateStats);
        appendOpStats(sb, "DELETE", deleteStats);

        sb.append("\n=== Cumulative ===\n");
        sb.append(String.format("总调用: %d, 总记录: %d, 总耗时: %dms, 累计QPS: %.1f/s",
                getTotalCalls(), getTotalRecords(), getTotalMs(), getCumulativeQps()));

        return sb.toString();
    }

    private static void appendOpStats(StringBuilder sb, String name, OpStats stats) {
        sb.append(String.format(
                "\n[%s] 调用: %d, 记录: %d, 累计QPS: %.1f/s, 单次QPS: %.1f/s\n"
                        + "  总耗时: %dms, 平均: %dms, 最大: %dms, 最小: %dms\n",
                name,
                stats.getCallCount(),
                stats.getRecordCount(),
                stats.getCumulativeQps(),
                stats.getInstantQps(),
                stats.getTotalMs(),
                stats.getAvgMs(),
                stats.getMaxMs(),
                stats.getMinMs()
        ));

        long[] boundaries = stats.getBoundaries();
        long[] histogram = stats.getHistogram();
        sb.append("  耗时分布: ");
        for (int i = 0; i < histogram.length; i++) {
            if (i > 0) sb.append(", ");
            if (i == 0) {
                sb.append(String.format("≤%dms=%d", boundaries[i], histogram[i]));
            } else if (i < boundaries.length) {
                sb.append(String.format("%d~%dms=%d", boundaries[i - 1], boundaries[i], histogram[i]));
            } else {
                sb.append(String.format(">%dms=%d", boundaries[i - 1], histogram[i]));
            }
        }
        sb.append('\n');

        sb.append(String.format("  最近一次: %dms, %d 条", stats.getLastMs(), stats.getLastRecords()));
    }

    // ==================== 内部类：单操作类型统计 ====================

    /**
     * 单个操作类型的性能统计，所有更新操作线程安全。
     */
    public static class OpStats {

        /** 累计调用次数 */
        private final AtomicLong callCount = new AtomicLong();
        /** 累计记录条数 */
        private final AtomicLong recordCount = new AtomicLong();
        /** 累计总耗时 (ms) */
        private final AtomicLong totalMs = new AtomicLong();
        /** 单次最高耗时 (ms)，无记录时为 0 */
        private final AtomicLong maxMs = new AtomicLong(0);
        /** 单次最低耗时 (ms)，无记录时为 0 */
        private final AtomicLong minMs = new AtomicLong(0);

        /** 最近一次操作耗时 (ms) */
        private volatile long lastMs;
        /** 最近一次操作记录条数 */
        private volatile int lastRecords;

        /** 耗时分布直方图的边界（升序），边界数量 + 1 = 桶数 */
        private final long[] boundaries;
        /** 直方图各桶计数，长度为 boundaries.length + 1 */
        private final AtomicLongArray histogram;

        /**
         * @param boundaries 耗时边界（升序），例如 {50, 500, 1000, 5000}
         *                   产生 5 个桶：≤50 / 50~500 / 500~1000 / 1000~5000 / &gt;5000
         */
        public OpStats(long[] boundaries) {
            this.boundaries = boundaries.clone();
            this.histogram = new AtomicLongArray(boundaries.length + 1);
        }

        /**
         * 记录一次成功操作。
         *
         * @param elapsedMs 本次操作耗时 (ms)
         * @param records   本次操作涉及的记录条数
         */
        public void record(long elapsedMs, int records) {
            callCount.incrementAndGet();
            recordCount.addAndGet(records);
            totalMs.addAndGet(elapsedMs);

            // 更新 max（CAS 循环）
            long curMax;
            do {
                curMax = maxMs.get();
                if (elapsedMs <= curMax) break;
            } while (!maxMs.compareAndSet(curMax, elapsedMs));

            // 更新 min（CAS 循环）：以 0 当作"尚未初始化"哨兵——首次记录(curMin==0)必须写入，
            // 之后只有更小的耗时才替换；否则 min 会永远停在初始值 0
            long curMin;
            do {
                curMin = minMs.get();
                if (curMin != 0 && elapsedMs >= curMin) break;
            } while (!minMs.compareAndSet(curMin, elapsedMs));

            // 最近一次
            this.lastMs = elapsedMs;
            this.lastRecords = records;

            // 直方图：找到 elapsedMs 对应的桶
            int bucket = boundaries.length; // 默认：最后一个桶（超出所有边界）
            for (int i = 0; i < boundaries.length; i++) {
                if (elapsedMs <= boundaries[i]) {
                    bucket = i;
                    break;
                }
            }
            histogram.incrementAndGet(bucket);
        }

        // ===== Getters =====

        public long getCallCount()   { return callCount.get(); }
        public long getRecordCount() { return recordCount.get(); }
        public long getTotalMs()     { return totalMs.get(); }
        public long getMaxMs()       { return maxMs.get(); }
        public long getMinMs()       { return minMs.get(); }
        public long getLastMs()      { return lastMs; }
        public int  getLastRecords() { return lastRecords; }

        /** 平均耗时；无调用时返回 0 */
        public long getAvgMs() {
            long cnt = callCount.get();
            return cnt == 0 ? 0 : totalMs.get() / cnt;
        }

        /**
         * 累计平均吞吐量 (records/sec) = 累计记录总数 × 1000 / 累计总耗时(ms)。
         * 无记录时返回 0。
         */
        public double getCumulativeQps() {
            long totalRecords = recordCount.get();
            long totalMsVal = totalMs.get();
            if (totalRecords == 0 || totalMsVal == 0) return 0;
            return totalRecords * 1000.0 / totalMsVal;
        }

        /**
         * 单次吞吐量 (records/sec)，基于最近一次操作的记录数和耗时推算。
         * = lastRecords × 1000 / lastMs。无记录时返回 0。
         */
        public double getInstantQps() {
            long last = lastMs;
            return last > 0 ? (lastRecords * 1000.0) / last : 0;
        }

        /** 返回耗时边界的副本 */
        public long[] getBoundaries() { return boundaries.clone(); }

        /** 返回直方图计数的副本 */
        public long[] getHistogram() {
            long[] result = new long[histogram.length()];
            for (int i = 0; i < histogram.length(); i++) {
                result[i] = histogram.get(i);
            }
            return result;
        }

        /** 重置所有统计 */
        public void reset() {
            callCount.set(0);
            recordCount.set(0);
            totalMs.set(0);
            maxMs.set(0);
            minMs.set(0);
            lastMs = 0;
            lastRecords = 0;
            for (int i = 0; i < histogram.length(); i++) {
                histogram.set(i, 0);
            }
        }

        /** 获取统计快照（不可变副本，适合外部报告） */
        public OpStatsSnapshot snapshot() {
            return new OpStatsSnapshot(
                    callCount.get(), recordCount.get(), totalMs.get(),
                    getMaxMs(), getMinMs(), getAvgMs(), lastMs, lastRecords,
                    boundaries.clone(), getHistogram(),
                    getCumulativeQps(), getInstantQps()
            );
        }
    }

    // ==================== 不可变快照 ====================

    /**
     * OpStats 的不可变快照，可在任意时刻获取并安全传递。
     */
    public record OpStatsSnapshot(
            long callCount,
            long recordCount,
            long totalMs,
            long maxMs,
            long minMs,
            long avgMs,
            long lastMs,
            int  lastRecords,
            long[] boundaries,
            long[] histogram,
            double cumulativeQps,
            double instantQps
    ) {}
}
