package com.wxdgaming.batis.sql;

/**
 * 批量刷盘结果
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-08-05
 */
public class FlushResult {

    private final int insertCount;
    private final int updateCount;
    private final int deleteCount;
    private final int insertAffected;
    private final int updateAffected;
    private final int deleteAffected;

    public FlushResult(int insertCount, int updateCount, int deleteCount,
                       int insertAffected, int updateAffected, int deleteAffected) {
        this.insertCount = insertCount;
        this.updateCount = updateCount;
        this.deleteCount = deleteCount;
        this.insertAffected = insertAffected;
        this.updateAffected = updateAffected;
        this.deleteAffected = deleteAffected;
    }

    public int getInsertCount()     { return insertCount; }
    public int getUpdateCount()     { return updateCount; }
    public int getDeleteCount()     { return deleteCount; }
    public int getInsertAffected()  { return insertAffected; }
    public int getUpdateAffected()  { return updateAffected; }
    public int getDeleteAffected()  { return deleteAffected; }
    public int getTotalCount()      { return insertCount + updateCount + deleteCount; }
    public int getTotalAffected()   { return insertAffected + updateAffected + deleteAffected; }

    @Override
    public String toString() {
        return String.format("FlushResult{insert=%d(%d行), update=%d(%d行), delete=%d(%d行)}",
                insertCount, insertAffected, updateCount, updateAffected, deleteCount, deleteAffected);
    }
}
