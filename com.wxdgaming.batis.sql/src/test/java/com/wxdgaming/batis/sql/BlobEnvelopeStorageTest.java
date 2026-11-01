package com.wxdgaming.batis.sql;

import com.wxdgaming.batis.exception.TypeMismatchDataAccessException;
import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.StorageMode;
import com.wxdgaming.batis.util.ProtostuffUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Blob;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BLOB（Protostuff）存储通道的类型壳与安全闸门测试。
 *
 * <p>类型壳解决的是一个真实的数据损坏问题：写侧按<b>运行时类型</b>选 schema、读侧过去按
 * <b>字段声明类型</b>选 schema。字段声明成父类、实际放的是子类时，两侧 schema 不一致，
 * 子类字段会被静默丢弃甚至字段错位。壳里记下运行时类名后，读侧才能用对的 schema，
 * 再用"必须是声明类型的子类"把库里的类名焊回编译期声明。</p>
 *
 * <p>同时覆盖两条安全边界（与 JSON 多态列同级的闸门）：</p>
 * <ul>
 *   <li>壳里的类必须可加载，且是字段声明类型的子类——否则库里的字节就能决定实例化哪个类；</li>
 *   <li>声明的是业务类型时，壳里不允许点名 JDK 平台类（{@code String}/{@code Runtime} 等），
 *   否则 BLOB 列就是另一个反序列化 gadget 入口。</li>
 * </ul>
 *
 * <p>另有一条兼容性约定：没有魔数的历史裸 Protostuff 行必须仍能按声明类型读出，
 * 否则升级框架就会让存量 BLOB 数据全部不可读。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-11-01
 */
class BlobEnvelopeStorageTest {

    /** 只暴露基类二进制编解码的桩 */
    private static final class Codec extends AbstractBeanValueHandler {
        @Override
        public Object convertToDbValue(Object value, ColumnInfo col) {
            return toBlobBytes(value, col);
        }

        @Override
        public Object convertFromDbValue(Object value, ColumnInfo col) {
            return fromBlobBytes(value, col);
        }
    }

    private static final Codec CODEC = new Codec();

    private static final byte[] MAGIC = {(byte) 0xBF, 'W', 'X', 'D', 'B', 'L', 'O'};
    private static final byte VERSION = 1;

    public static class Animal {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    public static class Dog extends Animal {
        private String breed;

        public String getBreed() {
            return breed;
        }

        public void setBreed(String breed) {
            this.breed = breed;
        }
    }

    /** 与声明类型毫无继承关系的类：用来验证"类型壳点名即可实例化"的口子确实被堵上 */
    public static class Unrelated {
        static final AtomicInteger CREATED = new AtomicInteger();

        private String tag;

        public Unrelated() {
            CREATED.incrementAndGet();
        }

        public String getTag() {
            return tag;
        }

        public void setTag(String tag) {
            this.tag = tag;
        }
    }

    private static ColumnInfo col(String fieldName, Class<?> javaType) {
        ColumnInfo col = new ColumnInfo();
        col.setColumnName(fieldName);
        col.setFieldName(fieldName);
        col.setJavaType(javaType);
        col.setStorageMode(StorageMode.BLOB);
        col.setNullable(true);
        return col;
    }

    private static Dog dog(String name, String breed) {
        Dog dog = new Dog();
        dog.setName(name);
        dog.setBreed(breed);
        return dog;
    }

    /** 按格式手工构造类型壳，用于模拟"库里写了什么" */
    private static byte[] envelope(String className, byte[] payload) {
        byte[] name = className.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[MAGIC.length + 1 + 2 + name.length + payload.length];
        System.arraycopy(MAGIC, 0, out, 0, MAGIC.length);
        int offset = MAGIC.length;
        out[offset++] = VERSION;
        out[offset++] = (byte) (name.length >>> 8);
        out[offset++] = (byte) name.length;
        System.arraycopy(name, 0, out, offset, name.length);
        offset += name.length;
        System.arraycopy(payload, 0, out, offset, payload.length);
        return out;
    }

    // ==================== 基本形态 ====================

    @Test
    @DisplayName("byte[] 原样落库、原样读回，不套 Protostuff 壳")
    void rawBytesPassThrough() {
        byte[] raw = {1, 2, 3, 4};
        ColumnInfo col = col("raw", byte[].class);

        Object encoded = CODEC.convertToDbValue(raw, col);
        assertSame(raw, encoded, "byte[] 不应被重新编码");
        assertSame(raw, CODEC.convertFromDbValue(raw, col));
    }

    @Test
    @DisplayName("具体类型往返：写出带魔数/版本/类名的壳，读回字段完整")
    void concreteTypeRoundTrip() {
        ColumnInfo col = col("snapshot", Dog.class);
        Dog source = dog("rex", "husky");

        byte[] bytes = (byte[]) CODEC.convertToDbValue(source, col);

        assertArrayEquals(MAGIC, Arrays.copyOfRange(bytes, 0, MAGIC.length));
        assertEquals(VERSION, bytes[MAGIC.length], "版本号必须是当前格式版本");
        Dog back = assertInstanceOf(Dog.class, CODEC.convertFromDbValue(bytes, col));
        assertEquals("rex", back.getName());
        assertEquals("husky", back.getBreed());
    }

    @Test
    @DisplayName("多态：字段声明父类、实际存子类时，读回仍是子类且子类字段不丢")
    void runtimeTypeIsRestoredFromEnvelope() {
        ColumnInfo col = col("pet", Animal.class);

        byte[] bytes = (byte[]) CODEC.convertToDbValue(dog("rex", "husky"), col);
        String className = new String(bytes, MAGIC.length + 1 + 2,
                ((bytes[MAGIC.length + 1] & 0xFF) << 8) | (bytes[MAGIC.length + 2] & 0xFF),
                StandardCharsets.UTF_8);
        assertEquals(Dog.class.getName(), className, "壳里记录的必须是运行时类型");

        Animal back = assertInstanceOf(Animal.class, CODEC.convertFromDbValue(bytes, col));
        assertInstanceOf(Dog.class, back, "按声明类型解会把子类字段静默丢掉");
        assertEquals("husky", ((Dog) back).getBreed());
    }

    // ==================== 安全闸门 ====================

    @Test
    @DisplayName("壳里点名无关类：拒绝且根本不构造对象")
    void unrelatedStoredClassIsRejected() {
        Unrelated payload = new Unrelated();
        payload.setTag("x");
        byte[] bytes = envelope(Unrelated.class.getName(), ProtostuffUtil.encode(payload));
        int before = Unrelated.CREATED.get();

        TypeMismatchDataAccessException ex = assertThrows(TypeMismatchDataAccessException.class,
                () -> CODEC.convertFromDbValue(bytes, col("pet", Animal.class)));

        assertTrue(ex.getMessage().contains("pet"), "报错必须点名是哪一列: " + ex.getMessage());
        assertEquals(before, Unrelated.CREATED.get(), "被拒绝的壳不能发生实例化");
    }

    @Test
    @DisplayName("声明业务类型时，壳里点名 JDK 类一律拒绝（防止 BLOB 变成反序列化入口）")
    void jdkStoredClassIsRejected() {
        byte[] bytes = envelope(String.class.getName(), "payload".getBytes(StandardCharsets.UTF_8));

        TypeMismatchDataAccessException ex = assertThrows(TypeMismatchDataAccessException.class,
                () -> CODEC.convertFromDbValue(bytes, col("pet", java.io.Serializable.class)));

        assertTrue(ex.getMessage().contains("JDK"), "必须命中 JDK 平台类闸门: " + ex.getMessage());
    }

    @Test
    @DisplayName("壳里的类无法加载时报错并带上类名")
    void unknownStoredClassIsRejected() {
        byte[] bytes = envelope("com.example.NotOnClasspath", new byte[]{1, 2, 3});

        TypeMismatchDataAccessException ex = assertThrows(TypeMismatchDataAccessException.class,
                () -> CODEC.convertFromDbValue(bytes, col("pet", Animal.class)));

        assertTrue(ex.getMessage().contains("com.example.NotOnClasspath"), ex.getMessage());
    }

    @Test
    @DisplayName("未知壳版本/头部不完整：明确报错，不按旧格式硬解")
    void malformedEnvelopeIsRejected() {
        byte[] newVersion = envelope(Dog.class.getName(), new byte[]{1});
        newVersion[MAGIC.length] = 99;
        TypeMismatchDataAccessException versionEx = assertThrows(TypeMismatchDataAccessException.class,
                () -> CODEC.convertFromDbValue(newVersion, col("pet", Dog.class)));
        assertTrue(versionEx.getMessage().contains("版本"), versionEx.getMessage());

        byte[] truncated = Arrays.copyOfRange(envelope(Dog.class.getName(), new byte[]{1}), 0, MAGIC.length + 1);
        TypeMismatchDataAccessException headEx = assertThrows(TypeMismatchDataAccessException.class,
                () -> CODEC.convertFromDbValue(truncated, col("pet", Dog.class)));
        assertTrue(headEx.getMessage().contains("头部"), headEx.getMessage());

        byte[] shortName = envelope(Dog.class.getName(), new byte[]{1});
        shortName[MAGIC.length + 1] = 0x7F;
        shortName[MAGIC.length + 2] = (byte) 0xFF;
        TypeMismatchDataAccessException lengthEx = assertThrows(TypeMismatchDataAccessException.class,
                () -> CODEC.convertFromDbValue(shortName, col("pet", Dog.class)));
        assertTrue(lengthEx.getMessage().contains("长度"), lengthEx.getMessage());
    }

    // ==================== 兼容与载体 ====================

    @Test
    @DisplayName("没有魔数的历史裸 Protostuff 行仍按声明类型解码")
    void legacyRawProtostuffStillReads() {
        Dog source = dog("old", "beagle");
        byte[] legacy = ProtostuffUtil.encode(source, Dog.class);
        assertNotEquals((byte) 0xBF, legacy[0], "构造的裸数据不应自带魔数");

        Dog back = assertInstanceOf(Dog.class, CODEC.convertFromDbValue(legacy, col("pet", Dog.class)));
        assertEquals("beagle", back.getBreed());
    }

    @Test
    @DisplayName("java.sql.Blob 载体先物化成 byte[] 再解码")
    void blobCarrierIsMaterialized() {
        Dog source = dog("blob", "pug");
        byte[] bytes = envelope(Dog.class.getName(), ProtostuffUtil.encode(source, Dog.class));
        Blob blob = (Blob) Proxy.newProxyInstance(
                Blob.class.getClassLoader(), new Class<?>[]{Blob.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "length" -> (long) bytes.length;
                    case "getBytes" -> Arrays.copyOfRange(bytes, ((Number) args[0]).intValue() - 1,
                            ((Number) args[0]).intValue() - 1 + ((Number) args[1]).intValue());
                    case "free", "close" -> null;
                    default -> null;
                });

        Dog back = assertInstanceOf(Dog.class, CODEC.convertFromDbValue(blob, col("pet", Dog.class)));
        assertEquals("pug", back.getBreed());
    }

    @Test
    @DisplayName("既不是 byte[] 也不是 Blob 的载体：报类型不支持，而不是 ClassCastException")
    void unsupportedCarrierIsReported() {
        TypeMismatchDataAccessException ex = assertThrows(TypeMismatchDataAccessException.class,
                () -> CODEC.convertFromDbValue("not bytes", col("pet", Dog.class)));
        assertTrue(ex.getMessage().contains("二进制类型"), ex.getMessage());
    }
}
