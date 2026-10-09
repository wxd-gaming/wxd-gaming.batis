package com.wxdgaming.batis.exception;

/**
 * 数据完整性约束违反异常基类
 * <p>唯一键、外键、非空、截断等约束冲突。</p>
 * <p>
 * 什么时候抛出（以翻译器实际映射为准）：
 * <ul>
 *   <li>MySQL 数字错误码按细分落到四个子类（这些错误码的 SQLState 都是 23000，
 *       但路由只看数字码）：1062/1467 →
 *       {@link DuplicateKeyException}，1451/1452 → {@link ForeignKeyConstraintViolationException}，
 *       1048/1364 → {@link NotNullConstraintViolationException}，
 *       1406/1265/1264 → {@link DataTruncationException}；</li>
 *   <li>SQLState 以 23 或 45 开头（完整性约束违反类）时先走消息关键字细分，
 *       四个关键字（duplicate/unique、foreign key、cannot be null、truncat/too long）
 *       都没命中才落到本类本身。</li>
 * </ul>
 * </p>
 * <p>
 * 也就是说：拿到本类型（而非某个子类）说明"确定是约束冲突，但认不出是哪一类约束"，
 * 需要读 sqlState/errorCode 或原始消息。子类存在的意义是把这种判断前移到类型上，
 * 让调用方能直接区分"唯一键冲突可以转成语义化的已存在提示"和"外键缺失要先补父行"。
 * </p>
 * <p>上层怎么应对：全支不可重试——数据或语句本身不满足约束，重试必然再失败。
 * 批量写入路径下它会触发降级逐条执行，以便定位到具体脏数据行（见 BatchResult）。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class DataIntegrityViolationException extends NonTransientDataAccessException {

    /** 合成/包装场景，仅消息与 cause */
    public DataIntegrityViolationException(String message, Throwable cause) {
        super(message, cause);
    }

    /** 方言翻译器构造，携带原始 SQL/参数/错误码/SQLState */
    public DataIntegrityViolationException(String message, String sql, Object[] params,
                                           int errorCode, String sqlState, Throwable cause) {
        super(message, sql, params, errorCode, sqlState, cause);
    }

}
