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

    @Test
    @Order(2)
    void inBetweenAndComposition() {
        List<AccountEntity> list = template.select(AccountEntity.class)
                .where(Criteria.where("accountName").in("sel-user-2", "sel-user-5"))
                .and(Criteria.where("balance").between(20, 60))
                .list();
        assertEquals(2, list.size());
    }

    @Test
    @Order(3)
    void likeMatchesWrappedPattern() {
        // "user-1" 命中 sel-user-1 与 sel-user-10
        long hits = template.select(AccountEntity.class)
                .where(Criteria.where("accountName").like("user-1"))
                .count();
        assertEquals(2, hits);
    }

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

    @Test
    @Order(6)
    void countAndExists() {
        assertEquals(10, template.select(AccountEntity.class).count());
        assertTrue(template.select(AccountEntity.class)
                .where(Criteria.where("accountName").eq("sel-user-7")).exists());
        assertFalse(template.select(AccountEntity.class)
                .where(Criteria.where("accountName").eq("ghost")).exists());
    }

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

    @Test
    @Order(9)
    void unknownColumnRejectedBeforeExecution() {
        assertThrows(UnknownColumnException.class,
                () -> template.select(AccountEntity.class)
                        .where(Criteria.where("no_such_field").eq(1)).list());
    }

    @Test
    @Order(10)
    void toSqlAndParamsFollowInvariant() {
        var q = template.select(AccountEntity.class)
                .where(Criteria.where("vipLevel").eq(2))
                .limit(5).offset(10);
        assertEquals("SELECT * FROM `t_account` WHERE `vip_level` = ? LIMIT ? OFFSET ?", q.toSql());
        assertEquals(List.of(2, 5, 10), q.toParams(), "参数顺序 = [WHERE..., LIMIT, OFFSET]");
    }

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
