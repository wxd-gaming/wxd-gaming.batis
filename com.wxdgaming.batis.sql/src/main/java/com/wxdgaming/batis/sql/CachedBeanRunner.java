package com.wxdgaming.batis.sql;

import com.wxdgaming.batis.cache.CacheKeyBuilder;
import com.wxdgaming.batis.cache.EntityCache;
import com.wxdgaming.batis.cache.LocalEntityCache;
import com.wxdgaming.batis.exception.IllegalTransactionStateException;
import com.wxdgaming.batis.sql.query.SelectQuery;
import com.wxdgaming.batis.sql.tx.ConnectionHolder;
import com.wxdgaming.batis.sql.tx.TransactionSynchronization;
import com.wxdgaming.batis.sql.tx.TxContext;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 带缓存的 BeanRunner，支持 Write-Behind（延迟批刷）模式。
 *
 * <h3>缓存三防策略</h3>
 * <table>
 *   <tr><th>问题</th><th>方案</th></tr>
 *   <tr><td>缓存击穿（单 Key 并发）</td><td>getOrLoad 单飞加载，Caffeine 保证同一 Key 仅一个线程查 DB</td></tr>
 *   <tr><td>缓存雪崩（批量过期）</td><td>expireAfterWrite 固定过期，热 key 也会到期回源刷新，陈旧数据有界</td></tr>
 *   <tr><td>缓存穿透（删除后重查）</td><td>Tombstone 哨兵标记已删除但未刷盘的 Key</td></tr>
 * </table>
 *
 * <h3>写模式</h3>
 * <table>
 *   <tr><th>方法</th><th>immediate = true</th><th>immediate = false（默认）</th></tr>
 *   <tr><td>insertBean</td><td>写 DB + 写缓存</td><td>只写缓存 + 标记脏插入</td></tr>
 *   <tr><td>updateBean</td><td>写 DB + 更新缓存</td><td>更新缓存 + 标记脏更新</td></tr>
 *   <tr><td>deleteBean</td><td>删 DB + 删缓存</td><td>缓存写 Tombstone + 标记脏删除</td></tr>
 * </table>
 *
 * <h3>数据一致性保障（三重机制）</h3>
 * <ol>
 *   <li><b>手动刷盘</b>：{@link #flushAll()} / {@link #flush(Class)} / {@link #flush(Object)}</li>
 *   <li><b>淘汰兜底</b>：缓存条目过期时 onEvict 回调自动将脏数据刷到 DB</li>
 *   <li><b>失败恢复</b>：刷盘失败后脏数据放回脏表，下次 flush 或淘汰时重试</li>
 * </ol>
 *
 * <h3>事务边界（三条硬规则，每条都对应一类会丢数据或读脏数据的事故）</h3>
 * <ol>
 *   <li><b>事务内延迟写自动升级为立即写</b>（{@link FlushMode#TX_IMMEDIATE}，默认）：
 *       延迟写只落缓存不落 DB，而事务里的写必须当场可见、可回滚，否则外层回滚之后缓存里还留着
 *       "从未提交过的数据"，后续查询会读到幽灵行。同时把 key 记入事务写集，
 *       完成回调里非提交状态精确失效该 key 的缓存；</li>
 *   <li><b>事务内 {@link #flushAll()} 等于 JOIN 当前事务</b>：全有或全无。
 *       摘取脏数据用的是 remove，一旦回滚而快照不追回，这批数据既没进 DB 也不在脏表里——永久丢失；
 *       所以刷盘前登记回滚补偿，用 putIfAbsent 把快照放回；</li>
 *   <li><b>推荐姿势是 {@code executeAndFlush(runner, cb)}</b>：业务在事务里改缓存，
 *       刷盘安排在 afterCommit，既不阻塞事务又保证"提交成功才落库"。</li>
 * </ol>
 *
 * <h3>读写的不对称性（最容易踩的一点）</h3>
 * <p>
 * 延迟模式下写方法<em>不碰 DB</em>，所以返回值失去意义：{@code insertBean} 返回 null（拿不到自增主键）、
 * {@code updateBean}/{@code deleteBean} 返回 -1（没动过行数）。缓存里是新的、DB 里是旧的，
 * 这在进程内是一致的，但<em>其他进程/其他连接看不到</em>。因此：
 * 依赖自增 ID 的后续逻辑、跨实例读取、以及"写完立刻用 SQL 统计"都必须走 immediate 或先 flush。
 * </p>
 *
 * <h3>刷盘窗口</h3>
 * <p>
 * 脏表与缓存是两份独立状态：条目可能已因容量/过期从缓存消失但仍躺在脏表里；
 * 反过来缓存被淘汰时若该 key 还有脏数据，{@code handleEviction} 会当场补一次单条刷盘，
 * 兜住"还没来得及 flush 就被淘汰"的丢失窗口。
 * </p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * CachedBeanRunner runner = new CachedBeanRunner(jdbcTemplate);
 *
 * // 查询：缓存优先 + 单飞加载 + Tombstone 感知
 * User u = runner.queryById(User.class, 1L);
 *
 * // 延迟更新：频繁变动，等批量刷 DB
 * runner.updateBean(u);
 *
 * // 立即写入：关键数据同步刷 DB
 * runner.deleteBean(cheater, true);
 *
 * // 定时批量刷盘
 * FlushResult r = runner.flushAll();
 * }</pre>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-08-05
 */
public class CachedBeanRunner {

    private static final Logger log = LoggerFactory.getLogger(CachedBeanRunner.class);

    /**
     * Tombstone 哨兵，标记该 Key 已被逻辑删除但尚未刷到 DB。
     * <p>存在的意义：没有它，删除后缓存就是空位，紧随其后的 queryById 会回源 DB 把"已删的行"读回来，
     * 表现为"删了又活过来"（缓存穿透）。用引用相等（{@code ==}）判定，因此绝不能序列化或复用实例。</p>
     */
    private static final Object TOMBSTONE = new Object();

    @Getter private final JdbcTemplate jdbcTemplate;
    /**
     * 读缓存。与三张脏表是<em>两份独立状态</em>：条目可以从缓存消失而仍留在脏表里，
     * 这时脏数据靠 onEvict 回调兜底刷盘。
     */
    private final EntityCache<String, Object> cache;

    /**
     * 待 INSERT：key → bean。
     * <p>三张脏表互斥：一个 key 在同一时刻只应属于一张（由写方法里的 remove 维护）。
     * 合并规则决定了刷盘形态——脏插入后再更新仍留在插入桶（一条 INSERT 带走最新值），
     * 若错误地拆成 INSERT+UPDATE 就是两次写；脏插入后再删除直接抵消为一条 DELETE，
     * 若保留 INSERT 再 DELETE 则多半撞唯一索引。</p>
     */
    private final ConcurrentHashMap<String, Object> dirtyInserts = new ConcurrentHashMap<>();
    /** 待 UPDATE：key → bean（key 对应的行必须已在 DB 中存在，否则刷盘得到 0 行更新） */
    private final ConcurrentHashMap<String, Object> dirtyUpdates = new ConcurrentHashMap<>();
    /** 待 DELETE：key → bean（保留引用以提取主键参数：删除只需要 PK，但 PK 必须来自这个实例） */
    private final ConcurrentHashMap<String, Object> dirtyDeletes = new ConcurrentHashMap<>();
    /** 批量提交大小，从 JdbcTemplate 继承默认值，可随时修改；&lt;=0 表示不分组一次性提交 */
    @Setter @Getter private int batchSize;
    /**
     * 事务内延迟写策略（默认 {@link FlushMode#TX_IMMEDIATE}）。
     * <p>改成 TX_SUSPEND 会把事务内的延迟写变成硬错误，适合"这个业务不允许任何延迟写"的场景；
     * TX_JOIN 与 TX_IMMEDIATE 当前行为相同（都当场写），区别在语义意图。</p>
     */
    @Setter @Getter private FlushMode txFlushMode = FlushMode.TX_IMMEDIATE;
    /** 延迟插入且主键尚未生成（如 IDENTITY 策略）的实例 → 临时 Key 映射；主键为 null 时按 buildKey 会全实例碰撞，故按实例身份分配唯一 Key */
    private final Map<Object, String> pendingKeys =
            Collections.synchronizedMap(new IdentityHashMap<>());
    /** 临时 Key 序列号（进程内单调递增，只保证唯一，不保证与最终主键有任何对应关系） */
    private final AtomicLong pendingSeq = new AtomicLong();

    /** 用默认本地缓存（Caffeine）构建 */
    public CachedBeanRunner(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new LocalEntityCache<>());
    }

    /**
     * 用指定缓存构建。
     * <p>构造里注册 onEvict 回调是本类"不丢数据"的最后一道防线：
     * 只要条目还在缓存里，被淘汰时一定会尝试刷盘。因此传入的缓存实现必须真的回调 onEvict，
     * 换成一个不通知淘汰的实现等于默默取消这条防线。</p>
     */
    public CachedBeanRunner(JdbcTemplate jdbcTemplate, EntityCache<String, Object> cache) {
        this.jdbcTemplate = jdbcTemplate;
        this.cache = cache;
        this.batchSize = jdbcTemplate.getBatchSize();

        // 注册淘汰回调：缓存过期时自动将脏数据刷到 DB，防止数据丢失
        this.cache.onEvict(entry -> handleEviction(entry.getKey(), entry.getValue()));
    }

    // ==================== 查询 ====================

    /**
     * 按主键查询（唯一走缓存的读路径）。
     * <p>缓存命中 → 返回（Tombstone 则返回 null）；
     * 缓存未命中 → 单飞加载 DB → 回填缓存。</p>
     * <p>
     * 两段式获取是刻意的：先 {@code getIfPresent} 再 {@code getOrLoad}。
     * 原因是 Tombstone 也是"值"，直接进 getOrLoad 的加载函数会被当成有效结果反复缓存；
     * 前置检查能让"已标记删除"的 key 立刻短路返回 null，不去查 DB（否则就是删了又活过来）。
     * </p>
     * <p>
     * {@code getOrLoad} 的单飞（同一 key 只有一个线程查 DB，其余等待）解决的是缓存击穿：
     * 热点行过期瞬间如果有 100 个线程，没有单飞就是 100 条 SELECT。
     * 加载函数里借用的是<em>新</em> BeanRunner（独立连接），所以延迟写尚未刷盘时，
     * 本方法返回的是缓存里的新值而不是 DB 里的旧值——这正是"缓存优先"的语义。
     * </p>
     *
     * @param entityClass 实体类
     * @param id          主键值
     * @param <T>         实体类型
     * @return 实体对象，未找到或已标记删除时返回 null
     */
    @SuppressWarnings("unchecked")
    public <T> T queryById(Class<T> entityClass, Object id) {
        String key = CacheKeyBuilder.buildKey(entityClass, id);

        // 先尝试不触发加载直接获取：命中就省掉一次 loader 调用与锁
        Object cached = cache.getIfPresent(key);
        if (cached != null) {
            if (cached == TOMBSTONE) {
                log.debug("Tombstone 命中，返回 null: {}", key);
                return null;
            }
            log.debug("缓存命中: {}", key);
            return (T) cached;
        }

        // 缓存未命中，单飞加载
        Object result = cache.getOrLoad(key, k -> {
            log.debug("缓存未命中，单飞加载 DB: {}", key);
            try (BeanRunner runner = jdbcTemplate.beanRunner()) {
                return runner.queryById(entityClass, id);
            }
        });

        // 单飞路径同样要认 Tombstone：别的线程可能在我们加载的间隙把它标成已删除
        if (result == TOMBSTONE) {
            return null;
        }
        return (T) result;
    }

    // ==================== 插入 ====================

    /** 默认延迟插入（只写缓存，不写 DB）。<p>因此返回 null，拿不到自增主键；需要 ID 请用 immediate 或先 flush。</p> */
    public Number insertBean(Object bean) {
        return insertBean(bean, false);
    }

    /**
     * 插入实体。
     * <p>
     * 延迟分支里的"以 insert 为准"合并规则（清掉同 key 的 update/delete 标记）不是可有可无的优化：
     * 若留着 update 标记，刷盘会先发一条 DB 里根本不存在的行的 UPDATE（0 行生效，改动丢失）；
     * 若留着 delete 标记，则同一份数据既 INSERT 又 DELETE，白占自增序列还可能撞唯一索引。
     * </p>
     *
     * @param bean      实体对象
     * @param immediate true=立即写 DB + 缓存；false=只写缓存 + 标记脏插入
     * @return 自增主键（immediate 时从 DB 获取，延迟模式返回 null）
     */
    public Number insertBean(Object bean, boolean immediate) {
        String key = keyOf(bean);

        // 事务内按 txFlushMode 把延迟写升级成立即写：见类注释"事务边界"规则①
        if (immediate || upgradeDeferredInTx()) {
            try (BeanRunner runner = jdbcTemplate.beanRunner()) {
                Number genId = runner.insertBean(bean);
                // 主键已回写，临时 Key 可以退休，之后该实例统一用 table:pk
                releasePendingKey(bean);
                cache.put(key, bean);
                invalidateCacheOnRollback(key);
                log.debug("立即插入+缓存: {}", key);
                return genId;
            }
        } else {
            cache.put(key, bean);
            dirtyInserts.put(key, bean);
            // 以 insert 为准，清除之前的 update/delete 标记
            dirtyUpdates.remove(key);
            dirtyDeletes.remove(key);
            log.debug("延迟插入（仅缓存）: {}", key);
            return null;
        }
    }

    // ==================== 更新 ====================

    /** 默认延迟更新（只更新缓存，不写 DB）。<p>返回 -1 表示没有触碰 DB，不是"影响 -1 行"。</p> */
    public int updateBean(Object bean) {
        return updateBean(bean, false);
    }

    /**
     * 更新实体。
     * <p><b>缓存策略：更新缓存而非删除</b>，保证业务代码持有的 bean 引用仍然有效，
     * 且后续查询直接命中缓存，无需重新查 DB。</p>
     * <p>
     * 延迟分支里区分两种情况：该 key 还在脏插入桶 → 只更新脏插入版本（合并成一次 INSERT，
     * 否则会退化成"先插入再更新"两次写）；否则进脏更新桶。
     * 立即分支里 {@code rows == 0} 时<em>不</em>写缓存：那是"行不存在"，
     * 把不存在的行缓存进去等于凭空造出一条幽灵数据。
     * </p>
     *
     * @param bean      实体对象
     * @param immediate true=立即写 DB + 更新缓存；false=只更新缓存 + 标记脏更新
     * @return 影响行数（延迟模式返回 -1 表示未写 DB）
     */
    public int updateBean(Object bean, boolean immediate) {
        String key = keyOf(bean);

        if (immediate || upgradeDeferredInTx()) {
            try (BeanRunner runner = jdbcTemplate.beanRunner()) {
                int rows = runner.updateBean(bean);
                releasePendingKey(bean);
                if (rows > 0) {
                    cache.put(key, bean);
                }
                // 已经落库，两张待写表里的同 key 条目都不再需要
                dirtyUpdates.remove(key);
                dirtyInserts.remove(key);
                invalidateCacheOnRollback(key);
                log.debug("立即更新+刷新缓存: {}, rows={}", key, rows);
                return rows;
            }
        } else {
            cache.put(key, bean);
            if (!dirtyInserts.containsKey(key)) {
                dirtyUpdates.put(key, bean);
            } else {
                // 本身是待插入的 bean，直接更新脏插入版本即可
                dirtyInserts.put(key, bean);
            }
            // "先删后改"合并成"改"：留着删除标记会让刷盘变成 DELETE + UPDATE，行没了改动也丢了
            dirtyDeletes.remove(key);
            log.debug("延迟更新（仅缓存）: {}", key);
            return -1;
        }
    }

    // ==================== 删除 ====================

    /** 默认延迟删除（缓存标记删除，不写 DB）。<p>返回 -1 表示没有触碰 DB。</p> */
    public int deleteBean(Object bean) {
        return deleteBean(bean, false);
    }

    /**
     * 删除实体。
     * <p>
     * 延迟分支写 Tombstone 而不是直接 remove 缓存：留空位等于允许下一次查询回源 DB 把已删的行读回来。
     * 同时脏插入桶里的同 key 条目被抵消为一条 DELETE（本来还没落库，直接按删除处理），
     * 并释放临时 Key——删除只需要主键参数，而这条 bean 已经不需要再被"按临时 Key"找到了。
     * </p>
     *
     * @param bean      实体对象（需包含主键值）
     * @param immediate true=立即删 DB + 删缓存；false=缓存写 Tombstone + 标记脏删除
     * @return 影响行数（延迟模式返回 -1 表示未写 DB）
     */
    public int deleteBean(Object bean, boolean immediate) {
        String key = keyOf(bean);

        if (immediate || upgradeDeferredInTx()) {
            try (BeanRunner runner = jdbcTemplate.beanRunner()) {
                int rows = runner.deleteBean(bean);
                releasePendingKey(bean);
                // 立即删除不写 Tombstone：DB 已经是真相，回源本来就查不到
                cache.remove(key);
                dirtyInserts.remove(key);
                dirtyUpdates.remove(key);
                dirtyDeletes.remove(key);
                invalidateCacheOnRollback(key);
                log.debug("立即删除+删缓存: {}, rows={}", key, rows);
                return rows;
            }
        } else {
            cache.put(key, TOMBSTONE);
            dirtyDeletes.put(key, bean);
            dirtyInserts.remove(key);
            dirtyUpdates.remove(key);
            pendingKeys.remove(bean);
            log.debug("延迟删除（Tombstone）: {}", key);
            return -1;
        }
    }

    // ==================== 批量刷盘 ====================

    /** 刷全部脏数据到 DB（三张脏表全量摘取，按实体类型分组批量执行）。 */
    public FlushResult flushAll() {
        return doFlush(null);
    }

    /**
     * 刷指定实体类的脏数据到 DB。
     * <p>按 key 前缀过滤而不是按类型遍历：key 形如 {@code table:pk}（临时 key 也保留表名前缀），
     * 所以前缀匹配能同时覆盖正式与临时两种条目。</p>
     */
    public FlushResult flush(Class<?> entityClass) {
        return doFlush(CacheKeyBuilder.keyPrefix(entityClass));
    }

    /**
     * 强制刷单个实体到 DB。
     * <p>不关心当前是否标记脏，直接移除并执行 DB 操作。</p>
     * <p>
     * 三张脏表都尝试 remove，所以"同一 key 同时存在插入和删除标记"这种不该出现的状态也能被清干净
     * （顺序 INSERT → UPDATE → DELETE 执行）。事务内同样登记回滚补偿，
     * 与 {@link #doFlush} 保持一致：remove 了的必须能追回。
     * </p>
     */
    public FlushResult flush(Object bean) {
        String key = keyOf(bean);

        int insCount = 0, insAff = 0;
        int updCount = 0, updAff = 0;
        int delCount = 0, delAff = 0;

        Object insertBean = dirtyInserts.remove(key);
        Object updateBean = dirtyUpdates.remove(key);
        Object deleteBean = dirtyDeletes.remove(key);
        // 事务内：注册回滚补偿，防止"回滚 + 已 remove"导致数据丢失
        registerTxFlushCompensation(
                singleSnapshot(key, insertBean), singleSnapshot(key, updateBean), singleSnapshot(key, deleteBean));

        if (insertBean != null) {
            insCount = 1;
            try (BeanRunner runner = jdbcTemplate.beanRunner()) {
                runner.insertBean(insertBean);
                releasePendingKey(insertBean);
                insAff = 1;
            }
        }

        if (updateBean != null) {
            updCount = 1;
            try (BeanRunner runner = jdbcTemplate.beanRunner()) {
                updAff = runner.updateBean(updateBean);
                releasePendingKey(updateBean);
            }
        }

        if (deleteBean != null) {
            delCount = 1;
            try (BeanRunner runner = jdbcTemplate.beanRunner()) {
                delAff = runner.deleteBean(deleteBean);
            }
            cache.remove(key);
            // 删除时用的是临时 key，刷盘后主键已回写，把 Tombstone 迁到正式 key 上继续挡回源
            relocatePendingTombstone(deleteBean, key);
            pendingKeys.remove(deleteBean);
        }

        return new FlushResult(insCount, updCount, delCount, insAff, updAff, delAff);
    }

    /** 构造单条目快照 Map（null 值返回空 Map） */
    private static Map<String, Object> singleSnapshot(String key, Object bean) {
        if (bean == null) return Map.of();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, bean);
        return m;
    }

    /**
     * 核心批量刷盘逻辑。
     * <p>先按实体类型分组再批量执行，因为 {@code insertBeanList / updateBeanList}
     * 要求列表内所有 bean 为同一类型（SQL 从第一个 bean 推断）。</p>
     * <p>
     * 执行顺序固定 INSERT → UPDATE → DELETE："先补上还没落库的行，再改已存在的行，最后删"。
     * 乱序（比如先 DELETE）会让同批里既待更新又待删除的数据变成"先删后改"的 0 行更新。
     * 正常情况下同一 key 只出现在一张脏表里（互斥由写方法维护），这个顺序是为异常状态兜底。
     * </p>
     * <p>
     * 摘取用 {@link #takeByPrefix} 一次性 remove 并留快照：脏表里不再保留这批数据，
     * 因此刷盘期间其他线程对同一 key 的新写入不会被本批覆盖（它会重新进脏表等下一次）。
     * 代价是"刷盘失败且补偿没生效"就丢数据，所以事务内必须先登记回滚补偿再执行（见规则②）。
     * </p>
     * <p>每个分组各开一个 BeanRunner（独立连接），不共享事务：刷盘本身是落库动作而不是业务事务的一部分；
     * 若它恰好跑在事务里，QueryRunner 会自动改用事务连接，于是自动变成"JOIN 当前事务"。</p>
     *
     * @param keyPrefix 若为 null 则刷全部；否则只刷指定表前缀的脏数据
     */
    private FlushResult doFlush(String keyPrefix) {
        Map<String, Object> insertSnap = takeByPrefix(dirtyInserts, keyPrefix);
        Map<String, Object> updateSnap = takeByPrefix(dirtyUpdates, keyPrefix);
        Map<String, Object> deleteSnap = takeByPrefix(dirtyDeletes, keyPrefix);

        // 事务内刷盘 = JOIN 当前事务（全有或全无）：注册回滚补偿，把移除的快照放回脏表
        registerTxFlushCompensation(insertSnap, updateSnap, deleteSnap);

        // 按实体类型分组（避免 User 和 Order 混在一起执行）；分组保留 key→bean 映射，失败时才能按原 key 精确放回
        Map<Class<?>, Map<String, Object>> insertGroups = groupByClass(insertSnap);
        Map<Class<?>, Map<String, Object>> updateGroups = groupByClass(updateSnap);
        Map<Class<?>, Map<String, Object>> deleteGroups = groupByClass(deleteSnap);

        int insertAffected = 0, updateAffected = 0, deleteAffected = 0;

        // 按分组批量 INSERT
        for (Map<String, Object> group : insertGroups.values()) {
            if (group.isEmpty()) continue;
            List<Object> beans = new ArrayList<>(group.values());
            try (BeanRunner runner = jdbcTemplate.beanRunner()) {
                runner.setBatchSize(this.batchSize);
                BatchResult<Object> objectBatchResult = runner.insertBeanList(beans);
                int sum = objectBatchResult.getSuccessCount();
                insertAffected += sum;
                releasePendingKeys(beans);
                // 非事务下失败条目必须放回脏表，否则永久丢失（事务内由补偿兜底）
                requeueFailed(dirtyInserts, group, failedBeans(objectBatchResult));
                log.info("批量刷盘 INSERT {}: {} 条, 影响 {} 行",
                        beans.getFirst().getClass().getSimpleName(), beans.size(), sum);
            } catch (Exception e) {
                log.warn("批量刷盘 INSERT 失败，放回脏表: {}", e.getMessage());
                requeueFailed(dirtyInserts, group, beans);
            }
        }

        // 按分组批量 UPDATE
        for (Map<String, Object> group : updateGroups.values()) {
            if (group.isEmpty()) continue;
            List<Object> beans = new ArrayList<>(group.values());
            try (BeanRunner runner = jdbcTemplate.beanRunner()) {
                runner.setBatchSize(this.batchSize);
                BatchResult<Object> objectBatchResult = runner.updateBeanList(beans);
                updateAffected += objectBatchResult.getSuccessCount();
                releasePendingKeys(beans);
                requeueFailed(dirtyUpdates, group, failedBeans(objectBatchResult));
                log.info("批量刷盘 UPDATE {}: {} 条, 影响 {} 行",
                        beans.getFirst().getClass().getSimpleName(), beans.size(), objectBatchResult.getSuccessCount());
            } catch (Exception e) {
                log.warn("批量刷盘 UPDATE 失败，放回脏表: {}", e.getMessage());
                requeueFailed(dirtyUpdates, group, beans);
            }
        }

        // 按分组批量 DELETE
        for (Map<String, Object> group : deleteGroups.values()) {
            if (group.isEmpty()) continue;
            List<Object> beans = new ArrayList<>(group.values());
            try (BeanRunner runner = jdbcTemplate.beanRunner()) {
                runner.setBatchSize(this.batchSize);
                int[] results = runner.deleteListBean(beans);
                int sum = results == null ? beans.size() : sum(results);
                deleteAffected += sum;
                // 从缓存中移除已删除的 key（删除不回填主键，按实例解析正式/临时 Key）
                for (Object bean : beans) {
                    String k = keyOf(bean);
                    cache.remove(k);
                    pendingKeys.remove(bean);
                }
                log.info("批量刷盘 DELETE {}: {} 条, 影响 {} 行",
                        beans.getFirst().getClass().getSimpleName(), beans.size(), sum);
            } catch (Exception e) {
                log.warn("批量刷盘 DELETE 失败，放回脏表: {}", e.getMessage());
                requeueDeleted(dirtyDeletes, group, beans);
            }
        }

        return new FlushResult(
                insertSnap.size(), updateSnap.size(), deleteSnap.size(),
                insertAffected, updateAffected, deleteAffected
        );
    }

    // ==================== 淘汰回调（数据一致性兜底） ====================

    /**
     * 缓存条目被淘汰时自动刷盘（最后一道防线：条目马上要没了，脏数据不能再留在内存里）。
     * <p>使用 {@link ConcurrentHashMap#remove} 原子操作，与 {@link #flushAll()}
     * 并发执行时同一 Key 只有一个线程能拿到脏数据，不会重复刷盘。</p>
     * <p>
     * 三段是"取到一段就 return"，不是三段都执行：一个 key 同一时刻只属于一张脏表（互斥由写方法维护），
     * 顺序检查即可。
     * </p>
     * <p>
     * 失败时把 bean <em>放回</em>脏表而不是吞掉：留在脏表里，下一次 flush 仍会重试。
     * 但要注意放回后它已经不在缓存里了，也就失去了 onEvict 保护，
     * 所以刷盘失败一定要靠 warn 日志暴露，不能只指望兜底。
     * </p>
     */
    private void handleEviction(String key, Object value) {
        // 1. 检查是否待 INSERT
        Object insertBean = dirtyInserts.remove(key);
        if (insertBean != null) {
            try (BeanRunner runner = jdbcTemplate.beanRunner()) {
                runner.insertBean(insertBean);
                releasePendingKey(insertBean);
                log.info("缓存淘汰时 INSERT 完成: {}", key);
            } catch (Exception e) {
                log.warn("缓存淘汰时 INSERT 失败，放回脏表: {}, {}", key, e.getMessage());
                dirtyInserts.put(key, insertBean);
            }
            return;
        }

        // 2. 检查是否待 UPDATE
        Object updateBean = dirtyUpdates.remove(key);
        if (updateBean != null) {
            try (BeanRunner runner = jdbcTemplate.beanRunner()) {
                runner.updateBean(updateBean);
                releasePendingKey(updateBean);
                log.info("缓存淘汰时 UPDATE 完成: {}", key);
            } catch (Exception e) {
                log.warn("缓存淘汰时 UPDATE 失败，放回脏表: {}, {}", key, e.getMessage());
                dirtyUpdates.put(key, updateBean);
            }
            return;
        }

        // 3. 检查是否待 DELETE（缓存中存的是 TOMBSTONE，value 已被忽略）
        Object deleteBean = dirtyDeletes.remove(key);
        if (deleteBean != null) {
            try (BeanRunner runner = jdbcTemplate.beanRunner()) {
                runner.deleteBean(deleteBean);
                cache.remove(key);
                relocatePendingTombstone(deleteBean, key);
                pendingKeys.remove(deleteBean);
                log.info("缓存淘汰时 DELETE 完成: {}", key);
            } catch (Exception e) {
                log.warn("缓存淘汰时 DELETE 失败，放回脏表: {}, {}", key, e.getMessage());
                dirtyDeletes.put(key, deleteBean);
            }
        }
    }

    // ==================== 透传方法（不走缓存） ====================
    // 这些方法刻意绕过缓存直连 DB：全表/条件查询/清空都没有"单个 key"的概念，无法映射到缓存条目上。
    // 后果是延迟写尚未刷盘时，这里看到的与 queryById 不一致（DB 是旧的，缓存是新的）——
    // 这是 Write-Behind 的固有语义而不是 bug，所以 queryAll 保持"透明直查"，
    // 不去合并脏表内容（那样做等于在缓存层重造一个未提交的视图，复杂度不可控）。

    /** 全表查询：直连 DB，不读缓存也不回填缓存 */
    public <T> List<T> queryAll(Class<T> entityClass) {
        try (BeanRunner runner = jdbcTemplate.beanRunner()) {
            return runner.queryAll(entityClass);
        }
    }

    /**
     * 条件查询单条：直连 DB。
     *
     * @deprecated 沿用 BeanRunner 的字符串 WHERE 旧 API，请改用 {@link #select(Class)}
     */
    @Deprecated(forRemoval = true)
    public <T> T queryOne(Class<T> entityClass, String whereSection, Object... params) {
        try (BeanRunner runner = jdbcTemplate.beanRunner()) {
            return runner.queryOne(entityClass, whereSection, params);
        }
    }

    /**
     * 条件查询列表：直连 DB。
     *
     * @deprecated 沿用 BeanRunner 的字符串 WHERE 旧 API，请改用 {@link #select(Class)}
     */
    @Deprecated(forRemoval = true)
    public <T> List<T> queryList(Class<T> entityClass, String whereSection, Object... params) {
        try (BeanRunner runner = jdbcTemplate.beanRunner()) {
            return runner.queryList(entityClass, whereSection, params);
        }
    }

    /** 查询 DSL 入口（不经过缓存，直接查 DB） */
    public <T> SelectQuery<T> select(Class<T> entityClass) {
        return new SelectQuery<>(jdbcTemplate, entityClass);
    }

    /**
     * 清空整表：直连 DB。
     * <p>注意它<em>不</em>清脏表：脏表里待写入的数据之后仍会被刷进这张"已清空"的表。
     * 需要确定语义请先 {@link #flushAll()} 再 deleteAll，或干脆 {@code clearAll()} 放弃未落库改动。</p>
     */
    public void deleteAll(Class<?> cls) {
        try (BeanRunner runner = jdbcTemplate.beanRunner()) {
            runner.deleteAll(cls);
        }
    }

    // ==================== 监控 ====================
    // 未刷盘的积压量只有这几个数字能反映，是判断"还要不要等/是不是刷不动"的唯一手段。

    /** 待刷插入条数 */
    public int dirtyInsertCount() {return dirtyInserts.size();}

    /** 待刷更新条数 */
    public int dirtyUpdateCount() {return dirtyUpdates.size();}

    /** 待刷删除条数 */
    public int dirtyDeleteCount() {return dirtyDeletes.size();}

    /** 脏表总量：刷盘前后各取一次，差值即本次真正摘走的条目数 */
    public int dirtyTotalCount() {return dirtyInsertCount() + dirtyUpdateCount() + dirtyDeleteCount();}

    /** 缓存条目数（含 Tombstone 哨兵） */
    public long cacheSize() {return cache.size();}

    /**
     * 清空所有缓存和脏数据（谨慎使用，会丢失未刷盘数据！）。
     * <p>只清内存、不动 DB：已经刷进去的数据还在库里。所以测试里用它做隔离时必须另外清表，
     * 否则跨用例残留会让断言假通过。</p>
     */
    public void clearAll() {
        dirtyInserts.clear();
        dirtyUpdates.clear();
        dirtyDeletes.clear();
        pendingKeys.clear();
        cache.clear();
        log.warn("已清空所有缓存和脏数据！未刷盘的数据已丢失！");
    }

    // ==================== 内部工具方法 ====================

    /**
     * 解析实例的缓存 Key。主键已生成 → {@code table:pk}；
     * 主键为 null（延迟插入待回写）→ 按实例身份分配/复用唯一的临时 Key
     * （形如 {@code table:#pending:1}，保留表名前缀使按类刷盘 {@code takeByPrefix} 仍能命中）。
     * <p>
     * 临时 Key 不是可选装饰：延迟插入的新行主键还是 null（IDENTITY 要等 DB 回写），
     * 若此时按主键建 key，1000 条待插入实体会全部落到同一个 key 上互相覆盖，
     * 脏表最终只剩一条。所以按实例身份（IdentityHashMap）分配，同一实例多次操作复用同一临时 key，
     * 从而保证"插入后又更新"能合并进脏插入桶而不是分裂成两条。
     * </p>
     * <p>{@code compute} 的原子性很关键：并发为同一实例求 key 时只能有一个线程写入映射。</p>
     */
    private String keyOf(Object bean) {
        String realKey = tryRealKey(bean);
        if (realKey != null) return realKey;
        return pendingKeys.compute(bean, (b, k) ->
                k != null ? k : CacheKeyBuilder.keyPrefix(b.getClass()) + "#pending:" + pendingSeq.incrementAndGet());
    }

    /** 主键已生成时返回正式 Key，主键缺失（null 或未回写的数值 0 哨兵）返回 null */
    private static String tryRealKey(Object bean) {
        Object pk = CacheKeyBuilder.extractPkValue(bean);
        if (pkMissing(pk)) return null;
        return CacheKeyBuilder.buildKey(bean);
    }

    /**
     * 主键缺失判定：null、或数值 0（原始类型 long/int 未回写时的哨兵，自增主键从 1 起）、或复合主键各分量均缺失。
     * <p>把 0 也算"缺失"是针对基本类型字段的必要妥协：{@code long id} 没回写时读出来就是 0，
     * 无法与 null 区分。副作用是"主键真为 0"的行会被当成未生成而走临时 key——
     * 业务若显式使用 0 作主键，请用包装类型字段。</p>
     */
    private static boolean pkMissing(Object pk) {
        if (pk == null) return true;
        if (pk instanceof Number n) return n.longValue() == 0L;
        if (pk instanceof Object[] arr) {
            for (Object component : arr) {
                if (!pkMissing(component)) return false;
            }
            return true;
        }
        return false;
    }

    /**
     * DB 写入成功（主键已回写）后释放该实例的临时 Key 映射。
     * <p>先确认真的拿到主键再移除：若插入失败或被延迟，临时 key 必须继续存在，
     * 否则下一次操作会给同一实例另分配一个新临时 key，脏表里就凭空多出一条待插入数据。</p>
     */
    private void releasePendingKey(Object bean) {
        if (tryRealKey(bean) != null) {
            pendingKeys.remove(bean);
        }
    }

    /** 批量释放：仅移除已拿到主键的实例映射（刷盘部分失败时失败的那些仍保留临时 key 等重试） */
    private void releasePendingKeys(Collection<Object> beans) {
        beans.forEach(this::releasePendingKey);
    }

    /**
     * 延迟删除的临时 Key 在刷盘拿到主键后，把 Tombstone 迁移到正式 Key 上。
     * <p>不迁移的后果很具体：删除前后 key 变了（{@code table:#pending:3} → {@code table:17}），
     * 迁移前对正式 key 的查询会绕过 Tombstone 直接回源，把刚删掉的行读回来。</p>
     */
    private void relocatePendingTombstone(Object bean, String pendingKey) {
        String realKey = tryRealKey(bean);
        if (realKey == null) return;
        if (cache.getIfPresent(pendingKey) == TOMBSTONE) {
            cache.put(realKey, TOMBSTONE);
            cache.remove(pendingKey);
        }
    }

    /**
     * 按前缀摘取脏数据（key → bean），并从脏表移除；返回快照供事务回滚补偿。
     * <p>
     * 用迭代器边遍历边 remove（ConcurrentHashMap 的迭代器支持弱一致移除），
     * 而不是先 collect 再 keySet().removeAll：后者会在两次操作之间被并发写入插队，
     * 那些插队条目既没进快照也没被移除，语义上等于"漏刷"。
     * </p>
     * <p>返回 LinkedHashMap 保留脏表遍历顺序——刷盘顺序不影响正确性，但稳定的顺序让日志可复现。</p>
     */
    private Map<String, Object> takeByPrefix(ConcurrentHashMap<String, Object> map, String keyPrefix) {
        Map<String, Object> taken = new LinkedHashMap<>();
        Iterator<Map.Entry<String, Object>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Object> entry = it.next();
            if (keyPrefix == null || entry.getKey().startsWith(keyPrefix)) {
                taken.put(entry.getKey(), entry.getValue());
                it.remove();
            }
        }
        return taken;
    }

    /** 当前线程本模板是否处于事务中（判定依据是线程上下文，不是某个 Runner 的连接来源） */
    private boolean txActive() {
        return TxContext.isActive(jdbcTemplate);
    }

    /**
     * 延迟写在事务内的处理：按 {@link FlushMode} 决定升级为立即写 / 拒绝。
     * 非事务时返回 false，维持原延迟语义。
     * <p>
     * 这是"事务边界规则①"的落点：返回值被写成 {@code immediate || upgradeDeferredInTx()}，
     * 所以只要本方法为 true，延迟分支就完全不会执行——脏表在事务里保持干净，
     * 事务回滚时不存在"缓存已改、DB 未改"的空洞。
     * </p>
     * <p>switch 覆盖全部枚举值且没有 default：将来新增 FlushMode 时编译期就会要求在此表态，
     * 避免新语义被静默当成"不升级"处理。</p>
     */
    private boolean upgradeDeferredInTx() {
        if (!txActive()) return false;
        return switch (txFlushMode) {
            case TX_IMMEDIATE, TX_JOIN -> true;
            case TX_SUSPEND -> throw new IllegalTransactionStateException(
                    "当前线程处于事务中，txFlushMode=TX_SUSPEND 不允许延迟写");
            case TX_PENDING_BUFFER -> throw new UnsupportedOperationException(
                    "FlushMode.TX_PENDING_BUFFER 尚未实现");
        };
    }

    /**
     * 事务内直接写 DB 后注册回滚补偿：回滚时精确失效该 key 的缓存，
     * 下次查询回源 DB，避免"幽灵写"；提交则什么都不做。
     * <p>
     * "精确"是重点：只失效本事务写过的 key，而不是清整个缓存。
     * 回滚后 DB 回到事务开始前的状态，而缓存里是本事务写入的新值——不失效就是脏读，
     * 且这份脏会一直驻留到条目过期。
     * </p>
     * <p>写在 {@code afterCompletion} 而不是 commit 之前：失效动作本身不可回滚，放在完成阶段才不会
     * 与"回滚后还要重试"冲突。非事务调用直接返回，因为 autocommit 下写入即生效，没有回滚一说。</p>
     */
    private void invalidateCacheOnRollback(String key) {
        ConnectionHolder holder = TxContext.get(jdbcTemplate);
        if (holder == null || !holder.isTransactional()) return;
        holder.getCacheKeysWritten().add(key);
        TxContext.register(holder, new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    cache.remove(key);
                }
            }
        });
    }

    /**
     * 事务内 flush 的回滚补偿：把已摘除的脏数据快照 putIfAbsent 放回脏表（不误伤其他线程
     * 期间写入的更新版本），否则"回滚 + 已 remove"意味着数据永久丢失。
     * <p>
     * 三点设计意图：
     * </p>
     * <ol>
     *   <li>注册即返回快照，实际放回延迟到 afterCompletion——提交成功时什么都不做，
     *       不能让已落库的数据重新变脏；</li>
     *   <li>{@code putIfAbsent} 而不是 put：刷盘期间其他线程可能已经写了同一 key 的更新版本，
     *       无条件放回会用旧值覆盖新值（丢更新）；</li>
     *   <li>删除快照放回时还要补写 Tombstone：条目在缓存里已被刷盘流程 remove 过，
     *       不补就等于"删掉的行又能被读回来"。</li>
     * </ol>
     * <p>非事务（holder 为空或 holder 不是真事务）时直接返回：那种情况下刷盘已经 autocommit 落库，
     * 没有"回滚"可言，放回反而是重复写。</p>
     */
    private void registerTxFlushCompensation(Map<String, Object> inserts, Map<String, Object> updates,
                                             Map<String, Object> deletes) {
        ConnectionHolder holder = TxContext.get(jdbcTemplate);
        if (holder == null || !holder.isTransactional()) return;
        if (inserts.isEmpty() && updates.isEmpty() && deletes.isEmpty()) return;
        TxContext.register(holder, new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_COMMITTED) return;
                restoreInto(dirtyInserts, inserts);
                restoreInto(dirtyUpdates, updates);
                for (Map.Entry<String, Object> entry : deletes.entrySet()) {
                    if (dirtyDeletes.putIfAbsent(entry.getKey(), entry.getValue()) == null
                            && cache.getIfPresent(entry.getKey()) == null) {
                        cache.put(entry.getKey(), TOMBSTONE);
                    }
                }
            }
        });
    }

    /**
     * 把快照放回脏表，并在缓存已不含该条目时一并回填值。
     * <p>回填缓存这一步是为了让"回滚后的读"仍能看到未落库的改动（本进程的写意图还在），
     * 而不是读到 DB 的旧值造成前后不一致的错觉。{@code getIfPresent == null} 的条件保证
     * 不去覆盖刷盘期间其他线程写入的更新值。</p>
     */
    private void restoreInto(ConcurrentHashMap<String, Object> dirtyMap, Map<String, Object> snapshot) {
        for (Map.Entry<String, Object> entry : snapshot.entrySet()) {
            if (dirtyMap.putIfAbsent(entry.getKey(), entry.getValue()) == null
                    && cache.getIfPresent(entry.getKey()) == null) {
                cache.put(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * 按实体类型分组（批量 INSERT/UPDATE 要求同类型）。
     * <p>分组保留 key→bean 映射：刷盘失败时要把未落库的条目按<em>原 key</em>放回脏表，
     * 若只保留 List 则只能用 {@code keyOf} 重算——主键为 null 的临时 key 会漂移，放不回原位。</p>
     */
    private Map<Class<?>, Map<String, Object>> groupByClass(Map<String, Object> snapshot) {
        Map<Class<?>, Map<String, Object>> groups = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : snapshot.entrySet()) {
            groups.computeIfAbsent(entry.getValue().getClass(), k -> new LinkedHashMap<>())
                    .put(entry.getKey(), entry.getValue());
        }
        return groups;
    }

    /** 提取批量结果里的失败实体列表（无失败返回空列表，避免调用方判 null） */
    private static List<Object> failedBeans(BatchResult<?> result) {
        if (!result.hasFailures()) return List.of();
        List<Object> failed = new ArrayList<>(result.getFailures().size());
        for (BatchResult.FailureDetail failure : result.getFailures()) {
            failed.add(failure.getBean());
        }
        return failed;
    }

    /**
     * 非事务刷盘失败时，把未落库的插入/更新实体放回脏表（按分组快照的原始 key，不重算）。
     * <p>事务内直接返回：那种情况由 {@link #registerTxFlushCompensation} 在 afterCompletion 兜底，
     * 这里再放回会与补偿重复、且放回时机早于回滚判定，语义矛盾。</p>
     * <p>{@code restoreInto} 内部用 putIfAbsent，不覆盖刷盘期间其他线程写入的更新版本；
     * 缓存里没有该条目时一并回填，保持"读仍见未落库改动"。</p>
     */
    private void requeueFailed(ConcurrentHashMap<String, Object> dirtyMap,
                               Map<String, Object> group, Collection<Object> failedBeans) {
        if (txActive()) return;
        Set<Object> failed = Collections.newSetFromMap(new IdentityHashMap<>());
        failed.addAll(failedBeans);
        for (Map.Entry<String, Object> entry : group.entrySet()) {
            if (failed.contains(entry.getValue())) {
                restoreInto(dirtyMap, singleSnapshot(entry.getKey(), entry.getValue()));
            }
        }
    }

    /**
     * 非事务刷盘失败时，把未落库的删除实体放回脏表，并补回 Tombstone。
     * <p>与 {@link #requeueFailed} 的差别只在缓存回填物：删除失败要补 Tombstone 而不是实体本身，
     * 否则下一次 {@link #queryById} 会回源 DB 把刚删掉的行读回来（缓存穿透）。</p>
     */
    private void requeueDeleted(ConcurrentHashMap<String, Object> dirtyMap,
                                Map<String, Object> group, Collection<Object> failedBeans) {
        if (txActive()) return;
        Set<Object> failed = Collections.newSetFromMap(new IdentityHashMap<>());
        failed.addAll(failedBeans);
        for (Map.Entry<String, Object> entry : group.entrySet()) {
            if (!failed.contains(entry.getValue())) continue;
            if (dirtyMap.putIfAbsent(entry.getKey(), entry.getValue()) == null
                    && cache.getIfPresent(entry.getKey()) == null) {
                cache.put(entry.getKey(), TOMBSTONE);
            }
        }
    }

    /** 数组求和（用于把逐行影响行数汇总成刷盘报告的 affected 总数） */
    private static int sum(int[] arr) {
        int s = 0;
        for (int v : arr) s += v;
        return s;
    }

}
