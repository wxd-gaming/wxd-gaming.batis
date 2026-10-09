package com.wxdgaming.batis.sql;

import com.wxdgaming.batis.model.ColumnInfo;

/**
 * INSERT SQL 构建与参数提取接口（方言 SPI）
 * <p>各数据库方言通过此接口提供差异化的 INSERT 实现（如标识符引用风格、自增主键的取得方式）。</p>
 * <p>方法拆成"造语句"和"造参数"两个动作，是为了让批量插入只解析一次实体结构、
 * 再复用同一条语句换参数（见 {@code BeanRunner#doInsertBatch}）。由此产生一条硬约束：
 * {@link #buildInsertSql} 只能由实体类型决定形态，绝不能依赖某个 bean 的字段值
 * （例如"字段为空就少写一列"）——否则同批第二条起就会与第一条的占位符对不上。</p>
 * <p>自增主键列在这条链路上被刻意跳过：它的值由数据库生成，出现在列清单里反而会
 * 让显式插入 0 或覆盖序列。识别与回写分别由 {@link #getIdentityColumn} 和
 * {@link #writeGeneratedKey} 承担，方言差异（如生成键的类型、能否回读）收敛在这两个方法里。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-28
 **/
public interface BeanToInsertHandler {

    /**
     * 构建 INSERT INTO 语句
     * <p>实现方必须保证：列清单与 VALUES 占位符数量相等、顺序一致，并跳过自增主键列；
     * 同一实体类多次调用必须返回完全相同的文本（批量路径只取第一个 bean 的语句复用）。</p>
     *
     * @param bean 实体对象，仅用于推导实体类型与表结构
     * @return 参数化 INSERT 语句，不含任何字段字面量
     */
    String buildInsertSql(Object bean);

    /**
     * 提取 INSERT 预编译参数数组
     * <p>顺序必须与 {@link #buildInsertSql} 生成的占位符严格对应（同样跳过自增主键列），
     * 每个值都要经过 {@link BeanValueHandler#convertToDbValue} 转换，
     * 交出的是驱动能直接绑定的形态。</p>
     *
     * @param bean 实体对象
     * @return 与占位符同序的参数数组，长度必须等于占位符个数
     */
    Object[] buildInsertParams(Object bean);

    /**
     * 获取自增主键列，没有自增主键时返回 null
     * <p>批量路径按第一个 bean 取一次，用于决定要不要读回生成键，
     * 因此同一实体类的返回值必须稳定。手工主键（如雪花 ID）在这里返回 null，
     * 上层据此跳过生成键回写。</p>
     *
     * @param bean 实体对象，仅用于推导实体类型
     * @return 自增主键列元数据，无自增主键返回 null
     */
    ColumnInfo getIdentityColumn(Object bean);

    /**
     * 将自增主键值回写到 bean
     * <p>这是"插入后立刻拿到 ID"成为框架保证的地方，调用方无需再查一次。</p>
     * <p>实现方必须容忍 genId 为 null（手工主键、驱动没给出键），此时静默跳过而不是抛错；
     * 同时要处理生成键的实际类型与实体字段类型不一致的情况（驱动常返回 Long 或 BigInteger，
     * 字段可能是 int），反射回写前要做数值形态转换。</p>
     * <p>批量场景下本方法在语句 commit 之前被调用，因为生成键游标会随语句关闭失效。</p>
     *
     * @param bean  刚插入的实体对象
     * @param genId 数据库生成的主键值，可为 null
     */
    void writeGeneratedKey(Object bean, Number genId);
}
