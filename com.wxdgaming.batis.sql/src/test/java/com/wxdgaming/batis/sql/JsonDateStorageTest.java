package com.wxdgaming.batis.sql;

import com.wxdgaming.batis.exception.TypeMismatchDataAccessException;
import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.StorageMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JSON 列内时间类型的落盘格式约定（fastjson2）。
 * <p>钉住两条契约：</p>
 * <ol>
 *   <li>{@code java.util.Date} 一族（含 java.sql.*）落盘为 epoch 毫秒——fastjson2 默认写
 *       本地时区字符串，不带偏移量、读回依赖运行 JVM 时区，多时区部署会写出不同文本；
 *       毫秒与时区无关，且 fastjson1 时代的存量行本来就是这个格式；</li>
 *   <li>{@code LocalDate}/{@code LocalDateTime} 保持 ISO 字符串——它们没有时区概念，
 *       折成毫秒反而把"几号"变成依赖时区换算的瞬时值。</li>
 * </ol>
 *
 * @author wxd-gaming
 */
@DisplayName("JSON 存储模式：Date 落盘毫秒、java.time 保持字符串")
class JsonDateStorageTest {

    /** 与 JsonPolymorphicStorageTest 同一款桩：只暴露基类编解码 */
    private static final class Codec extends AbstractBeanValueHandler {
        @Override
        public Object convertToDbValue(Object value, ColumnInfo col) {
            return toJsonString(value, col);
        }

        @Override
        public Object convertFromDbValue(Object value, ColumnInfo col) {
            return fromJsonText(value, col);
        }
    }

    private static final Codec CODEC = new Codec();
    private static final long EPOCH = 1697364000000L;

    public static class Event {
        private Date at;
        private java.sql.Timestamp stamp;
        private LocalDate day;
        private LocalDateTime moment;

        public Date getAt() {
            return at;
        }

        public void setAt(Date at) {
            this.at = at;
        }

        public java.sql.Timestamp getStamp() {
            return stamp;
        }

        public void setStamp(java.sql.Timestamp stamp) {
            this.stamp = stamp;
        }

        public LocalDate getDay() {
            return day;
        }

        public void setDay(LocalDate day) {
            this.day = day;
        }

        public LocalDateTime getMoment() {
            return moment;
        }

        public void setMoment(LocalDateTime moment) {
            this.moment = moment;
        }
    }

    /** 循环引用探针：next 指回自己 */
    public static class Node {
        private Node next;

        public Node getNext() {
            return next;
        }

        public void setNext(Node next) {
            this.next = next;
        }
    }

    private static ColumnInfo col(String fieldName) {
        try {
            Field f = Event.class.getDeclaredField(fieldName);
            ColumnInfo c = new ColumnInfo();
            c.setColumnName(fieldName);
            c.setFieldName(fieldName);
            c.setJavaType(f.getType());
            c.setGenericType(f.getGenericType());
            c.setStorageMode(StorageMode.JSON);
            c.setNullable(true);
            return c;
        } catch (NoSuchFieldException e) {
            throw new AssertionError(e);
        }
    }

    /** Map 形态的 JSON 列：嵌套 Date 走 ValueFilter 路径 */
    private static ColumnInfo mapCol() {
        ColumnInfo c = new ColumnInfo();
        c.setColumnName("payload");
        c.setFieldName("payload");
        c.setJavaType(Map.class);
        c.setStorageMode(StorageMode.JSON);
        c.setNullable(true);
        return c;
    }

    @Test
    @DisplayName("Date 根值与嵌套值都落 epoch 毫秒，java.time 保持 ISO 字符串")
    void dateWritesAsEpochMillis() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("at", new Date(EPOCH));
        payload.put("stamp", new java.sql.Timestamp(EPOCH));
        payload.put("day", LocalDate.of(2023, 10, 15));
        payload.put("moment", LocalDateTime.of(2023, 10, 15, 10, 0, 0));

        String json = (String) CODEC.convertToDbValue(payload, mapCol());
        assertTrue(json.contains("\"at\":" + EPOCH), "Date 必须落成毫秒数值: " + json);
        assertTrue(json.contains("\"stamp\":" + EPOCH), "Timestamp 必须落成毫秒数值: " + json);
        assertTrue(json.contains("\"day\":\"2023-10-15\""), "LocalDate 保持 ISO 字符串: " + json);
        assertTrue(json.contains("\"moment\":\"2023-10-15 10:00:00\""), "LocalDateTime 保持字符串: " + json);

        // 根值就是 Date：ValueFilter 管不到根，走的是根特判
        String root = (String) CODEC.convertToDbValue(new Date(EPOCH), col("at"));
        assertEquals(String.valueOf(EPOCH), root, "根 Date 必须落成毫秒数值: " + root);
    }

    @Test
    @DisplayName("读侧同时认毫秒与存量字符串两种形态")
    void dateReadsBothMillisAndLegacyString() {
        ColumnInfo at = col("at");
        Object fromMillis = CODEC.convertFromDbValue(String.valueOf(EPOCH), at);
        assertInstanceOf(Date.class, fromMillis);
        assertEquals(EPOCH, ((Date) fromMillis).getTime());

        // fastjson2 默认格式写出的存量行（本地时区字符串）必须仍可读回
        Object fromString = CODEC.convertFromDbValue("\"2023-10-15 18:00:00\"", at);
        assertInstanceOf(Date.class, fromString);
    }

    @Test
    @DisplayName("Event 整行往返：Date 毫秒、LocalDate/LocalDateTime 字符串各自归位")
    void eventRoundTrip() {
        Event source = new Event();
        source.setAt(new Date(EPOCH));
        source.setStamp(new java.sql.Timestamp(EPOCH));
        source.setDay(LocalDate.of(2023, 10, 15));
        source.setMoment(LocalDateTime.of(2023, 10, 15, 10, 0, 0));

        ColumnInfo anyCol = mapCol();
        String json = (String) CODEC.convertToDbValue(source, anyCol);
        Event back = (Event) CODEC.convertFromDbValue(json, eventColInfo());

        assertEquals(EPOCH, back.getAt().getTime(), "Date 往返后毫秒值不变: " + json);
        assertEquals(EPOCH, back.getStamp().getTime(), "Timestamp 往返后毫秒值不变: " + json);
        assertEquals(LocalDate.of(2023, 10, 15), back.getDay(), "LocalDate 不能错位: " + json);
        assertEquals(LocalDateTime.of(2023, 10, 15, 10, 0, 0), back.getMoment(), "LocalDateTime 不能错位: " + json);
    }

    private static ColumnInfo eventColInfo() {
        ColumnInfo c = new ColumnInfo();
        c.setColumnName("event");
        c.setFieldName("event");
        c.setJavaType(Event.class);
        c.setStorageMode(StorageMode.JSON);
        c.setNullable(true);
        return c;
    }

    @Test
    @DisplayName("循环引用：抛带列名的 TypeMismatch，而不是裸 StackOverflowError")
    void cyclicGraphIsWrappedNotRawSoe() {
        Node a = new Node();
        a.setNext(a);
        ColumnInfo c = new ColumnInfo();
        c.setColumnName("node");
        c.setFieldName("node");
        c.setJavaType(Node.class);
        c.setStorageMode(StorageMode.JSON);
        c.setNullable(true);

        TypeMismatchDataAccessException ex = assertThrows(TypeMismatchDataAccessException.class,
                () -> CODEC.convertToDbValue(a, c),
                "循环引用必须被包装成带列名的业务异常，裸 SOE 谁也定位不到是哪一列");
        assertTrue(ex.getMessage().contains("node"), "报错必须点名是哪一列: " + ex.getMessage());
    }
}
