package com.wxdgaming.batis.sql;

import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.TableInfo;
import com.wxdgaming.batis.parser.EntityParser;
import lombok.Getter;
import org.apache.commons.lang3.exception.ExceptionUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Bean CRUD 操作器，封装实体级别的插入、查询、更新、删除操作
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-31
 **/
@Getter
public class BeanRunner implements AutoCloseable {

    private final JdbcTemplate jdbcTemplate;
    private final QueryRunner queryRunner;
    /** 是否需要在 close 时关闭内部的 QueryRunner（外部注入的不关闭） */
    private final boolean closeQueryRunner;

    /**
     * 独立模式：自行创建 QueryRunner，close 时归还连接
     */
    public BeanRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.queryRunner = new QueryRunner(jdbcTemplate);
        this.closeQueryRunner = true;
    }

    /**
     * 共享模式：复用外部 QueryRunner（事务场景），close 时不关闭连接
     */
    public BeanRunner(QueryRunner queryRunner) {
        this.jdbcTemplate = queryRunner.getJdbcTemplate();
        this.queryRunner = queryRunner;
        this.closeQueryRunner = false;
    }

    @Override
    public void close() {
        if (closeQueryRunner) {
            queryRunner.close();
        }
    }

    // ===== 实体插入/更新（委托给 IBeanToInsertHandler / IBeanToUpdateHandler） =====

    private IBeanToInsertHandler requireInsertHandler() {
        Supplier<IBeanToInsertHandler> supplier = jdbcTemplate.getBeanToInsertHandlerSupplier();
        if (supplier == null) {
            throw new UnsupportedOperationException(
                    "未注册 beanToInsertHandlerSupplier，请调用 JdbcTemplate#setBeanToInsertHandlerSupplier()");
        }
        return supplier.get();
    }

    private IBeanToUpdateHandler requireUpdateHandler() {
        Supplier<IBeanToUpdateHandler> supplier = jdbcTemplate.getBeanToUpdateHandlerSupplier();
        if (supplier == null) {
            throw new UnsupportedOperationException(
                    "未注册 beanToUpdateHandlerSupplier，请调用 JdbcTemplate#setBeanToUpdateHandlerSupplier()");
        }
        return supplier.get();
    }

    /**
     * 插入实体，自动构建 INSERT SQL 并执行，返回自增主键（无自增列返回 null）
     *
     * @param bean 实体对象
     * @return 自增主键，无自增列时返回 null
     */
    public Number insertBean(Object bean) {
        IBeanToInsertHandler handler = requireInsertHandler();
        Number genId = queryRunner.executeInsert(handler.buildInsertSql(bean), handler.buildInsertParams(bean));
        handler.writeGeneratedKey(bean, genId);
        return genId;
    }

    /**
     * 更新实体，自动构建 UPDATE SQL 并按主键定位执行，返回影响行数
     *
     * @param bean 实体对象
     * @return 影响的行数
     */
    public int updateBean(Object bean) {
        IBeanToUpdateHandler handler = requireUpdateHandler();
        return queryRunner.update(handler.buildUpdateSql(bean), handler.buildUpdateParams(bean));
    }

    /**
     * 批量插入实体列表，自动构建 INSERT SQL，返回各条影响行数
     * <p>所有实体必须为同一类型；自增主键会自动回写到对应实体。</p>
     *
     * @param beans 实体列表
     * @return 各条语句影响的行数数组
     */
    public int[] insertBeanList(List<?> beans) {
        if (beans == null || beans.isEmpty()) return new int[0];

        Supplier<IBeanToInsertHandler> supplier = jdbcTemplate.getBeanToInsertHandlerSupplier();
        if (supplier == null) {
            throw new UnsupportedOperationException(
                    "未注册 beanToInsertHandlerSupplier，请调用 JdbcTemplate#setBeanToInsertHandlerSupplier()");
        }

        Object firstBean = beans.getFirst();
        IBeanToInsertHandler template = supplier.get();
        String sql = template.buildInsertSql(firstBean);
        ColumnInfo identityColumn = template.getIdentityColumn(firstBean);

        List<Object[]> batchArgs = new ArrayList<>(beans.size());
        for (Object bean : beans) {
            batchArgs.add(supplier.get().buildInsertParams(bean));
        }

        try {
            Connection conn = queryRunner.getConnection();
            try (PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                for (Object[] args : batchArgs) {
                    if (args != null) {
                        for (int i = 0; i < args.length; i++) {
                            stmt.setObject(i + 1, args[i]);
                        }
                    }
                    stmt.addBatch();
                }
                int[] results = stmt.executeBatch();

                if (identityColumn != null) {
                    try (ResultSet rs = stmt.getGeneratedKeys()) {
                        int idx = 0;
                        while (rs.next() && idx < beans.size()) {
                            Object val = rs.getObject(1);
                            Number genId = val instanceof Number ? (Number) val : null;
                            if (genId != null) {
                                template.writeGeneratedKey(beans.get(idx), genId);
                            }
                            idx++;
                        }
                    }
                }
                return results;
            }
        } catch (SQLException e) {
            throw ExceptionUtils.asRuntimeException(e);
        }
    }

    /**
     * 批量更新实体列表，自动构建 UPDATE SQL，返回各条影响行数
     * <p>所有实体必须为同一类型。</p>
     *
     * @param beans 实体列表
     * @return 各条语句影响的行数数组
     */
    public int[] updateBeanList(List<?> beans) {
        if (beans == null || beans.isEmpty()) return new int[0];

        Supplier<IBeanToUpdateHandler> supplier = jdbcTemplate.getBeanToUpdateHandlerSupplier();
        if (supplier == null) {
            throw new UnsupportedOperationException(
                    "未注册 beanToUpdateHandlerSupplier，请调用 JdbcTemplate#setBeanToUpdateHandlerSupplier()");
        }

        String sql = null;
        List<Object[]> batchArgs = new ArrayList<>(beans.size());

        for (Object bean : beans) {
            IBeanToUpdateHandler handler = supplier.get();
            if (sql == null) {
                sql = handler.buildUpdateSql(bean);
            }
            batchArgs.add(handler.buildUpdateParams(bean));
        }

        return queryRunner.executeBatch(sql, batchArgs);
    }

    // ===== SELECT 实体便捷方法（委托给 IBeanToSelectHandler） =====

    private IBeanToSelectHandler requireSelectHandler() {
        Supplier<IBeanToSelectHandler> supplier = jdbcTemplate.getBeanToSelectHandlerSupplier();
        if (supplier == null) {
            throw new UnsupportedOperationException(
                    "未注册 beanToSelectHandlerSupplier，请调用 JdbcTemplate#setBeanToSelectHandlerSupplier()");
        }
        return supplier.get();
    }

    /**
     * 按主键查询单个实体（基于实体类）
     * <p>单主键时 id 直接传值，复合主键时 id 传 Object[]。</p>
     *
     * @param entityClass 实体类
     * @param id          主键值（单主键）或 Object[]（复合主键）
     * @param <T>         实体类型
     * @return 实体对象，未找到时返回 null
     */
    public <T> T queryById(Class<T> entityClass, Object id) {
        TableInfo tableInfo = EntityParser.parse(entityClass);
        IBeanToSelectHandler handler = requireSelectHandler();
        String sql = handler.buildSelectByIdSql(tableInfo);

        int pkCount = 0;
        for (ColumnInfo col : tableInfo.getColumns().values()) {
            if (col.isPrimaryKey()) pkCount++;
        }
        Object[] params = pkCount == 1 ? new Object[]{id} : (Object[]) id;

        return queryRunner.query(sql, params, Handlers.bean(jdbcTemplate, entityClass));
    }

    /**
     * 查询全部实体
     *
     * @param entityClass 实体类
     * @param <T>         实体类型
     * @return 实体列表，不存在时返回空 List
     */
    public <T> List<T> queryAll(Class<T> entityClass) {
        TableInfo tableInfo = EntityParser.parse(entityClass);
        IBeanToSelectHandler handler = requireSelectHandler();
        String sql = handler.buildSelectAllSql(tableInfo);
        return queryRunner.query(sql, Handlers.beanList(jdbcTemplate, entityClass));
    }

    /**
     * 带条件查询单条实体
     * <p>{@code whereSection} 为 {@code null} 或空字符串时等价于 {@link #queryAll} 并返回第一条。</p>
     *
     * @param entityClass  实体类
     * @param whereSection WHERE 子句（不含 WHERE 关键字），例如 {@code "status = ? AND paid = ?"}
     * @param params       参数列表
     * @param <T>          实体类型
     * @return 实体对象，未找到时返回 null
     */
    public <T> T queryOne(Class<T> entityClass, String whereSection, Object... params) {
        TableInfo tableInfo = EntityParser.parse(entityClass);
        IBeanToSelectHandler handler = requireSelectHandler();
        String sql;
        Object[] args;
        if (whereSection == null || whereSection.trim().isEmpty()) {
            sql = handler.buildSelectAllSql(tableInfo);
            args = null;
        } else {
            sql = handler.buildSelectAllSql(tableInfo) + " WHERE " + whereSection;
            args = params.length > 0 ? params : null;
        }
        return queryRunner.query(sql, args, Handlers.bean(jdbcTemplate, entityClass));
    }

    /**
     * 带条件查询实体列表
     * <p>{@code whereSection} 为 {@code null} 或空字符串时等价于 {@link #queryAll}。</p>
     *
     * @param entityClass  实体类
     * @param whereSection WHERE 子句（不含 WHERE 关键字），例如 {@code "status = ?"}
     * @param params       参数列表
     * @param <T>          实体类型
     * @return 实体列表，不存在时返回空 List
     */
    public <T> List<T> queryList(Class<T> entityClass, String whereSection, Object... params) {
        TableInfo tableInfo = EntityParser.parse(entityClass);
        IBeanToSelectHandler handler = requireSelectHandler();
        String sql;
        Object[] args;
        if (whereSection == null || whereSection.trim().isEmpty()) {
            sql = handler.buildSelectAllSql(tableInfo);
            args = null;
        } else {
            sql = handler.buildSelectAllSql(tableInfo) + " WHERE " + whereSection;
            args = params.length > 0 ? params : null;
        }
        return queryRunner.query(sql, args, Handlers.beanList(jdbcTemplate, entityClass));
    }

    // ===== DELETE =====

    /**
     * 清空表中所有数据（基于实体类）
     */
    public void deleteAll(Class<?> cls) {
        TableInfo tableInfo = EntityParser.parse(cls);
        deleteAll(tableInfo);
    }

    /**
     * 清空表中所有数据（基于 TableInfo）
     */
    public void deleteAll(TableInfo tableInfo) {
        if (jdbcTemplate.getDeleteHandler() == null) {
            throw new UnsupportedOperationException("未注册 deleteHandler");
        }
        queryRunner.update(jdbcTemplate.getDeleteHandler().buildDeleteAllSql(tableInfo));
    }

    /**
     * 根据主键删除单个实体
     *
     * @param bean 实体对象（需包含主键字段值）
     * @return 影响的行数
     */
    public int deleteBean(Object bean) {
        if (jdbcTemplate.getDeleteHandler() == null) {
            throw new UnsupportedOperationException("未注册 deleteHandler");
        }
        return queryRunner.update(
                jdbcTemplate.getDeleteHandler().buildDeleteByIdSql(bean),
                jdbcTemplate.getDeleteHandler().buildDeleteByIdParams(bean)
        );
    }

}
