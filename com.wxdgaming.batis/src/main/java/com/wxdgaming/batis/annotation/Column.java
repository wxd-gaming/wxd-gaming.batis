package com.wxdgaming.batis.annotation;

import com.wxdgaming.batis.converter.NoneTypeConverter;
import com.wxdgaming.batis.converter.TypeConverter;
import com.wxdgaming.batis.model.StorageMode;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 字段列注解，标注在实体字段上，定义列名、类型、长度、注释、默认值等元信息
 * <p>
 * 当 {@link #type()} 未指定时，解析器会根据 Java 类型推测对应的数据库类型（参考 MySQL 方言）。
 * </p>
 * <p>
 * <b>解析位置与影响面</b>：EntityParser 逐字段读取本注解生成 ColumnInfo（结果按 Class 缓存，
 * 进程内只解析一次）。ColumnInfo 随后被两侧消费——DDL 侧（建表/改表用列名、物理类型、长度、
 * 精度、注释、默认值、nullable、unique）与运行期侧（增删改用列名拼 SQL，读写值时按
 * storageMode/converter 做 Java 值与数据库值的互转）。
 * </p>
 * <p>
 * <b>不写本注解会怎样</b>：字段照样是有效列——列名取字段名驼峰转下划线、长度 255、
 * nullable 为 true、类型按 Java 类型推断。也就是说本注解只在需要偏离这些默认值时才写，
 * 它是"覆盖默认"的手段而不是字段声明的必要条件。static 字段始终被跳过，成为列的是全部
 * 实例字段（含父类字段，子类同名字段优先）。
 * </p>
 *
 * @author wxd-gaming
 */
@Documented
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Column {

    /**
     * 列名，不填时默认使用字段名（驼峰转下划线）。
     * <p>填写后原样使用，命名转换不再介入，所以这里必须写数据库里的真实列名；
     * 查询 DSL 既可以传本列名也可以传 Java 字段名，两者都能被解析。</p>
     */
    String name() default "";

    /**
     * 数据库物理列类型字面量（方言相关，如 MySQL 的 VARCHAR(64)、pgsql 的 varchar(64)）。
     * 一般不需要填写：不填时框架按 Java 类型推断逻辑类型（SqlType），再由当前方言渲染物理类型；
     * 填写后则跳过推断，原样用于 DDL。
     * <p>这也是唯一的"逃生舱"：unsigned/zerofill 这类逻辑类型表达不出来的列定义只能走字面量。
     * 代价是从此与方言绑定，并且长度/精度的自动修正（如 BigDecimal 兜底精度、UUID 固定 36）
     * 都不会再触发——宽度写错只能自己负责。</p>
     * <p>另一个正当用途是"在同一份实体里挑方言的原生档位"：pgsql 上写 {@code type = "jsonb"}
     * 或 {@code "json"} 就是把 {@code StorageMode.JSON} 的列从默认的 text 换成 PG 原生 JSON 类型，
     * 结构比对与迁移都按这一档走（json 与 jsonb 之间也是两种不同结构，不会互相视为"没变"）。</p>
     */
    String type() default "";

    /**
     * 列长度（仅对字符串/可变长类型有效），默认 255。
     * <p>它不只是 DDL 里的数字：推断阶段还按它选 TEXT 档位——String 类型下
     * 大于 16383 变 TEXT、大于 65535 变 MEDIUMTEXT、大于 16777215 变 LONGTEXT。
     * 个别类型会反向覆盖本值：UUID 固定按 36、char/Character 固定按 1、
     * AUTO 模式下的 BigDecimal 固定按 20（见 {@link #scale()}）。</p>
     */
    int length() default 255;

    /**
     * 小数精度（仅对 DECIMAL / NUMERIC 类型有效），默认 0 表示不设置。
     * <p>注意与 {@link #length()} 的一对坑：未显式写 {@link #type()} 时，
     * BigDecimal 字段在 AUTO 存储模式下会被解析器强制改写成 DECIMAL(20,6)，
     * 本属性与 length 的填值都会被覆盖。要拿到别的小数位，只能显式写
     * type（如 DECIMAL(10,2)），或把 storageMode 显式指定为某个非 AUTO 档位。</p>
     */
    int scale() default 0;

    /** 列注释，写进 DDL 的列注释，供表结构文档与漂移比对使用，不参与 SQL 拼装 */
    String comment() default "";

    /** 列默认值（SQL 字面量，如 {@code '0'}、{@code 0}、{@code CURRENT_TIMESTAMP}） */
    String defaultValue() default "";

    /**
     * 是否允许为空，默认 true。
     * <p>只需注意一处覆盖：字段同时标了主键注解时，解析器会强制把 nullable 改成 false，
     * 这里写 true 也不起作用——主键可空在 DDL 上根本不成立。</p>
     */
    boolean nullable() default true;

    /**
     * 是否唯一约束，默认 false。
     * <p>解析阶段它会额外生成一个名为 {@code 表名_列名_uk} 的单列唯一索引（如果同列还没有单列唯一索引），
     * 因此不需要再手写唯一索引注解；重复声明会被解析器去重。</p>
     */
    boolean unique() default false;

    /**
     * 存储模式
     * <p>
     * <ul>
     *   <li>{@link StorageMode#AUTO} - 自动推断（默认）：基础类型直接映射，复杂类型（List/Map/Set/自定义POJO）自动升级为 JSON</li>
     *   <li>{@link StorageMode#JSON} - 强制以 JSON 字符串存储，列类型推断为 LONGTEXT</li>
     *   <li>{@link StorageMode#BLOB} / {@link StorageMode#MEDIUMBLOB} / {@link StorageMode#LONGBLOB} -
     *       强制以二进制存储，使用 ProtostuffUtil 序列化/反序列化，列类型自动映射为对应数据库 BLOB 类型</li>
     * </ul>
     * <p>
     * 谁在读它、读了影响什么：两处。DDL 侧，EntityParser 的类型推断优先按本值分支
     * （非 AUTO 时 Java 类型不再参与判断，直接映射到对应 SqlType）；运行期侧，方言的
     * BeanValueHandler 在写库前按它决定序列化方式（JSON 走<b>不带类名</b>的 JSON 串，
     * 需要保留子类类型时用 {@link #polymorphic()} 逐列开启；BLOB 走 Protostuff 二进制），
     * 读库时反向还原。
     * <p>
     * 留 AUTO 时的推断规则：byte 数组例外地保持 AUTO（原生映射为 BLOB，不做任何序列化），
     * 其它数组、Collection、Map 以及不在 JDK 基础类型名单里的类型（自定义 POJO、枚举等）一律升级为 JSON，
     * 剩下的数值/字符串/日期族/UUID 等保持 AUTO。基础类型名单由解析器的判定方法给出，
     * 不在名单里的类型不要指望按 name() 之类的轻量方式落库——必要时显式写本属性或 {@link #converter()}。
     * 显式指定成基础档位（如 INT、VARCHAR）则等于跳过上述全部推断，用 Java 字段的类型换一种列类型落库。
     */
    StorageMode storageMode() default StorageMode.AUTO;

    /**
     * 多态 JSON 列：让"声明为接口/父类的字段存具体子类"在 JSON 往返后仍然是子类。
     * <p>默认 false。开启后写出的 JSON 会带上 fastjson2 的类型标记（{@code @type}），读取端按
     * 这一列的声明类型算出继承树上界作为白名单：标记是上界的子类才实例化，指向不相干类直接报错。
     * 白名单不是全局开关——只有显式标了本属性、且声明类型确实算得出上界的列才会走这条路径，
     * 所以攻击面限定在本字段自己的继承树里。</p>
     * <p>四条实测边界，写之前先对照：</p>
     * <ul>
     *   <li>前提是这一列真的走 JSON 编码（{@code storageMode} 为 JSON，或 AUTO 推断成 JSON 的
     *       List/Map/POJO 列）；标在 BLOB 档或基础类型列上没有作用。</li>
     *   <li>只对声明为接口/抽象类/父类的列有确定效果。声明太宽、算不出上界的列
     *       （{@code Object}、{@code List<Object>}、{@code Map<String, Object>}）读写两侧都是空操作：
     *       不写标记，读取也不开 autoType。这类字段一旦放行标记，就等于让库里的字符串决定实例化哪个类，
     *       而"谁能写这一列"不再是安全边界。</li>
     *   <li>开启后仍然是合法 JSON：Map 里的 Long/Double 不再写成 {@code 9L}、{@code 0.5D}，
     *       Set 不再写成 {@code Set[...]}，PG 的原生 {@code json}/{@code jsonb} 列（见 {@link #type()}）
     *       可以直接用。代价是嵌套容器上会出现 {@code @type} 键（如 {@code java.util.LinkedHashMap}），
     *       读取时一律归一成标准容器，库里点名的实现类不当真。</li>
     *   <li>存量行不受影响：没有类型标记的旧文本仍按声明类型解析。但同一列一旦开启，
     *       库里存的就不再是纯业务数据，别的消费者（SQL 里的 JSON 函数、报表、其它服务）
     *       会多读到类型标记键。</li>
     * </ul>
     * <p>标记指向继承树外的类（或被裁剪掉的 JDK 类）时读取抛
     * {@code TypeMismatchDataAccessException}，报错里带列名。</p>
     * <p>与 {@link #converter()} 互斥：转换器是完全接管的，框架不再做 JSON 编码，
     * 本属性会被忽略——这种"配置了但不起作用"在解析阶段就会直接报错。</p>
     */
    boolean polymorphic() default false;

    /**
     * 自定义类型转换器类，需实现 {@link com.wxdgaming.batis.converter.TypeConverter}
     * <p>
     * 指定后，该字段的 Bean↔DB 值转换将完全由转换器接管，
     * 不再走框架默认的类型映射逻辑（包括 {@link #storageMode()} 设置也会被忽略）。
     * <p>
     * 转换器类必须有无参构造函数，实例会被框架缓存并复用（须线程安全）。
     * <p>
     * 具体时机：EntityParser 解析该字段时就反射创建实例（不是首次读写时才创建），
     * 因此缺无参构造或构造器抛异常会让整个实体的解析失败，报错是实例化失败的运行时异常，
     * 而不是数据访问异常——排查时容易误认为 SQL 问题。
     * 保持默认 {@link NoneTypeConverter} 等于不启用，解析器只在它不等于默认值时才接管。
     */
    Class<? extends TypeConverter<?, ?>> converter() default NoneTypeConverter.class;
}
