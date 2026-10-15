# wxd-gaming.batis

轻量级 JDBC ORM 框架（Java 21 / Maven 多模块），面向游戏服务端的数据访问场景。
以「**注解即表结构**」+「**实体即 API**」为核心：实体类上的注解一次性描述表名、列、
索引与主键策略，框架据此自动建表 / 对已有表做结构漂移比对，并提供查询 DSL、批量读写、
编程式事务、分区表维护与 Write-Behind 缓存。

- 版本：`1.1.0`
- 构建坐标：`io.github.wxd-gaming:wxd-gaming.batis`（聚合 POM）
- 许可：Apache License 2.0（仓库根目录 `LICENSE`）
- 作者：wxd-gaming（無心道）

> 说明：本 README 由代码阅读整理而成，覆盖当前仓库全部模块。文中所有类名、方法名、
> 常量与默认值均取自源码。

---

## 目录

- [设计目标与整体结构](#设计目标与整体结构)
- [模块与依赖关系](#模块与依赖关系)
- [环境要求与构建](#环境要求与构建)
- [快速开始](#快速开始)
- [实体定义](#实体定义)
- [查询 DSL](#查询-dsl)
- [数据读写](#数据读写)
- [事务](#事务)
- [缓存与 Write-Behind](#缓存与-write-behind)
- [分区表维护](#分区表维护)
- [异常体系](#异常体系)
- [MySQL 方言内部](#mysql-方言内部)
- [类型映射](#类型映射)
- [性能统计](#性能统计)
- [测试](#测试)
- [目录结构](#目录结构)
- [已知边界与注意事项](#已知边界与注意事项)

---

## 设计目标与整体结构

框架的骨架可以用三层来概括，依赖方向严格单向（`core ← sql ← sql.mysql`）：

```
业务代码
   │  使用 BeanRunner / CachedBeanRunner / SelectQuery / TransactionTemplate
   ▼
com.wxdgaming.batis.sql          方言无关的 JDBC 层
   │  JdbcTemplate 持有「一组可空的方言 SPI 字段」，自己不认识任何具体数据库
   ▼
com.wxdgaming.batis              元数据与通用模型层
   │  EntityParser 把「注解 + Java 类型」翻译成 TableInfo / ColumnInfo / IndexInfo
   ▼
com.wxdgaming.batis.sql.mysql    方言实现层
   MysqlTemplate 在构造时用 setter 把全部 SPI 注入核心层
```

三条贯穿全项目的设计约定：

1. **逻辑类型与物理类型解耦**。`EntityParser` 只产出逻辑类型 `SqlType`（加长度/精度），
   绝不产出方言字面量；「这一列写成 `VARCHAR(64)` 还是 `TINYINT(1)`」由 sql 层的
   `ColumnTypeDefHandler` SPI 按方言渲染。MySQL 侧实现见 `MysqlColumnTypeDefHandler`。
2. **核心层不反向依赖方言**。`JdbcTemplate` 只声明一组可空的 handler 字段
   （`dbMetaProvider`、`tableCreateHandler`、`beanToInsertHandler`……），
   由 `MysqlTemplate#initDefaults()` 一次性注入。换方言只换注入，上层 Runner / DSL / 事务代码一行不动。
3. **结构比对必须零漂移**。`checkTable()` 会把「实体侧渲染再规格化」与「库端元数据规格化」
   做 diff；任何渲染字面量的改动都可能让存量表被判成漂移并对全表刷 `MODIFY`
   （大表上等于锁表事故）。因此 `MysqlColumnTypeDefHandler#renderType` 的分支看似可"美化"，
   实际上是历史行为的锁定，改动必须同步验证 `SchemaDriftIT` 的零漂移不变式。

---

## 模块与依赖关系

| 模块目录 | artifactId | 职责 | 依赖 |
| --- | --- | --- | --- |
| `com.wxdgaming.batis` | `com.wxdgaming.batis` | 元数据与通用模型：注解、`EntityParser`、`TableInfo/ColumnInfo/IndexInfo`、查询 DSL 模型、缓存抽象、类型转换、异常体系、性能统计 | caffeine |
| `com.wxdgaming.batis.sql` | `com.wxdgaming.batis.sql` | 方言无关 JDBC 层：`JdbcTemplate`、`QueryRunner`、`BeanRunner`、`CachedBeanRunner`、SELECT DSL、事务、分区抽象、DDL handler SPI | core、HikariCP 7.1.0、caffeine |
| `com.wxdgaming.batis.sql.mysql` | `com.wxdgaming.batis.sql.mysql` | MySQL/MariaDB 方言：`MysqlTemplate` 与全部 handler 实现 | sql、mysql-connector-j 9.5.0、（test）mariaDB4j 3.1.0 |
| `com.wxdgaming.batis.sql.pgsql` | `com.wxdgaming.batis.sql.pgsql` | PostgreSQL 方言：`PgsqlTemplate` 与全部 handler 实现，JSON 列原生档位（`@Column(type="json"/"jsonb")`）与多态开关 | sql、postgresql 42.7.8、（test）embedded-postgres 2.2.2 |
| `com.wxdgaming.batis.mongodb` | `com.wxdgaming.batis.mongodb` | **占位模块**：`pom.xml` + 一个空类 `MongodbTemplate`（无字段/构造/方法）；仍在聚合 POM 注释掉 | — |

最后一个模块被注释在聚合 POM 的 `<modules>` 之外，原因是「仅一个空类」会打出空 jar
让使用方误引入；落地实现后再加回。
因此当前 reactor 有四个模块（core、sql、mysql、pgsql）。

第三方依赖（版本统一在根 POM 的 `<dependencyManagement>` 中锁定）：

| 依赖 | 版本 | 用途 |
| --- | --- | --- |
| `com.zaxxer:HikariCP` | 7.1.0 | 连接池（仅 sql 层） |
| `com.mysql:mysql-connector-j` | 9.5.0 | MySQL 驱动（仅 mysql 层） |
| `com.github.ben-manes.caffeine:caffeine` | 3.2.2 | 本地缓存实现 |
| `io.protostuff:protostuff-core` / `-runtime` | 1.8.0 | BLOB 存储模式的二进制序列化 |
| `com.alibaba.fastjson2:fastjson2` | 2.0.65 | JSON 存储模式序列化（多态列按需带类名） |
| `org.projectlombok:lombok` | 1.18.46 | 实体 getter/setter 等样板代码 |
| `org.slf4j:slf4j-api` + `logback-classic/core` | 2.0.18 / 1.5.36 | 日志 |
| `org.apache.commons:commons-lang3` / `commons-text` | 3.18.0 / 1.10.0 | 工具类 |
| `ch.vorburger.mariaDB4j:mariaDB4j` | 3.1.0（test） | 嵌入式 MariaDB，自包含集成测试 |
| `org.postgresql:postgresql` | 42.7.8 | PG 驱动（仅 pgsql 层） |
| `io.zonky.test:embedded-postgres` | 2.2.2（test） | 嵌入式 PostgreSQL，自包含集成测试（发行包版本由 `embedded-postgres-binaries-bom` 14.22.0 统一锁定） |
| `org.junit.jupiter:junit-jupiter-api` | 5.11.4（test） | 单元/集成测试 |

> 根 POM 把 `junit`、`lombok`、`fastjson2`、`commons-*`、`logback`、`slf4j`、`protostuff`
> 声明在 `<dependencies>`（非 `<dependenciesManagement>`）里，即所有子模块默认继承这些依赖；
> `caffeine`、`HikariCP`、MySQL 驱动则由各模块按需声明。

---

## 环境要求与构建

- **JDK 21**（根 POM `maven.compiler.release = ${java.version} = 21`，IDEA 项目 language level 亦为 JDK_21）
- **Maven 3.9.9，由仓库自带的 wrapper 提供**，不需要本机安装 Maven
  （`maven-surefire-plugin` 显式锁定在 **3.2.5**，因为 surefire 2.x 无法发现 JUnit 5 测试）

**直接用 `mvnw`，不要用本机 `mvn`**——wrapper 会把 Maven 版本固定成 3.9.9，新环境无需
预装 Maven，也不会因为某台机器装了 3.8/4.x 而出现构建差异：

```bash
# 全量构建 + 跑测试（mysql 模块的集成测试需要本地能拉起嵌入式 MariaDB）
./mvnw -q clean install

# 只跑某个模块的测试
./mvnw -q -pl com.wxdgaming.batis.sql.mysql -am test

# 跳过测试构建
./mvnw -q clean install -DskipTests
```

Windows 下把 `./mvnw` 换成 `mvnw.cmd`（`.\mvnw.cmd -q clean install`）。

- 首次执行会把 Maven 3.9.9 下载到 `~/.m2/wrapper/dists/`，因此需要能访问
  `repo.maven.apache.org`；下载包用 `.mvn/wrapper/maven-wrapper.properties` 里的
  `distributionSha256Sum` 做完整性校验。
- wrapper 只认 `JAVA_HOME`：未设置 `JAVA_HOME` 且 `java` 不在 `PATH` 时会直接报错。
- 本项目使用 **wrapper 的 `only-script` 模式**：仓库里没有 `maven-wrapper.jar`，
  只有 `mvnw` / `mvnw.cmd` 两个脚本 + `.mvn/wrapper/maven-wrapper.properties`，
  由脚本负责下载并解包发行版。

### 依赖引入

发布到 Maven Central 走根 POM 的 `deploy` profile（Central Portal，`central-publishing-maven-plugin`，
GPG 签名 + source/javadoc JAR）：

```bash
# 前置：settings.xml 配置 <server><id>central</id>（Central Portal 的 user token），
#       以及可用的 GPG 签名密钥
./mvnw clean deploy -Pdeploy
```

使用方等构件上线后可直接从 Central 引入；本地开发验证则先安装到本地仓库：

```bash
./mvnw clean install
```

```xml
<dependency>
    <groupId>io.github.wxd-gaming</groupId>
    <artifactId>com.wxdgaming.batis.sql.mysql</artifactId>
    <version>1.1.0</version>
</dependency>
```

只依赖核心模型（注解、DSL 模型、异常）时引 `com.wxdgaming.batis`；
需要 JDBC 能力但自行接其它方言时引 `com.wxdgaming.batis.sql`。

---

## 快速开始

### 1）定义实体

```java
@Setter
@Getter
@Table(name = "t_account", comment = "账号表",
       indexes = @Index(columnList = "account_name", unique = true))
public class AccountEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(type = "BIGINT", comment = "账号ID")
    private long id;

    @Column(length = 64, comment = "账号名", nullable = false)
    private String accountName;

    @Column(type = "DECIMAL(12,2)", comment = "余额", defaultValue = "0.00")
    private BigDecimal balance;

    @Column(type = "INT", comment = "VIP等级", defaultValue = "0")
    private int vipLevel;

    @Column(comment = "最后登录时间")
    private Date lastLoginTime;

    @Column(comment = "是否冻结", defaultValue = "0")
    private boolean frozen;
}
```

### 2）创建模板

```java
HikariConfig config = new HikariConfig();
config.setJdbcUrl("jdbc:mysql://localhost:3306/test?serverTimezone=Asia/Shanghai&useSSL=false");
config.setUsername("root");
config.setPassword("");
config.setDriverClassName("com.mysql.cj.jdbc.Driver");

MysqlTemplate template = new MysqlTemplate(config);
```

`MysqlTemplate(HikariConfig)` 在构造时**自动检查目标库是否存在**：从 JDBC URL 正则提取库名，
改连 `mysql` 系统库执行 `CREATE DATABASE IF NOT EXISTS ... CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci`，
再创建 `HikariDataSource`。若连不上系统库（权限不足等），只打 `warn` 日志并跳过自动建库，
后续由连接池抛出原始异常。已持有连接池时改用 `MysqlTemplate(HikariDataSource)`，跳过自动建库。

### 3）建表 / 结构同步

```java
template.checkTable(AccountEntity.class);   // 幂等：表不存在则建，存在则做结构漂移比对
```

### 4）读写

```java
try (BeanRunner br = template.beanRunner()) {
    AccountEntity a = new AccountEntity();
    a.setAccountName("sel-user-1");
    a.setBalance(new BigDecimal("10.00"));

    Number id = br.insertBean(a);              // 自增主键回写到 a.getId()
    AccountEntity loaded = br.queryById(AccountEntity.class, a.getId());
    loaded.setVipLevel(1);
    br.updateBean(loaded);
    br.deleteBean(loaded);
}
```

`JdbcTemplate` 是长期复用的门面（字段除表结构缓存外都是启动期注入的只读引用，线程安全）；
`QueryRunner` / `BeanRunner` / `CachedBeanRunner` **不是**线程安全的，且独立持有连接，
必须在使用后 `close()`（都实现了 `AutoCloseable`，推荐 try-with-resources）。

---

## 实体定义

### 注解一览（`com.wxdgaming.batis.annotation`）

| 注解 | 目标 | 作用 |
| --- | --- | --- |
| `@Table` | 类 | 表名、表注释、复合索引列表。**优先级高于 `@Entity`**；两者同时存在时 `@Entity` 被完全忽略 |
| `@Entity` | 类 | 实体标记；表名/注释语义与 `@Table` 等价，但**没有 `indexes` 属性** |
| `@Column` | 字段 | 列名、物理类型、长度、精度、注释、默认值、nullable、unique、存储模式、自定义转换器 |
| `@Id` | 字段 | 标记主键（可标多个字段构成联合主键） |
| `@GeneratedValue` | 字段 | 主键生成策略，需与 `@Id` 配合 |
| `@Index` | 字段 / 内嵌于 `@Table#indexes` | 单列索引或复合索引 |

**不标任何注解也能用**：表名取类简单名驼峰转下划线、列名取字段名驼峰转下划线、
长度默认 255、nullable 默认 true、类型按 Java 类型推断。注解是「覆盖默认值」的手段，
不是必要条件。

### 命名规则

- 表名：`@Table(name=...)` 非空则**原样使用**（不再做命名转换）；否则类简单名驼峰转下划线。
- 列名：`@Column(name=...)` 非空则原样使用；否则字段名驼峰转下划线。
  查询 DSL 里传**列名或 Java 字段名都能被解析**。
- 索引名：**不可自定义**，统一生成为 `表名_列名(多列按下划线组合)_类型后缀`
  （唯一索引 `_uk`、普通索引 `_idx`），例如 `t_account_account_name_uk`、
  `t_player_server_id_level_idx`。名字是"表 + 列 + 唯一性"的纯函数：同样的定义在任何环境
  生成同一个名字，既是去重依据（同名即同定义，`distinct` 自然合并）也是漂移比对的锚点；
  自带表名前缀，在 PG（索引名 schema 级唯一）上也不会跨表撞名。超过 63 字符
  （PG/MySQL 便携上限）时截断中段并补稳定哈希，类型后缀保留。
  三步索引解析（字段级 → `Table#indexes` → `Column#unique` 自动派生）合并后按名字去重、排序。
  **迁移注意**：本口径替代了旧的 `uk_<列名>`/`idx_<列名>` 命名——存量表上的旧名索引
  在下次 `checkTable` 时会被判为"实体未声明的索引"：默认 DROP（有 WARN 日志），
  想保留可 `setDropRedundantIndexes(false)`，新名索引会同步补建。

### 类型推断（`@Column#type` 为空时）

`EntityParser` 按 `storageMode` 分支推断：

- `AUTO`（默认）：基础类型（包装类、`String`、`byte[]`、时间族、`UUID` 等）直接映射；
  `Collection` / `Map` / 其它数组 / 不在基础名单里的类型（自定义 POJO、枚举等）**自动升级为 JSON**。
  `byte[]` 是例外——保持原生 BLOB 映射，不做任何序列化。
- `JSON`：强制 JSON 字符串存储，列类型推断为 `LONGTEXT`。
- `BLOB` / `MEDIUMBLOB` / `LONGBLOB`：强制二进制存储（Protostuff 序列化），映射到对应 BLOB 类型。
- 显式指定基础档位（`INT`、`VARCHAR` 等）：跳过全部推断。

几处**会被强制覆盖的值**（都是为守住「零漂移」不变式）：

| 情形 | 强制结果 |
| --- | --- |
| 字段标了 `@Id` | `nullable` 强制为 `false`（注解里写 `true` 也不生效） |
| `char` / `Character` | 长度固定 1 → `CHAR(1)` |
| `UUID` | 长度固定 36 |
| `BigDecimal` 且 `storageMode = AUTO` | 长度 20、精度 6 → `DECIMAL(20,6)`（`length` / `scale` 填值被覆盖） |
| `String` 且 `length > 16383 / 65535 / 16777215` | 分别升档为 `TEXT` / `MEDIUMTEXT` / `LONGTEXT`（判定用严格大于） |

想拿到别的小数位，只能显式写 `@Column(type = "DECIMAL(10,2)")` 或显式指定非 AUTO 的 `storageMode`。

### 解析缓存

`EntityParser` 内部用 `ConcurrentHashMap<Class<?>, TableInfo>` 缓存解析结果，
`computeIfAbsent` 保证同一 Class 只解析一次（并发首次访问也只有一个线程真正执行）。
`TableInfo` 建好后视为只读共享；运行期改注解不会生效，需要
`EntityParser.clearCache(Class)` / `EntityParser.clearAll()`。

### 转换器

```java
@Column(converter = MyConverter.class)
private MyType value;
```

转换器需实现 `com.wxdgaming.batis.converter.TypeConverter`，**必须有无参构造函数**，
实例会被框架缓存复用（需线程安全）。指定后该字段的 Bean↔DB 值转换完全由转换器接管，
`storageMode` 亦被忽略。注意实例是在**解析该字段时**反射创建的，
因此缺无参构造或构造抛异常会让整个实体解析失败（报的是实例化异常，不是数据访问异常）。

---

## 查询 DSL

### 模型（`com.wxdgaming.batis.query`）

| 类型 | 说明 |
| --- | --- |
| `Criteria` | 条件树节点，`Kind` 区分叶子/AND/OR/NOT；提供 `where(column)`、`allOf(...)`、`anyOf(...)`、`not(...)`、`raw(fragment, params...)`、`and/or/negate`、`flatten()` |
| `CriteriaBuilder` | 由 `Criteria.where(column)` 返回的链式构造器 |
| `Criterion` | 叶子记录：`record Criterion(String column, CriterionOperator op, List<Object> values)` |
| `CriterionOperator` | 操作符枚举 |
| `Sort` | 排序：`Sort.by(...)` / `Sort.asc(...)` / `Sort.desc(...)`，`record Order(property, Direction)` |
| `Pageable` | 分页请求：`Pageable.of(page, size[, sort])`，`getOffset()` |
| `Page<T>` | 分页结果：`getContent/getTotal/getPage/getSize/getSort/getTotalPages/hasNext/isEmpty` |

`CriteriaBuilder` 的完整操作符方法：

| 方法 | 说明 |
| --- | --- |
| `eq` / `ne` / `gt` / `ge` / `lt` / `le` | 常规比较 |
| `like` / `leftLike` / `rightLike` | 模糊匹配（自动补 `%`） |
| `in(Collection)` / `in(Object...)` / `notIn(...)` | 集合匹配 |
| `between(lo, hi)` | 区间 |
| `isNull` / `isNotNull` | 空值判断 |
| `eqCol(otherColumn)` | 列对列比较（右侧是列名而非参数） |

`CriterionOperator`（16 个常量）与 `getSql()` 返回的 SQL token：

| 常量 | SQL token | 说明 |
| --- | --- | --- |
| `EQ` / `NE` | `=` / `<>` | 相等 / 不等 |
| `GT` / `GE` / `LT` / `LE` | `>` / `>=` / `<` / `<=` | 大小比较 |
| `LIKE` / `LEFT_LIKE` / `RIGHT_LIKE` | 三者皆为 `LIKE` | 通配符由**绑定值**提供，不由 token 表达 |
| `IN` / `NOT_IN` | `IN` / `NOT IN` | 集合匹配 |
| `BETWEEN` | `BETWEEN` | 区间（`lo` 在前） |
| `IS_NULL` / `IS_NOT_NULL` | `IS NULL` / `IS NOT NULL` | 无参数 |
| `EQ_COL` | `=` | 右侧是**列标识符**而非参数 |
| `RAW` | `null` | 原始 SQL 片段逃生舱（无操作符 token），`column` 存片段、`values` 存绑定参数 |

`Criterion#placeholderCount()` 对 `IS_NULL` / `IS_NOT_NULL` / `EQ_COL` 返回 0，其余返回 `values.size()`
——渲染器用它校验「值个数 == 占位符个数」。

`RAW` 有注入风险，`Criteria.raw` 仅限受信输入。

几处构造期的快速失败（都是为了避免生成坏 SQL）：

| 调用 | 行为 |
| --- | --- |
| `eq(null)` / `ne(null)` | 自动降级为 `IS_NULL` / `IS_NOT_NULL` |
| `like/leftLike/rightLike(null)` | 抛 `IllegalArgumentException("LIKE 模式不能为 null")` |
| `in(...)` / `notIn(...)` 传空集合或含 null | 抛 `IllegalArgumentException`（否则会渲染出 `IN ()`） |
| `Criteria.allOf()` / `anyOf()` 全空 | 抛 `IllegalArgumentException("条件组合不能为空")` |
| `Pageable.of(page<1, ...)` / `size<1` | 抛 `IllegalArgumentException` |

`allOf` / `anyOf` 会过滤 `null` 成员，且**只剩一个成员时塌缩为该成员本身**
（`allOf(leaf).getKind() == LEAF`，渲染结果与裸 leaf 一致）。
`in` 用 `List.copyOf` 快照入参，防止调用方后续修改集合影响已构建的条件。

### 链式查询（`SelectQuery<T>`）

```java
List<AccountEntity> list = template.select(AccountEntity.class)
        .where(Criteria.where("vipLevel").eq(1))
        .orderBy(Sort.by("id"))
        .list();

long hits = template.select(AccountEntity.class)
        .where(Criteria.where("accountName").like("user-1"))
        .count();

Page<AccountEntity> page = template.select(AccountEntity.class)
        .where(Criteria.where("frozen").eq(false))
        .pageResult(Pageable.of(1, 10, Sort.by("id")));
```

`SelectQuery` 公开方法：`where` / `and` / `or` / `columns` / `orderBy` / `limit` / `offset` /
`page(Pageable)` / `list()` / `one()` / `first()` / `count()` / `exists()` /
`pageResult([Pageable])` / `toSql()` / `toParams()`。

语义要点：

- `one()` 命中 0 条返回 `null`，命中多条抛 `TooManyResultsException`；`first()` 取首条。
- 列名解析失败抛 `UnknownColumnException`（而不是把裸字符串拼进 SQL）——
  投影列同样会过 `resolveColumn`，所以写错列名会在真库上直接变 1054 而非静默出错。
- 参数顺序不变式在 `SelectSqlBuilder#render` 单点保证：`[WHERE 展平参数..., LIMIT, OFFSET]`；
  `COUNT` 渲染只含 WHERE 参数（排序与分页整块跳过，避免参数顺序被分页参数污染）。
- 渲染是纯函数：`SelectSqlBuilder.build` / `buildCount` 返回 `record Plan(String sql, Object[] params)`，
  可脱离数据库单测与排障。

### 便捷方法（`JdbcTemplate` 直接可用）

```java
List<AccountEntity> list = template.queryList(AccountEntity.class, Criteria.where("frozen").eq(false));
AccountEntity one       = template.queryOne(AccountEntity.class, Criteria.where("accountName").eq("u1"));
long total              = template.count(AccountEntity.class, Criteria.where("vipLevel").eq(0));
Page<AccountEntity> p   = template.queryPage(AccountEntity.class, Criteria.where("vipLevel").eq(0),
                                             Pageable.of(1, 2, Sort.by("id")));
```

### 原生 SQL 查询

`BeanRunner` / `DbToBeanHandler` 提供裸 SQL 入口（列名与占位符由调用方负责，
框架不做 `whereSection` 那样的注入检查）：

```java
try (BeanRunner br = template.beanRunner()) {
    AccountEntity one  = br.queryOne(AccountEntity.class, "account_name = ?", "u1");
    List<AccountEntity> list = br.queryList(AccountEntity.class, "vip_level > ?", 0);
    List<AccountEntity> all  = br.queryAll(AccountEntity.class);
}
```

---

## 数据读写

### `JdbcTemplate`——模块门面

| 成员 | 说明 |
| --- | --- |
| `JdbcTemplate(HikariConfig)` / `JdbcTemplate(HikariDataSource)` | 构造不建表、不连库（Hikari 首次 `getConnection` 才真正建连） |
| `queryRunner()` | 新建 `QueryRunner`（独立持有连接，需 close） |
| `beanRunner()` | 新建 `BeanRunner` |
| `cachedBeanRunner([EntityCache])` | 新建 `CachedBeanRunner` |
| `transactionTemplate()` | 事务模板 |
| `inTransaction(Function<TransactionStatus,T>)` / `inTransaction(Propagation, ...)` | 事务快捷入口 |
| `select(Class)` | 启动链式查询 |
| `queryList/queryOne/count/queryPage` | 条件查询便捷方法 |
| `getConnection()` | **事务连接的唯一借出口**（未来读写分离/多数据源路由也收敛在这里） |
| `transactionActive()` | 当前线程是否在事务中 |
| `loadAllTables()` | 从库端加载全部表结构到缓存（全量替换语义） |
| `checkTable(Class)` / `checkTable(TableInfo)` | 建表 / 结构 diff 同步（幂等） |
| `clearTableCache()` | 清空表结构缓存 |
| `dropTable(Class|TableInfo)` / `truncateTable(Class|TableInfo)` | 删表 / 截断 |
| `translateException(SQLException, sql, params)` | 异常翻译 |

继承自 `DbTemplate`：`batchSize`（默认 **500**，`<= 0` 表示不分组一次性提交）、`stats`（`DbStats`）。

### `QueryRunner`——JDBC 执行器

```java
public QueryRunner(JdbcTemplate jdbcTemplate);                    // 从池借连接
public QueryRunner(JdbcTemplate jdbcTemplate, Connection conn);   // 复用给定连接（事务内）
```

核心方法：

```java
<R> R query(String sql, ResultSetHandler<R> handler);
<R> R query(String sql, Object[] args, ResultSetHandler<R> handler);
boolean execute(String sql);  boolean execute(String sql, Object[] args);
int update(String sql);       int update(String sql, Object[] args);
Number executeInsert(String sql, Object[] args);   // 声明 RETURN_GENERATED_KEYS
int[] executeBatch(String sql, List<Object[]> batchArgs);
```

另有 `isInTransaction()`、`isCloseConnection()`、`checkWritable()`（只读事务下拦写操作）、
`applyQueryTimeout(Statement)`、`close()`。

连接来源由内部 `enum Source { EXTERNAL, TRANSACTION, POOLED }` 区分：
`QueryRunner(JdbcTemplate)` 为 `POOLED`（延迟借连接），`QueryRunner(JdbcTemplate, Connection)`
为 `EXTERNAL`（外部连接，事务感知失效）。`getConnection()` 的取用优先级是
「已持有 → 线程事务绑定的 holder（需 `isTransactional()`）→ 连接池」。
`close()` **只关闭 `POOLED` 连接**，事务连接由 `TransactionTemplate` 负责归还。

写入路径（`execute` / `update` / `executeInsert` / `executeBatch`）都会先调 `checkWritable()`；
查询路径不调。`executeBatch` 的两条分支值得注意：

- **事务内**：`prepareStatement` → `addBatch` → `executeBatch()`，**不改 autoCommit、不提交**
  （由外层模板决定）。
- **事务外**：记住原 autoCommit → `setAutoCommit(false)` → 执行 → `commit()`；
  异常时静默 `rollback()` 并翻译抛出；`finally` 恢复原 autoCommit。

插入路径 `executeInsert` 会 `prepareStatement(sql, RETURN_GENERATED_KEYS)`，
取 `getGeneratedKeys()` 的**第一列**且仅当其为 `Number` 时返回，否则返回 `null`
（无自增列 / 驱动未回传 / 非数值键都落到这条分支）。即「标了 IDENTITY」不等于「保证拿到 ID」。

调试日志只打印批量条数与耗时/累计行数，**不打印逐行参数**（`formatParams` 会把 `char[]` 掩码为 `[***]`）。

`ResultSetHandlers` 提供映射工厂，例如 `ResultSetHandlers.bean(template, Class)`、
`ResultSetHandlers.beanList(template, Class)`、`ResultSetHandlers.list()`。

### `BeanRunner`——实体级 API

```java
public BeanRunner(JdbcTemplate jdbcTemplate);   // 自持 QueryRunner，close() 时一并关闭
public BeanRunner(QueryRunner queryRunner);     // 复用外部 runner，close() 不关它

Number insertBean(Object bean);                         // 返回自增主键并回写 bean
int    updateBean(Object bean);
<T> BatchResult<T> insertBeanList(List<T> beans);       // 批量（返回成功/失败明细）
<T> BatchResult<T> updateBeanList(List<T> beans);
<T> T queryById(Class<T> entityClass, Object id);       // 查不到返回 null
<T> List<T> queryAll(Class<T> entityClass);             // 永不返回 null
<T> SelectQuery<T> select(Class<T> entityClass);
<T> T queryOne(Class<T> entityClass, String whereSection, Object... params);      // @Deprecated(forRemoval)
<T> List<T> queryList(Class<T> entityClass, String whereSection, Object... params); // @Deprecated(forRemoval)
int deleteBean(Object bean);
<T> int[] deleteListBean(List<T> beans);
void deleteAll(Class<?> cls) / deleteAll(TableInfo);    // DELETE 全表（非 TRUNCATE，保留自增计数）
```

- `queryById` 按主键列数决定传参形态：单主键传值，复合主键需传 `Object[]`（顺序须与实体主键声明顺序一致）。
- 带 `whereSection` 的两个方法已标记 `@Deprecated(forRemoval = true)`，入参会被
  `SqlFragments.guardWhereSection` 过滤（拒绝 `;`、`--`、`/*`、`#`、`union`、`drop`、`delete`、
  `insert`、`update`、`information_schema`、`grant`、`into outfile/dumpfile` 等），
  空白则退化为全表查询。新代码请改用 `select(...)` 链式 DSL。
- **批量失败降级策略**：`insertBeanList` / `updateBeanList` 先试批量；失败后若异常不可重试
  且当前**不在事务中**，则降级为逐行执行并记录失败明细（`BatchResult.FailureDetail`）；
  若异常可重试（`DataAccessException#isRetryable()`）或处于事务中，则直接抛出——
  因为降级会掩盖重试语义、或在事务里留下部分写入。
  `deleteListBean` **不做**逐行降级，只按 `batchSize` 分块。
- 批量的 `baseIndex` 是「在原列表中的下标」，因此 `BatchResult#getFailures()` 的 `index`
  可直接用于定位入参元素。
- `batchSize` 在构造时从 `JdbcTemplate#getBatchSize()` 复制（默认 500，`<= 0` 或不超一屏则单次提交）。
- 性能统计只在这里记录（`insertBean`/`updateBean`/`deleteBean` 记 1 条，批量记成功条数）；
  注意批量降级为逐行时**会重复计数**（每次 `insertBean` 也各自记录一次）。

`BatchResult<T>`：`hasFailures()`、`getTotalCount()`、`getSuccessCount()`、`getErrorCount()`、
`getFailures()` → `FailureDetail#getIndex()/getBean()/getCause()`、`getRequestList()`。

`BeanRunner` **没有** `save()` / `get()` / `list()` / `page()` / `count()`——
这些走 `select(...)` 或 `JdbcTemplate` 的 DSL 便捷方法。

### `CachedBeanRunner`——带缓存与 Write-Behind

见 [缓存与 Write-Behind](#缓存与-Write-Behind)。

---

## 事务

编程式事务，用法与 Spring `TransactionTemplate` 类似：

```java
TransactionTemplate tx = template.transactionTemplate();

int rows = tx.execute(status -> {
    try (BeanRunner br = template.beanRunner()) {
        br.insertBean(a);
        br.updateBean(b);
    }
    return 1;
});

tx.executeWithoutResult(status -> { /* 无返回值场景 */ });
```

**实现要点**：一条连接 + 一个线程上下文槽位。`begin` 时从池借一条连接关掉 `autoCommit`，
把 `ConnectionHolder` 绑到 `TxContext`；期间任何 Runner 经 `JdbcTemplate#getConnection()`
拿到的都是这条连接；完成时提交/回滚、解绑、恢复连接状态再归还。

- **事务是线程绑定的**。跨线程池提交的任务一律在事务之外，不要在回调内跨线程执行需要参与事务的操作。
  线程绑定由 `TxContext` 实现：`ThreadLocal<Map<Object, ConnectionHolder>>`，
  key 是 `JdbcTemplate` 实例（因此天然支持多数据源），刻意不用 `InheritableThreadLocal`。
- **回滚规则：回调抛出的任何 `Throwable`（含 `Error`）都会回滚**新事务，
  或在「参与」场景下把全局 `rollbackOnly` 打上标记；事务内吞掉异常则照常提交。
  `status.setRollbackOnly()` 会在提交时被尊重。框架不按异常类型过滤，
  因此 `TransactionCallback` 无需声明 `throws`（`DataAccessException` 家族都是非受检异常）。
- `TransactionTemplate` 是轻量配置载体（`isolation` / `readOnly` / `timeoutSeconds`），
  可每次现建也可长期复用，但三个配置字段本身不是线程安全的——请按启动期配置对待。
- 加入事务的那一层**绝不提交也绝不回滚**，只把异常转成全局 `rollbackOnly` 标记，
  决定权留给开启事务的外层。这修掉了「内层批量写提前提交外层事务」的问题。
- `checkWritable()` 使只读事务下的 `execute` / `update` / `executeInsert` / `executeBatch`
  抛 `IllegalTransactionStateException("只读事务中不允许执行写操作")`。

### 回调时序（`TransactionSynchronization`）

`STATUS_COMMITTED = 0`、`STATUS_ROLLED_BACK = 1`、`STATUS_UNKNOWN = 2`。

```
提交路径：所有 beforeCommit() → conn.commit() → 解绑 TxContext + 归还连接
          → 所有 afterCommit() → afterCompletion(COMMITTED)
回滚路径：conn.rollback() → 解绑 TxContext + 归还连接 → afterCompletion(ROLLED_BACK)
```

- **`afterCommit` / `afterCompletion` 跑在事务之外**：触发点固定在「解绑 + 归还连接」之后，
  所以回调里的 SQL 用的是新借的 autocommit 连接，读到的也不再是「我在事务中」。
  这一点对 Write-Behind 是硬要求：刷盘若在事务上下文里失败，
  `requeueFailed` / `requeueDeleted` 会因 `txActive()` 仍为 true 直接返回，
  而那批脏数据已被 `takeByPrefix` 摘走——数据静默丢失、调用方却收到「事务成功」。
- `beforeCommit()` 抛异常 ⇒ 走回滚路径（此时 JDBC commit 尚未发出）。
- `afterCommit()` 只在提交成功后执行，其异常（含 `Error`）只记 `warn`，不影响事务结果，
  也不会把已提交的事务误报成回滚。
- `afterCompletion(int)` 保证**恰好触发一次**（`ConnectionHolder#markCompletionNotified()`
  用 `AtomicBoolean.compareAndSet` 把关），单个回调异常被吞掉。
- 提交失败 ⇒ `rollback()` + `afterCompletion(UNKNOWN)` + 抛 `TransactionStartedException`；
  回滚失败 ⇒ 只记 `warn` 并 `markRollbackFailed()`，之后 `release` 会跳过 autoCommit 恢复。
- `close()` 前会恢复 `readOnly=false` 与 `autoCommit=previousAutoCommit`。
  **`isolation` 不会被恢复**——只在 `doBegin()` 设置，属于文档化的连接池级副作用。

### 传播行为 `Propagation`

| 取值 | 行为 |
| --- | --- |
| `REQUIRED`（默认） | 存在事务则加入，否则新建 |
| `REQUIRES_NEW` | 挂起当前事务，用**另一条连接**新建独立事务（需池中至少 2 个可用连接，否则自己等自己死锁） |
| `SUPPORTS` | 存在事务则加入，否则非事务执行 |
| `NOT_SUPPORTED` | 挂起当前事务，非事务执行（每条 DML 各自 autocommit） |
| `MANDATORY` | 必须存在事务，否则抛 `IllegalTransactionStateException` |
| `NEVER` | 必须以非事务方式执行，存在事务则抛 `IllegalTransactionStateException` |

**未提供 `NESTED`（savepoint）**，这是有意为之：Write-Behind 的脏表按 key 记账，
无法跟着 savepoint 一起局部回滚，真要做必须连缓存语义一起重设计。

### 隔离级别 `Isolation`

`DEFAULT`（默认，不调 `setTransactionIsolation`，沿用库端配置）、`READ_COMMITTED`、
`REPEATABLE_READ`、`SERIALIZABLE`。**未提供 `READ_UNCOMMITTED`**（脏读不应作为中间件的入口）。
`toJdbcConstant()` 把 `DEFAULT` 映射为 `null`，`doBegin` 用「null 就跳过设置」表达「不动库端默认」。

### 事务后刷盘

```java
tx.executeAndFlush(cachedRunner, status -> {
    // 在事务里改缓存；刷盘安排在 afterCommit
    return null;
});
```

`executeAndFlush` 把 `flushAll()` 注册到 `afterCommit`：既不阻塞事务，又保证「提交成功才落库」。
放在事务内 `flushAll()` 会把**其他线程**的待写数据一并卷进本事务（脏表是 JVM 全局的），
本事务回滚时它们的写入也一起消失。代价是「提交成功但进程立刻崩溃」这一窗口内会丢脏数据
——这是 Write-Behind 的固有权衡，需要强一致请改用 `immediate` 写或直接走 `BeanRunner`。

---

## 缓存与 Write-Behind

### 缓存抽象（core 层）

```java
public interface EntityCache<K, V> {
    V getIfPresent(K key);
    V getOrLoad(K key, Function<K, V> loader);   // 单飞加载
    void put(K key, V value);
    void remove(K key);
    void clear();
    long size();
    void onEvict(Consumer<Map.Entry<K, V>> listener);
}
```

`LocalEntityCache<K,V>`（Caffeine 实现）默认 **maximumSize = 100_000**、
**expireAfterWrite = 30 分钟**；构造重载可分别指定 `maxSize`、`expireAfterWrite`、`TimeUnit`。
采用**固定过期**（而非 `expireAfterAccess`）：热 key 也会到期回源刷新，陈旧数据有界。

`CacheKeyBuilder`：`buildKey(Class, id)`、`buildKey(bean)`、`keyPrefix(Class)`、
`extractPkValue(bean)`（无主键时抛 `IllegalArgumentException`，提示「未定义主键」）。

### `CachedBeanRunner` 的缓存三防

| 问题 | 方案 |
| --- | --- |
| 缓存击穿（单 Key 并发） | `getOrLoad` 单飞加载，Caffeine 保证同一 Key 仅一个线程查 DB |
| 缓存雪崩（批量过期） | `expireAfterWrite` 固定过期，热 key 也会到期回源刷新 |
| 缓存穿透（删除后重查） | **Tombstone** 哨兵标记「已删除但未刷盘」的 Key |

### 写模式（`immediate` 开关）

| 方法 | `immediate = true` | `immediate = false`（默认） |
| --- | --- | --- |
| `insertBean(bean[, immediate])` | 写 DB + 写缓存 | 只写缓存 + 标记脏插入 |
| `updateBean(bean[, immediate])` | 写 DB + 更新缓存 | 更新缓存 + 标记脏更新 |
| `deleteBean(bean[, immediate])` | 删 DB + 删缓存 | 缓存写 Tombstone + 标记脏删除 |

其余公开方法：`queryById` / `queryAll` / `queryOne` / `queryList` / `select` / `deleteAll`、
`flushAll()` / `flush(Class)` / `flush(Object)`（均返回 `FlushResult`）、
`dirtyInsertCount()` / `dirtyUpdateCount()` / `dirtyDeleteCount()` / `dirtyTotalCount()`、
`cacheSize()`、`clearAll()`；另有 `setBatchSize(int)` 与 `setTxFlushMode(FlushMode)`（默认 `TX_IMMEDIATE`）。

补充语义（都来自实现，容易误判）：

- **读缓存只覆盖 `queryById`**。`queryById` 走 `getIfPresent` → 未命中则 `getOrLoad` 单飞回源
  并回填，且对回源结果再查一次 Tombstone；命中值还会做一次实体类型校验，
  类型不符（同表多实体共用 key）时按未命中重载，不会在调用点抛 `ClassCastException`。
- `queryAll` / `queryOne` / `queryList` / `select` 是**直通**的
  （新开 `BeanRunner`，绕过缓存与脏表），因此**延迟写的改动在这里看不见**，
  而 `queryById` 看得见。这个不对称是有意设计。
- **`deleteAll` 是例外**：它直连 DB 清表，同时按 key 前缀**失效该表的缓存**
  （不清脏表——脏数据之后仍会被刷进这张已清空的表，需要确定语义请先 `flushAll()`）。
- **`FlushResult` 报告「意图条数」与「实际影响行数」两组数字**：
  `getInsertCount/getUpdateCount/getDeleteCount` 与
  `getInsertAffected/getUpdateAffected/getDeleteAffected`，另有 `getTotalCount()` / `getTotalAffected()`
  ——`affected` 取自驱动回传的逐条行数，因此「更新一条库里不存在的行」会表现为
  `updateCount=1, updateAffected=0`（只统计"语句没抛异常"是发现不了这种情况的）。
  INSERT 侧驱动给不出行数时按「语句成功即 1 行」计。
- 刷盘顺序固定为 **INSERT → UPDATE → DELETE**，按具体 bean 类分组后复用
  `BeanRunner` 的批量方法；刷盘失败时把失败 bean 重新放回脏表
  （`requeueFailed` / `requeueDeleted`，**事务内不重放**）并记 `warn`。
- `flush(Object bean)` 会把该 key 从三张脏表摘出并就地执行，不走批量。
- **无主键实体也能用延迟写**：主键缺失（`null`、数值 `0`、复合主键全缺）时分配临时 key
  `table:#pending:N`（按对象身份记账），待刷盘写回主键后再把 key 与 Tombstone 迁移到真实 key。
- **临时 key 会在主键回写后迁移到正式 key**（立即写与批量刷盘两条路径都会迁移）：
  迁移前 `queryById(真实主键)` 必然未命中，且临时条目无人可读地占着缓存容量。
- **脏表与缓存是每个 `CachedBeanRunner` 实例一份**，不是进程全局的；
  `JdbcTemplate#cachedBeanRunner()` 每次调用都会新建实例（各自一份缓存），
  因此请把它当单例持有，否则多个实例之间互相看不见对方的脏数据与缓存。
- `clearAll()` 会清空三张脏表 + 临时 key 映射 + 缓存，并 `warn` 提示有未保存数据。

### 数据一致性三重机制

1. **手动刷盘**：`flushAll()` / `flush(Class)` / `flush(Object)`。
2. **淘汰兜底**：缓存条目过期/被容量挤出时 `onEvict` 回调**同步**把脏数据刷到 DB
   （本地实现用 Caffeine 的 `evictionListener`，默认在触发淘汰的线程上执行；
   换成异步回调的实现会让这条"最后一道防线"在进程退出时失效）。
3. **失败恢复**：刷盘失败后脏数据放回脏表（`putIfAbsent` 语义，不覆盖并发写入的新版本），
   下次 flush 或淘汰时重试；事务内摘走的会在回滚时由补偿放回。

### 事务边界三条硬规则

1. **事务内延迟写自动升级为立即写**（`FlushMode.TX_IMMEDIATE`，默认）：延迟写只落缓存不落 DB，
   而事务里的写必须当场可见、可回滚，否则外层回滚后缓存里还留着「从未提交过的数据」。
   同时把 key 记入事务写集，完成回调里非提交状态精确失效该 key 的缓存。
2. **事务内 `flushAll()` 等于 JOIN 当前事务**：全有或全无。摘取脏数据用的是 `remove`，
   一旦回滚而快照不追回，这批数据既没进 DB 也不在脏表里——永久丢失。所以刷盘前登记回滚补偿，
   用 `putIfAbsent` 把快照放回。
3. **推荐姿势是 `executeAndFlush(runner, cb)`**：业务在事务里改缓存，刷盘安排在 `afterCommit`。

`FlushMode` 另外三个取值：`TX_JOIN`（与 `TX_IMMEDIATE` 行为一致，差别只在意图声明）、
`TX_SUSPEND`（事务内延迟写直接抛 `IllegalTransactionStateException`）、
`TX_PENDING_BUFFER`（规划中，当前使用抛 `UnsupportedOperationException`）。
`CachedBeanRunner` 里的 `switch` **没有 default**——新增取值会被编译期强制要求表态。

### 读写不对称性（最容易踩的一点）

延迟模式下写方法**不碰 DB**，所以返回值失去意义：`insertBean` 返回 `null`（拿不到自增主键）、
`updateBean` / `deleteBean` 返回 `-1`（没动过行数）。缓存里是新的、DB 里是旧的，
进程内一致但**其他进程/其他连接看不到**。因此以下场景必须走 `immediate` 或先 flush：

- 依赖自增 ID 的后续逻辑；
- 跨实例读取；
- 「写完立刻用 SQL 统计」。

**刷盘窗口**：脏表与缓存是两份独立状态。条目可能已因容量/过期从缓存消失但仍躺在脏表里；
反过来缓存被淘汰时若该 key 还有脏数据，`handleEviction` 会当场补一次单条刷盘，
兜住「还没来得及 flush 就被淘汰」的丢失窗口。

---

## 分区表维护

分区能力是一套**独立的附加模块**（不改动任何既有代码），分四层：

| 层 | 类型 | 职责 |
| --- | --- | --- |
| 语义层 | `PartitionType`（enum） | `RANGE` / `LIST` / `HASH` / `KEY` / `RANGE_COLUMNS` / `LIST_COLUMNS` / `LINEAR_HASH` / `LINEAR_KEY` |
| 表达式层 | `PartitionExpr`（interface） | 把「按日/按月」翻译成库端整数表达式 |
| DDL 层 | `PartitionManager`（interface） | 5 个纯函数式方法，生成 DDL 文本（方言插入点） |
| 编排层 | `PartitionBuilder`（class） | 链式 API，组装配置、生成 SQL、执行 |
| 支撑 | `PartitionConfig` / `PartitionDef` / `PartitionCache` / `PartitionMetaReader` | 配置载体、分区定义、库端分区视图缓存、元数据读取 |

`PartitionManager` 的五个方法：

```java
String buildCreatePartition(PartitionConfig config);      // ALTER TABLE ... PARTITION BY ...（重建整表，代价最大）
String buildAddPartition(PartitionConfig config);         // ALTER TABLE ... ADD PARTITION (...)（日常滚动补分区）
String buildDropPartition(PartitionConfig config);        // ALTER TABLE ... DROP PARTITION ...（定义+数据一起删）
String buildTruncatePartition(PartitionConfig config);    // ALTER TABLE ... TRUNCATE PARTITION ...（只清数据留边界）
String buildReorganizePartition(PartitionConfig config);  // ALTER TABLE ... REORGANIZE PARTITION ... INTO (...)（拆分/搬数据）
```

全部是「生成文本」的纯函数，不碰数据库、不吞异常，因此可以先 `build` 看 SQL 再决定是否 `execute`
（生产 DDL 走人工评审）。

### 用法

```java
PartitionCache cache = new PartitionCache(template, MysqlPartitionMetaReader.INSTANCE);

// 每日分区（滚动补分区，幂等：已存在的分区会被缓存跳过）
PartitionBuilder.of(MysqlPartitionManager.INSTANCE, cache)
        .table(DailyLogEntity.class)
        .withExpr(MysqlPartitionExpr.INSTANCE)
        .rangeByDaily("log_time", LocalDate.now(), LocalDate.now())
        .executeAddPartition();

// 每月分区 + 尾部 MAXVALUE 兜底（建表阶段）
PartitionBuilder.of(MysqlPartitionManager.INSTANCE, cache)
        .table(MonthlyOrderEntity.class)
        .withExpr(MysqlPartitionExpr.INSTANCE)
        .rangeByMonthly("create_time", YearMonth.of(2026, 1), YearMonth.of(2026, 12))
        .withMaxValue()
        .executePartition();
```

`PartitionBuilder` 完整 API：

- 工厂：`of(manager, cache)`（支持执行）、`of(manager)`（只支持 `buildXxx`，`executeXxx` 会抛 `IllegalStateException`）
- 配置：`table(Class)` / `table(String)`（**会 `resetConfig()`，复用实例必须从这里重新开始**）、`withExpr(PartitionExpr)`
- 分区类型：`rangeBy(expr)` / `listBy(expr)` / `hashBy(expr, count)` / `keyBy(columns, count)` / `partitionBy(type, expr)`
- 定义：`addPartition(name, value)` / `addPartitions(PartitionDef...)` / `withMaxValue()`
- 维护：`dropPartition(name)` / `truncatePartition(name)` / `reorganizePartition(old, newDefs...)`
- 批量生成：`rangeByDaily(column, start, end[, daysPerPart])` / `rangeByWeekly` / `rangeByMonthly` / `rangeByQuarterly` / `rangeByYearly`
- 生成与执行：`buildPartition()` / `buildAddPartition()` / `buildDropPartition()` / `buildTruncatePartition()` / `buildReorganizePartition()`，以及对应的 `executeXxx()`

### 关键语义与坑

- **`LESS THAN` 是排他上界，且只有上界**。分区名取区间起点、边界取终点下一天：
  `PARTITION p20260801 VALUES LESS THAN (20260802)` 恰好容纳 08-01 一天。
- 列表**第一个**分区没有下界，早于起始日期的历史数据会落进第一个分区而不报错，
  所以起始日期设得偏早是安全的。
- **超出最后一个上界的数据会被数据库直接拒绝写入**（丢数据），这正是 `withMaxValue()` 的存在理由。
- 但兜底分区一旦存在，就**不能再用 `ADD PARTITION` 往它前面插分区**（MySQL 硬限制），
  需要扩边界时只能改用 `reorganizePartition`。因此「要滚动补分区」的表在基础分区阶段**不要**加 `pmax`。
- `withMaxValue()` 必须在所有 `rangeByXxx` **之后**调用（分区列表顺序即 DDL 顺序，MySQL 要求 MAXVALUE 在末尾）。
- `DROP` 与 `TRUNCATE` 的分工是「删不删定义」：误用 `DROP` 会让边界消失，新数据全堆进兜底分区，
  按时间查询失去分区裁剪而退化成全表扫，**且不报错**。
- `addPartition` 会走缓存去重（幂等），`dropPartition` **不做**存在性检查（点名删除，名字错由库端报错）。
- **`build*` 在没有目标时返回 `null`（这是正常结果，不是错误）**：`buildPartition()` 在未指定分区类型、
  或 RANGE/LIST 系列没有分区定义时返回 null；`buildDropPartition()` 在 `dropNames` 为空、
  `buildTruncatePartition()` 在未指定分区名、`buildReorganizePartition()` 在缺原分区名/新定义时同样返回 null。
  对应的 `executeXxx()` 会跳过执行并且**不动缓存**——早期这些路径会渲染出 `PARTITION BY RANGE(...) ( );`
  或 `ALTER TABLE \`t\` ;` 这类非法 DDL，重跑还会撞 "Duplicate partition name"。
- **`table(...)` 会重置上一轮的意图数据（含 `truncateName`）**：换表复用同一 builder 时不会拿旧表的分区名去 TRUNCATE 新表。
- 分区名与边界值都是纯数字编码，便于比较：
  按日 `p20260801` / 值 `20260802`；按月 `p202608` / 值 `202609`（`YEAR*100+MONTH`，零填充）；
  按周 `p202632` / 值 `202633`（`YEARWEEK(col,1)` 口径，起点回退对齐周一）；
  按季 `p20263` / 值（`YEAR*10+QUARTER`，Q4 后年份进位）；按年 `p2026` / 值 `2027`。

### 分层上的既有妥协

`PartitionType#RANGE_COLUMNS` / `LIST_COLUMNS` 是 MySQL 独有写法（RANGE 只接受整数表达式，
RANGE COLUMNS 才接受日期/字符串列），严格说属方言概念却放进了方言无关的枚举。
另外分区**边界子句文本**（`"VALUES LESS THAN (n)"`、`"VALUES LESS THAN MAXVALUE"`）
是在方言无关的 `PartitionBuilder` 里拼出来的。换 pgsql 时这两处需要下沉或参数化。

---

## 异常体系

所有数据访问异常都是 `RuntimeException`，根为 `DataAccessException`（携带 `sql` / `params` /
`errorCode` / `sqlState` 便于排查）。三大分支：

```
DataAccessException
├── TransientDataAccessException           可重试的瞬时故障
│   ├── DeadlockLoserDataAccessException   死锁（ER_LOCK_DEADLOCK 1213）
│   ├── CannotAcquireLockException         锁等待超时（ER_LOCK_WAIT_TIMEOUT 1205）
│   ├── QueryTimeoutException              查询超时
│   ├── CannotGetJdbcConnectionException   拿不到连接（ER_CON_COUNT 1040）
│   └── RecoverableDataAccessException
├── NonTransientDataAccessException        需业务修正，重试无意义
│   ├── DataIntegrityViolationException    数据完整性
│   │   ├── DuplicateKeyException          重复键（1062 / 自增冲突）
│   │   ├── ForeignKeyConstraintViolationException  外键（1216 / 1452）
│   │   ├── NotNullConstraintViolationException     非空（1048 / MariaDB 1364）
│   │   └── DataTruncationException        过长 1406 / 截断 1265 / 越界 1264
│   ├── BadSqlGrammarException             语法/表不存在（1064 / 1146 / 1096 / 1052 / 1582）
│   │   └── UnknownColumnException         未知列（1054）
│   ├── TypeMismatchDataAccessException
│   ├── TooManyResultsException            one() 命中多条
│   ├── BatchUpdateFailedException         批量更新失败
│   └── UncategorizedDataAccessException
└── TransactionException                   事务状态
    ├── IllegalTransactionStateException   传播行为约束被违反
    └── TransactionStartedException
```

MySQL 翻译策略：**优先用服务端数字错误码**（稳定、语义唯一，不受语言/版本影响），
未命中时委托 `DefaultSqlExceptionHandler` 按 SQLState / 关键字兜底，**绝不抛「翻译失败」**。
码表集中在 `MysqlSqlCodes`。调用方据返回的具体子类决定「可重试（死锁、锁超时）还是直接失败
（重复键、语法错误）」。

> 注意：MariaDB 对无默认值的 NOT NULL 列报 **1364**，MySQL 报 **1048**，两者同归
> `NotNullConstraintViolationException`，调用方无需感知这个方言差异。

---

## MySQL 方言内部

`MysqlTemplate#initDefaults()` 用 **12 个 handler** 填满核心层的全部 SPI 字段。其中
`MysqlBeanValueHandler` 是唯一被 4 个 handler **共享**的协作者（值转换逻辑只实现一份，
保证 INSERT 写入端与 SELECT 读取端对同一列使用完全对称的转换规则）：

| SPI 字段 | MySQL 实现 | 职责 |
| --- | --- | --- |
| `dbMetaProvider` | `MysqlDbMetaHandler` | 库端元数据回读 |
| `columnTypeDefHandler` | `MysqlColumnTypeDefHandler.INSTANCE` | 逻辑类型 → 物理类型渲染与规格化 |
| `tableCreateHandler` | `MysqlTableCreateHandler` | 建表 DDL |
| `tableAlterHandler` | `MysqlTableAlterHandler` | 结构漂移比对与 ALTER |
| `tableDropHandler` / `tableTruncateHandler` | `MysqlTableDropHandler` / `MysqlTableTruncateHandler` | 删表 / 截断 |
| `sqlDbToBeanHandler` | `MysqlDbToBeanHandler` | 结果集 → 实体 |
| `beanToInsertHandler` / `beanToUpdateHandler` / `beanToDeleteHandler` | `MysqlBeanToInsertHandler` 等 | 实体 → SQL 与参数 |
| `beanToSelectHandler` | `MysqlBeanToSelectHandler` | 按主键/全表 SELECT |
| `sqlExceptionHandler` | `MysqlSqlExceptionHandler` | 异常翻译 |

**分区不在这一层装配**：`JdbcTemplate` 没有分区 SPI 字段，需调用方自行构造
`new PartitionCache(template, MysqlPartitionMetaReader.INSTANCE)` 并
`PartitionBuilder.of(MysqlPartitionManager.INSTANCE, cache)`。

### 元数据回读（`MysqlDbMetaHandler`）

**用 `information_schema` 直查而不是 `DatabaseMetaData`**，原因是前者能保留 `COLUMN_TYPE`
的原生形态（含显示宽度与 `unsigned`），并且排序可控——这正是结构比对需要的原始素材。

- 当前库：`SELECT DATABASE()`
- 表：`SELECT TABLE_NAME, TABLE_COMMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA = ?`
- 列：`SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT, COLUMN_KEY, EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION`
  → 把 `COLUMN_TYPE` 原样写进 `ColumnInfo#type`（**逻辑类型 `sqlType` 在库端一侧故意留空**，比对全部走 `normalize`）
- 索引：`... FROM information_schema.STATISTICS ... ORDER BY INDEX_NAME, SEQ_IN_INDEX`
  → 用 `LinkedHashMap` 按索引名聚合以保持列序，`PRIMARY` 索引被跳过（已由 `COLUMN_KEY='PRI'` 表达）

三条 SQL 的占位符契约固定：表列表 1 个（catalog），列/索引 2 个（catalog、表名）。

### DDL 生成

**建表**（`MysqlTableCreateHandler#buildSql`）：

```sql
CREATE TABLE IF NOT EXISTS `t` (
  `col` TYPE [NOT NULL] [DEFAULT v] [AUTO_INCREMENT] [COMMENT '...'],
  ...,
  PRIMARY KEY (`a`, `b`),
  [UNIQUE ]INDEX `idx` (`c`, `d`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4[ COMMENT='...'];
```

`IF NOT EXISTS` + InnoDB + utf8mb4 是为「启动期幂等同步 + 事务 + 完整 Unicode」而定的；
标识符统一小写 + 反引号；`PRIMARY KEY` 始终单独成句（复合主键安全）；
`AUTO_INCREMENT` 只在「是主键且策略为 `IDENTITY`」时追加。
`buildSql(TableInfo)` 另有一个不依赖模板的重载，可单独预览 DDL。

**结构漂移比对**（`MysqlTableAlterHandler`）：

- 列：实体有、库端无 ⇒ `ADD COLUMN`；两边都有但有变化 ⇒ `MODIFY COLUMN`。
  变化判定 = `renderNormalized(实体列) != normalize(库端 COLUMN_TYPE)`
  **或** nullable 不同 **或** 默认值不同 **或** 注释不同。
- 索引：库端有、实体无 ⇒ `DROP INDEX`；实体有、库端无 ⇒ `ADD [UNIQUE] INDEX`；
  都存在但有差异 ⇒ **先 DROP 再 ADD**。索引列顺序参与比较（`(a,b)` ≠ `(b,a)`）。
- 所有子句合并成**一条** `ALTER TABLE ... , ...` 执行；无变化则**一条语句都不发**（零 ALTER 幂等）。

> ⚠️ **`MysqlTableAlterHandler` 从不生成 `DROP COLUMN`。** 实体里删掉的字段，库端的列会
> **永久保留**（只有索引会被删）。这是刻意的保守策略，但意味着「改实体就能清理表结构」
> 的预期不成立——需要清列时必须手工 DDL。

**删表 / 截断**：`DROP TABLE IF EXISTS ...`（幂等，缓存由 `JdbcTemplate` 负责失效）、
`TRUNCATE TABLE ...`（DDL，隐式提交、不可回滚、重置自增）。

索引只有两个产生点：`CREATE TABLE` 内联，以及合并后的 `ALTER TABLE` 里的 `ADD/DROP INDEX`。

### 错误码映射（`MysqlSqlCodes`，19 个常量）

| 错误码 | 常量 | 框架异常 |
| --- | --- | --- |
| 1040 | `ER_CON_COUNT` | `CannotGetJdbcConnectionException` |
| 1048 | `ER_BAD_NULL_ERROR` | `NotNullConstraintViolationException` |
| 1052 | `ER_NON_UNIQ_ERROR` | `BadSqlGrammarException` |
| 1054 | `ER_BAD_FIELD_ERROR` | `UnknownColumnException` |
| 1062 | `ER_DUP_ENTRY` | `DuplicateKeyException` |
| 1064 | `ER_PARSE_ERROR` | `BadSqlGrammarException` |
| 1096 | `ER_NO_TABLES_USED` | `BadSqlGrammarException` |
| 1110 | `ER_WRONG_FUNC_TO_ARG` | `BadSqlGrammarException` |
| 1146 | `ER_NO_SUCH_TABLE` | `BadSqlGrammarException` |
| 1205 | `ER_LOCK_WAIT_TIMEOUT` | `CannotAcquireLockException` |
| 1213 | `ER_LOCK_DEADLOCK` | `DeadlockLoserDataAccessException`（可重试） |
| 1264 | `ER_WARN_DATA_OUT_OF_RANGE` | `DataTruncationException` |
| 1265 | `ER_WARN_DATA_TRUNCATED` | `DataTruncationException` |
| 1364 | `ER_NO_DEFAULT` | `NotNullConstraintViolationException`（MariaDB） |
| 1406 | `ER_DATA_TOO_LONG` | `DataTruncationException` |
| 1451 | `ER_ROW_IS_REFERENCED_2` | `ForeignKeyConstraintViolationException` |
| 1452 | `ER_NO_REFERENCED_ROW_2` | `ForeignKeyConstraintViolationException` |
| 1467 | `ER_DUP_AUTO_INCREMENT` | `DuplicateKeyException` |
| 3024 | `ER_QUERY_TIMEOUT` | `QueryTimeoutException` |

未命中 ⇒ 委托 `DefaultSqlExceptionHandler` 按 SQLState/关键字兜底。

### 分区实现（`MysqlPartitionManager` 等）

`buildCreatePartition` 有两条渲染分支：

```sql
-- 需要边界值（RANGE / LIST / *_COLUMNS），分区名由调用方给出
ALTER TABLE `t`
PARTITION BY RANGE(YEAR(log_time)*10000 + MONTH(log_time)*100 + DAY(log_time))
(
  PARTITION p20260801 VALUES LESS THAN (20260802),
  ...
);

-- 只需要分区数（HASH / KEY / LINEAR_*），分区名由库端生成
ALTER TABLE `t`
PARTITION BY HASH(id)
PARTITIONS 8;
```

`RANGE_COLUMNS` / `LIST_COLUMNS` 走 `RANGE COLUMNS (<expr>)` / `LIST COLUMNS (<expr>)`（要求裸列名），
其余类型统一渲染成 `type.name().replace('_',' ')` + `(expr)`（因此 `LINEAR_HASH` → `LINEAR HASH(id)`）。
`DROP PARTITION` 支持一次点名多个并按名删除；`REORGANIZE PARTITION` 是唯一能改已有边界的语句。

`MysqlPartitionExpr` 的关键是**库端表达式与 Java 端边界值必须同口径**，否则数据会静默落进错误分区：

| 粒度 | 库端表达式 | Java 边界值 | 取名 |
| --- | --- | --- | --- |
| 日 | `YEAR(col)*10000 + MONTH(col)*100 + DAY(col)` | `y*10000+m*100+d`（8 位） | `p20260808` |
| 周 | `YEARWEEK(col, 1)`（周一为一周之始） | `WEEK_BASED_YEAR*100 + WEEK_OF_WEEK_BASED_YEAR`（6 位） | `p202632` |
| 月 | `YEAR(col)*100 + MONTH(col)` | 同式（6 位，零填充） | `p202608` |
| 季 | `YEAR(col)*10 + QUARTER(col)` | 同式（5 位） | `p20263` |
| 年 | `YEAR(col)` | 同式（4 位） | `p2026` |

周粒度用 `WEEK_BASED_YEAR` 而非 `getYear()`，跨年那一周才不会从 `202601` 倒退成 `202552` 而破坏单调性；
刻意不用 `TO_DAYS`（数值轴与 `dayValue` 不同）。所有分区名都是纯数字，`VALUES LESS THAN` 才能按数值比较。

`MysqlPartitionMetaReader` 从 `information_schema.PARTITIONS` 读现有分区名
（`TABLE_SCHEMA = ?` 必带，因为 `information_schema` 是全实例的；
`PARTITION_NAME IS NOT NULL` 用来过滤非分区表返回的那一行 NULL），
并在**调用时**用 `SELECT DATABASE()` 解析当前库；取不到库名只记 `debug` 并返回空列表。
注意 `PartitionCache` 会把读取失败降级为「warn + 空分区集」
——后果是**去重静默失效**、补分区作业会重复下发 `ADD PARTITION`。

---

## 类型映射

### `StorageMode`（写入侧策略）

`AUTO`、`BOOL`、`BYTE`、`SHORT`、`INT`、`LONG`、`FLOAT`、`DOUBLE`、`DECIMAL`、
`VARCHAR`、`TEXT`、`MEDIUMTEXT`、`LONGTEXT`、`BLOB`、`MEDIUMBLOB`、`LONGBLOB`、
`DATE`、`DATETIME`、`TIME`、`JSON`。

### `SqlType`（建表侧逻辑类型）

`BOOLEAN`、`BYTE`、`SHORT`、`MEDIUMINT`、`INT`、`BIGINT`、`FLOAT`、`DOUBLE`、`DECIMAL`、
`VARCHAR`、`CHAR`、`TEXT`、`MEDIUMTEXT`、`LONGTEXT`、`BLOB`、`MEDIUMBLOB`、`LONGBLOB`、
`DATE`、`TIME`、`DATETIME`、`TIMESTAMP`、`JSON`。

### MySQL 渲染（`MysqlColumnTypeDefHandler#renderType`）

| SqlType | MySQL 物理类型 |
| --- | --- |
| `BOOLEAN` | `TINYINT(1)` |
| `BYTE` / `SHORT` / `MEDIUMINT` / `INT` / `BIGINT` | `TINYINT` / `SMALLINT` / `MEDIUMINT` / `INT` / `BIGINT` |
| `FLOAT` / `DOUBLE` | `FLOAT` / `DOUBLE` |
| `DECIMAL` | `DECIMAL(len,scale)`，`len` 缺省 20、`scale` 取 `max(scale,0)` |
| `VARCHAR` | `VARCHAR(max(len,1))` |
| `CHAR` | `CHAR(len)`，`len` 缺省 1 |
| `TEXT` / `MEDIUMTEXT` / `LONGTEXT` | 同名 |
| `BLOB` / `MEDIUMBLOB` / `LONGBLOB` | 同名 |
| `DATE` / `TIME` / `DATETIME` / `TIMESTAMP` | 同名 |
| `JSON` | **`LONGTEXT`**（历史兼容，非原生 JSON，因此别指望用 SQL 的 JSON 函数查它） |

优先级：显式 `@Column(type=...)` 字面量**原样透传**（逃生舱，用于 `unsigned` / `zerofill`
这类逻辑类型表达不出的定义）。代价是从此与方言绑定，且长度/精度的自动修正都不再触发。
`sqlType` 与 `type` 皆空时抛 `IllegalArgumentException`——宁可建表当场失败也不默认给 `VARCHAR` 让数据错类型落库。

**规格化**（`normalize`）是结构比对的关键：

- 统一小写、去空白；
- **剥掉整数显示宽度**：MariaDB/MySQL 的 `COLUMN_TYPE` 会回报 `int(11)` / `tinyint(4)` /
  `bigint(20)` / `smallint(6)`，而实体侧渲染的是裸 `INT`。不剥宽度则每次启动都判成漂移、
  对全表刷一遍 `MODIFY`（大表上等于线上锁表事故）。
- **`tinyint(1)`（布尔档）与 `tinyint(4)`（单字节整数档）必须仍可区分**，否则布尔列与整数列会互相误判。
- 折叠 `decimal(20, 6)` 与 `decimal(20,6)` 为等价（旧实现认为二者不同，正是它对没变的 DECIMAL 列
  反复发 `MODIFY` 的根因）。
- `FLOAT` / `DOUBLE` 的显示宽度按旧语义丢弃；`VARCHAR` / `CHAR` / `DECIMAL` 的长度/精度是列契约，予以保留。

### 键映射（库端 → 逻辑类型）

`tinyint→BYTE`、`smallint→SHORT`、`mediumint→MEDIUMINT`、`int/integer→INT`、`bigint→BIGINT`、
`float→FLOAT`、`double→DOUBLE`、`decimal/numeric→DECIMAL`、`varchar→VARCHAR`、`char→CHAR`、
`tinytext/text→TEXT`、`mediumtext→MEDIUMTEXT`、`longtext→LONGTEXT`、`json→JSON`、
`tinyblob/blob→BLOB`、`mediumblob→MEDIUMBLOB`、`longblob→LONGBLOB`、`date→DATE`、`time→TIME`、
`datetime/timestamp→DATETIME`、`year→SHORT`、`bit→BYTE`、`binary/varbinary→BLOB`、
`enum/set→VARCHAR`。

> `SqlType.TIMESTAMP` 是**预留档**：目前没有任何路径产出它——`EntityParser` 不推断时间戳
> （`Date`/`LocalDateTime` 统一 `DATETIME`），MySQL 侧也把库端的 `timestamp` 读回成 `DATETIME`。

### JSON 与 BLOB 的取舍

- **JSON**（`StorageMode.JSON`）：可读、可被数据库函数查询，代价是序列化开销和体积。
  写入用 `JSON.toJSONString(value)`，读取用
  `JSON.parseObject(json, genericType != null ? genericType : javaType)`——
  即**按字段声明的泛型/类型反解**。
  > 默认档**刻意不带类型标记**（非多态列不注入任何 autoType 特性）：按 JSON 里的 `@type`
  > 实例化任意类，等于凡能写该 JSON 列者都能发起反序列化攻击。因此多态子元素会静默退化
  > （`List<接口>` 读回来是 JDK 代理，父类字段读回来是父类且丢子类独有字段）。
  > 需要保留运行时类型时逐列开启 `@Column(polymorphic = true)`：它走 fastjson2 的
  > `AutoTypeBeforeHandler` 白名单，允许实例化的类由**字段声明类型自己算出的继承树上界**给出，
  > 上界外的类名（`JdbcRowSetImpl` 这类 gadget）一律抛错并升级为带列名的
  > `TypeMismatchDataAccessException`；声明太宽、算不出上界的列（`Object`、`Map<String, Object>`）
  > 读写两侧都是空操作，避免把"谁能写这一列"当成唯一边界。
  > 这条通道确实带了 `JSONReader.Feature.SupportAutoType`（嵌套容器只有在这一档才读得回来），
  > 与旧文档"全程不开 autoType"的说法不同，安全边界改由白名单承担，并有测试钉住。
- **BLOB**（`StorageMode.BLOB` 等）：用 `ProtostuffUtil` 二进制序列化，体积小、速度快，
  但对数据库而言是一团不透明字节，无法按内容检索。
  `byte[]` 字段直接写原始字节；其它类型走 Protostuff。读取时 `java.sql.Blob` 会先取成 `byte[]`。
  需要人工排查或在 SQL 里查的用 JSON，纯粹存取大对象（快照、缓存体）的用 BLOB。

序列化/反序列化失败（JSON 与 Protostuff 各**重试 3 次**后仍失败）统一抛
`TypeMismatchDataAccessException`，消息里带字段名与值/字节长度，便于定位是哪个字段坏了。

---

## 性能统计

`DbTemplate#stats`（`DbStats`）随模板生命周期常驻，对所有实例共享同一份累计指标，`reset()` 清零。

- 分别统计 `INSERT` / `UPDATE` / `DELETE`（各一个 `OpStats`）；
- 每个操作类型收集：**单次**（最近一次耗时与条数）、**累计**（调用次数、记录总条数、总耗时、
  最大/最小/平均耗时）、**5 段耗时分布** `≤50ms / 50~500ms / 500~1000ms / 1000~5000ms / >5000ms`；
- 汇总：`getTotalCalls()`、`getTotalRecords()`、`getTotalMs()`、`getCumulativeQps()`、`getInstantQps()`；
- `toPrettyString()` 输出可读报告；`OpStats#snapshot()` 返回不可变 `OpStatsSnapshot` 记录。
- `setEnabled(false)` 后 `record()` 为空操作，零开销。

所有计数用 `AtomicLong` / `AtomicLongArray`，线程安全。`OpStats#record` 里 min 的更新以 `0`
作为「尚未初始化」哨兵——首次记录必须写入，之后只有更小的耗时才替换，否则 min 会永远停在 0。

---

## 测试

测试分两类，命名约定：单元测试 `*Test`，集成测试 `*IT`。
mysql 模块的 POM **显式把 `**/*IT.java` 纳入 surefire 的 includes**（默认只扫 `*Test.java`）。
根 POM 只为 surefire 锁版本（2.x 发现不了 JUnit 5），不再传 `--add-opens`：
JSON 序列化换成 fastjson2 后不再反射触碰 `java.lang.reflect` 内部字段，
原先为它开放 JDK 内部包的参数已成累赘，删掉后 core 单测仍全绿。

### 单元测试（无需数据库）

| 模块 | 测试类 | 覆盖 |
| --- | --- | --- |
| core | `EntityParserTest` | 注解解析、表名/列名转换、类型推断、索引合并与去重、缓存 |
| core | `query/CriteriaFlattenTest` | 条件树展平（AND/OR/NOT 嵌套） |
| core | `ProtostuffUtilTest` | 二进制序列化往返 |
| sql | `DefaultSqlExceptionHandlerTest` | 默认异常翻译（SQLState / 关键字兜底） |
| sql | `query/SelectSqlBuilderTest` | SELECT 渲染文本与参数顺序 |
| sql | `query/PaginationClauseTest` | 分页子句（`LIMIT ? OFFSET ?`）与参数顺序 |

### 集成测试（嵌入式 MariaDB）

`MariaDB4jExtension`（JUnit 5 `BeforeAllCallback` / `AfterAllCallback`）在测试类运行前拉起嵌入式
MariaDB，测试结束后 `db.stop()`：

- 默认端口 **3307**（避开本地 3306），root 空密码，建 `test` 库，
  连接串 `jdbc:mysql://localhost:<port>/test?serverTimezone=Asia/Shanghai&useSSL=false`；
- 数据目录 `~/.wxd-batis/maria4j-data`、二进制目录 `~/.wxd-batis/maria4j-base`，
  **持久化复用**（进程退出不删除），因此创建的表在测试进程重启后依然存在；
- 通过反射构造 `DB` 并复用已初始化目录（`MariaDB4j` 的 `newEmbeddedDB()` 会无条件执行
  `mariadb-install-db`，而它拒绝非空目录）。启动前清理上次异常退出遗留的 `*.pid` / `*.sock`，
  但**绝不删除 `aria_log_control`**（既是 Aria 引擎的真实控制文件，也被 mariaDB4j 当作「已安装」标记）。

可配置的系统属性：

| 属性 | 作用 |
| --- | --- |
| `-Dwxd.batis.mysql.url=jdbc:mysql://host:3306/test` | 使用外部 MySQL，**跳过**嵌入式实例 |
| `-Dwxd.batis.mariadb4j.dataDir=<path>` | 数据目录 |
| `-Dwxd.batis.mariadb4j.baseDir=<path>` | 二进制/解压目录 |
| `-Dwxd.batis.mariadb4j.port=<port>` | 端口 |
| `-Dwxd.batis.mariadb4j.clean=true` | 启动时清空 dataDir 重新初始化 |

| 测试类 | 验证内容 |
| --- | --- |
| `BeanRunnerTest` | 自增主键回写到 bean、类型化映射无损（重点 BigDecimal 精度与 boolean）、批量插入后 `queryAll` 顺序与条数、完整 insert→query→update→query→delete 生命周期 |
| `CachedBeanRunnerTest` | Write-Behind 全语义（19 个有序用例）：读写不对称、脏表按操作类型分三张并合并、Tombstone、三种 flush、事务边界与刷盘；含 **30 秒随机长跑**（3 秒 TTL 缓存 + 强制淘汰睡眠）与 **12 线程 × 40 轮多表并发**（共用同一 runner，约 10% 并发 flush），两者都带 `@Timeout(120, SECONDS)` |
| `QueryRunnerTest` | DDL/元数据管线（10 个有序且有依赖的用例）：`loadAllTables`、`checkTable` 建表、`information_schema` 回读（列数与 NOT NULL/主键）、索引 `t_role_account_uk`（唯一）与 `t_role_level_score_idx`（恰好 2 列）、`checkTable` 幂等、缓存清理、原生 SQL、`ResultSetHandlers` 各工厂、`DROP TABLE` |
| `QueryRunnerCloseTest` | `close()` 幂等、`isCloseConnection()`、事务内外 `isInTransaction()` 的状态切换 |
| `SelectQueryIT` | 查询 DSL（13 个用例）：条件、`in`/`between`/`like`、**OR 嵌套在 AND 下的括号化并与手写 SQL 交叉验证**、4 页翻页且用 `Set` 检测重复、`count`/`exists`、`one()` 的实体/null/`TooManyResultsException` 三态、投影列、未知列在任何 SQL 发出前就抛、`toSql()`/`toParams()` 文本与参数顺序、DSL 复用事务连接、已废弃 `whereSection` 的注入拦截 |
| `TransactionTemplateIT` | 12 个用例：提交可见性、异常回滚、`setRollbackOnly`、事务内 Runner 共用同一连接（用 Hikari `activeConnections` 计数与 `TxContext` 解绑验证）、**事务内批量不会提前提交**（3 行 → 回滚后为 0）、`REQUIRES_NEW` 用不同连接且不被外层回滚、只读事务拒写、回滚后的**缓存幽灵读防护**、事务内 `flushAll` 回滚后脏快照恢复、`executeAndFlush` 提交后刷盘、**并发转账后 `SUM(balance)` 守恒且失败事务为 0** |
| `SchemaDriftIT` | 类型解耦回归：建表后用第三方 `information_schema` 快照断言 20+ 列的**原生 MariaDB 形态**（`varchar(255)`、`tinyint(1)`、`tinyint(4)`、`int(11)`、`bigint(20)`、`smallint(6)`、`decimal(20,6)`、`longtext`…）；**再跑两轮 `clearTableCache` + `checkTable` 必须让快照逐字节相等（零 ALTER）**；手工制造 `varchar(32)` 漂移后被检测并改回 `varchar(64)` |
| `SqlExceptionIT` | 真库触发 1062 → `DuplicateKeyException`（并断言 `errorCode`/`getSql()`/`getParams()`/不可重试）、1054、1064、1048 **或** 1364（MySQL vs MariaDB）、1406（需先 `SET SESSION sql_mode='STRICT_TRANS_TABLES'`，`finally` 恢复）；1213 用合成 `SQLException` 验证；未知码 999999 验证兜底委托 |
| `PartitionManagerTest` | 14 个有序用例：按日 RANGE + `pmax` + 分区透明插入、`ADD PARTITION` 重复执行被缓存**跳过而不报错**、按月、按名 `DROP`（邻居分区完好）、`refresh`、build-only 文本、HASH 8 分区、按周、按年 2024–2027 + `pmax`、表达式值格式与单调性、`TRUNCATE` 保留分区定义、HASH/KEY/LIST 子句文本、`instanceof` 分层约束、`daysPerPart<=0` 被拒 |
| `MysqlColumnTypeDefHandlerTest` | **纯单测**：23 个 `renderType` 期望与历史字面量逐字节一致、显式物理类型原样透传、两者皆空抛异常、`normalize` 幂等且与旧 `normalizeType` 在 31 个历史输入上等价、刻意保留的差异（`decimal(20,6)`==`decimal(20, 6)`，`tinyint(1)`≠`tinyint(4)`） |
| `MysqlDbToBeanHandlerTest` | `toBean` 的列标签大小写回退（`SELECT ... AS \`NAME\`` 能映射到字段） |
| `DbStatsPerformanceTest` | **冒烟而非基准**：5 线程 × 1000 条分批共 5 万插入、5 万查询、5 万更新；唯一断言是最终 `COUNT(*) == 50000`（不丢不重），耗时与 5 段直方图只打印不断言 |
| `SqlDebugLogTest` | 用 logback `ListAppender` 断言调试日志内容：SQL + 参数 + 结果 + 耗时、失败日志带 SQL 上下文、批量只打印「参数组数」**不打印逐行参数**、`char[]` 参数被掩码为 `[***]`（口令不进日志） |

> 测试断言中，`BigDecimal` 刻意用 `compareTo` 而非 `equals`——JDBC 回读的 scale 可能与构造时不同
> （`10.00` 与 `10.0` 值相等但 `equals` 为假），用 `equals` 会得到「看着像 bug」的假失败。

---

## 目录结构

```
wxd-gaming.batis/
├── pom.xml                                  聚合 POM（版本 1.1.0，JDK 21，surefire 3.2.5）
├── README.md
├── .gitignore
├── mvnw / mvnw.cmd                          Maven wrapper 启动脚本（only-script 模式）
├── .mvn/wrapper/maven-wrapper.properties    固定 Maven 3.9.9 + distributionSha256Sum
├── com.wxdgaming.batis/                     核心层
│   └── src/main/java/com/wxdgaming/batis/
│       ├── annotation/                      @Table @Entity @Column @Id @GeneratedValue @Index (+GenerationType)
│       ├── parser/EntityParser.java         注解 + Java 类型 → TableInfo / ColumnInfo / IndexInfo
│       ├── model/                           TableInfo / ColumnInfo / IndexInfo / SqlType / StorageMode
│       ├── query/                           Criteria / CriteriaBuilder / Criterion / CriterionOperator / Sort / Page / Pageable
│       ├── cache/                           EntityCache / LocalEntityCache / CacheKeyBuilder
│       ├── converter/                       TypeConverter / NoneTypeConverter
│       ├── exception/                       异常体系（21 个类）
│       ├── util/ProtostuffUtil.java         二进制序列化
│       ├── DbTemplate.java                  批量大小 + 性能统计
│       ├── DbStats.java                     分操作类型的指标与直方图
│       ├── BeanToDbHandler / DbToBeanHandler  读写侧回调接口（泛型约束到 T extends DbTemplate）
│       └── Entity.java                      预留的空实体基类（与注解 @Entity 同名，注意区分）
├── com.wxdgaming.batis.sql/                 方言无关 JDBC 层
│   └── src/main/java/com/wxdgaming/batis/sql/
│       ├── JdbcTemplate.java                门面 + 方言 SPI 装配容器
│       ├── QueryRunner.java                 JDBC 执行器
│       ├── BeanRunner.java                  实体级 API
│       ├── CachedBeanRunner.java            缓存 + Write-Behind
│       ├── *Handler.java                    DbMeta / ColumnTypeDef / TableCreate·Alter·Drop·Truncate /
│       │                                    BeanToInsert·Update·Delete·Select / SqlDbToBean / SqlException
│       ├── query/                           SelectSqlBuilder / SelectQuery / SqlFragments
│       ├── tx/                              TransactionTemplate / TxContext / ConnectionHolder /
│       │                                    TransactionStatus / TransactionCallback /
│       │                                    TransactionSynchronization / Propagation / Isolation
│       └── partition/                       PartitionManager / PartitionBuilder / PartitionConfig /
│                                            PartitionDef / PartitionExpr / PartitionType /
│                                            PartitionCache / PartitionMetaReader
├── com.wxdgaming.batis.sql.mysql/           MySQL / MariaDB 方言
│   └── src/
│       ├── main/java/.../mysql/
│       │   ├── MysqlTemplate.java           装配全部 MySQL SPI + 自动建库
│       │   ├── Mysql*Handler.java           14 个方言实现
│       │   ├── MysqlSqlCodes.java           错误码常量表
│       │   └── partition/                   MysqlPartitionManager / MysqlPartitionExpr / MysqlPartitionMetaReader
│       └── test/java/.../mysql/             集成测试 + 测试实体 + MariaDB4jExtension
├── com.wxdgaming.batis.sql.pgsql/           PostgreSQL 方言
│   └── src/
│       ├── main/java/.../pgsql/
│       │   ├── PgsqlTemplate.java           装配全部 PG SPI + 自动建库
│       │   ├── Pgsql*Handler.java           12 个方言实现
│       │   ├── PgsqlIdentifier.java         标识符双引号引用（强制小写）
│       │   ├── PgsqlSqlLiteral.java         字符串字面量转义 / DEFAULT 字面量
│       │   ├── PgsqlSqlStates.java          SQLState 常量表
│       │   ├── PgsqlJsonType.java           json/jsonb 档位与迁移方向
│       │   └── PgsqlExtension.java          嵌入式 PG 测试扩展
│       └── test/java/.../pgsql/             集成测试 + 单测 + 测试实体
└── com.wxdgaming.batis.mongodb/             占位（仅空类 MongodbTemplate，已从聚合摘除）
```

---

## 已知边界与注意事项

1. **单数据源假设**。`JdbcTemplate#dataSource` 是 `final` 的单一 `HikariDataSource`，
   `getConnection()` 是唯一的借出口——将来读写分离/多数据源路由会收敛在这个点上。
2. **`NESTED` 传播与 `READ_UNCOMMITTED` 隔离级别有意缺失**，见上文说明。
3. **`TX_PENDING_BUFFER` 未实现**，使用会抛 `UnsupportedOperationException`。
4. **`SqlType.TIMESTAMP` 是预留档**，当前无任何路径产出。
5. **`GenerationType.AUTO` 行为等同 `NONE`**：MySQL 方言只判定 `IDENTITY`。
   想用数据库自增必须显式写 `IDENTITY`。
6. **`@Entity` 与 `@Table` 不可互补**：`@Table` 存在时 `@Entity` 被完全忽略，
   所以用 `@Entity` 就没法配复合索引（只能靠字段级 `@Index`）。
7. **主键回写不保证成功**：表无自增列、驱动未回传或回传值非数值时取到 `null` 而非异常。
8. **索引名不可自定义**：一律按 `表名_列名_类型后缀` 生成（见「命名规则」），同名即同定义，
   去重天然成立；改列组合或唯一性会改变索引名，对存量表表现为"删旧建新"（见命名规则的迁移注意）。
9. **`@Column#converter` 在解析期实例化**，缺无参构造会让整个实体解析失败，
   报错是实例化异常而非数据访问异常，排查时容易误认为 SQL 问题。
10. **`JSON` 存储模式在 MySQL 侧渲染为 `LONGTEXT`**，不是原生 `json` 列，无法用 JSON 函数查询。
11. **`CachedBeanRunner` 的延迟写不落库**，返回值无意义（`null` / `-1`），跨进程不可见；
    且只有 `queryById` 走缓存，`queryAll`/`queryOne`/`queryList`/`select` 直通数据库、
    **看不到未刷盘的延迟写**。`deleteAll` 是例外：它清库之外还会失效该表缓存。
12. **`MysqlTableAlterHandler` 从不生成 `DROP COLUMN`**：实体里删掉的字段，库端的列会永久保留
    （只删对应索引）。想清列必须手工 DDL。索引则相反——实体里没有声明的索引会被 `DROP`，
    现在会逐条 `warn` 出索引名，也可用 `setDropRedundantIndexes(false)` 改为只告警不删除。
13. **JSON 反序列化默认不碰 autoType**（防按 `@type` 实例化任意类的反序列化攻击），所以多态子类
    元素会静默退化；只有 `@Column(polymorphic = true)` 的列才走带白名单的还原通道
    （声明类型继承树外的类名一律抛错）。
14. **列的字符集/排序规则只告警不修复**：两边 `COLUMN_TYPE` 相同而字符集不同（库端 latin1、
    实体期望 utf8mb4）时，类型比对发现不了，框架会在 `checkTable` 时 `warn` 出列名，
    但不会自动 `MODIFY`——实体注解目前无法表达 charset/collation。
15. **自增属性只单向修复**：实体声明 `@GeneratedValue(strategy = IDENTITY)` 而库端列不是
    `AUTO_INCREMENT` 时会发 `MODIFY COLUMN` 修回来；反方向（库端有自增、实体没声明）只 `warn`，
    避免把存量表人工加的自增属性摘掉。
16. **表注释只单向修复**：实体声明了 `@Table(comment=...)` 且与库端不同才发 `COMMENT='...'`；
    实体没写注释时不会清空库端已有注释。
17. **`JdbcTemplate#loadAllTables()` 会静默返回**：未注入 `dbMetaProvider` 或取不到 catalog 时
    不加载任何表，`tableCache` 可能保持 `null`。`checkTable` 之后的刷新是**单表增量**的
    （3 条元数据查询），整段以内部锁互斥，避免并发重复下发 DDL。
18. **`PartitionCache` 把元数据读取失败降级为「空分区集」**，去重随之静默失效，
    补分区作业会重复下发 `ADD PARTITION`（库端报分区已存在）。
19. **`EntityParser` 缓存是强引用且无上限**：类只解析一次、无 TTL、无弱引用，
    热更实体定义必须显式 `clearCache` / `clearAll` 或重启。
20. **分区模块的分层并不彻底**：`RANGE_COLUMNS` / `LIST_COLUMNS` 是 MySQL 特化常量放在通用枚举里，
    边界子句文本在 `PartitionBuilder` 中拼接。换方言时这两处需要下沉。
21. **`logback-test.xml` 引用了本仓库未定义的类**
    `ch.qos.logback.core.LogbackResetTimeFilter`（`file_info` appender 的 filter），
    它必须来自外部依赖，否则测试日志配置会加载失败。
22. **DDL 里的 `DEFAULT` 与 `COMMENT` 现在走统一的转义工具**（`MysqlSqlLiteral`）：
    字符串默认值必须自带引号（如 `defaultValue = "'unknown'"`），裸字面量（`0`、`CURRENT_TIMESTAMP`、
    `CURRENT_TIMESTAMP(3)`）走字符白名单；两者都不符合会抛 `IllegalArgumentException` 而不是拼出坏 DDL。
23. **遗留的字符串 `whereSection` API 收紧了闸门**：除原有的分号/注释/UNION 黑名单外，
    现在要求占位符数量与参数数量一致，且**只要出现字符串字面量就会被直接拒绝**
    （无论是否同时带有占位符——`name = 'x' OR '1'='1 AND id = ?` 这类「字面量 + 占位符」混搭
    正是此前唯一能绕过闸门的形态）。常量写法改用 `status = ?` 传参即可；迁移到查询 DSL 彻底绕开。
24. **批量执行的"部分失败"现在会中断本批**：驱动若用 `Statement.EXECUTE_FAILED(-3)` 返回失败
    而不抛异常，`QueryRunner#executeBatch` 与 `BeanRunner` 的批量插入旁路会在 `commit` 之前
    回滚并抛 `BatchUpdateFailedException`（带首个失败下标）；`BatchUpdateException` 的
    `getUpdateCounts()` 也不再被丢弃，用于把失败定位到具体条目。
25. **缓存 key 只按表名隔离**：两个实体映射同一张表时会共用 key，`queryById` 已加类型校验
    （不符时按未命中重载，不会 `ClassCastException`），但两者会互相挤占同一条缓存条目。
26. **MySQL 客户端侧错误码已映射**：2006/2013 归 `RecoverableDataAccessException`（可重试），
    2002/2003 归 `CannotGetJdbcConnectionException`；SQLState `57`/`HYT`（语句超时、查询被取消）
    归 `QueryTimeoutException`，避免瞬时超时被当成"未知错误不可重试"而被批量降级放大。
27. ~~**根 POM 的编译版本配置有冗余**~~ **已修复**：原先 `maven.compiler.source/target`
    被声明两次，后一次用 `${java.version}`，而该属性在 POM 中**未定义**，实际取自运行
    Maven 的 JDK 保留系统属性——在 JDK 21 下碰巧与前面写死的 `21` 一致，换到 JDK 25
    编译就会把字节码目标提到 25，新环境因此表现不一致。现在根 POM 只保留
    `<java.version>21</java.version>` 与 `<maven.compiler.release>${java.version}</maven.compiler.release>`：
    `release` 同时约束语法级别和可引用的 JDK API，无法再跟随构建机 JDK 漂移。
28. **发布链路**：构件经 Central Portal 发布（根 POM `deploy` profile，GPG 签名，
    `settings.xml` 需配置 id 为 `central` 的 server token）；旧 OSSRH `s01.oss.sonatype.org`
    端点已停服，POM 中已清除相关死配置。仓库根目录已有 Apache-2.0 `LICENSE`。
    `mvnw` wrapper 已补齐（Maven 3.9.9，`only-script` 模式，含 SHA-256 校验）。
29. ~~**父 POM 把 `fastjson2` / `logback-classic` / `commons-*` / `protostuff` 作为 compile 依赖下发**~~
    **已修复**：`commons-lang3`/`commons-text`（全仓零引用）已移除，`logback-classic/core`
    降为 test scope（不再向使用方传递日志绑定），`fastjson2` 只在 `com.wxdgaming.batis.sql`
    （JSON 列编解码）以 compile 声明、`protostuff` 只在 `com.wxdgaming.batis`（BLOB 通道）
    以 compile 声明——这两个是功能本体，仍会传递引入，属预期；父 POM 仅在
    `dependencyManagement` 里统一版本。
30. **JSON 列里 `java.util.Date` 族（含 `java.sql.Timestamp`）落盘为 epoch 毫秒**：
    序列化经 `ValueFilter` 统一转毫秒（根位置是 Date 时整个值就是毫秒数字符串），
    `LocalDate`/`LocalDateTime` 保持 ISO 字符串不变。读取两侧兼容：毫秒与历史版本写出的
    本地时区字符串 `"yyyy-MM-dd HH:mm:ss"` 都能读回 `Date`——存量行无需迁移，但新旧行在
    库里形态不同，直接以人读/SQL 函数消费 JSON 文本的场景要注意。
31. **BLOB 通道（Protostuff）没有反序列化白名单**，与 JSON 列的继承树白名单不同源：
    根类型声明为 `Object`/接口/抽象类的 BLOB 列、以及 BLOB 列上声明 `polymorphic = true`，
    都会在实体解析期抛 `IllegalStateException`（配置错误快速失败）。多态需求请改用
    JSON 存储模式 + `polymorphic = true`。
32. **PG 时区族类型已可收敛**：`@Column(type = "timestamptz"/"timetz")` 短写与库端
    `format_type` 回读的长写（`timestamp with time zone`）折叠到同一比较键，重复
    `checkTable` 不再整表重写；`timestamp` ↔ `timestamptz` 互转的 ALTER 自动补
    `USING 列 AT TIME ZONE current_setting('TimeZone')`，存量值按会话时区解释——
    漂移修复前请确认会话 `TimeZone` 与数据写入口径一致。
33. **PG 索引名是 schema 级唯一**：实体声明的索引名被**别的表**占用时，`checkTable`
    告警并跳过创建（不抢名、不抛错）；本表上已存在的表达式/部分索引与约束支撑索引
    不受框架管理（不参与比对、不受 `dropRedundantIndexes` 控制），存在时启动会 WARN 计数。
34. **PG 列级 collation 只回读告警、不参与变更**：实体注解无法表达排序规则，
    库端列使用非默认 collation（如 `COLLATE "C"`）时启动 WARN 指出列名，框架绝不
    自动 ALTER——需要变更请手工 DDL。
