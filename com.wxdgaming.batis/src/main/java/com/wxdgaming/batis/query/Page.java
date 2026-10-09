package com.wxdgaming.batis.query;

import java.util.List;

/**
 * 分页结果
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public final class Page<T> {

    private final List<T> content;
    private final long total;
    private final int page;
    private final int size;
    private final Sort sort;

    public Page(List<T> content, long total, int page, int size, Sort sort) {
        this.content = content;
        this.total = total;
        this.page = page;
        this.size = size;
        this.sort = sort;
    }

    public List<T> getContent() {
        return content;
    }

    public long getTotal() {
        return total;
    }

    public int getPage() {
        return page;
    }

    public int getSize() {
        return size;
    }

    public Sort getSort() {
        return sort;
    }

    /** 总页数 */
    public int getTotalPages() {
        return size == 0 ? 0 : (int) ((total + size - 1) / size);
    }

    public boolean hasNext() {
        return page < getTotalPages();
    }

    public boolean isEmpty() {
        return content.isEmpty();
    }

}
