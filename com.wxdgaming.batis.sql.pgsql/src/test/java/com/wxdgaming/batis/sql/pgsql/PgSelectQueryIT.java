package com.wxdgaming.batis.sql.pgsql;

import com.wxdgaming.batis.exception.TooManyResultsException;
import com.wxdgaming.batis.exception.UnknownColumnException;
import com.wxdgaming.batis.query.Criteria;
import com.wxdgaming.batis.query.Page;
import com.wxdgaming.batis.query.Pageable;
import com.wxdgaming.batis.query.Sort;
import com.wxdgaming.batis.sql.BeanRunner;
import com.wxdgaming.batis.sql.QueryRunner;
import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.math.BigDecimal;
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
 * PG 查询 DSL 集成测试（真库：嵌入式 PostgreSQL）
 * <p>条件渲染、括号优先级、count 忽略分页这些语义在 MySQL 的 {@code SelectQueryIT} 已充分覆盖，
 * 核心层实现也只有一份；本类不重复那套断言，只测<b>换到 PG 才会变</b>的四件事：</p>
 * <ol>
 *   <li>标识符引用形式是双引号且区分大小写规则不同于反引号（列名解析必须全小写）；</li>
 *   <li>分页尾巴：PG 的 LIMIT 是 bigint，MySQL 用 {@code LIMIT 18446744073709551615} 表达"只有 OFFSET"
 *       的写法在 PG 会报 22001，所以 PG 侧必须渲染裸 {@code OFFSET}；</li>
 *   <li>布尔列直接绑 Boolean（PG 的 BOOLEAN 不接受 0/1 整型参数）；</li>
 *   <li>DSL 复用事务连接（PG 的 MVCC 下，新连接看不到本事务未提交的写入）。</li>
 * </ol>
 * <p>数据前置：每个用例前灌入 10 条账户，balance = i*10，vipLevel = i 模 3，i 大于 8 的两条 frozen=true。
 * 断言里的数字全部来自这个分布，改种子必须同步改断言。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-14
 **/
@TestMethodOrder(OrderAnnotation.class)
class PgSelectQueryIT {

    @RegisterExtension
    static PgsqlExtension pg = new PgsqlExtension();

    static PgsqlTemplate template;

    static PgsqlTemplate template() {
        if (template != null) return template;
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(pg.getJdbcUrl());
        config.setUsername(pg.getUsername());
        config.setPassword(pg.getPassword());
        config.setDriverClassName("org.postgresql.Driver");
        template = new PgsqlTemplate(config);
        return template;
    }

    @BeforeAll
    static void setUp() {
        template().checkTable(PgAccountEntity.class);
    }

    /** 种子数据：清表后灌入 10 条，构造出可被条件精确切片的分布 */
    @BeforeEach
    void seed() {
        clean();
        try (BeanRunner br = template().beanRunner()) {
            for (int i = 1; i <= 10; i++) {
                PgAccountEntity a = new PgAccountEntity();
                a.setAccountName("sel-user-" + i);
                a.setBalance(new BigDecimal(i * 10));
                a.setVipLevel(i % 3);
                a.setFrozen(i > 8);
                br.insertBean(a);
            }
        }
    }

    @AfterEach
    void clean() {
        try (QueryRunner qr = new QueryRunner(template())) {
            qr.update("DELETE FROM \"t_pg_account\"");
        }
    }

    /**
     * 用例①：渲染产物用双引号标识符，且参数顺序不变式是 [WHERE…, LIMIT, OFFSET]（只渲染不执行）。
     * <p>意义：这条是"快照测试"，把 PG 的引用形式与分页尾巴一次钉住。
     * 参数一旦与占位符错位，结果不是报错而是静默查错页/错条件，比崩溃更难发现。</p>
     */
    @Test
    @Order(1)
    void renderedSqlUsesDoubleQuotesAndParamOrder() {
        var q = template().select(PgAccountEntity.class)
                .where(Criteria.where("vipLevel").eq(2))
                .limit(5).offset(10);
        assertEquals("SELECT * FROM \"t_pg_account\" WHERE \"vip_level\" = ? LIMIT ? OFFSET ?", q.toSql());
        assertEquals(List.of(2, 5, 10), q.toParams(), "参数顺序 = [WHERE..., LIMIT, OFFSET]");
    }

    /**
     * 用例②：只有 OFFSET 没有 LIMIT 时必须渲染裸 OFFSET（PG 专属分支）。
     * <p>这是 MySQL 与 PG 分页写法真正分岔的地方：MySQL 方言用
     * {@code LIMIT 18446744073709551615 OFFSET ?} 表达"跳过前 N 行、其余都要"，
     * 而 PG 的 LIMIT 是 bigint，那个无符号上限超出范围，语句会在解析期报 22001；
     * PG 本身允许省略 LIMIT，所以正确的渲染是只留 OFFSET。
     * 动作与断言：10 条数据按 id 升序跳过前 7 条，应真库返回 3 条而不是抛异常——
     * 如果渲染退化成 MySQL 形式，这里就是一条 22001 而不是断言失败。</p>
     */
    @Test
    @Order(2)
    void offsetWithoutLimitRendersBareOffset() {
        List<PgAccountEntity> tail = template().select(PgAccountEntity.class)
                .orderBy(Sort.by("id"))
                .offset(7)
                .list();
        assertEquals(3, tail.size(), "裸 OFFSET 应跳过前 7 条返回后 3 条");
        assertEquals("sel-user-8", tail.getFirst().getAccountName());
    }

    /**
     * 用例③：分页不重不漏（4 页 × 每页 3 条）。
     * <p>断言三层：首页元数据 total=10 / totalPages=4 / hasNext；末页只剩 1 条且 hasNext 为假；
     * 每页 id 塞进 Set 时必须 {@code add} 返回 true——偏移量算错或排序不稳定导致的翻页重叠会在这里被抓。
     * 排序显式按 id（唯一且单调），这是分页稳定性的前提。</p>
     */
    @Test
    @Order(3)
    void pagingNoOverlapNoGaps() {
        Set<Long> seen = new HashSet<>();
        int total = 0;
        for (int page = 1; page <= 4; page++) {
            Page<PgAccountEntity> result = template().select(PgAccountEntity.class)
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
            for (PgAccountEntity a : result.getContent()) {
                assertTrue(seen.add(a.getId()), "分页结果出现重复行: id=" + a.getId());
            }
            total += result.getContent().size();
        }
        assertEquals(10, total, "分页总数应等于表行数");
        assertEquals(10, seen.size());
    }

    /**
     * 用例④：布尔条件直接绑 Boolean 参数。
     * <p>MySQL 的布尔列是 TINYINT(1)，条件值绑 1/0 也能过；PG 的 BOOLEAN 是真正的布尔类型，
     * 绑 Integer 会在解析参数时报"操作符不存在: boolean = integer"(42883)。
     * 断言 frozen=true 命中 2 条、frozen=false 命中 8 条，两个方向都走真库。</p>
     */
    @Test
    @Order(4)
    void booleanCriterionBindsBooleanValue() {
        assertEquals(2, template().select(PgAccountEntity.class)
                .where(Criteria.where("frozen").eq(true)).count());
        assertEquals(8, template().select(PgAccountEntity.class)
                .where(Criteria.where("frozen").eq(false)).count());
    }

    /**
     * 用例⑤：AND 里嵌 OR 的括号优先级与手写 PG SQL 对拍。
     * <p>参照物用 PG 的原生写法（布尔直接写 {@code = true}，标识符双引号）。
     * 少一对括号时 SQL 变成 {@code frozen AND vip=2 OR balance>=90}，结果集静默变大且不报错，
     * 所以必须用"对拍"而不是硬编码数字。</p>
     */
    @Test
    @Order(5)
    void orUnderAndPrecedence() {
        Criteria c = Criteria.where("frozen").eq(true)
                .and(Criteria.anyOf(
                        Criteria.where("vipLevel").eq(2),
                        Criteria.where("balance").ge(90)));
        List<PgAccountEntity> viaDsl = template().select(PgAccountEntity.class).where(c).list();
        try (QueryRunner qr = new QueryRunner(template())) {
            var viaSql = qr.query(
                    "SELECT * FROM \"t_pg_account\" WHERE \"frozen\" = true"
                            + " AND (\"vip_level\" = 2 OR \"balance\" >= 90)",
                    com.wxdgaming.batis.sql.ResultSetHandlers.list());
            assertEquals(viaSql.size(), viaDsl.size(), "DSL 的 OR 括号优先级必须与手写 SQL 一致");
        }
    }

    /**
     * 用例⑥：count / exists / one 三种读语义。
     * <p>count 走的是另一条 SQL（SELECT COUNT(*)，必须丢掉 ORDER BY 与 LIMIT/OFFSET，
     * 否则计数被分页污染）；one 在命中多于 1 条时必须抛 {@link TooManyResultsException}，
     * 只取第一行会让条件写宽的调用方静默拿到错误数据。</p>
     */
    @Test
    @Order(6)
    void countExistsAndOneSemantics() {
        assertEquals(10, template().select(PgAccountEntity.class).count());
        assertTrue(template().select(PgAccountEntity.class)
                .where(Criteria.where("accountName").eq("sel-user-7")).exists());
        assertFalse(template().select(PgAccountEntity.class)
                .where(Criteria.where("accountName").eq("ghost")).exists());
        assertNotNull(template().select(PgAccountEntity.class)
                .where(Criteria.where("accountName").eq("sel-user-3")).one());
        assertNull(template().select(PgAccountEntity.class)
                .where(Criteria.where("accountName").eq("nobody")).one());
        assertThrows(TooManyResultsException.class, () -> template().select(PgAccountEntity.class)
                .where(Criteria.where("vipLevel").eq(1)).one());
    }

    /**
     * 用例⑦：列投影在 PG 上同样按 Java 字段名解析。
     * <p>动作：只投影 id 与 accountName。断言被投影的字段有值、未投影的保持 Java 默认值——
     * 这正是投影的代价，使用方必须知道只能读被选中的列（JSON/BLOB 大字段列因此可以整列跳过）。
     * accountName 能解析成 {@code "account_name"} 说明引用前的归一化没有把驼峰直接拼进去：
     * PG 的双引号标识符区分大小写，一旦保留驼峰就是 42703 未知列。</p>
     */
    @Test
    @Order(7)
    void projectionResolvesFieldNamesToLowerCaseColumns() {
        PgAccountEntity a = template().select(PgAccountEntity.class)
                .columns("id", "accountName")
                .where(Criteria.where("accountName").eq("sel-user-4"))
                .one();
        assertNotNull(a);
        assertEquals("sel-user-4", a.getAccountName());
        assertNull(a.getTags(), "未投影的列保持默认值");
    }

    /**
     * 用例⑧：字段名解析错时保留驼峰会被 PG 拒绝（本地提前失败的必要性）。
     * <p>动作：条件里用实体上不存在的字段名。断言：抛 {@link UnknownColumnException}，
     * 而不是把任意字符串拼进 SQL 让库端报 42703。
     * 这条与用例⑦配对：⑦证明解析正确时能查，⑧证明解析不出来时不会把原始串漏进标识符位置。</p>
     */
    @Test
    @Order(8)
    void unknownFieldRejectedBeforeExecution() {
        assertThrows(UnknownColumnException.class, () -> template().select(PgAccountEntity.class)
                .where(Criteria.where("no_such_field").eq(1)).list());
    }

    /**
     * 用例⑨：DSL 复用当前事务连接（PG 的 MVCC 下新连接读不到未提交写入）。
     * <p>动作：事务内插入后立刻用 DSL 查它，随后标记回滚。
     * 断言两段：事务内 exists 为真（同连接、同快照才可见）；回滚后 exists 为假。
     * 如果 DSL 入口从池里另借一条连接，前半段就会失败——
     * 这是"事务内查不到刚写的对象"这类线上问题的根因，正反两头都要锁。</p>
     */
    @Test
    @Order(9)
    void dslSelectSharesTransactionConnection() {
        template().transactionTemplate().executeWithoutResult(status -> {
            try (BeanRunner br = template().beanRunner()) {
                PgAccountEntity fresh = new PgAccountEntity();
                fresh.setAccountName("sel-tx-visible");
                fresh.setBalance(BigDecimal.ONE);
                br.insertBean(fresh);
                assertTrue(br.select(PgAccountEntity.class)
                        .where(Criteria.where("accountName").eq("sel-tx-visible"))
                        .exists(), "事务内应读到自己未提交的写入");
                status.setRollbackOnly();
            }
        });
        assertFalse(template().select(PgAccountEntity.class)
                .where(Criteria.where("accountName").eq("sel-tx-visible")).exists());
    }

    /**
     * 用例⑩：JdbcTemplate 便捷入口与 DSL 走同一套渲染。
     * <p>断言数字来自种子分布：frozen=false 有 8 条；vipLevel=0 有 3 条（序号 3/6/9），
     * 每页 2 条时首页 2 条而 total 仍是 3，说明 count 没被分页污染。</p>
     */
    @Test
    @Order(10)
    void jdbcTemplateConvenienceEntries() {
        List<PgAccountEntity> list = template().queryList(PgAccountEntity.class,
                Criteria.where("frozen").eq(false));
        assertEquals(8, list.size());
        assertEquals(8, template().count(PgAccountEntity.class, Criteria.where("frozen").eq(false)));
        assertNotNull(template().queryOne(PgAccountEntity.class,
                Criteria.where("accountName").eq("sel-user-6")));
        Page<PgAccountEntity> page = template().queryPage(PgAccountEntity.class,
                Criteria.where("vipLevel").eq(0), Pageable.of(1, 2, Sort.by("id")));
        assertEquals(2, page.getContent().size());
        assertEquals(3, page.getTotal());
    }

}
