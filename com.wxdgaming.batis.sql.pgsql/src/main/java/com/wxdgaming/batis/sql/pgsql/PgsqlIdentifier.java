package com.wxdgaming.batis.sql.pgsql;

import java.util.Locale;

/**
 * PostgreSQL 标识符引用：全方言唯一的引号出口。
 * <p>
 * 为什么要单独收口：MySQL 侧是在建表/改表/元数据各处内联拼反引号，二十多个散落点；
 * PostgreSQL 用的是双引号，若照原样分散翻译，任何一个漏改的点都会生成"看着对、跑不了"的 SQL。
 * 所有标识符都从这里出去，引号字符与大小写策略就只有一个人说了算。
 * </p>
 * <p>
 * 大小写策略与引号字符是一套的（见 {@code BeanToSelectHandler#quote} 的约定）：
 * 框架的表结构缓存键、列名查找、{@code PartitionBuilder} 一律按小写口径运作，
 * pg_catalog/information_schema 回读到的也是小写名，所以这里同样是"双引号 + 强制小写"。
 * 若改成双引号保留原大小写，建表写出 {@code "tRole"} 而查询按缓存用小写名，
 * 结果是每次访问都 42P01（表不存在）——只在换库时暴露的那类错配。
 * </p>
 * <p>
 * 强制加引号的收益：保留字列名（{@code order}、{@code state}、{@code user}）无需特殊处理，
 * 也不必依赖 PG 的大小写折叠规则。
 * </p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-14
 */
public final class PgsqlIdentifier {

    private PgsqlIdentifier() {
    }

    /**
     * 把未引用的表名/列名/索引名转成 PostgreSQL 认可的双引号形式。
     *
     * @param identifier 原始标识符，不可为 null
     * @return 形如 {@code "t_role"}；标识符内部的双引号按标准写法双写
     */
    public static String quote(String identifier) {
        // 必须用 Locale.ROOT：PG 折叠未加引号的标识符走的是服务器侧的固定规则，
        // 而默认 Locale 为土耳其语的 JVM 会把大写 I 折成 dotless ı，
        // 于是 ID 被写成 "ıd"，与库端真实列名对不上，报错还是一句"column does not exist"
        return '"' + identifier.toLowerCase(Locale.ROOT).replace("\"", "\"\"") + '"';
    }

    /**
     * 带 schema 限定的引用（{@code COMMENT ON COLUMN "t"."c"} 这类语句用）。
     *
     * @param first  前段标识符（表名）
     * @param second 后段标识符（列名）
     * @return 形如 {@code "t_role"."level"}
     */
    public static String quote(String first, String second) {
        return quote(first) + "." + quote(second);
    }
}
