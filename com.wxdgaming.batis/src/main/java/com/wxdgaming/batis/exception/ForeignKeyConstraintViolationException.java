package com.wxdgaming.batis.exception;

/**
 * 外键约束违反异常
 * <p>
 * 什么时候抛出（以翻译器实际映射为准）：
 * <ul>
 *   <li>MySQL 错误码 1451（ER_ROW_IS_REFERENCED_2，父行仍被子行引用，无法更新/删除）、
 *       1452（ER_NO_REFERENCED_ROW_2，外键找不到对应父行）；</li>
 *   <li>SQLState 23/45 类别下消息含 "foreign key"，以及消息含 "foreign key" 的关键字兜底。</li>
 * </ul>
 * 这两个方向正好相反：1452 是"插入/更新了不存在的父"，1451 是"删除/更新了仍被引用的父"。
 * 二者都归本类，调用方如需区分要看 getErrorCode()。
 * </p>
 * <p>
 * 上层怎么应对：不可重试，且通常不是终端用户的错，而是数据一致性被破坏或初始化顺序错了
 * （先建子后建父、测试库缺父行）。业务侧应转成明确的"关联数据不存在/仍被引用"提示，
 * 或者在 DDL 层配置级联与延迟检查；靠 catch 住它继续往下写会把不一致扩散。
 * </p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class ForeignKeyConstraintViolationException extends DataIntegrityViolationException {

    /** 合成/包装场景，仅消息与 cause */
    public ForeignKeyConstraintViolationException(String message, Throwable cause) {
        super(message, cause);
    }

    /** 方言翻译器构造，携带原始 SQL/参数/错误码/SQLState */
    public ForeignKeyConstraintViolationException(String message, String sql, Object[] params,
                                                  int errorCode, String sqlState, Throwable cause) {
        super(message, sql, params, errorCode, sqlState, cause);
    }

}
