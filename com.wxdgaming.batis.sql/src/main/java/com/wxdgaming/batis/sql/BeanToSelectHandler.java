package com.wxdgaming.batis.sql;

import com.wxdgaming.batis.model.TableInfo;

import java.util.List;

/**
 * SELECT SQL 构建接口（方言 SPI）
 * <p>新增一个方言时，把这个接口当成"SELECT 侧的待办清单"来读：只有
 * {@link #buildSelectByIdSql} 与 {@link #buildSelectAllSql} 是强制实现的；
 * 其余三个 default 方法（{@link #quote}、{@link #appendPaging}、{@link #buildCountSql}）
 * 承载的是查询 DSL 渲染链路里的差异点，方言只覆盖与自己不兼容的那一个，
 * 其余继承默认值即可跑通整条 DSL——这是"差异点收敛到最少几个方法"的刻意设计。</p>
 * <p>默认方法按 MySQL 风格实现（反引号 + 全小写标识符、{@code LIMIT ? OFFSET ?}），
 * 目的是让 MySQL 方言零成本接入：{@code MysqlBeanToSelectHandler} 只实现了两个抽象方法，
 * 引用、分页、计数全部沿用这里的默认值。代价是 sql 层因此带了 MySQL 口味的默认值，
 * 所以接入 pgsql 时要在这里覆盖差异（双引号引用、必要时调整分页写法），
 * 而不是回到 sql 层的调用点加"如果是某种方言"的分支。</p>
 * <p>分层边界：本接口在 sql 层，向下依赖 core 的模型（TableInfo/ColumnInfo），
 * 向上被查询 DSL（{@code query.SelectSqlBuilder}）与 {@code BeanRunner} 调用；
 * core 侧不得出现任何 SQL 字面量。这条边界之所以重要：这里拼出的 SQL 文本会进 debug 日志、
 * 被测试逐字符断言、并可能成为缓存键的一部分，方言细节一旦漏进 core 或缓存模型，
 * 换一种数据库就得改核心代码。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public interface BeanToSelectHandler {

    /**
     * 构建按主键查询的 SQL（形如 SELECT * FROM {@code table} WHERE {@code pk} = ?）
     * <p>实现方必须保证两条，否则 {@code BeanRunner#queryById} 的参数绑定会静默错位：</p>
     * <ul>
     *   <li>占位符个数等于 {@code tableInfo} 里主键列的个数，且顺序严格按
     *       {@code getColumns()} 的迭代顺序——调用方用同一次迭代数出主键列数，
     *       再把单个 id 或 {@code Object[]} 按序绑上去，两边顺序必须一致；</li>
     *   <li>实体没有主键时快速失败（MySQL 实现抛 IllegalArgumentException）。
     *       不能退化成无条件全表查询：那会让"按主键取一条"变成"随机拿到一条"，
     *       而且在大表上是性能事故。</li>
     * </ul>
     *
     * @param tableInfo 由实体解析得到的表元数据
     * @return 只含占位符、不含任何主键字面量的参数化 SQL
     */
    String buildSelectByIdSql(TableInfo tableInfo);

    /**
     * 构建查询全部记录的 SQL（形如 SELECT * FROM {@code table}）
     * <p>除了服务于 {@code queryAll}，它还是旧版字符串条件 API 的拼接基底
     * （{@code buildSelectAllSql(table) + " WHERE " + whereSection}），
     * 因此返回值末尾不能带分号，也不能自带 WHERE / ORDER BY / 分页子句。</p>
     *
     * @param tableInfo 由实体解析得到的表元数据
     * @return 全表查询语句
     */
    String buildSelectAllSql(TableInfo tableInfo);

    /**
     * 标识符引用：把未加引号的表名/列名转成可以直接拼进 SQL 的形式
     * <p>查询 DSL 在列投影、FROM、ORDER BY、条件叶子上都会走这一个出口，
     * 所以它是"引号风格"的唯一决定点：想改风格只需覆盖本方法，DSL 与其余默认方法自动跟随。</p>
     * <p>实现方必须保证：</p>
     * <ul>
     *   <li>幂等且确定——同一标识符多次调用返回同一结果，SQL 文本会被日志与测试逐字符比对；</li>
     *   <li>返回值自带引号，调用方不会再包一层，也不负责转义标识符内的引号字符；</li>
     *   <li>与库端元数据口径一致——{@link DbMetaHandler} 查回的列名、表结构缓存的小写键
     *       都要能被这里生成的标识符命中。</li>
     * </ul>
     * <p>默认 MySQL 风格（反引号 + 转小写）。大小写敏感的方言（如 pgsql 用双引号保留原大小写）
     * 覆盖时要把"引号字符"和"大小写策略"一起决策：这两件事是一套的，只换引号会导致
     * 建表用小写、查询用原名的错配。</p>
     *
     * @param identifier 未引用的表名或列名
     * @return 该方言认可的标识符文本
     */
    default String quote(String identifier) {
        return "`" + identifier.toLowerCase() + "`";
    }

    /**
     * 追加分页子句，并把分页值按占位符出现顺序加入 params
     * <p>这里是"分页方言归属"的落点：LIMIT/OFFSET 的关键字顺序、是否允许只给 OFFSET、
     * 是否必须用占位符，全由本方法决定；DSL 只负责在 WHERE/ORDER BY 之后调用一次，
     * 然后把 sql 与 params 一起交给执行层。新增方言若分页语法不同，覆盖本方法即可，
     * 不必改动渲染骨架。</p>
     * <p>实现方必须遵守的不变式（{@code SelectQueryIT} 的参数顺序用例逐字符盯着它）：</p>
     * <ul>
     *   <li>只在 sql 末尾追加，不改写已生成的片段；</li>
     *   <li>params 的追加顺序必须与占位符在 SQL 中的出现顺序一一对应。默认形态是
     *       {@code [WHERE 展平参数..., LIMIT, OFFSET]}，即 limit 在 offset 之前；
     *       若方言写成 offset 在前（如 {@code OFFSET ? ROWS FETCH NEXT ? ROWS ONLY}），
     *       追加顺序也要换成 offset 在前，否则值会绑到错的占位符上，
     *       表现为"翻页结果偏移量莫名不对"，是最难查的一类 bug；</li>
     *   <li>limit / offset 为 null 或小于等于 0 时既不追加子句也不追加参数——
     *       "不分页"和"分页 0 条"是两回事。默认实现额外处理了"只给 offset"：MySQL 不接受
     *       没有 LIMIT 的 OFFSET，因此会补一个无上限的 {@code LIMIT 18446744073709551615}
     *       （该常量不占位符、不进参数，所以参数顺序不变）。</li>
     * </ul>
     * <p>默认实现把两个值都做成占位符绑定，分页数字因此不出现在 SQL 文本里，
     * 没有注入面，也便于语句复用。</p>
     *
     * @param sql    已渲染到 WHERE / ORDER BY 的语句缓冲，本方法只向其追加
     * @param params 参数列表，按占位符顺序追加值
     * @param limit  每页条数，null 或小于等于 0 表示不分页
     * @param offset 跳过条数，null 或小于等于 0 表示不跳过
     */
    default void appendPaging(StringBuilder sql, List<Object> params, Integer limit, Integer offset) {
        boolean hasLimit = limit != null && limit > 0;
        boolean hasOffset = offset != null && offset > 0;
        if (!hasLimit && !hasOffset) {
            return;
        }
        if (hasLimit) {
            sql.append(" LIMIT ?");
            params.add(limit);
        } else {
            // MySQL 的分页语法要求 OFFSET 必须伴随 LIMIT：只给 offset 时补一个"无上限"的 LIMIT。
            // 否则会渲染出 `... OFFSET ?`，运行期直接语法错误（而这是可渲染期就避免的事）。
            sql.append(" LIMIT 18446744073709551615");
        }
        if (hasOffset) {
            sql.append(" OFFSET ?");
            params.add(offset);
        }
    }

    /**
     * 构建计数语句主体（不含 WHERE 后缀）
     * <p>DSL 会把它与条件拼成 {@code SELECT COUNT(*) FROM t WHERE ...}，
     * 拼完之后不再补分页，所以实现里既不能自带 WHERE / ORDER BY，也不能塞分页子句：
     * 一旦带上 LIMIT，"总条数"就退化成"本页条数"，分页接口的 total 会跟着错，
     * 而这种错误在单页数据里根本看不出来。</p>
     * <p>实现方必须保证结果集是单行单列、且该列能被 JDBC 读成 Number：
     * 计数取值走的是"第一单元格 + Number 判定"，拿到非 Number（例如某些方言把计数
     * 报成字符串）会被当成 0，而不是抛错。需要列别名时在本方法里补齐即可，别名不影响取值。</p>
     * <p>标识符统一走 {@link #quote}，因此覆盖了引号风格的方言这里自动跟随，无需重复实现。</p>
     *
     * @param tableInfo 目标表元数据
     * @return 计数语句主体，末尾不带分号
     */
    default String buildCountSql(TableInfo tableInfo) {
        return "SELECT COUNT(*) FROM " + quote(tableInfo.getTableName());
    }

}
