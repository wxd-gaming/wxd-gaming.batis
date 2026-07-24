/**
 * ORM 核心模块
 * <p>
 * 提供类似 JPA 风格的注解驱动实体映射体系，包括：
 * <ul>
 *   <li>注解定义：{@code @Table}、{@code @Id}、{@code @Column}、{@code @Index}、{@code @GeneratedValue}</li>
 *   <li>运行时元数据模型：{@code TableInfo}、{@code ColumnInfo}、{@code IndexInfo}</li>
 *   <li>注解解析器 {@code EntityParser}：将实体类解析为元数据</li>
 * </ul>
 *
 * @author wxd-gaming
 */
package com.wxdgaming.batis.orm;
