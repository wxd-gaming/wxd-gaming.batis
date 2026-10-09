package com.wxdgaming.batis.sql.query;

import com.wxdgaming.batis.exception.BadSqlGrammarException;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * SQL 片段安全工具
 * <p>为遗留的字符串拼接式 API（whereSection）提供纵深防御：
 * 拒绝明显的多语句/注释/联合注入特征。不宣称绝对安全，推荐路径是查询 DSL。</p>
 * <p>
 * 为什么还要有这一层：DSL 会从源头杜绝拼接（条件只能经 Criteria 变成占位符），但历史代码里
 * {@code queryList(cls, "status = '" + x + "'")} 这类写法不可能一夜之间清完，直接删除这些方法
 * 会破坏兼容。于是先标记 @Deprecated 再在唯一的入口加这道闸门——它是<b>拦误用，不是拦攻击者</b>，
 * 真正的修复仍然是迁移到 DSL。
 * </p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public final class SqlFragments {

    /**
     * 危险特征：分号（多语句堆叠）、注释（截断后半段条件）、UNION（窃取别的表）、
     * DML/DDL 关键字（改库）、information_schema（探测库结构）、into outfile（落盘）。
     * <p>关键字两侧加 {@code \b} 是为了不误伤列名——{@code update_time}、{@code delete_flag}
     * 这类下划线命名的合法列不会命中（下划线是单词字符，不构成边界）。</p>
     */
    private static final Pattern FORBIDDEN = Pattern.compile(
            ";|--|/\\*|#|\\bunion\\b|\\bdrop\\b|\\bdelete\\b|\\binsert\\b|\\bupdate\\b"
                    + "|\\binformation_schema\\b|\\bgrant\\b|\\binto\\s+(out|dump)file\\b",
            Pattern.CASE_INSENSITIVE);

    private SqlFragments() {
    }

    /**
     * 校验遗留 API 传入的 whereSection，命中危险特征直接拒绝
     * <p>
     * 抛 {@link BadSqlGrammarException} 而不是更"专用"的异常：这条路径本来就是废弃 API，
     * 用一个语义明确、消息里写清"请改用查询 DSL"的语法错误最容易被排查，也无需为它再扩异常体系。
     * </p>
     * <p>代价：合法值里真含有这些片段的字符串字面量（如 {@code remark = 'a--b'}）也会被拒。
     * 这是废弃路径上可接受的误伤——迁移到 DSL 用参数绑定即可绕开。</p>
     *
     * @param whereSection WHERE 子句（不含 WHERE 关键字）；null/空白原样返回（调用方据此走"无条件"分支）
     */
    public static String guardWhereSection(String whereSection) {
        if (whereSection == null || whereSection.trim().isEmpty()) {
            return whereSection;
        }
        if (FORBIDDEN.matcher(whereSection).find()) {
            throw new BadSqlGrammarException(
                    "whereSection 含有被禁止的特征（多语句/注释/UNION/系统库等），请改用参数绑定的查询 DSL: "
                            + whereSection, null, null, 0, null, null);
        }
        return whereSection;
    }

}
