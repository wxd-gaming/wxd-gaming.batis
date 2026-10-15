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
 * <p><b>安全边界（无类白名单）</b>：{@code RuntimeSchema} 对 {@code Object}/接口/抽象类型的字段会把
 * 实现类名嵌进字节流、解码时按名反射实例化，全程没有 JSON 多态列那样的继承树白名单。
 * 因此 BLOB 列的实体图里<b>不要</b>声明多态字段（{@code EntityParser} 已拒绝根类型为
 * Object/接口/抽象类的 BLOB 列，但嵌套在 POJO 内部的多态字段只能靠约定避免）——
 * 能让攻击者写入该列的场景下，多态字段等于"库里的字节流决定实例化哪个类"。</p>
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
        if (object == null) {
            // 不判空会在这里 NPE，报错信息只有空指针、定位不到是哪个字段写了 null 对象
            throw new IllegalArgumentException("Protostuff 不能编码 null 对象");
        }
        Class<T> aClass = (Class<T>) object.getClass();
        return encode(object, aClass);
    }

    /**
     * 编码：显式指定 schema 类型，LinkedBuffer 用完即弃（单次写入不做池化以保线程安全）
     */
    public static <T> byte[] encode(T object, Class<T> clazz) {
        if (object == null || clazz == null) {
            throw new IllegalArgumentException("Protostuff 不能编码 null 对象或 null 类型");
        }
        Schema<T> schema = RuntimeSchema.getSchema(clazz);
        LinkedBuffer buffer = LinkedBuffer.allocate();
        return ProtostuffIOUtil.toByteArray(object, schema, buffer);
    }


    /**
     * 解码到新实例：需目标类提供无参构造，失败时抛出带类名的明确异常
     */
    public static <T> T decode(byte[] bytes, Class<T> clazz) {
        if (clazz == null) {
            throw new IllegalArgumentException("Protostuff 不能解码到 null 类型");
        }
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
        if (object == null) {
            // 不判空会在 object.getClass() 上抛裸 NPE，调用方无法区分"列值为 null"与"实体少无参构造"
            throw new IllegalArgumentException(
                    "Protostuff 不能解码到 null 实例，请改用 decode(bytes, Class) 指定目标类型");
        }
        return decode(bytes, (Class<T>) object.getClass(), object);
    }

    /**
     * 解码核心：mergeFrom 把字节流字段级合并进 object，未出现的字段保持 object 原值
     * <p>三个入参都显式判空，与 {@link #encode} 的契约保持对称：调用方拿到的是"哪个参数为空"的
     * 可读异常，而不是第三方库内部抛出的裸 NPE。</p>
     */
    public static <T> T decode(byte[] bytes, Class<T> clazz, T object) {
        if (bytes == null) {
            throw new IllegalArgumentException("Protostuff 不能解码 null 字节数组");
        }
        if (clazz == null || object == null) {
            throw new IllegalArgumentException("Protostuff 不能解码到 null 类型或 null 实例");
        }
        Schema<T> schema = RuntimeSchema.getSchema(clazz);
        ProtostuffIOUtil.mergeFrom(bytes, object, schema);
        return object;
    }

}
