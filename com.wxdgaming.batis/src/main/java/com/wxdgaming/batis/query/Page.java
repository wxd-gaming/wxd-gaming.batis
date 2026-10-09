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

    /**
     * 总页数：向上取整（{@code (total + size - 1) / size}），size 为 0 时返回 0 防除零。
     * <p>size 允许为 0 仅出现在无数据构造的边界页，正常分页 size &gt;= 1（由 {@link Pageable} 保证）。</p>
     */
    public int getTotalPages() {
        return size == 0 ? 0 : (int) ((total + size - 1) / size);
    }

    /** 是否存在下一页：当前页码严格小于总页数 */
    public boolean hasNext() {
        return page < getTotalPages();
    }

    public boolean isEmpty() {
        return content.isEmpty();
    }

}
