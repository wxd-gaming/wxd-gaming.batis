package com.wxdgaming.batis.sql.mysql;

import com.wxdgaming.batis.annotation.Column;
import com.wxdgaming.batis.annotation.GeneratedValue;
import com.wxdgaming.batis.annotation.GenerationType;
import com.wxdgaming.batis.annotation.Id;
import com.wxdgaming.batis.annotation.Table;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 测试用实体 — 订单表，专用于 Bean CRUD 测试
 * <p>使用独立的表名 t_order，避免与 MysqlTemplateTest 的 t_role 冲突。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 */
@Setter
@Getter
@Table(name = "t_order", comment = "订单表")
@ToString
public class OrderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(type = "BIGINT", comment = "订单ID")
    private long id;

    @Column(length = 64, comment = "商品名称", nullable = false)
    private String title;

    @Column(type = "INT", comment = "数量", defaultValue = "1")
    private int quantity;

    @Column(type = "INT", comment = "单价（分）", defaultValue = "0")
    private int price;

    @Column(comment = "是否已支付", defaultValue = "0")
    private boolean paid;

    @Column(comment = "创建时间")
    private Date createTime;

    @Column(type = "DECIMAL(12,2)", comment = "总金额", defaultValue = "0.00")
    private BigDecimal totalAmount;

}
