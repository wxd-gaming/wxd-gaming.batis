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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @Column(unique = true)} 与 {@code @Index} 的协作测试。
 * <p>
 * 守的是一处"唯一约束被静默吞掉"的缺陷：早期判重只看"同列是否已有单列索引"，
 * 不看那条索引是否唯一——于是字段上只要加一条普通的查询索引，
 * 唯一索引就不再生成，数据库层不再拦重复值，
 * 而结构比对（库端 vs 实体）也发现不了这个缺失的唯一键。
 * </p>
 * <p>
 * 索引名不可自定义（{@code 表名_列名_类型} 纯函数生成）之后，
 * "同名索引指向别的列"这一类冲突在构造上就不存在了：名字相同 ⇔ 定义相同。
 * </p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 */
class UniqueIndexDedupTest {

    /** 同列既有普通索引、也有 unique 声明：普通索引必须就地升级为唯一索引，而不是两条共存 */
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

    /** 同列已有单列唯一索引：不再重复生成第二条唯一索引 */
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

    /** 表级复合索引：名字按声明顺序组合全部列 */
    @Table(name = "t_composite", indexes = {
            @Index(columnList = "server_id, level"),
            @Index(columnList = "server_id, level", unique = true)
    })
    public static class CompositeEntity {
        @Id
        @Column(type = "BIGINT")
        private long id;

        @Column
        private int serverId;
        @Column
        private int level;

        public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        public int getServerId() { return serverId; }
        public void setServerId(int serverId) { this.serverId = serverId; }
        public int getLevel() { return level; }
        public void setLevel(int level) { this.level = level; }
    }

    @Test
    void plainIndexOnUniqueColumnIsUpgradedNotDuplicated() {
        TableInfo info = EntityParser.parse(MixedIndexEntity.class);
        List<IndexInfo> indexes = info.getIndexes();

        assertEquals(1, indexes.size(),
                "唯一索引覆盖普通索引的全部查询能力，同列两条都建是纯冗余: " + indexes);
        IndexInfo uk = indexes.getFirst();
        assertTrue(uk.isUnique(), "普通索引必须就地升级为唯一，不能把唯一约束丢掉");
        assertEquals(List.of("account"), uk.getColumns());
        assertEquals("t_unique_mix_account_uk", uk.getName(),
                "升级唯一性必须同步换名：名字是列与唯一性的纯函数，不换名会与库端比对对不上");
    }

    @Test
    void uniqueIndexIsNotDuplicatedWhenSingleColumnUniqueIndexExists() {
        TableInfo info = EntityParser.parse(AlreadyUniqueEntity.class);
        long uniqueOnAccount = info.getIndexes().stream()
                .filter(idx -> idx.isUnique() && idx.getColumns().size() == 1
                        && "account".equals(idx.getColumns().getFirst()))
                .count();
        assertEquals(1, uniqueOnAccount, "同列已有唯一索引时不应再生成一条重复的唯一索引");
        assertEquals("t_unique_only_account_uk", info.getIndexes().getFirst().getName());
    }

    @Test
    void indexNameIsPureFunctionOfTableColumnsAndUniqueness() {
        // 命名口径：表名_列名(复合索引按声明顺序组合)_类型后缀
        assertEquals("t_player_account_uk", EntityParser.indexName("t_player", true, List.of("account")));
        assertEquals("t_player_server_id_idx", EntityParser.indexName("t_player", false, List.of("server_id")));
        assertEquals("t_player_server_id_level_idx",
                EntityParser.indexName("t_player", false, List.of("server_id", "level")));

        TableInfo info = EntityParser.parse(CompositeEntity.class);
        IndexInfo plain = info.getIndexes().stream().filter(idx -> !idx.isUnique()).findFirst().orElse(null);
        IndexInfo unique = info.getIndexes().stream().filter(IndexInfo::isUnique).findFirst().orElse(null);
        assertNotNull(plain);
        assertNotNull(unique);
        assertEquals("t_composite_server_id_level_idx", plain.getName());
        assertEquals("t_composite_server_id_level_uk", unique.getName(),
                "同列不同唯一性必须落到两个名字，否则唯一约束会被普通索引静默吞掉");
    }

    @Test
    void indexNameTruncatesWithStableHashBeyond63Chars() {
        // 63 是 PG/MySQL 双方便携上限（PG 63 字节、MySQL 64 字符）：
        // 超长截断中段 + 8 位稳定哈希 + 保留类型后缀，两条不同的长索引不能撞名
        List<String> longColumns = List.of("a_very_long_column_name_one", "a_very_long_column_name_two",
                "a_very_long_column_name_three");
        String name = EntityParser.indexName("t_a_very_long_table_name", false, longColumns);

        assertEquals(63, name.length(), "超长名字必须压到便携上限: " + name);
        assertTrue(name.endsWith("_idx"), "类型后缀必须保留: " + name);
        assertEquals(name, EntityParser.indexName("t_a_very_long_table_name", false, longColumns),
                "哈希必须稳定：同样的定义在任何环境生成同一个名字");
        String other = EntityParser.indexName("t_a_very_long_table_name", true, longColumns);
        assertTrue(other.endsWith("_uk"));
        assertTrue(!other.equals(name), "唯一性不同的长索引不能撞名");
    }
}
