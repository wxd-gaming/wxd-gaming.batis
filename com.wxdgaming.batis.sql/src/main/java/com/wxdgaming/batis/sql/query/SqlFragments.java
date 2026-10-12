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
     * 校验遗留 API 传入的 whereSection（等价于"没有任何参数"的调用）。
     * <p>
     * 抛 {@link BadSqlGrammarException} 而不是更"专用"的异常：这条路径本来就是废弃 API，
     * 用一个语义明确、消息里写清"请改用查询 DSL"的语法错误最容易被排查，也无需为它再扩异常体系。
     * </p>
     * <p>
     * 校验规则与带参重载<b>完全一致</b>（含"无参时不得出现字符串字面量"）。之所以强调这一点：
     * 两个重载若各管一套规则，调用方写 {@code guardWhereSection(拼接串)} 就会绕过新校验——
     * 而"拼接串"恰恰是最需要拦的那一种。
     * </p>
     * <p>代价：合法值里含这些特征的字面量（如 {@code remark = 'a--b'}）也会被拒，
     * 属于废弃路径上可接受的误伤——迁移到 DSL 用参数绑定即可绕开。</p>
     *
     * @param whereSection WHERE 子句（不含 WHERE 关键字）；null/空白原样返回（调用方据此走"无条件"分支）
     */
    public static String guardWhereSection(String whereSection) {
        return guardWhereSection(whereSection, 0);
    }

    /**
     * 校验 whereSection，并额外核对"占位符数量 == 参数数量"，
     * 拒绝"夹带字符串字面量却不传参"的写法。
     * <p>
     * 为什么需要这两条：黑名单拦的是"特征明显"的攻击，而最常见的拼接误用
     * {@code "name = '" + userInput + "'"} 结果形如 {@code name = '' OR '1'='1}，
     * 里面既没有分号也没有 UNION，黑名单完全放行——条件被绕过、退化成全表读取。
     * 强制"要么用占位符 + 参数，要么不出现引号"能把这条路径堵死：参数化之后，
     * 值无论长什么样都只是数据。
     * </p>
     *
     * @param whereSection WHERE 子句（不含 WHERE 关键字）
     * @param params       与占位符一一对应的参数
     * @return 校验通过的 whereSection（null/空白原样返回）
     */
    public static String guardWhereSection(String whereSection, Object... params) {
        return guardWhereSection(whereSection, params == null ? 0 : params.length);
    }

    /** 两个公开重载的唯一实现：provided 为调用方声明的参数个数 */
    private static String guardWhereSection(String whereSection, int provided) {
        if (whereSection == null || whereSection.trim().isEmpty()) {
            return whereSection;
        }
        if (FORBIDDEN.matcher(whereSection).find()) {
            throw new BadSqlGrammarException(
                    "whereSection 含有被禁止的特征（多语句/注释/UNION/系统库等），请改用参数绑定的查询 DSL: "
                            + whereSection, null, null, 0, null, null);
        }
        int placeholders = countPlaceholders(whereSection);
        if (placeholders != provided) {
            throw new BadSqlGrammarException(
                    "whereSection 的占位符数量(" + placeholders + ")与参数数量(" + provided
                            + ")不一致，参数会绑到错误的位置: " + whereSection, null, null, 0, null, null);
        }
        if (provided == 0 && whereSection.indexOf('\'') >= 0) {
            throw new BadSqlGrammarException(
                    "whereSection 含字符串字面量却没有参数，请改写成占位符 + 参数的形式"
                            + "（例如 name = ? 然后传参）：把值拼进 SQL 就是注入的入口: " + whereSection,
                    null, null, 0, null, null);
        }
        return whereSection;
    }

    /**
     * 统计字符串字面量之外的 {@code ?} 个数（{@code ''} 转义按一个引号处理）。
     * <p>只用于数量校验，不做 SQL 解析：真正的安全性来自"值走参数绑定"，
     * 这条校验的作用是尽早暴露"参数写少了/写多了"这类错位。</p>
     */
    private static int countPlaceholders(String fragment) {
        int count = 0;
        boolean inLiteral = false;
        for (int i = 0; i < fragment.length(); i++) {
            char c = fragment.charAt(i);
            if (c == '\'') {
                if (inLiteral && i + 1 < fragment.length() && fragment.charAt(i + 1) == '\'') {
                    i++;
                    continue;
                }
                inLiteral = !inLiteral;
            } else if (c == '?' && !inLiteral) {
                count++;
            }
        }
        return count;
    }

}
