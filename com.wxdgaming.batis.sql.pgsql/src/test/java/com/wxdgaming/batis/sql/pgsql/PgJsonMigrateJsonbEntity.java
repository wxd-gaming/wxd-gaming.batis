package com.wxdgaming.batis.sql.pgsql;

import com.wxdgaming.batis.annotation.Column;
import com.wxdgaming.batis.annotation.GeneratedValue;
import com.wxdgaming.batis.annotation.GenerationType;
import com.wxdgaming.batis.annotation.Id;
import com.wxdgaming.batis.annotation.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 结构迁移用例的"改造后"实体 —— 与 {@link PgJsonMigrateEntity} 同一张表，只把 {@code payload}
 * 从文本列改成 PG 原生 jsonb 列（Java 侧也从 String 换成 {@code List<Integer>}，
 * 由 AUTO 存储模式自动升级为 JSON 编解码）。
 *
 * @author wxd-gaming(無心道, 15388152619)
 **/
@Setter
@Getter
@Table(name = "t_pg_json_migrate")
public class PgJsonMigrateJsonbEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    /** 同一列改成 jsonb：实体侧声明的就是迁移目标 */
    @Column(type = "jsonb")
    private List<Integer> payload;

}
