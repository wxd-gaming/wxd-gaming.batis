package com.wxdgaming.batis.sql;

import com.wxdgaming.batis.model.ColumnInfo;

/**
 * 列类型定义处理器（方言 SPI）
 * <p>把 core 层的逻辑类型（{@link com.wxdgaming.batis.model.SqlType} + 长度/精度）
 * 渲染为方言物理类型字符串，并把物理类型字符串规格化为可比较形式，供 DDL 结构 diff 使用。</p>
 * <p>DDL 处理器统一通过本接口取得列物理类型，不要直接读取
 * {@link ColumnInfo#getType()} —— 自动推断的列该字段为 null，物理形态只存在于方言实现中。</p>
 * <p>三个方法为什么要拆开：渲染（实体 → 物理串）和规格化（任意物理串 → 可比较串）
 * 是两种方向的工作，漂移检测必须把<em>实体渲染结果</em>和<em>库端元数据</em>喂进同一个
 * {@link #normalize} 才能比较，否则同一类型在两侧写法不同就会天天误报变更。
 * 方言只需回答"我的类型长什么样、哪些写法等价"，diff 算法本身与数据库无关。</p>
 * <p>分层边界：物理类型字面量（VARCHAR、int(11) 这类）只能存在于方言模块和本接口的实现里，
 * 不得写进 core 的模型或 sql 层的通用逻辑——否则换一种数据库就得回去改正向渲染与比较代码。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public interface ColumnTypeDefHandler {

    /**
     * 渲染列的物理类型字符串（大写，如 MySQL 的 {@code VARCHAR(255)}）
     * <p>优先级：显式 {@code @Column(type=...)} 物理字面量原样返回；
     * 否则按 sqlType + length/scale 渲染；两者皆空时抛 IllegalArgumentException。</p>
     * <p>实现方必须保证：显式物理类型一律原样透传，不再做二次加工——用户写了什么就该建什么；
     * 逻辑类型缺失时报错而不是猜一个默认类型，静默猜类型会把错误结构写进生产库。
     * 返回值需要能安全拼进 DDL（不含分号、注释等截断语句的字符）。</p>
     *
     * @param col 列元数据（实体侧解析或库端读取均可，但通常传实体侧）
     * @return 方言物理类型字符串
     */
    String renderType(ColumnInfo col);

    /**
     * 渲染物理类型字符串并规格化为可比较形式（等价于 {@code normalize(renderType(col))}）
     * <p>存在的原因是调用点几乎都是"渲染完马上要比"，用这个方法可以保证
     * 渲染与规格化的配对不会在各调用点写歪。</p>
     * <p>漂移判定必须用它，不能只用 {@link #renderType}：库端读回的形态与实体渲染的形态
     * 常常是同义不同形（显示宽度、大小写、参数空格），直接比字符串会把没变化的表判成有变化，
     * 于是每次启动都跑一遍无意义的 MODIFY COLUMN。</p>
     *
     * @param col 列元数据
     * @return 规格化后的物理类型字符串
     */
    String renderNormalized(ColumnInfo col);

    /**
     * 规格化物理类型字符串以便比较：统一小写、消除同义写法与
     * 方言的显示宽度差异（如 MySQL 整数列的 {@code int(11)} → {@code int}）。
     * <p>这是"同义写法的判定权"所在，实现方必须保证：</p>
     * <ul>
     *   <li>幂等——对已规格化的串再调用一次结果不变，否则两侧各比一次会得出不同结论；</li>
     *   <li>只丢真正无语义的差异。长度对 VARCHAR、精度对标度是有约束语义的，必须保留；
     *       判丢错了会让结构漂移永远检测不出来；</li>
     *   <li>无法识别的类型原样返回（去掉首尾空白即可），不要抛错——
     *       比较一个不认识的类型，胜过因为解析失败而跳过整张表；</li>
     *   <li>null 输入返回空串，调用方不必到处判空。</li>
     * </ul>
     *
     * @param physicalType 任意来源的物理类型字符串，可为 null
     * @return 可比较形态，识别不了时返回清理过的原串
     */
    String normalize(String physicalType);

    /**
     * 方言名，用于日志与诊断
     * <p>异常信息里带上它才能区分"哪套方言没装配对"，默认 generic 表示未被方言覆盖。</p>
     */
    default String dialectName() {
        return "generic";
    }

}
