package com.wxdgaming.batis.sql.partition;

/**
 * 单个分区定义（通用，与具体数据库无关）
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-08-08
 */
public class PartitionDef {

    private final String name;
    private final String value;

    public PartitionDef(String name, String value) {
        this.name = name;
        this.value = value;
    }

    public String getName() { return name; }
    public String getValue() { return value; }

    @Override
    public String toString() {
        return name + ": " + value;
    }
}
