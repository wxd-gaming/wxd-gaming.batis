package com.wxdgaming.batis.sql.mysql;

import com.wxdgaming.batis.annotation.Column;
import com.wxdgaming.batis.annotation.GeneratedValue;
import com.wxdgaming.batis.annotation.GenerationType;
import com.wxdgaming.batis.annotation.Id;
import com.wxdgaming.batis.annotation.Table;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.Date;

/**
 * 测试用实体 — 订单表，用于每月分区测试
 *
 * <p>分区键 create_time 必须包含在主键中（MySQL 分区要求）。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-08-08
 */
@Setter
@Getter
@Table(name = "t_monthly_order", comment = "月度订单表-分区测试")
@ToString
public class MonthlyOrderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(type = "BIGINT", comment = "订单ID")
    private long id;

    @Id
    @Column(type = "DATETIME", comment = "创建时间（分区键）")
    private Date createTime;

    @Column(length = 64, comment = "商品名称", nullable = false)
    private String title;

    @Column(type = "INT", comment = "数量", defaultValue = "1")
    private int quantity;
}
