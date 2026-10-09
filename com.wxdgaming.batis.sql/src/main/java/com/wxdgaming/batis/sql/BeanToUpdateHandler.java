package com.wxdgaming.batis.sql;

/**
 * UPDATE SQL 构建与参数提取接口（方言 SPI）
 * <p>各数据库方言通过此接口提供差异化的 UPDATE 实现（如标识符引用风格）。</p>
 * <p>语义约定：更新是"整实体覆盖"，非主键列全部进 SET 子句，null 字段同样写回 NULL，
 * 不做"只更新变化列"的脏检查——那是缓存层（{@code CachedBeanRunner}）的职责，
 * 本接口保持与手写 SQL 一致的可预期行为。</p>
 * <p>与 INSERT 同样的硬约束：语句形态只由实体类型决定。批量更新取第一个 bean 的 SQL 复用，
 * 若实现按字段值动态增减 SET 列，同批参数就会与占位符错位。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-28
 **/
public interface BeanToUpdateHandler {

    /**
     * 构建 UPDATE 语句
     * <p>实现方必须保证：SET 覆盖全部非主键列，WHERE 用主键列定位且只出现占位符；
     * 实体没有主键时必须快速失败（MySQL 实现抛 IllegalArgumentException）——
     * 退化成的无条件 UPDATE 会重写全表，是不可挽回的事故。</p>
     *
     * @param bean 实体对象，仅用于推导实体类型与列结构
     * @return 参数化 UPDATE 语句，不含任何字段字面量
     */
    String buildUpdateSql(Object bean);

    /**
     * 提取 UPDATE 预编译参数数组（SET 参数在前，WHERE 参数在后）
     * <p>顺序是契约的一部分：占位符在 SQL 文本里就是 SET 列在前、WHERE 列在后，
     * 两边一旦反了，条件值会被当成数据写进 SET，主键直接被改坏且没有任何报错。
     * 每个值都要经过 {@link BeanValueHandler#convertToDbValue} 转换。</p>
     *
     * @param bean 实体对象（主键字段必须有值）
     * @return 与占位符同序的参数数组，长度等于占位符个数
     */
    Object[] buildUpdateParams(Object bean);
}
