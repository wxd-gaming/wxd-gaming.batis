package com.wxdgaming.batis.sql.mysql;

import com.wxdgaming.batis.exception.TooManyResultsException;
import com.wxdgaming.batis.exception.UnknownColumnException;
import com.wxdgaming.batis.exception.BadSqlGrammarException;
import com.wxdgaming.batis.query.Criteria;
import com.wxdgaming.batis.query.Page;
import com.wxdgaming.batis.query.Pageable;
import com.wxdgaming.batis.query.Sort;
import com.wxdgaming.batis.sql.BeanRunner;
import com.wxdgaming.batis.sql.JdbcTemplate;
import com.wxdgaming.batis.sql.QueryRunner;
import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 查询 DSL（Criteria + SelectQuery + 分页）集成测试（MariaDB4j 真库）
 * <p>数据前置：每个用例前重新灌入 10 条账户（见 {@link #seed()}），
 * 余额 10/20/…/100，vipLevel 为序号 i 模 3 的余数（0/1/2 循环），i 大于 8 的两条 frozen=true。
 * 这个分布是各用例断言数字的唯一依据，改种子数据必须同步改断言。</p>
 * <p>验证重点依次是：条件渲染与参数化、AND 下 OR 的括号优先级、分页不重不漏、
 * count/exists/one 语义、列投影、未知列提前失败、参数顺序不变式、便捷入口、
 * DSL 复用事务连接，以及旧 whereSection 字符串 API 的注入防护。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
@TestMethodOrder(OrderAnnotation.class)
class SelectQueryIT {

    @RegisterExtension
    static MariaDB4jExtension maria = new MariaDB4jExtension();

    static MysqlTemplate template;

    @BeforeAll
    static void setUp() {
        if (template != null) return;
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(maria.getJdbcUrl());
        config.setUsername("root");
        config.setPassword("");
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        template = new MysqlTemplate(config);
        template.checkTable(AccountEntity.class);
    }

    /**
     * 种子数据：清表后插入 10 条账户。
     * <p>刻意构造出可被条件精确切片的分布：vipLevel 按 0/1/2 轮转（各 3/4/3 条）、
     * balance 等于序号的 10 倍（便于 between / 比较类断言）、
     * 最后两条 frozen=true（布尔条件与 not-eq 断言用）。</p>
     */
    @BeforeEach
    void seed() {
        clean();
        try (BeanRunner br = template.beanRunner()) {
            for (int i = 1; i <= 10; i++) {
                AccountEntity a = new AccountEntity();
                a.setAccountName("sel-user-" + i);
                a.setBalance(new BigDecimal(i * 10));
                a.setVipLevel(i % 3);          // 0/1/2 循环
                a.setFrozen(i > 8);            // 9、10 冻结
                br.insertBean(a);
            }
        }
    }

    @AfterEach
    void clean() {
        try (QueryRunner qr = new QueryRunner(template)) {
            qr.update("DELETE FROM `t_account`");
        }
    }

    /**
     * 用例①：eq 等值条件 + 按字段名排序，返回类型化列表。
     * <p>动作：{@code where("vipLevel").eq(1)} 并按 id 升序。断言命中 4 条
     * （序号 1/4/7/10 模 3 余 1，见种子分布），且首尾两条名字符合预期。
     * 这里同时验证了 DSL 的两件基础事：Java 字段名 vipLevel 被解析成列 vip_level、
     * orderBy 用字段名也能排序；如果列名解析错，真库会直接抛 1054 而不是断言失败。</p>
     */
    @Test
    @Order(1)
    void whereEqReturnsMatchedList() {
        List<AccountEntity> list = template.select(AccountEntity.class)
                .where(Criteria.where("vipLevel").eq(1))
                .orderBy(Sort.by("id"))
                .list();
        // vip = i%3 == 1 → i ∈ {1,4,7,10}
        assertEquals(4, list.size());
        assertEquals("sel-user-1", list.getFirst().getAccountName());
        assertEquals("sel-user-10", list.get(3).getAccountName());
    }

    /**
     * 用例②：IN 与 BETWEEN 两个叶子条件被 AND 组合。
     * <p>动作：accountName in (sel-user-2, sel-user-5) 且 balance between 20 and 60。
     * 断言命中 2 条。意义：IN 的多占位符展开（{@code IN (?, ?)}）与参数逐个落位、
     * BETWEEN 的两个参数顺序，都和条件树里的书写顺序一致；
     * 若参数与占位符数量或顺序错位，真库会返回错误行数或抛类型异常。</p>
     */
    @Test
    @Order(2)
    void inBetweenAndComposition() {
        List<AccountEntity> list = template.select(AccountEntity.class)
                .where(Criteria.where("accountName").in("sel-user-2", "sel-user-5"))
                .and(Criteria.where("balance").between(20, 60))
                .list();
        assertEquals(2, list.size());
    }

    /**
     * 用例③：like 的百分号由框架包裹，值仍然走参数绑定。
     * <p>动作：{@code like("user-1")}，期望命中 sel-user-1 和 sel-user-10（contains 语义）。
     * 断言 count = 2。意义：调用方只写关键字、不写 % 通配符，
     * 一是方言中立（不同库转义/拼接写法不同），二是通配符不会混进参数值里造成注入或误匹配；
     * 命中 2 条而不是 1 条，说明两端都加了 %。</p>
     */
    @Test
    @Order(3)
    void likeMatchesWrappedPattern() {
        // "user-1" 命中 sel-user-1 与 sel-user-10
        long hits = template.select(AccountEntity.class)
                .where(Criteria.where("accountName").like("user-1"))
                .count();
        assertEquals(2, hits);
    }

    /**
     * 用例④：AND 里嵌 OR 时必须自动加括号，优先级与手写 SQL 完全一致。
     * <p>动作：构造 {@code frozen = true AND (vipLevel = 2 OR balance >= 90)}，
     * 再用一条手写 SQL 跑同样语义的查询作为参照物，断言两边行数相等。
     * 意义：布尔优先级是条件树渲染最容易出错的地方——
     * 少一对括号，SQL 就变成 {@code (frozen AND vip) OR balance>=90}，结果集静默变大且不报错。
     * 这里用"与手写 SQL 对拍"而不是硬编码一个数字，是因为数字错了也可能是种子数据变了，
     * 而对拍只要语义不一致就会失败。</p>
     */
    @Test
    @Order(4)
    void orUnderAndPrecedence() {
        // frozen=true OR vipLevel=2 且 balance >= 20 的行数，与拆两条 SQL 的结果一致
        Criteria c = Criteria.where("frozen").eq(true)
                .and(Criteria.anyOf(
                        Criteria.where("vipLevel").eq(2),
                        Criteria.where("balance").ge(90)));
        List<AccountEntity> viaDsl = template.select(AccountEntity.class).where(c).list();
        try (QueryRunner qr = new QueryRunner(template)) {
            var viaSql = qr.query(
                    "SELECT * FROM `t_account` WHERE `frozen` = 1"
                            + " AND (`vip_level` = 2 OR `balance` >= 90)",
                    com.wxdgaming.batis.sql.ResultSetHandlers.list());
            assertEquals(viaSql.size(), viaDsl.size(),
                    "DSL 的 OR 括号优先级必须与手写 SQL 一致");
        }
    }

    /**
     * 用例⑤：分页不重不漏（本类最重要的一个用例）。
     * <p>动作：10 条数据按每页 3 条翻完 4 页（末页 1 条），用 Set 收集所有 id。
     * 断言三层：
     * <ol>
     *   <li>第 1 页的元数据：total=10、totalPages=4、hasNext 为真；末页 hasNext 为假且只剩 1 条
     *   —— 校验 count 与分页页数的推导（向上取整）而不是 LIMIT/OFFSET 本身；</li>
     *   <li>{@code seen.add(id)} 必须返回 true —— 一旦某行在两页里重复出现（偏移量算错、
     *   或排序不稳定导致翻页重叠），这里立刻失败；</li>
     *   <li>累计条数 = 10 且去重后仍 = 10 —— 既没漏也没重。</li>
     * </ol>
     * 排序固定按 id（唯一且单调），这是分页稳定性的前提，故用例里必须显式传入 Sort。</p>
     */
    @Test
    @Order(5)
    void pagingNoOverlapNoGaps() {
        Set<Long> seen = new HashSet<>();
        int total = 0;
        for (int page = 1; page <= 4; page++) {
            Page<AccountEntity> result = template.select(AccountEntity.class)
                    .pageResult(Pageable.of(page, 3, Sort.by("id")));
            if (page == 1) {
                assertEquals(10, result.getTotal());
                assertEquals(4, result.getTotalPages());
                assertTrue(result.hasNext());
            }
            if (page == 4) {
                assertFalse(result.hasNext());
                assertEquals(1, result.getContent().size());
            }
            for (AccountEntity a : result.getContent()) {
                assertTrue(seen.add(a.getId()), "分页结果出现重复行: id=" + a.getId());
            }
            total += result.getContent().size();
        }
        assertEquals(10, total, "分页总数应等于表行数");
        assertEquals(10, seen.size());
    }

    /**
     * 用例⑥：count 与 exists 的语义与"忽略分页"。
     * <p>动作：无条件 count 应为全表 10；对存在/不存在的名字分别 exists 判断。
     * 断言：true / false。意义：count 渲染走的是另一条 SQL（SELECT COUNT(*)，
     * 必须丢弃 ORDER BY 与 LIMIT/OFFSET，否则计数会被分页影响），
     * 而 exists 只关心"有没有"，用条件命中的第一条即可判定，不该把整行数据读回来。</p>
     */
    @Test
    @Order(6)
    void countAndExists() {
        assertEquals(10, template.select(AccountEntity.class).count());
        assertTrue(template.select(AccountEntity.class)
                .where(Criteria.where("accountName").eq("sel-user-7")).exists());
        assertFalse(template.select(AccountEntity.class)
                .where(Criteria.where("accountName").eq("ghost")).exists());
    }

    /**
     * 用例⑦：one 查询的三种结果状态。
     * <p>断言三种情况：恰好 1 条 → 返回实体；0 条 → 返回 null（"没有"不是错误，调用方可直接判空）；
     * 多于 1 条 → 抛 {@link TooManyResultsException}。
     * 第三种最容易被漏：如果实现只是"取第一行"，那么条件写宽时程序会静默拿到错误的那条数据，
     * 而按业务意图（"这个条件应该唯一"）它必须失败。</p>
     */
    @Test
    @Order(7)
    void oneReturnsUniqueNullOrThrows() {
        AccountEntity single = template.select(AccountEntity.class)
                .where(Criteria.where("accountName").eq("sel-user-3")).one();
        assertNotNull(single);
        assertNull(template.select(AccountEntity.class)
                .where(Criteria.where("accountName").eq("nobody")).one());
        assertThrows(TooManyResultsException.class,
                () -> template.select(AccountEntity.class)
                        .where(Criteria.where("vipLevel").eq(1)).one());
    }

    /**
     * 用例⑧：列投影（只 SELECT 指定列）。
     * <p>动作：columns("id", "accountName") 取一条。断言：投影出来的字段有值。
     * 意义：一是大字段表（JSON/BLOB）可以只取需要的列，避免整行反序列化开销；
     * 二是列名解析在这里同样要支持 Java 字段名（accountName → account_name）；
     * 未投影的列不会被 SELECT，映射后保持 Java 默认值（0 / null），
     * 所以投影结果只能读被选中的字段，这是使用方需要知道的约束。</p>
     */
    @Test
    @Order(8)
    void columnsProjectionWorks() {
        AccountEntity a = template.select(AccountEntity.class)
                .columns("id", "accountName")
                .where(Criteria.where("accountName").eq("sel-user-4"))
                .one();
        assertNotNull(a);
        assertEquals("sel-user-4", a.getAccountName());
    }

    /**
     * 用例⑨：未知列在"发 SQL 之前"就失败。
     * <p>动作：条件里用了一个实体上根本不存在的字段名 no_such_field。
     * 断言：抛 {@link UnknownColumnException}（而不是 BadSqlGrammar 或空结果）。
     * 意义：列名解析发生在渲染阶段，属于本地校验，零网络开销就能拒绝拼错的字段，
     * 错误信息带表名和字段名，比数据库回一句 1054 更早也更可读。
     * 同时它也守住了"DSL 不会把任意字符串当列名拼进 SQL"这条边界。</p>
     */
    @Test
    @Order(9)
    void unknownColumnRejectedBeforeExecution() {
        assertThrows(UnknownColumnException.class,
                () -> template.select(AccountEntity.class)
                        .where(Criteria.where("no_such_field").eq(1)).list());
    }

    /**
     * 用例⑩：参数顺序不变式 [WHERE 参数…, LIMIT, OFFSET]（DSL 的"快照测试"）。
     * <p>动作：一条带等值条件 + limit + offset 的查询，只渲染不执行，检查产物文本与参数数组。
     * 断言两点：SQL 骨架逐字符等于期望串（反引号、LIMIT ? OFFSET ? 的位置都不能变）；
     * 参数序列严格是 [条件值, limit, offset]。
     * 意义：这条不变式是整个分页渲染的命门——绑定顺序一旦和占位符顺序错位，
     * 结果不是报错而是"静默查错页/错条件"，比崩溃更危险。
     * 同时渲染产物本身可见（toSql / toParams 只渲染不执行），也是排查手段：
     * 出问题时能直接拿到将要执行的 SQL 和参数。</p>
     */
    @Test
    @Order(10)
    void toSqlAndParamsFollowInvariant() {
        var q = template.select(AccountEntity.class)
                .where(Criteria.where("vipLevel").eq(2))
                .limit(5).offset(10);
        assertEquals("SELECT * FROM `t_account` WHERE `vip_level` = ? LIMIT ? OFFSET ?", q.toSql());
        assertEquals(List.of(2, 5, 10), q.toParams(), "参数顺序 = [WHERE..., LIMIT, OFFSET]");
    }

    /**
     * 用例⑪：JdbcTemplate 上的四个便捷入口（queryList / count / queryOne / queryPage）。
     * <p>意义：它们只是 DSL 的语法糖，本用例证明"不显式创建 SelectQuery"也能拿到同样的结果，
     * 且四个入口走的是同一套渲染逻辑。断言里的数字都来自种子分布：
     * frozen=false 有 8 条；vipLevel 为 0 的有 3 条（序号 3/6/9），
     * 每页 2 条时第一页正好 2 条、total 仍是 3（说明 count 没被分页污染）。</p>
     */
    @Test
    @Order(11)
    void jdbcTemplateConvenienceEntries() {
        List<AccountEntity> list = template.queryList(AccountEntity.class,
                Criteria.where("frozen").eq(false));
        assertEquals(8, list.size());
        assertEquals(8, template.count(AccountEntity.class, Criteria.where("frozen").eq(false)));
        AccountEntity one = template.queryOne(AccountEntity.class,
                Criteria.where("accountName").eq("sel-user-6"));
        assertNotNull(one);
        Page<AccountEntity> page = template.queryPage(AccountEntity.class,
                Criteria.where("vipLevel").eq(0), Pageable.of(1, 2, Sort.by("id")));
        assertEquals(2, page.getContent().size());
        assertEquals(3, page.getTotal()); // i%3==0 → {3,6,9}
    }

    /**
     * 用例⑫：DSL 查询复用当前事务连接（读写同事务才可见）。
     * <p>动作：事务内插入 sel-tx-visible 后立刻用 DSL 查它，最后标记回滚。
     * 断言两段：事务内 exists 为真（查询走的是事务那条连接，所以能看到自己未提交的写入）；
     * 事务回滚后 exists 为假。
     * 意义：如果 DSL 入口从池里另借一条连接，前半段就会失败（新连接看不到未提交数据），
     * 这是"事务内查询查不到刚写的对象"这类线上疑难杂症的根因，故从正向和反向两头都锁住。</p>
     */
    @Test
    @Order(12)
    void beanRunnerSelectEntrySharesTransactionConnection() {
        template.transactionTemplate().executeWithoutResult(status -> {
            try (BeanRunner br = template.beanRunner()) {
                AccountEntity fresh = new AccountEntity();
                fresh.setAccountName("sel-tx-visible");
                fresh.setBalance(BigDecimal.ONE);
                br.insertBean(fresh);
                // 事务内未提交，DSL 查询走同一连接 → 同批可见
                assertTrue(br.select(AccountEntity.class)
                        .where(Criteria.where("accountName").eq("sel-tx-visible"))
                        .exists());
                status.setRollbackOnly();
            }
        });
        assertFalse(template.select(AccountEntity.class)
                .where(Criteria.where("accountName").eq("sel-tx-visible")).exists());
    }

    /**
     * 用例⑬：已废弃的字符串 whereSection API 的纵深防御。
     * <p>背景：老接口把调用方拼好的字符串直接接在 WHERE 之后，是最典型的注入面；
     * 但不能直接删（存量代码在用），所以策略是"保留 + 标记废弃 + 加守卫"。</p>
     * <p>动作与断言：正常的条件片段（列名 = ? 带参数）仍然可用，返回 1 条，保证不破坏兼容；
     * 带 {@code ;}（多语句）、{@code UNION}（拖库）特征的片段被拒绝并抛异常，
     * 说明守卫在 SQL 真正执行前就生效。</p>
     */
    @Test
    @Order(13)
    void deprecatedWhereSectionGuarded() {
        try (BeanRunner br = template.beanRunner()) {
            // 正常条件仍可用
            assertEquals(1, br.queryList(AccountEntity.class,
                    "`account_name` = ?", "sel-user-1").size());
            // 注入特征被纵深防御拒绝
            assertThrows(BadSqlGrammarException.class, () ->
                    br.queryList(AccountEntity.class, "`id` = 1; DROP TABLE `t_account`"));
            assertThrows(BadSqlGrammarException.class, () ->
                    br.queryOne(AccountEntity.class, "`id` = 1 UNION SELECT * FROM `t_account`"));
        }
    }

}
