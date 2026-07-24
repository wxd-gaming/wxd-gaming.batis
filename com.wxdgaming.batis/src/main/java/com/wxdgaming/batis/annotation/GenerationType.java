package com.wxdgaming.batis.annotation;

/**
 * 主键生成策略枚举
 *
 * @author wxd-gaming
 */
public enum GenerationType {

    /** 数据库自增（IDENTITY / AUTO_INCREMENT / SERIAL） */
    IDENTITY,

    /** 自动选择生成策略（由具体方言实现决定） */
    AUTO,

    /** 不自动生成，由应用层手动赋值（默认值） */
    NONE
}
