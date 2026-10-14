package com.wxdgaming.batis.sql.pgsql;

import com.wxdgaming.batis.annotation.Column;
import com.wxdgaming.batis.annotation.GeneratedValue;
import com.wxdgaming.batis.annotation.GenerationType;
import com.wxdgaming.batis.annotation.Id;
import com.wxdgaming.batis.annotation.Table;
import com.wxdgaming.batis.model.StorageMode;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * 结构漂移回归专用实体 —— 覆盖 PG 侧全部逻辑类型档位
 * <p>与 MySQL 的 {@code DdlDriftEntity} 一一对应，用来验证同一批 Java 字段在 PG 上落成的物理类型：
 * PG 的档位收敛（TEXT 三档同为 text、BLOB 三档同为 bytea、BYTE/SHORT 同为 smallint）是有意的，
 * 因此这里的期望值是"折叠后的形态"，而不是 MySQL 的九档。</p>
 *
 * @author wxd-gaming(無心道, 15388152619)
 **/
@Setter
@Getter
@Table(name = "t_pg_ddl_drift", comment = "PG 列类型档位回归表")
public class PgDdlDriftEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    /** 默认宽度 VARCHAR(255) */
    private String plain;

    /** VARCHAR(64) */
    @Column(length = 64, nullable = false, comment = "短字符串")
    private String shortText;

    /** TEXT 档（>16383） */
    @Column(length = 20000)
    private String article;

    /** MEDIUMTEXT 档（>65535），PG 折叠成 text */
    @Column(length = 70000)
    private String bigArticle;

    /** LONGTEXT 档，PG 折叠成 text */
    @Column(length = 20000000)
    private String novel;

    /** JSON 存储模式 → PG 渲染 TEXT */
    @Column(storageMode = StorageMode.JSON)
    private List<String> tags;

    /** BLOB 档 */
    private byte[] payloadSmall;

    /** MEDIUMBLOB 档，PG 折叠成 bytea */
    @Column(storageMode = StorageMode.MEDIUMBLOB, length = 70000)
    private byte[] payloadMedium;

    /** LONGBLOB 档，PG 折叠成 bytea */
    @Column(storageMode = StorageMode.LONGBLOB, length = 20000000)
    private byte[] payloadLarge;

    private boolean flag;
    private byte tiny;
    private short small;
    private int num;
    private long big;
    private float real;
    private double dbl;
    private BigDecimal exact;
    private BigInteger hugeNumber;

    private java.util.Date utilDate;
    private LocalDateTime dateTime;
    private LocalDate dateOnly;
    private LocalTime timeOnly;

    private char letter;
    private UUID uid;

    /** 显式物理字面量（PG 拼写），必须原样保留 */
    @Column(type = "NUMERIC(12,2)", defaultValue = "0.00")
    private BigDecimal explicitDecimal;

    /** 显式物理字面量（PG 拼写），默认值是裸数字写法 */
    @Column(type = "INTEGER", defaultValue = "1")
    private int explicitInt;

    /** 布尔列的默认值按 MySQL 习惯写 0，PG 回读是 false，比对必须折叠 */
    @Column(defaultValue = "0", comment = "是否冻结")
    private boolean frozen;

    /** 日期默认值按 PG 约定写成带引号的字符串字面量 */
    @Column(defaultValue = "'2026-01-01'")
    private LocalDate startDate;

}
