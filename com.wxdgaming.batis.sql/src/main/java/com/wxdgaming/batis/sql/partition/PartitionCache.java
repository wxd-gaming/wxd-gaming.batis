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
 * <h3>它存在的唯一理由：让补分区变成幂等动作</h3>
 * <p>没有缓存时，"每天定时给日志表补明天的分区"这类作业跑第二遍就会撞上
 * {@code ALTER TABLE ... ADD PARTITION} 的重复分区名错误，运维脚本重跑一次就把应用打挂。
 * 有了缓存，{@link PartitionBuilder#addPartition} 会先查这里、已存在就跳过，
 * 于是同一个作业可以安全地反复执行（测试用例②专门验证这一点）。</p>
 *
 * <h3>为什么只缓存分区名，不缓存边界值</h3>
 * <p>ADD/DROP 的唯一冲突点就是分区名，名字集合足以支撑去重；边界值只有生成 DDL 时才用得到，
 * 而那是 {@link PartitionBuilder} 当场算出来的。少存一半状态也少了一半不一致的机会。</p>
 *
 * <h3>注意：它只是"名字视图"，不是库端状态的完整镜像</h3>
 * <p>缓存里的内容来自 {@link PartitionMetaReader} 的一次读取，之后靠 add/remove 增量维护。
 * 有人在库端手工增删过分区，就必须 {@link #refresh} 才能发现——
 * 否则框架会把已删的分区当成还在（该重建的被跳过），或把已存在的当成没有（重复 ADD 报错）。</p>
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

    /** 执行 DDL 用的数据源，同时也是 executeXxx 唯一的取连接途径（Builder 里没有别的引用） */
    @Getter
    private final JdbcTemplate jdbcTemplate;
    /** 库端真相的来源，方言无关接口，MySQL 实现查 information_schema.PARTITIONS */
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
     *
     * <p>失败处理是这里最需要留意的一点：读取元数据抛异常时按"该表没有分区"处理并把空集合
     * 缓存下来（降级而不是让业务 DDL 失败）。后果是<b>去重能力静默失效</b>——
     * 之后每次 addPartition 都查不到已有分区，补分区作业会退化成重复 ADD 而在库端报错。
     * 排查"分区明明存在却还在反复 ADD"时，应先怀疑这里有没有吞掉元数据查询异常（warn 级日志）。</p>
     *
     * @param tableName 表名，大小写不敏感（内部按小写建 key）
     * @return 该表已有分区名的可变集合；未分区或读取失败时为空集合
     */
    public Set<String> getPartitions(String tableName) {
        String key = tableName.toLowerCase();
        return cache.computeIfAbsent(key, tn -> {
            // 大小写不敏感比较：分区名在不同库/不同书写方式下大小写不一致，按名字比对不能区分大小写
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

    /**
     * 检查分区是否存在
     * <p>{@link PartitionBuilder} 的 ADD 去重就靠这一步；注意大小写不敏感。</p>
     */
    public boolean contains(String tableName, String partitionName) {
        return getPartitions(tableName).contains(partitionName);
    }

    /**
     * 添加分区到缓存（执行 ADD 成功后调用）
     * <p>只在 DDL 真正成功后写缓存，失败时不写——否则缓存会宣称一个库里不存在的分区，
     * 后续去重会跳过本该重建的分区。</p>
     */
    public void add(String tableName, String partitionName) {
        // 直接改 getPartitions 返回的可变集合：同一表的并发写没有加锁，
        // 多线程同时维护同一张表的分区时不应依赖这里的原子性（当前用法是单线程定时作业）
        getPartitions(tableName).add(partitionName);
        log.debug("缓存新增: {}.{}", tableName, partitionName);
    }

    /**
     * 从缓存移除分区（执行 DROP 成功后调用）
     * <p>必须与 DROP 配套：缓存里留着已删的分区名，重建同一时间窗口的分区时会被跳过，
     * 数据就无处可落（测试用例④同时断言"被删的消失"和"邻居仍在"）。</p>
     */
    public void remove(String tableName, String partitionName) {
        getPartitions(tableName).remove(partitionName);
        log.debug("缓存移除: {}.{}", tableName, partitionName);
    }

    /**
     * 强制刷新指定表的缓存（下次访问时重新从 DB 加载）
     * <p>丢掉内存状态、以库端为准重建，是应对"有人手工动过分区"的唯一手段。
     * 也用于 REORGANIZE 之后——原分区名消失、新名出现，增量 add/remove 推不出来，
     * 索性整体重读一次。</p>
     */
    public void refresh(String tableName) {
        cache.remove(tableName.toLowerCase());
    }

    /**
     * 清空全部缓存
     * <p>集成测试每个用例收尾都会调它：分区是库端状态，用例已经把表 DROP 了，
     * 缓存若还留着旧分区名，下一条测试的 ADD 会被误判为"已存在"而跳过，
     * 于是断言莫名通过。测试里与之配套的是 {@code JdbcTemplate.clearTableCache()}——
     * 那是另一层缓存（表结构/表存在性），两层都要作废，缺一层就会出现"线上不生效"式的假象。</p>
     */
    public void refreshAll() {
        cache.clear();
    }

    /**
     * 预加载指定表的分区（启动时批量预热）
     * <p>把 information_schema 查询的开销挪到启动阶段，避免第一次补分区时才发现连不上库；
     * 未分区的表预加载后同样留下空集合，等价于"确认过库里确实没有分区"。</p>
     */
    public void preload(String... tableNames) {
        for (String tn : tableNames) {
            getPartitions(tn);
        }
    }
}
