package com.wxdgaming.batis.sql.pgsql;

import com.wxdgaming.batis.annotation.Column;
import com.wxdgaming.batis.annotation.GeneratedValue;
import com.wxdgaming.batis.annotation.GenerationType;
import com.wxdgaming.batis.annotation.Id;
import com.wxdgaming.batis.annotation.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 结构迁移用例的"改造前"实体 —— 同一张表 {@code t_pg_json_migrate} 的文本列形态。
 * <p>与 {@link PgJsonMigrateJsonbEntity} 成对使用：字段名相同（{@code payload} → 同一列），
 * 只有物理类型不同。先按本实体建表灌数据，再按对面实体同步，验证
 * {@code ALTER COLUMN ... TYPE jsonb USING "payload"::jsonb} 这条迁移路径真的能走通。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 **/
@Setter
@Getter
@Table(name = "t_pg_json_migrate")
public class PgJsonMigrateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    /** 文本形态：JSON 只是一个恰好合法的字符串，列本身没有任何结构 */
    @Column(type = "TEXT")
    private String payload;

}
