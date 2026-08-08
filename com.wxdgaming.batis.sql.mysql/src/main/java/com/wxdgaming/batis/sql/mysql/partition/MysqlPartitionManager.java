package com.wxdgaming.batis.sql.mysql.partition;

import com.wxdgaming.batis.sql.partition.*;
import lombok.extern.slf4j.Slf4j;

import java.util.stream.Collectors;

/**
 * MySQL 方言的分区管理实现
 *
 * <p>负责将 {@link PartitionConfig} 转换为 MySQL 风格的 ALTER TABLE ... PARTITION 语句。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-08-08
 */
@Slf4j
public class MysqlPartitionManager implements PartitionManager {

    public static final MysqlPartitionManager INSTANCE = new MysqlPartitionManager();

    @Override
    public String buildCreatePartition(PartitionConfig config) {
        String tn = config.getTableName();
        StringBuilder sb = new StringBuilder(512);

        if (config.getPartitionCount() > 0 && config.getPartitions().isEmpty()) {
            // HASH / KEY 分区
            sb.append("ALTER TABLE `").append(tn).append("`\n");
            sb.append("PARTITION BY ").append(config.getType().name().replace('_', ' '))
              .append("(").append(config.getExpression()).append(")");
            sb.append("\nPARTITIONS ").append(config.getPartitionCount());
        } else {
            // RANGE / LIST 分区
            sb.append("ALTER TABLE `").append(tn).append("`\n");
            sb.append("PARTITION BY ");
            appendRangeOrListPrefix(sb, config.getType(), config.getExpression());
            sb.append("\n(\n");
            sb.append(config.getPartitions().stream()
                    .map(d -> "  PARTITION " + d.getName() + " " + d.getValue())
                    .collect(Collectors.joining(",\n")));
            sb.append("\n)");
        }
        sb.append(";");
        return sb.toString();
    }

    @Override
    public String buildAddPartition(PartitionConfig config) {
        String tn = config.getTableName();
        StringBuilder sb = new StringBuilder();
        sb.append("ALTER TABLE `").append(tn).append("` ADD PARTITION (\n");
        sb.append(config.getPartitions().stream()
                .map(d -> "  PARTITION " + d.getName() + " " + d.getValue())
                .collect(Collectors.joining(",\n")));
        sb.append("\n);");
        return sb.toString();
    }

    @Override
    public String buildDropPartition(PartitionConfig config) {
        String tn = config.getTableName();
        StringBuilder sb = new StringBuilder();
        sb.append("ALTER TABLE `").append(tn).append("` ");
        sb.append(config.getDropNames().stream()
                .map(n -> "DROP PARTITION `" + n + "`")
                .collect(Collectors.joining(", ")));
        sb.append(";");
        return sb.toString();
    }

    @Override
    public String buildTruncatePartition(PartitionConfig config) {
        return "ALTER TABLE `" + config.getTableName()
                + "` TRUNCATE PARTITION `" + config.getTruncateName() + "`;";
    }

    @Override
    public String buildReorganizePartition(PartitionConfig config) {
        String tn = config.getTableName();
        StringBuilder sb = new StringBuilder();
        sb.append("ALTER TABLE `").append(tn).append("` REORGANIZE PARTITION ")
          .append(config.getReorganizeOldName()).append(" INTO (\n");
        sb.append(config.getReorganizeDefs().stream()
                .map(d -> "  PARTITION " + d.getName() + " " + d.getValue())
                .collect(Collectors.joining(",\n")));
        sb.append("\n);");
        return sb.toString();
    }

    /** RANGE / RANGE_COLUMNS / LIST / LIST_COLUMNS 前缀 */
    private void appendRangeOrListPrefix(StringBuilder sb, PartitionType type, String expression) {
        switch (type) {
            case RANGE_COLUMNS:
                sb.append("RANGE COLUMNS (").append(expression).append(")");
                break;
            case LIST_COLUMNS:
                sb.append("LIST COLUMNS (").append(expression).append(")");
                break;
            default:
                sb.append(type.name().replace('_', ' '))
                  .append("(").append(expression).append(")");
        }
    }
}
