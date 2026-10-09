package com.wxdgaming.batis.sql.mysql;

import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.SqlType;
import com.wxdgaming.batis.sql.ColumnTypeDefHandler;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MySQL 列类型定义处理器
 * <p>逻辑类型（SqlType）→ MySQL 物理类型渲染，以及物理类型规格化（消除整数显示宽度等同义写法）。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class MysqlColumnTypeDefHandler implements ColumnTypeDefHandler {

    public static final MysqlColumnTypeDefHandler INSTANCE = new MysqlColumnTypeDefHandler();

    /** 物理类型分解：基名 + 括号参数 + 尾部修饰（unsigned/zerofill 等） */
    private static final Pattern PHYSICAL_PATTERN =
            Pattern.compile("^([a-z]+)\\s*(?:\\(([^)]*)\\))?\\s*(.*)$");

    /** MySQL 物理类型基名（含同义词）→ 逻辑类型 */
    private static final Map<String, SqlType> BASE_TYPE_MAP = new HashMap<>();

    static {
        BASE_TYPE_MAP.put("tinyint", SqlType.BYTE);
        BASE_TYPE_MAP.put("smallint", SqlType.SHORT);
        BASE_TYPE_MAP.put("mediumint", SqlType.MEDIUMINT);
        BASE_TYPE_MAP.put("int", SqlType.INT);
        BASE_TYPE_MAP.put("integer", SqlType.INT);
        BASE_TYPE_MAP.put("bigint", SqlType.BIGINT);
        BASE_TYPE_MAP.put("float", SqlType.FLOAT);
        BASE_TYPE_MAP.put("double", SqlType.DOUBLE);
        BASE_TYPE_MAP.put("decimal", SqlType.DECIMAL);
        BASE_TYPE_MAP.put("numeric", SqlType.DECIMAL);
        BASE_TYPE_MAP.put("varchar", SqlType.VARCHAR);
        BASE_TYPE_MAP.put("char", SqlType.CHAR);
        BASE_TYPE_MAP.put("tinytext", SqlType.TEXT);
        BASE_TYPE_MAP.put("text", SqlType.TEXT);
        BASE_TYPE_MAP.put("mediumtext", SqlType.MEDIUMTEXT);
        BASE_TYPE_MAP.put("longtext", SqlType.LONGTEXT);
        BASE_TYPE_MAP.put("json", SqlType.JSON);
        BASE_TYPE_MAP.put("tinyblob", SqlType.BLOB);
        BASE_TYPE_MAP.put("blob", SqlType.BLOB);
        BASE_TYPE_MAP.put("mediumblob", SqlType.MEDIUMBLOB);
        BASE_TYPE_MAP.put("longblob", SqlType.LONGBLOB);
        BASE_TYPE_MAP.put("date", SqlType.DATE);
        BASE_TYPE_MAP.put("time", SqlType.TIME);
        BASE_TYPE_MAP.put("datetime", SqlType.DATETIME);
        BASE_TYPE_MAP.put("timestamp", SqlType.DATETIME);
        BASE_TYPE_MAP.put("year", SqlType.SHORT);
        BASE_TYPE_MAP.put("bit", SqlType.BYTE);
        BASE_TYPE_MAP.put("binary", SqlType.BLOB);
        BASE_TYPE_MAP.put("varbinary", SqlType.BLOB);
        BASE_TYPE_MAP.put("enum", SqlType.VARCHAR);
        BASE_TYPE_MAP.put("set", SqlType.VARCHAR);
    }

    @Override
    public String renderType(ColumnInfo col) {
        String explicit = col.getType();
        if (explicit != null && !explicit.isEmpty()) {
            return explicit;
        }
        SqlType sqlType = col.getSqlType();
        if (sqlType == null) {
            throw new IllegalArgumentException("列 " + col.getColumnName()
                    + " 既无逻辑类型(sqlType)也无物理类型(type)，无法渲染 DDL 列定义");
        }
        return switch (sqlType) {
            case BOOLEAN -> "TINYINT(1)";
            case BYTE -> "TINYINT";
            case SHORT -> "SMALLINT";
            case MEDIUMINT -> "MEDIUMINT";
            case INT -> "INT";
            case BIGINT -> "BIGINT";
            case FLOAT -> "FLOAT";
            case DOUBLE -> "DOUBLE";
            case DECIMAL -> "DECIMAL(" + paramOr(col.getLength(), 20) + "," + Math.max(col.getScale(), 0) + ")";
            case VARCHAR -> "VARCHAR(" + Math.max(col.getLength(), 1) + ")";
            case CHAR -> "CHAR(" + paramOr(col.getLength(), 1) + ")";
            case TEXT -> "TEXT";
            case MEDIUMTEXT -> "MEDIUMTEXT";
            case LONGTEXT -> "LONGTEXT";
            case BLOB -> "BLOB";
            case MEDIUMBLOB -> "MEDIUMBLOB";
            case LONGBLOB -> "LONGBLOB";
            case DATE -> "DATE";
            case TIME -> "TIME";
            case DATETIME -> "DATETIME";
            case TIMESTAMP -> "TIMESTAMP";
            case JSON -> "LONGTEXT";
        };
    }

    @Override
    public String renderNormalized(ColumnInfo col) {
        return normalize(renderType(col));
    }

    @Override
    public String normalize(String physicalType) {
        if (physicalType == null) return "";
        String t = physicalType.toLowerCase(Locale.ROOT).trim();
        Matcher m = PHYSICAL_PATTERN.matcher(t);
        if (!m.matches()) return t;
        String base = m.group(1);
        String params = m.group(2);
        String suffix = m.group(3) == null ? "" : m.group(3).trim();

        SqlType sqlType = BASE_TYPE_MAP.get(base);
        if (sqlType == null) return t;

        // 整数类型无宽度概念：int(11) / bigint(20) 均规格化为基名
        if (sqlType == SqlType.BYTE || sqlType == SqlType.SHORT || sqlType == SqlType.MEDIUMINT
                || sqlType == SqlType.INT || sqlType == SqlType.BIGINT) {
            return concat(base, suffix);
        }
        // boolean 逻辑档：任意宽度的 tinyint(1) 都归一为 tinyint(1)
        if ("tinyint".equals(base) && params != null && "1".equals(params.trim())) {
            return "tinyint(1)";
        }
        // FLOAT/DOUBLE 显示宽度（如 double(10,2)） MySQL 8 已不保留，按旧规格化语义丢弃
        if (sqlType == SqlType.FLOAT || sqlType == SqlType.DOUBLE) {
            return concat(base, suffix);
        }
        // 其余（VARCHAR/CHAR/DECIMAL/TEXT/BLOB 等）：参数是类型契约的一部分，保留
        if (params == null) return concat(base, suffix);
        String compact = params.replaceAll("\\s*,\\s*", ",").replaceAll("\\s+", "");
        return concat(base + "(" + compact + ")", suffix);
    }

    private static String concat(String type, String suffix) {
        return suffix.isEmpty() ? type : type + " " + suffix;
    }

    private static int paramOr(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    @Override
    public String dialectName() {
        return "mysql";
    }

}
