package com.wxdgaming.batis;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.wxdgaming.batis.annotation.Column;
import com.wxdgaming.batis.annotation.Id;
import com.wxdgaming.batis.annotation.Table;
import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.IndexInfo;
import com.wxdgaming.batis.model.SqlType;
import com.wxdgaming.batis.model.StorageMode;
import com.wxdgaming.batis.model.TableInfo;
import com.wxdgaming.batis.parser.EntityParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EntityParser 解析器核心功能测试
 */
class EntityParserTest {

    // ==================== 基本元数据 ====================

    @Test
    void testTableName() {
        TableInfo info = EntityParser.parse(PlayerEntity.class);
        assertEquals("t_player", info.getTableName());
        assertEquals("玩家表", info.getComment());
        assertEquals(PlayerEntity.class, info.getEntityClass());
    }

    @Test
    void testTableNameNoAnnotation() {
        TableInfo info = EntityParser.parse(SimpleEntity.class);
        System.out.println(JSON.toJSONString(info, SerializerFeature.PrettyFormat));
        assertEquals("simple_entity", info.getTableName());
        assertNull(info.getComment());
    }

    @Test
    void testEntityAnnotationFallback() {
        TableInfo info = EntityParser.parse(ItemEntity.class);
        assertEquals("t_item", info.getTableName());
        assertEquals("道具表", info.getComment());
    }

    // ==================== 列信息 ====================

    @Test
    void testColumnCount() {
        TableInfo info = EntityParser.parse(PlayerEntity.class);
        assertEquals(8, info.getColumns().size());
    }

    @Test
    void testPrimaryKey() {
        ColumnInfo pk = EntityParser.parse(PlayerEntity.class).getColumns().values().stream()
                .filter(ColumnInfo::isPrimaryKey)
                .findFirst().orElse(null);
        assertNotNull(pk);
        assertEquals("id", pk.getColumnName());
        // id 显式 @Column(type = "BIGINT")：物理字面量原样保留
        assertEquals("BIGINT", pk.getType());
    }

    @Test
    void testColumnAnnotation() {
        ColumnInfo col = EntityParser.parse(PlayerEntity.class).getColumns().values().stream()
                .filter(c -> c.getFieldName().equals("name"))
                .findFirst().orElse(null);
        assertNotNull(col);
        assertEquals("player_name", col.getColumnName());
        // 未显式指定 type：core 不再产出方言字面量，只有逻辑类型 + 长度
        assertNull(col.getType());
        assertEquals(SqlType.VARCHAR, col.getSqlType());
        assertEquals(64, col.getLength());
        assertEquals("昵称", col.getComment());
        assertEquals("''", col.getDefaultValue());
        assertFalse(col.isNullable());
    }

    @Test
    void testTypeInference() {
        TableInfo info = EntityParser.parse(SimpleEntity.class);

        // 无注解的字段应自动推断逻辑类型（不含方言字面量）
        assertEquals("simple_entity", info.getTableName());
        assertEquals("id", info.getColumn("id").getColumnName());
        assertEquals(SqlType.BIGINT, info.getColumn("id").getSqlType());
        assertNull(info.getColumn("id").getType());

        ColumnInfo nameCol = info.getColumns().values().stream()
                .filter(c -> c.getFieldName().equals("name")).findFirst().orElse(null);
        assertNotNull(nameCol);
        assertEquals(SqlType.VARCHAR, nameCol.getSqlType()); // 默认长度 255
        assertEquals(255, nameCol.getLength());

        ColumnInfo ageCol = info.getColumns().values().stream()
                .filter(c -> c.getFieldName().equals("age")).findFirst().orElse(null);
        assertNotNull(ageCol);
        assertEquals(SqlType.INT, ageCol.getSqlType());

        ColumnInfo activeCol = info.getColumns().values().stream()
                .filter(c -> c.getFieldName().equals("active")).findFirst().orElse(null);
        assertNotNull(activeCol);
        assertEquals(SqlType.BOOLEAN, activeCol.getSqlType());
    }

    // ==================== 多态 JSON 列（polymorphic） ====================

    @Test
    void testPolymorphicFlagParsed() {
        TableInfo info = EntityParser.parse(StorageModeEntity.class);
        // 只有显式声明的列带标记：默认档必须保持 false，否则存量列的 JSON 格式会被框架升级改掉
        assertTrue(info.getColumn("polymorphic_payload").isPolymorphic());
        assertFalse(info.getColumn("payload").isPolymorphic());
        assertFalse(info.getColumn("code").isPolymorphic());
        // 开启标记不改变列的逻辑类型（仍是 JSON 档 -> MySQL 渲染 LONGTEXT）
        assertEquals(SqlType.JSON, info.getColumn("polymorphic_payload").getSqlType());
    }

    @Test
    void testPolymorphicWithConverterFailsFast() {
        // 转换器完全接管编解码，polymorphic 会被静默忽略——这类配置错误要在解析期炸掉，
        // 不能等到"子类读回成父类"这种运行期数据损坏才发现
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> EntityParser.parse(PolymorphicConflictEntity.class));
        assertTrue(ex.getMessage().contains("polymorphic"), ex.getMessage());
    }

    /** 同时声明 converter 与 polymorphic 的非法实体 */
    @Table(name = "t_poly_conflict")
    public static class PolymorphicConflictEntity {
        @Id
        private long id;
        @Column(storageMode = StorageMode.JSON, polymorphic = true, converter = TagConverter.class)
        private List<Object> payload;

        public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        public List<Object> getPayload() { return payload; }
        public void setPayload(List<Object> payload) { this.payload = payload; }
    }

    /** 占位转换器：只为触发与 polymorphic 的互斥校验 */
    public static class TagConverter implements com.wxdgaming.batis.converter.TypeConverter<Object, String> {
        @Override
        public String convertToDatabaseColumn(Object attribute) {
            return attribute == null ? null : attribute.toString();
        }

        @Override
        public Object convertToEntityAttribute(String dbData) {
            return dbData;
        }
    }

    @Test
    void testStorageModeToSqlType() {
        TableInfo info = EntityParser.parse(StorageModeEntity.class);
        assertEquals(SqlType.JSON, info.getColumn("payload").getSqlType());      // JSON 存储模式 -> 逻辑 JSON（MySQL 渲染为 LONGTEXT）
        assertEquals(SqlType.BIGINT, info.getColumn("counter").getSqlType());     // 显式 LONG
        assertEquals(SqlType.VARCHAR, info.getColumn("code").getSqlType());       // 显式 VARCHAR
        assertEquals(64, info.getColumn("code").getLength());
    }

    // ==================== 索引 ====================

    @Test
    void testIndexCount() {
        TableInfo info = EntityParser.parse(PlayerEntity.class);
        // idx_account(unique) + idx_server_id + idx_srv_level(复合) + uk_account(@Column unique)
        // 注意：account 同时有 @Index(unique=true) 和 @Column(unique=false)，
        // 但 @Index(unique=true) 会生成 idx_account(unique)，@Column(unique=false) 不会生成 uk 索引
        assertEquals(3, info.getIndexes().size());
    }

    @Test
    void testUniqueIndexFromColumn() {
        // ItemEntity 中 name 字段 @Column(nullable=false) 但没有 unique，不应生成唯一索引
        TableInfo info = EntityParser.parse(ItemEntity.class);
        // 只有 @Id 字段没有 @Index 注解，所以应无索引
        // 不过 @Column(unique=true) 才会触发自动生成，这里没有，应为 0
        assertTrue(info.getIndexes().isEmpty());
    }

    @Test
    void testUniqueIndexFromFieldAnnotation() {
        List<IndexInfo> indexes = EntityParser.parse(PlayerEntity.class).getIndexes();
        IndexInfo accountIdx = indexes.stream()
                .filter(idx -> idx.getName().contains("account"))
                .findFirst().orElse(null);
        assertNotNull(accountIdx);
        assertTrue(accountIdx.isUnique());
        assertEquals(1, accountIdx.getColumns().size());
        assertEquals("account", accountIdx.getColumns().get(0));
    }

    @Test
    void testCompositeIndex() {
        List<IndexInfo> indexes = EntityParser.parse(PlayerEntity.class).getIndexes();
        IndexInfo composite = indexes.stream()
                .filter(idx -> idx.getName().equals("idx_srv_level"))
                .findFirst().orElse(null);
        assertNotNull(composite);
        assertEquals(2, composite.getColumns().size());
        assertEquals("server_id", composite.getColumns().get(0));
        assertEquals("level", composite.getColumns().get(1));
        assertFalse(composite.isUnique());
    }

    // ==================== 主键生成策略 ====================

    @Test
    void testGeneratedValue() {
        ColumnInfo pk = EntityParser.parse(PlayerEntity.class).getColumns().values().stream()
                .filter(ColumnInfo::isPrimaryKey)
                .findFirst().orElse(null);
        assertNotNull(pk);
        assertEquals(com.wxdgaming.batis.annotation.GenerationType.IDENTITY,
                pk.getGenerationType());
    }

    @Test
    void testNoGeneratedValueDefault() {
        ColumnInfo pk = EntityParser.parse(SimpleEntity.class).getColumns().values().stream()
                .filter(ColumnInfo::isPrimaryKey)
                .findFirst().orElse(null);
        assertNotNull(pk);
        assertEquals(com.wxdgaming.batis.annotation.GenerationType.NONE,
                pk.getGenerationType());
    }

    // ==================== 缓存 ====================

    @Test
    void testCache() {
        TableInfo info1 = EntityParser.parse(PlayerEntity.class);
        TableInfo info2 = EntityParser.parse(PlayerEntity.class);
        assertSame(info1, info2, "同一 Class 应返回缓存中的同一对象");
    }

    @Test
    void testClearCache() {
        EntityParser.parse(PlayerEntity.class);
        EntityParser.clearCache(PlayerEntity.class);

        // 清缓存后应重新解析，此时和之前非同一对象
        // 简单验证：重新获取后不抛异常
        TableInfo info = EntityParser.parse(PlayerEntity.class);
        assertNotNull(info);
    }

    @Test
    void testClearAll() {
        EntityParser.parse(PlayerEntity.class);
        EntityParser.parse(SimpleEntity.class);
        EntityParser.clearAll();

        // 全部清空后重新获取
        assertNotNull(EntityParser.parse(PlayerEntity.class));
        assertNotNull(EntityParser.parse(SimpleEntity.class));
    }

    // ==================== 驼峰转换 ====================

    @Test
    void testCamelToUnderscore() {
        assertEquals("user_name", EntityParser.camelToUnderscore("userName"));
        assertEquals("user_name", EntityParser.camelToUnderscore("UserName"));
        assertEquals("user_name", EntityParser.camelToUnderscore("user_name"));
        assertEquals("simple_entity", EntityParser.camelToUnderscore("SimpleEntity"));
        assertEquals("t_player", EntityParser.camelToUnderscore("t_player"));
        assertEquals("ab_test", EntityParser.camelToUnderscore("ABTest"));
        assertEquals("url_value", EntityParser.camelToUnderscore("URLValue"));
    }
}
