package com.wxdgaming.batis.sql.partition;

import com.wxdgaming.batis.sql.JdbcTemplate;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分区缓存
 *
 * <p>首次访问时从数据库加载已有分区名，后续操作命中内存缓存。
 * 执行 ADD/DROP 后自动同步缓存，避免重复 DDL 操作。</p>
 *
 * <p>线程安全：使用 {@link ConcurrentHashMap} + {@link TreeSet}（大小写不敏感）。</p>
 *
 * <h3>典型场景</h3>
 * <pre>{@code
 * // 程序启动时创建缓存
 * PartitionCache cache = new PartitionCache(template, mysqlMetaReader);
 *
 * // 预热
 * cache.preload("t_log", "t_order");
 *
 * // 每日定时添加分区（重启后不会重复添加）
 * PartitionBuilder.of(mysqlManager, cache)
 *     .table(LogEntity.class)
 *     .withExpr(MysqlPartitionExpr.INSTANCE)
 *     .rangeByDaily("log_time", LocalDate.now(), LocalDate.now())
 *     .executeAddPartition();  // 自动跳过已存在的分区
 * }</pre>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-08-08
 */
@Slf4j
public class PartitionCache {

    @Getter
    private final JdbcTemplate jdbcTemplate;
    private final PartitionMetaReader metaReader;

    /** key=表名(小写), value=该表已有分区名集合 */
    private final Map<String, Set<String>> cache = new ConcurrentHashMap<>();

    public PartitionCache(JdbcTemplate jdbcTemplate, PartitionMetaReader metaReader) {
        this.jdbcTemplate = jdbcTemplate;
        this.metaReader = metaReader;
    }

    /**
     * 获取指定表的已有分区名集合
     * <p>首次访问时从数据库加载，后续命中缓存。</p>
     */
    public Set<String> getPartitions(String tableName) {
        String key = tableName.toLowerCase();
        return cache.computeIfAbsent(key, tn -> {
            Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            try {
                List<String> fromDb = metaReader.getPartitionNames(jdbcTemplate, tn);
                names.addAll(fromDb);
                log.debug("加载表 {} 的分区缓存: {}", tn, fromDb);
            } catch (Exception e) {
                log.warn("加载表 {} 分区元数据失败，视为无分区: {} (cause: {})",
                        tn, e.getMessage(), e.getClass().getSimpleName());
            }
            return names;
        });
    }

    /** 检查分区是否存在 */
    public boolean contains(String tableName, String partitionName) {
        return getPartitions(tableName).contains(partitionName);
    }

    /** 添加分区到缓存（执行 ADD 成功后调用） */
    public void add(String tableName, String partitionName) {
        getPartitions(tableName).add(partitionName);
        log.debug("缓存新增: {}.{}", tableName, partitionName);
    }

    /** 从缓存移除分区（执行 DROP 成功后调用） */
    public void remove(String tableName, String partitionName) {
        getPartitions(tableName).remove(partitionName);
        log.debug("缓存移除: {}.{}", tableName, partitionName);
    }

    /** 强制刷新指定表的缓存（下次访问时重新从 DB 加载） */
    public void refresh(String tableName) {
        cache.remove(tableName.toLowerCase());
    }

    /** 清空全部缓存 */
    public void refreshAll() {
        cache.clear();
    }

    /** 预加载指定表的分区（启动时批量预热） */
    public void preload(String... tableNames) {
        for (String tn : tableNames) {
            getPartitions(tn);
        }
    }
}
