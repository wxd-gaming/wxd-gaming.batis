package com.wxdgaming.batis.sql.mysql;

import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.TableInfo;
import com.wxdgaming.batis.parser.EntityParser;
import com.wxdgaming.batis.sql.BeanToUpdateHandler;
import com.wxdgaming.batis.sql.BeanValueHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * MySQL 方言的 UPDATE 处理器
 * <p>表名和列名使用反引号 `` ` `` 引用，全小写。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-28
 **/
@Slf4j
public class MysqlBeanToUpdateHandler implements BeanToUpdateHandler {

    private final BeanValueHandler valueHandler;

    /** 注入与读端共享的值转换协作者（见 {@code MysqlTemplate#initDefaults}） */
    public MysqlBeanToUpdateHandler(BeanValueHandler valueHandler) {
        this.valueHandler = valueHandler;
    }

    /**
     * 构建 UPDATE 语句：非主键列进 SET、主键列进 WHERE，形如
     * {@code UPDATE `t` SET `a` = ?, `b` = ? WHERE `id` = ?}。
     * <p>主键只作定位条件、不出现在 SET 里，天然避免"改到自己主键"；复合主键以 AND 串联全部主键列。
     * 无主键实体无法安全定位目标行，直接抛异常拒绝，好过退化成全表更新。</p>
     */
    @Override
    public String buildUpdateSql(Object bean) {
        TableInfo tableInfo = EntityParser.parse(bean.getClass());
        LinkedHashMap<String, ColumnInfo> columns = tableInfo.getColumns();

        List<String> setCols = new ArrayList<>();
        List<String> whereCols = new ArrayList<>();

        for (ColumnInfo col : columns.values()) {
            if (col.isPrimaryKey()) {
                whereCols.add("`" + col.getColumnName().toLowerCase() + "` = ?");
            } else {
                setCols.add("`" + col.getColumnName().toLowerCase() + "` = ?");
            }
        }

        if (whereCols.isEmpty()) {
            throw new IllegalArgumentException(
                    "实体 " + bean.getClass().getSimpleName() + " 未定义主键，无法执行 update");
        }

        String sql = "UPDATE `" + tableInfo.getTableName().toLowerCase() + "` SET "
                + String.join(", ", setCols) + " WHERE " + String.join(" AND ", whereCols);
        log.debug("构建 UPDATE sql:\n{}", sql);
        return sql;
    }

    /**
     * 提取 UPDATE 预编译参数：SET 段参数在前、WHERE 段主键参数在后。
     * <p>这是与 {@link #buildUpdateSql} 配套的参数顺序不变式——SQL 文本里 SET 的 ? 先于 WHERE 的 ?，
     * 参数数组必须同序拼接（先 setParams 再 addAll whereParams），错位会把主键值绑到业务列上。</p>
     */
    @Override
    public Object[] buildUpdateParams(Object bean) {
        TableInfo tableInfo = EntityParser.parse(bean.getClass());
        LinkedHashMap<String, ColumnInfo> columns = tableInfo.getColumns();

        List<Object> setParams = new ArrayList<>();
        List<Object> whereParams = new ArrayList<>();

        for (ColumnInfo col : columns.values()) {
            Object dbValue = valueHandler.convertToDbValue(
                    valueHandler.readFieldValue(bean, col), col);
            if (col.isPrimaryKey()) {
                whereParams.add(dbValue);
            } else {
                setParams.add(dbValue);
            }
        }

        // SET 参数在前，WHERE 参数在后
        setParams.addAll(whereParams);
        return setParams.toArray();
    }
}
