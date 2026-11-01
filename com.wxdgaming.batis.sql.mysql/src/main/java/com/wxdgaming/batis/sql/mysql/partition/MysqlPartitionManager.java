package com.wxdgaming.batis.sql.mysql.partition;

import com.wxdgaming.batis.sql.partition.*;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * MySQL 方言的分区管理实现
 *
 * <p>负责将 {@link PartitionConfig} 转换为 MySQL 风格的 ALTER TABLE ... PARTITION 语句。</p>
 *
 * <h3>这里是方言语法的唯一落点</h3>
 * <p>反引号标识符、{@code PARTITION BY} 关键字、{@code RANGE COLUMNS}/{@code LIST COLUMNS}、
 * {@code PARTITIONS n} 这些只有 MySQL 认识的东西都只在本类出现，
 * 因此它是 {@code PartitionManager} 接口在 sql.mysql 模块的实现（测试用例⑬钉住这层关系）。
 * 换 pgsql 时新写一个实现即可，配置模型与 Builder 都不用动。</p>
 *
 * <p>无状态、纯字符串拼接，因此用 {@link #INSTANCE} 单例共享，天然线程安全。</p>
 *
 * <h3>渲染出的两种形态</h3>
 * <pre>{@code
 * // RANGE / LIST / *_COLUMNS：逐个分区带边界子句
 * ALTER TABLE `t_daily_log`
 * PARTITION BY RANGE(YEAR(log_time)*10000 + MONTH(log_time)*100 + DAY(log_time))
 * (
 *   PARTITION p20260801 VALUES LESS THAN (20260802),
 *   PARTITION pmax VALUES LESS THAN MAXVALUE
 * );
 *
 * // HASH / KEY / LINEAR_*：只写分区数，分区名由库端生成（p0…p{n-1}）
 * ALTER TABLE `t_daily_log`
 * PARTITION BY HASH(id)
 * PARTITIONS 8;
 * }</pre>
 *
 * <p>换行与缩进纯粹为了日志里的 DDL 可读（{@code log.info("执行分区SQL")} 会整段打印），
 * 所以测试用例⑥只断言"包含哪些关键字和分区名"而不对整串比对。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-08-08
 */
@Slf4j
public class MysqlPartitionManager implements PartitionManager {

    /** 单例：本类无可变状态，全工程共用一个即可（Builder 里以接口类型持有） */
    public static final MysqlPartitionManager INSTANCE = new MysqlPartitionManager();

    /**
     * 表名/分区名标识符白名单：DDL 无法参数化，这些名字是直接拼进语句的，
     * 只收字母数字与 {@code _ $}（分区名按约定是 {@code p+数字}/{@code pmax}）。
     * 反引号、分号、空白、引号等 DDL 逃逸字符一律在渲染前拒绝，而不是发到库端撞语法错。
     */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9_$]+");

    /**
     * 分区表达式白名单：只允许列名/函数名、数字、括号、逗号与算术运算符。
     * <p>表达式是 DDL 里唯一无法参数化的自由文本，原样拼接等于把注入面交给调用方。
     * 字符白名单挡住分号与引号，但挡不住 {@code id) PARTITIONS 8} 这种"闭合括号再追加子句"——
     * 所以还要做括号配平（深度不得为负、结尾必须归零），并拒绝 SQL 关键字。</p>
     */
    private static final Pattern SAFE_EXPRESSION = Pattern.compile("[A-Za-z0-9_$(),+\\-*/%. ]+");

    /** 表达式里不该出现的 SQL 关键字（下划线命名的列名不会命中：{@code update_time} 没有词边界） */
    private static final Pattern EXPRESSION_KEYWORD = Pattern.compile(
            "\\b(?:union|select|insert|update|delete|drop|alter|create|partition|partitions"
                    + "|values|into|outfile|dumpfile|sleep|benchmark|load_file)\\b",
            Pattern.CASE_INSENSITIVE);

    /** 单个边界值：数字或字符串字面量（字符串内只允许 {@code ''} 转义，反斜杠一律拒绝） */
    private static final String VALUE_ITEM = "MAXVALUE|[-+]?\\d+(?:\\.\\d+)?|'(?:[^'\\\\]|'')*'";

    /**
     * ADD/REORGANIZE 的边界子句：{@code VALUES LESS THAN (...)} / {@code VALUES LESS THAN MAXVALUE} /
     * {@code VALUES IN (...)}。整串必须吻合，多一个分号或注释都会拒绝。
     */
    private static final Pattern PARTITION_VALUE = Pattern.compile(
            "VALUES\\s+(?:LESS\\s+THAN|IN)\\s*(?:\\s*\\(\\s*(?:" + VALUE_ITEM + ")"
                    + "(?:\\s*,\\s*(?:" + VALUE_ITEM + "))*\\s*\\)|(?:" + VALUE_ITEM + "))",
            Pattern.CASE_INSENSITIVE);

    private static void requireIdentifier(String kind, String name) {
        if (name == null || name.isEmpty() || !SAFE_IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException(kind + " 含非法字符（只允许字母数字与 _ $）: " + name);
        }
    }

    /** 校验分区定义：名字是安全标识符，边界子句是完整的 VALUES ... 形态（防 DDL 注入与语法漏拼） */
    private static void requirePartitionDefs(String kind, List<PartitionDef> defs) {
        for (PartitionDef def : defs) {
            requireIdentifier(kind, def.getName());
            requirePartitionValue(def);
        }
    }

    private static void requirePartitionValue(PartitionDef def) {
        String value = def.getValue();
        if (value == null || value.isBlank() || !PARTITION_VALUE.matcher(value.trim()).matches()) {
            throw new IllegalArgumentException("分区 " + def.getName()
                    + " 的边界定义不合法（期望形如 VALUES LESS THAN (20260802) / "
                    + "VALUES LESS THAN MAXVALUE / VALUES IN (1, 2)）: " + value);
        }
    }

    /** 表达式校验：非空、字符白名单、括号配平、无 SQL 关键字 */
    private static void requireExpression(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("分区表达式不能为空");
        }
        if (!SAFE_EXPRESSION.matcher(expression).matches()) {
            throw new IllegalArgumentException(
                    "分区表达式含非法字符（只允许列名/函数、数字、括号与算术运算符）: " + expression);
        }
        if (EXPRESSION_KEYWORD.matcher(expression).find()) {
            throw new IllegalArgumentException("分区表达式含 SQL 关键字，拒绝拼进 DDL: " + expression);
        }
        int depth = 0;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (--depth < 0) {
                    throw new IllegalArgumentException("分区表达式括号不配对: " + expression);
                }
            }
        }
        if (depth != 0) {
            throw new IllegalArgumentException("分区表达式括号不配对: " + expression);
        }
    }

    /**
     * 渲染建分区 DDL，按"有没有边界值"分两个分支。
     * <p>判定条件是"分区数大于 0 且分区列表为空"，对应 HASH/KEY 一类
     * 只给个数、不给名字的形态；其余（包括给了分区列表的 RANGE/LIST）走逐个列名的一支。
     * 两支不能混用：对 HASH 类型又塞进分区列表时，这里会按第二支渲染出
     * {@code PARTITION BY HASH(x) ( PARTITION … )}，MySQL 不接受这种写法。</p>
     */
    @Override
    public String buildCreatePartition(PartitionConfig config) {
        requireIdentifier("表名", config.getTableName());
        requireExpression(config.getExpression());
        if (config.getType() == null) {
            throw new IllegalArgumentException("分区类型不能为空（请调用 rangeBy/listBy/hashBy/keyBy/partitionBy）");
        }
        String tn = config.getTableName();
        StringBuilder sb = new StringBuilder(512);

        if (config.getPartitionCount() > 0 && config.getPartitions().isEmpty()) {
            // HASH / KEY 分区
            // 枚举名里的下划线换成空格：LINEAR_HASH → LINEAR HASH，正好是 MySQL 的写法
            sb.append("ALTER TABLE `").append(tn).append("`\n");
            sb.append("PARTITION BY ").append(config.getType().name().replace('_', ' '))
              .append("(").append(config.getExpression()).append(")");
            // 分区数直接来自配置，测试用例⑫断言这个数字就是防"参数没接上、默默用了默认值"
            sb.append("\nPARTITIONS ").append(config.getPartitionCount());
        } else {
            // RANGE / LIST 分区
            requirePartitionDefs("分区名", config.getPartitions());
            sb.append("ALTER TABLE `").append(tn).append("`\n");
            sb.append("PARTITION BY ");
            appendRangeOrListPrefix(sb, config.getType(), config.getExpression());
            sb.append("\n(\n");
            // 分区定义按 config 里的顺序原样输出：RANGE 要求边界单调，顺序由 Builder 负责保证
            sb.append(config.getPartitions().stream()
                    .map(d -> "  PARTITION `" + d.getName() + "` " + d.getValue())
                    .collect(Collectors.joining(",\n")));
            sb.append("\n)");
        }
        sb.append(";");
        return sb.toString();
    }

    /**
     * 渲染 ADD PARTITION：只往分区列表尾部追加，不重建已有数据。
     * <p>与 {@link #buildCreatePartition} 的区别就是代价差异——前者是整表重分布，
     * 这一支才是日常滚动补分区用的。子句语法同建表时一致，所以 MAXVALUE 之类的
     * 尾部限制同样适用：尾部已有兜底分区时不能再用本语句往前插分区。</p>
     */
    @Override
    public String buildAddPartition(PartitionConfig config) {
        requireIdentifier("表名", config.getTableName());
        requirePartitionDefs("分区名", config.getPartitions());
        String tn = config.getTableName();
        StringBuilder sb = new StringBuilder();
        sb.append("ALTER TABLE `").append(tn).append("` ADD PARTITION (\n");
        sb.append(config.getPartitions().stream()
                .map(d -> "  PARTITION `" + d.getName() + "` " + d.getValue())
                .collect(Collectors.joining(",\n")));
        sb.append("\n);");
        return sb.toString();
    }

    /**
     * 渲染 DROP PARTITION：按名字删除，多个名字合并进同一条 ALTER。
     * <p>用 {@code DROP PARTITION} 加分区名（名字用反引号包裹）而不是序号定位，是"点谁删谁"的实现基础——
     * Builder 不保存位置信息，位置一旦成为依据就会删错分区（测试用例④防的就是这个）。</p>
     * <p>一次 ALTER 带多个 DROP 子句：只触发一次分区元数据变更，比循环发多条更省。</p>
     * <p>注意会连带删掉分区内所有数据，只想清数据请走 {@link #buildTruncatePartition}。</p>
     */
    @Override
    public String buildDropPartition(PartitionConfig config) {
        requireIdentifier("表名", config.getTableName());
        config.getDropNames().forEach(n -> requireIdentifier("分区名", n));
        String tn = config.getTableName();
        StringBuilder sb = new StringBuilder();
        sb.append("ALTER TABLE `").append(tn).append("` ");
        sb.append(config.getDropNames().stream()
                .map(n -> "DROP PARTITION `" + n + "`")
                .collect(Collectors.joining(", ")));
        sb.append(";");
        return sb.toString();
    }

    /**
     * 渲染 TRUNCATE PARTITION：清空数据但保留分区定义与边界。
     * <p>与 DROP 的关键差异就是"定义还在"，因此 Builder 执行完不必动缓存。
     * 当前一支只处理一个分区名（{@code truncateName} 是单值）。</p>
     */
    @Override
    public String buildTruncatePartition(PartitionConfig config) {
        requireIdentifier("表名", config.getTableName());
        requireIdentifier("分区名", config.getTruncateName());
        return "ALTER TABLE `" + config.getTableName()
                + "` TRUNCATE PARTITION `" + config.getTruncateName() + "`;";
    }

    /**
     * 渲染 REORGANIZE PARTITION ... INTO (...)：用新的若干分区替换原有分区并重分布数据。
     * <p>这是唯一能改动已有边界的语句，也是尾部有 MAXVALUE 兜底分区、
     * ADD 无法再插入分区时的替代手段。语义上等价于一次局部数据重写，
     * Builder 执行后走整体 refresh 来对齐分区名视图。</p>
     */
    @Override
    public String buildReorganizePartition(PartitionConfig config) {
        requireIdentifier("表名", config.getTableName());
        requireIdentifier("原分区名", config.getReorganizeOldName());
        requirePartitionDefs("分区名", config.getReorganizeDefs());
        String tn = config.getTableName();
        StringBuilder sb = new StringBuilder();
        sb.append("ALTER TABLE `").append(tn).append("` REORGANIZE PARTITION `")
          .append(config.getReorganizeOldName()).append("` INTO (\n");
        sb.append(config.getReorganizeDefs().stream()
                .map(d -> "  PARTITION `" + d.getName() + "` " + d.getValue())
                .collect(Collectors.joining(",\n")));
        sb.append("\n);");
        return sb.toString();
    }

    /**
     * RANGE / RANGE_COLUMNS / LIST / LIST_COLUMNS 前缀。
     * <p>{@code *_COLUMNS} 是 MySQL 特有的两支：它们要求括号里是<b>裸列名</b>
     * （可以直接用 DATETIME、字符串列），因此对应的 {@code expression} 必须是列名而不是
     * {@code YEAR(x)*10000+…} 这类算式；反过来 RANGE 只接受整数表达式，
     * 这正是 {@code MysqlPartitionExpr} 要把日期编码成数字的原因。</p>
     * <p>default 分支复用枚举名并把下划线转空格，覆盖 RANGE/LIST/HASH/KEY/LINEAR_*。</p>
     */
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
