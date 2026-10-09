package com.wxdgaming.batis;

/**
 * 把实体类写入 db 的处理器（写侧回调接口）。
 * <p>与读侧 {@link DbToBeanHandler} 对称：模板方法把"取连接 / 事务 / 统计"等骨架交给具体 DbTemplate，
 * 由实现注入真正的写库动作，从而让 core 层依赖接口而非某个数据库版本。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-24 18:35
 **/
public interface BeanToDbHandler<T extends DbTemplate> {

    /** 在给定的数据模板上下文中执行一次写入动作（插入/更新/删除由实现决定） */
    void doAction(T dbTemplate, Object bean);

}
