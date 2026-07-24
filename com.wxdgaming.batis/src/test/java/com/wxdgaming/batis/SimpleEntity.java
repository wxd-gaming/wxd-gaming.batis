package com.wxdgaming.batis;

import com.wxdgaming.batis.annotation.Column;
import com.wxdgaming.batis.annotation.Id;
import com.wxdgaming.batis.model.TableInfo;

/**
 * 简化实体 —— 无任何注解，用于测试自动推断逻辑
 */
public class SimpleEntity {

    @Id
    private long id;

    private String name;

    private int age;

    private boolean active;

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getAge() { return age; }
    public void setAge(int age) { this.age = age; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
