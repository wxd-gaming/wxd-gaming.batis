package com.wxdgaming.batis;

/**
 * 把实体类写入db的处理器
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-24 18:35
 **/
public interface BeanToDbHandler<T extends DbTemplate> {

    void doAction(T dbTemplate, Object bean);

}
