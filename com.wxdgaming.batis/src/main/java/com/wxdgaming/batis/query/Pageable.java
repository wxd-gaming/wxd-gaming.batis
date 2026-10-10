package com.wxdgaming.batis.query;

/**
 * 分页请求（1-based 页码，不可变）
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public final class Pageable {

    private final int page;
    private final int size;
    private final Sort sort;

    private Pageable(int page, int size, Sort sort) {
        // 1-based 页码：面向业务的"第几页"语义，页 0 无意义故拒绝；size 同理必须 >= 1
        if (page < 1) throw new IllegalArgumentException("页码从 1 开始，page=" + page);
        if (size < 1) throw new IllegalArgumentException("每页大小必须 >= 1，size=" + size);
        this.page = page;
        this.size = size;
        // 排序为空时退化为无排序的空 Sort，避免下游对 null 排序做特判
        this.sort = sort == null ? Sort.by() : sort;
    }

    public static Pageable of(int page, int size) {
        return new Pageable(page, size, null);
    }

    public static Pageable of(int page, int size, Sort sort) {
        return new Pageable(page, size, sort);
    }

    public int getPage() {
        return page;
    }

    public int getSize() {
        return size;
    }

    /**
     * 起始偏移量。
     * <p>page/size 都是 int，(page-1)*size 用 long 计算：两个 int 相乘可能溢出成负数，
     * 溢出后 OFFSET 子句会拿到负值直接报错或返回错误页。超出 int 范围时快速失败抛异常，
     * 而不是静默溢出。</p>
     */
    public int getOffset() {
        long offset = (long) (page - 1) * size;
        if (offset > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "偏移量超出 int 范围: page=" + page + ", size=" + size + ", offset=" + offset);
        }
        return (int) offset;
    }

    public Sort getSort() {
        return sort;
    }

    /** 换页保持排序 */
    public Pageable withPage(int newPage) {
        return new Pageable(newPage, size, sort);
    }

}
