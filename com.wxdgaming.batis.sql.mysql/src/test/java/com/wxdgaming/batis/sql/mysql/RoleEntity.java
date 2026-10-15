package com.wxdgaming.batis.sql.mysql;

import com.wxdgaming.batis.annotation.Column;
import com.wxdgaming.batis.annotation.GeneratedValue;
import com.wxdgaming.batis.annotation.GenerationType;
import com.wxdgaming.batis.annotation.Id;
import com.wxdgaming.batis.annotation.Index;
import com.wxdgaming.batis.annotation.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 测试用实体 — 角色表，用于验证建表、查表结构、变更等全流程
 *
 * @author wxd-gaming(無心道, 15388152619)
 */
@Setter
@Getter
@Table(name = "t_role", comment = "角色表",
       indexes = @Index(columnList = "level,score"))
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

    private float price;

}
