/**
 * ORM 注解解析器
 * <p>
 * 负责读取实体类上的 {@code @Table}、{@code @Id}、{@code @Column}、{@code @Index} 等注解，
 * 生成运行时元数据 {@link com.wxdgaming.batis.orm.model.TableInfo}。
 *
 * @author wxd-gaming
 */
package com.wxdgaming.batis.orm.parser;
