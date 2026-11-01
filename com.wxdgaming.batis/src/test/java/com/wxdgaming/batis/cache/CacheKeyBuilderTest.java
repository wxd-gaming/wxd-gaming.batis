package com.wxdgaming.batis.cache;

import com.wxdgaming.batis.PlayerEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 缓存 Key 构建的离线验收测试。
 *
 * <p>Key 是 Write-Behind 脏数据定位的唯一坐标，两类错误都很难排查：</p>
 * <ul>
 *   <li><b>主键为 null 被静默转成字符串 "null"</b>：会和真实主键值 "null" 撞车，
 *   还会把"主键未生成"的编程错误伪装成一次缓存命中；</li>
 *   <li><b>复合主键的拼接歧义</b>：{@code [1,23]} 与 {@code [1,2,3]} 用下划线连接都会得到
 *   {@code 1_23}，长度前缀是消除歧义的手段。</li>
 * </ul>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-11-01
 */
class CacheKeyBuilderTest {

    @Test
    @DisplayName("单主键：表名前缀统一小写")
    void singlePrimaryKey() {
        assertEquals("t_player:42", CacheKeyBuilder.buildKey(PlayerEntity.class, 42L));
        assertEquals("t_player:", CacheKeyBuilder.keyPrefix(PlayerEntity.class));
    }

    @Test
    @DisplayName("主键为 null 直接失败，不生成可被误命中的 \"null\" 键")
    void nullPrimaryKeyIsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CacheKeyBuilder.buildKey(PlayerEntity.class, null));
        assertTrue(ex.getMessage().contains("null"), ex.getMessage());
    }

    @Test
    @DisplayName("复合主键用长度前缀消除歧义：[1,23] 与 [1,2,3] 必须得到不同 Key")
    void compositeKeyIsUnambiguous() {
        String a = CacheKeyBuilder.buildKey(PlayerEntity.class, new Object[]{1, 23});
        String b = CacheKeyBuilder.buildKey(PlayerEntity.class, new Object[]{1, 2, 3});
        String c = CacheKeyBuilder.buildKey(PlayerEntity.class, new Object[]{"1_2", 3});

        assertEquals("t_player:1:12:23", a);
        assertEquals("t_player:1:11:21:3", b);
        assertEquals("t_player:3:1_21:3", c);
        assertTrue(!a.equals(b) && !a.equals(c) && !b.equals(c), "任何两个复合 Key 都不能相同");
    }

    @Test
    @DisplayName("复合主键缺分量：报出第几个分量为 null")
    void nullCompositeComponentIsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CacheKeyBuilder.buildKey(PlayerEntity.class, new Object[]{1, null}));
        assertTrue(ex.getMessage().contains("第 1 个分量"), ex.getMessage());
    }
}
