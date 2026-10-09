package com.wxdgaming.batis.sql;

import com.wxdgaming.batis.exception.DataAccessException;

import java.sql.SQLException;

/**
 * SQL 异常翻译接口（方言 SPI）
 * <p>把 JDBC 的 {@link SQLException} 翻译成统一的 {@link DataAccessException} 层次，
 * 由各数据库方言模块提供精确实现（如 MySQL 数字错误码映射），
 * 未注册方言实现时由 {@link DefaultSqlExceptionHandler} 按 SQLState/关键字兜底。</p>
 * <p>这层翻译存在的理由：业务代码只能捕获统一的异常体系，而"该不该重试"这件事
 * 完全依赖异常落在瞬时/非瞬时哪条主干上（{@code DataAccessException#isRetryable()}）。
 * 批量降级的逐条补写路径就是靠它决定是上抛还是继续，翻译错一支——比如把死锁译成
 * 通用异常——本该重试的故障会直接失败，或该快速失败的数据错误被无限重试。</p>
 * <p>方言实现要遵守的契约：</p>
 * <ul>
 *   <li>任何输入都返回一个异常实例，绝不返回 null，也不在本方法里再抛别的异常；</li>
 *   <li>保留原始 SQLException 作为 cause，并带上 SQL 文本与绑定参数、错误码和 SQLState，
 *       否则线上只留下一句无法复现的中文描述；</li>
 *   <li>数字错误码未命中时委托 {@link DefaultSqlExceptionHandler#translate}，
 *       让驱动侧异常（错误码为 0 那一类）仍有合理归类；</li>
 * </ul>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public interface SqlExceptionHandler {

    /**
     * 翻译 SQLException
     * <p>实现方必须保证翻译是纯函数式的：不吞异常链、不改变入参、不做重试或日志副作用
     * （调用点在 catch 分支里，副作用会随异常路径重复执行）。</p>
     * <p>拿不准归哪类时归到无法分类的异常，保持"未知即不重试"，
     * 不要为了让日志好看而硬套一个语义更强的类型。</p>
     *
     * @param ex     原始 SQL 异常，翻译的唯一依据
     * @param sql    触发异常的 SQL（可为 null，仅用于诊断信息）
     * @param params 绑定参数（可为 null）
     * @return 统一的 DataAccessException 子类，永不为 null
     */
    DataAccessException translate(SQLException ex, String sql, Object[] params);

    /**
     * 方言名，用于日志与诊断
     * <p>异常归错类时先要能看出是哪套方言干的；未覆盖即表示注册的是兜底实现。</p>
     */
    default String dialectName() {
        return "generic";
    }

}
