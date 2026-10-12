package com.wxdgaming.batis.sql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 安全批量操作结果，包含成功/失败明细。
 * <p>当批量操作因部分脏数据失败、降级为逐条执行后，汇总为此结果对象。</p>
 * <p>为什么批量不抛异常而单条抛：批量执行的真实诉求通常是"尽可能写进去，
 * 把失败的那几条交给我处理"，一条脏数据不该拖垮整批；代价是调用方必须显式看
 * {@link #hasFailures()}，所以本类把"有没有失败"做成一眼能问出来的对象。</p>
 * <p>读写分离的构造方式（{@link #recordSuccess()} / {@link #recordFailure} 只包内可见）
 * 是有意的：结果只能由同包的执行器（BeanRunner）在<b>执行期间</b>写入，
 * 业务代码拿到之后只能读。否则统计数与明细可能被外部改乱，
 * 而"降级分组之间存在部分成功"这类语义就再也无法信任。</p>
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
    /**
     * 实际影响行数合计
     * <p>与"成功条数"不是一个概念，两者差额本身就是最有用的信号：UPDATE 一条库里不存在的行
     * 会"语句执行成功但影响 0 行"。由执行器把 {@code executeBatch} 的逐条行数累加进来。</p>
     */
    private long affectedRows;
    /** 失败条数 */
    private int errorCount;
    /** 失败明细 */
    private final List<FailureDetail> failures = new ArrayList<>();

    /**
     * 用原始请求列表构造结果对象。
     * <p>传入的列表按引用保存，因此后续对入参列表的修改会反映到
     * {@link #getRequestList()} 与 {@link #getTotalCount()}；
     * 执行期间不应改动原列表。null 视作空列表，让"没数据"与"没结果"不必两套判断。</p>
     *
     * @param requestList 本次批量请求的实体列表，可为 null
     */
    public BatchResult(List<T> requestList) {
        this.requestList = requestList != null ? requestList : Collections.emptyList();
    }

    // ==================== 包内写入方法（BeanRunner 调用） ====================

    /** 记一条成功（单条写入固定影响 1 行）；由执行器在每条落库后调用，外部无从伪造 */
    void recordSuccess() {
        recordSuccess(1);
    }

    /**
     * 记一条成功并累加实际影响行数。
     * <p>{@code affected} 非正时按 0 计：{@code Statement.SUCCESS_NO_INFO(-2)} 表示"驱动没给出行数"，
     * 不是"影响了负数行"；而 UPDATE 影响 0 行是真实结果，必须如实体现在汇总里。</p>
     *
     * @param affected 该条语句影响的行数
     */
    void recordSuccess(int affected) {
        successCount++;
        if (affected > 0) {
            affectedRows += affected;
        }
    }

    /**
     * 记一条失败。
     * <p>参数里的 index 必须是元素在<em>原始列表</em>中的下标（分组执行时要加回组起点），
     * 否则调用方按这个下标回查会拿到另一条数据。</p>
     *
     * @param index 在原始请求列表中的下标
     * @param bean  出错的数据
     * @param cause 导致失败的原始异常
     */
    void recordFailure(int index, T bean, Exception cause) {
        errorCount++;
        failures.add(new FailureDetail(index, bean, cause));
    }

    // ==================== 公开查询方法 ====================

    /** 是否存在失败条目；批量方法不抛异常，这是唯一的判定入口 */
    public boolean hasFailures() {
        return errorCount > 0;
    }

    /** 请求总条数（以原始列表长度为准，不等于成功数加失败数的情形只可能是执行中途放弃） */
    public int getTotalCount() {
        return requestList.size();
    }

    /** 原始请求列表（同一引用，非拷贝） */
    public List<T> getRequestList() {
        return requestList;
    }

    /** 成功写入的条数 */
    public int getSuccessCount() {
        return successCount;
    }

    /**
     * 实际影响行数合计。
     * <p>典型用法是看它与 {@link #getSuccessCount()} 的差额：更新一条库里不存在的行时
     * "成功条数 +1、影响行数 +0"，只统计成功条数会把这种情况报成"全部更新成功"。</p>
     */
    public long getAffectedRows() {
        return affectedRows;
    }

    /** 失败的条数，与 {@link #getFailures()} 大小一致 */
    public int getErrorCount() {
        return errorCount;
    }

    /** 失败明细列表（不可修改） */
    public List<FailureDetail> getFailures() {
        return Collections.unmodifiableList(failures);
    }

    /** 摘要形式，只报总数与成败计数；明细请遍历 {@link #getFailures()}，避免日志被堆满 */
    @Override
    public String toString() {
        return String.format("BatchResult{total=%d, success=%d, affected=%d, error=%d}",
                getTotalCount(), successCount, affectedRows, errorCount);
    }

    // ==================== 内部类 ====================

    /**
     * 单条失败明细，包含在原列表中的索引、原始数据和完整异常对象。
     * <p>调用方可自行决定：记录日志、发送告警、人工修复、或提取失败数据重试。</p>
     * <p>这里刻意保留完整异常对象而不是消息字符串：重试策略需要判断异常是否瞬时
     * （死锁与唯一键冲突的处置完全不同），丢了 cause 就只能靠字符串猜。</p>
     */
    public static class FailureDetail {
        /** 在原请求列表中的索引位置（0-based） */
        private final int index;
        /** 出错的数据对象 */
        private final Object bean;
        /** 完整异常对象 */
        private final Exception cause;

        /** 仅由外层 {@link BatchResult#recordFailure} 创建，外部无法构造不一致的明细 */
        FailureDetail(int index, Object bean, Exception cause) {
            this.index = index;
            this.bean = bean;
            this.cause = cause;
        }

        /** 在原始请求列表中的下标，可直接用于 {@link #getRequestList()} 回查 */
        public int getIndex() { return index; }
        /** 出错的数据本身（引用，未拷贝），修复后可原样重新提交 */
        public Object getBean() { return bean; }
        /** 完整异常对象，用于判断是否值得重试 */
        public Exception getCause() { return cause; }

        /** 单条明细的一行摘要，便于逐条打日志 */
        @Override
        public String toString() {
            return String.format("[%d] %s → %s",
                    index, bean,
                    cause != null ? cause.getMessage() : "未知错误");
        }
    }
}
