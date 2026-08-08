package com.wxdgaming.batis.sql.mysql;

import com.wxdgaming.batis.annotation.Column;
import com.wxdgaming.batis.annotation.Id;
import com.wxdgaming.batis.annotation.Index;
import com.wxdgaming.batis.annotation.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 性能测试专用实体 — 手工指定 ID（时间戳纳秒），不使用数据库自增。
 *
 * @author wxd-gaming(無心道, 15388152619)
 */
@Setter
@Getter
@Table(name = "t_account_manual", comment = "账号表(手动ID)",
       indexes = @Index(name = "idx_manual_account_name", columnList = "account_name", unique = true))
public class AccountEntityManualId {

    @Id
    @Column(type = "BIGINT", comment = "账号ID")
    private long id;

    @Column(length = 64, comment = "账号名", nullable = false)
    private String accountName;

    @Column(type = "DECIMAL(12,2)", comment = "余额", defaultValue = "0.00")
    private BigDecimal balance;

    @Column(type = "INT", comment = "VIP等级", defaultValue = "0")
    private int vipLevel;

    @Column(comment = "最后登录时间")
    private Date lastLoginTime;

    @Column(comment = "是否冻结", defaultValue = "0")
    private boolean frozen;

}
