package com.wxdgaming.batis.sql;

import com.wxdgaming.batis.cache.CacheKeyBuilder;
import com.wxdgaming.batis.cache.EntityCache;
import com.wxdgaming.batis.cache.LocalEntityCache;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
        String key = CacheKeyBuilder.buildKey(bean);

        if (immediate) {
            try (BeanRunner runner = jdbcTemplate.beanRunner()) {
                Number genId = runner.insertBean(bean);
                cache.put(key, bean);
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
        String key = CacheKeyBuilder.buildKey(bean);

        if (immediate) {
            try (BeanRunner runner = jdbcTemplate.beanRunner()) {
                int rows = runner.updateBean(bean);
                if (rows > 0) {
                    cache.put(key, bean);
                }
                dirtyUpdates.remove(key);
                dirtyInserts.remove(key);
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
        String key = CacheKeyBuilder.buildKey(bean);

        if (immediate) {
            try (BeanRunner runner = jdbcTemplate.beanRunner()) {
                int rows = runner.deleteBean(bean);
                cache.remove(key);
                dirtyInserts.remove(key);
                dirtyUpdates.remove(key);
                dirtyDeletes.remove(key);
                log.debug("立即删除+删缓存: {}, rows={}", key, rows);
                return rows;
            }
        } else {
            cache.put(key, TOMBSTONE);
            dirtyDeletes.put(key, bean);
            dirtyInserts.remove(key);
            dirtyUpdates.remove(key);
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
        String key = CacheKeyBuilder.buildKey(bean);

        int insCount = 0, insAff = 0;
        int updCount = 0, updAff = 0;
        int delCount = 0, delAff = 0;

        Object insertBean = dirtyInserts.remove(key);
        if (insertBean != null) {
            insCount = 1;
            try (BeanRunner runner = jdbcTemplate.beanRunner()) {
                runner.insertBean(insertBean);
                insAff = 1;
            }
        }

        Object updateBean = dirtyUpdates.remove(key);
        if (updateBean != null) {
            updCount = 1;
            try (BeanRunner runner = jdbcTemplate.beanRunner()) {
                updAff = runner.updateBean(updateBean);
            }
        }

        Object deleteBean = dirtyDeletes.remove(key);
        if (deleteBean != null) {
            delCount = 1;
            try (BeanRunner runner = jdbcTemplate.beanRunner()) {
                delAff = runner.deleteBean(deleteBean);
            }
            cache.remove(key);
        }

        return new FlushResult(insCount, updCount, delCount, insAff, updAff, delAff);
    }

    /**
     * 核心批量刷盘逻辑。
     * <p>先按实体类型分组再批量执行，因为 {@code insertBeanList / updateBeanList}
     * 要求列表内所有 bean 为同一类型（SQL 从第一个 bean 推断）。</p>
     *
     * @param keyPrefix 若为 null 则刷全部；否则只刷指定表前缀的脏数据
     */
    private FlushResult doFlush(String keyPrefix) {
        List<Object> inserts = filterByPrefix(dirtyInserts, keyPrefix, true);
        List<Object> updates = filterByPrefix(dirtyUpdates, keyPrefix, true);
        List<Object> deletes = filterByPrefix(dirtyDeletes, keyPrefix, true);

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
                int sum = sum(results);
                deleteAffected += sum;
                // 从缓存中移除已删除的 key
                for (Object bean : group) {
                    cache.remove(CacheKeyBuilder.buildKey(bean));
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
        cache.clear();
        log.warn("已清空所有缓存和脏数据！未刷盘的数据已丢失！");
    }

    // ==================== 内部工具方法 ====================

    /** 按前缀过滤脏数据并移除已收集的条目 */
    private List<Object> filterByPrefix(ConcurrentHashMap<String, Object> map,
                                        String keyPrefix, boolean remove) {
        List<Object> result = new ArrayList<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (keyPrefix == null || entry.getKey().startsWith(keyPrefix)) {
                result.add(entry.getValue());
                if (remove) {
                    map.remove(entry.getKey());
                }
            }
        }
        return result;
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
