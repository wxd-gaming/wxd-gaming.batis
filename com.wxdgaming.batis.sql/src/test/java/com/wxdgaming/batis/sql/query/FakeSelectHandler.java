package com.wxdgaming.batis.sql.query;

import com.wxdgaming.batis.model.TableInfo;
import com.wxdgaming.batis.sql.BeanToSelectHandler;

/**
 * 方言风格可控的 Fake 选择处理器：只实现两个抽象方法，
 * 默认方法（quote/appendPaging/buildCountSql）走接口实现，
 * 子类可覆盖以模拟其他方言（如 pgsql 双引号 + OFFSET/FETCH）。
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
public class FakeSelectHandler implements BeanToSelectHandler {

    @Override
    public String buildSelectByIdSql(TableInfo tableInfo) {
        return "SELECT * FROM " + quote(tableInfo.getTableName());
    }

    @Override
    public String buildSelectAllSql(TableInfo tableInfo) {
        return "SELECT * FROM " + quote(tableInfo.getTableName());
    }

}
