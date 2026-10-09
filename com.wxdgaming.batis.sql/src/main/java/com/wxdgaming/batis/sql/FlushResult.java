package com.wxdgaming.batis.sql;

/**
 * 批量刷盘结果
 * <p>两组数字的差别是读这个对象的关键，混淆它会误判数据是否全部落库：</p>
 * <ul>
 *   <li><b>Count</b>（insert/update/delete）：本次从脏表取出的条目数，即"打算写多少条"，
 *       取的是快照大小，与执行结果无关；</li>
 *   <li><b>Affected</b>（xxxAffected）：真正落到数据库的行数。两者不等就是信号——
 *       插入侧通常是批量降级后有失败条目，更新侧更常见的是"更新一条库里不存在的行"
 *       （延迟更新标记残留、或那条记录本就没落库），得到 0 行生效却不报错。</li>
 * </ul>
 * <p>不可变对象：刷盘结束时一次性构造，只读，可安全跨线程传递做统计或断言。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-08-05
 */
public class FlushResult {

    /** 本次取出的待插入条目数 */
    private final int insertCount;
    /** 本次取出的待更新条目数 */
    private final int updateCount;
    /** 本次取出的待删除条目数 */
    private final int deleteCount;
    /** 实际插入成功的条数（批量降级后可能小于 insertCount） */
    private final int insertAffected;
    /** 实际更新到的行数（小于 updateCount 多为主键在库里不存在） */
    private final int updateAffected;
    /** 实际删除的行数 */
    private final int deleteAffected;

    /**
     * 构造刷盘结果。
     * <p>计数与影响行数由 {@code CachedBeanRunner} 的刷盘路径按同一次执行汇总后传入，
     * 调用方不应自行拼装——口径不一致的六个数字比没有数字更误导人。</p>
     *
     * @param insertCount    取出的待插入条目数
     * @param updateCount    取出的待更新条目数
     * @param deleteCount    取出的待删除条目数
     * @param insertAffected 实际插入条数
     * @param updateAffected 实际更新行数
     * @param deleteAffected 实际删除行数
     */
    public FlushResult(int insertCount, int updateCount, int deleteCount,
                       int insertAffected, int updateAffected, int deleteAffected) {
        this.insertCount = insertCount;
        this.updateCount = updateCount;
        this.deleteCount = deleteCount;
        this.insertAffected = insertAffected;
        this.updateAffected = updateAffected;
        this.deleteAffected = deleteAffected;
    }

    /** 待插入条目数（提交的量，不代表成功量） */
    public int getInsertCount()     { return insertCount; }
    /** 待更新条目数（提交的量，不代表成功量） */
    public int getUpdateCount()     { return updateCount; }
    /** 待删除条目数（提交的量，不代表成功量） */
    public int getDeleteCount()     { return deleteCount; }
    /** 实际插入条数 */
    public int getInsertAffected()  { return insertAffected; }
    /** 实际更新行数 */
    public int getUpdateAffected()  { return updateAffected; }
    /** 实际删除行数 */
    public int getDeleteAffected()  { return deleteAffected; }
    /** 提交的条目总数：三类 count 之和 */
    public int getTotalCount()      { return insertCount + updateCount + deleteCount; }
    /** 实际生效的行数：三类 affected 之和，与 {@link #getTotalCount()} 的差额即未落库部分 */
    public int getTotalAffected()   { return insertAffected + updateAffected + deleteAffected; }

    /** 一行摘要，形如 {@code insert=3(3行), update=2(1行)}，括号内为受影响行数 */
    @Override
    public String toString() {
        return String.format("FlushResult{insert=%d(%d行), update=%d(%d行), delete=%d(%d行)}",
                insertCount, insertAffected, updateCount, updateAffected, deleteCount, deleteAffected);
    }
}
