package com.wxdgaming.batis.sql.pgsql;

import com.alibaba.fastjson2.TypeReference;
import com.wxdgaming.batis.converter.TypeConverter;
import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.SqlType;
import com.wxdgaming.batis.model.StorageMode;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PG 方言值转换单元测试（纯函数，不连库）
 * <p>这里测的是"Java 值该落成哪种 JDBC 形态"，而 {@code PgSchemaDriftIT} 测的是"该落成哪种列类型"。
 * 分开是因为这两类错误的表现完全不同：列类型错会被 information_schema 抓到，
 * 而 JDBC 形态错只在绑参那一刻由 PG 报 42804（类型不匹配），且只有真库路径才会走到。</p>
 * <p>本类最有价值的一条是时间族短路：PG 的 DATE / TIME / TIMESTAMP 是三种互不相容的类型，
 * 把 LocalDate 一律当 Timestamp 交出去（照抄 MySQL 的写法）会让 DATE 列直接报错，
 * 把已是 Timestamp 的值再按毫秒重建会丢掉亚毫秒位。这两件事都没有异常可依赖，只能显式断言。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-14
 **/
class PgsqlBeanValueHandlerTest {

    static final PgsqlBeanValueHandler HANDLER = new PgsqlBeanValueHandler();

    /** 造一个只关心转换的列描述：javaType + 存储模式，不涉及 DDL */
    private static ColumnInfo col(Class<?> javaType, StorageMode mode) {
        ColumnInfo c = new ColumnInfo();
        c.setColumnName("c");
        c.setFieldName("c");
        c.setJavaType(javaType);
        c.setStorageMode(mode);
        c.setNullable(true);
        return c;
    }

    // ==================== 写入端：JDBC 形态选择 ====================

    /**
     * 用例①：已是 JDBC 时间形态的值必须原样交出。
     * <p>三条各对一个具体的 PG 行为：Timestamp 若落到 {@code java.util.Date} 分支，
     * {@code new Timestamp(getTime())} 只保留毫秒，PG TIMESTAMP 的微秒位被清零；
     * java.sql.Date 若落到同一分支会变成 Timestamp，绑进 DATE 列报 42804；
     * java.sql.Time 同理落进 DATE/TIMESTAMP 列都是 42804。用 assertSame 而不是 equals，
     * 因为 equals 无法区分"没动过"和"重建了一个等价值"。</p>
     */
    @Test
    void jdbcTemporalFormsPassThroughUnchanged() {
        Timestamp ts = Timestamp.valueOf("2026-03-14 15:09:26.123456789");
        assertSame(ts, HANDLER.convertToDbValue(ts, col(Timestamp.class, StorageMode.AUTO)),
                "Timestamp 不应被重建，否则丢掉亚毫秒位");
        java.sql.Date d = java.sql.Date.valueOf("2026-03-14");
        assertSame(d, HANDLER.convertToDbValue(d, col(java.sql.Date.class, StorageMode.AUTO)),
                "java.sql.Date 不能被转成 Timestamp，DATE 列会拒绝");
        Time t = Time.valueOf("15:09:26");
        assertSame(t, HANDLER.convertToDbValue(t, col(Time.class, StorageMode.AUTO)),
                "java.sql.Time 必须保持 Time，TIME 列只接受 Time");
    }

    /**
     * 用例②：java.time 各档落到 PG 对应的那一列类型上。
     * <p>断言的是"具体类"而不是"某个 Object"：LocalDate 必须落成 java.sql.Date
     * （MySQL 侧习惯把它拼成当日零点的 Timestamp，PG 的 DATE 列会拒绝 Timestamp，这是 42804 的源头）；
     * LocalTime 必须落成 java.sql.Time；其余时间点落 Timestamp。</p>
     */
    @Test
    void javaTimeTypesMapToMatchingJdbcForms() {
        assertInstanceOf(java.sql.Date.class,
                HANDLER.convertToDbValue(LocalDate.of(2026, 3, 14), col(LocalDate.class, StorageMode.AUTO)),
                "LocalDate 应绑成 DATE 形态，不能拼成 Timestamp");
        assertInstanceOf(Time.class,
                HANDLER.convertToDbValue(LocalTime.of(15, 9, 26), col(LocalTime.class, StorageMode.AUTO)),
                "LocalTime 应绑成 TIME 形态");
        assertInstanceOf(Timestamp.class,
                HANDLER.convertToDbValue(LocalDateTime.of(2026, 3, 14, 15, 9, 26),
                        col(LocalDateTime.class, StorageMode.AUTO)));
        assertInstanceOf(Timestamp.class,
                HANDLER.convertToDbValue(Instant.parse("2026-03-14T07:09:26Z"), col(Instant.class, StorageMode.AUTO)));
        assertInstanceOf(Timestamp.class,
                HANDLER.convertToDbValue(OffsetDateTime.parse("2026-03-14T15:09:26+08:00"),
                        col(OffsetDateTime.class, StorageMode.AUTO)));
        assertInstanceOf(Timestamp.class,
                HANDLER.convertToDbValue(ZonedDateTime.of(LocalDateTime.of(2026, 3, 14, 15, 9, 26),
                        ZoneId.of("Asia/Shanghai")), col(ZonedDateTime.class, StorageMode.AUTO)));
        // 跨时区档位统一按 instant 归一，与 MySQL 侧约定一致
        assertEquals(Timestamp.from(Instant.parse("2026-03-14T07:09:26Z")),
                HANDLER.convertToDbValue(Instant.parse("2026-03-14T07:09:26Z"), col(Instant.class, StorageMode.AUTO)));
    }

    /**
     * 用例③：标量档的落库形态。
     * <p>枚举存 name()（不随 ordinal 重排而损坏）；UUID/Character 转 String（PG 没有这两类的原生列）；
     * BigInteger 转 BigDecimal 而不是 longValue（超过 long 时静默截断）；原子类型拆成基本值。
     * 断言 {@code BigInteger -> BigDecimal} 时特意选一个超过 long 的数，
     * 截断类缺陷只有溢出取值时才暴露。</p>
     */
    @Test
    void scalarTypesMapToStableDbForms() {
        assertEquals("PAID", HANDLER.convertToDbValue(Status.PAID, col(Status.class, StorageMode.AUTO)));
        UUID uid = UUID.fromString("11111111-2222-3333-4444-555555555555");
        assertEquals(uid.toString(), HANDLER.convertToDbValue(uid, col(UUID.class, StorageMode.AUTO)));
        assertEquals("A", HANDLER.convertToDbValue('A', col(Character.class, StorageMode.AUTO)));
        BigInteger huge = new BigInteger("123456789012345678901234567890");
        Object rendered = HANDLER.convertToDbValue(huge, col(BigInteger.class, StorageMode.AUTO));
        assertInstanceOf(BigDecimal.class, rendered, "BigInteger 应转成 BigDecimal 保留位数");
        assertEquals(huge, ((BigDecimal) rendered).toBigInteger(), "超过 long 的 BigInteger 不能被截断");
        assertEquals(7, HANDLER.convertToDbValue(new AtomicInteger(7), col(AtomicInteger.class, StorageMode.AUTO)));
        assertEquals(9L, HANDLER.convertToDbValue(new AtomicLong(9L), col(AtomicLong.class, StorageMode.AUTO)));
        // java.util.Date 没有日期/时间之分，按时间点落进 TIMESTAMP 列
        assertEquals(Timestamp.valueOf("2026-03-14 15:09:26"),
                HANDLER.convertToDbValue(new java.util.Date(Timestamp.valueOf("2026-03-14 15:09:26").getTime()),
                        col(java.util.Date.class, StorageMode.AUTO)));
    }

    /**
     * 用例④：null 与 byte[] 的短路。
     * <p>null 必须直接返回 null（否则后面每个 switch/instanceof 分支都要重复判空，漏一处就是 NPE）；
     * byte[] 在 BLOB 模式下原样交出而不是再套一层 Protostuff——套壳会让库里存的字节和调用方给的字节不一致，
     * 读回来还得手工剥壳。用 assertSame 确认没有复制/包装。</p>
     */
    @Test
    void nullAndByteArrayShortCircuit() {
        org.junit.jupiter.api.Assertions.assertNull(
                HANDLER.convertToDbValue(null, col(String.class, StorageMode.AUTO)));
        byte[] bytes = {1, 2, 3};
        assertSame(bytes, HANDLER.convertToDbValue(bytes, col(byte[].class, StorageMode.BLOB)));
        assertSame(bytes, HANDLER.convertFromDbValue(bytes, col(byte[].class, StorageMode.BLOB)));
    }

    /**
     * 用例⑤：JSON 存储模式写读对称，且反解按字段声明的泛型走。
     * <p>断言写出来是 {@code ["a","b"]}、读回来还是两元素 List。
     * 这里顺带守住了"非多态列不开 autoType"这条安全约定：解码只按字段声明的泛型走，
     * 与 JSON 文本里是否含 {@code @type} 无关，所以库里被塞进恶意 @type 也不会实例化任意类。</p>
     */
    @Test
    void jsonStorageModeRoundTripUsesDeclaredType() {
        ColumnInfo c = col(List.class, StorageMode.JSON);
        c.setGenericType(new TypeReference<List<String>>() {
        }.getType());
        Object json = HANDLER.convertToDbValue(List.of("a", "b"), c);
        assertEquals("[\"a\",\"b\"]", json);
        Object back = HANDLER.convertFromDbValue(json, c);
        assertInstanceOf(List.class, back);
        assertEquals(List.of("a", "b"), back);
    }

    /**
     * 用例⑥：自定义 TypeConverter 完全接管，优先级高于存储模式与类型归一。
     * <p>动作：给一个 JSON 模式的列挂转换器。断言两边走的都是转换器而不是 JSON 编解码。
     * 意义：转换器是业务对某列的最终解释权，如果它排在 StorageMode 之后，
     * 同一实体在 MySQL 和 PG 上的优先级就会不一致（基类收口判定的目的）。</p>
     */
    @Test
    void customConverterTakesPrecedence() {
        ColumnInfo c = col(String.class, StorageMode.JSON);
        c.setConverter(new TypeConverter<String, String>() {
            @Override
            public String convertToDatabaseColumn(String attribute) {
                return "to-db";
            }

            @Override
            public String convertToEntityAttribute(String dbData) {
                return "from-db";
            }
        });
        assertEquals("to-db", HANDLER.convertToDbValue("a", c));
        assertEquals("from-db", HANDLER.convertFromDbValue("anything", c));
    }

    /**
     * 用例⑦：读回的布尔与数值按声明类型收窄。
     * <p>PG 的 BOOLEAN 列回读就是 Boolean（不会像 MySQL 给 0/1），但数值族仍要收窄：
     * BIGINT 列读回 Long，字段声明 int 时必须给出 Integer，否则反射 setter 直接失败。</p>
     */
    @Test
    void readSideNarrowsToDeclaredType() {
        ColumnInfo bool = col(boolean.class, StorageMode.AUTO);
        bool.setSqlType(SqlType.BOOLEAN);
        assertEquals(Boolean.TRUE, HANDLER.convertFromDbValue(true, bool));
        ColumnInfo i = col(int.class, StorageMode.AUTO);
        assertEquals(5, HANDLER.convertFromDbValue(5L, i));
        ColumnInfo s = col(String.class, StorageMode.AUTO);
        assertEquals("PAID", HANDLER.convertFromDbValue(Status.PAID, s));
        ColumnInfo ldt = col(LocalDateTime.class, StorageMode.AUTO);
        assertEquals(LocalDateTime.of(2026, 3, 14, 15, 9, 26),
                HANDLER.convertFromDbValue(Timestamp.valueOf("2026-03-14 15:09:26"), ldt));
        ColumnInfo dateOnly = col(LocalDate.class, StorageMode.AUTO);
        assertEquals(LocalDate.of(2026, 3, 14),
                HANDLER.convertFromDbValue(java.sql.Date.valueOf("2026-03-14"), dateOnly));
        assertTrue(String.valueOf(HANDLER.convertFromDbValue(123.45, col(BigDecimal.class, StorageMode.AUTO)))
                .startsWith("123.4"), "数值应能放宽成 BigDecimal");
    }

    /**
     * 用例⑧：显式声明为 PG 原生 JSON 档的列，文本值必须包成带类型名的 PGobject。
     * <p>WHY 这是承重的：<b>真库上的 42804 在本类里造不出来</b>——这里没有连接，能钉住的只有
     * "该交出什么形态的对象"，而 {@code PgJsonTypeIT#rawStringParameterIntoJsonbColumnIsRejected}
     * 用一条手写 SQL 证明了不包装确实会被 PG 拒绝（pgjdbc 的 setObject(String) 按 varchar 送参，
     * PG 没有 varchar→json/jsonb 的隐式赋值转换）。两侧合起来才是完整证据。</p>
     * <p>顺带钉住注解书写的宽容度：{@code " JSONB "} 这种大小写混排也要能识别，
     * 判定复用 {@code normalize} 而不是直接比字符串。</p>
     */
    @Test
    void jsonColumnsBindAsTypedPgObject() {
        Object tags = HANDLER.convertToDbValue(List.of("a"), jsonCol(List.class, StorageMode.JSON, "JSONB"));
        assertInstanceOf(PGobject.class, tags);
        assertEquals("jsonb", ((PGobject) tags).getType());
        assertEquals("[\"a\"]", ((PGobject) tags).getValue());

        Object raw = HANDLER.convertToDbValue("{\"k\":\"v\"}", jsonCol(String.class, StorageMode.AUTO, "json"));
        assertEquals("json", ((PGobject) raw).getType(), "json 与 jsonb 是两个不同的档，不能一律送 jsonb");
        assertEquals("{\"k\":\"v\"}", ((PGobject) raw).getValue());

        assertEquals("jsonb", ((PGobject) HANDLER.convertToDbValue("[]",
                jsonCol(String.class, StorageMode.AUTO, " JSONB "))).getType());
    }

    /**
     * 用例⑨：不是 JSON 档的列一律不包装，非文本值也不代做序列化。
     * <p>两条各自挡一种回归：把 {@code StorageMode.JSON}（不带 type，物理列是 text）也包成 PGobject，
     * 会让所有存量 JSON 字段的写入形态改变；给数字/时间值补一层 {@code toJSONString}，
     * 会把"实体把时间字段声明在 JSON 列上"这类错误静默改成合法 JSON 文本，事后无法还原原意。</p>
     */
    @Test
    void nonJsonColumnsAndNonTextValuesStayUnwrapped() {
        assertEquals("[\"a\"]", HANDLER.convertToDbValue(List.of("a"), col(List.class, StorageMode.JSON)),
                "不带 type 的 JSON 存储模式仍按普通字符串绑定");
        assertEquals("abc", HANDLER.convertToDbValue("abc", jsonCol(String.class, StorageMode.AUTO, "TEXT")));
        assertEquals(Integer.valueOf(5),
                HANDLER.convertToDbValue(5, jsonCol(Integer.class, StorageMode.AUTO, "jsonb")));
        assertNull(HANDLER.convertToDbValue(null, jsonCol(List.class, StorageMode.JSON, "jsonb")),
                "null 要交给驱动的 setNull，包成 PGobject 反而丢掉了未指定类型这一便利");
    }

    /** 造一个带显式物理类型字面量的列描述（JSON 档判定的唯一输入就是 type） */
    private static ColumnInfo jsonCol(Class<?> javaType, StorageMode mode, String physicalType) {
        ColumnInfo c = col(javaType, mode);
        c.setType(physicalType);
        return c;
    }

    /** 用例③用的枚举 */
    enum Status {
        PAID
    }

}
