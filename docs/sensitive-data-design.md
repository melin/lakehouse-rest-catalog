# 敏感数据识别与分类 · 设计方案

> **状态：设计稿，未实现。** 本文只描述方案与契约，不含任何已落地代码。
> 配套交互原型：[`sensitive-data-mockup.html`](sensitive-data-mockup.html)（八画面）。

## 0. 一句话结论

把「这一列是什么敏感数据、多敏感」做成 **catalog 里的一等元数据**：扫描器用五层探测器自动识别、按置信度分档（自动应用 / 待人审 / 丢弃），确认后落成**列级标签**；标签再驱动三件事——**按标签脱敏**（策略不再按列名硬绑）、**沿血缘传播**、**对外供目录检索**。识别与打标只发生在 catalog 侧，**Paimon / Iceberg / Lance 零改动**。

## 1. 问题与边界

### 1.1 今天已经有什么

| 能力 | 现状 |
|---|---|
| 列脱敏 | `lakehouse_column_mask`，一列一个 mask，挂在 `lakehouse_data_policy` 下 |
| 整列不可见 | `hidden_json`（`PolicyHiddenColumnsRule`） |
| 行过滤 | `row_filter_json`（白名单 AST） |
| 列级血缘 | `lineage_column_edge`，带 `transform_kind`（`DIRECT` / `DERIVED` / `AGGREGATE` / `CONSTANT` / `UNRESOLVED`） |
| 审计 | `AuditedEntity`（`owner` / 创建时间 / 更新时间） |
| 标签 | `lakehouse_tag` —— **但这是快照标签，不是治理标签**，见 §2 |

### 1.2 缺的是哪一半

现有策略体系是**「知道要保护什么」之后**的执行层，缺的是**「怎么知道哪一列要保护」**：

- **全靠人报列名**：`SET MASK keep_last(4) ON id_card` 要求治理员先知道 `id_card` 这列存在。1000 张表 × 50 列 = 5 万个列，人工不可穷举。
- **新列无人管**：`ALTER TABLE ADD COLUMN id_no STRING` 之后没有任何信号，策略不会自动覆盖，直到出事。
- **答案不能复用**：即便识别过，结论也没地方落——没法按「有哪些 L4 列」检索，没法让下游数据地图消费，没法在表详情里告诉使用者「这列是身份证」。
- **策略与分类耦合**：策略按列名硬绑，同一种「身份证」在 200 张表里叫 200 个名字，就得写 200 条 mask。

### 1.3 明确不做（划清边界，避免能力膨胀）

| 不做 | 理由 |
|---|---|
| 全表扫描 / 全量读 | 采样足够判型；全量读既费钱又把风险面放大 |
| ML / NER 模型识别 | P0–P3 用规则 + 校验器已能覆盖证件号/卡号/手机号这类强格式；模型识别留作 P4 的可选增强（§12） |
| 自动修复 / 自动清洗数据 | 识别给结论，改数据不是 catalog 的事 |
| 爬取数据内容入库 | **证据里只存打码样本**，原始值不落库、不进日志、不进事件（§5.4） |
| 跨 catalog 联邦识别 | 标签按 catalog 隔离；跨源统一是数据地图平台的事 |
| 把标签写回表属性 | 见 §6.3，与 `DataPolicyEntity` 类注释同一条理由 |

## 2. 一个必须先解决的命名冲突

`lakehouse_tag` / `TagEntity` 已经存在，语义是**「给某个快照起的不可变名字」**（`table_id` + `tag_name` + `snapshot_id` + `tag_time_retained`），用于 time travel 与长期保留。

治理标签与它**没有一处相同**：作用对象是列不是快照、可改可删、带来源与置信度、参与权限判定。所以：

- 新表命名 `lakehouse_column_tag`（类 `ColumnTagEntity`），**不复用、不扩展 `lakehouse_tag`**；
- 两处类注释互相点名，防止后来者把「给表打标签」实现到快照标签表上；
- 控制台文案区分：快照那边叫「快照标签」，这边叫「敏感标签」。

> 这条不是洁癖：血缘边表已经踩过一次「常量列用空串占位」来表达「没有源」。若把两种 tag 混在一张表里，`tag_name` 唯一键会直接打架，且删快照标签会连带删掉治理标签。

## 3. 总体架构

```
                    ┌─────────────── catalog（服务端） ───────────────┐
  Spark / REST ──▶  │  扫描器 SensitivityScanner                       │
  （手动/钩子/调度    │    D1 列名·注释   D2 类型·形状                    │
    三种触发，见§5.6）│    D3 值模式(采样)  D4 字典   D5 统计特征         │
                    │            ↓ 加权打分                            │
                    │    ┌──────────────────────────────┐             │
                    │    │ ≥0.85 自动应用 │ 0.6~0.85 待人审 │ <0.6 丢弃 │
                    │    └──────────────────────────────┘             │
                    │            ↓                                     │
                    │  lakehouse_sensitivity_finding（扫描产物·历史）   │
                    │            ↓ 人工确认 / 自动                      │
                    │  lakehouse_column_tag（当前状态·列级标签）        │
                    │        ↙            ↓                 ↘          │
                    │  按标签脱敏      血缘传播          对外检索/事件   │
                    │  (tag_mask_binding) (lineage_column_edge)        │
                    └──────────────────────────────────────────────────┘
```

左侧那根箭头是**触发者**（谁发起这次扫描），不是「扫描跑在 Spark 里」。扫描的**执行位置**是另一个维度、可配置：默认在 catalog 进程内（复用表预览的读通道），大范围采样时可切到 Spark 作业——两种方式的取舍见 §5.6.1，其中「下推 Spark 怎么提交作业」直接复用表优化任务那条提交链路，见 §5.6.5。

四段职责严格分开，**不要合并**：

1. **识别**（scan）——一次扫描的产物，可重跑、可留历史；
2. **确认**（finding → decided）——人审闸门，误报在这里挡掉；
3. **打标**（column_tag）——当前状态，唯一真相源，策略只看它；
4. **消费**（binding / 传播 / 查询）——读标签，不回写标签。

> 为什么 finding 与 tag 必须两张表：扫描每天重跑，finding 会重复产生；而「这列是身份证」这个结论只该有一份。合并成一张表就会出现「同列 30 条 pending」或者「重扫把人工拒绝洗回待审」。

## 4. 数据模型

五张新表，全部沿用既有约定：`AuditedEntity` 审计列、`entity_version` 乐观并发、全名摘要列做唯一键（理由见 §4.6）。

### 4.1 `lakehouse_sensitive_type` —— 敏感类型与探测器

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | bigint PK | 自增 |
| `type_code` | varchar(64) unique | 如 `CN_ID_CARD` / `CN_MOBILE` / `BANK_CARD` / `EMAIL` / `PERSON_NAME` / `CREDENTIAL` |
| `type_name` | varchar(128) | 展示名「中国大陆居民身份证号」 |
| `category` | varchar(32) | `PII` / `PCI` / `PHI` / `SECRET` / `FINANCIAL` |
| `level` | varchar(8) | 默认分级 `L1`–`L4`（§5.5 对齐《网络数据分类分级指引》1–4 级），可被类型级覆盖。**类型表不出现 `CORE` / `IMPORTANT`**：那两档按国家/行业目录人工认定，不挂在「能被探测器命中的类型」上 |
| `detectors_json` | text | 五层探测器配置（列名正则、值正则、校验器、字典、权重） |
| `auto_apply` | boolean | **是否允许自动应用**（默认 false，仅强格式类型开，见 §5.3） |
| `min_confidence` | double | 自动应用阈值，默认 0.85 |
| `builtin` | boolean | 内置类型随启动 seed，**不可删、只可停用** |
| `enabled_flag` | boolean | 停用后不再参与识别（历史标签保留） |

### 4.2 `lakehouse_sensitivity_scan` —— 一次扫描

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | varchar(64) PK | UUID |
| `catalog_name` / `database_name` / `table_name` | varchar | 后两者可空 = 整个 catalog / 整个库 |
| `scope` | varchar(16) | `CATALOG` / `DATABASE` / `TABLE` |
| `mode` | varchar(16) | `METADATA_ONLY`（只跑 D1/D2，秒级）/ `SAMPLED`（加 D3/D4/D5） |
| `execution` | varchar(16) | `IN_PROCESS`（默认，§5.6.1 A）/ `SPARK`（下推 B）；落库以便回看「这次为什么慢 / 谁读的」。取值来自三层覆盖的**生效值**，不是请求体原值——请求没传时落库的是当时 catalog / 全局的默认，这样「三个月后回看这次扫描跑在哪」才有答案 |
| `trigger_type` | varchar(16) | `MANUAL` / `LIFECYCLE` / `SCHEDULE`（§5.6.2） |
| `schedule_name` | varchar(128) | 仅 `trigger_type=SCHEDULE` 有值，回查调度项（**不建外键**） |
| `sample_size` | int | 采样行上限，默认 2000 |
| `status` | varchar(16) | A 路线：`RUNNING` / `SUCCEEDED` / `FAILED` / `CANCELLED`；B 路线多两态 `PENDING` / `SUBMITTING`（§5.6.5），因此迁移前该列已按 B 的取值域建 |
| `tables_scanned` / `columns_scanned` / `findings_created` | int | 计数 |
| `tables_failed` | int | **B 路线才有意义**：逐表回调里记为失败的表数。与 `tables_scanned` 分开，否则「扫了 100 张、其中 3 张读失败」无法表达（§5.6.5 第 2 条） |
| `triggered_by` | varchar(255) | 主体名 |
| `started_at` / `finished_at` | bigint | epoch 毫秒 |
| `error_message` | varchar(2048) | 失败原因（截断） |
| `execution_namespace` / `execution_resource` | varchar | **B 路线**：提交后的 Job 坐标，用于跟踪与取消（A 路线为空） |
| `callback_token_hash` | varchar(64) | **B 路线**：本次扫描回调令牌的 SHA-256。只存哈希，不存令牌本身——与执行集群凭据「只进不出」同一口径 |
| `claim_attempt` / `claim_expires_at` | int / bigint | **B 路线**：提交的 fencing 代数与租约到期。与优化批次同形，用于回收「提交中」的孤儿扫描 |

### 4.3 `lakehouse_sensitivity_finding` —— 扫描命中（历史）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | bigint PK | 自增 |
| `scan_id` | varchar(64) | 所属扫描 |
| `catalog_name` / `database_name` / `table_name` / `column_name` | varchar | `column_name` 空串 = 表级聚合结论 |
| `column_ref_hash` | varchar(64) | `catalog.db.table.column` 的 SHA-256 |
| `type_code` | varchar(64) | 命中类型 |
| `confidence` | double | 0–1 |
| `matched_detectors_json` | text | 各探测器得分与命中率（`{"name":0.9,"value":0.97,...}`） |
| `masked_samples_json` | text | **打码后**的样本，至多 5 个（§5.4） |
| `sampling_mode` | varchar(16) | `TABLESAMPLE` / `HEAD`（降级，见 §6.2） |
| `state` | varchar(16) | `PENDING` / `AUTO_APPLIED` / `CONFIRMED` / `REJECTED` |
| `decided_by` / `decided_at` | varchar / bigint | 谁审的、何时 |

唯一键：`(scan_id, column_ref_hash, type_code)` —— 同一次扫描里同列同型只留一行。

### 4.4 `lakehouse_column_tag` —— 列级标签（当前状态）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | bigint PK | 自增 |
| `catalog_name` / `database_name` / `table_name` | varchar | 三段全名，用于展示与按 catalog 过滤 |
| `column_name` | varchar(255) | **空串 = 表级标签**（照抄血缘边「常量列用空串占位」的既有做法，不引入可空列） |
| `column_ref_hash` | varchar(64) | `catalog.db.table.column` 的 SHA-256 |
| `tag_key` | varchar(128) | 敏感标签用 `域.类型`（`PII.CN_ID_CARD`）；手工标签自由（`owner.team`） |
| `tag_value` | varchar(255) | 敏感标签存分级。**自动识别只产出 `L1`–`L4`**（§5.5 对齐《网络数据分类分级指引》1–4 级）；`CORE` / `IMPORTANT`（GB/T 43697-2024 的核心 / 重要数据）**只能人工打**，探测器与自动应用白名单都不产出它们——那两档按国家与行业目录认定，不是扫描能推的结论 |
| `source` | varchar(16) | `AUTO`（自动应用）/ `MANUAL`（人工打）/ `INHERITED`（血缘继承） |
| `type_code` | varchar(64) | 关联类型（手工标签可空） |
| `confidence` | double | 自动/继承来才有 |
| `inherited_from` | varchar(1024) | 继承来源列全名（`source=INHERITED` 时有值） |
| `finding_id` | bigint | 溯源到哪条 finding |
| `entity_version` | int | etag，同 `DataPolicyEntity` |
| `enabled_flag` | boolean | 停用 = 保留记录但不参与策略展开 |

唯一键：`(column_ref_hash, tag_key)` —— 一列一个标签键只有一个值，**从数据模型上排除「同列同键两个值」**（与 `ColumnMaskEntity` 的 `(policy_id, column_name)` 同一思路）。

### 4.5 `lakehouse_tag_mask_binding` —— 标签 → 脱敏动作

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | bigint PK | 自增 |
| `catalog_name` | varchar(64) | 绑定作用域（catalog 级，不按表） |
| `tag_key` / `tag_value` | varchar | 匹配条件（`tag_value` 可空 = 该键任意值） |
| `mask_json` | text | 与 `ColumnMaskEntity.mask_json` **同构**（`{"fn":...,"args":[...]}` 或 `{"expr":...}`） |
| `mask_sql` | varchar(2048) | 展示用渲染 |
| `priority` | int | 同列多命中时排序（大者优先） |
| `enabled_flag` | boolean | 停用 |

唯一键：`(catalog_name, tag_key, tag_value)`。

> 为什么单独一张表而不是给 `DataPolicyEntity` 加列：绑定是 **catalog 级规则**，策略是 **表级对象**。把绑定塞进策略意味着「200 张表各存一份同样的绑定」，改一次要改 200 行。

### 4.6 主键与索引取舍

列级全名 `catalog.database.table.column` 在 utf8mb4 下 `(64+255+512+255)×4 = 4344` 字节，**远超 MySQL 单索引 3072 上限**。沿用 `DataPolicyEntity` 的做法：`column_ref_hash`（SHA-256，定长 64）做唯一键，三段 + 列名原名列只用于展示与过滤。

写入方（服务层）负责保持哈希与原名列一致；改名钩子同时更新两处。**漂移只会来自绕过服务层的写法**——与策略表现有的口径一致，不引入新机制。

### 4.7 DDL 示意（MySQL，实际由 `gen-mysql-ddl.sh` 生成）

```sql
create table lakehouse_column_tag (
    id                  bigint       not null auto_increment comment '自增主键',
    catalog_name        varchar(64)  not null comment '绑定的 catalog 名',
    database_name       varchar(255) not null comment '库名',
    table_name          varchar(512) not null comment '表名',
    column_name         varchar(255) not null comment '列名；空串 = 表级标签',
    column_ref_hash     varchar(64)  not null comment 'catalog.db.table.column 的 SHA-256（唯一键，索引长度与值长度解耦）',
    tag_key             varchar(128) not null comment '标签键；敏感标签形如 PII.CN_ID_CARD',
    tag_value           varchar(255)          comment '标签值；敏感标签存分级 L1-L4',
    source              varchar(16)  not null comment 'AUTO / MANUAL / INHERITED',
    type_code           varchar(64)           comment '关联的敏感类型',
    confidence          double                comment '置信度（AUTO / INHERITED 才有）',
    inherited_from      varchar(1024)         comment '继承来源列全名',
    finding_id          bigint                comment '溯源 finding',
    entity_version      int          not null comment '乐观并发版本号，同时作为 etag',
    enabled_flag        tinyint(1)   not null comment '是否参与策略展开',
    created_at          datetime(6)  not null comment '创建时间',
    created_by          varchar(255)          comment '创建者',
    updated_at          datetime(6)  not null comment '更新时间',
    updated_by          varchar(255)          comment '更新者',
    primary key (id),
    unique key uk_column_tag (column_ref_hash, tag_key),
    key ix_column_tag_catalog (catalog_name)
) comment '列级标签（治理标签；与快照标签 lakehouse_tag 无关）';
```

其余四张表同风格，`comment` 逐列写全（本仓库惯例）。

## 5. 识别引擎

### 5.1 五层探测器

| 层 | 依据 | 成本 | 触发条件 | 典型判据 |
|---|---|---|---|---|
| **D1 名称·注释** | 列名 / 列注释 / 表名上下文 | 零 | `METADATA_ONLY` 也跑 | `id_card` / `id_no` / `mobile` / `phone` / `email` / `card_no` / `salary` / `password`；中文注释「身份证」「手机号码」 |
| **D2 类型·形状** | SQL 类型 + 长度/精度 | 零 | 同上 | `char(18)` → 身份证候选；`string` 且注释含证件；`decimal(12,2)` → 金额 |
| **D3 值模式** | 采样值正则 + 校验器 | 中 | 需 `SAMPLED` | 见下表 |
| **D4 字典** | 小基数枚举对照敏感字典 | 中 | 列基数 < 1000 且 `SAMPLED` | 民族、ICD-10 诊断码、ATC 药品码 |
| **D5 统计特征** | 基数比 / 长度分布 / 空值率 | 低 | `SAMPLED` | 人名：基数 ≈ 行数、长度集中 2–4 字；商品名基数更高、长度更散 |

D3 的强格式清单（正则 + 校验器，**校验器比正则重要**——正则只能排格式，校验位才能排「随便编的 18 位」）：

| 类型 | 正则骨架 | 校验器 |
|---|---|---|
| `CN_ID_CARD` | `^[1-9]\d{5}(18\|19\|20)\d{2}(0[1-9]\|1[0-2])(0[1-9]\|[12]\d\|3[01])\d{3}[\dXx]$` | 模 11 加权校验（权重 `7 9 10 5 8 4 2 1 6 3 7 9 10 5 8 4 2`，校验码表 `10X98765432`） |
| `CN_MOBILE` | `^1[3-9]\d{9}$` | 号段白名单（排除 `140`–`144` 等未分配段） |
| `BANK_CARD` | `^\d{13,19}$` | Luhn |
| `CN_USCC` | `^[0-9A-HJ-NPQRTUWXY]{18}$` | 模 31 校验 |
| `EMAIL` | 宽松 RFC-lite | 无（靠域名段与 `@` 结构） |
| `IP` | IPv4 四段 / IPv6 冒号段 | 每段 0–255 |
| `CREDENTIAL` | 列名优先，值层不判 | — |

### 5.2 打分与三条决策带

```
confidence = clamp( 0.35·s_name + 0.10·s_type + 0.40·s_value + 0.10·s_dict + 0.05·s_stat , 0, 1 )
```

- `s_value` 以**非空行**为分母：`hitRate = 命中行 / 非空行`；`hitRate ≥ 0.95` 记 1.0，线性降到 `0.60` 记 0。
- 校验器通过率 `≥ 0.99` 才给满分——**校验位不过的样本按未命中计**。
- 有效样本 < 200 行时整体 `× 0.7`（样本太小不给高置信）。

| 区间 | 处置 |
|---|---|
| `≥ min_confidence`（默认 0.85）**且** 类型 `auto_apply=true` | 直接落 tag，`source=AUTO`，finding 记 `AUTO_APPLIED` |
| `0.60 – 0.85`，或类型不允许自动应用 | finding 记 `PENDING`，进人审队列 |
| `< 0.60` | 丢弃，只计入扫描统计 |

阈值与 `auto_apply` 都**允许按 catalog 覆盖**（同一个 `CN_ID_CARD`，A 库用 0.85、B 库用 0.99 是正常诉求：新接入库先只收建议）。覆盖动作进审计——调阈值本身就是一次治理决定。

### 5.3 自动应用的类型白名单（防误脱敏的核心闸门）

**只有「格式强、误判代价低」的类型允许自动应用**：

- 允许：`CN_ID_CARD`、`CN_MOBILE`、`BANK_CARD`、`CN_USCC`、`EMAIL`、`IP`、`CREDENTIAL`
- **永不允许**：`PERSON_NAME`、`ADDRESS`、`SALARY`、`MEDICAL_DIAGNOSIS`——这几类的误报率天然高（`name` 可能是商品名、`amount` 可能是库存），一旦自动应用就是**误脱敏**：数据被 mask 掉但没人知道为什么，业务侧只会看到「值突然全是 `****`」。

这两条独立开关：`auto_apply` 是类型属性，`min_confidence` 是阈值，**两者同时满足**才自动落标。反过来说，治理员想让「姓名」也自动，必须显式改类型配置——这个动作本身会被审计，责任可追。

### 5.4 采样与安全（识别系统自己不能变成泄漏面）

| 约束 | 做法 |
|---|---|
| 不落原始值 | `masked_samples_json` 只存打码样本（`110***********1234`），**任何路径都不写原值**：不入库、不进日志、不进事件 payload |
| 采样量有上限 | 默认 2000 行/列，硬上限 10000；列数超阈值时按列分批 |
| 采样要单独授权 | 需要 `CATALOG:SENSITIVE_SCAN`（§10），**不复用 `TABLE:SELECT`** |
| 采样读是「合法高权限读」 | 必须绕过列脱敏（否则读到的是 `****`，识别失效）。这是本方案最需要被审视的一处张力，见下 |
| 全程审计 | `sensitivity.scan.started` / `.sampled` / `.completed`，含范围、列数、采样行数、主体 |

**关于「绕过脱敏去采样」**：这不是漏洞，而是这个能力的必要前提——但必须收口：

1. 只用内部只读通道（复用 `TablePreviewService` 的读路径，**不新开一条绕过策略的 SQL 入口**）；
2. 只读待判列，不 `SELECT *`；
3. 权限点独立于 `SELECT`，且在控制台发起扫描时显示「本次将读取 N 列明文样本」的显式提示；
4. 采样结果**不返回给调用方**——只返回统计量与打码样本，原始行不出服务端。

### 5.5 分类分级（对齐国标与等保口径）

**先拆一个最容易混的地方：等保 2.0 不是数据分级。** GB/T 22239-2019《网络安全等级保护基本要求》定的是**系统**的保护等级（1–5 级），它管的是「这套系统要按几级建」；**数据**该怎么分级由另一套标准回答——`GB/T 43697-2024《数据安全技术 数据分类分级规则》`（核心数据 / 重要数据 / 一般数据）与《网络安全标准实践指南—网络数据分类分级指引》（一般数据细分 1–4 级）。

本方案的四档**对齐的是后者**，而不是把 `L1–L4` 改成等保的 1–5 级：

| 本方案 | 《网络数据分类分级指引》 | GB/T 43697-2024 归属 | 归入的类型 |
|---|---|---|---|
| `L4` 机密 | **4 级**（严重危害个人/组织合法权益） | 一般数据 | 身份证、银行卡、密码/密钥、病历诊断 |
| `L3` 敏感 | **3 级**（一般危害） | 一般数据 | 手机号、姓名、地址、邮箱、薪资 |
| `L2` 内部 | **2 级**（轻微危害） | 一般数据 | IP、设备号、内部编号、统一社会信用代码 |
| `L1` 公开 | **1 级**（无危害） | 一般数据 | 其余业务字段 |

> **`CORE` / `IMPORTANT` 两档刻意不进自动识别。** GB/T 43697-2024 的「级别确定规则」一节规定核心数据与重要数据按**国家与行业发布的目录**认定，不是靠扫描能推出来的——把「一张含身份证的业务表」自动判成重要数据，是把监管责任当成算法输出。因此 catalog 的 `tag_value` **预留** `CORE` / `IMPORTANT` 两个取值供**人工**打标（`source=MANUAL`），但 §5.1–§5.3 的探测器与自动应用白名单里都没有它们。

**这四档对等保 2.0 控制项的贡献**（第三级「安全计算环境—数据安全」五类，本方案命中三类）：

| 等保控制项 | 本方案怎么满足 | 缺口 |
|---|---|---|
| 数据**保密性** | 标签驱动脱敏（§8.1）是执行点；本方案负责回答「哪些列要脱」 | — |
| **个人信息保护** | 分级 + 自动应用白名单 + 独立权限点 `CATALOG:SENSITIVE_SCAN` + 采样只留打码样本（§5.4） | 未覆盖「告知—同意」链路，那在业务侧 |
| 数据**完整性** | 标签的唯一键（§4.4）与乐观锁保证「治理结论不被并发写坏」 | 不涉及数据本身的校验 |
| 数据**备份恢复** | **不在范围** | 由存储层与 `expire` 策略管 |
| **剩余信息保护** | 采样不落原始行、只存打码样本；任务侧禁止把样本值写进日志（§5.6.5） | — |

**审计留存**：等保要求日志留存**不少于 6 个月**，而本方案的 `sensitivity.scan.*` 事件与 finding 决策记录**不设 TTL**（与 `lineage` 的 `run-retention-days` 不同——血缘运行史可以清，治理决策史不能）——这一条写在这里是为了防止后来者顺手给它加个清理任务。

表级级别 = 该表所有列标签 `max(level)`，落一行 `column_name=''` 的表级标签，供目录检索与「这张表能不能对外」的粗判。

### 5.6 扫描执行位置与触发方式（两个独立可配置项）

「扫描在哪里跑」和「什么时候跑」不是一回事，分开配置。两者都**不写死**：位置按 catalog 可切，触发方式可多路并存。

#### 5.6.1 执行位置：两种方式可选

| 维度 | **A. 进程内**（默认，复用 `TablePreviewService`） | **B. 下推 Spark**（复用执行集群） |
|---|---|---|
| 读通道 | 服务端进程内 reader（`PreviewTableFactory` 按格式分派） | 提交 Spark 作业，作业侧直读表 |
| 绕过脱敏 | 是——§5.4 的四条收口在这条路上最完整 | 是。但绕过点从「服务端一条链」变成「作业侧读」，§5.4 的四条收口**不再覆盖它**，需要重新论证——论证结果见 §5.6.5（收口重述为「原始行不出 Pod」） |
| 吞吐 | 单机；与数据预览争 `MAX_CONCURRENT_PER_PRINCIPAL = 2` 的名额 | 并行、可按列分区，量级更高 |
| 适用规模 | `METADATA_ONLY` 全 catalog；`SAMPLED` 表级 / 库级 | `SAMPLED` 全 catalog（几千表 × 每列 2000 行） |
| 凭据 | 服务端已有（`storageConfigInfo` 由自己消费，不涉及下发） | 要给作业下发短期凭据（复用既有 vending 通道与 `USE_CREDENTIAL`） |
| 多实例防重 | 每个实例都能跑，靠租约（沿用优化任务的三通道租约 + fencing） | 执行集群天然单点调度，但**提交侧**仍需防重复提交（与优化任务同一问题） |
| 审计 | 服务端一条链 | 服务端「提交」+ 作业侧「实读」两条链，必须对齐，否则「谁读了明文」答不上来 |
| 前置依赖 | 无 | 该 catalog 已配 `catalogs/{c}/execution-cluster`（复用与表优化任务同一份配置，**不引入第二套集群配置**） |
| 失败表现 | 扫描线程异常 → 直接 `FAILED` + `error_message` | 要回读 Spark 历史取错误，`error_message` 的形状和 A 不一样 |
| 格式覆盖 | 三格式都有 `PreviewTable` 实现 | 依赖 Spark 侧三格式 connector，Lance 要确认版本（§6.2 同一条差异） |

**默认 A，理由是它不新增读路径**：`METADATA_ONLY` 恒走 A（纯元数据读，下推没有任何收益，反而多一次作业调度开销）。只有 `SAMPLED` + 范围 ≥ 整个 catalog 时才值得切 B。

**这一默认值是全局的、可覆盖的，三层都配得上**——沿用本仓「代码默认值 / `application.yml` / k8s ConfigMap」三层一起改的口径（缺一层就是同镜像在不同环境行为不同）：

| 层 | 键 | 作用 |
|---|---|---|
| 全局默认 | `lakehouse.rest.sensitivity.scan.execution=IN_PROCESS` | 整个服务端的缺省执行位置 |
| catalog 覆盖 | `PUT /catalogs/{c}/sensitivity-settings` 的 `defaultExecution` | 某个大 catalog 想默认走 B，不必让所有人改请求体 |
| 单次覆盖 | `POST /sensitivity-scans` 请求体的 `execution` | 一次性的显式选择，优先级最高 |

三者是**同一份枚举的逐级覆盖**（请求 > catalog > 全局），不是三个独立开关——只配一层也能跑。切 B 时控制台必须在发起页显式提示「本次扫描将提交 Spark 作业，作业将以服务身份直读表数据」——B 的绕过点比 A 更难收口，不能静默切。

> 两条路都不是「更安全的那条」：A 的风险集中在服务端一条链上（好审计、但吞吐有天花板），B 的风险被摊到作业侧（吞吐够、但「谁读了明文」要拼两条审计链）。写清楚差异比选一个「正确的」重要——这正是 §6.2 对三格式的那条结论换个场景重演。

#### 5.6.2 触发方式：四种并存

| 触发 | 入口 | 带定时 | 范围 | 落地期 |
|---|---|---|---|---|
| **手动** | `POST /sensitivity-scans` → `202` + `scanId` | — | 任意（catalog / 库 / 表） | P0 |
| **生命周期钩子** | `ALTER TABLE ADD COLUMN` | — | 恒为**该表**，`METADATA_ONLY` | P2 |
| **定时 · 外部调度器**（推荐） | 外部系统（Airflow / k8s CronJob / 优化任务那套）调 `POST /sensitivity-scans` | cron | 逐调度项配 | P4 |
| **定时 · catalog 自建** | 服务端内轻量调度 | cron | 逐调度项配 | P4（可选） |

**推荐外部调度**，理由与 §5.6.1 的默认值同源：catalog 保持无状态最省事。多实例下的内建定时要么把 `@Scheduled` 变成 N 个副本跑 N 次，要么就得为它再配一套分布式租约——那是为「必须内跑」的优化任务付过的成本（见 `optimization-multi-instance-design.md`），调度本身不需要重复付。外部调度沿用 `POST /sensitivity-scans` 这一个入口，审计链也只有一条。

#### 5.6.3 两种落法的差别：要不要第 6 张表

选**外部调度**时，cron 由外部系统自己管，本方案**不建** `lakehouse_sensitivity_schedule` 表（实体仍是 §4 的五张，DDL 不变）。

选**catalog 自建**时才需要第 6 张表：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | bigint PK | 自增 |
| `name` | varchar(128) | 调度项名 |
| `cron` | varchar(64) | 5 或 6 段 cron（秒级可选） |
| `catalog_name` / `database_name` / `table_name` | varchar | 可空 = 上层范围，与 §4.2 同口径 |
| `mode` / `sample_size` / `execution` | varchar / int / varchar | 复用扫描参数，含 §5.6.1 的位置 |
| `enabled_flag` | boolean | 停用 |
| `last_run_at` / `next_run_at` | bigint | epoch 毫秒，`next_run_at` 由服务端算 |

唯一键 `(name)`。**不给它加 `last_scan_id`**：一次调度可能因范围变化对应多条扫描，用「扫描表里 `trigger_type=SCHEDULE` + `schedule_name`」回查更准，也避免两表互相更新。

#### 5.6.4 并发与互斥（两条路都要守）

| 约束 | 口径 |
|---|---|
| 同一张表 | 同时只允许一个 `RUNNING` 扫描（否则两条 finding 流互相洗状态）；重入返 `409` |
| 同一 catalog | 同时最多 `N` 个（**默认 2，可配**，见下）；超了排队而不是拒绝——定时任务批量触发时排队比让调度器重试更可控 |
| 与数据预览 | A 路线下扫描与预览**共用一个并发池**，扫描不应把预览饿死；扫描请求按较低优先级入池 |
| 与优化任务 | 数据上无互斥（读的是不同东西）；B 路线下扫描作业的并发上限**与优化批次各算各的**（见 §5.6.6），共用同一台执行集群但**不共用配额** |

**catalog 级并发默认 2，且是可配的**——三层配置，与 §5.6.1 的执行位置同一套口径：

| 层 | 键 | 默认 |
|---|---|---|
| 全局默认 | `lakehouse.rest.sensitivity.scan.max-concurrent` | `2` |
| catalog 覆盖 | `sensitivity-settings.maxConcurrentScans` | 继承全局 |
| 单次覆盖 | **不支持** | — |

为什么单次不能覆盖：它是**资源保护**而不是本次请求的参数。允许单次放开，等于「谁都能给自己提额」，那这道闸门就只剩字面意思。要调就调 catalog 级——那是一次治理决定、进审计。

为什么默认 2 而不是按 CPU 算：**这道闸门守的不是服务端自己的 CPU，而是存储侧被并发读的压力**（A 路线每条扫描会按列拉样本）。同一份存储被 `N` 条扫描同时打，`N` 越大越接近「用小文件随机读打满对象存储」。默认 2 与 `TablePreviewService.MAX_CONCURRENT_PER_PRINCIPAL` 同值不是巧合——**两条读路径争的是同一份后端带宽**，给它们同一个量级才不会出现「预览限 2、扫描不限」的失衡。

#### 5.6.5 `execution=SPARK` 的落法：复用表优化任务的提交链路

结论：**提交、探活、跟踪、取消这四段的机械部分应当直接复用**——「往哪个集群、用什么镜像、凭据从哪来、Job 建成什么形状」与「任务干什么」是两件事，`table-optimization.md` §4 已经按这条切开。**不能照搬的是任务的输入输出**：扫描拉的是「要扫的表 + 要用的探测器」，回的是「打码样本 + 命中计数」；优化任务拉的是「要压的表 + 压缩参数」，回的是「前后文件数」。

| 环节 | 复用程度 | 说明 |
|---|---|---|
| 执行集群配置与客户端 | **原样复用** | `resolve` / `clientFor` / `dispatchable` 全部按 catalog 生效；扫描不引入第二套「集群配置」概念。**但配额不共用**——见 §5.6.6 |
| 探活（五问） | **原样复用** | 它问的是「这个集群能不能收下并跑起一个 Job」，与 Job 内容无关；第 4 步 dry-run 用的仍是同一份报文构造 |
| k8s 报文骨架 | 复用骨架，换 `--scan-id` 与容器名 | 前缀 `paimon-scan-`、容器名 `scanner`；`backoffLimit=0`、`restartPolicy=Never`、`imagePullSecrets` / `serviceAccountName` 逐条照抄。**TTL 与 `jobTimeoutSeconds` 取扫描自己的批次策略**（§5.6.6 的 `scanJobTimeoutSeconds`），不读优化那组 |
| Spark 参数 | 复用注入位置，**取值独立** | 同一份「`extraJavaOptions` / `--conf` 注入点」的实现，但读的是扫描那一组 Spark 参数（§5.6.7 的 `scan_spark_conf`）——优化任务的参数改了不该悄悄改变扫描任务的资源诉求 |
| 提交器的幂等 / 状态折算 / 取消 | **原样复用** | 「先查同名再建」「先看 conditions 再看计数」「Background 删除」三条判据都不含优化语义 |
| 调度租约 | 复用模式，**新增一个通道** | `sensitivity.dispatch` 必须登记进通道清单，否则多副本下每台都会提交一遍（§5.6.2 已论证过内建定时的这份代价） |
| 回调客户端 | 复用形状，另开两个端点 | 令牌换 `X-Sensitivity-Scan-Token`（作用域 = 这一次扫描），端点换成「清单」与「结果」两个 |
| Spark 会话构建 | **抽公共类，不许复制** | 三套配置（Paimon 的 `data-token.enabled`、Iceberg 的 `X-Iceberg-Access-Delegation`、Lance 的 `/lance` + `parent` + `headers.Authorization`）与镜像里 arrow 的类路径排序都是**编译器看不见**的约定；复制一份就是下次升级 connector 时必分叉 |
| 镜像 | **同一个优化器镜像** | 已定：扫描入口是同一个 jar 里的另一个 main class（`SensitivityScannerJob`）。三种格式的运行时、Lance 的 arrow 类路径排序都白拿；单独打镜像省下的体积换不来那两处修复的分叉风险 |

**三处不能照搬的语义**（这才是本节的重点）：

1. **一个扫描 = 一个 Job，不要「组批」**。优化任务的批次是「N 张表的集合」，因为每张表的压缩独立且失败可分（`PARTIAL_SUCCESS` + 只重跑失败 item）。扫描没有这个对应物：§5.6.4 规定「同一张表同时只允许一个 `RUNNING` 扫描」，而组批会让两个调度周期各自抓走同一张表、落在两个 Job 里——互斥就被绕过了。表内并行由任务内部做（照优化任务那样逐表 `try/catch` + 逐表回调），不靠多开 Job。
2. **「没结果」必须上报成结果**。优化任务里「k8s 已终态但某张表没上报」按失败处理，因为没跑完的 compaction 就是没做完。扫描里「没上报」有两种含义——**扫了但没命中** 与 **压根没跑**——而 §13 的「覆盖率」正是靠这个区分才成立。因此任务必须对**每张表**回一条 item，哪怕 `findings=0`、哪怕该表被跳过；否则「扫完了没发现」与「漏扫」在控制台上长得一模一样。
3. **明文的出网口只允许一条**。优化任务全程只搬文件、不读内容，所以它的威胁模型里没有「任务把数据发出去」。扫描任务的收口要强一档：原始行**不出 Pod**，回调只带打码样本与命中率——§5.4 第 4 条在 B 路线下的重述是「不出 Pod」而不是「不出服务端」；并且**任务侧禁止把样本值写进日志**（优化任务的日志里可以有语句和指标，扫描的日志里不能有值）。

**三条由复用带来的、必须写清的推断**：

- **`execution=SPARK` 要 fail-closed**。给一个没配执行集群的 catalog 建 `execution=SPARK` 的扫描必须返 **400**，而不是静默退回 A——静默降级会让「我以为它在集群上跑」变成一条查不出来的行为，与 §5 那两处 fail-closed 同一口径。
- **探测器不在 Spark 里重写**。D1/D2 是元数据、不进 Spark；D3/D4/D5 需要值，但**只是把抽样的行拉回来、跑同一份 Java 打分器**。**不要**把正则与校验器翻译成 Spark SQL 表达式：那会造出第二份打分实现，§5.1 的权重与 §5.2 的公式立即分叉。抽样语句走 `TABLESAMPLE`、降级 `HEAD`，与 A 路线同一口径（§6.2）。
- **扫描配额与优化配额各算各的**（已定，见 §5.6.6）。`maxConcurrentJobs` 是优化批次的额度，扫描不借用它；扫描用自己那组配置的 `maxConcurrentScans`（默认 2，§5.6.4）计数。**代价是两侧可以同时把同一台集群放到 2N 个驱动**——这个风险不由「共用配额」消解（共用会让「优化占的额度」与「扫描占的额度」互相成为对方的噪声），而由 §5.6.6 末尾的**集群侧硬配额**兜底。

**状态机要补两态。** 走 `execution=SPARK` 后扫描不再从 `RUNNING` 开始：`POST /sensitivity-scans` 返 `202` 时是 `PENDING`，调度节拍提交成功才转 `RUNNING`，提交期间是 `SUBMITTING`，并需要一份与优化批次同形的「提交成功、回写前进程挂掉」回收（`reapStaleSubmissions`）。§4.2 的 `status` 取值随之扩充。

#### 5.6.6 执行集群配置：一张表，两组策略（表优化 / 敏感识别各一套）

已定：**扫描任务与优化任务共用同一份执行集群配置**（同一个 k8s、同一个镜像、同一份凭据、同一组探活结论），但**策略参数按任务类型分开**。因此 `lakehouse_catalog_execution_cluster` 在「连接与运行位置」（现有 ①②③ 段）之外，把批次策略扩成**两组并列**：

| 组 | 字段 | 消费方 |
|---|---|---|
| **表优化**（现有，保持不动） | `maxTablesPerJob` / `maxConcurrentJobs` / `jobTimeoutSeconds` | `OptimizationDispatcher` / `OptimizationBatchCreator` |
| **敏感识别**（新增） | `maxScanTablesPerJob` / `maxConcurrentScans` / `scanJobTimeoutSeconds` | `SensitivityScanner`（B 路线） |

**为什么不把两组并成一列带类型标记的子表**：它们是**两个固定的、字段集合已知的**策略组，不是一个会增长的列表。子表（`execution_cluster_policy(type, ...)`）会让「读一个策略」变成一次 join，并且 `type` 列一旦要校验取值域、又要为两种类型各写一遍约束——现在多出来的列宽与迁移成本，远小于为两个已知类型建一套 EAV 的复杂度。

**列名不能省掉类型前缀**（`maxTablesPerJob` vs `maxScanTablesPerJob`）：两条业务线读的是同一行，`maxTablesPerJob` 这个名字在扫描代码里会让人以为「扫描也受它约束」。前缀是**给下一个读代码的人**看的，不是给数据库看的。

**默认值与校验**：`maxScanTablesPerJob=200`（扫描的表通常比「一批待优化表」多得多，一个 catalog 几千张表是常态）、`maxConcurrentScans=2`（§5.6.4 已论证）、`scanJobTimeoutSeconds=3600`。区间与优化那组同形（1–500 / 1–50 / 5m–24h），超范围 400。

**这一扩列对状态机的影响**：`config_hash` 的算法与「改完没验证」的判据**不含批次策略**（现有实现如此）——新增的两组策略列同样不进 `config_hash`。这意味着**只改 `maxScanTablesPerJob` 不会把状态打回「待验证」**，这是对的：改一次并发数不需要重新证明「这个集群连得上」。

**两组合法但不能自相矛盾**：若某 catalog 的 `maxConcurrentScans` 与 `maxConcurrentJobs` 都配成 20，同一台集群可能同时收到 40 个 Spark 驱动。本方案**不做交叉校验**（两边都是合法值），而是在文档里点名这张账要由**集群侧的准入**兜（ResourceQuota / 调度器队列）。理由：服务端不知道那台集群的 CPU 总量，编一个「两边加起来不超过 X」的规则只会得到一个 X 猜错的系统——而 k8s 自己正好知道答案。

#### 5.6.7 两组策略里的 Spark 参数（默认注入，可逐 catalog 覆盖）

已定：**两类任务都能各自配 Spark 参数**，且默认就带上参——不配也得能跑，且跑得合理。参数存在同一行的两个 JSON 列：

| 组 | 列 | 典型键 |
|---|---|---|
| 表优化 | `optimization_spark_conf` | `spark.sql.shuffle.partitions`、`spark.driver.memory`、`spark.driver.cores`、`spark.executor.instances` … |
| 敏感识别 | `scan_spark_conf` | 同上 + `spark.sql.files.maxPartitionBytes`（按列分区读时控制单分区大小） |

**默认值（新建配置时预置，不是「空即无」）**——三类参数各给一个能跑起来的起点：

| 参数 | 默认 | 为什么是这个数 |
|---|---|---|
| `spark.driver.memory` | `4g` | Spark 发行版默认 1g，对「一次处理几十张表的 compaction」/「拉样本跑打分」都偏小；4g 是这两类负载的常见起点 |
| `spark.driver.cores` / `master` | `local[N]`，`N=4` | 容器就是 driver（`Dockerfile` 的 ENTRYPOINT 用 `--master local[*]`）。**注释里写清 `local[N]` 的 N 是「表内并行度」**：优化任务的横向扩展单位是 pod（一个批次一个 pod），单 pod 内 N 决定并发执行几张表的 IO |
| `spark.sql.shuffle.partitions` | `200`（Spark 默认）**但显式写出来** | 小数据量下 200 个 shuffle 分区是纯开销；写出来是为了让人看见它、能按表数调小。**不改成更小的默认值**：改了会让「按默认跑」的部署在两处行为不同 |
| `spark.executor.*` | **不配** | `local[*]` 模式下没有独立 executor，配了是死配置。只有将来改 `k8s://` 提交模式才需要——**留空并注明原因**，比配一组永不生效的值好 |
| `spark.sql.session.timeZone` | `UTC` | 这是**任务代码里钉死的**（见 `TableOptimizerJob.spark()`），不是可配项；列在这里是提醒：用户传的 `scan_spark_conf` **不得覆盖它**，否则 Iceberg 的 `older_than` 会按本机时区折算 |

**注入顺序与优先级**（三层，后者覆盖前者）：

1. **任务代码里的硬编码**（`timeZone=UTC`、`extraClassPath` 的 arrow 排序）——**不可覆盖**，用户传同名键时**忽略并告警**，不静默生效；
2. **`*_spark_conf` 里的键**——按 catalog 生效；
3. **`Dockerfile` 里已经写死的 `--conf`**（如 `spark.ui.enabled=false`）——它是镜像级约定，`*_spark_conf` 想改也改不动（不在同一层）。

**一个必须写死的边界**：`*_spark_conf` **不承载安全语义**。它允许调内存与并行度，但**不允许**塞 `spark.sql.catalog.*.token` / `storage.*` / `rest.auth.type` 这类**连接与认证**键——那些由服务端按本次扫描构造（§5.6.5 的「Spark 会话构建」）。不设这条，`scan_spark_conf` 就成了一条能改认证目标的旁路。实现上按**前缀白名单**过滤（`spark.driver.*` / `spark.executor.*` / `spark.sql.shuffle.*` / `spark.sql.files.*` / `spark.memory.*` / `spark.default.parallelism`），**白名单外一律丢弃 + 记审计**——用黑名单会漏（`spark.sql.catalog.*` 是通配前缀，黑名单写不全）。

## 6. 三格式支持（Paimon / Iceberg / Lance）

### 6.1 元数据层：天然统一

D1 / D2 只读 `lakehouse_table.schema_doc`（当前 schema 的 JSON 文档）里的列名、类型、注释——**与表格式无关**。Paimon、Iceberg 共用 `table_type` 标记，Lance 表同在这张实体里（`table_version` / `properties_json` 是 Lance 专属列）。

**兜底**：若某张 Lance 表 `schema_doc` 未落 schema（例如 register 进来的外部表还没刷新过元数据），扫描跳过该表并在 finding 里标 `METADATA_MISSING`，**不猜**。控制台的「覆盖率」把这类表单独计数，而不是算成「已扫描、无敏感列」。

### 6.2 数据层：能力差异要显式降级，不要假装一样

| 能力 | Paimon | Iceberg | Lance |
|---|---|---|---|
| `TABLESAMPLE (n PERCENT)` | 支持 | 支持 | **不支持** |
| `LIMIT` 取头 N 行 | 支持 | 支持 | 支持 |
| 列裁剪 | 支持 | 支持 | 支持 |

采样策略：优先 `TABLESAMPLE`；不支持时降级 `LIMIT`，并在 finding 上标 `sampling_mode=HEAD` + 置信度 `×0.85`——**头部数据偏斜是真实风险**（按时间分区的表头部全是最新一天），降级必须留痕，让人审时看得见。

> 这一条刻意不写成「三格式完全一致」。查询层的能力差异是客观存在的，把差异藏在「都支持」里，最后表现为「Lance 表识别率莫名偏低」，比显式降级难查得多。

### 6.3 为什么不把标签写回表属性

`DataPolicyEntity` 的类注释已经论证过一次：**表属性对所有客户端可见，会泄露「这张表被保护了、保护成什么样」**。敏感标签同理——`paimon.column.id_card.tag=PII.CN_ID_CARD` 等于给攻击者递了一张「哪列值得偷」的地图。

所以标签**只存 catalog 自有表**，通过 API 对外供给。若将来确需下推（Iceberg `properties` / Paimon `table options`），必须是**独立开关 + 默认关闭 + 只推脱敏后的占位值**，不进本方案的 P0–P3。

## 7. 传播：让「答案」自己往前走

### 7.1 沿血缘列边传播

数据从 `ods.user` 流到 `dwd.user_wide`，身份证列改名成了 `id_no_enc`——重新扫一遍要等下次扫描，而血缘边**已经是现成的**（`lineage_column_edge`）。按 `transform_kind` 分档：

| `transform_kind` | 是否继承 | 说明 |
|---|---|---|
| `DIRECT` | ✅ 继承，置信度 = 上游 × 0.9 | 直接列引用，语义不变 |
| `DERIVED` | ⚠️ 继承但标 `needs_review`，置信度 × 0.6 | `substr(id_card,1,6)`、`md5(id_card)` 仍可反推，但已变形 |
| `AGGREGATE` | ❌ 不继承，生成 `PENDING` 建议 | `count(id_card)` 不敏感，`max(salary)` 敏感——无法用规则区分，交给人 |
| `CONSTANT` / `UNRESOLVED` | ❌ 不传播 | 没有源列 / 解析不出源 |

实现要点：

- **幂等增量**：只处理 `last_seen` 之后有新活动的边，不每次全图重算；
- **防环**：血缘图可能有环（`docs/lineage-design.md` 已讨论过环），传播用 visited set；
- **深度上限默认 5 跳，可在 `application.yml` 配**（已定）。键是 `lakehouse.rest.sensitivity.propagation.max-depth`，默认 `5`；传给扫描/传播的请求体也能单次覆盖（与 §5.6.1 的执行位置同一套「全局默认 / 单次覆盖」口径），超过则停在 `PENDING` 建议——避免一条误标沿长链污染半张数仓。

  > 5 跳这个默认值本身是**折中而非最优**：深链数仓（`ods → dwd → dws → ads`，每层还可能有中间表）实际需要 8–10 跳才够，而每多一跳，误标的污染面翻一倍。所以它不是「调对一次就完了」的参数——**放在 yml 里就是要让人按自己的数仓层级调**，而不是让它藏在代码里被当成物理常数。调大它的同时应调小自动应用白名单（§5.3），两条一起动才安全。

### 7.2 人工否决优先于自动传播

这是整节最重要的一条：**被人工 `REJECTED` 的列，任何后续扫描与传播都不得再自动打标**。

否则会形成死循环——识别器认为是身份证 → 人审判定不是（比如那是「身份证后四位」的掩码列）→ 第二天重扫又变回敏感 → 人再拒一次。所以 `REJECTED` 要落成**永久抑制项**（按 `column_ref_hash + type_code` 记，带 `decided_by`），只在两类情况下失效：列被重命名（哈希变了，视为新列）或治理员显式解禁。

### 7.3 生命周期钩子

与 `DataPolicyLifecycleListener` 同一位置、同一套三条钩子（策略表已经踩过一遍，直接复用结论）：

| 事件 | 动作 |
|---|---|
| `DROP TABLE` | 删除该表全部列级标签与 finding |
| `RENAME` | 跟随改名（同时更新三段原名列与 `column_ref_hash`） |
| `CREATE` | 不继承任何东西（新表就是新表） |
| `ALTER TABLE ADD COLUMN` | 触发**该表**增量扫描；新列在分类前，控制台把它标成「未分类」并计入覆盖率缺口 |

## 8. 消费面

### 8.1 标签驱动脱敏（核心价值）

策略解析时，服务端把「标签」展开成「列的 mask」：

```text
masks = 显式 mask（lakehouse_column_mask，现状）
for tag in columnTags(表):
    for b in bindings(catalog, tag.tag_key, tag.tag_value):
        if tag.column 不在 masks:            # 显式优先
            masks[tag.column] = b.mask       # origin = TAG
        else:
            conflicts += (tag.column, b.tag_key, "explicit-wins")
```

三种结果都有明确归宿：

- **无显式 mask，有绑定** → 自动脱敏，**新增列被识别打标后自动纳入覆盖，不用改策略**；
- **有显式 mask，也有绑定** → 显式赢，冲突记进响应，控制台列出来（不静默吞掉）；
- **同列多个标签命中多个绑定** → 按 `level desc, priority desc, tag_key asc` 取一条，其余记冲突。

**引擎侧零改动**：`resolve` 下发的仍然是「列名 → mask」的既有形状，Spark 侧 `PolicyProjectRule` / `PolicyHiddenColumnsRule` 不知道「标签」这回事。

**缓存**：策略下发有 60 秒 TTL，打标后最多 60 秒生效。另需在标签变更时**主动失效**对应表的策略缓存（与 `StorageCredentialCache.invalidateCatalog` 同构），否则「刚确认完、页面上还是明文」会被当成 bug 报上来。

### 8.2 与既有策略的优先级宣告（必须写死在文档里）

```
显式 mask  >  标签绑定（按 level / priority）  >  表级默认（无）
```

同列「mask」与「hidden」仍然互斥（`DataPolicyEntity` 既有约束），标签展开出的 mask 若撞上 `hidden_json` 里的同名列，**拒绝展开并报冲突**，不擅自决定谁生效。

### 8.3 与数据质量的协同

DQ 的规则可以按标签匹配：「所有 `L3+` 列必须有 completeness 规则」「`PII.*` 列禁止出现在明细视图」。这让 `docs/data-quality-design.md` 的规则从「按列名硬绑」升级成「按分类套用」，两份设计共用同一份标签真相源。

### 8.4 对外查询

`GET /api/catalogs/{c}/column-tags?level=L4&table=` 供数据地图、审批流、上线检查消费。事件 `column.tag.changed` 供下游订阅（含 before/after 与操作者）。

## 9. API 契约

沿用 management 风格（`/api/catalogs/{c}/...`），全部需要登记到运行时端点计数（**catalog 端点只加控制器判权不够**，`AuthorizationInterceptor` 先执行、`resolve()` 返 null 即 403；守它的是「运行时端点数 == 登记数」）。

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/sensitive-types` | 类型清单（内置 + 自定义） |
| `POST` / `PUT` / `DELETE` | `/sensitive-types[/{code}]` | 自定义类型；内置只可停用，删除返 409 |
| `POST` | `/sensitivity-scans` | 发起扫描 → `202` + `scanId`（请求体带 `execution` / `mode` / 范围；`trigger_type=MANUAL`）。**`execution=SPARK` 且该 catalog 不可派发 → `400`**（fail-closed，不静默退回 A，§5.6.5） |
| `GET` | `/sensitivity-scans?status=&scope=&triggerType=` | 扫描历史（含 `execution` 一列，能一眼看出哪次下了 Spark） |
| `GET` | `/sensitivity-scans/{id}` | 单次扫描详情与计数（B 路线含 `executionNamespace` / `executionResource` 与逐表 `tablesFailed`） |
| `GET` | `/sensitivity-scans/{id}/submission` | **仅 B 路线**：扫描作业回调拉取的执行清单（要扫的表 + 生效探测器配置 + 采样参数）。令牌走 `X-Sensitivity-Scan-Token`，作用域 = 这一次扫描；与优化任务的 `submission` 端点同形 |
| `POST` | `/sensitivity-scans/{id}/items` | **仅 B 路线**：作业逐表回报（`{table, status, findings, maskedSamples, metrics}`）——**没命中也要回**，否则「扫完没发现」与「漏扫」不可分（§5.6.5） |
| `POST` | `/sensitivity-scans/{id}:cancel` | 取消（RUNNING 才有意义；`execution=SPARK` 时同时停作业——复用 `OptimizationJobSubmitter#cancel` 的 Background 删除语义） |
| `GET` | `/sensitivity-findings?state=PENDING&table=&type=` | 人审队列 |
| `POST` | `/sensitivity-findings:decide` | 批量确认/拒绝（`{ids, decision, note}`）；确认即落 tag |
| `GET` | `/column-tags?table=&tagKey=&level=` | 标签查询（对外主接口） |
| `PUT` | `/column-tags` | 手工打标（upsert，带 `entityVersion` 做 409） |
| `DELETE` | `/column-tags/{id}` | 删标签（**同时写抑制项**，否则下次扫描复活） |
| `GET` / `PUT` / `DELETE` | `/tag-mask-bindings[/{id}]` | 标签 → 脱敏绑定 |
| `GET` / `PUT` | `/sensitivity-settings` | **catalog 级治理设置**：`defaultExecution`（§5.6.1）、`maxConcurrentScans`（§5.6.4）、`propagationMaxDepth`（§7.1）。`PUT` 走乐观锁，且**变更进审计**——它改的是「这个 catalog 的扫描怎么跑」，与改绑定同级 |
| `GET` / `PUT` / `DELETE` | `/sensitivity-schedules[/{id}]` | **仅当选 catalog 自建调度时存在**（§5.6.3）；外部调度路线不注册这三个端点，运行时端点计数也不含它们 |

执行集群的两组策略与 Spark 参数挂在**既有**的 `PUT /catalogs/{c}/execution-cluster` 上（`maxScanTablesPerJob` / `maxConcurrentScans` / `scanJobTimeoutSeconds` / `optimizationSparkConf` / `scanSparkConf`，§5.6.6、§5.6.7），**不新增端点**——它们与「连哪个集群」是同一份配置的不同段，拆成两个端点会让「保存了连接但没保存策略」变成一个用户看得见、却说不清为什么的状态。

> 「是否自建调度」是**部署期决定**（§5.6.3），因此它决定了端点数——这与本仓库「运行时端点数 == 登记数」的守卫直接相关：切换路线时登记表要同步增删三个端点，漏改会让守卫直接红。

事件：`sensitivity.scan.started` / `.completed` / `.failed`、`sensitivity.finding.decided`、`column.tag.changed`、`sensitivity.binding.changed`。扫描事件的 payload 带 `execution` 与 `triggerType`——「谁在什么时候读了明文」要能仅凭事件流回答，不必回查扫描表。

## 10. 权限与可见性

| 权限点 | 覆盖动作 | 为什么独立 |
|---|---|---|
| `CATALOG:SENSITIVE_SCAN` | 发起扫描（含读明文样本） | 采样是「合法高权限读」，不能挂在 `TABLE:SELECT` 上——否则任何能查表的人都能让服务端替他读明文并给出打码样本 |
| `CATALOG:SENSITIVE_ADMIN` | 管类型 / 改绑定 / 批量决策 | 改绑定 = 改脱敏行为，与 `TABLE:ALTER` 同级 |
| `TABLE:SELECT` | 看该表的**标签存在性** | 只知道「被保护了」，不知道「保护的是什么类型」 |
| `CATALOG:SENSITIVE_READ` | 看完整标签（类型 + 级别 + 置信度） | 标签本身是敏感信息，见下 |

**标签分级可见**：`PII.CN_ID_CARD` 这个字符串直接告诉你「这列是身份证」——对普通使用者只显示「敏感（已保护）」，完整类型与级别需 `CATALOG:SENSITIVE_READ`。与 `DataPolicyEntity`「策略不是表属性」同一条思路：**保护动作的存在本身也是信息**。

## 11. 控制台

八屏，见 [`sensitive-data-mockup.html`](sensitive-data-mockup.html)：

| 屏 | `#hash` | 内容 |
|---|---|---|
| 总览 | `#overview` | 覆盖率 / 敏感列数 / 待确认 / 自动应用率；分级分布；类别 Top；最近扫描（含 `execution` 与 `triggerType` 两列） |
| 发起扫描 | `#scan` | 范围级联（catalog → 库 → 表）、**执行位置（进程内 / 下推 Spark，带前置依赖与代价对比）**、模式（仅元数据 / 采样）、采样量、**成本与权限提示**（「将读取 N 列明文样本」；下推时额外提示「将提交 Spark 作业」） |
| 识别结果审核 | `#findings` | 待确认队列：列引用 + 置信度条 + 命中探测器 + **打码样本** + 右侧「为什么这么判」；批量确认/拒绝 |
| 标签清单 | `#tags` | 列级标签表：来源徽标（自动/人工/继承）、级别、置信度、时间；行内改/删；按表汇总 |
| **类型与探测器配置** | `#types` | 类型清单 + **新建/编辑类型**：`type_code` / 类别 / 分级 / `auto_apply` 开关 / 阈值 / **五层探测器逐层编辑**（列名正则、值正则 + 校验器、字典、权重）+ 权重实时归一化预览。内置类型只可停用、每个字段失焦前是只读态 |
| **扫描任务与调度** | `#schedules` | 调度项列表（cron / 范围 / 模式 / 执行位置 / 上次·下次运行 / 启停）+ **「下推 Spark 时服务端把这一发变成什么」对照卡**（复用表优化任务链路的哪几段、哪几条语义不能照搬，§5.6.5）；外部调度路线下此屏退化为**只读的「调度建议」页**（展示一行可直接复制的 `curl POST /sensitivity-scans`，供 Airflow / CronJob 用），不出现新建按钮 |
| 标签驱动脱敏 | `#bindings` | 绑定列表 + 「本绑定覆盖 N 列」预估 + 冲突提示（显式 mask 覆盖了几列） |
| 表详情联动 | `#table` | 表详情的「敏感数据」页签：列 → 标签徽标、级别、来源；未分类列显式标灰 |

`#types` 与 `#schedules` 是本轮新增：前者补上「识别规则怎么配」（此前原型只有类型**清单**、没有**编辑**），后者补上 §5.6 的触发方式落地界面。两屏都随触发路线的选择而改变形态——这正是文档与原型要对齐的地方，不能只画一条路。

## 12. 分期与验收

| 期 | 范围 | 验收断言 |
|---|---|---|
| **P0** | 五实体 + 内置类型 seed + 类型/探测器配置端点与控制台 `#types` + D1/D2 元数据扫描 + 手工打标 + `/column-tags` 只读 + 控制台 `#overview` / `#tags` + `/sensitivity-settings`（catalog 级设置含 `defaultExecution`） | 扫描 100 张表 < 30 秒；同一列重扫不产生重复 tag；内置类型不可删（409）；**改一个内置类型的探测器权重后，下一轮扫描的判定随之改变且变更进审计**；**`defaultExecution` 与 `maxConcurrentScans` 三层覆盖生效（请求 > catalog > 全局）** |
| **P1** | D3 采样探测 + 校验器 + 置信度分档 + 人审队列 + 自动应用闸门 | 身份证列在 500 条造数上命中率 100%、误报 0；`PERSON_NAME` 即使置信度 0.99 也进 PENDING |
| **P2** | 血缘传播 + 生命周期钩子 + 增量扫描（`ALTER ADD COLUMN`）；`execution` 落库，扫描历史可见位置；`propagationMaxDepth` 可配 | `DIRECT` 边一轮传播后下游出 tag；`AGGREGATE` 边不产生 tag；`REJECTED` 列重扫不复活；生命周期钩子产生的扫描 `trigger_type=LIFECYCLE`；**`max-depth=2` 时第 3 跳停在 `PENDING`** |
| **P3** | 标签驱动脱敏（resolve 展开）+ 冲突报告 + 缓存主动失效 | 打标后 ≤60 秒策略生效；显式 mask 优先且冲突可见；三格式（Paimon/Iceberg/Lance）同表同规则结果一致 |
| **P4** | D4 字典 / D5 统计特征 + 触发方式补全（`#schedules`：外部调度建议页，或自建调度 + `/sensitivity-schedules` 三端点）+ `execution=SPARK` 下推（复用表优化任务的提交/探活/跟踪/取消链路，§5.6.5；执行集群扩两组策略与两组 Spark 参数，§5.6.6 / §5.6.7）+ 对外事件订阅 | 外部调度按 cron 触发一次扫描且只触发一次；同表并发重入返 409；SPARK 路线下扫描事件的 `execution` 为 `SPARK` 且两条审计链可按 `scanId` 拼上；**给不可派发的 catalog 建 `execution=SPARK` 返 400**；**扫了 100 张表、其中 3 张读失败时，`tables_scanned=100` 且 `tables_failed=3`（不是「扫了 97 张」）**；**作业日志里不含任何原始列值**（抽查一次真实扫描的 Pod 日志）；**`scanSparkConf` 里塞 `spark.sql.catalog.*.token` 被丢弃并记审计，且扫描仍用服务端构造的会话** |

> `execution` 字段 P2 就落库（成本极低、事后补埋点会丢历史），但 `SPARK` 这条路到 P4 才通。P2 阶段该列恒为 `IN_PROCESS`——**先有列再有值，比事后给存量行补一个说不清的默认值干净**。同理 §4.2 的那组 B 专属列（Job 坐标 / 令牌哈希 / 提交代数）也在 P2 一次建好：`status` 的取值域一旦要扩到 `PENDING` / `SUBMITTING`，改的是列宽与所有既有读取点，不如一次到位。

## 13. 风险与未决

| 风险 | 处置 |
|---|---|
| **误报 → 误脱敏** | 自动应用白名单（§5.3）+ 阈值双开关 + 标签可撤销 + 全程审计；宁可多一条待审，不多一条静默 mask |
| **漏报** | 不承诺「扫完即全覆盖」，控制台报**覆盖率**而非「已完成」；未分类列显式标灰 |
| **采样偏斜** | `TABLESAMPLE` 优先，降级 `HEAD` 必须标 `sampling_mode` 并打折置信度 |
| **识别系统自身泄漏** | 只存打码样本、采样单独授权、原始行不出服务端（§5.4）；**下推 Spark 时缺口收窄到「原始行不出 Pod」**——值层抽样在作业内跑、只回落打码样本与命中率，且任务侧禁止把样本值写进日志（§5.6.5） |
| **下推路线的「假覆盖」** | 逐表回调里「没命中也要回」，把「扫完没发现」与「漏扫」分开（§5.6.5）；`tables_failed` 与 `tables_scanned` 分开计数，覆盖率不会把读失败的表算成已覆盖 |
| **标签本身就敏感** | 分级可见性（§10） |
| **调度重复触发** | 外部调度器负责「只触发一次」；服务端再加同表并发闸门（§5.6.4）兜底，两者不互相假设 |
| **密文 / 哈希列识别不出** | 明确口径：识别的是**明文形态**；`md5(...)` 这类列靠血缘 `DERIVED` 传播兜，而不是靠值匹配 |
| **标签行数爆炸** | 只为命中的列落 tag（不是「每列 × 每类型」）；控制台强制分页 + 按 catalog 过滤 |
| **内置类型与用户自定义冲突** | `type_code` 唯一；内置 seed 用 upsert 且**不覆盖**用户改动过的字段 |
| **两组并发配额叠加上限** | 扫描与优化**各算各的**（§5.6.6）：同一 catalog 可能同时收到 `maxConcurrentScans + maxConcurrentJobs` 个驱动。本方案**不做交叉校验**（编一个「加起来不超过 X」只会得到一个 X 猜错的系统），由集群侧 ResourceQuota / 调度器队列兜——**部署时这份配额必须显式设**，空着就是敞口 |
| **`*_sparkConf` 成为改认证目标的旁路** | 按**前缀白名单**过滤（§5.6.7），白名单外一律丢弃 + 记审计；连接与认证键只能由服务端构造，用户传了不生效 |

**已拍板（本轮六条）**：

1. 下推路线用**同一个优化器镜像**（同一 jar 的另一个 main class `SensitivityScannerJob`），不单独打镜像——白拿三格式运行时与 Lance 的 arrow 排序修复（§5.6.5）。
2. 下推路线的作业**不与优化批次共用** `maxConcurrentJobs`：两组配额各算各的，扫描用自己的 `maxConcurrentScans`（§5.6.4、§5.6.6）；叠加风险由集群侧配额兜。
3. catalog 级并发**默认 2，可配**（全局默认 / catalog 覆盖两层，不支持单次覆盖）（§5.6.4）。
4. 默认执行位置**定 A（进程内）**，且**全局可切**——三层覆盖：全局默认 < catalog 的 `defaultExecution` < 单次请求（§5.6.1）。
5. 血缘传播默认深度 **5 跳，支持在 `application.yml` 配**（`lakehouse.rest.sensitivity.propagation.max-depth`），也可单次覆盖（§7.1）。
6. 分级**对齐国标口径**：`L1–L4` 对应《网络数据分类分级指引》1–4 级（GB/T 43697-2024 均属一般数据）；`CORE` / `IMPORTANT` 两档只人工打、不自动识别（§5.5）。

**仍未决**：

1. 采样读是否一律走 `TablePreviewService`（安全复用但受其限额约束），还是给扫描器开独立只读通道（更快但要单独审计）？——**注意这条与 §5.6.1 是关联的**：若「独立只读通道」在服务端进程内，仍属 A 路线；只有把读放到 Spark 作业里才是 B。
2. 表级标签（`column_name=''`）是否要参与策略展开？本文口径是**不参与**（只用于检索与粗判），需确认。
3. §5.6.3 的调度选**外部**（不建表、不注册三端点）还是**自建**（建第 6 张表、端点数 +3）？本文倾向外部——但它会决定「运行时端点登记数」，是会影响守卫脚本的硬选择。
4. §5.6.6 的**两组策略放在同一行**（本稿）还是各自建独立表（`optimization_policy` / `scan_policy`）？同一行的代价是「改扫描配额的人要看得到优化那几列」，独立表的代价是 join 与两套乐观锁。本稿选同一行——因为读它们的代码本来就都要先拿到这一行。
5. §5.6.7 的 Spark 参数**默认值（4g / local[4] / 显式 200 分区）**是否按你们的集群资源再调一轮？这三个数只影响性能不影响正确性，但会决定「照默认跑」的部署是不是合理起点。
