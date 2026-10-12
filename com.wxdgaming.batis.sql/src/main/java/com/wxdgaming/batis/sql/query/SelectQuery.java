package com.wxdgaming.batis.sql.query;

import com.wxdgaming.batis.exception.TooManyResultsException;
import com.wxdgaming.batis.exception.TypeMismatchDataAccessException;
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

import java.util.Arrays;
import java.util.Collections;
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
 * <p>
 * <b>为什么是"一次性"的</b>：构建方法是链式改内部字段（非拷贝返回），所以一个 SelectQuery
 * 只能代表一个查询规格，不能当模板复用、不能跨线程共享、也不该存成字段。
 * 反过来，同一个实例上多次调用终端操作是安全的（每次都重新渲染），
 * {@link #pageResult(Pageable)} 正是靠这点在一次调用里跑完 count + list。
 * </p>
 * <p>
 * 本类不做任何缓存，每次终端操作现解析实体、现展平条件——数据量小、代码直，
 * 也避免"条件对象被跨查询复用"这类串味 bug。
 * </p>
 * <p>每次终端操作独立创建 QueryRunner 执行；
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
    /**
     * limit/offset 用包装类型：null 表示"没设过分页"，必须与"offset = 0"区分开，
     * 否则方言实现会给不分页的查询也硬加一段 LIMIT/OFFSET 子句。
     */
    private Integer limit;
    private Integer offset;

    public SelectQuery(JdbcTemplate template, Class<T> entityClass) {
        this.template = template;
        this.entityClass = entityClass;
    }

    // ===== 构建 =====

    /**
     * 追加条件（与已有条件 AND）
     * <p>首次调用直接赋值而不是包一层 allOf：少一层无意义的嵌套，渲染出的 SQL 更贴近手写。</p>
     */
    public SelectQuery<T> where(Criteria criteria) {
        this.where = this.where == null ? criteria : Criteria.allOf(this.where, criteria);
        return this;
    }

    /** 追加条件（与已有条件 AND），与 {@link #where} 等价，只为链式可读性 */
    public SelectQuery<T> and(Criteria criteria) {
        return where(criteria);
    }

    /**
     * 追加条件（与已有条件 OR）
     * <p>注意左结合：{@code a.or(b).and(c)} 是 {@code (a OR b) AND c}，不是 {@code a OR (b AND c)}。
     * 想要后者请显式 {@code Criteria.anyOf(...)} 包一层——渲染器会给 OR 子节点补括号，
     * 但优先级本身仍由这棵树的形状决定。</p>
     */
    public SelectQuery<T> or(Criteria criteria) {
        this.where = this.where == null ? criteria : Criteria.anyOf(this.where, criteria);
        return this;
    }

    /** 指定投影列（数据库列名或 Java 字段名），不指定时为 *；未知列名当场抛 UnknownColumnException */
    public SelectQuery<T> columns(String... cols) {
        this.columns = List.of(cols);
        return this;
    }

    /** 设置排序（覆盖 Pageable 里带的排序，除非之后再调 {@link #page}） */
    public SelectQuery<T> orderBy(Sort s) {
        this.sort = s;
        return this;
    }

    /** 限制返回条数；负数/0 由分页子句按"不限制"处理 */
    public SelectQuery<T> limit(int limit) {
        this.limit = limit;
        return this;
    }

    /** 跳过条数（0 与"未设置"在结果上等价，但 null 会让方言实现跳过整个 OFFSET 子句） */
    public SelectQuery<T> offset(int offset) {
        this.offset = offset;
        return this;
    }

    /**
     * 设置分页（含排序，若 Pageable 带排序则覆盖 orderBy）
     * <p>排序会被覆盖是刻意的：分页必须有一个稳定次序，否则同一条记录可能出现在两页、
     * 另一条永远不出现。Pageable 自带排序时应以它为准。</p>
     */
    public SelectQuery<T> page(Pageable pageable) {
        this.limit = pageable.getSize();
        this.offset = pageable.getOffset();
        if (!pageable.getSort().isEmpty()) {
            this.sort = pageable.getSort();
        }
        return this;
    }

    // ===== 终端操作 =====

    /** 查询列表；无匹配时返回空 List（不返回 null） */
    public List<T> list() {
        SelectSqlBuilder.Plan plan = buildPlan(false, null, null);
        // 每次新建 Runner：Builder 生命周期可能跨越多次查询，不能占着连接；
        // try-with-resources 归还的只是池借出的连接，事务内的会被 Runner 自己识别为"不该归还"
        try (QueryRunner qr = new QueryRunner(template)) {
            return qr.query(plan.sql(), plan.params(), ResultSetHandlers.beanList(template, entityClass));
        }
    }

    /**
     * 查询唯一记录；0 条返回 null，命中多于 1 条抛 {@link TooManyResultsException}
     * <p>
     * 实现上是 {@code LIMIT 2} 而不是 LIMIT 1：取 2 条才能分辨"恰好 1 条"和"其实有好几条"。
     * 静默取第一条会把数据一致性问题藏起来（本该唯一的业务键重复了却毫无征兆），
     * 而这里多读一行代价恒定（最多 2 行）。
     * </p>
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

    /** 查询第一条（自动 LIMIT 1）；语义是"随便给一条"，需要确定性请配合 orderBy */
    public T first() {
        SelectSqlBuilder.Plan plan = buildPlan(false, 1, null);
        try (QueryRunner qr = new QueryRunner(template)) {
            List<T> list = qr.query(plan.sql(), plan.params(), ResultSetHandlers.beanList(template, entityClass));
            return list.isEmpty() ? null : list.getFirst();
        }
    }

    /**
     * 条件计数（忽略排序与分页）
     * <p>
     * COUNT 必然返回一行一列，因此读不到数字只可能是方言/处理器出了问题
     * （例如把计数报成字符串）。这种情况抛异常而不是静默返回 0：
     * 0 会让 {@link #exists()} 误报 false、分页的 total 归零，业务表现为"数据明明在却查不到"，
     * 而且没有任何日志线索。
     * </p>
     *
     * @throws TypeMismatchDataAccessException COUNT 结果不是数值类型
     */
    public long count() {
        SelectSqlBuilder.Plan plan = buildPlan(true, null, null);
        try (QueryRunner qr = new QueryRunner(template)) {
            Object cell = qr.query(plan.sql(), plan.params(), ResultSetHandlers.firstCell());
            if (cell instanceof Number n) {
                return n.longValue();
            }
            throw new TypeMismatchDataAccessException(
                    "COUNT 结果不是数值类型（实际为 "
                            + (cell == null ? "null" : cell.getClass().getName()) + "）: " + plan.sql());
        }
    }

    /**
     * 是否存在匹配记录
     * <p>当前实现走 COUNT(*)：语义正确但代价高于 {@code LIMIT 1} 探测，
     * 大表上的存在性判断建议用 {@code limit(1).list()}。</p>
     */
    public boolean exists() {
        return count() > 0;
    }

    /**
     * 分页查询（COUNT + LIMIT 两条语句）
     * <p>
     * 两条语句之间有窗口：并发写入会让 total 与 content 不吻合（总数算早了、页内容拿了晚了）。
     * 这是 offset 分页的固有代价，不是实现缺陷；需要严格一致请在事务里调用
     * （同一连接两次查询至少能看到同一批数据），或改用 keyset 分页。
     * </p>
     */
    public Page<T> pageResult(Pageable pageable) {
        page(pageable);
        // 顺序有讲究：先 count 再取页，页内容才是"数完之后"的状态
        long total = count();
        List<T> content = list();
        return new Page<>(content, total, pageable.getPage(), pageable.getSize(), pageable.getSort());
    }

    /**
     * 使用先前 {@link #page(Pageable)} 设置的分页执行分页查询
     * <p>页码由 offset/size 反推，所以只能得到"从 1 开始的整页页码"；
     * 手工 limit/offset 出来的非整页偏移，这里推出的页码没有业务含义。</p>
     */
    public Page<T> pageResult() {
        // limit 未设置或为 0/负数（limit() 的"不限制"语义）时回退默认页大小，避免 off/size 除零
        int size = limit == null || limit <= 0 ? 10 : limit;
        int off = offset == null || offset < 0 ? 0 : offset;
        long total = count();
        List<T> content = list();
        return new Page<>(content, total, off / size + 1, size, sort);
    }

    /** 渲染 SQL（不执行），供单测与拦截器使用；也是排查"这条 DSL 到底拼成了什么"的入口 */
    public String toSql() {
        return buildPlan(false, null, null).sql();
    }

    /**
     * 渲染参数序列（不执行），顺序与 SQL 占位符一致，可与 {@link #toSql()} 配对打印。
     * <p>用 {@code Arrays.asList} 而不是 {@code List.of}：{@code Criteria.raw(...)} 允许绑定 null
     * （片段由调用方书写，{@code IS NOT DISTINCT FROM ?} 这类写法是合法的），
     * 而 {@code List.of} 遇到 null 元素会抛无消息的 NPE，把"渲染参数"变成新的坑。</p>
     */
    public List<Object> toParams() {
        return Collections.unmodifiableList(Arrays.asList(buildPlan(false, null, null).params()));
    }

    public JdbcTemplate getTemplate() {
        return template;
    }

    public Class<T> getEntityClass() {
        return entityClass;
    }

    // ===== SelectQuerySpec 渲染视图 =====
    // 下面五个方法是"本构建器自身就是一种规格"的实现，渲染器只经这五个访问器读，不碰字段：
    // 这样 buildPlan 里换成临时 Spec 快照时，渲染路径完全一致。

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

    /**
     * 以当前规格为底，覆盖 limit/offset（overrideLimit 为 null 表示沿用当前值）
     * <p>
     * 为什么要造一个临时 {@link Spec} 而不是直接改字段再渲染：终端方法（one/first）要的 LIMIT
     * 与用户设置的 LIMIT 是两回事，改字段会污染构建器，导致随后再调 list() 用上被覆盖的值。
     * </p>
     * <p>countOnly 时把 limit/offset 强制置 null：计数不能被分页影响，
     * 同时列投影也无意义（COUNT 语句不渲染列）。</p>
     * <p>handler 缺失时抛 UnsupportedOperationException 而非 NPE：这是装配问题，
     * 报错要直接指出"少注入了哪个处理器"。</p>
     */
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

    /** 不可变渲染规格：一次渲染的输入快照，避免渲染期间构建器被继续修改 */
    private record Spec(Criteria where, List<String> columns, Sort sort, Integer limit, Integer offset)
            implements SelectSqlBuilder.SelectQuerySpec {
    }

}
