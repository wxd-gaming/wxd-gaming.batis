package com.wxdgaming.batis.sql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 安全批量操作结果，包含成功/失败明细。
 * <p>当批量操作因部分脏数据失败、降级为逐条执行后，汇总为此结果对象。</p>
 *
 * @param <T> 实体类型
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-08-07
 */
public class BatchResult<T> {

    /** 原始请求列表（引用，非拷贝，调用方可对照索引定位问题数据） */
    private final List<T> requestList;
    /** 成功条数 */
    private int successCount;
    /** 失败条数 */
    private int errorCount;
    /** 失败明细 */
    private final List<FailureDetail> failures = new ArrayList<>();

    public BatchResult(List<T> requestList) {
        this.requestList = requestList != null ? requestList : Collections.emptyList();
    }

    // ==================== 包内写入方法（BeanRunner 调用） ====================

    void recordSuccess() {
        successCount++;
    }

    void recordFailure(int index, T bean, Exception cause) {
        errorCount++;
        failures.add(new FailureDetail(index, bean, cause));
    }

    // ==================== 公开查询方法 ====================

    public boolean hasFailures() {
        return errorCount > 0;
    }

    public int getTotalCount() {
        return requestList.size();
    }

    public List<T> getRequestList() {
        return requestList;
    }

    public int getSuccessCount() {
        return successCount;
    }

    public int getErrorCount() {
        return errorCount;
    }

    /** 失败明细列表（不可修改） */
    public List<FailureDetail> getFailures() {
        return Collections.unmodifiableList(failures);
    }

    @Override
    public String toString() {
        return String.format("BatchResult{total=%d, success=%d, error=%d}",
                getTotalCount(), successCount, errorCount);
    }

    // ==================== 内部类 ====================

    /**
     * 单条失败明细，包含在原列表中的索引、原始数据和完整异常对象。
     * <p>调用方可自行决定：记录日志、发送告警、人工修复、或提取失败数据重试。</p>
     */
    public static class FailureDetail {
        /** 在原请求列表中的索引位置（0-based） */
        private final int index;
        /** 出错的数据对象 */
        private final Object bean;
        /** 完整异常对象 */
        private final Exception cause;

        FailureDetail(int index, Object bean, Exception cause) {
            this.index = index;
            this.bean = bean;
            this.cause = cause;
        }

        public int getIndex() { return index; }
        public Object getBean() { return bean; }
        public Exception getCause() { return cause; }

        @Override
        public String toString() {
            return String.format("[%d] %s → %s",
                    index, bean,
                    cause != null ? cause.getMessage() : "未知错误");
        }
    }
}
