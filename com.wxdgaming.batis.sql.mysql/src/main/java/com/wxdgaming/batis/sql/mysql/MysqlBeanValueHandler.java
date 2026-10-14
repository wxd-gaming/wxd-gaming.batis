package com.wxdgaming.batis.sql.mysql;

import com.wxdgaming.batis.converter.TypeConverter;
import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.StorageMode;
import com.wxdgaming.batis.sql.AbstractBeanValueHandler;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.BigInteger;
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
 * MySQL 方言的 Bean ↔ DB 双向值转换实现
 * <p>继承 {@link AbstractBeanValueHandler} 复用纯反射的字段读写、主键回写，
 * 以及与管理器无关的 JSON/Protostuff 编解码与基础类型派发；本类只实现方言真正的差异点：
 * {@code convertToDbValue}/{@code convertFromDbValue} 的编排顺序与时间族落到哪种 JDBC 形态。</p>
 * <p>由 {@code MysqlTemplate#initDefaults} 造出单一实例并共享给 Insert/Update/Delete/DbToBean 四个 handler，
 * 保证写端与读端使用同一套对称的转换规则。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-31
 */
@Slf4j
public class MysqlBeanValueHandler extends AbstractBeanValueHandler {

    /**
     * Java 值 → JDBC 参数。
     * <p>转换优先级：自定义 {@link TypeConverter}（完全接管）&gt; 列的 StorageMode（JSON/BLOB 序列化）&gt;
     * 基础类型归一。编解码与重试逻辑在基类，这里只保留 MySQL 侧的时间取舍。</p>
     * <p>基础类型 switch 里的方言取舍：日期族统一转 {@code java.sql.Timestamp}（MySQL DATETIME 的 JDBC 形态），
     * 因此 {@code LocalDate} 也按零点时间落进 DATETIME 列；
     * 枚举存 {@code name()}（稳定、可读、不随 ordinal 重排而损坏）；{@code UUID}/{@code Character} 转 String；
     * {@code BigInteger} 转 BigDecimal 避免超过 long 时静默截断。</p>
     */
    @Override
    public Object convertToDbValue(Object value, ColumnInfo col) {
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
            case java.util.Date date -> new Timestamp(date.getTime());
            case LocalDateTime localDateTime -> Timestamp.valueOf(localDateTime);
            case LocalDate localDate -> Timestamp.valueOf(localDate.atStartOfDay());
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
     * JDBC/ResultSet 读出的值 → Java 对象，是 {@link #convertToDbValue} 的逆过程。
     * <p>转换优先级与写入端对称：自定义 TypeConverter &gt; StorageMode（JSON 按字段声明类型反解、
     * BLOB 用 Protostuff 解码；byte[] 列直接返回原始字节不解码）&gt; 基础类型转换（基类
     * {@code convertBasicValue}）。精度问题（如 BigDecimal scale）在此不做四舍五入，
     * 交由测试端用 compareTo 规避。</p>
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
