package com.wxdgaming.batis.sql.pgsql;

import com.wxdgaming.batis.converter.TypeConverter;
import com.wxdgaming.batis.exception.TypeMismatchDataAccessException;
import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.StorageMode;
import com.wxdgaming.batis.sql.AbstractBeanValueHandler;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.util.PGobject;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PostgreSQL 方言的 Bean ↔ DB 双向值转换实现
 * <p>继承 {@link AbstractBeanValueHandler} 复用纯反射的字段读写、主键回写，
 * 以及与管理器无关的 JSON/Protostuff 编解码与基础类型派发；本类只实现方言真正的差异点：
 * {@code convertToDbValue}/{@code convertFromDbValue} 的编排顺序、时间族落到哪种 JDBC 形态，
 * 以及 PG 原生 json/jsonb 列的参数包装（{@link #bindJsonColumn}）。</p>
 * <p>由 {@code PgsqlTemplate#initDefaults} 造出单一实例并共享给 Insert/Update/Delete/DbToBean
 * 四个 handler，保证写端与读端使用同一套对称的转换规则。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-10-14
 */
@Slf4j
public class PgsqlBeanValueHandler extends AbstractBeanValueHandler {

    /**
     * Java 值 → JDBC 参数：先按 {@link #toJdbcValue} 定出值形态，再按列的物理类型定出绑定形态
     * （PG 原生 JSON 列要包成 {@code PGobject}，见 {@link #bindJsonColumn}）。
     */
    @Override
    public Object convertToDbValue(Object value, ColumnInfo col) {
        return bindJsonColumn(toJdbcValue(value, col), col);
    }

    /**
     * Java 值 → JDBC 参数的编排。
     * <p>转换优先级：自定义 {@link TypeConverter}（完全接管）&gt; 列的 StorageMode（JSON/BLOB 序列化）&gt;
     * 基础类型归一。编解码与重试逻辑在基类，这里只保留 PG 侧的时间取舍。</p>
     * <p>与 MySQL 版唯一实质差别的档位是 {@code LocalDate}：它转成 {@code java.sql.Date} 而不是零点
     * {@code Timestamp}。原因是 PG 有真正的 DATE 列（{@link PgsqlColumnTypeDefHandler} 把 DATE 渲染成
     * {@code DATE}），把 Timestamp 绑进 DATE 列会直接 42804（类型不匹配）而不会被隐式降格；
     * MySQL 那边反过来只有 DATETIME，所以按零点时间落列才对。</p>
     * <p>其余取舍与 MySQL 一致：{@code LocalDateTime}/{@code Instant}/{@code Offset}/{@code Zoned}
     * 都归一到 {@code Timestamp}（PG 的 {@code TIMESTAMP} 无时区，绑定带时区的载体同样报 42804，
     * 折成 Timestamp 也和列类型口径统一）；枚举存 {@code name()}（不随 ordinal 重排而损坏）；
     * {@code UUID}/{@code Character} 转 String；{@code BigInteger} 转 BigDecimal 避免超过 long 时静默截断。</p>
     */
    private Object toJdbcValue(Object value, ColumnInfo col) {
        if (value == null) return null;

        // 优先使用自定义 TypeConverter（完全接管该字段的转换）
        if (hasConverter(col)) {
            return applyConverterToDb(value, col);
        }

        StorageMode mode = col.getStorageMode();
        if (mode == StorageMode.JSON) {
            return toJsonString(value, col);
        }
        if (isBinaryMode(mode)) {
            return toBlobBytes(value, col);
        }
        return switch (value) {
            // 已是 JDBC 时间形态的先短路：Timestamp 若落到下面的 java.util.Date 分支，
            // new Timestamp(long) 会按毫秒重建、丢掉亚毫秒位（PG 的 TIMESTAMP 存到微秒）；
            // java.sql.Date 落到同一分支则变成 Timestamp，绑进 PG 的 DATE 列直接 42804
            case Timestamp timestamp -> timestamp;
            case java.sql.Date sqlDate -> sqlDate;
            case java.sql.Time sqlTime -> sqlTime;
            case java.util.Date date -> new Timestamp(date.getTime());
            case LocalDateTime localDateTime -> Timestamp.valueOf(localDateTime);
            case LocalDate localDate -> java.sql.Date.valueOf(localDate);
            case LocalTime localTime -> java.sql.Time.valueOf(localTime);
            case OffsetDateTime offsetDateTime -> Timestamp.from(offsetDateTime.toInstant());
            case ZonedDateTime zonedDateTime -> Timestamp.from(zonedDateTime.toInstant());
            case Instant instant -> Timestamp.from(instant);
            case BigInteger bigInt -> new BigDecimal(bigInt);
            case AtomicInteger ai -> ai.get();
            case AtomicLong al -> al.get();
            case Character c -> c.toString();
            case UUID uuid -> uuid.toString();
            case Enum<?> anEnum -> anEnum.name();
            default -> value;
        };
    }

    /**
     * PG 原生 JSON 列（{@code @Column(type = "JSON"/"JSONB")}）的参数包装：字符串改包成 {@link PGobject}。
     * <p><b>WHY 必须包</b>：{@code QueryRunner}/{@code BeanRunner} 统一用 {@code setObject(idx, value)} 绑参，
     * pgjdbc 对 {@code String} 走的是 {@code setString}，参数按 {@code varchar} 送出；
     * 而 PG <b>没有</b> varchar→json/jsonb 的隐式赋值转换，于是每次写入都 42804
     * （"column x is of type jsonb but expression is of type character varying"）。
     * 包成带类型名的 PGobject 后驱动直接按该类型送 oid，SQL 文本一个字都不用改。</p>
     * <p>刻意不改成"在占位符后面拼 {@code ?::jsonb}"：那要动 INSERT/UPDATE/批量三条渲染路径，
     * 还会把 {@code toSql()} 的产物写成方言腔；包装点只有这一个，收敛在值边界更划算。</p>
     * <p>只包文本：JSON 档与自定义转换器的产物是 String，String 字段直落 JSON 列也该按原样存；
     * 数字/时间等非文本值落进 JSON 列属于实体写错（时间形态折成字符串大概率不是合法 JSON），
     * 让它带着 42804 原样失败比在这里悄悄 {@code toJSONString} 一层更好查。</p>
     */
    private static Object bindJsonColumn(Object dbValue, ColumnInfo col) {
        String jsonType = PgsqlJsonType.of(col);
        if (jsonType == null || !(dbValue instanceof CharSequence text)) {
            return dbValue;
        }
        PGobject json = new PGobject();
        json.setType(jsonType);
        try {
            json.setValue(text.toString());
        } catch (SQLException e) {
            // setValue 目前只记录值（异常声明是它的历史签名），进来这一支说明驱动版本变了行为；
            // 仍然包住：值转换层不能让裸 SQLException 逃出去，那里没有 SQL 上下文，报错查不出是哪一列
            throw new TypeMismatchDataAccessException("字段 " + col.getFieldName()
                    + " 无法写入 PG 的 " + jsonType + " 列: " + e.getMessage(), e);
        }
        return json;
    }

    /**
     * JDBC/ResultSet 读出的值 → Java 对象，是 {@link #convertToDbValue} 的逆过程。
     * <p>转换优先级与写入端对称：自定义 TypeConverter &gt; StorageMode（JSON 按字段声明类型反解、
     * BLOB 用 Protostuff 解码；byte[] 列直接返回原始字节不解码）&gt; 基础类型转换（基类
     * {@code convertBasicValue}）。时间族的来源形态在 PG 下更杂（DATE 给 {@code java.sql.Date}、
     * TIME 给 {@code java.sql.Time}、TIMESTAMP 给 {@code Timestamp}），都由基类
     * {@code convertTemporal} 先收敛再派发，本类不再重复一遍。</p>
     * <p>JSON 列不需要读端特判：pgjdbc 把 json/jsonb 回读成 {@code PGobject}，它的
     * {@code toString()} 就是列里的 JSON 文本，基类 {@code fromJsonText} 与 String 兜底走的都是
     * {@code value.toString()}。反过来写端必须特判（见 {@link #bindJsonColumn}），
     * 因为"送什么 oid"是 PG 的约束，而"怎么解读文本"是驱动的便利。</p>
     */
    @Override
    public Object convertFromDbValue(Object value, ColumnInfo col) {
        // 优先使用自定义 TypeConverter（完全接管该字段的转换）
        if (hasConverter(col)) {
            return applyConverterFromDb(value, col);
        }

        StorageMode mode = col.getStorageMode();
        if (mode == StorageMode.JSON) {
            return fromJsonText(value, col);
        }
        if (isBinaryMode(mode)) {
            return fromBlobBytes(value, col);
        }
        return convertBasicValue(value, col);
    }
}
