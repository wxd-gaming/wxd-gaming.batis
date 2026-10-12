package com.wxdgaming.batis.sql;

import com.wxdgaming.batis.model.ColumnInfo;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bean 值处理抽象基类，提供纯反射方法的通用实现
 * <p>方言只需实现 {@link #convertToDbValue} 和 {@link #convertFromDbValue}。</p>
 * <p>之所以把反射部分抽成基类而不是 default 方法：这三段逻辑与数据库完全无关，
 * 但错误信息、数值收窄、可访问性处理都很容易在各方言里写歪，收敛到一处才能保证
 * "同一个字段在 MySQL 和 pgsql 里报错方式一致"。新增方言时继承本类，
 * 不要再复制一遍反射代码。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-31
 */
public abstract class AbstractBeanValueHandler implements BeanValueHandler {

    /**
     * 反射读字段（走 getter）。
     * <p>缺 getter 时抛出的消息点名实体与字段：参数数组是按下标绑定的，
     * 少读一个字段会表现为"整条语句错位"，靠堆栈根本查不到是哪个字段没 getter。</p>
     */
    @Override
    public Object readFieldValue(Object bean, ColumnInfo col) {
        Method getter = col.getReadMethod();
        if (getter == null) {
            throw new RuntimeException("实体 " + bean.getClass().getSimpleName()
                    + " 缺少字段 " + col.getFieldName() + " 的 getter 方法，请为其添加 getter");
        }
        try {
            return getter.invoke(bean);
        } catch (Exception e) {
            throw new RuntimeException("通过 getter 读取字段 " + col.getFieldName() + " 失败", e);
        }
    }

    /**
     * 反射写字段（走 setter）。
     * <p>异常消息额外带上值的运行时类型：行映射失败九成是转换环节给错了载体
     * （比如把 Blob 直接塞给 String 字段），带上类型才能一眼看出是转换问题还是实体问题。</p>
     */
    @Override
    public void writeFieldValue(Object bean, ColumnInfo col, Object value) {
        Method setter = col.getWriteMethod();
        if (setter == null) {
            throw new RuntimeException("实体 " + bean.getClass().getSimpleName()
                    + " 缺少字段 " + col.getFieldName() + " 的 setter 方法，请为其添加 setter");
        }
        try {
            setter.invoke(bean, value);
        } catch (Exception e) {
            throw new RuntimeException("通过 setter 写回字段 " + col.getFieldName()
                    + " 失败，value=" + value + (value == null ? "" : ", valueType=" + value.getClass().getName()), e);
        }
    }

    /**
     * 生成键回写：按 setter 参数类型把 Number 收窄成实体字段能接受的形态。
     * <p>驱动返回的生成键类型由数据库决定（多为 Long，部分驱动给 BigInteger），
     * 而实体字段常声明为 int/long 甚至原子类型；直接 {@code invoke} 会因参数类型不匹配失败。
     * 收窄在这里统一做，方言只负责交出 Number。</p>
     * <p>遇到未覆盖的参数类型时原样交出，让反射自然报错，而不是悄悄丢掉主键值。</p>
     */
    @Override
    public void writeGeneratedKey(Object bean, ColumnInfo col, Number genId) {
        if (genId == null) return;
        Method setter = col.getWriteMethod();
        if (setter == null) {
            throw new RuntimeException("实体 " + bean.getClass().getSimpleName()
                    + " 缺少字段 " + col.getFieldName() + " 的 setter 方法，请为其添加 setter");
        }
        Class<?> paramType = setter.getParameterTypes()[0];
        Object arg;
        if (paramType == long.class || paramType == Long.class) arg = genId.longValue();
        else if (paramType == int.class || paramType == Integer.class) {
            arg = (int) requireInRange(genId, col, Integer.MIN_VALUE, Integer.MAX_VALUE, "int");
        } else if (paramType == short.class || paramType == Short.class) {
            arg = (short) requireInRange(genId, col, Short.MIN_VALUE, Short.MAX_VALUE, "short");
        } else if (paramType == byte.class || paramType == Byte.class) {
            arg = (byte) requireInRange(genId, col, Byte.MIN_VALUE, Byte.MAX_VALUE, "byte");
        } else if (paramType == double.class || paramType == Double.class) arg = genId.doubleValue();
        else if (paramType == float.class || paramType == Float.class) arg = genId.floatValue();
        else if (paramType == BigDecimal.class) arg = BigDecimal.valueOf(genId.longValue());
        else if (paramType == BigInteger.class) arg = BigInteger.valueOf(genId.longValue());
        else if (paramType == AtomicInteger.class) {
            arg = new AtomicInteger((int) requireInRange(genId, col, Integer.MIN_VALUE, Integer.MAX_VALUE, "int"));
        } else if (paramType == AtomicLong.class) arg = new AtomicLong(genId.longValue());
        else arg = genId;
        try {
            setter.invoke(bean, arg);
        } catch (Exception e) {
            throw new RuntimeException("通过 setter 写回字段 " + col.getFieldName() + " 失败", e);
        }
    }

    /**
     * 收窄前做范围校验：生成键超出字段类型范围时抛错，而不是静默溢出成负数。
     * <p>
     * {@code intValue()} 在自增 ID 超过 int 上限时会回绕成负数，调用方拿这个负 ID 继续关联，
     * 表现为"查不到自己刚插入的行"这类难查的问题；{@code byte/short} 截断更隐蔽。</p>
     */
    private static long requireInRange(Number genId, ColumnInfo col, long min, long max, String typeName) {
        long value = genId.longValue();
        if (value < min || value > max) {
            throw new IllegalStateException("字段 " + col.getFieldName() + " 声明为 " + typeName
                    + "，但数据库生成的键值 " + value + " 超出其取值范围，请把该字段改为 long/Long");
        }
        return value;
    }
}
