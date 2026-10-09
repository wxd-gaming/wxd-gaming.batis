package com.wxdgaming.batis.sql;

import com.wxdgaming.batis.model.ColumnInfo;

/**
 * 列类型定义处理器（方言 SPI）
 * <p>把 core 层的逻辑类型（{@link com.wxdgaming.batis.model.SqlType} + 长度/精度）
 * 渲染为方言物理类型字符串，并把物理类型字符串规格化为可比较形式，供 DDL 结构 diff 使用。</p>
 * <p>DDL 处理器统一通过本接口取得列物理类型，不要直接读取
 * {@link ColumnInfo#getType()} —— 自动推断的列该字段为 null，物理形态只存在于方言实现中。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public interface ColumnTypeDefHandler {

    /**
     * 渲染列的物理类型字符串（大写，如 MySQL 的 {@code VARCHAR(255)}）。
     * <p>优先级：显式 {@code @Column(type=...)} 物理字面量原样返回；
     * 否则按 sqlType + length/scale 渲染；两者皆空时抛 IllegalArgumentException。</p>
     */
    String renderType(ColumnInfo col);

    /**
     * 渲染物理类型字符串并规格化为可比较形式（等价于 {@code normalize(renderType(col))}）。
     */
    String renderNormalized(ColumnInfo col);

    /**
     * 规格化物理类型字符串以便比较：统一小写、消除同义写法与
     * 方言的显示宽度差异（如 MySQL 整数列的 {@code int(11)} → {@code int}）。
     */
    String normalize(String physicalType);

    /** 方言名，用于日志与诊断 */
    default String dialectName() {
        return "generic";
    }

}
