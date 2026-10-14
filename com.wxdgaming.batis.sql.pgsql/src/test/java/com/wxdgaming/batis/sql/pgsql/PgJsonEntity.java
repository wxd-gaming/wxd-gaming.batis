package com.wxdgaming.batis.sql.pgsql;

import com.wxdgaming.batis.annotation.Column;
import com.wxdgaming.batis.annotation.GeneratedValue;
import com.wxdgaming.batis.annotation.GenerationType;
import com.wxdgaming.batis.annotation.Id;
import com.wxdgaming.batis.annotation.Table;
import com.wxdgaming.batis.model.StorageMode;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/**
 * PG 原生 JSON 列回归专用实体 —— 一个实体里同时摆出 json、jsonb 与默认 text 三种物理档。
 * <p>选型走 {@code @Column(type = ...)} 这个既有逃生舱，而不是新增 StorageMode 档位：
 * StorageMode 管"值怎么写"（JSON 档的产物本来就是字符串，三种物理列都收），
 * 而 {@code type} 管"列在库里长什么样"，正是用户要挑的那件事。</p>
 * <p>字段刻意混着来：{@code tags} 是集合（AUTO 升级为 JSON 存储模式）、{@code rawJson} 是裸字符串
 * （不经 fastjson，原样落列）、{@code doc} 用 {@code Map<String,Object>} 存嵌套结构、
 * {@code asText} 不带 type 作为"默认仍是 text"的对照组。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 **/
@Setter
@Getter
@Table(name = "t_pg_json", comment = "PG 原生 JSON 列回归表")
public class PgJsonEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    /** jsonb：二进制存储，键序与空白会被 PG 规范化 */
    @Column(type = "JSONB", nullable = false, comment = "标签数组")
    private List<String> tags;

    /** json：原始文本存储，写进去什么形态就读回来什么形态（这里刻意用小写拼写） */
    @Column(type = "json")
    private Map<String, Object> doc;

    /** String 字段直落 jsonb 列：不经 fastjson 包装，值本身就是 JSON 文本 */
    @Column(type = "JSONB")
    private String rawJson;

    /** 带默认值的 jsonb 列：默认值按 SQL 字面量书写，PG 建表时自己转换类型 */
    @Column(type = "JSONB", defaultValue = "'[]'")
    private List<String> withDefault;

    /** 对照组：不带 type 的 JSON 存储模式，物理列仍是 text（存量实体的既有口径） */
    @Column(storageMode = StorageMode.JSON)
    private List<String> asText;

    /**
     * 多态列：声明成接口、存具体子类，且落在 PG 原生 jsonb 上。
     * <p>这一档把两个承诺叠在一起：类型标记只是 JSON 里的一个普通键（否则 jsonb 在写入阶段就报
     * 非法输入，而 Java 侧的单测看不出来），以及读回来仍然是子类。</p>
     */
    @Column(type = "JSONB", polymorphic = true)
    private PgShape shape;

    /** 主键之外的第二个唯一列：迁移用例要在同一张表上按 id 定位这一行 */
    @Column(type = "VARCHAR(32)", nullable = false, unique = true)
    private String bizKey;

}
