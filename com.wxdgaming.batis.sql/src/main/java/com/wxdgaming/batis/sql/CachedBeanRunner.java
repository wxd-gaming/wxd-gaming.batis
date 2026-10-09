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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 带缓存的 BeanRunner，支持 Write-Behind（延迟批刷）模式。
 *
 * <h3>缓存三防策略</h3>
 * <table>
 *   <tr><th>问题</th><th>方案</th></tr>
 *   <tr><td>缓存击穿（单 Key 并发）</td><td>getOrLoad 单飞加载，Caffeine 保证同一 Key 仅一个线程查 DB</td></tr>
 *   <tr><td>缓存雪崩（批量过期）</td><td>refreshAfterWrite 访问时检测 → 返回旧值 + 后台异步刷新</td></tr>
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

    /** Tombstone 哨兵，标记该 Key 已被逻辑删除但尚未刷到 DB */
    private static final Object TOMBSTONE = new Object();

    @Getter private final JdbcTemplate jdbcTemplate;
    private final EntityCache<String, Object> cache;

    /** 待 INSERT：key → bean */
    private final ConcurrentHashMap<String, Object> dirtyInserts = new ConcurrentHashMap<>();
    /** 待 UPDATE：key → bean */
    private final ConcurrentHashMap<String, Object> dirtyUpdates = new ConcurrentHashMap<>();
    /** 待 DELETE：key → bean（保留引用以提取主键参数） */
    private final ConcurrentHashMap<String, Object> dirtyDeletes = new ConcurrentHashMap<>();
    /** 批量提交大小，从 JdbcTemplate 继承默认值，可随时修改；&lt;=0 表示不分组一次性提交 */
    @Setter @Getter private int batchSize;
    /** 事务内延迟写策略（默认 {@link FlushMode#TX_IMMEDIATE}） */
    @Setter @Getter private FlushMode txFlushMode = FlushMode.TX_IMMEDIATE;
    /** 延迟插入且主键尚未生成（如 IDENTITY 策略）的实例 → 临时 Key 映射；主键为 null 时按 buildKey 会全实例碰撞，故按实例身份分配唯一 Key */
    private final Map<Object, String> pendingKeys =
            Collections.synchronizedMap(new IdentityHashMap<>());
    /** 临时 Key 序列号 */
    private final AtomicLong pendingSeq = new AtomicLong();

    public CachedBeanRunner(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new LocalEntityCache<>());
    }

    public CachedBeanRunner(JdbcTemplate jdbcTemplate, EntityCache<String, Object> cache) {
        this.jdbcTemplate = jdbcTemplate;
        this.cache = cache;
        this.batchSize = jdbcTemplate.getBatchSize();

        // 注册淘汰回调：缓存过期时自动将脏数据刷到 DB，防止数据丢失
        this.cache.onEvict(entry -> handleEviction(entry.getKey(), entry.getValue()));
    }

    // ==================== 查询 ====================

    /**
     * 按主键查询。
     * <p>缓存命中 → 返回（Tombstone 则返回 null）；
     * 缓存未命中 → 单飞加载 DB → 回填缓存。</p>
     *
     * @param entityClass 实体类
     * @param id          主键值
     * @param <T>         实体类型
     * @return 实体对象，未找到或已标记删除时返回 null
     */
    @SuppressWarnings("unchecked")
    public <T> T queryById(Class<T> entityClass, Object id) {
        String key = CacheKeyBuilder.buildKey(entityClass, id);

        // 先尝试不触发加载直接获取
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

        if (result == TOMBSTONE) {
            return null;
        }
        return (T) result;
    }

    // ==================== 插入 ====================

    /** 默认延迟插入（只写缓存，不写 DB）。 */
    public Number insertBean(Object bean) {
        return insertBean(bean, false);
    }

    /**
     * 插入实体。
     *
     * @param bean      实体对象
     * @param immediate true=立即写 DB + 缓存；false=只写缓存 + 标记脏插入
     * @return 自增主键（immediate 时从 DB 获取，延迟模式返回 null）
     */
    public Number insertBean(Object bean, boolean immediate) {
        String key = keyOf(bean);

        if (immediate || upgradeDeferredInTx()) {
            try (BeanRunner runner = jdbcTemplate.beanRunner()) {
                Number genId = runner.insertBean(bean);
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

    /** 默认延迟更新（只更新缓存，不写 DB）。 */
    public int updateBean(Object bean) {
        return updateBean(bean, false);
    }

    /**
     * 更新实体。
     * <p><b>缓存策略：更新缓存而非删除</b>，保证业务代码持有的 bean 引用仍然有效，
     * 且后续查询直接命中缓存，无需重新查 DB。</p>
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
            dirtyDeletes.remove(key);
            log.debug("延迟更新（仅缓存）: {}", key);
            return -1;
        }
    }

    // ==================== 删除 ====================

    /** 默认延迟删除（缓存标记删除，不写 DB）。 */
    public int deleteBean(Object bean) {
        return deleteBean(bean, false);
    }

    /**
     * 删除实体。
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

    /** 刷全部脏数据到 DB。 */
    public FlushResult flushAll() {
        return doFlush(null);
    }

    /** 刷指定实体类的脏数据到 DB。 */
    public FlushResult flush(Class<?> entityClass) {
        return doFlush(CacheKeyBuilder.keyPrefix(entityClass));
    }

    /**
     * 强制刷单个实体到 DB。
     * <p>不关心当前是否标记脏，直接移除并执行 DB 操作。</p>
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
     *
     * @param keyPrefix 若为 null 则刷全部；否则只刷指定表前缀的脏数据
     */
    private FlushResult doFlush(String keyPrefix) {
        Map<String, Object> insertSnap = takeByPrefix(dirtyInserts, keyPrefix);
        Map<String, Object> updateSnap = takeByPrefix(dirtyUpdates, keyPrefix);
        Map<String, Object> deleteSnap = takeByPrefix(dirtyDeletes, keyPrefix);
        List<Object> inserts = new ArrayList<>(insertSnap.values());
        List<Object> updates = new ArrayList<>(updateSnap.values());
        List<Object> deletes = new ArrayList<>(deleteSnap.values());

        // 事务内刷盘 = JOIN 当前事务（全有或全无）：注册回滚补偿，把移除的快照放回脏表
        registerTxFlushCompensation(insertSnap, updateSnap, deleteSnap);

        // 按实体类型分组（避免 User 和 Order 混在一起执行）
        Map<Class<?>, List<Object>> insertGroups = groupByClass(inserts);
        Map<Class<?>, List<Object>> updateGroups = groupByClass(updates);
        Map<Class<?>, List<Object>> deleteGroups = groupByClass(deletes);

        int insertAffected = 0, updateAffected = 0, deleteAffected = 0;

        // 按分组批量 INSERT
        for (List<Object> group : insertGroups.values()) {
            if (group.isEmpty()) continue;
            try (BeanRunner runner = jdbcTemplate.beanRunner()) {
                runner.setBatchSize(this.batchSize);
                BatchResult<Object> objectBatchResult = runner.insertBeanList(group);
                int sum = objectBatchResult.getSuccessCount();
                insertAffected += sum;
                releasePendingKeys(insertSnap.values());
                log.info("批量刷盘 INSERT {}: {} 条, 影响 {} 行",
                        group.getFirst().getClass().getSimpleName(), group.size(), sum);
            }
        }

        // 按分组批量 UPDATE
        for (List<Object> group : updateGroups.values()) {
            if (group.isEmpty()) continue;
            try (BeanRunner runner = jdbcTemplate.beanRunner()) {
                runner.setBatchSize(this.batchSize);
                BatchResult<Object> objectBatchResult = runner.updateBeanList(group);
                updateAffected += objectBatchResult.getSuccessCount();
                releasePendingKeys(updateSnap.values());
                log.info("批量刷盘 UPDATE {}: {} 条, 影响 {} 行",
                        group.getFirst().getClass().getSimpleName(), group.size(), objectBatchResult.getSuccessCount());
            }
        }

        // 按分组批量 DELETE
        for (List<Object> group : deleteGroups.values()) {
            if (group.isEmpty()) continue;
            try (BeanRunner runner = jdbcTemplate.beanRunner()) {
                runner.setBatchSize(this.batchSize);
                int[] results = runner.deleteListBean(group);
                int sum = results == null ? group.size() : sum(results);
                deleteAffected += sum;
                // 从缓存中移除已删除的 key（删除不回填主键，按实例解析正式/临时 Key）
                for (Object bean : group) {
                    String k = keyOf(bean);
                    cache.remove(k);
                    pendingKeys.remove(bean);
                }
                log.info("批量刷盘 DELETE {}: {} 条, 影响 {} 行",
                        group.getFirst().getClass().getSimpleName(), group.size(), sum);
            }
        }

        return new FlushResult(
                inserts.size(), updates.size(), deletes.size(),
                insertAffected, updateAffected, deleteAffected
        );
    }

    // ==================== 淘汰回调（数据一致性兜底） ====================

    /**
     * 缓存条目被淘汰时自动刷盘。
     * <p>使用 {@link ConcurrentHashMap#remove} 原子操作，与 {@link #flushAll()}
     * 并发执行时同一 Key 只有一个线程能拿到脏数据，不会重复刷盘。</p>
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

    public <T> List<T> queryAll(Class<T> entityClass) {
        try (BeanRunner runner = jdbcTemplate.beanRunner()) {
            return runner.queryAll(entityClass);
        }
    }

    public <T> T queryOne(Class<T> entityClass, String whereSection, Object... params) {
        try (BeanRunner runner = jdbcTemplate.beanRunner()) {
            return runner.queryOne(entityClass, whereSection, params);
        }
    }

    public <T> List<T> queryList(Class<T> entityClass, String whereSection, Object... params) {
        try (BeanRunner runner = jdbcTemplate.beanRunner()) {
            return runner.queryList(entityClass, whereSection, params);
        }
    }

    /** 查询 DSL 入口（不经过缓存，直接查 DB） */
    public <T> SelectQuery<T> select(Class<T> entityClass) {
        return new SelectQuery<>(jdbcTemplate, entityClass);
    }

    public void deleteAll(Class<?> cls) {
        try (BeanRunner runner = jdbcTemplate.beanRunner()) {
            runner.deleteAll(cls);
        }
    }

    // ==================== 监控 ====================

    public int dirtyInsertCount() {return dirtyInserts.size();}

    public int dirtyUpdateCount() {return dirtyUpdates.size();}

    public int dirtyDeleteCount() {return dirtyDeletes.size();}

    public int dirtyTotalCount() {return dirtyInsertCount() + dirtyUpdateCount() + dirtyDeleteCount();}

    public long cacheSize() {return cache.size();}

    /** 清空所有缓存和脏数据（谨慎使用，会丢失未刷盘数据！） */
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

    /** 主键缺失判定：null、或数值 0（原始类型 long/int 未回写时的哨兵，自增主键从 1 起）、或复合主键各分量均缺失 */
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

    /** DB 写入成功（主键已回写）后释放该实例的临时 Key 映射 */
    private void releasePendingKey(Object bean) {
        if (tryRealKey(bean) != null) {
            pendingKeys.remove(bean);
        }
    }

    /** 批量释放：仅移除已拿到主键的实例映射 */
    private void releasePendingKeys(Collection<Object> beans) {
        beans.forEach(this::releasePendingKey);
    }

    /** 延迟删除的临时 Key 在刷盘拿到主键后，把 Tombstone 迁移到正式 Key 上 */
    private void relocatePendingTombstone(Object bean, String pendingKey) {
        String realKey = tryRealKey(bean);
        if (realKey == null) return;
        if (cache.getIfPresent(pendingKey) == TOMBSTONE) {
            cache.put(realKey, TOMBSTONE);
            cache.remove(pendingKey);
        }
    }

    /** 按前缀摘取脏数据（key → bean），并从脏表移除；返回快照供事务回滚补偿 */
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

    /** 当前线程本模板是否处于事务中 */
    private boolean txActive() {
        return TxContext.isActive(jdbcTemplate);
    }

    /**
     * 延迟写在事务内的处理：按 {@link FlushMode} 决定升级为立即写 / 拒绝。
     * 非事务时返回 false，维持原延迟语义。
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

    private void restoreInto(ConcurrentHashMap<String, Object> dirtyMap, Map<String, Object> snapshot) {
        for (Map.Entry<String, Object> entry : snapshot.entrySet()) {
            if (dirtyMap.putIfAbsent(entry.getKey(), entry.getValue()) == null
                    && cache.getIfPresent(entry.getKey()) == null) {
                cache.put(entry.getKey(), entry.getValue());
            }
        }
    }

    /** 按实体类型分组（批量 INSERT/UPDATE 要求同类型） */
    private Map<Class<?>, List<Object>> groupByClass(List<Object> beans) {
        return beans.stream().collect(Collectors.groupingBy(
                Object::getClass,
                LinkedHashMap::new,
                Collectors.toList()
        ));
    }

    /** 数组求和 */
    private static int sum(int[] arr) {
        int s = 0;
        for (int v : arr) s += v;
        return s;
    }
}
