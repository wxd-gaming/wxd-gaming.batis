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
        if (page < 1) throw new IllegalArgumentException("页码从 1 开始，page=" + page);
        if (size < 1) throw new IllegalArgumentException("每页大小必须 >= 1，size=" + size);
        this.page = page;
        this.size = size;
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

    /** 起始偏移量 */
    public int getOffset() {
        return (page - 1) * size;
    }

    public Sort getSort() {
        return sort;
    }

    /** 换页保持排序 */
    public Pageable withPage(int newPage) {
        return new Pageable(newPage, size, sort);
    }

}
