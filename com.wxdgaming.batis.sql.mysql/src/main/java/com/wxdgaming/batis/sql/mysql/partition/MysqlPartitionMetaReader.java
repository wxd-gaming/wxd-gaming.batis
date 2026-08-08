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
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-08-08
 */
@Slf4j
public class MysqlPartitionMetaReader implements PartitionMetaReader {

    public static final MysqlPartitionMetaReader INSTANCE = new MysqlPartitionMetaReader();

    private static final String SQL =
            "SELECT PARTITION_NAME FROM information_schema.PARTITIONS " +
            "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? " +
            "AND PARTITION_NAME IS NOT NULL " +
            "ORDER BY PARTITION_ORDINAL_POSITION";

    @Override
    public List<String> getPartitionNames(JdbcTemplate jdbcTemplate, String tableName) {
        List<String> names = new ArrayList<>();
        try (QueryRunner qr = new QueryRunner(jdbcTemplate)) {
            // 先获取当前数据库名
            String catalog = qr.query("SELECT DATABASE()", (ResultSet rs) -> {
                if (rs.next()) return rs.getString(1);
                return null;
            });
            if (catalog == null) {
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
