package com.wxdgaming.batis.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 主键标记注解，标注在字段上标识该字段是主键
 * <p>
 * 支持联合主键：可在多个字段上标注本注解（EntityParser 只逐个把 ColumnInfo 的 primaryKey
 * 置为 true，不做单/复合区分），由建表方言渲染成对应的 PRIMARY KEY 定义。
 * </p>
 * <p>
 * <b>解析位置与连带效果</b>：EntityParser 逐字段构建列信息时读取它，命中后除了置 primaryKey，
 * 还会强制把 nullable 写成 false——覆盖 {@code Column.nullable()} 的值，
 * 所以主键字段不必再额外声明非空，写了也不起作用。
 * </p>
 * <p>
 * 下游消费方：按 id 的查询/更新/删除语句靠它拼 WHERE 条件，缓存 key 的构建
 * （core 模块的 CacheKeyBuilder）、建表 DDL 的 PRIMARY KEY 子句、以及自增主键判定
 * （方言里用"是主键且生成策略为 IDENTITY"这条谓词找自增列）都读这个标记。
 * 不标注的后果：实体没有主键，按 id 的读写在方言侧会直接被拒（抛参数非法异常，提示实体未定义主键），
 * 缓存 key 也取不到主键值，自增列更会被当成普通列参与 INSERT（数据库不会回写生成键）。
 * </p>
 * <p>与 {@link GeneratedValue} 的配合：本注解只说明"哪个字段是主键"，值从哪来由后者决定，
 * 两者各自独立解析，互不校验——只标 GeneratedValue 而不标 Id 时，自增策略不会生效。</p>
 *
 * @author wxd-gaming
 */
@Documented
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Id {
}
