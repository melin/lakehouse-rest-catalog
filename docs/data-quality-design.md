# 数据质量治理设计（Data Quality / DQ）

> **设计稿，未实现。** 本文只做设计，不写实现代码。
>
> 外部参照：[Databricks Data Quality Management](https://www.databricks.com/discover/pages/data-quality-management)
> 的六维质量模型、Constraints / Expectations 两类约束、Fail / Drop / Quarantine / Retain(Flag)
> 四类处置，以及 Lakehouse Monitoring 的 Metric Tables。
>
> 本文回答五个问题：**规则存在哪、怎么表达（§4–§5）、Spark 侧怎么执行（§6）、
> 怎么让 paimon / iceberg / lance 三种格式零改动共用一套规则（§6.3）、这条规则到底拦得住谁（§7）**。
> §8 是 API，§9 是控制台，§10 是与现有模块的边界，§11 是分期与验收。
>
> 配套产品原型：[`data-quality-mockup.html`](data-quality-mockup.html)——五个画面
> （规则列表 / 新建·编辑 / 运行历史与违规 / 通过率趋势 / 表详情联动），`#hash` 可直达。

---

## 1. 问题与结论

### 1.1 要解决的两个问题

**规则散落在引擎脚本里。** 同一个「订单金额不得为负」，Paimon 的 Flink 作业里写一遍、
Iceberg 的回填 Spark 脚本里写一遍、Lance 的导入任务里再写一遍。三份定义各自演进，
口径漂移无人察觉；规则本身不进 catalog，**「这张表到底有哪些质量约束」在目录里查不到**。

**坏数据处置不统一。** 有的作业直接失败、有的静默丢行、有的写进各自的 `bad_records` 目录。
处置方式写在代码里而不是登记在元数据里，出了问题无法回答「这条规则上次拦住多少行」。

### 1.2 一句话结论

**把质量规则变成 catalog 的一等元数据（集中登记、带版本与审计），由 Spark 在执行侧统一消费；
规则作用于 DataFrame 层，因此同一套规则对 paimon / iceberg / lance 零改动生效。**

> 注意「零改动」说的是**同一套规则、同一段 enforcer 代码可复用**，不是「不可绕过」。
> 本方案的强制点只落在「经过 enforcer 的写路径」上，覆盖面有明确边界——展开见 §7。

这正好补上 [`README.md` §6.1](../README.md) 所引文章里「Catalog 不只是表注册中心」缺的那块——
本仓已有权限、脱敏、血缘、审计，唯独缺「数据质量规则」这一项治理资产。

### 1.3 范围

| 做 | 不做 |
| --- | --- |
| 质量规则作为 catalog 实体：CRUD + 启停 + 版本/etag + 审计 | 替代调度系统的「任务依赖」——那是调度器的事 |
| 六维模型 + 四类处置动作（Fail / Drop / Quarantine / Flag） | 自动发现/推断规则（profile 后自动建规则，P2 再议） |
| Spark 侧格式无关执行（ON_WRITE 写前校验、ON_READ 读后画像） | 在服务端重算数据（服务端不读数据文件，只存元数据与结果） |
| 运行结果、违规采样、指标快照落 catalog，控制台出趋势 | AI/模型层审计、SQL 执行限制——那些在 AI 应用层 |
| 敏感数据「识别后可挂脱敏规则」的挂钩点 | 敏感数据自动分类识别（独立能力，见 §12） |

---

## 2. 参考基线：Databricks 怎么做的，我们哪里不同

### 2.1 Databricks 的四块能力

| 能力 | 形态 | 关键点 |
| --- | --- | --- |
| 六维模型 | 概念框架 | 完整性 / 唯一性 / 有效性 / 一致性 / 准确性 / 时效性 |
| Constraints | 表属性（`NOT NULL` / `CHECK`） | 在 Delta 表上持久化；写入即校验，违反整事务回滚 |
| Expectations | 写在 Lakeflow/DLT 管道代码里 | 声明式 `EXPECT` / `expect_all`，配 `ON VIOLATION`（Fail / Drop / Retain） |
| Monitoring | Lakehouse Monitoring | 自动生成 Metric Tables（Delta）+ Dashboard，含 drift / 自定义指标 |

### 2.2 刻意的差异

| 维度 | Databricks | 本方案 |
| --- | --- | --- |
| 规则存放 | 写在管道代码里，绑 Delta + DLT | **集中存 catalog 元数据**，与引擎、格式解耦 |
| 生效范围 | 该管道 / 该 Delta 表 | 一条规则对 paimon / iceberg / lance 同源生效 |
| 消费方 | DLT 运行时 | 任何能读 catalog 的引擎（本期 Spark，Flink 留位） |
| 结果去向 | Event Log（Delta 表） | catalog 的 run / violation / metric 三张表 + 控制台 |
| 处置动作 | Fail / Drop / Retain | Fail / Drop / Quarantine / Flag（Quarantine 显式建隔离表） |

> 取舍一句话：**Databricks 把规则绑进「怎么算」，我们把它抽出来放进「目录是什么」。
> 代价是执行侧要自带一个 enforcer；收益是多格式、多引擎、可发现、可审计。**
> 本仓的定位是「Lakehouse REST Catalog」，规则属于目录资产，这个取舍是顺的。

---

## 3. 总体设计

```
         ┌──────────────────────── catalog（服务端，只存元数据）────────────────────────┐
         │  QualityRuleEntity   QualityRunEntity   QualityViolationEntity   QualityMetric │
         └───────────────▲───────────────────────────────────────────▲──────────────────┘
                         │ ① 拉规则 GET /quality-rules                │ ④ 回写结果 POST /quality-runs
                         │                                           │
   ┌─────────────────────┴───────────────────────────────────────────┴──────────────────┐
   │                        Spark 侧 QualityEnforcer（引擎无关 / 格式无关）              │
   │   df = spark.read.table("lakehouse.db.tbl")   ← paimon / iceberg / lance 皆同一入口  │
   │   rules = QualityRuleClient.load(...)                                               │
   │   (valid, invalid) = QualityEnforcer.partition(df, rules)                           │
   │   QualityEnforcer.write(valid, invalid, rules, target)  ← 按 action 路由             │
   └────────────────────────────────────────────────────────────────────────────────────┘
                         │ ② 读数据（DataFrame）              │ ③ 处置（写目标 / 隔离 / 失败）
                         ▼                                   ▼
              paimon / iceberg / lance 表          {table}__dq_quarantine（坏行隔离表）
```

**角色分工**
- **catalog**：规则的唯一真相源 + 运行结果与指标的存档。不读数据文件。
- **Spark enforcer**：规则的执行者。读 DataFrame、算违规、按 action 处置、回写结果。
- **控制台**：规则的编辑入口与结果的可视化，不参与执行。

**三条不变量**
1. 规则只在 catalog 改，引擎不持有规则副本（每次运行现拉，可加短 TTL 缓存）。
2. 处置动作由规则定义，不由作业代码临时决定。
3. 单条规则的运行结果必落一条 run；违规明细按采样存，不存全量。

---

## 4. 数据模型

四个新实体，均继承 `AuditedEntity`（`owner` / `createdBy` / `updatedBy` / `createdAt` / `updatedAt`），
并带 `version` 乐观锁（与 `DataPolicyEntity` 同构，后提交者 409）。仓储放 `core` 的
`domain/repo`，与既有实体一致；DDL 由 `gen-mysql-ddl.sh` / `gen-postgres-ddl.sh` 生成。

### 4.1 `QualityRuleEntity`（`lakehouse_quality_rule`）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | uuid PK | 规则 ID |
| `catalog_name` / `db_name` / `table_name` | varchar(255) | 作用目标；与 `lakehouse_table` 的逻辑引用一致 |
| `column_name` | varchar(255), nullable | 列级规则填列名；**表级规则为 null** |
| `dimension` | varchar(32) | 六维之一（见 §5.1） |
| `expression` | text | Spark SQL 布尔式（VALIDITY / CONSISTENCY / ACCURACY 用） |
| `column_set_json` | text, nullable | UNIQUENESS 用的列集，如 `["id"]` 或 `["tenant","order_no"]` |
| `time_column` | varchar(255), nullable | TIMELINESS 用的时间列 |
| `max_lag_ms` | bigint, nullable | TIMELINESS 的最大允许延迟（毫秒） |
| `severity` | varchar(16) | `ERROR` / `WARN` / `INFO` |
| `action` | varchar(16) | `FAIL` / `DROP` / `QUARANTINE` / `FLAG` |
| `pass_rate_threshold` | double, nullable | 批量作业用；通过率低于此值即按 FAIL 处理 |
| `enabled` | boolean | **默认 true**（与 `policy.enabled` 缺省一致；写 `false` 才停用） |
| `trigger` | varchar(16) | `ON_WRITE` / `ON_READ` / `SCHEDULED` / `AD_HOC` |
| `schedule_cron` | varchar(64), nullable | `SCHEDULED` 用 |
| `version` | int | 乐观锁 / etag |

> 唯一约束建议：`(catalog_name, db_name, table_name, column_name, dimension, expression 摘要)` 防重；
> 但同一列允许多条不同维度的规则（如 email 既有「非空」又有「格式合法」）。

### 4.2 `QualityRunEntity`（`lakehouse_quality_run`）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `run_id` | uuid PK | 一次执行 |
| `rule_id` | uuid, nullable | 单规则执行为规则 ID；整表批量可为 null |
| `catalog_name` / `db_name` / `table_name` | varchar | 目标 |
| `triggered_by` | varchar(16) | `WRITE` / `READ` / `SCHEDULED` / `AD_HOC` |
| `engine` | varchar(16) | 本期恒 `SPARK` |
| `started_at` / `finished_at` | bigint | epoch 毫秒 |
| `status` | varchar(16) | `RUNNING` / `SUCCESS` / `FAILED` |
| `total_rows` / `violated_rows` | bigint | 统计口径见 §6.5 |
| `pass_rate` | double | `1 - violated_rows/total_rows`（多规则时按「任一不通过」计行） |
| `note` | varchar(1024) | 失败原因 / 引擎侧摘要 |

### 4.3 `QualityViolationEntity`（`lakehouse_quality_violation`）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | uuid PK | |
| `run_id` / `rule_id` | uuid | 归属 |
| `ref` | varchar | `catalog.db.table` |
| `row_locator` | varchar(512) | 行定位：分区 + 主键 / 文件 + offset（实现相关） |
| `violated_expression` | varchar(2048) | 命中的表达式或列集 |
| `severity` | varchar(16) | |
| `detected_at` | bigint | |

> **采样入库**：默认每规则最多存 N 条（如 100），并记 `violated_rows` 总数。全量坏行落在
> quarantine 表里，catalog 只存样本与计数，避免大表把元数据库撑爆。

### 4.4 `QualityMetricSnapshotEntity`（`lakehouse_quality_metric`）

对标 Databricks 的 Metric Table，用于趋势与 drift。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | uuid PK | |
| `ref` | varchar | `catalog.db.table` |
| `run_id` | uuid | 归属 run |
| `metric_name` | varchar(64) | `null_ratio` / `distinct_ratio` / `row_count` / `freshness_ms` …… |
| `metric_value` | double | |
| `captured_at` | bigint | |

### 4.5 DDL 示意（生成物，勿手改）

```sql
create table lakehouse_quality_rule (
  id varchar(64) not null,
  catalog_name varchar(255) not null,
  db_name varchar(255) not null,
  table_name varchar(255) not null,
  column_name varchar(255),
  dimension varchar(32) not null,
  expression text,
  column_set_json text,
  time_column varchar(255),
  max_lag_ms bigint,
  severity varchar(16) not null,
  action varchar(16) not null,
  pass_rate_threshold double,
  enabled boolean not null default true,
  trigger varchar(16) not null,
  schedule_cron varchar(64),
  version int not null default 0,
  -- AuditedEntity 列：owner / created_by / updated_by / created_at / updated_at
  primary key (id)
);
```

> 与既有约定一致：**新增 `not null` 列必须带 `default`**，否则存量行的 `ADD COLUMN` 跑不通；
> 加完实体先 `install core` 再跑 `gen-mysql-ddl.sh`，别手改生成物。

---

## 5. 规则语义

### 5.1 六维怎么表达

| 维度 | 适用层级 | 表达方式 | Spark 判定 |
| --- | --- | --- | --- |
| COMPLETENESS 完整性 | 列级 | `column_name` | `col IS NOT NULL`（可扩展为「非空且非空串」） |
| UNIQUENESS 唯一性 | 表级 / 列集 | `column_set_json` | `count(*) over (partition by cols) = 1` |
| VALIDITY 有效性 | 列级 | `expression` | 逐行布尔式为 false 即违规 |
| CONSISTENCY 一致性 | 表级（跨列） | `expression` | 如 `total = qty * price` |
| ACCURACY 准确性 | 表级 / 列级 | `expression` | 如「金额与对账源差额在阈值内」 |
| TIMELINESS 时效性 | 列级 | `time_column` + `max_lag_ms` | `current_timestamp() - to_timestamp(col) > lag` |

> `expression` 走与行过滤/脱敏同一套 **SQL 表达式白名单**（`parseExpression` + 白名单 AST），
> 不注册 UDF，不执行任意代码。这是复用 `row-filter-column-mask-design.md` §13 的现成能力。
>
> 六维里只有 `COMPLETENESS` 在部分格式上能下推成**格式级约束**；`UNIQUENESS` / `VALIDITY`
> 在三种格式上一律按**软规则**执行（enforcer 判定 + 处置动作）。控制台不要承诺「硬约束」，
> 原因见 §7.5。

### 5.2 四类处置动作

| action | 作业结果 | 数据动作 | 适用 |
| --- | --- | --- | --- |
| `FAIL` | 失败 | 不写入（提交前 abort） | 关键规则：主键唯一、关键非空（软规则判定，非格式级约束） |
| `DROP` | 继续 | 丢弃违规行，只写合法行 | 可容忍少量脏行的导入 |
| `QUARANTINE` | 继续 | 合法行写目标表，违规行写 `{table}__dq_quarantine` | 需保留坏行后续修复重跑 |
| `FLAG` | 继续 | 全量写入，附加 `dq_violations` 数组列；或只上报不写 | 观察期 / 只监控 |

> `QUARANTINE` 是**本方案独有的动作**：Databricks 没有 `ON VIOLATION QUARANTINE`，它的做法是
> 「drop 一份 + 反向条件再写一张表」这种**模式**，两张表之间没有事务保证。我们把它做成一等动作
> 省掉用户写两遍逻辑，代价是**得自己保证好行与坏行的落盘原子性**——见 §7.6。

### 5.3 阈值

- `pass_rate_threshold` 为空：`severity=ERROR` 时「有任一违规即 FAIL」，`WARN/INFO` 只记录。
- 非空：`pass_rate < threshold` 才按 FAIL 处理（对「脏行比例极低」的大批量导入友好）。
- 多规则命中同一行时，`FAIL` 的判定取「最严」——任一 `ERROR` 规则超阈即整体失败。

### 5.4 触发方式

| trigger | 含义 | 挂载点 |
| --- | --- | --- |
| `ON_WRITE` | 写前校验 | `QualityEnforcer.write(...)` 在写入前跑 |
| `ON_READ` | 读后画像 | `QualityEnforcer.profile(...)` 只统计 + 落 metric，不改数据 |
| `SCHEDULED` | 定时巡检 | 由 optimizer / 外部调度按 `schedule_cron` 触发 `POST /run` |
| `AD_HOC` | 手动 | 控制台「立即运行」 |

---

## 6. Spark 实现

### 6.1 为什么格式无关成立

三种表在 Spark 里都是 `lakehouse.<db>.<table>`，经本仓的 catalog REST connector 解析为
`DataFrame`。DQ 的全部逻辑都在 **DataFrame 层**，规则是 **catalog 元数据**——
所以：

```
paimon  → spark.read.table("lakehouse.db.t1") → DataFrame ─┐
iceberg → spark.read.table("lakehouse.db.t2") → DataFrame ─┼→ 同一段 QualityEnforcer
lance   → spark.read.table("lakehouse.db.t3") → DataFrame ─┘
```

**唯一格式相关的是 quarantine 写回**：坏行要写回同 catalog 的 `{table}__dq_quarantine`，
由各格式的 connector 自己落盘（Paimon / Iceberg / Lance 的 writer 各自处理），enforcer 只负责
「把 DataFrame 交给对应 catalog 表的写接口」。

### 6.2 组件与 API

```text
QualityRuleClient
  .load(spark, catalog, db, table)  -> Seq[QualityRule]
      GET /api/catalogs/{c}/quality-rules?db=&table=&enabled=true
      （进程内短 TTL 缓存，默认 60s，与策略下发同口径）

QualityEnforcer
  .partition(df, rules)             -> (validDF, invalidDF)
  .write(validDF, invalidDF, rules, target)   // 按 action 路由
  .profile(df, rules)               // ON_READ：只统计 + 落 metric
  .report(run)                      // POST 结果回 catalog
```

### 6.3 执行伪代码（示意，非交付）

```scala
val rules = QualityRuleClient.load(spark, "lakehouse", "ods", "orders")
val df    = spark.read.table("lakehouse.ods.orders")

val (valid, invalid) = QualityEnforcer.partition(df, rules)
//  对每条规则生成一个违规掩码列，OR 起来得到「该行是否违规」；
//  invalid 额外带 dq_rule_ids: array<string>，用于隔离表溯源。

rules.foreach { r =>
  r.action match {
    case FAIL       => QualityEnforcer.assertPass(valid, invalid, r)  // 超阈则 abort
    case DROP       => // valid 才进入下游
    case QUARANTINE => QualityEnforcer.quarantine(invalid, target)    // 写 __dq_quarantine
    case FLAG       => // 全量写，附 dq_violations 列
  }
}
QualityEnforcer.report(run)   // 落 run / violation 采样 / metric
```

> 实际落地建议做一个 `QualityEnforcer.write(df, target)` 门面：内部先 `partition`、按 action
> 决定写哪些行、再调用各格式 writer。这样业务代码只改一行 `df.write` → `QualityEnforcer.write`。

### 6.4 FAIL 与 ACID

- enforcer **在发起写入前**完成校验；未过阈值就直接抛 `QualityCheckFailedException`，
  不产生任何提交——这比「写进去再回滚」更省，也不依赖各格式的回滚能力。
- 若采用「边写边校验」（流式 `foreachBatch`），则利用 Paimon / Iceberg 的事务提交语义，
  在 commit 前判定并放弃本次提交；Lance 同理由其 writer 处理。

### 6.5 统计口径

- `total_rows`：本次校验作用到的行数（不是整表行数；ON_WRITE 是这一批，ON_READ 是扫描范围）。
- `violated_rows`：命中**任一**启用规则的行数（去重），不是各规则违规数之和。
- `pass_rate = 1 - violated_rows / total_rows`，`total_rows=0` 时记 1.0 并在 note 标注。

### 6.6 成本控制

| 手段 | 说明 |
| --- | --- |
| 分区裁剪 | ON_READ 只扫最近分区（默认最新，可配 N 个） |
| 采样 | `profile` 支持 `sampleRatio`，大表默认 0.1；ON_WRITE 不采样（要准） |
| 列裁剪 | 只读规则涉及的列，避免全表宽行 |
| 规则合并 | 同一 DataFrame 一次扫描算完所有规则，不逐规则重扫 |

---

## 7. 强制点：这条规则到底拦得住谁

**先把话说死：本方案的 `QualityEnforcer` 落在「引擎内、本写路径」这一层，它不是不可绕过的约束。**
设计里凡是出现「统一」「强制」「保证」，都得能回答「在哪个强制点、覆盖哪些写入方」；答不上来，
承诺就会被读成「谁写都拦得住」——那是另一层能力，本方案没有。

这一节对照 Databricks 的四套机制，定位我们自己在哪一级，并说明三格式为什么**做不到**同等强度的约束。

### 7.1 Databricks 的四套机制，落在四个不同位置

| 机制 | 声明在哪 | 执行点 | 违反时 | 覆盖面 |
| --- | --- | --- | --- | --- |
| Expectations（`@dp.expect*` / `CONSTRAINT ... EXPECT`） | **只能挂在数据集定义上**（streaming table / materialized view / view 的建表语句），不是任意 SQL | 管道运行时，包在写入之前 | `warn`：写进目标表并计数；`drop`：写前丢弃；`fail`：整次更新失败并回滚 | 只覆盖**经过这条管道**的数据 |
| 表约束（`NOT NULL` / `CHECK`） | `CREATE TABLE` / `ALTER TABLE ADD CONSTRAINT` | **提交时**，存储层 | 整个事务失败（`DELTA_VIOLATE_CONSTRAINT_WITH_VALUES`） | **所有写入方**：管道、notebook、`MERGE`、外部客户端 |
| 行过滤 / 列脱敏（`SET ROW FILTER` / `SET MASK`） | 表属性 | **扫描时注入执行计划** | 不报错，静默改写结果 | **所有读取方** |
| Lakehouse Monitoring | 监控配置 | 定时任务算 metric 表 | 只出报表 | 不拦截 |

> 只有第三处在动**查询**的执行计划，而那是它正常工作的方式（对用户不可见）。
> 第一处的 `fail` 也会动计划，但目的完全不同——见 7.2。
> 另外，`warn` / `drop` / `fail` 三档里**没有 quarantine**，官方口径是「自己实现」，见 7.6。

### 7.2 Expectations 具体怎么「执行」

规则是**建表定义的一部分**，编译进管道的流图。运行时对每条 expectation 统计 `passed_records` /
`failed_records`，写进 event log 的 `flow_progress.data_quality.expectations`；`drop` 另有
`flow_progress.data_quality.dropped_records`。

所以「执行」的本质是：**在写入路径外面套一层过滤 + 计数**，不是往用户的查询里插节点。

`warn` / `drop` 都不动计划，**只有 `fail` 会动**。官方原文：

> Expectations configured to fail pipelines modify the Spark query plan of your transformations
> to track information required to detect and report violations.

目的很具体：让报错能带出**违规行本身**，便于定位是哪条输入导致的：

```text
[EXPECTATION_VIOLATION.VERBOSITY_ALL] Flow 'sensor-pipeline' failed to meet the expectation.
Violated expectations: 'temperature_in_valid_range'.
Input data: '{"id":"TEMP_001","temperature":-500,...}'
Output record: '{"sensor_id":"TEMP_001","temperature":-500,...}'
```

**是诊断，不是执行**——规则本身仍在写路径上判定。两个容易踩的点：

- `fail` **反而不产度量**：更新失败就没有计数，监控只能靠「更新失败」这条信号。
- `fail` 只停**自己那条 flow**；triggered 模式下并行 flow 照跑，continuous 模式才会停依赖链。

### 7.3 表约束才是真正「不可绕过」的那层

- **提交时**校验：不管写入来自管道、notebook 的 `INSERT` 还是 `MERGE`，一律拒绝。
- **读时不校验**：所以历史脏数据不会让查询失败（反过来讲，别指望靠加约束清理存量）。
- 加约束时会先验一遍存量行；代价是 writer protocol 升到 3，影响外部 Delta 客户端的兼容性。
- `PRIMARY KEY` / `FOREIGN KEY` / `UNIQUE` 是 **informational，不强制**（`UNIQUE` 仍是 preview）——
  设了主键不防重，这是最常见的误解。

### 7.4 本方案的位置：比行过滤还低一档

[`row-filter-column-mask-design.md`](row-filter-column-mask-design.md) §2.3 已给过一套强制等级
（L0–L3），DQ 直接沿用，不另造一套：

| 等级 | 含义 | 代表实现 | 本方案 |
| --- | --- | --- | --- |
| 应用层自觉 | 调不调校验，看作业作者自己 | 任何 SDK / 门面 | **当前的 `QualityEnforcer.write()`** |
| **L0** | 引擎内注入，但凭据在客户端 | 本仓的行过滤（自定义逻辑节点） | 可升级，见下 |
| L1 | 网关代理，用户只有 SQL 连接 | Kyuubi + Ranger | 超出 DQ 范围 |
| L2 | 凭据唯一来源 + 存储层拒绝旁路 | EMR + Lake Formation | 超出 DQ 范围 |
| L3 | 隔离执行，用户代码与引擎不共享进程 | Databricks Lakeguard | 超出 DQ 范围 |

> 行过滤那边的结论是「本仓在 L0，升级路径是加网关（L1）或收掉凭据来源（L2）」。DQ 在同一张表里
> 的位置**更低**：行过滤由引擎插件强制执行，作业作者改不了；DQ 只是一个「建议你调用的门面」——
> 写 `df.write(...)` 而不是 `QualityEnforcer.write(...)`，规则就**静默失效**。

升级到 L0 的路径是现成的：行过滤已经用自定义逻辑节点改写了**读**路径，DQ 可以在**写**路径做
同构的事（拦截 `lakehouse.*` 的写入并前置校验节点）。这是 Spark 侧的新增能力，属于 P3 之后的
独立工作，不在本期范围；本期只能承诺「**经过门面的写入被校验**」。

两件明确做不到的事，别在设计里暗示能做：

1. **服务端 commit 前校验**——catalog 不读数据文件、不参与写入路径（§3 的三条不变量），
   它无法在提交前看到待写入的行。
2. **凭据直连写入**——`credential.providers` 默认开三家、relay 档还会转发长期 AK，拿到凭据的
   调用方可以直接落文件。这与行过滤「凭据下发后直连文件无法拦截」是**同一条硬边界**，
   不是 DQ 的实现缺陷。

### 7.5 三格式的约束能力不对等

想从「软规则」升到「格式级硬约束」，只能靠各格式原生的 schema 能力，而三家**不对等**：

| 格式 | 非空 | 值域（`CHECK` 类） | 唯一性 |
| --- | --- | --- | --- |
| Paimon | 字段 `NOT NULL`（required） | 无通用 `CHECK`，只能靠计算列或写入侧 | 主键表按主键**合并去重**，不是「拒绝重复」 |
| Iceberg | 字段 `required` | spec 不提供 `CHECK` | 无 |
| Lance | `nullable = false` | 无 | 无 |

> 表里是「当前版本的实际能力」，落地前必须按各格式版本实测——这一行比任何文档都容易过期。
> 另外，格式级约束是**格式协议内的拒绝**，不是 L2 的存储层拒绝：正常写入都必须经 writer，
> 所以覆盖面接近全部写入方；但直接落文件会破坏表本身，那属于「破坏性绕过」，是另一类问题。

**结论：「统一 DQ」和「硬约束」是两件事。**

- 前者可以跨格式承诺：规则是 catalog 元数据、执行在 DataFrame 层。
- 后者不能：跨格式只有「非空」勉强对齐，值域与唯一性都得逐格式实现，且语义各不相同
  （「合并去重」不等于「拒绝重复」）。
- 控制台必须**按格式灰掉不可用的选项**。否则用户以为勾了「唯一性硬约束」就真拦得住，
  最后表现为「脏数据进表了、但规则是绿的」——比不配更糟。

**本方案的取舍**：`UNIQUENESS` / `VALIDITY` 一律按软规则执行（enforcer 判定 + 处置动作），
不承诺格式级硬约束；只有 `COMPLETENESS` 在支持 `NOT NULL` 的表上可以「顺带」落成格式约束，
但要在控制台显式标出「已下推」，别让两种强度混在一个绿灯里。

### 7.6 Quarantine 是「模式」，不是动作

Databricks 没有 `ON VIOLATION QUARANTINE`。官方口径是「自己实现」：`drop` 一份 + 反向条件再写
一张表——**两张表之间没有事务保证**。

我们把它做成一等动作（§5.2），省掉用户写两遍逻辑；代价是**得自己保证好行与坏行的落盘原子性**，
这个负担他们没有。

> 落地要求：`QUARANTINE` 必须先写隔离表、再写目标表（或两者同属一个可提交单元）。
> 反序会在中途失败时留下「坏行进了隔离表、好行没进主表」；顺序反了则是「好行已提交、坏行丢了」。
> 这一条进 §11.1 的 P2 交付内容与验收。

### 7.7 本次同步落地的三处修正

1. **措辞**：描述本方案时避免「不可绕过 / 强约束」，统一说「覆盖本写路径」（§7.3 的「不可绕过」
   说的是 Databricks 表约束，保留）；§1.2 的「零改动生效」也保留（那是事实），但补了边界说明。
2. **边界表**（§10）在「凭据下发」那一行补上：凭据一旦到调用方手上，直连文件写入不受 DQ 约束。
3. **风险表**（§12）新增三条：格式约束能力不对等、quarantine 写入不原子、规则被绕过。

---

## 8. API 契约

沿用 Management API 风格（`/api/catalogs/{catalog}` 已在授权拦截器覆盖范围内）。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/catalogs/{c}/quality-rules` | 列表；支持 `db` / `table` / `dimension` / `enabled` 过滤 |
| POST | `/api/catalogs/{c}/quality-rules` | 新建 |
| PUT | `/api/catalogs/{c}/quality-rules/{id}` | 更新（带 `version`，冲突 409） |
| DELETE | `/api/catalogs/{c}/quality-rules/{id}` | 删除 |
| POST | `/api/catalogs/{c}/quality-rules/{id}/run` | 立即运行（异步，返回 runId） |
| GET | `/api/catalogs/{c}/quality-runs` | 运行历史；`table` / `from` / `to` |
| GET | `/api/catalogs/{c}/quality-runs/{runId}` | 单次详情 |
| GET | `/api/catalogs/{c}/quality-runs/{runId}/violations` | 违规采样 |
| GET | `/api/catalogs/{c}/quality-metrics` | 指标序列；`table` / `metric` / `from` / `to` |

> **授权要点**：catalog 端点只加控制器判权不够——`AuthorizationInterceptor` 先执行，
> `resolve()` 返 null 即 403。新端点必须登记进运行时端点表，守它的是「运行时端点数 == 登记数」。

**权限点建议**：读规则用 `SELECT`（与表可见性一致）；写规则需要表级 `UPDATE` 或管理角色；
`/run` 需要 `SELECT`（只读校验）。写规则、删除规则、运行均记审计。

**事件**：接入现有 [`event-framework.md`](event-framework.md)，发
`quality.rule.created` / `quality.rule.updated` / `quality.run.completed`（带 pass_rate、violated_rows），
供告警与后续 AIOps 消费。

---

## 9. 控制台

### 9.1 页面清单

| 页面 | 路由（建议） | 内容 |
| --- | --- | --- |
| 数据质量总览 | `/quality` | 规则数、启用数、近 7 天通过率趋势、失败 run 列表 |
| 规则列表 | `/quality/rules` | 按目标/维度/严重级/动作过滤；启停开关；立即运行 |
| 新建/编辑规则 | 抽屉 | 目标级联（catalog.db.table.col）+ 六维 + 表达式 + 四动作 + 阈值 + 触发 |
| 运行历史 | `/quality/runs` | run 列表、通过率、违规行、状态 |
| 违规明细 | run 详情 | 采样行定位 + 命中表达式 |
| 表详情联动 | 表详情页新增「数据质量」页签 | 该表的规则与其最近通过率 |
| 趋势监控 | `/quality/monitor` | 指标曲线（null_ratio / freshness / pass_rate） |

### 9.2 原型

[`data-quality-mockup.html`](data-quality-mockup.html) 五画面（`#rules` / `#edit` / `#runs` /
`#monitor` / `#table`），与真实控制台的 Element Plus 风格一致，含：
- 规则列表（格式/维度/严重级/动作/通过率条/启停）
- 新建抽屉（目标级联 + 六维 + 表达式 + 四动作 + 阈值 + 触发方式）
- 运行历史 + 违规采样明细
- 通过率趋势（示意折线）
- 表详情页「数据质量」页签联动

> 状态口径：本文是**设计稿，未实现**；原型是静态 HTML，未接入后端、未集成 Vue 路由。

---

## 10. 与现有模块的关系

| 模块 | 关系 |
| --- | --- |
| 行过滤 / 列脱敏（`DataPolicyEntity`） | **互补不重叠**：policy 是「访问控制层」（谁能看哪行/哪列），DQ 是「数据本身质量层」。两者都挂 catalog 元数据、复用同一套 Principal 与审计 |
| 血缘（`Lineage*`） | 正向协同：DQ 失败可沿血缘定位下游影响面；metric 变化可与血缘 run 关联（AIOps 的输入） |
| 审计（`AuditedEntity`） | 规则与 run 都带审计列；写规则/运行记审计事件 |
| 事件框架（`event-framework.md`） | 发 `quality.run.completed` 等事件，供告警与 AIOps |
| 凭据下发 | 无直接关系；enforcer 用调用者令牌读表，走既有认证。**但凭据一旦到调用方手上，直连文件写入就不受 DQ 约束**——强制点只在「经过 enforcer 的写路径」上（§7.4），这与行过滤那条硬边界同源 |
| 表元数据（`lakehouse_table`） | 规则按 `catalog.db.table` 逻辑引用，不建外键；表删除时规则置为 orphan（`ON_READ` 报告 `TABLE_NOT_FOUND`） |

**明确不做的边界**（与所引文章一致）：
- 任务依赖 → 调度器；
- Agent 工具权限、SQL 执行限制、模型 I/O 审计、高风险审批、回滚 → AI 应用层 / 数据服务层；
- 敏感数据自动识别/分类 → 独立能力（见 §12），DQ 只提供「识别后可挂脱敏规则」的挂钩点。

---

## 11. 落地分期与验收

### 11.1 分期

| 阶段 | 内容 | 交付 |
| --- | --- | --- |
| P0 | 四实体 + DDL + management DTO/端点 + 控制台规则 CRUD | 规则可登记、可查、可启停 |
| P1 | Spark `QualityEnforcer`（`FLAG` / `DROP` 先行）+ run/violation 回写 + 控制台运行历史 | 规则可执行、结果可见 |
| P2 | `QUARANTINE`（隔离表 + TTL + 好行/坏行落盘原子性）+ `FAIL`（阈值/ACID）+ ON_READ 指标 + 趋势图 | 完整四动作 + 监控 |
| P3 | SCHEDULED + 事件告警 + 与血缘/AIOps 联动 | 自动巡检闭环 |

### 11.2 验收清单（P1 最小闭环）

1. 在 catalog 建一条 `COMPLETENESS` 规则，Spark 通过 `lakehouse.ods.orders` 写入含 null 的批次：
   - `QUARANTINE` 时合法行进目标表、坏行进 `orders__dq_quarantine`、run 记 `violated_rows`。
2. 同一规则分别对 paimon / iceberg / lance 三张表执行，**不改一行 enforcer 代码**。
3. 规则改 `version` 后并发保存，后提交者得到 409。
4. `POST /run` 返回 runId，`GET /quality-runs` 能看到 `pass_rate` 与 `status`。
5. 控制台「数据质量」页签显示该表规则与最近通过率。
6. 删除表后，`ON_READ` 的 run 记 `TABLE_NOT_FOUND` 且不抛 500。

---

## 12. 风险与未决

| 风险 | 说明 | 对策 |
| --- | --- | --- |
| 大表全量校验太贵 | ON_WRITE 不能采样，宽表扫描昂贵 | 规则只覆盖关键列；分区裁剪；`LIMIT` 预检 + 全量复核两段 |
| quarantine 表膨胀 | 坏行长期累积 | 隔离表按 `detected_at` 分区 + TTL（复用 optimize 的 expire 能力） |
| 规则冲突 | 同一列多条规则、动作不一致 | 同一列同维度唯一；跨维度按「最严动作」合并，控制台提示 |
| 表达式安全 | 用户写任意表达式 | 复用行过滤/脱敏的 SQL 白名单 AST，不注册 UDF |
| 格式差异 | quarantine 写入各格式路径不同 | enforcer 只产 DataFrame，写回交给各 connector |
| 格式约束能力不对等 | 只有「非空」勉强三家对齐；值域 / 唯一性语义各不相同（§7.5） | 统一 DQ 只承诺软规则；硬约束按格式单独实现，控制台按格式灰掉不可用选项 |
| quarantine 写入不原子 | 好行与坏行分两次写，中途失败会出现「一成一败」 | 先写隔离表再写目标表；两者同属一个可提交单元；P2 验收显式覆盖（§7.6） |
| 规则被绕过 | 强制点只在 enforcer 写路径上，作业直写或凭据直连都不生效（§7.4） | 文档与控制台明确标注覆盖范围；如需升级，做 Spark 侧写路径注入（L0） |
| 敏感数据识别 | 本设计不含自动分类 | 预留「分类结果 → 推荐脱敏规则」的挂钩点，独立能力后续补 |

---

## 参考

- 六维模型 / Constraints / Expectations / Monitoring：Databricks Data Quality Management（见文首链接）
- 表达式白名单与 AST： [`row-filter-column-mask-design.md`](row-filter-column-mask-design.md) §13
- 事件框架： [`event-framework.md`](event-framework.md)
- 数据模型与审计列： [`data-model.md`](data-model.md)
- 表优化与 expire： [`table-optimization.md`](table-optimization.md)
