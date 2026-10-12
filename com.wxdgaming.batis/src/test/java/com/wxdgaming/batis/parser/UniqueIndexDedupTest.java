package com.wxdgaming.batis.parser;

import com.wxdgaming.batis.annotation.Column;
import com.wxdgaming.batis.annotation.Id;
import com.wxdgaming.batis.annotation.Index;
import com.wxdgaming.batis.annotation.Table;
import com.wxdgaming.batis.model.IndexInfo;
import com.wxdgaming.batis.model.TableInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @Column(unique = true)} 与 {@code @Index} 的协作测试。
 * <p>
 * 守的是一处"唯一约束被静默吞掉"的缺陷：早期判重只看"同列是否已有单列索引"，
 * 不看那条索引是否唯一——于是字段上只要加一条普通的查询索引，
 * {@code uk_<col>} 唯一索引就不再生成，数据库层不再拦重复值，
 * 而结构比对（库端 vs 实体）也发现不了这个缺失的唯一键。
 * </p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 */
class UniqueIndexDedupTest {

    /** 同列既有普通索引、也有 unique 声明：必须同时存在普通索引与 uk_ 唯一索引 */
    @Table(name = "t_unique_mix")
    public static class MixedIndexEntity {
        @Id
        @Column(type = "BIGINT")
        private long id;

        @Index
        @Column(unique = true, length = 32)
        private String account;

        public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        public String getAccount() { return account; }
        public void setAccount(String account) { this.account = account; }
    }

    /** 同列已有单列唯一索引：不再重复生成 uk_ 索引 */
    @Table(name = "t_unique_only")
    public static class AlreadyUniqueEntity {
        @Id
        @Column(type = "BIGINT")
        private long id;

        @Index(unique = true)
        @Column(unique = true, length = 32)
        private String account;

        public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        public String getAccount() { return account; }
        public void setAccount(String account) { this.account = account; }
    }

    /** 用户手写的索引正好叫 uk_account 但不是唯一索引：就地升级，不静默丢弃唯一约束 */
    @Table(name = "t_unique_samename")
    public static class SameNameEntity {
        @Id
        @Column(type = "BIGINT")
        private long id;

        @Index(name = "uk_account", columnList = "account")
        @Column(unique = true, length = 32)
        private String account;

        public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        public String getAccount() { return account; }
        public void setAccount(String account) { this.account = account; }
    }

    /** 同名索引指向别的列：属于命名冲突，宁可报错也不要生成语义不明的 DDL */
    @Table(name = "t_unique_conflict")
    public static class ConflictingNameEntity {
        @Id
        @Column(type = "BIGINT")
        private long id;

        @Index(name = "uk_account", columnList = "id")
        @Column(unique = true, length = 32)
        private String account;

        public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        public String getAccount() { return account; }
        public void setAccount(String account) { this.account = account; }
    }

    @Test
    void uniqueColumnStillGetsUniqueIndexWhenPlainIndexExists() {
        TableInfo info = EntityParser.parse(MixedIndexEntity.class);
        List<IndexInfo> indexes = info.getIndexes();

        IndexInfo uk = indexes.stream()
                .filter(idx -> "uk_account".equals(idx.getName()))
                .findFirst().orElse(null);
        assertNotNull(uk, "同列存在普通索引时，uk_ 唯一索引仍必须生成");
        assertTrue(uk.isUnique(), "uk_ 索引必须是唯一索引");
        assertEquals(List.of("account"), uk.getColumns());

        boolean plainIndexKept = indexes.stream()
                .anyMatch(idx -> !idx.isUnique() && idx.getColumns().size() == 1
                        && "account".equals(idx.getColumns().getFirst()));
        assertTrue(plainIndexKept, "原有的普通索引不应被移除（两者可以共存）");
    }

    @Test
    void uniqueIndexIsNotDuplicatedWhenSingleColumnUniqueIndexExists() {
        TableInfo info = EntityParser.parse(AlreadyUniqueEntity.class);
        long uniqueOnAccount = info.getIndexes().stream()
                .filter(idx -> idx.isUnique() && idx.getColumns().size() == 1
                        && "account".equals(idx.getColumns().getFirst()))
                .count();
        assertEquals(1, uniqueOnAccount, "同列已有唯一索引时不应再生成一条重复的唯一索引");
    }

    @Test
    void sameNameIndexOnSameColumnIsUpgradedToUnique() {
        TableInfo info = EntityParser.parse(SameNameEntity.class);
        IndexInfo uk = info.getIndexes().stream()
                .filter(idx -> "uk_account".equals(idx.getName()))
                .findFirst().orElseThrow();
        assertTrue(uk.isUnique(), "同名且同列的索引应就地升级为唯一索引，而不是把唯一约束丢掉");
        assertEquals(1, info.getIndexes().stream().filter(idx -> "uk_account".equals(idx.getName())).count());
    }

    @Test
    void sameNameIndexOnOtherColumnFailsFast() {
        assertThrows(IllegalStateException.class, () -> EntityParser.parse(ConflictingNameEntity.class),
                "同名索引覆盖的是别的列时必须报错，而不是生成语义不明的 DDL");
    }
}
