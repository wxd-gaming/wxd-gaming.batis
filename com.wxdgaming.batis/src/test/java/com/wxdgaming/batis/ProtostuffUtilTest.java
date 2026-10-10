package com.wxdgaming.batis;

import com.wxdgaming.batis.util.ProtostuffUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ProtostuffUtil 序列化工具测试
 *
 * <p>核心在 null 入参的快速失败：encode 之前不做判空会直接 NPE（{@code object.getClass()}），
 * 报错信息只有空指针、定位不到是哪个字段写了 null 对象。改成明确的 {@link IllegalArgumentException}
 * 后，这里锁住"null 对象 / null 类型必须抛可读异常"这条契约。</p>
 */
class ProtostuffUtilTest {

    public static class Sample {
        private String name;
        private int value;

        public Sample() {
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getValue() { return value; }
        public void setValue(int value) { this.value = value; }
    }

    @Test
    void encodeNullObjectThrows() {
        assertThrows(IllegalArgumentException.class, () -> ProtostuffUtil.encode((Object) null),
                "编码 null 对象应抛 IllegalArgumentException 而不是 NPE");
    }

    @Test
    void encodeNullObjectWithExplicitClassThrows() {
        assertThrows(IllegalArgumentException.class, () -> ProtostuffUtil.encode(null, Sample.class),
                "编码 null 对象应抛 IllegalArgumentException");
    }

    @Test
    void encodeWithNullClassThrows() {
        Sample sample = new Sample();
        sample.setName("x");
        assertThrows(IllegalArgumentException.class, () -> ProtostuffUtil.encode(sample, null),
                "编码 null 类型应抛 IllegalArgumentException");
    }

    @Test
    void encodeDecodeRoundtripStillWorks() {
        Sample sample = new Sample();
        sample.setName("roundtrip");
        sample.setValue(42);

        byte[] bytes = ProtostuffUtil.encode(sample);
        assertNotNull(bytes);
        assertTrue(bytes.length > 0, "编码结果不应为空");

        Sample decoded = ProtostuffUtil.decode(bytes, Sample.class);
        assertNotNull(decoded);
        assertEquals("roundtrip", decoded.getName());
        assertEquals(42, decoded.getValue());
    }
}
