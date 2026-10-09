package com.wxdgaming.batis.model;

/**
 * 列存储模式
 * <p>
 * 控制实体字段与数据库列之间的序列化/反序列化方式。
 * 可以显式指定基础类型（{@link #INT}、{@link #VARCHAR} 等），
 * 由解析器自动映射到对应的数据库列类型。
 *
 * <pre>
 * {@code
 * // AUTO（默认）：基础类型直接映射，复杂类型（List/Map/自定义POJO）自动升级为 JSON
 * private String name;
 *
 * // 显式指定基础类型
 * @Column(storageMode = StorageMode.INT)
 * private int level;
 *
 * @Column(storageMode = StorageMode.VARCHAR, length = 64)
 * private String account;
 *
 * // 显式 JSON 存储 -> LONGTEXT 列
 * @Column(storageMode = StorageMode.JSON)
 * private List<Item> items;
 *
 * // 显式二进制存储 -> BLOB/MEDIUMBLOB/LONGBLOB 列，使用 ProtostuffUtil 序列化
 * @Column(storageMode = StorageMode.LONGBLOB)
 * private BattleSnapshot snapshot;
 * }
 * </pre>
 * <p>
 * 为什么要区分"存储模式"和"逻辑列类型"：StorageMode 是<b>写入侧的策略</b>（这个字段怎么变成列值），
 * 而 {@link SqlType} 是<b>建表侧的物理描述</b>（这一列在库里长什么样）。两者解耦后，
 * 同一个 JSON 模式渲染成什么物理列完全由方言决定（MySQL 侧目前出于兼容既有表结构渲染为 LONGTEXT，
 * 别的方言可以换成原生 json / text），实体代码不用动。
 * <p>
 * AUTO 的升级规则：字段类型属于"数据库原生认得"的（包装类、String、byte[]、时间类）就直映，
 * 其余（List/Map/自定义 POJO）一律升级成 JSON。宁可自动选 JSON，也不要静默地把对象
 * 按 {@code toString()} 写进去——那样读回来就是不可逆的字符串，属于最难排查的一类数据事故。
 * <p>
 * 档位边界的数字不是随手取的：它们对应 MySQL 各文本档的容量上限（TEXT 65535 字节、
 * MEDIUMTEXT 16777215 字节，VARCHAR 在 utf8mb4 下约 16383 字符）。
 * {@code EntityParser} 的 AUTO 推断正是"声明长度越过上一档上限就升档"（大于 16383 / 65535 / 16777215），
 * 目的是在建表阶段就挡住"写入时才报 Data too long"。注意判定用的是严格大于，
 * 所以常量注释里的"长度 ≥ 16384"与代码里的"大于 16383"是同一件事。
 * <p>
 * JSON 与 BLOB 的取舍：JSON 可读、可被数据库函数查询，代价是序列化开销和体积；
 * BLOB 用 Protostuff 二进制，体积小、速度快，但对数据库而言是一团不透明字节，无法按内容检索。
 * 需要人工排查或在 SQL 里查的用 JSON，只是纯粹存取大对象（快照、缓存体）的用 BLOB。
 *
 * @author wxd-gaming(無心道, 15388152619)
 * @version 2026-07-24 18:17
 **/
public enum StorageMode {

    /**
     * 自动推断：基础类型直接映射，复杂类型自动升级为 JSON
     * <p>默认值。推断结果只在实体解析时算一次，因此改字段类型等于改列类型，
     * 需要配合建表/校验流程重新核对表结构，否则老列会留下宽度不足的隐患。</p>
     */
    AUTO,
    /**
     * boolean / Boolean → TINYINT(1)
     * <p>MySQL 没有真正的 BOOLEAN 物理类型（它是 TINYINT(1) 的别名），
     * 而驱动正是靠"宽度 1"这个约定把 0/1 读回成 boolean 的，所以这里固定用 TINYINT(1) 而不是 BOOL。</p>
     */
    BOOL,
    /** byte / Byte → TINYINT */
    BYTE,
    /** short / Short → SMALLINT */
    SHORT,
    /** int / Integer → INT */
    INT,
    /** long / Long → BIGINT */
    LONG,
    /** float / Float → FLOAT */
    FLOAT,
    /** double / Double → DOUBLE */
    DOUBLE,
    /**
     * BigDecimal → DECIMAL(20,6)
     * <p>AUTO 档会强制把长度改回 20、小数位 6（{@code EntityParser#applyInferredType}）：
     * 注解的 length 默认是 255，若让它透传给 DECIMAL 就变成 DECIMAL(255,x)，既有字段结构比对会立刻判漂移，
     * 语义上也荒谬。这个精度契约是"零虚假 ALTER"不变式的一部分，不要随手改。</p>
     */
    DECIMAL,
    /** String → VARCHAR（声明长度未越过 TEXT 上限时的默认档，注解不给长度时按 255 建列） */
    VARCHAR,
    /** String → TEXT（长度 ≥ 16384） */
    TEXT,
    /** String → MEDIUMTEXT（长度 ≥ 65536） */
    MEDIUMTEXT,
    /** String → LONGTEXT（长度 ≥ 16777216） */
    LONGTEXT,
    /**
     * byte[] → BLOB
     * <p>byte[] 在 AUTO 下也走这一档（不再套一层 Protostuff，原样落库），
     * 因为"再序列化一次"会让库里存的字节和调用方给的字节不一致，读回来还得手工剥壳。</p>
     */
    BLOB,
    /** byte[] → MEDIUMBLOB */
    MEDIUMBLOB,
    /** byte[] → LONGBLOB */
    LONGBLOB,
    /** LocalDate → DATE */
    DATE,
    /**
     * Date / LocalDateTime → DATETIME
     * <p>刻意不用 TIMESTAMP：MySQL 的 timestamp 带时区换算、范围只到 2038，而且会自动给列加
     * "插入/更新时填充当前时间"的隐式行为，用来存业务时间会产生人读不懂的值。
     * 时间族在写 JDBC 参数时统一归一成 {@code java.sql.Timestamp}（DATETIME 的 JDBC 形态），
     * 所以 Date、LocalDateTime、Instant 落在同一档是有意为之。</p>
     */
    DATETIME,
    /** LocalTime → TIME */
    TIME,
    /**
     * 以 JSON 字符串存储 / 原生 JSON 类型
     * <p>MySQL 侧写出去的是带类名的 JSON（fastjson 的 WriteClassName），读取端配合 SupportAutoType
     * 才能还原多态元素和泛型集合；不带类名时 {@code List<子类>} 读回来会退化成 JSONObject，
     * 这是这一档最常见的踩坑点。建表渲染目前是 LONGTEXT 而不是原生 json（历史兼容，见
     * {@code MysqlColumnTypeDefHandler#renderType}），所以别指望能在 SQL 里用 JSON 函数查它。</p>
     */
    JSON

}
