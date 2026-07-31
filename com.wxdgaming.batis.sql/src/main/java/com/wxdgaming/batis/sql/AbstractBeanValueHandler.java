package com.wxdgaming.batis.sql;

import com.wxdgaming.batis.model.ColumnInfo;

import java.lang.reflect.Method;
import java.math.BigDecimal;

/**
 * Bean 值处理抽象基类，提供纯反射方法的通用实现
 * <p>方言只需实现 {@link #convertToDbValue} 和 {@link #convertFromDbValue}。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-31
 */
public abstract class AbstractBeanValueHandler implements BeanValueHandler {

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
        else if (paramType == int.class || paramType == Integer.class) arg = genId.intValue();
        else if (paramType == short.class || paramType == Short.class) arg = genId.shortValue();
        else if (paramType == byte.class || paramType == Byte.class) arg = genId.byteValue();
        else if (paramType == double.class || paramType == Double.class) arg = genId.doubleValue();
        else if (paramType == float.class || paramType == Float.class) arg = genId.floatValue();
        else if (paramType == BigDecimal.class) arg = BigDecimal.valueOf(genId.longValue());
        else arg = genId;
        try {
            setter.invoke(bean, arg);
        } catch (Exception e) {
            throw new RuntimeException("通过 setter 写回字段 " + col.getFieldName() + " 失败", e);
        }
    }
}
