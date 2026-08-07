package com.wxdgaming.batis;

import lombok.Getter;
import lombok.Setter;

/**
 * 数据模板
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-24 17:30
 **/
@Getter
@Setter
public class DbTemplate {

    /** 批量提交大小，默认 500；&lt;=0 表示不分组一次性提交 */
    private int batchSize = 500;

    /** 数据库操作性能统计（线程安全） */
    private final DbStats stats = new DbStats();

}
