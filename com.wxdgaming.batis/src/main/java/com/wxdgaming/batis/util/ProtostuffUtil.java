package com.wxdgaming.batis.util;

import io.protostuff.LinkedBuffer;
import io.protostuff.ProtostuffIOUtil;
import io.protostuff.Schema;
import io.protostuff.runtime.RuntimeSchema;

/**
 * Protostuff 二进制序列化工具。
 * <p>服务于 {@code @Column(storageMode = BLOB/MEDIUMBLOB/LONGBLOB)} 的对象列：把 POJO 编码成 byte[] 存库、
 * 读回时 merge 回对象。用 {@code RuntimeSchema} 反射生成 schema 并内部缓存，故无需预注册 proto。
 * 注意 Protostuff 不编码 null 字段，反序列化会复用传入实例（{@link #decode(byte[], Object)} 原地填充），
 * 因此对"可能整体为 null"的列需在调用层自行判空，不能依赖此工具区分 null 与空对象。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-24 18:17
 **/
public class ProtostuffUtil {


    /**
     * 编码数据（自动按对象运行时类型取 schema）
     *
     * @param object 数据对象
     */
    @SuppressWarnings("unchecked")
    public static <T> byte[] encode(T object) {
        Class<T> aClass = (Class<T>) object.getClass();
        return encode(object, aClass);
    }

    /**
     * 编码：显式指定 schema 类型，LinkedBuffer 用完即弃（单次写入不做池化以保线程安全）
     */
    public static <T> byte[] encode(T object, Class<T> clazz) {
        Schema<T> schema = RuntimeSchema.getSchema(clazz);
        LinkedBuffer buffer = LinkedBuffer.allocate();
        return ProtostuffIOUtil.toByteArray(object, schema, buffer);
    }


    /**
     * 解码到新实例：需目标类提供无参构造，失败时抛出带类名的明确异常
     */
    public static <T> T decode(byte[] bytes, Class<T> clazz) {
        T object;
        try {
            object = clazz.getDeclaredConstructor().newInstance();
        } catch (Exception var4) {
            throw new RuntimeException("Protostuff反序列化时创建实例失败,Class:" + clazz.getName(), var4);
        }

        return decode(bytes, clazz, object);
    }

    /**
     * 解码 数据（复用传入实例，按其实时类型取 schema）
     */
    @SuppressWarnings("unchecked")
    public static <T> T decode(byte[] bytes, T object) {
        return decode(bytes, (Class<T>) object.getClass(), object);
    }

    /** 解码核心：mergeFrom 把字节流字段级合并进 object，未出现的字段保持 object 原值 */
    public static <T> T decode(byte[] bytes, Class<T> clazz, T object) {
        Schema<T> schema = RuntimeSchema.getSchema(clazz);
        ProtostuffIOUtil.mergeFrom(bytes, object, schema);
        return object;
    }

}
