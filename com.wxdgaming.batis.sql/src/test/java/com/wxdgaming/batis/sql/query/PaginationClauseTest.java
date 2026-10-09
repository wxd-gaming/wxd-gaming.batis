package com.wxdgaming.batis.sql.query;

import com.wxdgaming.batis.model.TableInfo;
import com.wxdgaming.batis.parser.EntityParser;
import com.wxdgaming.batis.sql.BeanToSelectHandler;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * 分页子句方言归属单测：
 * 默认方法产出 MySQL 风格（反引号 + LIMIT ? OFFSET ?），
 * 自定义 handler 只覆盖差异方法即可切换到另一套方言分页（OFFSET n ROWS FETCH n ROWS ONLY），
 * 证明分页渲染收敛在 handler 侧、builder 骨架方言无关。
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-09
 **/
class PaginationClauseTest {

    private static final TableInfo TABLE = EntityParser.parse(DslTestEntity.class);

    /** 模拟未来 pgsql 方言：双引号标识符 + SQL 标准分页语法 */
    private static class AnsiStyleHandler extends FakeSelectHandler {
        @Override
        public String quote(String identifier) {
            return "\"" + identifier.toLowerCase() + "\"";
        }

        @Override
        public void appendPaging(StringBuilder sql, List<Object> params, Integer limit, Integer offset) {
            if (offset != null && offset > 0) {
                sql.append(" OFFSET ? ROWS");
                params.add(offset);
            }
            if (limit != null && limit > 0) {
                sql.append(" FETCH NEXT ? ROWS ONLY");
                params.add(limit);
            }
        }

        @Override
        public String buildCountSql(TableInfo tableInfo) {
            return "SELECT COUNT(*) FROM " + quote(tableInfo.getTableName());
        }
    }

    private record PagingSpec(Integer limit, Integer offset) implements SelectSqlBuilder.SelectQuerySpec {
        @Override
        public com.wxdgaming.batis.query.Criteria where() {
            return null;
        }

        @Override
        public List<String> columns() {
            return null;
        }

        @Override
        public com.wxdgaming.batis.query.Sort sort() {
            return null;
        }
    }

    private static SelectSqlBuilder.Plan plan(BeanToSelectHandler handler, Integer limit, Integer offset) {
        return SelectSqlBuilder.build(handler, TABLE, new PagingSpec(limit, offset));
    }

    // ===== 默认方法（MySQL 风格） =====

    @Test
    void defaultNoPagingWhenBothNull() {
        SelectSqlBuilder.Plan p = plan(new FakeSelectHandler(), null, null);
        assertEquals("SELECT * FROM `t_dsl_test`", p.sql());
        assertEquals(0, p.params().length);
    }

    @Test
    void defaultLimitOnly() {
        SelectSqlBuilder.Plan p = plan(new FakeSelectHandler(), 10, null);
        assertEquals("SELECT * FROM `t_dsl_test` LIMIT ?", p.sql());
        assertArrayEquals(new Object[]{10}, p.params());
    }

    @Test
    void defaultLimitOffsetOrderMatchesParams() {
        SelectSqlBuilder.Plan p = plan(new FakeSelectHandler(), 20, 40);
        assertEquals("SELECT * FROM `t_dsl_test` LIMIT ? OFFSET ?", p.sql());
        assertArrayEquals(new Object[]{20, 40}, p.params());
    }

    @Test
    void defaultZeroValuesTreatedAsNoPaging() {
        SelectSqlBuilder.Plan p = plan(new FakeSelectHandler(), 0, 0);
        assertEquals("SELECT * FROM `t_dsl_test`", p.sql());
        assertEquals(0, p.params().length);
    }

    @Test
    void defaultQuoteLowercases() {
        assertEquals("`mixedcase`", new FakeSelectHandler().quote("MixedCase"));
    }

    // ===== 覆盖差异方法即切换方言 =====

    @Test
    void ansiOverrideChangesQuotingAndPagingAndParamOrder() {
        SelectSqlBuilder.Plan p = plan(new AnsiStyleHandler(), 20, 40);
        assertEquals("SELECT * FROM \"t_dsl_test\" OFFSET ? ROWS FETCH NEXT ? ROWS ONLY", p.sql());
        // 该方言下参数顺序为 [OFFSET, LIMIT]，骨架不做任何假设
        assertArrayEquals(new Object[]{40, 20}, p.params());
    }

    @Test
    void ansiOffsetOnlyStillValid() {
        SelectSqlBuilder.Plan p = plan(new AnsiStyleHandler(), null, 40);
        assertEquals("SELECT * FROM \"t_dsl_test\" OFFSET ? ROWS", p.sql());
        assertArrayEquals(new Object[]{40}, p.params());
    }

    @Test
    void countSqlComesFromHandler() {
        assertEquals("SELECT COUNT(*) FROM `t_dsl_test`", new FakeSelectHandler().buildCountSql(TABLE));
        assertEquals("SELECT COUNT(*) FROM \"t_dsl_test\"", new AnsiStyleHandler().buildCountSql(TABLE));
    }

}
