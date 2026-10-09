package com.wxdgaming.batis.sql.mysql.partition;

import com.wxdgaming.batis.sql.JdbcTemplate;
import com.wxdgaming.batis.sql.QueryRunner;
import com.wxdgaming.batis.sql.partition.PartitionMetaReader;
import lombok.extern.slf4j.Slf4j;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * MySQL 分区元数据读取器
 *
 * <p>查询 {@code information_schema.PARTITIONS} 获取已有分区名。</p>
 *
 * <p>这是分区链路的"读侧方言"，与写侧的 {@link MysqlPartitionManager} 一对：
 * 本类实现的是 sql 层的方言无关接口 {@link PartitionMetaReader}（测试用例⑬钉住），
 * 换 pgsql 时只需换一个实现（pg 要查 pg_partitions / pg_class），
 * {@link com.wxdgaming.batis.sql.partition.PartitionCache} 与上层都不用动。</p>
 *
 * <p>它是"以库为准"的真相来源：DBA 手工加过分区、应用带旧缓存重启，
 * 都靠这里的一次重读纠正过来。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-08-08
 */
@Slf4j
public class MysqlPartitionMetaReader implements PartitionMetaReader {

    /** 无状态，单例即可（{@code PartitionCache} 构造时注入的就是它） */
    public static final MysqlPartitionMetaReader INSTANCE = new MysqlPartitionMetaReader();

    /**
     * 按库名 + 表名精确取分区名。
     * <p>{@code TABLE_SCHEMA = ?} 这一条不能省：information_schema 是<b>全实例</b>视图，
     * 不限定库名时同名表在别的库里分过区，就会把别人的分区名当成本表的已有分区，
     * 于是补分区被误判为"已存在"而跳过，本表却始终没有这个分区。</p>
     * <p>{@code PARTITION_NAME IS NOT NULL} 用来识别"未分区的表"：MySQL 对非分区表
     * 也会返回一行且分区名为 NULL，不过滤就会得到 {@code [null]} 而不是空列表。</p>
     * <p>按 {@code PARTITION_ORDINAL_POSITION} 排序即库端分区顺序（RANGE 的单调顺序）；
     * 目前上层只取名字集合做去重，这个顺序信息暂时没被用到，保留是为了将来读边界。</p>
     */
    private static final String SQL =
            "SELECT PARTITION_NAME FROM information_schema.PARTITIONS " +
            "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? " +
            "AND PARTITION_NAME IS NOT NULL " +
            "ORDER BY PARTITION_ORDINAL_POSITION";

    /**
     * 读取指定表的分区名列表。
     * <p>失败时<b>不抛异常</b>而是返回空列表（含连接不上、无权限、库名取不到等），
     * 让 DDL 主流程继续走。代价是 {@link com.wxdgaming.batis.sql.partition.PartitionCache}
     * 会把"读失败"当成"这表没分区"并缓存空集合，从而静默失去去重能力，
     * 表现为反复 ADD 同一分区然后在库端报错。
     * 这里的日志是 debug 级，生产默认级别看不到——排查时记得调级。</p>
     */
    @Override
    public List<String> getPartitionNames(JdbcTemplate jdbcTemplate, String tableName) {
        List<String> names = new ArrayList<>();
        try (QueryRunner qr = new QueryRunner(jdbcTemplate)) {
            // 先获取当前数据库名
            // 走 SELECT DATABASE() 而不是配置项：连接串里的库才是本轮 DDL 真正作用的那个库，
            // 手工传入的库名一旦与实际连接不一致，就会读到别的库的分区
            String catalog = qr.query("SELECT DATABASE()", (ResultSet rs) -> {
                if (rs.next()) return rs.getString(1);
                return null;
            });
            if (catalog == null) {
                // 连库名都取不到（如连接未指定库），此时无法安全限定 TABLE_SCHEMA，直接放弃本次读取
                log.debug("无法获取当前数据库名，跳过查询分区信息");
                return names;
            }

            // 查询分区名
            qr.query(SQL, new Object[]{catalog, tableName}, (ResultSet rs) -> {
                while (rs.next()) {
                    names.add(rs.getString("PARTITION_NAME"));
                }
                return null;
            });
        } catch (Exception e) {
            log.debug("查询表 {} 的分区信息失败: {} (cause: {})",
                    tableName, e.getMessage(), e.getClass().getSimpleName());
        }
        return names;
    }
}
