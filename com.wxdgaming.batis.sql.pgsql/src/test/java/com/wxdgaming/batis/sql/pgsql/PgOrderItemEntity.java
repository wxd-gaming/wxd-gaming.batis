package com.wxdgaming.batis.sql.pgsql;

import com.wxdgaming.batis.annotation.Column;
import com.wxdgaming.batis.annotation.Id;
import com.wxdgaming.batis.annotation.Index;
import com.wxdgaming.batis.annotation.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 测试用实体 — 复合主键订单明细（PG）
 * <p>用途有两个：验证主键由多列组成时，建表走的是表级 {@code PRIMARY KEY ("a","b")}、
 * 元数据回读把两列都标成主键（PG 用 {@code conkey @> ARRAY[attnum]} 判定），
 * 以及 {@code queryById/deleteById} 把两个主键值都绑进 WHERE。
 * 只测单主键的话，"复合主键只取首列"这类缺陷在 PG 上不会被发现。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 **/
@Setter
@Getter
@Table(name = "t_pg_order_item", comment = "PG 订单明细",
        indexes = @Index(columnList = "order_id, sku_code"))
public class PgOrderItemEntity {

    @Id
    @Column(comment = "订单ID")
    private long orderId;

    @Id
    @Column(length = 32, comment = "SKU编码")
    private String skuCode;

    @Column(defaultValue = "1")
    private int quantity;

    @Column(type = "NUMERIC(12,2)", defaultValue = "0.00")
    private BigDecimal price;

}
