package com.wxdgaming.batis.sql.query;

import com.wxdgaming.batis.exception.TooManyResultsException;
import com.wxdgaming.batis.model.TableInfo;
import com.wxdgaming.batis.parser.EntityParser;
import com.wxdgaming.batis.query.Criteria;
import com.wxdgaming.batis.query.Page;
import com.wxdgaming.batis.query.Pageable;
import com.wxdgaming.batis.query.Sort;
import com.wxdgaming.batis.sql.BeanToSelectHandler;
import com.wxdgaming.batis.sql.JdbcTemplate;
import com.wxdgaming.batis.sql.QueryRunner;
import com.wxdgaming.batis.sql.ResultSetHandlers;

import java.util.List;

/**
 * 类型安全的查询 DSL（一次性构建器，非线程安全）
 * <p>用法：</p>
 * <pre>{@code
 * List<Order> paid = template.select(Order.class)
 *         .where(Criteria.where("status").eq("PAID"))
 *         .orderBy(Sort.desc("createTime"))
 *         .page(Pageable.of(1, 20))
 *         .list();
 * Page<Order> p = template.select(Order.class).where(cond).pageResult(Pageable.of(2, 20));
 * }</pre>
 * <p>每次终端操作（list/one/count/pageResult）独立创建 QueryRunner 执行；
 * 若当前线程处于事务中会自动复用事务连接（同批可见性）。</p>
 * <p>参数顺序不变式由 {@link SelectSqlBuilder} 单点保证：
 * {@code [WHERE 参数..., LIMIT, OFFSET]}。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public final class SelectQuery<T> implements SelectSqlBuilder.SelectQuerySpec {

    private final JdbcTemplate template;
    private final Class<T> entityClass;
    private Criteria where;
    private List<String> columns;
    private Sort sort;
    private Integer limit;
    private Integer offset;

    public SelectQuery(JdbcTemplate template, Class<T> entityClass) {
        this.template = template;
        this.entityClass = entityClass;
    }

    // ===== 构建 =====

    /** 追加条件（与已有条件 AND） */
    public SelectQuery<T> where(Criteria criteria) {
        this.where = this.where == null ? criteria : Criteria.allOf(this.where, criteria);
        return this;
    }

    /** 追加条件（与已有条件 AND） */
    public SelectQuery<T> and(Criteria criteria) {
        return where(criteria);
    }

    /** 追加条件（与已有条件 OR，注意优先级：or 组合会把已有条件整体作为左值） */
    public SelectQuery<T> or(Criteria criteria) {
        this.where = this.where == null ? criteria : Criteria.anyOf(this.where, criteria);
        return this;
    }

    /** 指定投影列（数据库列名或 Java 字段名），不指定时为 * */
    public SelectQuery<T> columns(String... cols) {
        this.columns = List.of(cols);
        return this;
    }

    public SelectQuery<T> orderBy(Sort s) {
        this.sort = s;
        return this;
    }

    public SelectQuery<T> limit(int limit) {
        this.limit = limit;
        return this;
    }

    public SelectQuery<T> offset(int offset) {
        this.offset = offset;
        return this;
    }

    /** 设置分页（含排序，若 Pageable 带排序则覆盖 orderBy） */
    public SelectQuery<T> page(Pageable pageable) {
        this.limit = pageable.getSize();
        this.offset = pageable.getOffset();
        if (!pageable.getSort().isEmpty()) {
            this.sort = pageable.getSort();
        }
        return this;
    }

    // ===== 终端操作 =====

    /** 查询列表 */
    public List<T> list() {
        SelectSqlBuilder.Plan plan = buildPlan(false, null, null);
        try (QueryRunner qr = new QueryRunner(template)) {
            return qr.query(plan.sql(), plan.params(), ResultSetHandlers.beanList(template, entityClass));
        }
    }

    /**
     * 查询唯一记录；0 条返回 null，命中多于 1 条抛 {@link TooManyResultsException}
     */
    public T one() {
        SelectSqlBuilder.Plan plan = buildPlan(false, 2, null);
        try (QueryRunner qr = new QueryRunner(template)) {
            List<T> list = qr.query(plan.sql(), plan.params(), ResultSetHandlers.beanList(template, entityClass));
            if (list.isEmpty()) return null;
            if (list.size() > 1) {
                throw new TooManyResultsException("期望唯一结果但命中多条: " + entityClass.getSimpleName()
                        + ", sql=" + plan.sql());
            }
            return list.getFirst();
        }
    }

    /** 查询第一条（自动 LIMIT 1） */
    public T first() {
        SelectSqlBuilder.Plan plan = buildPlan(false, 1, null);
        try (QueryRunner qr = new QueryRunner(template)) {
            List<T> list = qr.query(plan.sql(), plan.params(), ResultSetHandlers.beanList(template, entityClass));
            return list.isEmpty() ? null : list.getFirst();
        }
    }

    /** 条件计数（忽略排序与分页） */
    public long count() {
        SelectSqlBuilder.Plan plan = buildPlan(true, null, null);
        try (QueryRunner qr = new QueryRunner(template)) {
            Object cell = qr.query(plan.sql(), plan.params(), ResultSetHandlers.firstCell());
            return cell instanceof Number n ? n.longValue() : 0L;
        }
    }

    /** 是否存在匹配记录 */
    public boolean exists() {
        return count() > 0;
    }

    /** 分页查询（COUNT + LIMIT 两条语句） */
    public Page<T> pageResult(Pageable pageable) {
        page(pageable);
        long total = count();
        List<T> content = list();
        return new Page<>(content, total, pageable.getPage(), pageable.getSize(), pageable.getSort());
    }

    /** 使用先前 {@link #page(Pageable)} 设置的分页执行分页查询 */
    public Page<T> pageResult() {
        int size = limit == null ? 10 : limit;
        int off = offset == null ? 0 : offset;
        long total = count();
        List<T> content = list();
        return new Page<>(content, total, off / size + 1, size, sort);
    }

    /** 渲染 SQL（不执行），供单测与拦截器使用 */
    public String toSql() {
        return buildPlan(false, null, null).sql();
    }

    /** 渲染参数序列（不执行），顺序与 SQL 占位符一致 */
    public List<Object> toParams() {
        return List.of(buildPlan(false, null, null).params());
    }

    public JdbcTemplate getTemplate() {
        return template;
    }

    public Class<T> getEntityClass() {
        return entityClass;
    }

    // ===== SelectQuerySpec 渲染视图 =====

    @Override
    public Criteria where() {
        return where;
    }

    @Override
    public List<String> columns() {
        return columns;
    }

    @Override
    public Sort sort() {
        return sort;
    }

    @Override
    public Integer limit() {
        return limit;
    }

    @Override
    public Integer offset() {
        return offset;
    }

    // ===== 内部 =====

    /** 以当前规格为底，覆盖 limit/offset（overrideLimit 为 null 表示沿用当前值） */
    private SelectSqlBuilder.Plan buildPlan(boolean countOnly, Integer overrideLimit, Integer overrideOffset) {
        BeanToSelectHandler handler = template.getBeanToSelectHandler();
        if (handler == null) {
            throw new UnsupportedOperationException(
                    "未注册 beanToSelectHandler，请调用 JdbcTemplate#setBeanToSelectHandler()");
        }
        TableInfo table = EntityParser.parse(entityClass);
        Integer effLimit = overrideLimit != null ? overrideLimit : limit;
        Integer effOffset = overrideOffset != null ? overrideOffset : offset;
        Spec view = new Spec(where, columns, sort, countOnly ? null : effLimit, countOnly ? null : effOffset);
        return countOnly
                ? SelectSqlBuilder.buildCount(handler, table, view)
                : SelectSqlBuilder.build(handler, table, view);
    }

    /** 不可变渲染规格 */
    private record Spec(Criteria where, List<String> columns, Sort sort, Integer limit, Integer offset)
            implements SelectSqlBuilder.SelectQuerySpec {
    }

}
