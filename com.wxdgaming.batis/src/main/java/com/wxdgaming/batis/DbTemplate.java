package com.wxdgaming.batis;

import lombok.Getter;
import lombok.Setter;

/**
 * 数据模板基类：持有与"一次数据访问"相关的通用配置与统计，各方言模板（sql 层 JdbcTemplate 等）继承扩展。
 * <p>只放跨方言共用的东西（批量大小、性能统计），数据库连接、事务、具体 handler 交由子类，
 * 从而 core 不反向依赖任何数据库实现。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-24 17:30
 **/
@Getter
@Setter
public class DbTemplate {

    /** 批量提交大小，默认 500；&lt;=0 表示不分组一次性提交 */
    private int batchSize = 500;

    /**
     * 数据库操作性能统计（线程安全）。
     * <p>final 且随模板生命周期常驻：所有实例共享同一份累计指标，reset 才清零。</p>
     */
    private final DbStats stats = new DbStats();

}
