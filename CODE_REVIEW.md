# wxd-gaming.batis 代码审核报告

- 审核对象：`C:\code\wxd-gaming.batis`（master，工作树干净，HEAD = `e185a25`）
- 审核范围：核心模块 `com.wxdgaming.batis`、SQL 层 `com.wxdgaming.batis.sql`（含 `partition` / `tx` / `query`）、MySQL 方言模块 `com.wxdgaming.batis.sql.mysql`，以及测试体系与构建配置
- 审核方式：静态精读 + 逐条交叉取证（对每条高/中级结论回溯原始代码行）；未修改任何源文件
- **环境限制（影响结论强度）**：本机 `com.wxdgaming.batis.*/target/` 目录写入被拒绝，surefire 写报告失败后**执行了 0 个测试却仍报 BUILD SUCCESS**，因此本次所有结论均来自代码阅读而非运行验证；MySQL 模块的 `*IT` 还依赖下载嵌入式 MariaDB，同样未执行。`mvnw -o -DskipTests compile` 可通过（exit 0）。

---

## 零·续、2026-10-15 复审（HEAD = `408f95c`，pgsql 模块纳入范围）

> 本轮为复盘性质：核对上轮遗留项在当前代码的状态 + 对 pgsql 模块与 fastjson2 链路做增量深审 + 全量重跑测试。未修改任何源文件。

**测试验证（真跑，非纸面）**：`mvnw test` 全反应堆 `BUILD SUCCESS`，共 **337 个用例、0 失败**
（core 60 + sql 64 + mysql 101〔嵌入式 MariaDB 真库〕+ pgsql 112〔嵌入式 PG 真库〕）。
上轮"target/ 写入受限、0 测试报 SUCCESS"的环境问题本轮不存在。

**上轮遗留项复盘结论**

| 遗留项 | 状态 | 证据 |
| --- | --- | --- |
| 高：expectClass 宽接口旁路 | **仍未修** | `AbstractBeanValueHandler.java:391-394`：expectClass 分支只闸 `!isJdkPackage(clazz)`，不闸 expectClass 自身；业务类字段声明为 `Serializable`/`AutoCloseable` 等 JDK 宽接口时，第三方实现类（非 JDK 前缀）可经嵌套 `@type` 实例化 |
| 中：Date 落盘格式漂移 | **仍未修**（本轮实测） | fastjson2 2.0.65 默认把 `Date` 落盘为本地时区字符串 `"yyyy-MM-dd HH:mm:ss"`（无 `WriteDateUseDateFormat` 配置），fastjson1 时代的 epoch 毫秒存量行读回为 `Long`，两格式共存；且默认时区被 fastjson2 首次使用后缓存，运行中切 TZ 不生效 |
| 中：PG 跨表同名索引静默吞 | **仍未修** | `PgsqlTableCreateHandler.java:204` `CREATE INDEX IF NOT EXISTS` + `PgsqlDbMetaHandler.java:172` 只回本表索引：同名索引属于别的表时 PG 仅 NOTICE 跳过，本表索引永远建不上、每次启动重试、零日志 |
| 构建：依赖卫生 | **仍未修** | 父 POM 仍以 compile 下发 `commons-lang3`/`commons-text`（全仓零引用，已 grep 核实）、`logback-classic/core`（应为 test）、`protostuff`（仅 core 用，全员继承） |
| 轻微项 | 部分仍在 | 循环引用裸 SOE（`toJSONString` 的 `catch (Exception)` 捕不到 `StackOverflowError`，重试不生效）、每行解码重算白名单（`polymorphicCodec` 无缓存，代码注释自述有意为之）仍在；IDENTITY+defaultValue、timestamptz USING 见下方新发现归并 |

**本轮新发现（pgsql 模块为主）**

| 级别 | 发现 | 落点 |
| --- | --- | --- |
| 高 | **`@Column(type="timestamptz")` 永不收敛，每次启动整表重写**：实体侧 `normalize("timestamptz")` 原样返回，库端 `format_type` 回读 `"timestamp with time zone"` 折成另一个键，两键永不相等 → 每次 `checkTable` 发 `ALTER COLUMN TYPE`（PG 整表重写 relfilenode 变更），且该分支无 USING | `PgsqlColumnTypeDefHandler.java:125-127,228-265` + `PgsqlTableAlterHandler.java:109-113` |
| 中 | **Protostuff BLOB 通道反序列化零白名单**：`RuntimeSchema` 对 Object/接口字段把类名嵌进字节流按名反射实例化，无 allowlist；JSON 列有继承树白名单、BLOB 列没有任何等价护栏 | `ProtostuffUtil.java:91-92` + `AbstractBeanValueHandler.java:424-430` |
| 中 | 第三方容器实现类绕过容器归一：归一只置换 `java.` 前缀类，`Map` 字段位置的 commons-collections `LazyMap` 等经 expectClass 分支放行（与 expectClass 旁路同根因） | `AbstractBeanValueHandler.java:364-380,391-394` |
| 中 | timestamp↔timestamptz 漂移 ALTER 无 USING：`typeUsingClause` 只覆盖 json↔text，时区族互转按会话 TimeZone 静默重解释存量值 | `PgsqlTableAlterHandler.java:218-230` |
| 中 | 列级 collation 不回读：未查 `attcollation`/`pg_collation`，MySQL 侧 M4 已有等价护栏，pgsql 侧缺失 | `PgsqlDbMetaHandler.java:106-123` |
| 低 | 布尔默认值同义词表不全：只折 0/1/true/false，PG 还接受 `'t'/'f'/'yes'/'no'/'on'/'off'`，实体写这些写法时永不收敛、每次启动白发 SET DEFAULT | `PgsqlTableAlterHandler.java:50-52` |
| 低 | 表达式/部分索引被静默排除：`indexprs/indpred` 非空的索引不参与 diff、不受 dropRedundantIndexes 管理、无日志 | `PgsqlDbMetaHandler.java:173-175` |
| 低 | DROP INDEX 每索引一次 `SELECT current_schema()` 往返；metaProvider 缺失时降级为不限定 schema 的 DROP（其 javadoc 自述"可能删到别人的索引"） | `PgsqlTableAlterHandler.java:256-268` |
| 低 | 退化 SQL 无护栏：全 IDENTITY 列实体生成 `INSERT INTO "t" () VALUES ()`（PG 语法错误，应为 `DEFAULT VALUES`）；仅主键实体 UPDATE 生成空 SET | `PgsqlBeanToInsertHandler.java:49-55`、`PgsqlBeanToStatementHandler.java:65-66` |
| 低 | 复合主键强转 `(Object[]) idValue` 抛裸 ClassCastException，无列名上下文 | `PgsqlDbToBeanHandler.java:57` |
| 低 | 自动建库并发撞 42P04 时被外层 catch 误记为"无法连接 postgres 库" | `PgsqlTemplate.java:95-115` |

**已验证无问题的面**：fastjson2 版本全模块一致 2.0.65；JSON 反序列化入口收敛于基类 `fromJsonText`，两方言均委托；非多态列不开 `SupportAutoType`（fail-closed）；UNBOUNDED 列不写标记不注入 filter；pgsql 标识符/字面量/显式 type 注入闸门有真库测试覆盖（`PgsqlIdentifierTest`/`PgsqlSqlLiteralTest`/`PgsqlDdlTextTest`/`PgSchemaDriftIT` 等）；共享层 M1/M10-M12 修复 pgsql 自动继承。

**测试缺口**：timestamptz 收敛、跨表同名索引、排除索引告警、pgjdbc 下批量部分失败、`bpchar→json` 的 `::text` 中转分支均无回归用例。

---

## 零·续二、2026-10-15 修复实施记录（对应「零·续」复审结论）

> 本节由修复实施阶段追加。「零·续」的复审结论保留原样，便于对照"当初判断 → 实际改动 → 验证结果"。

**实施范围**：「零·续」两张表的全部条目——上轮遗留 4 项（expectClass 旁路、Date 格式漂移、PG 同名索引、依赖卫生）+ 本轮新发现 2 高 5 中 6 低，除"每行解码重算白名单"外全部修复；该项维持有意为之（`SubtypeFilter` 无状态、单行构造开销可忽略，代码注释已自述），不另设缓存。

**验证方式（真跑，非纸面）**：`mvnw test` 全反应堆 `BUILD SUCCESS`，共 **360 个用例、0 失败**（复审基线 337 → 新增 23 个回归用例）：

| 模块 | 用例数 | 新增回归 |
| --- | --- | --- |
| `com.wxdgaming.batis` | 64（+4） | BLOB 通道解析期闸门（`EntityParserTest`） |
| `com.wxdgaming.batis.sql` | 70（+6） | Date 毫秒落盘/双格式读回/循环引用包装（`JsonDateStorageTest` 新文件）、expectClass 双向闸门与第三方容器旁路（`JsonPolymorphicStorageTest`） |
| `com.wxdgaming.batis.sql.mysql` | 102（+1） | 复合主键标量主键值快速失败（`MysqlDbToBeanHandlerTest`） |
| `com.wxdgaming.batis.sql.pgsql` | 124（+12） | timestamptz 折叠（`PgsqlColumnTypeDefHandlerTest`）、布尔同义词全量/DEFAULT VALUES/空 SET/复合主键（`PgsqlDdlTextTest`）、**真库守护**（`PgDriftGuardsIT` 新文件：relfilenode 零重写、USING 迁移保真、跨表索引冲突、部分索引存活、collation 收敛） |

| 编号 | 问题 | 修复方式 | 主要落点 |
| --- | --- | --- | --- |
| 高·续1 | `@Column(type="timestamptz")` 永不收敛 | `normalize` 把 `timestamptz`/`timetz` 短写折到 format_type 长写键（`"timestamp with time zone"`/`"time with time zone"`），精度参数与库端同口径丢弃 | `PgsqlColumnTypeDefHandler` |
| 高·续2 | expectClass 宽接口旁路 + 第三方容器绕过 | expectClass 分支改为**双向闸门**：expectClass 自身是 JDK 包（`Serializable`/`AutoCloseable` 等宽接口）直接拒绝，候选类是 JDK 包同样拒绝——`WithResource` 字段带不进 `JdbcRowSetImpl`，commons-collections 容器实现也进不来 | `AbstractBeanValueHandler.SubtypeFilter` |
| 中·续3 | Protostuff BLOB 零白名单 | 解析期快速失败：BLOB 族列根类型为 `Object`/接口/抽象类（`byte[]` 原样落库除外）→ `IllegalStateException`；BLOB + `polymorphic=true` → `IllegalStateException`（该开关只在 JSON 路径生效）。`ProtostuffUtil` 类文档补"无类白名单"安全边界自述 | `EntityParser` / `ProtostuffUtil` |
| 中·续4 | Date 落盘格式漂移 | 新增 `DATE_AS_MILLIS`（`ValueFilter`，只命中 `java.util.Date` 族）：Date/Timestamp 一律写 epoch 毫秒，LocalDate/LocalDateTime 保持 ISO 字符串（实测 fastjson2 `"millis"` format 会误伤时间族，故用 ValueFilter 精确制导）；根位置 Date 特判为毫秒字符串。读侧 fastjson2 原生兼容毫秒与旧格式字符串，存量行免迁移 | `AbstractBeanValueHandler` |
| 中·续5 | PG 跨表同名索引静默吞 + 排除索引无提示 | `diffIndexes` 新增 `findIndexOwner`：CREATE 前查同名对象归属——无归属→建；属别表→WARN 冲突并跳过（不抢名不抛错）；属本表但是排除索引→WARN 不受管理。新增 `warnUnmanagedIndexes`：表达式/部分/约束支撑索引存在即 WARN 计数 | `PgsqlTableAlterHandler` |
| 中·续6 | timestamp↔timestamptz ALTER 无 USING | `typeUsingClause` 补时区族分支：两个方向都生成 `USING "col" AT TIME ZONE current_setting('TimeZone')`（对该类型对两侧均合法），存量值按会话时区解释而非静默重解释 | `PgsqlTableAlterHandler` |
| 中·续7 | 列级 collation 不回读 | `columnListSql` 关联 `pg_type`/`pg_collation`，回读 `attcollation <> typcollation` 时的非默认 collation 入 `ColumnInfo.collation`；比对侧只 WARN 不变更（实体注解表达不了排序规则） | `PgsqlDbMetaHandler` / `PgsqlTableAlterHandler` |
| 构建·续8 | 依赖卫生 | 父 POM 移除 `commons-lang3`/`commons-text`（全仓零引用，grep 核实）；`logback-classic/core` 降 test scope；`fastjson2` 下沉到 `.sql`（compile）、`protostuff` 下沉到 core（compile），父 POM 只在 dependencyManagement 留版本 | 三个 POM |
| 低·续9 | 布尔默认值同义词不全 | `BOOLEAN_DEFAULT_SYNONYMS` 收齐 PG 全部布尔字面量写法（`'t'/'f'/'yes'/'no'/'on'/'off'`），全部折到 `true`/`false` | `PgsqlTableAlterHandler` |
| 低·续10 | DROP INDEX schema 解析 | `buildDropIndex` 改 `LazySchema`：每次 diff 只解析一次 `current_schema()`，metaProvider 缺失/返回 null 时抛 `IllegalStateException`（删掉"降级为不限定 schema 的 DROP"分支——那一支可能删到别人的索引） | `PgsqlTableAlterHandler` |
| 低·续11 | 退化 SQL 无护栏 | 全 IDENTITY 列实体 INSERT 改发 `INSERT INTO "t" DEFAULT VALUES`；仅主键实体 UPDATE 空 SET 快速失败（`IllegalArgumentException`） | `PgsqlBeanToInsertHandler` / `PgsqlBeanToStatementHandler` / `MysqlBeanToUpdateHandler` |
| 低·续12 | 复合主键裸 CCE | `queryById` 在强转前校验：复合主键收到非 `Object[]` → 带列数与实际类型的 `IllegalArgumentException`（两方言同修） | `PgsqlDbToBeanHandler` / `MysqlDbToBeanHandler` |
| 低·续13 | 自动建库 42P04 误报 | `createDatabaseIfAbsent` 捕获 SQLState `42P04`（并发创建撞车）记 INFO 并返回，其余异常照旧抛出 | `PgsqlTemplate` |
| 低·续14 | 循环引用裸 SOE | `toJsonString`/`toBlobBytes` 增捕 `StackOverflowError`，包装为带列名与"对象图存在循环引用"提示的 `TypeMismatchDataAccessException`——SOE 是 Error 不被 `catch (Exception)` 接住，重试链因此失效 | `AbstractBeanValueHandler` |

**行为变更（使用方可见，已登记 README「已知边界」第 30-34 条）**

1. JSON 列里 `Date` 族落盘形态由本地时区字符串变为 epoch 毫秒（读侧双格式兼容，存量行免迁移）；
2. BLOB 列的两类非法声明从"静默放行"变为解析期异常（原本就是配置错误，无合法存量）；
3. `timestamptz` 短写实体首次同步后不再每次启动整表重写（修复前每次启动都在复制全表）。

---

## 零、修复实施记录（后续提交，与上文审核结论对应）

> 本节由修复实施阶段追加。审核结论保留原样，便于对照"当初判断 → 实际改动 → 验证结果"。

**实施范围**：第 2 节全部 7 条严重、第 3 节全部 12 条中等、第 4 节的文档漂移与轻微项（能一处改完的都改了）。

**验证方式**：把仓库镜像到临时目录构建（绕开本机 `target/` 的写入限制），并把嵌入式 MariaDB 的
baseDir/dataDir 通过系统属性重定向到可写临时目录，执行**全部测试**：

| 模块 | 用例数 | 说明 |
| --- | --- | --- |
| `com.wxdgaming.batis` | 58（新增 22） | 解析器 / 查询条件 / 缓存 / 统计 |
| `com.wxdgaming.batis.sql` | 43（新增 8） | 异常映射 / 分页 / SQL 渲染 / 片段闸门 |
| `com.wxdgaming.batis.sql.mysql` | 101（新增 3） | **真库集成测试**：嵌入式 MariaDB 10.11 |
| **合计** | **202，0 失败** | `BUILD SUCCESS` |

与本次改动直接相关且全部通过的集成用例：`SchemaDriftIT`（零 ALTER 幂等 + 真实漂移修复）、
`TransactionTemplateIT`（传播行为、提交后刷盘、回滚补偿）、`CachedBeanRunnerTest`（缓存/脏表/并发/新增 3 条回归）、
`PartitionManagerTest`（分区边界与维护）、`SelectQueryIT`（含注入闸门用例）、`SqlExceptionIT`。
新增回归用例共 33 个。

| 编号 | 问题 | 修复方式 | 主要落点 |
| --- | --- | --- | --- |
| S1 | `executeAndFlush` 刷盘失败静默丢数据 | `commit()` 只做 `beforeCommit + JDBC commit`；`afterCommit`/`afterCompletion` 统一挪到 `executeNew` 的 finally，且**在解绑 TxContext、归还连接之后**执行（新增 `finish()`）。回调异常改捕 `Throwable`，避免把已提交事务上报成已回滚 | `TransactionTemplate` |
| S2 | 读路径把未提交行写入缓存，回滚后成幽灵行 | loader 里对非 null 结果调用 `invalidateCacheOnRollback(key)`，与写路径同一套补偿 | `CachedBeanRunner#queryById` |
| S3 | `takeByPrefix` 并发丢更新 | 迭代器 `it.remove()` 改为原子 `map.remove(key, value)`，只摘走"读到的那个版本" | `CachedBeanRunner` |
| S4 | `deleteAll` 清库不清缓存 | 新增 `EntityCache#invalidateByPrefix`（默认实现显式抛 `UnsupportedOperationException`，本地实现用 `keySet().removeIf`），`deleteAll` 清库后按表前缀失效 | `EntityCache` / `LocalEntityCache` / `CachedBeanRunner` |
| S5 | 立即更新丢弃未刷盘的延迟插入 | 立即分支若发现 key 仍在 `dirtyInserts`，先补一次 INSERT 再做后续记账 | `CachedBeanRunner#updateBean` |
| S6 | `@Column(unique=true)` 被同列普通索引吞掉 | 判重改为只认**唯一**单列索引；同名索引若覆盖同列则就地升级为唯一，覆盖别的列则快速失败报错 | `EntityParser` |
| S7 | 立即插入把缓存写到临时 key | 新增 `realKeyAfterWrite` / `migrateCacheEntries`：主键回写后把缓存条目从 `#pending:N` 迁到 `table:pk`，并退休临时 key 映射 | `CachedBeanRunner` |
| S7' | `resetConfig()` 不清 `truncateName` | 重置时一并清空；`buildPartition`/`buildDropPartition`/`buildTruncatePartition`/`buildReorganizePartition` 无目标时返回 `null`，`executeXxx` 不再误动缓存 | `PartitionBuilder` |
| M1 | 批量部分失败无检测 | `QueryRunner` 新增 `checkExecuteFailures`/`firstExecuteFailure`/`translateBatchException`：检测 `EXECUTE_FAILED` 与短数组，非事务分支**先回滚再抛** `BatchUpdateFailedException`（带失败下标）；`BatchUpdateException.getUpdateCounts()` 不再丢弃；`BeanRunner` 的批量插入旁路复用同一套校验（`commit` 之前） | `QueryRunner` / `BeanRunner` |
| M2 | `FlushResult.affected` 名不副实 | `BatchResult` 新增 `affectedRows` 与 `recordSuccess(int)`，批量路径按驱动回传行数累加、逐条降级路径用 `updateBean` 的返回值；`CachedBeanRunner` 改用它填 `affected`，`sum(int[])` 负值按 0 计 | `BatchResult` / `BeanRunner` / `CachedBeanRunner` |
| M3 | 淘汰兜底是异步的 | `removalListener` → `evictionListener` **并加 `executor(Runnable::run)`**：维护任务（含淘汰回调）在调用线程上执行，`onEvict` 的"同步刷库"契约才真正成立；`handleEviction` 补登记回滚补偿、失败放回改用 `putIfAbsent` | `LocalEntityCache` / `CachedBeanRunner` |
| M4 | 漂移检测缺口（字符集 / EXTRA / 表注释） | 元数据查询补 `CHARACTER_SET_NAME`/`COLLATION_NAME`（`ColumnInfo` 新增三个字段）；自增属性纳入比较（实体要 IDENTITY 而库端没有 → MODIFY，反方向只 warn）；表级注释参与 diff（实体声明了才会改） | `MysqlDbMetaHandler` / `MysqlTableAlterHandler` / `ColumnInfo` |
| M5 | 索引被无提示删除 | 删除前逐条 `warn` 出索引名；`JdbcTemplate#setDropRedundantIndexes(false)` 可改为只告警不删除 | `MysqlTableAlterHandler` / `JdbcTemplate` |
| M6 | DDL 字面量不转义 | 新增 `MysqlSqlLiteral`（`DEFAULT` 白名单 + 字符串转义、`COMMENT` 统一转义），CREATE 与 ALTER 两条路径共用；删掉两份各自漏转义的 `escapeComment` | `MysqlSqlLiteral`（新）/ 两个 DDL handler |
| M7 | `checkTable` 全量重载退化 O(N²) | 抽出 `readTable`，新增 `refreshTableCache` 单表增量刷新（3 条查询）；`loadAllTables`/`checkTable` 以 `tableLock` 互斥 | `JdbcTemplate` |
| M8 | 每 key 一个同步器 + `cacheKeysWritten` 只写不读 | `ConnectionHolder` 加 `markCacheInvalidationRegistered()`，每个事务只注册**一个**同步器，完成时遍历事务写集统一失效 | `ConnectionHolder` / `CachedBeanRunner` |
| M9 | 同表多实体共用缓存 key | 命中值加实体类型校验：类型不符按未命中重载（并 warn），加载后仍不符则直接回源；key 格式保持不变并在 `CacheKeyBuilder`/README 写明该边界 | `CachedBeanRunner` / `CacheKeyBuilder` |
| M10 | 遗留 `whereSection` 黑名单可绕过 | 新增 `guardWhereSection(whereSection, params)`：校验"占位符数量 == 参数数量"、拒绝"无参却含字符串字面量"（`' OR '1'='1` 正落在这一条）；`BeanRunner` 两个入口改调新重载 | `SqlFragments` / `BeanRunner` |
| M11 | 值数与占位符数不匹配会静默错位 | 渲染端新增 `requireValueCount`，`IN/NOT_IN` 空列表直接报错；`Criterion.of`/`CriteriaBuilder` 对 null 值给出"哪一列哪个操作符"的可读异常 | `SelectSqlBuilder` / `Criterion` / `CriteriaBuilder` |
| M12 | 超时类 SQLState 未映射 | SQLState `57`/`HYT` 归 `QueryTimeoutException`，关键字补 `statement timeout`/`canceling statement`/`timeout expired`（仍排在锁等待之后） | `DefaultSqlExceptionHandler` |
| 文档漂移 1-5 | 与实现不符的注释 | 逐条改正：脏表是**每实例一份**（`JdbcTemplate#cachedBeanRunner`、`executeAndFlush`）、`DbTemplate#stats` 每实例一份、删掉不存在的 `@Version` 引用、`PartitionBuilder#table(Class)` 不再声称会建表、`default` 分支注释与 README 第 709 行的自述对齐；README 事务回调时序、缓存章节、已知边界列表同步重写 | `JdbcTemplate` / `TransactionTemplate` / `DbTemplate` / `QueryRunner` / `PartitionBuilder` / `README.md` |
| 轻微项 | 一批 | `executeBatch(null/empty)` 短路；`sum(int[])` 非负；生成键回写做范围校验（越界抛错而非静默溢出）；`count()` 非 Number 抛 `TypeMismatchDataAccessException`；`OFFSET` 无 `LIMIT` 时补 `LIMIT 18446744073709551615`；`Criteria.and/or(null)` 快速失败；`raw` 允许绑定 null；`SelectQuery#toParams` 容忍 null；`ProtostuffUtil.decode` 三个重载判空；`DbStats` 最小值哨兵改为独立标志；`CacheKeyBuilder` 用 `Locale.ROOT`；`MysqlTemplate` 库名白名单 + 兼容 `jdbc:mariadb://`；MySQL 客户端错误码 2002/2003/2006/2013 映射；`MysqlBeanValueHandler` 的 `Character` 空串与兜底分支改为带列名的显式异常 | 多处 |

**对原审核结论的两处修正（实施阶段发现）**

1. **M11 的实际可达性低于原判断**：`Criteria.of(Criterion)` 是包内可见，公开的 `CriteriaBuilder` 各方法产出的值数天然与占位符一致，因此外部代码目前**无法**构造出数量不符的叶子。渲染端校验属于纵深防御（防未来新增工厂/反射路径），已实现但不应按"可被外部触发的注入"看待。
2. **E 组报告的"断连高危"维持不予采纳**（原报告已说明）：SQLState `08` 类已覆盖常见情形；本次仅补齐客户端错误码 2002/2003/2006/2013 作为兜底。

**未实施（保留为后续项，均已在 README「已知边界」登记）**

- `isolation` 归还连接时不恢复（文档化的连接池级副作用，改动会影响既有池行为）；
- `EntityParser` 静态缓存改用弱引用/`ClassValue`；`TableInfo`/`ColumnInfo` 的不可变化（`@Setter` 仍公开）；
- `EntityCache` 的 key 加入实体类维度（当前用类型校验兜住 `ClassCastException`，但同表两模型仍共用条目）；
- `PartitionCache` 元数据读取失败固化为"空分区集"；分区边界与库端时区的口径统一；
- 依赖卫生（父 POM 把 `fastjson`/`logback`/`commons-*`/`protostuff` 作为 compile 依赖下发）；
- 测试基建（`MariaDB4jExtension` 固定 3307 端口 + 持久化 `~/.wxd-batis` 数据目录，本轮验证靠系统属性重定向到临时目录绕过）；
- 异常类的 `serialVersionUID`；`tinyint unsigned` 规格化对称性（仍待真库复核）。

---

## 一、结论速览

整体质量明显高于同类轻量 ORM：分层（core / sql / 方言 SPI）干净、方言处理器用 setter 注入实现零硬编码、连接来源三态与归还语义严谨、异常翻译是纯函数且按可重试性分主干、注释密度与「为什么这么做」的记录罕见地充分，README 甚至主动登记了 24 条已知边界。

但存在一个**共同模式**：写路径的三重护栏（事务内延迟写升级、回滚按 key 精确失效、刷盘前登记补偿）做得很细，而这些护栏**没有覆盖读路径、批量结果校验、淘汰兜底与 DDL 变化检测**，于是缺陷集中在「静默」类——不抛异常、不打日志、也不报错。

| 级别 | 数量 | 典型后果 |
| --- | --- | --- |
| 严重 | 7 | 数据已提交却不落库 / 幽灵行 / 丢更新 / 清表后仍读到旧行 / 唯一约束不生成 / TRUNCATE 错表 |
| 中等 | 12 | 批量失败无检测、DDL 变化漏判、启动期 O(N²)、O(N²) 同步器、注入面收窄但存在 |
| 轻微 / 文档漂移 | 15 | 统计口径、命名、契约与实现不一致 |

**最该先修的 6 条**：S1（afterCommit 刷盘失败丢写）、S2（读路径幽灵行）、S3（takeByPrefix 丢更新）、S4（deleteAll 不清缓存）、S5（updateBean 丢弃未刷盘插入）、S6（唯一约束被吞）。

---

## 二、严重问题

### S1 推荐姿势 `executeAndFlush` 在刷盘失败时静默丢数据
- 位置：`com.wxdgaming.batis.sql/src/main/java/com/wxdgaming/batis/sql/tx/TransactionTemplate.java:84-98`、`:293-300`；`.../sql/CachedBeanRunner.java:460-466`、`:922-951`
- 证据：`afterCommit` 回调在 `commit()` 内执行（`:293-300`），而 `TxContext.unbind` 在更晚的 `finally`（`:205`）；此时 `CachedBeanRunner.txActive()` 仍为 true，于是失败放回逻辑直接早退：
  ```java
  private void requeueFailed(...) { if (txActive()) return; ... }      // CachedBeanRunner.java:924
  private void requeueDeleted(...) { if (txActive()) return; ... }     // :941
  if (status == TransactionSynchronization.STATUS_COMMITTED) return;   // :863（补偿也提前返回）
  ```
- 影响：`doFlush` 已用 `takeByPrefix` 把脏数据**移出**脏表（`:461-463`）。刷盘抛错时：脏表已空、补偿因 COMMITTED 跳过、异常被分组内 `catch` 吞成一条写着「放回脏表」的 warn（实际没放回）；数据库永远拿不到这批写，调用方却认为事务成功。这是 Write-Behind 与事务边界规则③交叉处唯一的真缺口。
- 修复：把 `afterCommit` 移到解绑之后执行（或在 `ConnectionHolder` 上加 `completed` 标记，让 `txActive()` 在完成后为 false）；`requeueFailed/requeueDeleted` 不应以线程事务状态为唯一判据。

### S2 事务内读路径把未提交行写进全局缓存，回滚后成为幽灵行
- 位置：`.../sql/CachedBeanRunner.java:204-217`（loader）对比 `:824-836`（写路径失效）
- 证据：loader 复用的是**线程事务连接**，读到的未提交行被无条件写入共享缓存，且从不登记任何失效：
  ```java
  Object result = cache.getOrLoad(key, k -> {
      try (BeanRunner runner = jdbcTemplate.beanRunner()) { return runner.queryById(entityClass, id); }
  });
  ```
- 影响：`inTransaction` 里先写后读同一实体，事务回滚后 `queryById` 仍从缓存返回该行（DB 无此行），直到 TTL（默认 30 分钟）才自愈。写路径专门有 `invalidateCacheOnRollback`，读路径没有对应物；而 S7 让「事务内立即插入」的真实 key 为空，必然走到这条 loader，进一步放大。
- 修复：loader 内判断 `TxContext.isActive(jdbcTemplate)`，命中即登记 `afterCompletion` 非提交状态失效；或事务内读不回填缓存。

### S3 `takeByPrefix` 先取值后 `it.remove()`，会连带删掉并发写入的新值
- 位置：`.../sql/CachedBeanRunner.java:773-784`
- 证据：
  ```java
  taken.put(entry.getKey(), entry.getValue());
  it.remove();                       // ConcurrentHashMap 迭代器 remove 按 key 无条件删除
  ```
  该处 Javadoc（`:767-770`）恰好声称迭代器方案避免了「先 collect 再 removeAll」的漏刷，实际换成了更隐蔽的丢写：`remove()` 删掉的是**当前**映射（可能是并发线程刚替换进去的新版本），而 `taken` 里留的是旧版本。
- 影响：刷盘线程读到 `K→v1` 后、`it.remove()` 前，业务线程 `updateBean(v2,false)` 把 `dirtyUpdates[K]=v2`（返回 -1 表示已受理）；随后 v2 被删掉——DB 落 v1、脏表无 v2、缓存是 v2，这次更新永久丢失，且 `dirtyTotalCount()` 反而归零。这是已受理却丢失的更新，属于最严重的一类。
- 修复：`Object v = entry.getValue(); if (map.remove(entry.getKey(), v)) taken.put(entry.getKey(), v);`（`remove(k,v)` 原子且不会误删被替换的新值）。

### S4 `deleteAll` 只清库不清缓存，清表后 `queryById` 仍返回旧行
- 位置：`.../sql/CachedBeanRunner.java:645-654`
- 证据：方法体只有一次 DB 调用，Javadoc 只提醒「不清脏表」，对读缓存只字未提；对比 `EntityCache` 也缺少按前缀失效的能力。
- 影响：`insertBean(o,true); queryById(o.getId()); deleteAll(OrderEntity.class);` 之后 `queryById` 从缓存返回已清掉的行，而 `queryAll`（透传 DB）返回 0 条——同一对象的两个读接口互相矛盾。清档、回档、赛季重置场景直接脏读。
- 修复：`deleteAll` 内按 `keyPrefix` 失效缓存（补 `EntityCache#invalidateByPrefix`），或至少在 Javadoc 中同时声明缓存不失效并给出「先 clearAll」的用法。

### S5 `updateBean` 的立即分支会丢弃尚未刷盘的延迟插入
- 位置：`.../sql/CachedBeanRunner.java:285-301`
- 证据：
  ```java
  int rows = runner.updateBean(bean);   // 该行还没落库 → WHERE id=NULL/0 → 0 行
  ...
  dirtyUpdates.remove(key);
  dirtyInserts.remove(key);             // 注释「已经落库」的前提不成立
  ```
- 影响：非事务场景下 `insertBean(o,false)`（延迟插入，`dirtyInsertCount()==1`）后调用 `updateBean(o,true)`，脏插入被移除、DB 从未插入过该行、缓存留着新值——数据彻底消失且无补偿。同样路径会经由 `upgradeDeferredInTx()` 在**事务内**被触发（事务内默认 `TX_IMMEDIATE`），即「先用延迟写缓冲、随后在事务里更新同一对象」这种很自然的用法。
- 修复：立即更新前先判断该 key 是否仍在 `dirtyInserts`（或 `tryRealKey(bean)==null`），是则改走插入；`dirtyInserts.remove` 只在确有落库证据时执行。

### S6 `@Column(unique=true)` 会被同列任意单列索引静默吞掉
- 位置：`com.wxdgaming.batis/src/main/java/com/wxdgaming/batis/parser/EntityParser.java:381-396`
- 证据：
  ```java
  boolean alreadyExists = indexes.stream().anyMatch(idx ->
          idx.getColumns().size() == 1
                  && idx.getColumns().get(0).equals(col.getColumnName()));
  ```
  判重只看「同列是否已有单列索引」，**没有校验 `idx.isUnique()`**。
- 影响：字段同时写 `@Column(unique = true)` 和任意 `@Index`（哪怕是 `unique = false` 的普通查询索引）时，`uk_<col>` 唯一索引不再生成，建表只剩普通索引——数据库层不再拦重复值，且结构比对也看不到这个缺失的唯一键（比对的是库端与实体渲染结果，两边一致）。
- 修复：判重条件改为只认唯一单列索引；同列已有非唯一索引时仍应生成 `uk_` 索引（两者可共存），并把该组合写进 `Column.unique()` 的文档。

### S7 `PartitionBuilder.resetConfig()` 不清 `truncateName`，复用 builder 可能 TRUNCATE 到别的表
- 位置：`.../sql/partition/PartitionBuilder.java:196-204`；`.../partition/PartitionConfig.java:50`
- 证据：`resetConfig()` 清了 `partitions / dropNames / reorganize / count / type / expression / skipped`，唯独没有 `setTruncateName(null)`，且全仓没有任何清空点。
- 影响：`table(A).truncatePartition("p20260801")` 之后再 `table(B).executeTruncatePartition()`，B 表会收到用 A 的分区名渲染出的 `ALTER TABLE B TRUNCATE PARTITION p20260801`（各表分区名同构，极易同名）——**清空另一张表的数据且不报错**，不可恢复。项目自己的测试就是「复用同一 builder 换表」的姿势。
- 修复：`resetConfig()` 补 `setTruncateName(null)`；执行前对 null/空名快速失败。

---

## 三、中等问题

### M1 批量"部分失败"没有任何检测点
- 位置：`.../sql/QueryRunner.java:349-403`；`.../sql/BeanRunner.java:214-217`、`:396-400`、`:343-356`
- 证据：`executeBatch` 拿到 `int[] result` 后不看内容（`:381-385`）；`BatchUpdateException.getUpdateCounts()` 在 `:393/:401` 被当普通 `SQLException` 翻译掉；调用方把返回值整个丢弃，无条件记账成功：
  ```java
  doInsertBatch(beans);                       // 返回值被丢弃
  for (int i = 0; i < beans.size(); i++) { result.recordSuccess(); }
  ```
  框架自带的 `BatchUpdateFailedException`（含 `failedIndex`）**全仓从未被构造**。
- 影响：驱动若用 `Statement.EXECUTE_FAILED(-3)` 表达"某条失败但不抛异常"（`continueBatchOnError` 语义），会得到"部分提交 + 全部记为成功"；更严重的是 `writeGeneratedKeys` 按行号 zip 回写主键（`:343-356`），失败行不产生生成键时**主键与实体整体错位**，把 A 的 ID 写进 B。另外 `BatchResult.getSuccessCount()` 只表示"没抛异常"，这直接导致 M2 的口径问题。
- 修复：扫描返回值，遇 `EXECUTE_FAILED` 或长度不符即回滚并抛携带 `updateCounts` 的 `BatchUpdateFailedException`；把 `int[]` 交给 `BeanRunner` 按元素记账。

### M2 `FlushResult` 的 `affected` 与自身 Javadoc 口径不符
- 位置：`.../sql/FlushResult.java:8-11`、`:26-31`；`.../sql/CachedBeanRunner.java:481-506`；`.../sql/BeanRunner.java:396-400`
- 证据：`updateAffected += objectBatchResult.getSuccessCount();`，而成功只代表"没抛异常"——`doUpdateBatchSafe` 调 `doUpdateBatchInternal(beans)` 时把返回的 `int[]` 直接丢掉。
- 影响：`FlushResult` 声称 `Affected` 是"真正落到数据库的行数"，并声称"更新一条库里不存在的行会得到 0 行生效却不报错"可被这个差额发现——实现上该信号对 INSERT/UPDATE **永不触发**（DELETE 侧 `:520` 却按真实行数累加）。同一对象三个数字三种口径，README 第 678 行也复述了同一错误承诺。全仓测试从未断言这些字段。
- 修复：UPDATE/INSERT 走能回传行数的路径记 affected，或修正 `FlushResult` 与 README 的语义声明。

### M3 缓存淘汰兜底是异步的，且该路径没有回滚补偿
- 位置：`com.wxdgaming.batis/src/main/java/com/wxdgaming/batis/cache/LocalEntityCache.java:49-63`；`.../sql/CachedBeanRunner.java:558-601`
- 证据：用的是 Caffeine `.removalListener(...)`（默认在 `ForkJoinPool.commonPool()` 上**异步**执行；同步语义需要 `.evictionListener(...)` 或显式 executor），而类注释把"条目被淘汰时一定会尝试刷盘"当作"不丢数据的最后一道防线"；`handleEviction` 也是全类唯一没有 `registerTxFlushCompensation` 的落库路径。
- 影响：通知排队期间进程退出即永久丢数据；自定义缓存实现（接口鼓励换实现）改成同步回调时，`handleEviction` 的写入会跑在调用线程的事务连接上却无补偿，回滚后"脏表已 remove + 缓存已淘汰 + DB 已回滚"三头皆空。
- 修复：改用同步淘汰回调，或让 `handleEviction` 与 `doFlush` 共用一套补偿/放回协议，并明确"停机前必须 `flushAll()`"。

### M4 漂移检测的四处缺口
- 位置：`.../sql/mysql/MysqlDbMetaHandler.java:70`；`.../sql/mysql/MysqlTableAlterHandler.java:41-113`、`:162-167`
- 证据与影响：
  1. **字符集/排序规则不参与比对**：元数据 SQL 只取 `COLUMN_TYPE`，`hasColumnChanged` 只比类型/可空/默认值/注释。库端列是 `latin1`（历史库默认或 DBA 手工 `CONVERT TO`）、实体是 utf8mb4 时，两边 `COLUMN_TYPE` 都是 `varchar(64)` → 判定一致、永不发 MODIFY，随后 emoji 在严格模式报 1366、非严格模式被截断成 `?`，而框架每次启动都坚称结构一致。
  2. **`EXTRA` 查了不用**：SQL 选出了 `EXTRA`，`columnListHandler` 从不读它，`ColumnInfo` 也没有对应字段——`auto_increment` / `DEFAULT_GENERATED` 无法进入比较，`IDENTITY` 策略与库端不符时永远检测不到。
  3. **表注释漂移不可修复**：库端注释被读取，但 `doAction` 只产出 ADD/MODIFY COLUMN 与 ADD/DROP INDEX，没有表级 `COMMENT=` 子句。
  4. **索引只比增删，不比定义**：[代理核查] 同名列的类型/顺序差异不在比较键内。
- 修复：元数据查询补 `CHARACTER_SET_NAME, COLLATION_NAME` 与 `EXTRA` 的消费；表注释纳入 diff；给出"实体与库端比较键"的完整清单文档。

### M5 实体未声明的索引会被无提示删除
- 位置：`.../sql/mysql/MysqlTableAlterHandler.java:96-99`
- 证据：`for (IndexInfo dropIdx : dbIndexMap.values()) { alterClauses.add("DROP INDEX \`" + dropIdx.getName() + "\`"); }`
- 影响：接口契约确实包含 DROP INDEX，但存量表首次接入框架时，DBA 手工建的索引（含全文索引、覆盖索引）会在一次 `checkTable` 中被静默删除，无日志、无开关，性能退化难以归因。
- 修复：加显式开关（默认关闭或首次仅 warn），至少在删除前 `log.warn` 列出索引名。

### M6 DDL 拼接：`DEFAULT` 原样入 SQL，注释转义漏反斜杠与回车
- 位置：`.../sql/mysql/MysqlTableCreateHandler.java:121-122`、`:141-142`；`.../sql/mysql/MysqlTableAlterHandler.java:128-129`、`:212-213`（同源重复实现）
- 证据：
  ```java
  sb.append(" DEFAULT ").append(col.getDefaultValue());
  return comment.replace("'", "\\'").replace("\n", "\\n");
  ```
- 影响：默认值按"SQL 字面量"设计可以理解，但含引号/分号的写法会直接破坏 DDL（1121/1064），且失败发生在启动期 DDL 路径上；注释里的反斜杠（Windows 路径、`\n` 字面量）会被 MySQL 当转义引导符消费，导致 DDL 解析失败或注释被静默改写，`\r` 同样漏转义。CREATE 与 ALTER 两份拷贝各自维护，是典型的"改一处漏一处"结构。
- 修复：抽单一 `SqlLiteral.quote(...)`（转义 `\ ' \n \r \0 \Z` 或改双写 `''`），两条路径共用。

### M7 `checkTable` 每次全量重载表结构，启动期退化为 O(N²)
- 位置：`.../sql/JdbcTemplate.java:325-344`（加载实现 `:130-173`）
- 证据：建表/改表分支结束后无条件 `loadAllTables()`；而 `loadAllTables` 是"1 条表列表 + 每表 2 条（列/索引）"的全量加载。
- 影响：N 个实体各调一次 `checkTable` 即 N×(1+2N) 条元数据查询（N=50 约 5000 条）；实体全部已存在时也走 ALTER 分支触发重载。另外 `checkTable` 无并发保护，多线程并发首次调用会重复加载（建表有 `IF NOT EXISTS` 兜底，代价是多余 DDL 与锁）。
- 修复：单表增量刷新 + per-template 锁；全量加载收敛到启动期一次。

### M8 事务内每个写入 key 注册一个同步器，配合 `CopyOnWriteArrayList` 构成 O(N²)
- 位置：`.../sql/CachedBeanRunner.java:824-836`；`.../sql/tx/TxContext.java:84-86`；`.../sql/tx/ConnectionHolder.java:38-40`
- 证据：`invalidateCacheOnRollback(key)` 对**每一个 key** 都 `TxContext.register(holder, new TransactionSynchronization(){...})`，而 `synchronizations` 是 `CopyOnWriteArrayList`（每次 `add` 整体复制数组）。
- 影响：事务内写 N 条即 N 个回调对象 + N 次数组复制（O(N²)），提交/回滚再各遍历 N 次；同时 `ConnectionHolder.cacheKeysWritten` **只写不读**（唯一写入点 `:827`，全仓无读取点），注释声称它"供回滚时精确失效缓存"，实际实现是每 key 一个监听器——既是有意设计的半成品，也是纯内存增长。
- 修复：合并为单个同步器，完成时遍历 `cacheKeysWritten` 统一失效（顺带让这个集合真正被用上）；注册容器换成普通 `ArrayList` 或 `ConcurrentLinkedQueue`。

### M9 缓存 key 只按表名隔离：同表多实体类会互相污染
- 位置：`com.wxdgaming.batis/src/main/java/com/wxdgaming/batis/cache/CacheKeyBuilder.java:27`；`.../sql/CachedBeanRunner.java:189-216`
- 证据：`ti.getTableName().toLowerCase() + ":" + idToString(id)`——key 里没有实体类维度；命中路径 `return (T) cached;` 是无检查强转。
- 影响：读写模型分离等"两个实体映射同一张表"的常见做法下，`queryById(A.class,1)` 之后 `queryById(B.class,1)` 会拿到 A 的实例并在调用点抛 `ClassCastException`；`flush(Class)` 的前缀过滤也会把两张表当成一张。
- 修复：key 前缀加入实体类维度，命中后加 `entityClass.isInstance` 兜底。

### M10 遗留 `whereSection` 的黑名单闸门可被最经典的注入绕过
- 位置：`.../sql/query/SqlFragments.java:30-33`、`:49-59`（调用点 `BeanRunner.java:537`、`:564`）
- 证据：`;|--|/\*|#|\bunion\b|\bdrop\b|\bdelete\b|\binsert\b|\bupdate\b|...`
- 影响：`queryList(User.class, "name = '" + req.getParameter("name") + "'")` 传 `' OR '1'='1`（不含任何被禁 token）直接放行 → 条件绕过/全表读取；`OR SLEEP(5)` 同理。该 API 自己声明"拦误用不拦攻击者"，但现实效果是给了"已有防护"的错觉。
- 修复：过渡期强制"必须含 `?` 且 `params.length` 与占位符数一致"，否则拒绝；更稳妥是直接删除这个已 `@Deprecated(forRemoval=true)` 的入口。

### M11 查询 DSL：值数与占位符数不匹配时静默错位参数
- 位置：`.../sql/query/SelectSqlBuilder.java:168-202`
- 证据：`default -> { params.addAll(c.values()); yield qc + " " + c.op().getSql() + " ?"; }`——只加 1 个占位符却把 values 全加进参数；`BETWEEN` 分支同理固定 2 个占位符。
- 影响：`Criterion` 是公开 record、`Criterion.of(...)` 是公开工厂，手工构造出 `EQ` + 两个值即会渲染 `a = ?` 却绑定 2 个参数，**后续所有条件参数整体错位一格**：SQL 正常执行、结果静默错误。`Criterion.placeholderCount()` 的 Javadoc 明确写"渲染端据此校验"，但生产代码从未调用它（只有测试用）。
- 修复：渲染每个叶子前断言 `placeholderCount()` 与该分支占位符数一致，不一致抛 `IllegalArgumentException`。

### M12 超时类 SQLState 未映射，超时会被当成"不可重试"并放大成 N 次重试
- 位置：`.../sql/DefaultSqlExceptionHandler.java:64-121`；`.../sql/BeanRunner.java` 的 `rethrowWithoutFallback`
- 证据：SQLState 分支只覆盖 `08/23/45/22/40/42`，关键字兜底为 `query timeout` / `max_execution_time`；而语句超时在标准里属 `57` 类（如 57014）或 `HYT00/HYT01`，消息常为 *canceling statement due to statement timeout* / *statement timeout*。
- 影响：落到 `UncategorizedDataAccessException`（`NonTransient`，`isRetryable()==false`）；`rethrowWithoutFallback` 正是用 `isRetryable()` 决定"上抛还是逐条降级"，于是本应上抛的瞬时超时被降级成 N 次逐条执行，把一次超时放大成 N 次压库。
- 修复：补 `57`/`HYT` 类到 `QueryTimeoutException`（Transient），关键字补 `statement timeout` / `timeout`，保持"未知即不重试"。

> 修正说明：代理报告曾把「MySQL 2006/2013 断连未映射」列为高危，经复核**不予采纳为高危**——`DefaultSqlExceptionHandler:68-72` 对 SQLState `08` 类已有处理，`08S01` → `RecoverableDataAccessException`，其余 `08xx` → `CannotGetJdbcConnectionException`（经核实继承 `TransientDataAccessException`，可重试），而 Connector/J 的通断异常通常报 `08S01`。仅当驱动以 `HY000` 上报且消息不含 `connection closed` 时才会落到不可重试的兜底，故降级为「轻微」。

---

## 四、轻微问题与文档漂移

**文档与实现不一致（"注释承诺 > 实现"是最容易误导使用方的一类）**

1. `JdbcTemplate.java:273-282` 与 `TransactionTemplate.java:77-84` 都声称「Write-Behind 的脏表是**进程全局**的，多个 `CachedBeanRunner` 共享同一份待刷数据」，实际 `dirtyInserts/Updates/Deletes` 是**实例字段**（`CachedBeanRunner.java:127-131`），且 `cachedBeanRunner()` 每次 `new` 一个新缓存。按注释理解会漏刷另一个实例的脏数据，多实例即"缓存分脑"。
2. `DbTemplate.java:21-25` 声称统计「所有实例共享同一份累计指标」，实际 `private final DbStats stats = new DbStats()` 是每实例一份。
3. `QueryRunner.java:255-258` 引用「DSL 层的乐观锁（`@Version`）」——**该功能在整个仓库不存在**（无 `@Version` 注解，无版本列处理）。
4. `PartitionBuilder.java:171` 的 `table(Class)` 声称"顺带保证表已存在"，实现（`:177-181`）只做 `EntityParser.parse` + 设表名，不会 `checkTable`，按文档使用会在 DDL 阶段拿到 1146。
5. `TransactionTemplate.java:155-157` 的 `default` 分支注释"NESTED 等未实现档位落到这里"，但 `Propagation` 枚举里根本没有 `NESTED`，该分支不可达；且与 README 第 709 行"switch 没有 default，新增取值会被编译期强制表态"的自述相矛盾（`CachedBeanRunner.upgradeDeferredInTx` 确实没写 default，`TransactionTemplate` 写了）。

**健壮性 / 一致性小项**

6. `QueryRunner.java:353`/`:361`：`executeBatch(null, ...)` 抛裸 NPE（debug 开关还会改变抛出行）——未经异常翻译，上层的降级/重试补偿全部失效；空列表本身安全。
7. `QueryRunner.java:441-443`/`:471-474`：DEBUG 日志只脱敏 `char[]`，`String` 形态的密码/token 原文落盘，`byte[]` 全量转字符串；`DataAccessException` 还携带 `params`，上层打 error 日志会再复制一份。文档建议"不要生产常开 DEBUG"应写进 README 已知边界。
8. `.../sql/BeanToSelectHandler.java:107-113`：`LIMIT` 与 `OFFSET` 相互独立追加，`select(X.class).offset(20).list()` 会生成 `OFFSET ?`（MySQL 要求 OFFSET 必须带 LIMIT）→ 运行期语法错误；建议补 `LIMIT 18446744073709551615` 或渲染期直接拒绝。
9. `CachedBeanRunner.java:954-956` 的 `sum(int[])` 未做负值归一，`SUCCESS_NO_INFO(-2)` 会让 `deleteAffected` 变成负数（`QueryRunner.java:500-504` 的 `sumRows` 已经用 `Math.max(r,0)` 处理过同一问题，两处口径不一致）。
10. `AbstractBeanValueHandler.java:80-89`：生成键回写用 `intValue()/shortValue()` 静默收窄，自增 ID 超 `int` 范围时写回负数主键。
11. `.../sql/query/SelectQuery.java:180-192`：`count()` 拿到非 `Number` 静默返回 0，`exists()` 与分页 `total` 随之出错，无日志无异常。
12. `DbStats.java:213-219`：用 0 当"未初始化"哨兵——记录一次 0ms 后，下一次更大的耗时会 CAS 覆盖，`getMinMs()` 偏高（注释声称的防护方向恰好相反）；`lastMs`/`lastRecords` 两个 volatile 分写，读取端可能看到撕裂组合。
13. `CacheKeyBuilder.java:27`/`:36`/`:45`：`toLowerCase()` 未指定 `Locale.ROOT`，与同项目 `SelectSqlBuilder.resolveColumn`、`MysqlColumnTypeDefHandler.normalize` 的写法不一致；tr/az 默认语言下同一张表会算出不同 key 前缀。
14. `EntityParser.java:174`：`TableInfo.columns` 用**原始大小写**列名做 key，而 `SelectSqlBuilder.resolveColumn`（`:120-121`）先按小写查、兜底只比 `fieldName`/下划线形式。于是 `@Column(name = "PlayerName")` 这类显式大写列名在 DDL/增删改路径正常（那些路径会 lower），但在 where/排序/投影里三种写法全部抛 `UnknownColumnException`。
15. `EntityParser.java:424-431`：`(20,6)` 精度契约只在 `storageMode == AUTO` 时套用；显式写 `@Column(storageMode = StorageMode.DECIMAL)` 且不写 `length` 时，注解默认 `length=255`（`Column.java:62`）会一路传到方言渲染成 `DECIMAL(255,0)` → MySQL 报 1426（精度上限 65），而 `StorageMode` 与 `Column` 的文档恰恰推荐显式指定非 AUTO 档位。
16. `Criteria.java:106-121`：`and(null)` / `or(null)` 静默丢弃该条件并返回自身——若本应来自权限/租户过滤而因上游 bug 为 null，查询会被无声放宽。建议二元形式 `requireNonNull` 快速失败，只保留 varargs 形式的 null 过滤。
17. `CriteriaBuilder.java:115-117` 与 `Criteria.java:96-98`：除 `eq/ne` 外的操作符传 null（`gt(null)`、`between(lo,null)`、`raw("... = ?", (Object) null)`）抛**空消息 NPE**，与 `in(空集合)`/`like(null)` 的明确 `IllegalArgumentException` 不一致，也与 `eq/ne` 的 null 收敛语义不一致。
18. `EntityParser.java:76-96` 的静态缓存持有 `Class/Field/Method` 强引用，且 `clearCache/clearAll` 只在测试里被调用（README 第 20 条已登记，此处仅作提醒：热更/多 ClassLoader 会整包泄漏）。`TableInfo`/`ColumnInfo` 全可变且 `getColumns()` 直接交出内部 map，而 `EntityParser` 把同一实例共享给所有线程（README 未提，属新增风险点）。
19. `CacheKeyBuilder.java:57-62`：`Method.invoke` 未做 `setAccessible`，非 public 实体会以"提取主键失败"这类误导消息暴露（真实原因是类不可访问）。
20. 异常包 20 个类均无 `serialVersionUID`，基类还带 `final` 诊断字段——跨版本 RPC 反序列化失败会发生在错误处理路径上。
21. `ProtostuffUtil.decode` 三个重载都不判空，与 `encode` 的 `IllegalArgumentException` 契约不对称，BLOB 反序列化失败时无法区分"列值为 null"与"实体缺无参构造"。
22. 依赖卫生：父 POM 把 `fastjson 1.2.83`、`logback-classic/core`、`commons-lang3/text`、`protostuff-*` 作为 **compile** 依赖继承给所有模块，使用方会被迫传递引入一个日志绑定与 fastjson（日志绑定冲突是经典事故）；MySQL 模块还把 `mysql-connector-j` 作为 compile 依赖。建议 `optional`/`provided`/按模块收敛。
23. 测试基建：`MariaDB4jExtension` 用固定端口 3307 + 持久化 `${user.home}/.wxd-batis/maria4j-data`，且靠反射调用 MariaDB4j 私有方法（`prepareDirectories/unpackEmbeddedDb/install`）——测试通过条件依赖上一轮残留状态（`QueryRunnerTest.java:29-30` 自己承认"断言写成可容忍两种起点"），并行跑测试类会端口冲突，库升级会因私有方法签名变化而失败。建议随机空闲端口 + 临时 dataDir + 显式 `DROP TABLE IF EXISTS`。
24. 断言强度：`SqlExceptionIT.java:140-148` 的"1048 或 1364 皆可"使两个独立分支只要一个正确就恒真；`DbStatsPerformanceTest` 线程内异常只 `log.error`、`awaitTermination` 返回值不断言，Phase 3 的 5 万次更新可以全部失败而用例仍绿；`PartitionManagerTest.java:463-465` 的周分区只断言"6 位数字"，而代码注释自承"12 月末/1 月初边界周在两种口径下可能差 1 周"。
25. 覆盖盲区（`MysqlTableAlterHandler` 的 `DROP INDEX` 是模块内破坏性最强的路径却无用例）：`MysqlBeanValueHandler` 的 UUID/枚举/JSON/BLOB 等映射、`MysqlBeanToUpdateHandler` 仅主键列时的空 `SET`、`MysqlBeanToInsertHandler` 全 IDENTITY 时的 `INSERT INTO t () VALUES ()`、复合主键 `queryById` 的参数校验，均无回归护栏。

---

## 五、需要进一步复核的条目（本次未能确证，不计入结论）

1. `MysqlColumnTypeDefHandler.java:156-176` 的整数档规格化：`params == null` 早退分支与"剥离显示宽度"分支是否对 `tinyint(3) unsigned` 这类库端形态产出不同比较键（若成立，会导致每次启动对未变化的列发 MODIFY）。现有单测输入只覆盖 `int(11) unsigned` / `bigint(20) unsigned`，恰好绕过该档。需用 MariaDB 实测 `COLUMN_TYPE` 回读值确认。
2. 批量失败究竟以 `BatchUpdateException` 抛出还是以 `EXECUTE_FAILED` 返回，取决于驱动与 `continueBatchOnError` 配置——但**"框架从不校验批量返回值、也从不构造 `BatchUpdateFailedException`"与驱动无关**，M1 的修复价值不依赖该确认。
3. `logback-test.xml` 引用的 `ch.qos.logback.core.LogbackResetTimeFilter` 在本仓库不存在（README 第 22 条已登记），测试日志配置是否实际降级未运行验证。

---

## 六、值得肯定之处

1. **连接与事务的归还语义是认真做过的**：`QueryRunner.Source` 三态 + 惰性借连接保证事务连接与外部连接绝不被 Runner 归还；`close()` 幂等；`executeBatch` 先取连接再判 `isInTransaction()`（修掉"事务内静默提前提交"事故的关键顺序）；回滚失败的连接会 `markRollbackFailed` 并跳过 `setAutoCommit(true)`，避免隐式提交本该回滚的数据；`afterCompletion` 用 CAS 保证恰好一次。
2. **不变式有测试真的锁住**：`CriteriaFlattenTest` 用 13 个用例锁死"展平顺序 == 占位符顺序 == 参数绑定顺序"这条命门契约；`SchemaDriftIT` 用"零 ALTER"配"真实漂移必须修回"成对护栏，并明确论证"只断言零漂移的话把比对整体关掉也能通过"，快照取自 `information_schema` 这个第三方真相源而非框架内存。
3. **标识符白名单是单点闸门**：投影/ORDER BY/条件列/列对列比较全部经 `resolveColumn`（未知列渲染期抛 `UnknownColumnException`），值全部占位符化，`Sort.Direction` 取自枚举——真正可注入的只剩显式 `Criteria.raw` 逃生舱（已标注仅限受信输入）与遗留 `whereSection`（见 M10）。
4. **MySQL 类型规格化的取舍正确且用历史等价性证明**：剥离整数显示宽度让 `INT` 与 `int(11)` 判等，同时刻意保留 `tinyint(1)`（布尔）与 `tinyint(4)`（BYTE）可区分；测试把旧实现原样拷进来当基准，而不是按自己的理解重写。19 个错误码常量与官方语义逐一相符。
5. **解析期快速失败扎实**：同列名冲突抛 `IllegalStateException` 而非静默覆盖、`camelToUnderscore` 正确处理连续大写缩写、转换器缺无参构造时点名类名；`Criteria/Sort/Pageable` 用 `List.copyOf` 做防御性拷贝，`Pageable.getOffset()` 用 long 并显式拒绝超 int 范围。
6. **工程卫生**：`target/` 已正确 ignore，Maven Wrapper 锁定版本，根 POM 用 `maven.compiler.release`（而非 source/target）避免字节码目标随构建机 JDK 漂移，并显式锁定 surefire 3.x；README 主动登记 24 条已知边界（含未实现的 `NESTED`、`TX_PENDING_BUFFER`、`GenerationType.AUTO` 等同 NONE、`MysqlTableAlterHandler` 永不 DROP COLUMN 等），这种诚实度在同类库里很少见。

---

## 七、建议的修复顺序

| 顺序 | 条目 | 理由 |
| --- | --- | --- |
| 1 | S1、S2、S3、S4、S5 | 全部是"静默丢数据/读脏数据"，且都在默认使用路径上；先补上"事务完成即补偿"这条统一机制可一次覆盖 S1/S2 |
| 2 | S6、S7 | 约束缺失与清错表，改动小、后果不可逆 |
| 3 | M1、M2、M8 | 批量结果校验（顺带修好 FlushResult 口径与生成键错位风险）、同步器合并（同时让 `cacheKeysWritten` 真正生效） |
| 4 | M4、M5、M6、M7 | DDL 侧：比较键补全、破坏性操作加开关与日志、字面量转义统一、单表增量刷新 |
| 5 | M9、M10、M11、M12 | 键隔离、闸门收紧、渲染期一致性校验、异常映射补 57/HYT |
| 6 | 第四节 1-5 | 文档漂移必须与代码同步修正——这类错误会直接误导使用方的架构决策 |
| 7 | 测试基建 | 每个严重问题都应配一条回归用例：`assertSame` 校验缓存 key、并发刷盘 + 内容比对、`deleteAll` 后 `queryById`、唯一索引 DDL 断言、`afterCommit` 刷盘失败注入 |
