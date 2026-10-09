package com.wxdgaming.batis.sql.mysql;

import com.wxdgaming.batis.annotation.Column;
import com.wxdgaming.batis.annotation.GeneratedValue;
import com.wxdgaming.batis.annotation.GenerationType;
import com.wxdgaming.batis.annotation.Id;
import com.wxdgaming.batis.annotation.Table;
import com.wxdgaming.batis.model.StorageMode;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * DDL 漂移回归专用实体 —— 尽量覆盖全部逻辑类型档位（P1-4 解耦前后建表文本必须一致）
 */
@Setter
@Getter
@Table(name = "t_ddl_drift", comment = "列类型解耦回归表")
public class DdlDriftEntity {

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

    /** MEDIUMTEXT 档（>65535） */
    @Column(length = 70000)
    private String bigArticle;

    /** LONGTEXT 档 */
    @Column(length = 20000000)
    private String novel;

    /** JSON 存储模式 -> MySQL 渲染 LONGTEXT */
    @Column(storageMode = StorageMode.JSON)
    private List<String> tags;

    /** BLOB 档 */
    private byte[] payloadSmall;

    /** MEDIUMBLOB 档 */
    @Column(storageMode = StorageMode.MEDIUMBLOB, length = 70000)
    private byte[] payloadMedium;

    /** LONGBLOB 档 */
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

    private java.util.Date utilDate;
    private LocalDateTime dateTime;
    private LocalDate dateOnly;
    private LocalTime timeOnly;

    private char letter;
    private UUID uid;

    /** 显式物理字面量，必须原样保留 */
    @Column(type = "DECIMAL(12,2)", defaultValue = "0.00")
    private BigDecimal explicitDecimal;

    /** 显式 INT 字面量 */
    @Column(type = "INT", defaultValue = "1")
    private int explicitInt;

}
