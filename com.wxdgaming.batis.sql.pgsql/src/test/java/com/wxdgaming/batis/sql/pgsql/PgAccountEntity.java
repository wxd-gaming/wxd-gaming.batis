package com.wxdgaming.batis.sql.pgsql;

import com.wxdgaming.batis.annotation.Column;
import com.wxdgaming.batis.annotation.GeneratedValue;
import com.wxdgaming.batis.annotation.GenerationType;
import com.wxdgaming.batis.annotation.Id;
import com.wxdgaming.batis.annotation.Index;
import com.wxdgaming.batis.annotation.Table;
import com.wxdgaming.batis.model.StorageMode;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 测试用实体 — 账号表（PG）
 * <p>刻意把自增主键放在第二个字段：pgjdbc 的 {@code RETURN_GENERATED_KEYS} 是
 * {@code RETURNING *}，生成键结果集的第一列因此是 {@code account_name}（字符串）。
 * 如果取键时不按列名向驱动索取，就会把账号名当成 ID 回写，而这类错配只在 PG 上出现。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 **/
@Setter
@Getter
@Table(name = "t_pg_account", comment = "PG 账号表",
        indexes = @Index(name = "idx_pg_account_name", columnList = "account_name", unique = true))
public class PgAccountEntity {

    @Column(length = 64, comment = "账号名", nullable = false)
    private String accountName;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(comment = "账号ID")
    private long id;

    @Column(type = "NUMERIC(12,2)", comment = "余额", defaultValue = "0.00")
    private BigDecimal balance;

    @Column(comment = "VIP等级", defaultValue = "0")
    private int vipLevel;

    @Column(comment = "最后登录时间")
    private LocalDateTime lastLoginTime;

    @Column(comment = "注册日期")
    private LocalDate registerDate;

    @Column(comment = "是否冻结", defaultValue = "0")
    private boolean frozen;

    @Column(comment = "标签", storageMode = StorageMode.JSON)
    private List<String> tags;

}
