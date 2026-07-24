package com.wxdgaming.batis;

import com.wxdgaming.batis.annotation.Column;
import com.wxdgaming.batis.annotation.GeneratedValue;
import com.wxdgaming.batis.annotation.GenerationType;
import com.wxdgaming.batis.annotation.Id;
import com.wxdgaming.batis.annotation.Index;
import com.wxdgaming.batis.annotation.Table;

/**
 * 玩家表 —— 完整注解实体，用于测试
 */
@Table(name = "t_player", comment = "玩家表",
       indexes = @Index(name = "idx_srv_level", columnList = "server_id,level", unique = false))
public class PlayerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(type = "BIGINT", comment = "玩家ID")
    private long id;

    @Column(name = "player_name", length = 64, comment = "昵称", defaultValue = "''", nullable = false)
    private String name;

    @Column(type = "INT", comment = "等级", defaultValue = "1")
    private int level;

    @Index(unique = true)
    @Column(length = 32, comment = "账号", nullable = false)
    private String account;

    @Index
    @Column(type = "INT", comment = "服务器ID")
    private int serverId;

    @Column(comment = "是否在线", defaultValue = "0")
    private boolean online;

    @Column(comment = "创建时间")
    private java.util.Date createTime;

    @Column(type = "DECIMAL(20,2)", comment = "金币", defaultValue = "0.00")
    private java.math.BigDecimal gold;

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getLevel() { return level; }
    public void setLevel(int level) { this.level = level; }
    public String getAccount() { return account; }
    public void setAccount(String account) { this.account = account; }
    public int getServerId() { return serverId; }
    public void setServerId(int serverId) { this.serverId = serverId; }
    public boolean isOnline() { return online; }
    public void setOnline(boolean online) { this.online = online; }
    public java.util.Date getCreateTime() { return createTime; }
    public void setCreateTime(java.util.Date createTime) { this.createTime = createTime; }
    public java.math.BigDecimal getGold() { return gold; }
    public void setGold(java.math.BigDecimal gold) { this.gold = gold; }
}
