package com.wxdgaming.batis.annotation;

/**
 * 主键生成策略枚举
 * <p>
 * 由 {@link GeneratedValue#strategy()} 声明，EntityParser 原样存进 ColumnInfo 的 generationType，
 * 默认值是 NONE。它不是"框架帮你生成 id 的方式清单"，而是给方言的提示：
 * 每种方言自己决定认哪些值，因此不要假设三个档位在所有库上都有区别。
 * </p>
 * <p>
 * 目前消费方只有 MySQL 方言，且判定条件统一为"是主键且策略等于 IDENTITY"
 * （建表/改表加 AUTO_INCREMENT、插入时剔除该列、事后回写生成的主键）。
 * 也就是说 AUTO 在现有实现里没有任何分支读取它，实际效果等同于 NONE：列不带自增、
 * 主键由应用层自己赋值。这一档是为将来引入其它方言（序列、IDENTITY 等）预留的语义位。
 * </p>
 *
 * @author wxd-gaming
 */
public enum GenerationType {

    /** 数据库自增（IDENTITY / AUTO_INCREMENT / SERIAL）——MySQL 下唯一真正生效的策略 */
    IDENTITY,

    /** 自动选择生成策略（由具体方言实现决定）——当前无方言读取此档位，行为同 NONE */
    AUTO,

    /** 不自动生成，由应用层手动赋值（默认值） */
    NONE
}
