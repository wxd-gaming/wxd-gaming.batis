package com.wxdgaming.batis;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
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
        System.out.println(JSON.toJSONString(info, JSONWriter.Feature.PrettyFormat));
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

    // ==================== BLOB 通道的安全闸门 ====================

    @Test
    void testBlobWithPolymorphicRootFailsFast() {
        // Protostuff 通道没有任何类白名单：根类型声明成 Object/接口/抽象类，等于把
        // "实例化哪个类"的决定权交给库里的字节流。这类列必须在解析期拒绝，
        // 不能靠"业务方自己不会写恶意数据"撑着——BLOB 列的内容来源不止本进程。
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> EntityParser.parse(BlobObjectRootEntity.class));
        assertTrue(ex.getMessage().contains("BLOB"), ex.getMessage());
        assertTrue(ex.getMessage().contains("payload"), ex.getMessage());
    }

    @Test
    void testBlobWithInterfaceRootFailsFast() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> EntityParser.parse(BlobInterfaceRootEntity.class));
        assertTrue(ex.getMessage().contains("BLOB"), ex.getMessage());
    }

    @Test
    void testBlobWithPolymorphicFlagFailsFast() {
        // polymorphic 只在 JSON 编码路径生效，写在 BLOB 列上会被静默忽略——
        // 与 converter 冲突同理，配置错误要在解析期炸掉
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> EntityParser.parse(BlobPolymorphicEntity.class));
        assertTrue(ex.getMessage().contains("polymorphic"), ex.getMessage());
    }

    @Test
    void testBlobWithConcreteTypePasses() {
        // 具体类型的 BLOB 列是合法用法：Protostuff 按声明类型还原，不存在"库里决定类"的问题
        TableInfo info = EntityParser.parse(BlobConcreteEntity.class);
        assertEquals(SqlType.BLOB, info.getColumn("snapshot").getSqlType());
        // byte[] 原样落库、不过 Protostuff，多态根校验对它不适用
        assertEquals(SqlType.BLOB, info.getColumn("raw").getSqlType());
    }

    /** 非法：BLOB 列根类型是 Object */
    @Table(name = "t_blob_obj")
    public static class BlobObjectRootEntity {
        @Id
        private long id;
        @Column(storageMode = StorageMode.BLOB)
        private Object payload;

        public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        public Object getPayload() { return payload; }
        public void setPayload(Object payload) { this.payload = payload; }
    }

    /** 非法：BLOB 列根类型是接口 */
    @Table(name = "t_blob_iface")
    public static class BlobInterfaceRootEntity {
        @Id
        private long id;
        @Column(storageMode = StorageMode.BLOB)
        private java.io.Serializable payload;

        public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        public java.io.Serializable getPayload() { return payload; }
        public void setPayload(java.io.Serializable payload) { this.payload = payload; }
    }

    /** 非法：BLOB 列声明 polymorphic */
    @Table(name = "t_blob_poly")
    public static class BlobPolymorphicEntity {
        @Id
        private long id;
        @Column(storageMode = StorageMode.BLOB, polymorphic = true)
        private PlayerEntity payload;

        public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        public PlayerEntity getPayload() { return payload; }
        public void setPayload(PlayerEntity payload) { this.payload = payload; }
    }

    /** 合法：具体类型与 byte[] 的 BLOB 列 */
    @Table(name = "t_blob_ok")
    public static class BlobConcreteEntity {
        @Id
        private long id;
        @Column(storageMode = StorageMode.BLOB)
        private PlayerEntity snapshot;
        private byte[] raw;

        public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        public PlayerEntity getSnapshot() { return snapshot; }
        public void setSnapshot(PlayerEntity snapshot) { this.snapshot = snapshot; }
        public byte[] getRaw() { return raw; }
        public void setRaw(byte[] raw) { this.raw = raw; }
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
        // t_player_account_uk(@Index unique) + t_player_server_id_idx(@Index) + t_player_server_id_level_idx(表级复合)
        // 注意：account 同时有 @Index(unique=true) 和 @Column(unique=false)，
        // 后者不派生唯一索引，三个索引各一名、互不重叠
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
        assertEquals("t_player_account_uk", accountIdx.getName(), "字段级唯一索引按 表名_列名_uk 命名");
        assertTrue(accountIdx.isUnique());
        assertEquals(1, accountIdx.getColumns().size());
        assertEquals("account", accountIdx.getColumns().get(0));
    }

    @Test
    void testCompositeIndex() {
        List<IndexInfo> indexes = EntityParser.parse(PlayerEntity.class).getIndexes();
        IndexInfo composite = indexes.stream()
                .filter(idx -> idx.getName().equals("t_player_server_id_level_idx"))
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
