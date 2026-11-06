# 敏感数据识别与分类 · 设计方案

> **状态：P0 / P1 已实现，P2–P4 为设计**。已实现的落点：P0 = 五实体 + 内置类型 seed + 类型/探测器配置 + D1/D2 元数据扫描 + 手工打标 + 控制台六屏与表详情页签；P1 = D3 采样探测（走预览那条只读通道）+ 五个校验器 + 值层打分 + 自动应用闸门 + 人审 `decide` + 第 7 张表（抑制项，V4）+ 控制台 `#findings` 的闭环与「已否决」区块。代码里没有的部分会显式报错而不是静默降级（`execution=SPARK` → 400/501；`D4`/`D5` 层恒 0 分）。
> **§13 的十一条已全部拍板，无未决项。** 本文描述的是**拍板后的目标形态**：其中「调度自建」（第 9 条）与「Spark 默认 `1g`/`local[1]`」（第 11 条）是更早一轮才定的，与更早版本的口径相反——读到 §5.6.2 / §5.6.3 / §5.6.7 / §9 / §11 时以本稿为准。
> 配套交互原型：[`sensitive-data-mockup.html`](sensitive-data-mockup.html)（八画面；`#schedules` 已按自建 CRUD 呈现）。

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

七张新表，全部沿用既有约定：`AuditedEntity` 审计列、`entity_version` 乐观并发、全名摘要列做唯一键（理由见 §4.8）。

> **七张里已落库六张**：V3 建的五张（§4.1–§4.5）+ V4 建的抑制项表（§4.7）。第七张 `lakehouse_sensitivity_schedule`（§4.6）是拍板「调度自建」之后才进数据模型的，属 **P4**，还没建——`EXPECTED_TABLES` 因此是 **35**（29 + 5 + 1），**别再按 34 写**。
>
> **抑制项为什么必须单独一张表（P1 新增 §4.7）**：它是「这列**不是**这个类型」的断言，键是 `(column_ref_hash, type_code)`；而 `lakehouse_column_tag` 记的是「这列**是**什么」，键是 `(column_ref_hash, tag_key)`。两者的生命周期也不同：标签删掉可以重建，而「谁在什么时候否掉、谁又在什么时候解禁」删掉就再也拼不出来。并进标签表会让一次驳回要么被标签的唯一键挡住，要么在删标签时被一起删掉。

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

### 4.6 `lakehouse_sensitivity_schedule` —— 定时扫描的调度项

**本轮已拍板：调度自建，且与表优化的调度方案同形**（§5.6.3）。因此这张表不是「选了自建才建」的可选项，而是数据模型的一部分。

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | bigint PK | 自增 |
| `name` | varchar(128) unique | 调度项名 |
| `cron` | varchar(64) | **直接存 5 或 6 段 cron 表达式**（秒级可选），不走表优化那套「分钟粒度 interval」——§5.6.3 说明了为什么这两者不能共用一列 |
| `catalog_name` / `database_name` / `table_name` | varchar | 可空 = 上层范围，与 §5.6.1 的三段范围同口径；**mutually 递进**（`table` 有值则 `database` 必须有值） |
| `mode` / `sample_size` / `execution` | varchar / int / varchar | 复用扫描参数，含 §5.6.1 的执行位置 |
| `enabled_flag` | boolean | 停用；停用后不再出计划，但历史扫描按 `schedule_name` 仍可查 |
| `last_run_at` / `next_run_at` | bigint | epoch 毫秒，`next_run_at` 由服务端算——**这是抢占的 CAS 条件**，与表优化的 `next_run_at` 同一手法 |

唯一键 `(name)`。**不给它加 `last_scan_id`**：一次调度可能因范围变化对应多条扫描，用「扫描表里 `trigger_type=SCHEDULE` + `schedule_name`」回查更准，也避免两表互相更新（与 §4.2 的 `schedule_name` 那一列成对）。

>`next_run_at` 是**抢占条件而不是展示字段**：多副本下每个实例都算同一个「到期」，靠 `UPDATE … WHERE next_run_at = <读到的值>` 的**条件更新**只让一方拿到推进权（与表优化那条完全同形）。因此它必须与 `entity_version` 分开——`entity_version` 是给**人工编辑**的乐观锁（改 cron 撞车返 409），`next_run_at` 是给**调度器**的抢占令牌（改 cron 时不该让调度器以为自己撞车了）。两者在一张表上各管一段，写成同一个字段会让「编辑调度项」与「调度节拍」互相干扰。

### 4.7 `lakehouse_sensitivity_suppression` —— 永久抑制项（P1，V4）

人审否决与「删标签」两条路径写的是同一张表（§7.2）：键是 `(column_ref_hash, type_code)`，语义是「这一列**不是**这个类型，别再提」。

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | bigint PK | 自增 |
| `catalog_name` / `database_name` / `table_name` / `column_name` | varchar | 库 / 表 / 列名原样存，供展示与按范围回查（与标签表同口径，不靠哈希反解） |
| `column_ref_hash` | varchar(64) | 全名 SHA-256，唯一键的一半 |
| `type_code` | varchar(64) | 被否决的敏感类型，唯一键的另一半 |
| `reason` | varchar(512) | 否决理由。人审驳回取用户填写的那句；删标签写成 `column tag deleted by <主体>`——**断言要有人负责** |
| `decided_by` / `decided_at` | varchar(255) / bigint | 谁在什么时候否掉 |
| `finding_id` | bigint | 溯源那一条被驳回的 finding（删标签路径为空） |
| `enabled_flag` | tinyint(1) | **解禁是置 false 而不是删行**：删掉就丢了「曾经否过、后来改主意」这段历史，界面也没法显示「已解除」 |

失效只有两条路：列改名（哈希变了，旧抑制项自然不再命中）或治理员显式解禁（`DELETE /sensitivity-suppressions/{id}`）。**解禁不删行、也不触发立刻重扫**——点的是「解除抑制」，不是「立刻读这张表」；下一轮扫描才会重新判。

### 4.8 主键与索引取舍

列级全名 `catalog.database.table.column` 在 utf8mb4 下 `(64+255+512+255)×4 = 4344` 字节，**远超 MySQL 单索引 3072 上限**。沿用 `DataPolicyEntity` 的做法：`column_ref_hash`（SHA-256，定长 64）做唯一键，三段 + 列名原名列只用于展示与过滤。

写入方（服务层）负责保持哈希与原名列一致；改名钩子同时更新两处。**漂移只会来自绕过服务层的写法**——与策略表现有的口径一致，不引入新机制。

### 4.9 DDL 示意（MySQL，实际由 `gen-mysql-ddl.sh` 生成）

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

**关于「绕过脱敏去采样」**：这不是漏洞，而是这个能力的必要前提——但必须收口。**已拍板：A 路线一律走数据预览那条读通道，不给扫描器开第二条只读入口**（见下方第 1 条），四个收口点是：

1. **只用内部只读通道**：复用 `TablePreviewService` 的读路径与其 `PreviewTableFactory` 按格式分派（`PreviewTable` / `PreviewLimits`），**不新开一条绕过策略的 SQL 入口**。这条**已定为口径**，不再作为「快 vs 稳」的取舍项——独立通道能换来吞吐，但代价是「谁读了明文」多一条只有自己知道的读路径，而扫描的吞吐问题在 B 路线（§5.6.1）另有更合适的解法；
2. 只读待判列，不 `SELECT *`；
3. 权限点独立于 `SELECT`，且在控制台发起扫描时显示「本次将读取 N 列明文样本」的显式提示；
4. 采样结果**不返回给调用方**——只返回统计量与打码样本，原始行不出服务端。

> **复用读通道的代价要写在这里，不能只写好处**：A 路线下扫描与预览**共用同一个并发池**（`MAX_CONCURRENT_PER_PRINCIPAL = 2`，§5.6.4 的 `max-concurrent` 取同值正是为此）。因此「大家同时在预览表」时扫描会排队，反之亦然。这是有意的：两条路径争的是同一份存储带宽，各自单开一把闸门才会出现「预览限 2、扫描不限」的失衡。

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

**已拍板：表级标签不参与策略展开**（只用于检索与分级可见性）。这条要在 §8.1 的 resolve 里硬性排除，理由是**两者的作用面不同**：

- 表级标签回答的是「**这张表整体算几级**」，是一个给目录检索与上线检查看的**汇总结论**；
- 策略展开要回答的是「**哪一列**该怎么脱敏」，需要的是**列**的来历。

把表级标签也喂给展开会在两处出错：① 表级标签的 `tag_key` 来自 `max(level)` 的聚合，**没有对应的列**，展开时找不到要遮的列名；② 一旦给表级标签也配一条 binding，就会命中这张表的**所有列**——「因为整表是 L4 所以把所有列都 mask 掉」，这不是本方案的意思，也不是任何治理员的意思。所以表级标签走的是**检索面**（`#tags` 的「按表汇总」、`#overview` 的分级分布），不走**执行面**。

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
| **手动** | `POST /sensitivity-scans` → `202` + `scanId` | — | 任意（catalog / 库 / 表） | P0（已实现） |
| **生命周期钩子** | `ALTER TABLE ADD COLUMN` | — | 恒为**该表**，`METADATA_ONLY` | P2 |
| **定时 · catalog 自建**（**已拍板**） | 服务端调度节拍读 `lakehouse_sensitivity_schedule` 的 `next_run_at` | cron | 逐调度项配 | P4 |
| ~~定时 · 外部调度器~~ | ~~外部系统（Airflow / k8s CronJob）调 `POST /sensitivity-scans`~~ | cron | 逐调度项配 | **已否决，见下** |

**已拍板：调度自建，与表优化的调度方案同形**（不选外部调度器）。这一条**推翻了本稿此前的「推荐外部」**，改的理由不是「内建更好」，而是**它要付的那笔成本已经付过了**：

- 原稿的顾虑是「多实例下 `@Scheduled` 要么跑 N 遍、要么再配一套分布式租约」。但**表优化的调度已经把这套租约做完了**：`lakehouse_scheduler_lease`（通道名硬编码在 `SchedulerLease.CHANNELS`）+ `next_run_at` 条件更新抢占 + `OptimizationSchedulingConfig` 的 `@EnableScheduling` 节拍。自建调度**复用这三样**即可，`sensitivity.scan` 只是往通道清单里加**一个名字**（§5.6.5 的「调度租约」那一行），不是新造一套机制。
- 反过来，外部调度器那条路**在「谁在什么时候读了明文」上多一个说不清的环节**：cron 在别人手里、`triggered_by` 只能记成「那个 Airflow 账号」，控制台 `#schedules` 也永远只能是一张只读建议页。**自建之后，调度项本身成为一个可审计、可停启、能在控制台上被看见的对象**——这与本方案「治理动作都要有主人」的口径一致。
- **代价（接受）**：多一张表、**登记表 +5 条**（`(方法, 路径)` 计数：`GET`/`POST /sensitivity-schedules` + `GET`/`PUT`/`DELETE /sensitivity-schedules/{id}`——注意是 **5 不是 3**，本仓库的登记表按方法算，别按「三个路径」数），要同步进守卫脚本（§9 末段）。以及自建调度器**必须自己保证「只触发一次」**——但这不是新增风险：`next_run_at` 条件更新与租约本来就是同一件事的两道，§5.6.4 的同表并发闸门照旧兜底。

> **与表优化「调度间隔」不共用的地方**：表优化的调度粒度是 `schedule_interval_seconds`（默认 3600，对齐 DLF 的 compaction 间隔），**是「隔多久看一次」而不是 cron 表达式**；扫描这边存的是 cron（5/6 段，秒级可选）。两者都是「到点了打一发」，但**不能把 interval 直接当 cron 用**——`schedule_interval_seconds=3600` 换算成 cron 是 `0 0 * * * *`，看起来对，可一旦用户想写 `*/30 * * * *`（每 30 分钟）或 `0 3 * * *`（每天 3 点），interval 那个模型就表达不了「凌晨 3 点」这种语义。所以 §4.6 那列直接叫 `cron`，不叫 `interval`。

#### 5.6.3 已定：自建调度（落第 6 张表）

**本轮拍板：调度自建，与表优化的调度方案同形**，因此**建** `lakehouse_sensitivity_schedule` 表（§4.6 六张新表之一），字段见该节。外部调度器那条路**否决**，理由见 §5.6.2。

**「与表优化方案一致」具体指哪三样**——这是本条约定的全部内容，不要多抄也不要少抄：

| 机制 | 表优化那边 | 敏感识别这边 | 一致？ |
|---|---|---|---|
| 调度单写者 | `lakehouse_scheduler_lease`，两个通道：`optimization.scan`（出批节拍）/ `optimization.dispatch`（提交作业） | 同形两个通道：**`sensitivity.scan`**（调度节拍）/ **`sensitivity.dispatch`**（B 路线提交作业），登记进 `SchedulerLease.CHANNELS` | ✅ 同表、同 API（`tryAcquire` / `renew` / `release`） |
| 到点抢占 | `lakehouse_table_optimization.next_run_at` 条件更新 | `lakehouse_sensitivity_schedule.next_run_at` 条件更新 | ✅ 同手法（**不是**新加分布式锁） |
| 节拍来源 | `@EnableScheduling`（`OptimizationSchedulingConfig`，可按配置关掉） | 复用同一个节拍（不新开一个 `SchedulingConfig`——两个 `@EnableScheduling` 没有意义，同一个开关关掉的是「服务端有没有定时任务」这件事本身） | ✅ 同一处 |

两个通道**都要**，因为是两个阶段：**`sensitivity.scan` 决定「这一拍谁去读调度表并发起扫描」**（A 路线在这一步就把扫描跑掉了；B 路线只落一行 `PENDING`），**`sensitivity.dispatch` 决定「谁去把 `PENDING` 提交成 Job」**。与表优化的 `optimization.scan` / `optimization.dispatch` 一一对应。只做第一个的话，B 路线下每台实例都会各自提交一遍同一批 `PENDING` 扫描——这正是多副本下最典型的重复任务。

三样都对上之后，「自建」的增量成本就只剩**一张表 + 5 条端点登记 + 两个租约通道名**，而 §5.6.2 说的那份收益（调度项可审计、可停启、在控制台上看得见）是白拿的。

> **一个必须记下的坑**：`SchedulerLease.CHANNELS` 里**已经**有一个通道叫 **`optimization.scan`**——它不是敏感识别的扫描，是表优化那条「扫描哪些表该入批」的节拍。新增的这个通道名**不要**叫 `scan`，用 **`sensitivity.scan`**；两者混名会让「停掉优化扫描的租约」变成一件说不清影响面的事。

唯一键 `(name)`；**不给它加 `last_scan_id`**：一次调度可能因范围变化对应多条扫描，用「扫描表里 `trigger_type=SCHEDULE` + `schedule_name`」回查更准，也避免两表互相更新。

#### 5.6.4 并发与互斥（两条路都要守）

| 约束 | 口径 |
|---|---|
| 同一张表 | 同时只允许一个 `RUNNING` 扫描（否则两条 finding 流互相洗状态）；重入返 `409` |
| 同一 catalog | 同时最多 `N` 个（**默认 2，可配**，见下）；超了排队而不是拒绝——定时任务批量触发时排队比让调度器重试更可控。**这一条只数 A 路线（进程内）的扫描**：B 路线下推的作业在集群上跑，它数的是执行集群那把 `maxConcurrentJobs`（见下与 §5.6.6） |
| 与数据预览 | A 路线下扫描与预览**共用一个并发池**，扫描不应把预览饿死；扫描请求按较低优先级入池 |
| 与优化任务 | 数据上无互斥（读的是不同东西）；B 路线下扫描作业的并发上限**与优化批次共用同一个 `maxConcurrentJobs`**（见 §5.6.6）——共用同一台执行集群、也就是同一份算力，因此**共用同一把闸门** |

**catalog 级并发默认 2，且是可配的**——三层配置，与 §5.6.1 的执行位置同一套口径：

| 层 | 键 | 默认 |
|---|---|---|
| 全局默认 | `lakehouse.rest.sensitivity.scan.max-concurrent` | `2` |
| catalog 覆盖 | `sensitivity-settings.maxConcurrentScans` | 继承全局 |

> ⚠️ 这个名字（`maxConcurrentScans`）**指的是 A 路线（进程内）的扫描并发**——守存储侧并发读的那道闸门。它与执行集群上那把 `maxConcurrentJobs`（§5.6.6）是**两件事**，只是过去曾有一个同名的集群列，本轮已删。读到这里请按下表区分：
>
> | 名字 | 管谁 | 默认 | 落在哪 |
> |---|---|---|---|
> | `max-concurrent` / `maxConcurrentScans` | A 路线的扫描线程（不进集群） | 2 | `application.yml` / `sensitivity-settings` |
> | `maxConcurrentJobs` | 这台集群上的**所有**作业（优化 + 下推扫描） | 2 | `lakehouse_catalog_execution_cluster` |
| 单次覆盖 | **不支持** | — |

为什么单次不能覆盖：它是**资源保护**而不是本次请求的参数。允许单次放开，等于「谁都能给自己提额」，那这道闸门就只剩字面意思。要调就调 catalog 级——那是一次治理决定、进审计。

为什么默认 2 而不是按 CPU 算：**这道闸门守的不是服务端自己的 CPU，而是存储侧被并发读的压力**（A 路线每条扫描会按列拉样本）。同一份存储被 `N` 条扫描同时打，`N` 越大越接近「用小文件随机读打满对象存储」。默认 2 与 `TablePreviewService.MAX_CONCURRENT_PER_PRINCIPAL` 同值不是巧合——**两条读路径争的是同一份后端带宽**，给它们同一个量级才不会出现「预览限 2、扫描不限」的失衡。

#### 5.6.5 `execution=SPARK` 的落法：复用表优化任务的提交链路

结论：**提交、探活、跟踪、取消这四段的机械部分应当直接复用**——「往哪个集群、用什么镜像、凭据从哪来、Job 建成什么形状」与「任务干什么」是两件事，`table-optimization.md` §4 已经按这条切开。**不能照搬的是任务的输入输出**：扫描拉的是「要扫的表 + 要用的探测器」，回的是「打码样本 + 命中计数」；优化任务拉的是「要压的表 + 压缩参数」，回的是「前后文件数」。

| 环节 | 复用程度 | 说明 |
|---|---|---|
| 执行集群配置与客户端 | **原样复用** | `resolve` / `clientFor` / `dispatchable` 全部按 catalog 生效；扫描不引入第二套「集群配置」概念。**并发闸门也共用**（同一把 `maxConcurrentJobs`）——见 §5.6.6 |
| 探活（五问） | **原样复用** | 它问的是「这个集群能不能收下并跑起一个 Job」，与 Job 内容无关；第 4 步 dry-run 用的仍是同一份报文构造 |
| k8s 报文骨架 | 复用骨架，换 `--scan-id` 与容器名 | 前缀 `paimon-scan-`、容器名 `scanner`；`backoffLimit=0`、`restartPolicy=Never`、`imagePullSecrets` / `serviceAccountName` 逐条照抄。**TTL 与 `jobTimeoutSeconds` 取扫描自己的批次策略**（§5.6.6 的 `scanJobTimeoutSeconds`），不读优化那组 |
| Spark 参数 | 复用注入位置，**取值独立** | 同一份「`extraJavaOptions` / `--conf` 注入点」的实现，但读的是扫描那一组 Spark 参数（§5.6.7 的 `scan_spark_conf`）——优化任务的参数改了不该悄悄改变扫描任务的资源诉求 |
| 提交器的幂等 / 状态折算 / 取消 | **原样复用** | 「先查同名再建」「先看 conditions 再看计数」「Background 删除」三条判据都不含优化语义 |
| 调度租约 | 复用模式，**新增两个通道** | `sensitivity.scan`（调度节拍）与 `sensitivity.dispatch`（提交作业）必须登记进 `SchedulerLease.CHANNELS`，否则多副本下每台都会提交一遍（§5.6.2 已论证过内建定时的这份代价）。**与既有的 `optimization.scan` 不是同一个通道**——那个是表优化的出批节拍，别混名（§5.6.3） |
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
- **B 路线的扫描作业与优化批次共用一把 `maxConcurrentJobs`**（已定，见 §5.6.6）。两者跑在同一台集群上、争的是同一份算力，因此**没有独立的 `maxConcurrentScans`**：下推的扫描作业也计入这个数，超出的留在 `PENDING`。理由不是「省一列」——各开一把闸门时，两侧各自看起来都在额度内，实际把同一台集群放到 `maxConcurrentScans + maxConcurrentJobs` 个驱动，而这个总和没有任何地方在守。共用一把闸门后，「同时最多几个作业在这台集群上」终于是一个可以被读出来的数字。
  - 代价是**两类任务会互相排队**：一个 catalog 把 `maxConcurrentJobs` 设成 2、又同时排了一大批优化和一次全量扫描时，扫描要等优化让位。这是**有意的**——它们争的本来就是同一份算力，让位比超发诚实。
  - 仍需集群侧兜底的只剩**跨 catalog** 的叠加（多个 catalog 指向同一台集群），那部分依然由 ResourceQuota / 调度器队列负责，服务端不知道集群总量（§5.6.6 末段）。
- **A 路线（进程内）的扫描不受这把闸门管**。它不提交任何 Job、不占集群算力，守的是存储侧被并发读的压力（§5.6.4 的 `max-concurrent`，默认 2，与 `TablePreviewService` 同池）。把这两件事混成一个数字会让「调大集群并发」意外地把对象存储的并发也放开。

**状态机要补两态。** 走 `execution=SPARK` 后扫描不再从 `RUNNING` 开始：`POST /sensitivity-scans` 返 `202` 时是 `PENDING`，调度节拍提交成功才转 `RUNNING`，提交期间是 `SUBMITTING`，并需要一份与优化批次同形的「提交成功、回写前进程挂掉」回收（`reapStaleSubmissions`）。§4.2 的 `status` 取值随之扩充。

#### 5.6.6 执行集群配置：一张表，两组策略（表优化 / 敏感识别各一套）

已定：**扫描任务与优化任务共用同一份执行集群配置**（同一个 k8s、同一个镜像、同一份凭据、同一组探活结论），**并发闸门也共用同一把**，只有「一批几张表」与「批次超时」按任务类型分开。因此 `lakehouse_catalog_execution_cluster` 在「连接与运行位置」（现有 ①②③ 段）之外，补上扫描那两列与两段 Spark 参数：

| 组 | 字段 | 消费方 |
|---|---|---|
| **表优化**（现有，保持不动） | `maxTablesPerJob` / **`maxConcurrentJobs`** / `jobTimeoutSeconds` | `OptimizationDispatcher` / `OptimizationBatchCreator` |
| **敏感识别**（新增） | `maxScanTablesPerJob` / `scanJobTimeoutSeconds` | `SensitivityScanner`（B 路线） |

**`maxConcurrentJobs` 是两类任务共用的一把闸门**（本轮口径变更）。它挂在「表优化」那一组下面只是因为历史顺序（列先为优化建的），语义上是**这台集群同时允许几个作业在跑**，扫描作业与优化批次**一起**计入、一起排队：

- 没有独立的 `maxConcurrentScans`。曾经设计过一列，本轮删掉——**各开一把闸门不等于更安全**：两侧各自都在自己的额度内、合起来正好把同一台集群放到两份额度的驱动数，而这个总和没有任何一处看得见。共用一把之后，「这台集群上同时最多几个作业」是一个能被读出来的数字。
- **代价（接受）**：两类任务互相排队。一个 catalog 同时排了优化与全量扫描时，扫描要等优化让位、反之亦然。它们争的本来就是同一份算力，让位比超发诚实。
- **A 路线（进程内）的扫描不受它管**：不提交 Job、不占集群算力，守的是存储侧并发读（§5.6.4 的 `max-concurrent`，与 `TablePreviewService` 同池）。两件事混成一个数字会让「调大集群并发」意外放开对象存储的并发。
- **跨 catalog 的叠加**依然由集群侧兜（见末段）——服务端不知道那台集群的 CPU 总量。

**为什么不把两组并成一列带类型标记的子表**：它们是**两个固定的、字段集合已知的**策略组，不是一个会增长的列表。子表（`execution_cluster_policy(type, ...)`）会让「读一个策略」变成一次 join，并且 `type` 列一旦要校验取值域、又要为两种类型各写一遍约束——现在多出来的列宽与迁移成本，远小于为两个已知类型建一套 EAV 的复杂度。

**列名不能省掉类型前缀**（`maxTablesPerJob` vs `maxScanTablesPerJob`）：两条业务线读的是同一行，`maxTablesPerJob` 这个名字在扫描代码里会让人以为「扫描也受它约束」。前缀是**给下一个读代码的人**看的，不是给数据库看的。

**默认值与校验**：`maxScanTablesPerJob=200`（扫描的表通常比「一批待优化表」多得多，一个 catalog 几千张表是常态）、`scanJobTimeoutSeconds=3600`。两者各自有区间（1–500 / 5m–24h），超范围 400。**两类任务的批次策略一共是 5 个数字**（优化 3 + 扫描 2）——并发那一项是共用的，不再算两遍。

**这一扩列对状态机的影响**：`config_hash` 的算法与「改完没验证」的判据**不含批次策略**（现有实现如此）——新增的策略列同样不进 `config_hash`。这意味着**只改 `maxScanTablesPerJob`（或 `maxConcurrentJobs`）不会把状态打回「待验证」**，这是对的：改一次并发数不需要重新证明「这个集群连得上」。

**为什么不再需要「两组配额交叉校验」**：这个风险随独立列一起消失了。原先要讨论的是「`maxConcurrentScans=20` + `maxConcurrentJobs=20` → 40 个驱动」，现在一个 catalog 只有一个并发数，**单个 catalog 的上限就是它自己**，交叉校验的对象不存在了。剩下的叠加只有**跨 catalog**（两个 catalog 指向同一台集群，各配 20 → 40 个驱动），那部分一如既往由**集群侧的准入**兜（ResourceQuota / 调度器队列）：服务端不知道那台集群的 CPU 总量，编一个「加起来不超过 X」的规则只会得到一个 X 猜错的系统——而 k8s 自己正好知道答案。**部署时这份集群侧配额必须显式设，空着就是敞口。**

#### 5.6.7 两组策略里的 Spark 参数（默认注入，可逐 catalog 覆盖）

已定：**两类任务都能各自配 Spark 参数**，且默认就带上参——不配也得能跑，且跑得合理。参数存在同一行的两个 JSON 列：

| 组 | 列 | 典型键 |
|---|---|---|
| 表优化 | `optimization_spark_conf` | `spark.sql.shuffle.partitions`、`spark.driver.memory`、`spark.driver.cores`、`spark.executor.instances` … |
| 敏感识别 | `scan_spark_conf` | 同上 + `spark.sql.files.maxPartitionBytes`（按列分区读时控制单分区大小） |

**默认值（新建配置时预置，不是「空即无」）**——已按本轮拍板调整为 **`1g` / `local[1]`**（原稿的 `4g` / `local[4]` 作废）：

| 参数 | 默认 | 为什么是这个数 |
|---|---|---|
| `spark.driver.memory` | **`1g`**（原 `4g`） | 与**容器内存请求对齐**：这个 pod 同时跑优化与下推扫描两类作业，而扫描的负载特征是「拉样本跑打分」——峰值取决于**一批列 × 采样行上限**，不取决于表本身有多大。给 1g 是让**默认值先跑得起来**，真跑大扫描的部署按 catalog 调大即可（三层覆盖的口径在这里照样成立）。**把默认值调大是把别人环境的成本替他们花了**：一个 catalog 只偶尔扫几张小表，却要为每次调度请求 4g，是不必要的常驻占用。 |
| `spark.driver.cores` / `master` | **`local[1]`**，即 `N=1`（原 `local[4]`） | 容器就是 driver（`Dockerfile` 的 ENTRYPOINT 用 `--master local[*]`，配 `local[N]` 收窄它）。**`N=1` 的含义是「单 pod 内串行执行」**——与 §5.6.5「一个扫描 = 一个 Job，不组批」是同一句话的两面：横向扩展单位是 **pod 数**（受集群侧配额约束），单 pod 内不靠并发抢吞吐。这样把「这台集群上真正并行跑着几个作业」这件事的答案完全留在 `maxConcurrentJobs` 一处，而不是「作业数 × 每作业线程数」两处相乘。 |
| `spark.sql.shuffle.partitions` | **由 `200` 改为不预置**（见下） | 小数据量下 200 个 shuffle 分区是纯开销，而扫描的 shuffle 量比 compaction 更小（按列拉样本）。**把 `200` 从默认里去掉**：它不是「能跑起来的起点」而是一个性能陷阱——留着它等于每次小扫描都白起 200 个分区。需要它的部署按 catalog 显式写上（`spark.sql.shuffle.partitions` 在白名单里）。 |
| `spark.executor.*` | **不配** | `local[*]` 模式下没有独立 executor，配了是死配置。只有将来改 `k8s://` 提交模式才需要——**留空并注明原因**，比配一组永不生效的值好 |
| `spark.sql.session.timeZone` | `UTC` | 这是**任务代码里钉死的**（见 `TableOptimizerJob.spark()`），不是可配项；列在这里是提醒：用户传的 `scan_spark_conf` **不得覆盖它**，否则 Iceberg 的 `older_than` 会按本机时区折算 |

> **`200 分区` 的处置要说清，别留下「到底改没改」的疑点**：原稿写的是「`200` 但显式写出来，好让人看见」。本轮拍板把默认值收敛到 `1g / local[1]` 之后，`200` 与新默认值的取向不一致（一个是「把开销藏起来」，一个是「让人自己看见并调」）。因此**统一到后者**：`shuffle.partitions` 不进默认集，`scanSparkConf` 的默认段只剩 `spark.driver.memory=1g` 与 `spark.driver.cores=1` 两行。要在控制台上看到这个差异，`#schedules` 的对照卡按新默认值渲染（§11）。

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

采样策略：**当前实现恒为 `HEAD`**——采样读走数据预览那条只读通道（`SensitivitySampler` → `TablePreviewService#sample`），它按文件顺序读前 N 行，没有抽样算子，因此 finding 上的 `sampling_mode` 一律记 `HEAD`，不编一个更聪明的模式名。**头部数据偏斜是真实风险**（按时间分区的表头部全是最新一天），所以这一列必须落库、必须能在人审时看见。

> **口径修正（P1 落地时）**：本节原写「降级 `LIMIT` 并乘 `×0.85`」，与 `SensitivityScorer` 的实际规则不一致——代码里没有这个折损系数。真正存在的打折只有一条：**有效样本 < 200 行时整体 × 0.7**（样本太少时「全命中」可能只是抽样抽得太巧）。写一个不存在的系数比不写更糟：人审时会去算那 15% 从哪来。`TABLESAMPLE` 仍作为**目标形态**保留在上表里：等有引擎侧抽样通道再翻成首选，届时 `sampling_mode` 这一列才第一次出现第二个取值。

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
| `DERIVED` | ⚠️ 继承，置信度 × 0.6，**并同时落一条 `PENDING` 建议** | `substr(id_card,1,6)`、`md5(id_card)` 仍可反推，但已变形。原文写的 `needs_review` 标记最终没有做成标签上的状态位——标签表只存「现在这列是什么」，没有待审态；「要人看一眼」这件事由一条 finding 表达（§4.3），人审的确认/拒绝、抑制项也都挂在它上面 |
| `AGGREGATE` | ❌ 不继承，生成 `PENDING` 建议，**且不再往下传** | `count(id_card)` 不敏感，`max(salary)` 敏感——无法用规则区分，交给人。这条链的下游没有结论可继承 |
| `CONSTANT` / `UNRESOLVED` | ❌ 不传播 | 没有源列 / 解析不出源 |

实现要点（**P2 落地口径**，与下面的设计原文有三处出入，已按实现改写）：

- **触发器是扫描，没有独立的传播端点。** 一次扫描收尾时（`finish` 之后、写终态之前），从这次覆盖到的表的标签出发沿列边 BFS 一遍。§9 的端点清单里没有 propagation 这一条，原因不只是「省一个接口」：finding 的唯一键建在 `scan_id` 上，一条没有扫描作为出处的结论答不上「谁在什么时候基于什么得出」。于是**增量**来自「只从这次扫到的表出发」（不是原文说的 `last_seen` 游标），**幂等**来自唯一键 + `applyInherited` 的「结论没变就不写」。
- **三档的实现差别只在「写不写标签」。** 三者共用同一个置信度口径：先算 `上游 × 0.9`（`DERIVED` 再 ×0.6），再分派给「写标签」「写标签 + 写建议」「只写建议」。建议的排序依据是「这条有多值得看」，与标签的分数必须是同一个口径，否则人审队列里「0.81 的建议」与「0.729 的标签」说的是同一件事却排在两处。`DIRECT` / `DERIVED` 写完继续往下走，`AGGREGATE` 那一跳**终止这条支路**（下游没有结论可继承）。
- **防环**：visited set，键是 `目标列哈希|typeCode`（不是「边」——同一条边经不同上游到达，结论应当只有一条）。
- **深度上限**：`lakehouse.rest.sensitivity.propagation.max-depth`（默认 5），catalog 可经 `sensitivity-settings.propagationMaxDepth` 覆盖。**P2 没有做「单次请求覆盖」**——传播不是端点，没有请求维度；原文那句「传给扫描/传播的请求体也能单次覆盖」在 P2 不成立，要覆盖只能改 catalog 设置。
- **深度语义 = 跳数**：被打标的那一列是 0，第一跳是 1。因此 `max-depth=2` 时**第 3 跳**停在 `PENDING`（`reason=DEPTH_EXCEEDED`）。
- **三条不越界**：不跨 catalog（往别人的 catalog 写标签会绕过那里的判权与设置，只计入 `crossCatalogSkipped`）；不覆盖已有 `MANUAL` / `AUTO` 结论的列（扫描在本列直接判出来的证据更强，被继承覆盖是一次降级）；深度超限只出建议。
- **标签没写进去也要继续往下传。** 目标列已有更强结论时这一跳不落标签，但那条链**下游**还没被推过；用本轮算出的置信度继续往下走是保守的一侧——最多把下游算低，不会算高。

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

P2 落地口径（`SensitivityLifecycleListener` + `ColumnTagService#onTableDropped/onTableRenamed`）：

- **挂在事件上，不在协议面里直接调。** `ALTER` 的实现分散在 Paimon / Iceberg / Lance 三个协议模块，而标签与扫描都在 server——让协议模块反过来依赖治理服务，等于把敏感数据焊死在表结构变更上。`CatalogEventListener` 是唯一两边都够得着的位置（与 `DataPolicyLifecycleListener` 同一条理由）。
- **`DROP` / `UNREGISTER` 连 finding 一起删**：表的元数据都没了，那些 finding 指向的列已不存在，留着只会在人审队列里堆一串点开就 404 的待审项。删掉的每条标签都发一条 `column.tag.changed`（`change=DELETED`）——删完之后那行就不在了，事件是「这条治理结论曾经是什么」的唯一残留，引擎侧也靠它按表驱逐策略缓存。
- **`RENAME` 只搬标签，不搬 finding，也不搬抑制项。** 三者时机不同：标签是「现在这列是什么」，必须跟过去；finding 是历史（某次扫描看到了什么），改写它等于篡改那次扫描的结论；抑制项按 `column_ref_hash` 记，改名后哈希变了自然不再命中——**这正是 §7.2 说的「改名视为新列」那条失效路径**，顺手迁过去就把人审否决变成了跨改名的永久诅咒。
- **`ALTER` 的判据只看 `TABLE_COMMITTED` 上的 `change=alter`。** 三个发布点（alter / rollback-schema / commit-snapshot）都发这个事件类型，但只有 `alter` 改了列的集合；漏掉这个判据的表现是「每次回滚 schema、每次提交快照都白扫一遍」，日志里除了一堆扫描记录什么也看不出来。
- **扫描放在提交之后（`afterCommit`），并用 `REQUIRES_NEW` 开新事务。** 发布点是同步的，监听器因此跑在改表那个事务**里面**：直接扫会把一次 DDL 变成「改表 + 扫表」的长事务，而且扫描读到的 `schema_doc` 在提交前对别的实例不可见。`REQUIRES_NEW` 不是可有可无的——`afterCommit` 里线程上仍绑着刚提交事务的连接与 `EntityManager`，直接写库**插入既不 flush 也不报错**，表现是「改完表结构，扫描记录一条也没有」。没有事务上下文时（例如直接调服务层的用例）就地执行，否则那些用例里 `afterCommit` 永不触发，「钩子确实触发了扫描」会以假绿灯通过。
- **钩子产生的扫描是 `trigger_type=LIFECYCLE` + `scope=TABLE` + `mode=METADATA_ONLY`**：为一个新列重新采样整张表，等于把一次 schema 变更变成一次明文读；而元数据档毫秒级、零 I/O，幂等且足够让 D1/D2 先给出名字与类型上的判断。

## 8. 消费面

### 8.1 标签驱动脱敏（核心价值）

策略解析时，服务端把「标签」展开成「列的 mask」：

```text
masks = 显式 mask（lakehouse_column_mask，现状）
for tag in columnTags(表):
    if tag.column == "":                      # 表级标签（§5.5 已拍板：不参与展开）
        continue                              # —— 它是一个汇总结论，没有对应的列可遮
    for b in bindings(catalog, tag.tag_key, tag.tag_value):
        if tag.column 不在 masks:            # 显式优先
            masks[tag.column] = b.mask       # origin = TAG
        else:
            conflicts += (tag.column, b.tag_key, "explicit-wins")
```

> **那句 `continue` 要写成代码里的第一个分支，不能靠「表级标签恰好没有 binding 命中」侥幸**：`bindings(catalog, tag_key, tag_value)` 如果有人在控制台上给 `TABLE_LEVEL` 这类键配了绑定，表级标签就会命中——而它的 `column` 是空串，落进 `masks[""]`，表现是**这条绑定静默不生效**（没有任何列叫空串），或者更糟：某个实现把空串当成「所有列」的前缀去匹配。**排除表级标签是显式规则，不是隐含结果。**

三种结果都有明确归宿：

- **无显式 mask，有绑定** → 自动脱敏，**新增列被识别打标后自动纳入覆盖，不用改策略**；
- **有显式 mask，也有绑定** → 显式赢，冲突记进响应，控制台列出来（不静默吞掉）；
- **同列多个标签命中多个绑定** → 按 `level desc, priority desc, tag_key asc` 取一条，其余记冲突。

**引擎侧零改动**：`resolve` 下发的仍然是「列名 → mask」的既有形状，Spark 侧 `PolicyProjectRule` / `PolicyHiddenColumnsRule` 不知道「标签」这回事。

**缓存**：策略下发有 60 秒 TTL，打标后最多 60 秒生效。另需在标签变更时**主动失效**对应表的策略缓存（与 `StorageCredentialCache.invalidateCatalog` 同构），否则「刚确认完、页面上还是明文」会被当成 bug 报上来。

> **这条「主动失效」不在本仓库里。** 60 秒 TTL 的缓存在**引擎侧**（`PolicyProjectRule`），本仓库只有 `resolve` 的 DB 查询。因此本仓库负责的是**把事件发对**：`column.tag.changed`（带 `database` / `table` / `tagKey`）与 `sensitivity.binding.changed`（`kind=BINDING` + `catalog`），让引擎能按 `catalog.db.table` 驱逐。P3 阶段 0+1 交付的是这一半；引擎侧订阅属跨仓库改动，验收（「打标后 ≤60 秒生效」）在 TTL 这一侧已经成立，主动失效是把「≤60 秒」压到「立刻」的增强。

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
| `POST` | `/sensitivity-findings:decide` | 批量确认/拒绝（`{ids, decision, note}`）；**确认即落 `MANUAL` 标签**（级别取类型表），**拒绝即落永久抑制项**（§7.2）、并撤掉该列的自动标签。整批一个事务；已决策的行计入 `skipped`（幂等）；有 id 不存在 → 整批 404。判权按 finding **自己记着的** catalog 逐个判 `ALTER`（请求体里没有 catalog，不能由调用方「声明」权限） |
| `GET` | `/sensitivity-suppressions?catalog=` | 永久抑制项清单，**含已解禁的**——界面要能显示「已解除」而不是让它凭空消失。判 `DESCRIBE`：「这列**不是**身份证」与「这列是身份证」是同一级别的信息 |
| `DELETE` | `/sensitivity-suppressions/{id}` | 显式解禁：置 `enabled=false`（**不删行**，保留决策史），**不触发立刻重扫**（点的是「解除抑制」，不是「立刻读这张表」）。判 `ALTER`，catalog 取自抑制项自己 |
| `GET` | `/column-tags?table=&tagKey=&level=` | 标签查询（对外主接口） |
| `PUT` | `/column-tags` | 手工打标（upsert，带 `entityVersion` 做 409） |
| `DELETE` | `/column-tags/{id}` | 删标签（**同时写抑制项**，否则下次扫描复活） |
| `GET` / `PUT` / `DELETE` | `/tag-mask-bindings[/{id}]` | 标签 → 脱敏绑定。**P3 阶段 0 已落地三条**：`GET`（全量列表，含停用项）、`PUT`（按唯一键 `(catalog, tagKey, tagValue)` upsert）、`DELETE /{id}`。**没有详情端点**——列表就是全量，「详情」等于把同一份数据再开一个判权入口 |
| `GET` / `PUT` | `/sensitivity-settings` | **catalog 级治理设置**：`defaultExecution`（§5.6.1）、`maxConcurrentScans`（§5.6.4，指 **A 路线进程内**的扫描并发，非集群闸门）、`propagationMaxDepth`（§7.1）。`PUT` 走乐观锁，且**变更进审计**——它改的是「这个 catalog 的扫描怎么跑」，与改绑定同级 |
| `GET` / `POST` | `/sensitivity-schedules` | **调度项**（本轮拍板自建，§5.6.3）：列表 / 新建。`POST` 校验 cron 合法性、范围递进（`table` 有值则 `database` 必须有值）、`execution=SPARK` 时目标 catalog 可派发（否则 400，与 `POST /sensitivity-scans` 同一条 fail-closed） |
| `GET` / `PUT` / `DELETE` | `/sensitivity-schedules/{id}` | 详情 / 改（含启停；`PUT` 带 `entityVersion` 走乐观锁）/ 删。**改 cron 会重算 `next_run_at`**（§4.6：人工编辑的乐观锁与调度的抢占令牌是两把，别合并） |

执行集群的批次策略（含扫描那两列）与两段 Spark 参数挂在**既有**的 `PUT /catalogs/{c}/execution-cluster` 上（新增 `maxScanTablesPerJob` / `scanJobTimeoutSeconds` / `optimizationSparkConf` / `scanSparkConf`；`maxConcurrentJobs` 是既有字段，现在两类任务共用，§5.6.6、§5.6.7），**不新增端点**——它们与「连哪个集群」是同一份配置的不同段，拆成两个端点会让「保存了连接但没保存策略」变成一个用户看得见、却说不清为什么的状态。

> **端点计数要连量词一起改。** 本仓库的守卫是「运行时端点数 == 登记数」（catalog 端点只加控制器判权不够——`AuthorizationInterceptor` 先执行、`resolve()` 返 null 即 403）。本轮把调度从「外部路线不注册」翻成「自建、正式注册」，因此**这 5 条从「可选」变为「必有」**：`GET`/`POST /sensitivity-schedules` 与 `GET`/`PUT`/`DELETE /sensitivity-schedules/{id}`。**计数口径是按 `(方法, 路径)` 元组，不是按路径数**——写成「+3」会让守卫的差集对不上（`EXTENSION_ENDPOINTS` 里每一条都是一个 `(METHOD, path)`）。登记表与守卫脚本要同步，漏改会让守卫直接红。**这正是 §5.6.3 说「这一条是会影响守卫的硬选择」的落点。**

> **但 `/tag-mask-bindings` 这三条在 P3 阶段 0 没有进 `EXTENSION_ENDPOINTS`，这是有意的。** 上一条讲的是调度那 5 条——它们与控制台 `#schedules` 同一轮落地，所以「登记端点」与「控制台调用」可以一起做。绑定不一样：§5.5 拍板 `#bindings` 是**唯一刻意不建的一屏**，控制台在阶段 3 之前不会调用这三条；而 `verify-console.py` 第 2 组断言是「白名单里每一条都在控制台里真的被调用」，提前登记会直接红。**顺序是先建屏、再登记，不要反过来。**（管理面端点不走 `AuthorizationInterceptor`，所以上面那句「只加控制器判权不够」不适用于这里——三条端点各自的判权在控制器里，分别是 `DESCRIBE` / `ALTER` / `ALTER`。）

事件：`sensitivity.scan.started` / `.completed` / `.failed`、`sensitivity.finding.decided`、`column.tag.changed`、`sensitivity.binding.changed`。扫描事件的 payload 带 `execution` 与 `triggerType`——「谁在什么时候读了明文」要能仅凭事件流回答，不必回查扫描表。

> **P1 已登记这 3 条**（`EXTENSION_ENDPOINTS` 按 `(METHOD, path)` 元组计）：`POST /sensitivity-findings:decide`、`GET /sensitivity-suppressions`、`DELETE /sensitivity-suppressions/{id}`。解禁复用的是 `sensitivity.binding.changed`（属性 `kind=SUPPRESSION`、`change=RELEASED|LIFTED`）——它是一条治理配置变更，不是一次扫描、也不是一条标签变更；`sensitivity.finding.decided` 由此从「有类型无发布点」变成真有发布点。

## 10. 权限与可见性

| 权限点 | 覆盖动作 | 为什么独立 |
|---|---|---|
| `CATALOG:SENSITIVE_SCAN` | 发起扫描（含读明文样本） | 采样是「合法高权限读」，不能挂在 `TABLE:SELECT` 上——否则任何能查表的人都能让服务端替他读明文并给出打码样本 |
| `CATALOG:SENSITIVE_ADMIN` | 管类型 / 改绑定 / 批量决策 | 改绑定 = 改脱敏行为，与 `TABLE:ALTER` 同级 |
| `TABLE:SELECT` | 看该表的**标签存在性** | 只知道「被保护了」，不知道「保护的是什么类型」 |
| `CATALOG:SENSITIVE_READ` | 看完整标签（类型 + 级别 + 置信度） | 标签本身是敏感信息，见下 |

**标签分级可见**：`PII.CN_ID_CARD` 这个字符串直接告诉你「这列是身份证」——对普通使用者只显示「敏感（已保护）」，完整类型与级别需 `CATALOG:SENSITIVE_READ`。与 `DataPolicyEntity`「策略不是表属性」同一条思路：**保护动作的存在本身也是信息**。

**表级标签的可读面比列级更宽、可写面不变**：表级标签（`column_name=''`）回答的是「这张表的整体级别」，属于§5.5 说的**检索面**，因此它在 `GET /column-tags` 里按 §10 的分级可见规则照常返回；但**它不参与策略展开**（§5.5 已拍板），所以它对 `CATALOG:SENSITIVE_ADMIN` 那条「改绑定 = 改脱敏行为」的判定**不构成输入**——改一个表级标签不会让任何列的脱敏行为变化，这一点要在控制台把它与列级标签区分展示（`#table` 页签里表级那行标注「整表级别，不驱动脱敏」）。

**调度项属受管资源**：`/sensitivity-schedules` 的读写判 `CATALOG:ALTER`（与发起扫描同档，因为它最终产出的就是一次扫描），**不新增权限词**——沿用本方案「权限不新增词」的既有偏离（§10 上方那条已在实现里落地的口径）。停启一个调度项等于「让这个 catalog 定期读明文」，与手动发起扫描是同一件事的两种时点。

## 11. 控制台

八屏，见 [`sensitive-data-mockup.html`](sensitive-data-mockup.html)：

| 屏 | `#hash` | 内容 |
|---|---|---|
| 总览 | `#overview` | 覆盖率 / 敏感列数 / 待确认 / 自动应用率；分级分布；类别 Top；最近扫描（含 `execution` 与 `triggerType` 两列） |
| 发起扫描 | `#scan` | 范围级联（catalog → 库 → 表）、**执行位置（进程内 / 下推 Spark，带前置依赖与代价对比）**、模式（仅元数据 / 采样）、采样量、**成本与权限提示**（「将读取 N 列明文样本」；下推时额外提示「将提交 Spark 作业」） |
| 识别结果审核 | `#findings` | 待确认队列：列引用 + 置信度条 + 命中探测器 + **打码样本** + 右侧「为什么这么判」；批量确认/拒绝 |
| 标签清单 | `#tags` | 列级标签表：来源徽标（自动/人工/继承）、级别、置信度、时间；行内改/删；按表汇总 |
| **类型与探测器配置** | `#types` | 类型清单 + **新建/编辑类型**：`type_code` / 类别 / 分级 / `auto_apply` 开关 / 阈值 / **五层探测器逐层编辑**（列名正则、值正则 + 校验器、字典、权重）+ 权重实时归一化预览。内置类型只可停用、每个字段失焦前是只读态 |
| **扫描任务与调度** | `#schedules` | **调度项 CRUD（本轮已定自建，§5.6.3）**：列表（cron / 范围 / 模式 / 执行位置 / 上次·下次运行 / 启停）+ 新建·编辑表单（cron 输入带 5/6 段校验与「下次运行时刻」实时预览）+ 「下推 Spark 时服务端把这一发变成什么」对照卡（复用表优化任务链路的哪几段、哪几条语义不能照搬，§5.6.5；Spark 参数按 §5.6.7 的**新默认值 `1g` / `local[1]`** 渲染）。**没有「外部调度建议页」这个形态了**——那个形态以「不建表、不注册端点」为前提，已随路线翻转废弃 |
| 标签驱动脱敏 | `#bindings` | 绑定列表 + 「本绑定覆盖 N 列」预估 + 冲突提示（显式 mask 覆盖了几列）。**表级标签不出现在这里的候选里**（§5.5 已拍板它不参与展开） |
| 表详情联动 | `#table` | 表详情的「敏感数据」页签：列 → 标签徽标、级别、来源；未分类列显式标灰；**表级那行单独标注「整表级别，不驱动脱敏」**，与列级标签视觉上分开（§10） |

`#types` 与 `#schedules` 是上一轮新增：前者补上「识别规则怎么配」（此前原型只有类型**清单**、没有**编辑**），后者补上 §5.6 的触发方式落地界面。`#schedules` 的形态**已由本轮拍板定死为 CRUD**（不再是「随路线在只读建议页与 CRUD 之间二选一」）——这正是文档与原型要对齐的地方。

## 12. 分期与验收

| 期 | 范围 | 验收断言 |
|---|---|---|
| **P0** | 五实体 + 内置类型 seed + 类型/探测器配置端点与控制台 `#types` + D1/D2 元数据扫描 + 手工打标 + `/column-tags` 只读 + 控制台 `#overview` / `#tags` + `/sensitivity-settings`（catalog 级设置含 `defaultExecution`） | 扫描 100 张表 < 30 秒；同一列重扫不产生重复 tag；内置类型不可删（409）；**改一个内置类型的探测器权重后，下一轮扫描的判定随之改变且变更进审计**；**`defaultExecution` 三层覆盖生效（请求 > catalog > 全局）；`maxConcurrentScans`（A 路线进程内并发）按 catalog 覆盖生效，且与集群闸门 `maxConcurrentJobs` 互不影响** |
| **P1**（**已交付**） | D3 采样探测 + 校验器 + 置信度分档 + 人审队列（`decide`）+ 自动应用闸门 + 第 7 张表（抑制项，V4） | 身份证列在 500 条造数上命中率 100%、误报 0；`PERSON_NAME` 即使置信度 0.99 也进 PENDING；**驳回后重扫不复活、解禁后下一轮重新判**；**删标签同时写抑制项** |
| **P2**（**已交付**） | 血缘传播 + 生命周期钩子 + 增量扫描（`ALTER ADD COLUMN`）；`execution` 落库，扫描历史可见位置；`propagationMaxDepth` 可配 | `DIRECT` 边一轮传播后下游出 tag；`AGGREGATE` 边不产生 tag；`REJECTED` 列重扫不复活；生命周期钩子产生的扫描 `trigger_type=LIFECYCLE`；**`max-depth=2` 时第 3 跳停在 `PENDING`**（`SensitivityPropagationTests` 5 条 + `SensitivityLifecycleTests` 4 条） |
| **P3**（**阶段 0+1 已交付**） | 标签驱动脱敏（resolve / 预览展开）+ 绑定端点（`/tag-mask-bindings` 三条）+ 冲突报告；缓存主动失效在**引擎侧**（本仓库只负责发事件） | 打标后 ≤60 秒策略生效；显式 mask 优先且冲突可见；三格式（Paimon/Iceberg/Lance）同表同规则结果一致 |
| **P4** | D4 字典 / D5 统计特征 + 触发方式补全（**自建调度**：`#schedules` 由建议页改为 CRUD + `/sensitivity-schedules` 5 条端点登记 + 第 6 张表 `lakehouse_sensitivity_schedule` + 两个租约通道，§5.6.3）+ `execution=SPARK` 下推（复用表优化任务的提交/探活/跟踪/取消链路，§5.6.5；执行集群扩扫描那两列与两组 Spark 参数，§5.6.6 / §5.6.7；**并发闸门共用既有的 `maxConcurrentJobs`**）+ 对外事件订阅 | 自建调度按 cron 触发一次扫描且只触发一次（多副本下靠 `sensitivity.scan` 租约 + `next_run_at` 条件更新，§5.6.3）；**停用一个调度项后不再产生新扫描，历史扫描仍按 `schedule_name` 可查**；同表并发重入返 409；SPARK 路线下扫描事件的 `execution` 为 `SPARK` 且两条审计链可按 `scanId` 拼上；**给不可派发的 catalog 建 `execution=SPARK` 返 400**；**`scanSparkConf` 的默认段是 `spark.driver.memory=1g` + `spark.driver.cores=1`，不含 `shuffle.partitions`**（§5.6.7）；**扫了 100 张表、其中 3 张读失败时，`tables_scanned=100` 且 `tables_failed=3`（不是「扫了 97 张」）**；**作业日志里不含任何原始列值**（抽查一次真实扫描的 Pod 日志）；**`scanSparkConf` 里塞 `spark.sql.catalog.*.token` 被丢弃并记审计，且扫描仍用服务端构造的会话** |

> `execution` 字段 P2 就落库（成本极低、事后补埋点会丢历史），但 `SPARK` 这条路到 P4 才通。P2 阶段该列恒为 `IN_PROCESS`——**先有列再有值，比事后给存量行补一个说不清的默认值干净**。同理 §4.2 的那组 B 专属列（Job 坐标 / 令牌哈希 / 提交代数）也在 P2 一次建好：`status` 的取值域一旦要扩到 `PENDING` / `SUBMITTING`，改的是列宽与所有既有读取点，不如一次到位。

> **P1 实际落了什么**（一句话对照上表，避免「按设计稿以为做了」）：采样读走**预览那条只读通道**（`SensitivitySampler` → `TablePreviewService#sample`，同一并发池、不落原值、只存打码样本）；五个校验器是 core 里的纯函数常量（`SensitivityValidators`）；值层得分按「非空行」做分母、校验通过率 &lt; 99% 时整层打折（`SensitivityScorer#valueScore`）；三条决策带在 `SensitivityDetectors#detect` 里合成；`AUTO_APPLIED` 由 `SensitivityTagWriter` 落 `AUTO` 标签并重算表级级别；人审 `decide` 与抑制项由 `SensitivityDecisionService` 承担。**丢弃下界随「这一列到底有没有采到样」走，而不是随扫描模式走**——`SAMPLED` 模式下某列读失败时它只有元数据证据，用 0.60 会把本可提出的候选判死。

> **P2 实际落了什么**（一句话对照上表）：`SensitivityPropagationService`（BFS 三档 + 建议落 finding）；调用点在 `SensitivityScanService#finish` 之后、写终态之前，异常只记 WARN（传播是一次已成功扫描的增值收尾，不该把它变成失败）；`SensitivityLifecycleListener` + `ColumnTagService#onTableDropped/onTableRenamed` 四个方法；`SensitivityTagWriter#applyInherited` 新增 `INHERITED` 来源，并把 `removeAuto` 放宽成 `removeAutomatic`（**驳回一条继承结论也必须能撤掉标签**，只删 `AUTO` 的表现是「我驳回了，它照样打码」）。**没有 V5 迁移**：P2 需要的 `execution` / `claimAttempt`（B 路线那组列）在 P1 已一次建好，`ScanStatus` 也已含 `PENDING` / `SUBMITTING`。`source` 优先级 `MANUAL > AUTO > INHERITED`——继承不能覆盖 `AUTO`，扫描在本列直接判出来的证据更强，被继承覆盖是一次降级。

> **P3 阶段 0+1 实际落了什么**（§9 的端点 + §8.1 的展开；控制台 `#bindings` 屏与缓存主动失效不在这一批）：
>
> - **展开是查询期行为**（`TagMaskExpander`，core）：打标签时**不**往策略表写 mask。写进去之后「改一条 catalog 级绑定」要改回多少张表没人知道，漏改的那几张不报错、只是继续按老规矩脱敏。
> - **没策略的表也生效**：`resolve` 对「没有 `DataPolicyEntity`」的表先问一次展开结果，有 mask 就回 `FOUND` + `version=null`。这是 §8.1「新增列被识别打标后自动纳入覆盖，不用改策略」的落点——只在「有策略」的分支里展开会让这句话变成空话，而配过策略的表全都工作，看不出来。
> - **`version=null` 是信息不是缺陷**：没有策略就没有版本号，因此引擎侧缓存**必须**靠 `column.tag.changed` / `sensitivity.binding.changed` 失效，不能靠比对版本。
> - **豁免连带豁免标签绑定，主体白名单不连带**：豁免（`exemptPrincipals`）是「这张表对这个人不设防」，是对**表**的判定，所以连标签展开一起跳过；`subjects`（FOR 子句）限定的是**这条策略**的适用主体，所以名单外的人看不到行过滤、但**仍会**被标签脱敏——否则一条只给某部门用的行过滤策略会顺带关掉全局 PII 保护。
> - **文件下载 / 分区列举的 403 判据改用 `hasActiveProtection`**（策略**或**标签绑定）。只判策略的话，一张「没策略、列被打了标、catalog 配了绑定」的表能把 parquet 原文件递出去，而同一张表的预览明明已经脱敏。
> - **`PII.*` 前缀通配按「段」匹配**（前缀保留末尾的点），且同级别同优先级时**精确匹配赢过通配**——这条 tie-breaker 是 §8.1 三项排序之外的第四个，只在三项全等时生效。
> - **冲突里区分 `tagKey` 与 `bindingKey`**：同一条标签能同时命中 `PII.*` 与 `PII.CN_ID_CARD`，只报标签键的话两条记录一模一样，「输的是哪条规则」答不上来。
> - **绑定写入只校验到语法层**（`PolicyExpressions.parseMaskTemplate`）：绑定不知道会落在什么类型的列上，所以「结果类型等于列类型」留到引擎编译时兜；但「fn 拼错」「表达式引用 `col`」必须写入即拒——绑定里写死列名，落到别的列上就是把那一列的值搬过来。
> - **控制台的 `#bindings` 屏刻意还没建**（§5.5 那条「唯一刻意不建的一屏」在本阶段仍然成立），因此这三条端点**没有**加进 `scripts/verify-console.py` 的 `EXTENSION_ENDPOINTS`：那份白名单的第 2 组断言要求「每一条都在控制台里真的被调用」，提前登记会让守卫红。阶段 3 建屏时再一起加。

## 13. 风险与未决

| 风险 | 处置 |
|---|---|
| **误报 → 误脱敏** | 自动应用白名单（§5.3）+ 阈值双开关 + 标签可撤销 + 全程审计；宁可多一条待审，不多一条静默 mask |
| **漏报** | 不承诺「扫完即全覆盖」，控制台报**覆盖率**而非「已完成」；未分类列显式标灰 |
| **采样偏斜** | `TABLESAMPLE` 优先，降级 `HEAD` 必须标 `sampling_mode` 并打折置信度 |
| **识别系统自身泄漏** | 只存打码样本、采样单独授权、原始行不出服务端（§5.4）；**下推 Spark 时缺口收窄到「原始行不出 Pod」**——值层抽样在作业内跑、只回落打码样本与命中率，且任务侧禁止把样本值写进日志（§5.6.5） |
| **下推路线的「假覆盖」** | 逐表回调里「没命中也要回」，把「扫完没发现」与「漏扫」分开（§5.6.5）；`tables_failed` 与 `tables_scanned` 分开计数，覆盖率不会把读失败的表算成已覆盖 |
| **标签本身就敏感** | 分级可见性（§10） |
| **调度重复触发** | 自建调度（§5.6.3）：`sensitivity.scan` 租约 + `next_run_at` 条件更新让多副本下只有一方推进；服务端另有同表并发闸门（§5.6.4）兜底，两者不互相假设 |
| **密文 / 哈希列识别不出** | 明确口径：识别的是**明文形态**；`md5(...)` 这类列靠血缘 `DERIVED` 传播兜，而不是靠值匹配 |
| **一次误驳会让真敏感列永久静默** | 抑制项按 `(column_ref_hash, type_code)` 记，**只挡这一个类型**（同一列被别的类型命中仍会提）；列改名自动失效；解禁入口在 `#findings` 屏的「已否决」区块里、且解禁进事件流（`kind=SUPPRESSION`）——「这列为什么一直没被提出来」永远能从抑制项清单答上来 |
| **标签行数爆炸** | 只为命中的列落 tag（不是「每列 × 每类型」）；控制台强制分页 + 按 catalog 过滤 |
| **内置类型与用户自定义冲突** | `type_code` 唯一；内置 seed 用 upsert 且**不覆盖**用户改动过的字段 |
| **跨 catalog 的并发叠加** | 同一台集群被 N 个 catalog 指向、每个都配 `maxConcurrentJobs=20` → N×20 个驱动。**单个 catalog 内部已无叠加**（扫描与优化共用一把闸门，§5.6.6），剩下的这层本方案**不做交叉校验**（编一个「加起来不超过 X」只会得到一个 X 猜错的系统），由集群侧 ResourceQuota / 调度器队列兜——**部署时这份配额必须显式设**，空着就是敞口 |
| **两类任务互相排队** | 共用一把 `maxConcurrentJobs` 的直接代价：优化占满时扫描要等，反之亦然（§5.6.6）。**有意为之**——它们争的是同一份算力；若某 catalog 确实需要两类任务都能随时起跑，做法是调大这个数或拆两台集群，而不是再开一把看不见的闸门 |
| **`*_sparkConf` 成为改认证目标的旁路** | 按**前缀白名单**过滤（§5.6.7），白名单外一律丢弃 + 记审计；连接与认证键只能由服务端构造，用户传了不生效 |

**已拍板（累计十一条：本轮六条 + 上文五条）**：

1. 下推路线用**同一个优化器镜像**（同一 jar 的另一个 main class `SensitivityScannerJob`），不单独打镜像——白拿三格式运行时与 Lance 的 arrow 排序修复（§5.6.5）。
2. 下推路线的扫描作业**与优化批次共用**执行集群的 `maxConcurrentJobs` 一把闸门：两者跑在同一台集群上、争的是同一份算力，**不设独立的 `maxConcurrentScans`**（§5.6.4、§5.6.6）；跨 catalog 的叠加仍由集群侧配额兜。
3. catalog 级并发**默认 2，可配**（全局默认 / catalog 覆盖两层，不支持单次覆盖）（§5.6.4）——这是 **A 路线（进程内）** 的并发，与上面那把集群闸门是两件事，互不影响。
4. 默认执行位置**定 A（进程内）**，且**全局可切**——三层覆盖：全局默认 < catalog 的 `defaultExecution` < 单次请求（§5.6.1）。
5. 血缘传播默认深度 **5 跳，支持在 `application.yml` 配**（`lakehouse.rest.sensitivity.propagation.max-depth`），也可单次覆盖（§7.1）。
6. 分级**对齐国标口径**：`L1–L4` 对应《网络数据分类分级指引》1–4 级（GB/T 43697-2024 均属一般数据）；`CORE` / `IMPORTANT` 两档只人工打、不自动识别（§5.5）。

**本轮新增（第 7–11 条，与上面 6 条同权，只是写下来更晚）**：

7. **采样读一律走数据预览那条内部只读通道**（`TablePreviewService` 的读路径），**不给扫描器开第二只读入口**（§5.4 第 1 条）。代价是 A 路线下扫描与预览共用并发池，这是有意为之——两条路径争同一份存储带宽。
8. **表级标签（`column_name=''`）不参与策略展开**，只用于检索与分级可见性（§5.5、§10、§11）。它没有对应的列，喂给展开会在「找不到列」与「全表 mask」两处出错。
9. **调度自建，与表优化调度方案同形**：复用 `lakehouse_scheduler_lease`（新增 `sensitivity.scan` / `sensitivity.dispatch` 两个通道）+ `next_run_at` 条件更新抢占 + 同一个 `@EnableScheduling` 节拍；建第 6 张表 `lakehouse_sensitivity_schedule`，注册 `/sensitivity-schedules` 的 5 条端点（§4.6、§5.6.2、§5.6.3）。**推翻本稿此前的「推荐外部调度」。**
10. **批次策略放同一行**（本稿既有口径，本轮确认）：`maxTablesPerJob` / `maxConcurrentJobs` / `jobTimeoutSeconds` 与 `maxScanTablesPerJob` / `scanJobTimeoutSeconds` 同在一张 `lakehouse_catalog_execution_cluster`；**不建独立的 `optimization_policy` / `scan_policy`**（§5.6.6）——读它们的代码本就要先拿到这一行，而并发闸门本来就是共用的，拆表会让「共用」变成一次跨表读、更难看出来。
11. **两组 Spark 参数的默认值收敛为 `1g` / `local[1]`**（原 `4g` / `local[4]` 作废），并**把 `spark.sql.shuffle.partitions=200` 从默认段移除**（§5.6.7）。理由：默认值该是「跑得起来的最小起点」，不是「把别人环境的成本替他们花了」；`N=1` 让「这台集群并行几个作业」的答案完全落在 `maxConcurrentJobs` 一处。

> **本条（11）是唯一一条「改了之后连既有文档口径都要跟着动」的拍板**——`catalog-execution-cluster-design.md` §…「`*SparkConf` 默认值（4g / local[4] / 显式 200 分区）」那句、以及同稿配套原型 `catalog-execution-cluster-mockup.html` 里的两段 `textarea` 示例文本，都属于同一份默认值。**两处不同步 = 同一个产品在两个文档里对不上**，改的时候一起改（守卫脚本对这一组的断言以本设计稿 §5.6.7 的表为准）。

**仍未决**：**无。** 原先列在这里的 5 条（采样读通道 / 表级标签是否参与展开 / 调度外部还是自建 / 批次策略放同一行还是分表 / Spark 参数默认值）已全部并入上面的第 7–11 条。此后若要再开新议题，**新开一节写，不要往这张已结案的清单里追加**——一张「已全部拍板」的表混进来一条新问题，会让读者分不清哪些是结论、哪些还在讨论。
