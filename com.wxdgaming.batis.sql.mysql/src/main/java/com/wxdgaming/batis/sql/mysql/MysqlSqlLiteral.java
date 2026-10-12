package com.wxdgaming.batis.sql.mysql;

import java.util.regex.Pattern;

/**
 * MySQL 字面量转义工具：DDL 拼接里字符串字面量的唯一出口。
 * <p>
 * 为什么必须收口到一个工具：{@code CREATE TABLE} 与 {@code MODIFY COLUMN} 是两条独立的渲染路径，
 * 早期各写一份 {@code replace("'", "\\'")}，结果两处都漏了反斜杠与回车——注释里写 Windows 路径
 * 或 {@code \n} 字面量时，MySQL 会把反斜杠当转义引导符消费，轻则 DDL 解析失败，重则注释被静默改写。
 * </p>
 * <p>
 * 转义策略（在 MySQL 默认 sql_mode 下正确，在 {@code NO_BACKSLASH_ESCAPES} 下也仍然安全）：
 * 单引号<b>双写</b>成 {@code ''}（两种模式下都表示一个引号），反斜杠与换行/回车/NUL 用反斜杠转义。
 * </p>
 * <p>
 * {@code DEFAULT} 值按设计就是"SQL 字面量"（可以是 {@code 0}、{@code CURRENT_TIMESTAMP} 这类裸写法），
 * 因此不能无脑加引号：已带引号的按字符串转义，裸字面量走白名单校验，两者之外直接拒绝并给出可读报错——
 * 与其把 {@code ); DROP TABLE ...} 拼进启动期 DDL，不如在渲染阶段就失败。
 * </p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-12
 */
final class MysqlSqlLiteral {

    /**
     * 裸字面量白名单：数字、小数、负数、函数调用、关键字、类型转换等。
     * <p>刻意不允许引号、反斜杠、反引号、分号、空格——它们要么该走"字符串字面量"分支，
     * 要么就是可疑输入。</p>
     */
    private static final Pattern SAFE_BARE = Pattern.compile("[A-Za-z0-9_$+.\\-:()]+");

    private MysqlSqlLiteral() {
    }

    /**
     * 渲染 {@code DEFAULT} 子句里的字面量。
     *
     * @param raw 注解里书写的默认值（字符串形态请自带引号，如 {@code 'O''Brien'}）
     * @return 可安全拼接的字面量
     * @throws IllegalArgumentException 字面量既不是字符串形态、也不符合裸字面量白名单
     */
    static String defaultValue(String raw) {
        String value = raw.trim();
        if (value.length() >= 2 && value.charAt(0) == '\'' && value.charAt(value.length() - 1) == '\'') {
            return "'" + escapeString(value.substring(1, value.length() - 1)) + "'";
        }
        if (!SAFE_BARE.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "@Column(defaultValue=...) 含无法识别的字面量: " + raw
                            + "；字符串请写成 'xxx' 形式，数字/函数/关键字请只使用字母数字与 _ $ + - . : ( ) 字符");
        }
        return value;
    }

    /**
     * 转义要放进单引号里的字符串内容（列/表注释等）。
     *
     * @param raw 原始文本
     * @return 已转义文本（调用方负责外加单引号）
     */
    static String escapeString(String raw) {
        StringBuilder sb = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '\'' -> sb.append("''");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\0' -> sb.append("\\0");
                case '\032' -> sb.append("\\Z");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
