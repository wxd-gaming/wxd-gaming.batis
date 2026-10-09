package com.wxdgaming.batis.sql.query;

import com.wxdgaming.batis.annotation.Column;
import com.wxdgaming.batis.annotation.GeneratedValue;
import com.wxdgaming.batis.annotation.GenerationType;
import com.wxdgaming.batis.annotation.Id;
import com.wxdgaming.batis.annotation.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 渲染层单测实体（方言无关，不连 DB）
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
@Setter
@Getter
@Table(name = "t_dsl_test", comment = "DSL 单测表")
public class DslTestEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(type = "BIGINT", comment = "主键")
    private long id;

    @Column(length = 64, comment = "名称", nullable = false)
    private String userName;

    @Column(type = "DECIMAL(12,2)", comment = "金额")
    private BigDecimal amount;

    @Column(type = "INT", comment = "状态")
    private int status;

    @Column(comment = "创建时间")
    private Date createTime;

}
