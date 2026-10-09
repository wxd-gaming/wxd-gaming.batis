package com.wxdgaming.batis.exception;

/**
 * 期望单条结果但命中多条
 * <p>
 * 什么时候抛出：查询 DSL 的 one()（内部按 LIMIT 2 取，只要多于 1 条就抛）。
 * 注意 0 条不算错误，one() 返回 null 由调用方判空；只有"应当唯一却拿到多条"才抛这里。
 * </p>
 * <p>
 * 为什么要显式失败而不是默默取第一条：调用 one() 就是在声明"这个条件业务上唯一"
 * （按账号、按订单号、按 (server_id, player_id) 这类组合）。一旦数据出现重复——唯一键没建、
 * 脏数据、条件写宽了——取第一条会让程序静默使用一条可能错误的记录继续跑，
 * 后续所有基于它的判断都不可信。宁可让这次请求失败，也不要污染数据。
 * </p>
 * <p>
 * 上层怎么应对：不可重试（继承 {@link NonTransientDataAccessException}）。
 * 三条正路：给该列补唯一约束；把意图改成"取一条"改用 first()（自动 LIMIT 1）；
 * 或者本来就允许多条，改成 list() 自己处理。
 * </p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class TooManyResultsException extends NonTransientDataAccessException {

    /** 主用法：消息里带上实体名与触发的 SQL，便于定位是哪条查询的意图不成立 */
    public TooManyResultsException(String message) {
        super(message);
    }

    /** 需要保留底层异常时使用 */
    public TooManyResultsException(String message, Throwable cause) {
        super(message, cause);
    }

}
