package com.wxdgaming.batis.sql.pgsql;

import java.util.regex.Pattern;

/**
 * PostgreSQL 字面量转义工具：DDL 拼接里字符串字面量的唯一出口。
 * <p>
 * 与 MySQL 版本的关键差别是<b>不做反斜杠转义</b>：PG 默认 {@code standard_conforming_strings=on}，
 * 字符串里的反斜杠就是普通字符，照抄 MySQL 的 {@code \\}、{@code \n} 会把字面量写成
 * 两个字符 {@code \n} 存进注释里（而不是换行），事后看不出被改写过。
 * 标准 SQL 的转义只有一种：单引号双写。
 * </p>
 * <p>
 * {@code DEFAULT} 值按设计就是"SQL 字面量"（{@code 0}、{@code CURRENT_TIMESTAMP} 这类裸写法都合法），
 * 因此不能无脑加引号：已带引号的按字符串转义，裸字面量走白名单校验，两者之外直接拒绝并给出可读报错——
 * 与其把半截 DDL 发到库端，不如在渲染阶段就失败。
 * 日期时间类默认值直接写成带引号的字符串（{@code '2026-01-01'}）即可：PG 在建表时按列类型转换该字面量，
 * 不必也不应写成 {@code '2026-01-01'::timestamp}——带类型转换的形态混合了引号与裸字符，
 * 两条分支都判不通过，而回读侧（{@code PgsqlDbMetaHandler}）折回来的形式也是带引号的那个。
 * </p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-14
 */
final class PgsqlSqlLiteral {

    /**
     * 裸字面量白名单：数字、小数、负数、函数调用、关键字、类型转换等。
     * <p>刻意不允许引号、反斜杠、分号、空格——它们要么该走"字符串字面量"分支，要么就是可疑输入。
     * {@code nextval('t_id_seq'::regclass)} 会因为内含单引号而被拒：那是库端回读的默认值形态，
     * 不该由实体注解书写。</p>
     */
    private static final Pattern SAFE_BARE = Pattern.compile("[A-Za-z0-9_$+.\\-:()]+");

    private PgsqlSqlLiteral() {
    }

    /**
     * 渲染 {@code DEFAULT} 子句里的字面量。
     * <p><b>约定：注解里写的就是 SQL 字面量本身</b>，字符串形态 {@code 'O''Brien'} 表示默认值是
     * {@code O'Brien}。因此这里对字符串形态只校验、不再二次转义——早先的实现把外层引号剥掉后再
     * {@code escapeString} 一遍，{@code ''} 变成 {@code ''''}，PG 存进去就成了字面的两个引号，
     * 而元数据回读给出的仍是 {@code 'O''Brien'}（它展示的是真实值 {@code O'Brien}），
     * 两侧比一次差一次，每次启动都发一条 {@code ALTER COLUMN ... SET DEFAULT}。</p>
     *
     * @param raw 注解里书写的默认值（字符串形态请自带引号并把内部引号双写成 {@code ''}）
     * @return 可安全拼接的字面量
     * @throws IllegalArgumentException 字符串形态内部引号没有成对出现，或裸写法不符合白名单
     */
    static String defaultValue(String raw) {
        String value = raw.trim();
        if (value.length() >= 2 && value.charAt(0) == '\'' && value.charAt(value.length() - 1) == '\'') {
            assertWellFormedStringLiteral(value);
            return value;
        }
        if (!SAFE_BARE.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "@Column(defaultValue=...) 含无法识别的字面量: " + raw
                            + "；字符串请写成 'xxx' 形式（内部单引号双写成 ''），数字/函数/关键字请只使用字母数字与 _ $ + - . : ( ) 字符");
        }
        return value;
    }

    /**
     * 校验一个"看着像字符串字面量"的输入是否真的合法：首尾各一个引号，内部的引号必须成对出现。
     * <p>为什么不能放行不合法的引号：{@code 'a'b'} 会被拼进 DDL 的默认值位置，PG 解析时把 {@code a}
     * 当成字面量、{@code b'} 当成裸标识符，报错是 {@code column "b" does not exist}——离真正的原因
     * （注解写错）隔了一层，很难查。在这里当场拒绝，并把有问题的原文带到消息里。
     * NUL 也一并拒绝：PG 的 text 存不下 0x00。</p>
     */
    private static void assertWellFormedStringLiteral(String value) {
        if (value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("默认值字面量不能包含 \\0: " + value);
        }
        int quotesInRun = 0;
        for (int i = 1; i < value.length() - 1; i++) {
            if (value.charAt(i) == '\'') {
                quotesInRun++;
            } else if (quotesInRun % 2 != 0) {
                throw new IllegalArgumentException(
                        "默认值字符串里的单引号没有成对双写（'' 表示一个引号）: " + value);
            } else {
                quotesInRun = 0;
            }
        }
        // 收尾的引号串也必须是偶数：形如 'abc''' 这种"值以引号结尾"要写成 'abc'''''
        if (quotesInRun % 2 != 0) {
            throw new IllegalArgumentException(
                    "默认值字符串里的单引号没有成对双写（'' 表示一个引号）: " + value);
        }
    }

    /**
     * 转义要放进单引号里的字符串内容（列/表注释等）。
     * <p>NUL 必须当场拒绝：PG 的 text 无法存储 0x00，塞进去会在执行期报
     * "invalid byte sequence"/"cannot convert"，而报错里不会提是哪一列的注释。</p>
     *
     * @param raw 原始文本
     * @return 已转义文本（调用方负责外加单引号）
     */
    static String escapeString(String raw) {
        if (raw.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("PostgreSQL 的文本列不能包含 \\0，请清理该注释: " + raw);
        }
        // 只双写单引号；换行/制表符原样保留（标准 SQL 允许字面量内含裸换行）
        return raw.replace("'", "''");
    }
}
