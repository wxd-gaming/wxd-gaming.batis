package com.wxdgaming.batis.sql.query;

import com.wxdgaming.batis.exception.BadSqlGrammarException;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * SQL 片段安全工具
 * <p>为遗留的字符串拼接式 API（whereSection）提供纵深防御：
 * 拒绝明显的多语句/注释/联合注入特征。不宣称绝对安全，推荐路径是查询 DSL。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public final class SqlFragments {

    /** 危险特征：分号、注释、UNION、系统库、DML/DDL 关键字 */
    private static final Pattern FORBIDDEN = Pattern.compile(
            ";|--|/\\*|#|\\bunion\\b|\\bdrop\\b|\\bdelete\\b|\\binsert\\b|\\bupdate\\b"
                    + "|\\binformation_schema\\b|\\bgrant\\b|\\binto\\s+(out|dump)file\\b",
            Pattern.CASE_INSENSITIVE);

    private SqlFragments() {
    }

    /**
     * 校验遗留 API 传入的 whereSection，命中危险特征直接拒绝
     *
     * @param whereSection WHERE 子句（不含 WHERE 关键字）
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
