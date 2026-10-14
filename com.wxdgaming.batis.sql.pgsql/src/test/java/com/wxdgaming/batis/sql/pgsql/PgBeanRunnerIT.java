package com.wxdgaming.batis.sql.pgsql;

import com.wxdgaming.batis.query.Criteria;
import com.wxdgaming.batis.sql.BeanRunner;
import com.wxdgaming.batis.sql.QueryRunner;
import com.wxdgaming.batis.sql.ResultSetHandlers;
import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PG Bean CRUD 集成测试（真库：嵌入式 PostgreSQL）
 * <p>与 MySQL 的 {@code BeanRunnerTest} 相比，这里不重复"CRUD 能不能跑通"，
 * 只测那些<b>换成 PG 就可能变味</b>的路径：</p>
 * <ol>
 *   <li>自增主键回写取自列名而非结果集首列（pgjdbc 的 RETURNING * 会把表首列排在最前）；</li>
 *   <li>时间族落到 DATE / TIME / TIMESTAMP 三种互不相容的列类型；</li>
 *   <li>复合主键的 queryById / deleteById 把两个键都绑进 WHERE；</li>
 *   <li>IDENTITY 用 BY DEFAULT，因此显式给定主键值仍能插入；</li>
 *   <li>TRUNCATE 后序列归零（RESTART IDENTITY）与 MySQL 的 AUTO_INCREMENT 重置对齐。</li>
 * </ol>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-14
 **/
@TestMethodOrder(OrderAnnotation.class)
class PgBeanRunnerIT {

    static final Logger log = LoggerFactory.getLogger(PgBeanRunnerIT.class);

    @RegisterExtension
    static PgsqlExtension pg = new PgsqlExtension();

    static PgsqlTemplate template;

    /**
     * 模板按需构造。每个用例前 checkTable 幂等建表，故本类不依赖 {@code PgSchemaDriftIT} 留下的表。
     */
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

    @BeforeEach
    void ensureTables() {
        template().checkTable(PgAccountEntity.class);
        template().checkTable(PgOrderItemEntity.class);
        template().checkTable(PgDdlDriftEntity.class);
    }

    @AfterEach
    void cleanTables() {
        try (QueryRunner qr = new QueryRunner(template())) {
            qr.update("DELETE FROM \"t_pg_account\"");
            qr.update("DELETE FROM \"t_pg_order_item\"");
            qr.update("DELETE FROM \"t_pg_ddl_drift\"");
        }
    }

    // ==================== 自增主键 ====================

    /**
     * 用例①：自增主键回写必须按列名取，而不是取生成键结果集的第一列。
     * <p>前置：{@link PgAccountEntity} 故意把 {@code account_name} 放在 {@code id} 之前。
     * PG 的 {@code RETURN_GENERATED_KEYS} 实现为 {@code RETURNING *}，于是结果集首列是账号名字符串。
     * 动作：{@code insertBean} 后比对返回值与实体字段。
     * 断言：返回的键是正数（若误取首列，账号名根本转不成 Number，会在收窄处报错），
     * 且实体 id 与该键一致；再用 queryById 以该 id 读回同一行——
     * 读得回来才证明回写的确实是主键，而不是某个巧合的数字。</p>
     */
    @Test
    @Order(1)
    void identityKeyIsFetchedByColumnNameNotFirstColumn() {
        try (BeanRunner br = template().beanRunner()) {
            PgAccountEntity a = newAccount("gen-key-user");
            Number genId = br.insertBean(a);
            assertNotNull(genId, "IDENTITY 主键应回传");
            assertTrue(genId.longValue() > 0, "生成键应为正数，实际 " + genId);
            assertEquals(genId.longValue(), a.getId(), "insertBean 应把主键写回实体");

            PgAccountEntity back = br.queryById(PgAccountEntity.class, genId);
            assertNotNull(back, "按回写的主键应能读回该行");
            assertEquals("gen-key-user", back.getAccountName());
        }
    }

    /**
     * 用例②：IDENTITY 用 BY DEFAULT，所以显式给主键值仍可插入。
     * <p>动作：绕过框架直接用裸 SQL 写入 id = 9001 的行，再按该主键读回。
     * 意义：建表若写成 {@code GENERATED ALWAYS AS IDENTITY}（PG 的 SQL 标准默认值），
     * 任何"按业务主键补数据/迁移历史行"的写入都会被 428C1 拒绝，
     * 而 MySQL 的 AUTO_INCREMENT 从不这么严格——这正是 PG 方言必须折叠成 BY DEFAULT 的原因，
     * 结构层面由 {@code PgSchemaDriftIT} 断言 identity_generation，行为层面由本用例断言。</p>
     */
    @Test
    @Order(2)
    void explicitPrimaryKeyValueIsAccepted() {
        try (QueryRunner qr = new QueryRunner(template())) {
            qr.update("INSERT INTO \"t_pg_account\" (\"account_name\", \"id\") VALUES (?, ?)",
                    new Object[]{"manual-id-user", 9001L});
        }
        try (BeanRunner br = template().beanRunner()) {
            PgAccountEntity back = br.queryById(PgAccountEntity.class, 9001L);
            assertNotNull(back, "显式主键应能写入并读回");
            assertEquals("manual-id-user", back.getAccountName());
        }
    }

    // ==================== 类型化往返 ====================

    /**
     * 用例③：全档位实体的写读往返（本类最重的一个用例）。
     * <p>动作：给 {@link PgDdlDriftEntity} 的每个字段都赋值后插入，再按主键读回逐字段比对。
     * 之所以用这张"档位表"而不是账号表：它的列正好覆盖 PG 的三种时间列
     * （date / time / timestamp）、bytea、text、numeric 与 smallint/integer/bigint，
     * 一条 SQL 就能把 {@code PgsqlBeanValueHandler} 的全部转换分支在真库上跑一遍。</p>
     * <p>三处断言特意选了只有 PG 会失败的值：</p>
     * <ul>
     *   <li>{@code dateOnly} 与 {@code timeOnly} 分别读回 LocalDate / LocalTime——
     *       写入端若把它们统一拼成 Timestamp，PG 会在绑参时就报 42804，整条插入根本进不来；</li>
     *   <li>{@code dateTime} 带微秒（.123456）：PG 的 TIMESTAMP 存到微秒，
     *       写入端若按毫秒重建 Timestamp，这里读回的是 .123000 而不是 .123456；</li>
     *   <li>{@code hugeNumber} 超过 long 范围：转成 longValue 会静默截断，读回就对不上。</li>
     * </ul>
     * <p>BigDecimal 用 compareTo：库端 scale 与构造 scale 可能不同（10.00 与 10.0），
     * equals 会得到一个看着像 bug 的假失败。</p>
     */
    @Test
    @Order(3)
    void fullTypeRoundTrip() {
        PgDdlDriftEntity e = new PgDdlDriftEntity();
        e.setShortText("短字符串");
        e.setArticle("正文");
        e.setBigArticle("中正文");
        e.setNovel("长正文");
        e.setTags(List.of("x", "y", "z"));
        e.setPayloadSmall(new byte[]{1, 2, 3});
        e.setPayloadMedium(new byte[]{4, 5, 6, 7});
        e.setPayloadLarge(new byte[]{8, 9});
        e.setFlag(true);
        e.setTiny((byte) 7);
        e.setSmall((short) 700);
        e.setNum(70000);
        e.setBig(7_000_000_000L);
        e.setReal(1.5f);
        e.setDbl(2.25);
        e.setExact(new BigDecimal("10.00"));
        e.setHugeNumber(new BigInteger("123456789012345678901234567890"));
        e.setUtilDate(new java.util.Date(1_770_000_000_000L));
        e.setDateTime(LocalDateTime.of(2026, 3, 14, 15, 9, 26, 123_456_000));
        e.setDateOnly(LocalDate.of(2026, 3, 14));
        e.setTimeOnly(LocalTime.of(15, 9, 26));
        e.setLetter('A');
        e.setUid(UUID.fromString("11111111-2222-3333-4444-555555555555"));
        e.setExplicitDecimal(new BigDecimal("12.34"));
        e.setExplicitInt(42);
        e.setFrozen(true);
        e.setStartDate(LocalDate.of(2026, 1, 1));

        try (BeanRunner br = template().beanRunner()) {
            Number genId = br.insertBean(e);
            assertTrue(genId.longValue() > 0);

            PgDdlDriftEntity back = br.queryById(PgDdlDriftEntity.class, genId);
            assertNotNull(back);
            assertEquals("短字符串", back.getShortText());
            assertEquals(List.of("x", "y", "z"), back.getTags(), "JSON 列应按声明类型反解");
            assertArrayEquals(new byte[]{1, 2, 3}, back.getPayloadSmall(), "bytea 应原样取回");
            assertArrayEquals(new byte[]{4, 5, 6, 7}, back.getPayloadMedium());
            assertTrue(back.isFlag());
            assertTrue(back.isFrozen());
            assertEquals((byte) 7, back.getTiny());
            assertEquals((short) 700, back.getSmall());
            assertEquals(70000, back.getNum());
            assertEquals(7_000_000_000L, back.getBig());
            assertEquals(1.5f, back.getReal(), 0.0f);
            assertEquals(2.25, back.getDbl(), 0.0);
            assertEquals(0, new BigDecimal("10.00").compareTo(back.getExact()));
            assertEquals(new BigInteger("123456789012345678901234567890"), back.getHugeNumber(),
                    "超过 long 的 BigInteger 不能被截断");
            assertEquals(UUID.fromString("11111111-2222-3333-4444-555555555555"), back.getUid());
            assertEquals('A', back.getLetter());
            assertEquals(42, back.getExplicitInt());
            assertEquals(0, new BigDecimal("12.34").compareTo(back.getExplicitDecimal()));

            // 三种 PG 时间列各自回读到各自的 Java 类型
            assertEquals(LocalDate.of(2026, 3, 14), back.getDateOnly(), "DATE 列应回读成 LocalDate");
            assertEquals(LocalTime.of(15, 9, 26), back.getTimeOnly(), "TIME 列应回读成 LocalTime");
            assertEquals(LocalDate.of(2026, 1, 1), back.getStartDate());
            assertEquals(LocalDateTime.of(2026, 3, 14, 15, 9, 26, 123_456_000), back.getDateTime(),
                    "TIMESTAMP 的微秒位不应在写入端被丢掉");
            assertEquals(1_770_000_000_000L, back.getUtilDate().getTime());
        }
    }

    /**
     * 用例④：库端默认值真的生效，且未赋值的时间/JSON 列保持 NULL。
     * <p>为什么用裸 SQL：库端默认值只在列被 INSERT 语句省略时才生效，
     * 而框架的 INSERT 会把实体字段值（包括 null）逐个绑进去，走不出这条路。
     * 这条用例是 {@code PgsqlTableCreateHandler.defaultLiteral} 的行为证据：
     * 实体上按 MySQL 习惯写的 {@code defaultValue = "0"} 被折叠成 {@code DEFAULT false} 之后，
     * PG 才真的接受了它并在此后每次省略该列时给出 false——
     * 修复前是建表即报 42804（boolean 列不接受 integer 默认表达式），整张表都建不出来。</p>
     */
    @Test
    @Order(4)
    void columnDefaultsMaterializeInsideTheTable() {
        try (QueryRunner qr = new QueryRunner(template())) {
            qr.update("INSERT INTO \"t_pg_account\" (\"account_name\") VALUES (?)",
                    new Object[]{"default-user"});
        }
        try (BeanRunner br = template().beanRunner()) {
            PgAccountEntity back = br.select(PgAccountEntity.class)
                    .where(Criteria.where("accountName").eq("default-user"))
                    .one();
            assertNotNull(back);
            assertEquals(0, new BigDecimal("0.00").compareTo(back.getBalance()));
            assertEquals(0, back.getVipLevel());
            assertFalse(back.isFrozen(), "布尔列的 MySQL 习惯默认值 0 应落成 false");
            assertNull(back.getLastLoginTime(), "未赋值的 TIMESTAMP 列应保持 null");
            assertNull(back.getRegisterDate(), "未赋值的 DATE 列应保持 null");
            assertNull(back.getTags(), "未赋值的 JSON 列应保持 null");

            PgAccountEntity withTime = newAccount("timed-user");
            withTime.setLastLoginTime(LocalDateTime.of(2026, 10, 14, 8, 30, 0));
            withTime.setRegisterDate(LocalDate.of(2026, 1, 2));
            br.insertBean(withTime);
            PgAccountEntity timeBack = br.queryById(PgAccountEntity.class, withTime.getId());
            assertEquals(LocalDateTime.of(2026, 10, 14, 8, 30, 0), timeBack.getLastLoginTime());
            assertEquals(LocalDate.of(2026, 1, 2), timeBack.getRegisterDate());
        }
    }

    // ==================== 批量 / 更新 / 删除 ====================

    /**
     * 用例⑤：批量插入逐条回写主键，queryAll 条数与顺序一致。
     * <p>批量走 JDBC batch，是与单条完全不同的分支；这里同时守住"没漏写没重写"和
     * "每条都拿到了自己的 id"（若批量分支忘记回填，ids 会全 0 而被抓住）。
     * 顺序断言依赖单表无并发写入。</p>
     */
    @Test
    @Order(5)
    void batchInsertWritesBackEveryKey() {
        try (BeanRunner br = template().beanRunner()) {
            List<PgAccountEntity> beans = new ArrayList<>();
            for (int i = 1; i <= 5; i++) {
                PgAccountEntity a = newAccount("batch-user-" + i);
                a.setVipLevel(i);
                beans.add(a);
            }
            br.insertBeanList(beans);

            List<PgAccountEntity> all = br.queryAll(PgAccountEntity.class);
            assertEquals(5, all.size());
            for (int i = 0; i < 5; i++) {
                assertTrue(beans.get(i).getId() > 0, "第" + (i + 1) + "条批量插入后应拿到主键");
                assertEquals(all.get(i).getAccountName(), beans.get(i).getAccountName());
                assertEquals(beans.get(i).getId(), all.get(i).getId());
                assertEquals(i + 1, all.get(i).getVipLevel());
            }
        }
    }

    /**
     * 用例⑥：一条实体的完整生命周期 插入 → 查 → 改 → 查 → 删 → COUNT。
     * <p>UPDATE 的 SET/WHERE 列序不变式在 {@code PgsqlDdlTextTest} 已有文本级断言，
     * 这里补上真库证据：更新只影响 1 行、新值确实落在这一行上、删除后库端 COUNT 归零
     * （COUNT 由数据库给出，不受框架内存状态影响）。</p>
     */
    @Test
    @Order(6)
    void beanLifecycleOnRealDb() {
        try (BeanRunner br = template().beanRunner()) {
            PgAccountEntity a = newAccount("lifecycle-user");
            br.insertBean(a);
            long id = a.getId();

            PgAccountEntity queried = br.queryById(PgAccountEntity.class, id);
            assertNotNull(queried);
            queried.setAccountName("lifecycle-user-renamed");
            queried.setVipLevel(3);
            queried.setFrozen(true);
            queried.setBalance(new BigDecimal("49.95"));
            assertEquals(1, br.updateBean(queried), "按主键更新应影响 1 行");

            PgAccountEntity after = br.queryById(PgAccountEntity.class, id);
            assertEquals("lifecycle-user-renamed", after.getAccountName());
            assertEquals(3, after.getVipLevel());
            assertTrue(after.isFrozen());
            assertEquals(0, new BigDecimal("49.95").compareTo(after.getBalance()));
            assertEquals(id, after.getId(), "更新不应改动主键本身");

            assertEquals(1, br.deleteBean(after));
            Number count = (Number) br.getQueryRunner().query(
                    "SELECT COUNT(*) FROM \"t_pg_account\"", ResultSetHandlers.firstCell());
            assertEquals(0, count.intValue(), "删除后 COUNT 应为 0");
        }
    }

    // ==================== 复合主键 ====================

    /**
     * 用例⑦：复合主键的 queryById 与 deleteById 必须把两个键都绑进 WHERE。
     * <p>动作：插入同一订单的两条明细（order_id 相同、sku 不同），
     * 用 {@code Object[]{orderId, sku}} 读回，再用同一实体删除。
     * 断言：读到的是 sku 匹配的那一条而不是"首列匹配的第一条"，
     * 且删除只影响 1 行（只按 order_id 删会一次带走两行，这里正是那类缺陷的探测器）。</p>
     */
    @Test
    @Order(7)
    void compositeKeyQueryAndDeleteBindAllColumns() {
        try (BeanRunner br = template().beanRunner()) {
            PgOrderItemEntity a = item(5001L, "SKU-A", 2, "9.90");
            PgOrderItemEntity b = item(5001L, "SKU-B", 1, "1.00");
            br.insertBean(a);
            br.insertBean(b);

            PgOrderItemEntity back = br.queryById(PgOrderItemEntity.class, new Object[]{5001L, "SKU-B"});
            assertNotNull(back, "复合主键应能按两个键定位");
            assertEquals("SKU-B", back.getSkuCode());
            assertEquals(1, back.getQuantity());

            assertEquals(1, br.deleteBean(back), "按复合主键删除只应影响 1 行");
            assertEquals(1, br.queryAll(PgOrderItemEntity.class).size());
            assertNull(br.queryById(PgOrderItemEntity.class, new Object[]{5001L, "SKU-B"}));
        }
    }

    // ==================== 清空 / 截断 ====================

    /**
     * 用例⑧：TRUNCATE 后序列归零，与 MySQL 的 AUTO_INCREMENT 重置行为对齐。
     * <p>动作：插 3 条 → {@code truncateTable} → 再插 1 条。
     * 断言：新行主键为 1。PG 的 {@code TRUNCATE} 默认<b>不</b>重置序列，
     * 少了 {@code RESTART IDENTITY} 这里就会拿到 4；
     * 反过来，如果实现带上了 {@code CASCADE}，外键引用它的表会被连带清空（本模块刻意不做这个决定，
     * 由 {@code PgSchemaDriftIT} 的索引/约束用例从结构侧兜住）。</p>
     */
    @Test
    @Order(8)
    void truncateRestartsIdentitySequence() {
        try (BeanRunner br = template().beanRunner()) {
            for (int i = 1; i <= 3; i++) {
                br.insertBean(newAccount("trunc-" + i));
            }
        }
        template().truncateTable(PgAccountEntity.class);
        try (BeanRunner br = template().beanRunner()) {
            assertEquals(0, br.queryAll(PgAccountEntity.class).size(), "TRUNCATE 应清空数据");
            PgAccountEntity a = newAccount("trunc-again");
            br.insertBean(a);
            assertEquals(1L, a.getId(), "TRUNCATE ... RESTART IDENTITY 后主键应回到 1");
        }
    }

    // ==================== 辅助 ====================

    private static PgAccountEntity newAccount(String name) {
        PgAccountEntity a = new PgAccountEntity();
        a.setAccountName(name);
        a.setBalance(new BigDecimal("1.00"));
        a.setTags(List.of("t1"));
        a.setLastLoginTime(Timestamp.valueOf("2026-10-14 08:00:00").toLocalDateTime());
        return a;
    }

    private static PgOrderItemEntity item(long orderId, String sku, int qty, String price) {
        PgOrderItemEntity i = new PgOrderItemEntity();
        i.setOrderId(orderId);
        i.setSkuCode(sku);
        i.setQuantity(qty);
        i.setPrice(new BigDecimal(price));
        return i;
    }

}
