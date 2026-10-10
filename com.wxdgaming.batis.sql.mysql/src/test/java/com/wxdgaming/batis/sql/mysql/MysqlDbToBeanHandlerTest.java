package com.wxdgaming.batis.sql.mysql;

import com.wxdgaming.batis.sql.QueryRunner;
import com.wxdgaming.batis.sql.ResultSetHandlers;
import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 行映射大小写不敏感测试
 *
 * <p>实体列名是驼峰转下划线后的小写（如 {@code name}），但手写 SQL 的别名可能用任意大小写
 * （{@code AS `NAME`}）。行映射必须先按精确名匹配、失配时回退到"列名小写索引"，
 * 否则大小写一不一致字段就静默不赋值。</p>
 * <p>本用例用 {@code SELECT 'x' AS `NAME`} 构造一个列标签为大写的单列结果集（不依赖任何表），
 * 断言实体对应字段被正确写入——这是对大小写不敏感回退路径的回归守护。</p>
 */
class MysqlDbToBeanHandlerTest {

    @RegisterExtension
    static MariaDB4jExtension maria = new MariaDB4jExtension();

    static MysqlTemplate template;

    @BeforeAll
    static void setUp() {
        if (template != null) return;
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(maria.getJdbcUrl());
        config.setUsername("root");
        config.setPassword("");
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        template = new MysqlTemplate(config);
    }

    @Test
    void columnLabelCaseMismatchStillMaps() {
        try (QueryRunner qr = new QueryRunner(template)) {
            // 别名刻意用大写，与实体列名 name / account 的小写不一致
            RoleEntity bean = qr.query(
                    "SELECT 'case-hello' AS `NAME`, 'acc-1' AS `ACCOUNT`",
                    ResultSetHandlers.bean(template, RoleEntity.class));

            assertNotNull(bean, "应映射出实体");
            assertEquals("case-hello", bean.getName(), "大写别名 NAME 应映射到 name 字段");
            assertEquals("acc-1", bean.getAccount(), "大写别名 ACCOUNT 应映射到 account 字段");
        }
    }
}
