package com.wxdgaming.batis.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 主键值生成策略注解，与 {@link Id} 配合使用
 * <pre>
 * &#64;Id
 * &#64;GeneratedValue(strategy = GenerationType.IDENTITY)
 * private long id;
 * </pre>
 * <p>
 * <b>解析位置</b>：EntityParser 逐字段读取本注解的 strategy 存进 ColumnInfo 的 generationType
 * （默认 ColumnInfo 里是 NONE，不标注就等于不自动生成）。本注解本身不校验字段是否为主键，
 * 因此必须和 {@link Id} 一起用——只标本注解时字段是普通列，自增 DDL 和主键回写都不会发生。
 * </p>
 * <p>
 * <b>下游消费（MySQL 方言）</b>：三处判定都是"是主键且策略为 IDENTITY"这条谓词——
 * 建表与改表时给列加 AUTO_INCREMENT；插入时把该列从 INSERT 的列清单里剔除（让数据库生成），
 * 生成后从回取到的 generated keys 写回实体的主键字段。写回这一步不能省：
 * 否则调用方手里是一条没有 ID 的实体，后续按 id 的更新/删除全部失效。
 * </p>
 * <p>
 * 与 JDBC 层的关系：主键回写走的是 QueryRunner 的插入路径（prepareStatement 时声明
 * RETURN_GENERATED_KEYS，执行后取 generated keys 的第一列）。若表没有自增列、驱动没回传
 * 或回传值不是数值，取到的主键是 null 而不是异常，所以框架不会因为你标了 IDENTITY 就保证拿到 ID。
 * </p>
 *
 * @author wxd-gaming
 */
@Documented
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface GeneratedValue {

    /**
     * 主键生成策略，默认 {@link GenerationType#NONE}。
     * <p>MySQL 方言只对 IDENTITY 做判定，AUTO 与 NONE 目前行为一致（列不带自增、值由应用层给），
     * 想用数据库自增请显式写 IDENTITY。</p>
     */
    GenerationType strategy() default GenerationType.NONE;
}
