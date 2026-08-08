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
 * 测试用实体 — 每日日志表，用于分区功能测试
 *
 * <p>分区键 log_time 必须包含在主键中（MySQL 分区要求）。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-08-08
 */
@Setter
@Getter
@Table(name = "t_daily_log", comment = "每日日志表-分区测试")
@ToString
public class DailyLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(type = "BIGINT", comment = "日志ID")
    private long id;

    @Id
    @Column(type = "DATETIME", comment = "日志时间（分区键）")
    private Date logTime;

    @Column(length = 128, comment = "日志内容")
    private String content;

    @Column(type = "INT", comment = "日志级别", defaultValue = "0")
    private int level;
}
