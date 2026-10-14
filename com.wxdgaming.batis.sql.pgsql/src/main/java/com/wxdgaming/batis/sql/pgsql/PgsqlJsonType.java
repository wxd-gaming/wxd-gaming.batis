package com.wxdgaming.batis.sql.pgsql;

import com.wxdgaming.batis.model.ColumnInfo;

import java.util.Locale;

/**
 * PG 原生 JSON 列的判定：识别实体用 {@code @Column(type = "JSON")} / {@code @Column(type = "JSONB")}
 * 显式声明的那一列，并交出可直接用于 cast 的类型名。
 *
 * <p><b>为什么选型走 {@code type} 字面量，而不是新增一个 StorageMode 档位</b>：
 * {@link com.wxdgaming.batis.model.StorageMode StorageMode} 是<b>写入侧策略</b>（这个字段怎么变成列值），
 * JSON 档的产物本来就是 JSON 字符串，json、jsonb、text 三种物理列都收得下；
 * 而"这一列在库里是什么类型"是物理描述，仓库里已经为此准备了逃生舱
 * （{@code @Column(type=...)，填写后跳过推断原样用于 DDL}）。用现成的通道表达选型，
 * 方言不必新增枚举，MySQL 侧也一行都不用改。</p>
 *
 * <p><b>为什么 json/jsonb 不注册进 {@link PgsqlColumnTypeDefHandler} 的基名表</b>：
 * 注册等于把它们折到逻辑档 {@code SqlType.JSON}，而 {@code CANONICAL} 里 JSON 折成比较键
 * {@code "text"}——于是"实体声明 jsonb、库里还是 text"这种<b>真实</b>漂移会被判成相等而永远不修，
 * 恰好把用户显式选型的意义丢掉。留在表外则走"未识别基名原样返回"那条路，
 * {@code "jsonb"} 与 {@code "text"} 是两个不同的键，漂移可见、可修。</p>
 *
 * <p>本类只做判定，不碰驱动类型；把 JSON 字符串包成 {@code PGobject} 的动作在
 * {@link PgsqlBeanValueHandler}，因为那是绑定参数时才需要的形态。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-14
 */
final class PgsqlJsonType {

    /** PG 的原始 JSON 档：按文本存、保序、几乎不校验 */
    static final String JSON = "json";
    /** PG 的二进制 JSON 档：解析后存储，键序与空白被规范化 */
    static final String JSONB = "jsonb";

    private PgsqlJsonType() {
    }

    /**
     * 实体列是否声明为 PG 原生 JSON 档。
     *
     * @param col 实体列元数据
     * @return {@link #JSON} 或 {@link #JSONB}（小写，可直接拼进 cast）；非 JSON 档返回 null
     */
    static String of(ColumnInfo col) {
        return col == null ? null : of(col.getType());
    }

    /**
     * 物理类型字面量是否属于 JSON 档，实体侧写法（{@code JSONB}）与库端回读形态（{@code jsonb}）都接受。
     * <p>判定复用 {@code normalize} 而不是自己比字符串：大小写、多余空白、{@code json  /  JSONB}
     * 这类手写差异都要先折平，否则同一条列在两条路径上会被认成不同的档。</p>
     */
    static String of(String physicalType) {
        if (physicalType == null || physicalType.isEmpty()) {
            return null;
        }
        String normalized = PgsqlColumnTypeDefHandler.INSTANCE.normalize(physicalType);
        return (JSON.equals(normalized) || JSONB.equals(normalized)) ? normalized : null;
    }

    /**
     * 物理类型字面量是否属于文本族（{@code text} / {@code character varying(n)} / {@code character(n)}，
     * 含 {@code citext} 这类折到 text 的同义词）。
     * <p>只有文本族与 JSON 族之间才需要显式 {@code USING}：PG 对这两族没有隐式赋值转换，
     * 直接 {@code ALTER COLUMN ... TYPE jsonb} 会 42846，见
     * {@link PgsqlTableAlterHandler} 的类型子句。</p>
     */
    static boolean isTextFamily(String physicalType) {
        if (physicalType == null || physicalType.isEmpty()) {
            return false;
        }
        String normalized = PgsqlColumnTypeDefHandler.INSTANCE.normalize(physicalType)
                .toLowerCase(Locale.ROOT);
        return normalized.equals("text")
                || normalized.startsWith("character varying")
                || normalized.startsWith("character");
    }

}
