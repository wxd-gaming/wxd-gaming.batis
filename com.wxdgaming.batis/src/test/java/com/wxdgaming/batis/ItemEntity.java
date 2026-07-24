package com.wxdgaming.batis;

import com.wxdgaming.batis.annotation.Column;
import com.wxdgaming.batis.annotation.Entity;
import com.wxdgaming.batis.annotation.Id;
import com.wxdgaming.batis.model.ColumnInfo;

import java.util.List;

/**
 * 用 @Entity 替代 @Table 的实体 —— 测试 @Entity 兼容性
 */
@Entity(name = "t_item", comment = "道具表")
public class ItemEntity {

    @Id
    @Column(type = "BIGINT", comment = "道具ID")
    private long id;

    @Column(length = 128, comment = "道具名称", nullable = false)
    private String name;

    @Column(type = "INT", comment = "数量", defaultValue = "0")
    private int count;

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getCount() { return count; }
    public void setCount(int count) { this.count = count; }
}
