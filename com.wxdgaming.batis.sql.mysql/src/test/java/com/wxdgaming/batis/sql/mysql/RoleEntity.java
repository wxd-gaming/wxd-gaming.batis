package com.wxdgaming.batis.sql.mysql;

import com.wxdgaming.batis.annotation.Column;
import com.wxdgaming.batis.annotation.GeneratedValue;
import com.wxdgaming.batis.annotation.GenerationType;
import com.wxdgaming.batis.annotation.Id;
import com.wxdgaming.batis.annotation.Index;
import com.wxdgaming.batis.annotation.Table;

/**
 * 测试用实体 — 角色表，用于验证建表、查表结构、变更等全流程
 *
 * @author wxd-gaming(無心道, 15388152619)
 */
@Table(name = "t_role", comment = "角色表",
       indexes = @Index(name = "idx_level_score", columnList = "level,score", unique = false))
public class RoleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(type = "BIGINT", comment = "角色ID")
    private long id;

    @Column(length = 64, comment = "角色名", defaultValue = "''", nullable = false)
    private String name;

    @Column(type = "INT", comment = "等级", defaultValue = "1")
    private int level;

    @Index(unique = true)
    @Column(length = 64, comment = "账号", nullable = false)
    private String account;

    @Column(type = "INT", comment = "积分", defaultValue = "0")
    private int score;

    @Column(comment = "是否禁言", defaultValue = "0")
    private boolean banned;

    @Column(comment = "创建时间")
    private java.util.Date createTime;

    @Column(type = "DECIMAL(12,2)", comment = "充值金额", defaultValue = "0.00")
    private java.math.BigDecimal recharge;

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getLevel() { return level; }
    public void setLevel(int level) { this.level = level; }
    public String getAccount() { return account; }
    public void setAccount(String account) { this.account = account; }
    public int getScore() { return score; }
    public void setScore(int score) { this.score = score; }
    public boolean isBanned() { return banned; }
    public void setBanned(boolean banned) { this.banned = banned; }
    public java.util.Date getCreateTime() { return createTime; }
    public void setCreateTime(java.util.Date createTime) { this.createTime = createTime; }
    public java.math.BigDecimal getRecharge() { return recharge; }
    public void setRecharge(java.math.BigDecimal recharge) { this.recharge = recharge; }
}
