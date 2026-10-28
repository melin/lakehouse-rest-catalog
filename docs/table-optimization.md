# 表存储优化

> 状态：**服务端、优化任务模块与控制台页签均已实现**（P1 + P2 + P3 的 Iceberg 部分，以及
> **第三种格式 Lance**——见 [`lance-table-optimization-design.md`](lance-table-optimization-design.md)）。
> 两项能力在 P3 之后补齐：**Iceberg 表的存储优化**（§2.5、§6.5）与**权限管理覆盖 Iceberg catalog**
> （见 `docs/authorization.md` §7）。落地过程中与原方案的偏差都写在对应小节里，
> 并注明了改动的理由——本文同时是「当时为什么这么设计」的记录。
>
> 各节的实现状态：§2 动作映射 ✅（含 §2.5 Iceberg） · §3 执行架构 ✅ · §4 数据模型 ✅（`sql/schema-mysql.sql` 当时是 22 张表，**Iceberg 未新增表**；现为 27 张，后来加了行过滤/列脱敏 2 张、执行集群 1 张与调度通道租约 1 张）·
> §5 API ✅ · §6 优化任务模块 ✅（`lakehouse-rest-optimizer`，三个格式分支）· §7 控制台 ✅（三种格式共用同一面板，参数与动作能力按格式分派）· §8 分期见文末。
>
> 对标对象：阿里云 DLF 的表存储优化（Compaction / Snapshot Expire / Orphan File Remove 三个子页签）与 AWS Lake Formation 的 Enable optimization（三项勾选 + 默认/自定义配置 + 三块历史）。**产品分层以 AWS 为基准**（总开关 → 三项独立启用 → 默认/自定义 → 历史三块），DLF 的快照详情卡、「立即清理」按钮与中文文案吸收进来。执行链路是本方案的自有部分：DLF 由服务端托管调度，AWS 由托管 optimizer（按 DPU 计费），我们用**先落库、再异步提交 k8s 运行 Spark 任务、多表合并执行**的方式实现。

---

## 1. 对标与取舍

| 维度 | 阿里云 DLF | AWS Lake Formation | 本方案 |
| --- | --- | --- | --- |
| 入口 | 表详情「存储优化」页签，内部分三个子页签 | 表级「Enable optimization」独立页 | 表详情新增「存储优化」页签（Paimon 与 Iceberg 表都有，外部表除外；参数按 catalog 格式分派，见 §2.5。Lance 表的面板已就绪，入口待其详情页落地——见 `lance-table-optimization-design.md` §7.5） |
| 开关形态 | 「智能存储优化」总开关 + 三个子页签各自配置 | 三项勾选（Compaction / Snapshot retention / Orphan file deletion） | 总开关 + 三项独立启用（AWS 形态，中文文案取 DLF） |
| 配置 | 每项一页，字段平铺 | **默认设置 / 自定义设置**两档，默认值来自 Data Catalog settings | 采用 AWS 两档：默认值收敛在 **catalog 级优化设置**，表级只写差异 |
| 历史 | 各子页签内一份「执行历史」 | 页面底部三块 history（Compaction / Snapshot retention / Orphan file deletion） | 表页签内三块历史（AWS 形态）；跨表视角另设全局「优化任务」页 |
| 执行 | 服务端定时调度 | 托管 optimizer，按 DPU 计费 | k8s 上的 Spark 任务，服务端负责调度、组批、提交与状态跟踪 |

取舍理由：

- **AWS 的分层更合理**：「要不要开」（三项勾选）与「怎么开」（默认/自定义）分离，默认设置让用户零配置即可启用——这与本工程已有的「catalog 级默认表属性（`table-default.*`）」思路一致，默认值放 catalog 一层，表级只存覆盖项。
- **不照搬 DLF 的三个子页签**：三个子页签把「配置」和「历史」拆在三页里，而三项动作的启用/停用本来就该一眼看全（AWS 一页三项勾选）；且我们的执行模型是**多表合并批次**，表内的历史天然按动作分块展示明细行，不需要子页签承载。
- **吸收 DLF 的两处好设计**：① 最早 snapshot 的详情卡（当前个数 / 最早快照 ID / 提交时间 / 类型 / 行数），放在 Snapshot Expire 配置下方，让用户在清理前看得见现状；② Orphan File Remove 的「立即清理」按钮（带干跑预览），因为孤儿文件清理是三类中唯一**有误删风险**的操作，手动触发 + 预览是必要的兜底。

---

## 2. 三种优化与 Paimon 能力的映射

三种动作都不需要发明新机制，Paimon 已有对应 procedure（Spark `CALL` 调用），优化任务本质是**按表的配置把这三个 procedure 编排执行并记录前后指标**。

### 2.1 Compaction（小文件合并）

| 项 | 说明 |
| --- | --- |
| Paimon 原生 | `CALL sys.compact(table => 'db.t', ...)`（append 表与主键表均支持） |
| 策略 | ① **小文件合并**（对应 AWS Binpack，默认推荐）：把小文件合并到目标大小；② **排序合并**（对应 AWS Sort / Z-Order）：`sort-compaction.strategy = order / zorder / hilbert` + 指定排序列 |
| 可配参数 | `min-input-files`（触发阈值，参与合并的最少小文件数，默认 5）；`target-file-size`（目标文件大小，默认 128 MB）；排序策略 + 排序列（仅排序合并） |
| 主键表补充 | 走 LSM compaction，可配 `full-compaction.delta-commits`；append 表忽略 |
| 指标 | 合并前/后文件数、合并前/后总大小（直接支撑效果图历史列与 DLF 同款列） |

### 2.2 Snapshot Expire（过期快照清理）

| 项 | 说明 |
| --- | --- |
| Paimon 原生 | `CALL sys.expire_snapshots(options => 'snapshot.time-retained=24 h', retain_min => 10)`（`older_than` 收的是时刻、不是时长，见 §2.5 后的时间窗口说明） |
| 可配参数 | `保留时间`（快照保留时长，默认 24 h——DLF 截图里是 1 h，但 1 h 对可回溯性太激进，默认取保守值）；`最少保留个数`（默认 10）；`执行间隔`（默认 24 h，对齐 AWS 的 deletion run rate） |
| 安全性 | Paimon expire 自身会尊重 consumer 位点与标签引用，不会删被挂住的快照；服务端仍在 item 上记录 `删除快照数 / 释放空间` |
| 展示 | 配置卡下方放「快照现状」卡（吸收 DLF）：当前 snapshot 个数、最早 snapshot 的 ID / 提交时间 / 提交类型 / 表总行数——数据源就是已有的 `lakehouse_snapshot` 表 |

### 2.3 Orphan File Remove（孤儿文件清理）

| 项 | 说明 |
| --- | --- |
| Paimon 原生 | `CALL sys.remove_orphan_files(older_than => ..., dry_run => ...)` |
| 可配参数 | `清理早于`（只删 modification time 早于该窗口的文件，默认 **1 天**）；`执行间隔`（默认 **7 天**）；`干跑预览`（默认开启：先以 dry_run 跑一遍，把将删除的文件数/大小展示出来，确认后再真删） |
| 安全约束（重点） | `olderThan` 必须**大于表的最大提交间隔**，否则会把正在写入的数据文件当孤儿删掉——表单强校验（最小 1 h，默认值 1 天），文案显式警告；这也是默认「执行间隔 7 天 + 手动触发 + 干跑」的原因 |
| 展示 | 扫描文件数、删除文件数、释放空间；手动「立即清理」按钮（吸收 DLF），点击后先出干跑结果确认框 |

### 2.4 一批内的动作顺序

同一批任务里对同一张表按 **compact → expire → orphan** 的固定顺序执行：compaction 产生新快照后，expire 才能基于最新状态判过期；orphan 最后跑，且它的 `olderThan` 窗口天然保护前两步刚产生的文件。顺序写死在优化任务里，不给用户配（配错的收益为零、风险为删数据）。

---

### 2.5 Iceberg 的对应物

三项动作在 Iceberg 里都有官方 procedure，语义与 Paimon 一一对应，因此**没有新增任务信息表**：
`lakehouse_table_optimization`（策略）、`optimization_job` / `optimization_job_item`（批次与明细）
原样复用——它们存的本来就是「动作 + 键值对参数」和「动作 + 指标」，格式差异只体现在键名与指标口径上。

| 项 | Paimon | Iceberg |
| --- | --- | --- |
| Compaction | `CALL sys.compact(...)` | `CALL catalog.system.rewrite_data_files(...)` |
| Snapshot Expire | `CALL sys.expire_snapshots(...)` | `CALL catalog.system.expire_snapshots(...)` |
| Orphan Remove | `CALL sys.remove_orphan_files(...)` | `CALL catalog.system.remove_orphan_files(...)` |
| 参数来源 | `OptimizationOptions` | `IcebergOptimizationOptions`（`support/` 下独立一个类） |
| 参数键 | `strategy` / `sort-columns` / `min-input-files` / `target-file-size-mb` / `retain-min` / `retention-hours` / `older-than-hours` / `interval-*` / `dry-run` | 同名为主，三处不同：`retain-min` → **`retain-last`**、新增 **`max-concurrent-file-group-rewrites`**、`target-file-size-mb` 默认 **512** |
| 内置默认 | compact `{binpack, 5, 128MB, 1h}`；expire `{24h, 10, 24h}`；orphan `{72h, 7d, dry-run}` | compact `{binpack, 5, **512MB**, 5 并发, 1h}`；expire `{**120h**, 10, 24h}`；orphan `{72h, 7d, dry-run}` |
| 窗口参数 | expire `options => 'snapshot.time-retained=24 h'`（**时长**，Paimon 自己折算）；orphan `older_than => '2026-10-27 10:00:00'`（**绝对时间戳**，任务侧按 JVM 默认时区格式化） | `older_than => TIMESTAMP '...'`（**绝对时间戳**，任务侧按 UTC 格式化）；两侧都是绝对时刻 |

**为什么参数表要单开一个类而不是加 if。** 两边的键名能对上「策略 / 阈值 / 保留窗口」这三件事，
但 `retain-min`（最少保留几个快照）与 `retain-last`（保留最后几个祖先快照）是**语义相反**的一对，
`target-file-size` 的默认值也不同（128 MB vs 512 MB，各自跟随上游的推荐值）。把它们塞进一张表，
「某个键属于哪种格式」就变成一张隐式的真相表，而写错的那一侧不报错、只表现为行为悄悄变成另一种格式的语义。

**zorder 在 Iceberg 里不是独立策略。** Paimon 的 `sort-compaction.strategy` 有 `zorder` 取值；
Iceberg 的 `rewrite_data_files` 只有 `strategy => 'binpack' | 'sort'`，Z-Order 是
`strategy => 'sort'` + `sort_order => 'zorder(c1,c2)'` 的组合。控制台仍按「策略」这一档呈现三种选择，
翻译发生在执行侧（`IcebergProcedures`），并在控制台文案里说明「Z-Order 建议 2～4 列」。

**时间窗口：Paimon 的两个 procedure 各只有一种写法——这是踩过坑才写清的。**

Paimon 的 `older_than` **不是时长**，而是「窗口起点这个时刻」（2.0.0 如此，2.1.0 照旧）：
`ExpireSnapshotsProcedure` 走 `ProcedureUtils.fillInSnapshotOptions`、
`RemoveOrphanFilesProcedure` 走 `OrphanFilesClean.olderThanMillis`，两条路都是
`DateTimeUtils.parseTimestampData(olderThan, 3, TimeZone.getDefault())`，没有「时长」这个分支。
早先 expire 与 orphan 共用一句 `older_than => '24 h'`（读起来很自然地像相对时长），集群上直接
`DateTimeParseException: Text '24 h' could not be parsed at index 0`，整张表 FAILED。

收时长的键是表属性 `snapshot.time-retained`。于是三处各走各的路：

| | 我们给的参数 | 谁把「保留 N 小时」折算成时刻 |
| --- | --- | --- |
| Paimon expire | `options => 'snapshot.time-retained=24 h'` + `retain_min` | Paimon，在**执行瞬间**折算（正好 N 小时）；时长语法由 `CoreOptions` 解析 |
| Paimon orphan | `older_than => '2026-10-26 10:20:20'`（绝对时刻，任务侧按 **JVM 默认时区**格式化） | 我们，批次开始前算好——它没有 `options` 形参，表达不了时长 |
| Iceberg expire / orphan | `older_than => TIMESTAMP '...'`（绝对时刻，任务侧按 **UTC** 格式化） | 我们（Iceberg 看 `spark.sql.session.timeZone`，会话已钉 UTC） |

为什么 Paimon 这两处不能统一：`expire_snapshots` 有形参 `options`，`remove_orphan_files` 没有。
为什么 Paimon 侧格式化必须用 JVM 默认时区：解析方就是同一个 JVM 的 `TimeZone.getDefault()`
（procedure 在 driver 进程里跑，调用栈里能看到 `SparkSession.sql`），同进程同时区 → 往返严格相等；
若照 Iceberg 那样按 UTC 写，而容器 TZ 是 +08:00，窗口就平移 8 小时，orphan 的后果是**多删文件**。

expire 其实也能用 `older_than` 表达同一件事（我们算好 `now - N 小时`），差别在折算时刻：那个 `now`
是批次开始前取的，compaction 跑了几小时，窗口就宽几小时（方向偏保守，多留不少删）；而且它要自己
格式化时间戳，就多了一条「格式化时区 ≠ 解析时区」的失败面。既然策略参数叫「保留时长」，就用时长形态。

两条护栏，都在 `PaimonProceduresTests`：`orphanWindowIsParseableByPaimonItself` 用 Paimon 自己的
`DateTimeUtils` 做往返校验；`retentionOptionSurvivesPaimonOwnOptionParsing` 把语句里的选项喂回
`CoreOptions` + `ProcedureUtils.fillInSnapshotOptions`，断言最终 `ExpireConfig.snapshotTimeRetain == 24h`
——键名写错一个字符时 Paimon 读不到这个键、也不会报错，「保留 24 小时」就悄悄退化成表的默认 1 天
（默认值恰好也是 1 天，肉眼更难发现），只有这条断言能发现。

**指标口径不顺译。** 执行侧报的是 procedure 真实输出的列，不是把 Iceberg 的读数套进 Paimon 的键：

| 动作 | Paimon 指标 | Iceberg 指标 | Lance 指标 |
| --- | --- | --- | --- |
| compact | `files-before` / `files-after` / `size-before` / `size-after` | `rewritten-files` / `added-files` / `rewritten-size`（`rewrite_data_files` 不报「合并后一共几个文件」） | `fragments-removed` / `fragments-added` / `files-removed` / `files-added`（`OPTIMIZE` 只报增量，没有 before/after） |
| expire | `deleted-snapshots` | `deleted-data-files` / `deleted-manifest-files` / `deleted-manifest-lists`（`+ deleted-statistics-files`，>0 时才报） | `old-versions` / `bytes-removed`（人类可读，采集侧就转好） |
| orphan | `deleted-files` / `released-size` | `deleted-files`（输出是「一行一个孤儿路径」，取 `count()`；干跑时就是候选条数） | —（`VACUUM` 已包含，这一项在 Lance 上不存在） |

控制台侧 `metricSummary()` 因此**不判格式、只判键存在**——三套键在同一动作下互不重叠，
批次页（一批里可能混着三种格式的表）也就不需要知道每张表的格式。

> 备注：`CALL` 在 Iceberg 里由 `IcebergSparkSessionExtensions` 注册成 procedure，
> 只把 `iceberg-spark-runtime` 放进类路径不够（见 §6.5）。

### 2.6 Lance 的对应物（设计稿，未实现）

Lance 面见 [`lance-table-optimization-design.md`](lance-table-optimization-design.md)。与上面两节的关系只有三条要点，其余全部复用本文：

| 项 | Paimon / Iceberg | Lance |
| --- | --- | --- |
| 语句 | 三条 `CALL` | **两条解析器命令**：`OPTIMIZE`（= compact）、`VACUUM`（= expire **兼** orphan） |
| 动作数 | 三 | **两**。`orphan` 恒不可用（`VACUUM` 一步就做完），响应里用动作级 `supported=false` 表达，不是前端硬隐藏 |
| 指标 | 前后快照（Paimon `$files`）/ procedure 计数 | **只有增量四元组**（`fragments_removed` / `fragments_added` / `files_removed` / `files_added`），Lance 在 Spark 里没有系统表可查 |

三个动作的**名字、执行顺序、批次状态机、回调协议、并发闸门、`SKIPPED` 语义全部不变**——本文 §3 之后的每一节都适用于 Lance。真正会「静默走错」的只有两处二元分派：服务端 `OptimizationOptions` 的 7 个 `isIceberg(format) ? … : …` 重载，与执行侧 `TableOptimizerJob.isIceberg(plan)`；不改它们，Lance 批次会用 Paimon 的键表校验参数、并用 Paimon 的会话去连 `/v1/{prefix}`（于是失败信息说「表不存在」）。

---

## 3. 执行架构

### 3.1 总体流程

```
控制台/管理 API 写策略
        │
        ▼
┌─────────────────────────┐   每分钟扫描（服务端调度器）
│ lakehouse_table_optimization│ ──────────────────────────┐
└─────────────────────────┘                            ▼
                                        ┌──────────────────────────┐
                                        │ 组批：到期且可入批的策略      │
                                        │ 按 catalog 分组 → 1 组 1 批  │
                                        │ 写 job(PENDING) + items     │◄─ 先落库，再执行
                                        └────────────┬─────────────┘
                                                     ▼ 异步（提交线程池）
                                        ┌──────────────────────────┐
                                        │ 提交 k8s（SparkApplication │
                                        │  / k8s Job，配置化）        │
                                        └────────────┬─────────────┘
                                                     ▼
                                          RUNNING（记录 pod / spark app id）
                                                     │
                            优化任务逐表回调上报 ──────┤
                                                     ▼
                                    收尾：items 全终态 → job 终态
                                    SUCCESS / PARTIAL_SUCCESS / FAILED
```

关键规则：**任何执行都先有记录**——job 与 items 先以 `PENDING` 落库，再异步提交；服务端重启后 `PENDING` / `SUBMITTING` / `RUNNING` 的 job 会被调度器重新认领（对 k8s 侧做幂等对账：查到同名资源就续跟踪，查不到就置 FAILED），不会出现「任务在跑但库里没记录」或「库里 PENDING 但没人管」的悬空态。

### 3.2 状态机

**job（批次）**：

| 状态 | 含义 |
| --- | --- |
| `PENDING` | 已落库，待提交 |
| `SUBMITTING` | 提交 k8s 中（短状态，提交异常会回到 `FAILED` 并保留错误信息） |
| `RUNNING` | k8s 侧运行中 |
| `SUCCESS` | 全部 item 成功 |
| `PARTIAL_SUCCESS` | 批次退出但部分表失败（合并执行的批次需要这个中间态，单表任务不会出现） |
| `FAILED` | 提交失败 / 超时 / 全部失败 |
| `CANCELED` | 用户取消（k8s 侧删除运行资源） |

**item（表级明细）**：`PENDING → RUNNING → SUCCESS / FAILED`，另有 `SKIPPED`（不满足触发条件，如 compaction 的当前文件数没到 `min-input-files`——这是正常分支，不算失败，历史里显式展示「跳过（未达阈值）」）。

### 3.3 组批（合并执行的规则，本方案的核心取舍）

1. **入批资格**：调度扫描发现策略到期后逐项判定——compaction 要查当前小文件数是否 ≥ `min-input-files`（不满足不入批；如果三项都不满足，只推进 `next_run_at` 不产生 job）；expire / orphan 按「距上次执行 ≥ 各自间隔」判定。
2. **分组**：同一批只放**同一个 catalog** 的表——它们共享 warehouse 与存储凭据，也共享该 catalog 的
   **执行集群**（提交到哪个 k8s、用什么身份、镜像与运行位置），优化任务用同一 catalog 配置建一次 REST catalog 即可处理全部表。
   这也是执行集群为什么按 catalog 配而不是按批次配：批次的边界本来就是 catalog。
3. **上限**：每批最多 `maxTablesPerJob` 张表、每个 catalog 最多 `maxConcurrentJobs` 个批次、单批 `jobTimeoutSeconds` 超时——
   **这三个值取自该 catalog 的执行集群配置，不再有全局版本**（不同集群的余量不一样，一套全局数字只能按最小的那台配）。
   超出上限拆成下一批；拿不到并发额度的批次留 `PENDING` 排队。并发是**两道闸门**，数的口径不同：出批侧数「已承诺的批次」（含排队中的 `PENDING`），决定**还该不该造新批次**；提交侧数「已放出去的」（`SUBMITTING`/`RUNNING`），决定**造好的批次放不放行**。两道都要有：手动「立即优化」与重试都是直接建批次，不经过出批那条路，少了提交侧这道就能绕过上限——而这条上限本来就是给集群留余量用的（每个批次一个 Spark 驱动）。放不出去的批次留在 `PENDING` 队列里按创建时间先后等下一拍，不是被丢掉。
   **两个上限都按 catalog 各自算**：A 集群满了不妨碍 B 集群出批。
4. **单表立即执行复用同一条路**：控制台「立即优化」= 产生一个单 item 的批次，走完全相同的提交与跟踪链路，不另写一条执行路径。
5. **防重复**：同一张表存在未终态 item 时不再入批；手动触发与调度触发撞车时以先落库者为准，后来者对同表报「已有进行中的优化任务」。

为什么不「一表一 Spark 任务」：表规模上去后 driver/executor 的固定开销（每个任务一个 pod、一次镜像拉取、一次 SparkContext 启动）会把 k8s 调度打满，而优化本身是低频、轻负载操作；Paimon 的三个 procedure 都接受表名参数，一个任务内逐表顺序执行即可。批次上限 20 是防止单批跑太久——一批挂了重试的代价要可控（重试只重跑 `FAILED` 的 item，见 3.5）。

### 3.4 k8s 提交与跟踪

提交侧只认**该 catalog 的执行集群配置**（§4 的 `lakehouse_catalog_execution_cluster`，
完整设计见 [`catalog-execution-cluster-design.md`](catalog-execution-cluster-design.md)）：
一个 catalog 对应一台 k8s 集群，认证也在 catalog 级配（自建集群给 kubeconfig，EKS / AKS 给令牌）。
服务端自己不再持有任何集群级提交参数——`lakehouse.rest.optimization` 下那 13 个键已整体删除。

| 项 | 设计 |
| --- | --- |
| 提交方式 | **fabric8 `KubernetesClient` 直接建 `batch/v1 Job`**（`Fabric8JobSubmitter`）。上一版用 Spring `RestClient` 手拼 HTTP，等于自己维护一份 k8s 客户端；换成 fabric8 后序列化、`deletionPropagation`、状态机读取都由库负责。**不引入 Spark Operator**：它的自动重试与「compaction 重跑非幂等」直接冲突，cron 触发又与 `next_run_at` 抢占重叠，而我们要的只是一个 Job → 收益不抵复杂度 |
| 客户端构造 | `Config.fromKubeconfig(contextName, text, null)` 或 `fromToken`；**必须 `withAutoConfigure(false)`**，否则 fabric8 会悄悄读 `~/.kube/config` 或集群内 SA——那意味着「配的是 A 集群，实际打到 B 集群」。构造后再断言 `masterUrl` 与配置里的 `apiServer` 一致。超时统一 10 s（调度线程是单线程，不能被一个不可达的 apiserver 拖住） |
| 客户端缓存 | key = `catalogId`，value = `(client, configHash)`；LRU 上限 32。配置变更、删除配置、`@PreDestroy` 三处都要 `client.close()`，否则连接池与 watch 线程泄漏 |
| 镜像与运行位置 | 镜像、`imagePullPolicy`、`imagePullSecrets`、`serviceAccountName`、`namespace`、`callbackBaseUrl` 全在执行集群里；服务端**只传** `--job-id`、`--management-url`、`--token`（+ 可选 `--catalog-token`） |
| 明细获取 | 优化任务启动后**回调管理 API 拉取本批次的表清单与每表配置**，不走命令行参数——避免表多了参数超长，也保证「库里是什么就执行什么」 |
| 状态跟踪 | 服务端定时轮询（默认 30 s）Job 状态推进批次状态（先看 conditions 再看 `succeeded`/`failed` 计数）；**表级结果由优化任务逐表回调上报**（每完成一张表 POST 一次 item 结果：成功/失败 + 前后指标），比解析日志可靠 |
| 终态判定 | k8s 侧到达终态 **且** 全部 item 到达终态，两者对上才收尾；只有 k8s 终态而 item 仍非终态（如任务没逐表回调就崩了）→ 整批置 FAILED，错误信息取 pod 退出码与日志尾部 |
| 超时 | 每批 deadline 取执行集群的 `jobTimeoutSeconds`（默认 7200 s），超时置 FAILED 并清理 k8s 资源（Job 自带 `ttlSecondsAfterFinished`，不需要额外清扫） |
| 重试 | job 级「重试」= 只把 `FAILED` 的 item 组成新批次重跑，成功的不动。**k8s 侧 `backoffLimit=0`**，不让 apiserver 替我们重跑一个非幂等的 compaction |
| 探测 | `POST .../execution-cluster/probe` 五步现场问答：`credential`（真调一次 STS / 真取一次 AAD 令牌）→ `identity`（`/version` + `SelfSubjectReview`）→ `rbac`（三个 `SelfSubjectAccessReview`）→ `admission`（`dryRun=All` 建 Job + 查 SA）→ `callback`（服务端 GET，且文案明说「只验证了一个方向」）。轻探活只跑前 3 步，全探活跑 5 步 |

**fail-closed 的两处入口**（配置不可派发 ≠ 配置能提交，两者都要拦）：

- 写策略（`enabled=true`）与「立即优化」→ **400**，文案指名是未配置 / 停用 / 改完没验证 / 验证失败中的哪一种。
- 调度出批 → 整组跳过且**游标不推进**（推进了就等于「假装跑过」，下一拍这张表还在，但这一轮机会被吃掉）。
  已入队但提交阶段失败的批次按终态 FAILED 收尾并指名原因，不回退到「没配」。

### 3.5 安全

- 优化任务使用专用主体 `optimizer`（管理 API 建），权限收敛为：本批次表的 `TABLE_READ_META` + `TABLE_WRITE_DATA`（compact/expire 需要写提交）+ `TABLE_DROP_DATA`（orphan 清理需要）——按 AWS 那张「Grant required permissions」提示卡的思路，权限在启用优化时由服务端自动装配，并在 UI 上显式告知。
- 下发给任务的 token 是**短期 token**（有效期 ≥ 批次 deadline 即可），不复用控制台登录态。
- 回调端点要求 job 专属 token；审计列记录触发者（`SCHEDULED` / 手动触发时的主体名）。
- **集群凭据只进不出**：kubeconfig / EKS / AKS 三份认证材料落库前由 `CredentialCipher` 密封，
  任何读接口只回 `has*` 与可公开标识（AccessKeyId / tenantId / clientId）。
  这与「客户端凭据」同一个原则：能写、能验证、不能读回来。
- **kubeconfig 里的 `exec:` 与 `auth-provider:` 一律拒收**：那等于让服务端替用户在本机执行命令，
  是 RCE 边界不是功能缺失。错误文案要给替代路径（换成静态证书 / 令牌，或用 EKS / AKS 类型），
  否则用户只会看到「不支持」而不知道该怎么办。

---

## 4. 数据模型（新增 4 张表）

沿用既有约定：实体继承 `AuditedEntity`（审计列），复杂结构 `@Convert` + `JsonConverters`，乐观并发 `entityVersion`；改实体后重跑 `scripts/gen-mysql-ddl.sh` 并同步 `docs/data-model.md`。

**`lakehouse_table_optimization`（策略，与表 1:1）**

| 列 | 说明 |
| --- | --- |
| `id` / `table_id` | 主键 / 表外键（唯一约束） |
| `enabled` | 总开关（关 = 三项全停，不产生批次） |
| `compaction_enabled` / `compaction_config_json` | 是否启用 + 配置差异（`strategy`、`minInputFiles`、`targetFileSizeMb`、排序列；为 `null` 的键回落 catalog 默认） |
| `snapshot_expire_enabled` / `snapshot_expire_config_json` | 同上（`retentionHours`、`retainMin`、`intervalHours`） |
| `orphan_remove_enabled` / `orphan_remove_config_json` | 同上（`olderThanHours`、`intervalDays`、`dryRun`） |
| `schedule_interval_seconds` | 调度间隔（扫描该表的周期，默认 3600，对齐 DLF 的 compaction 间隔） |
| `next_run_at` / `last_job_id` | 下次到期时间 / 最近一次批次 |

**`lakehouse_optimization_job`（批次）**

| 列 | 说明 |
| --- | --- |
| `id` / `catalog_id` | 批次号（展示用短 ID）/ 所属 catalog（组批以 catalog 为界） |
| `trigger_type` | `SCHEDULED` / `MANUAL`（含触发主体） |
| `actions` | 批次包含的动作集合（JSON，如 `["COMPACT","EXPIRE"]`） |
| `status` | §3.2 的七态 |
| `table_count` / `submitter` | 表数 / 提交方式（`SPARK_OPERATOR` / `K8S_JOB`） |
| `k8s_namespace` / `k8s_resource` / `spark_app_id` | 跟踪定位三件套（pod 名 / CRD 名 / Spark App ID） |
| `submitted_at` / `started_at` / `finished_at` / `deadline_at` | 时间线与超时判定 |
| `error_message` | 失败原因（含 pod 日志尾部摘录） |

**`lakehouse_optimization_job_item`（表级明细，批次 1:N）**

| 列 | 说明 |
| --- | --- |
| `id` / `job_id` / `table_id` | 主键 / 批次外键 / 表外键 |
| `actions` | 该表执行的动作（批内可按表裁剪：某表只开了两项就只跑两项） |
| `status` | `PENDING / RUNNING / SUCCESS / FAILED / SKIPPED` |
| `metrics_json` | 前后指标（合并前/后文件数与大小、删除快照数、删除孤儿文件数、释放空间） |
| `error_message` / `reported_at` | 单表失败原因 / 回调时间 |

**`lakehouse_catalog_execution_cluster`（执行集群，与 catalog 1:1）**

| 列组 | 说明 |
| --- | --- |
| `catalog_id` | 唯一约束 + 级联删除：一个 catalog 最多一行，删 catalog 时配置跟着走（不留孤儿目标） |
| 连接 | `cluster_type`（`SELF_HOSTED` / `EKS` / `AKS`，类型即认证）、`api_server`、`context_name`、`namespace`、`ca_cert_pem` |
| 凭据 | `kubeconfig_sealed`、`auth_config_sealed`、`catalog_token_sealed`——`CredentialCipher` 密封后的整份明文；读模型只回 `has*` |
| 运行位置 | `image`、`image_pull_policy`、`image_pull_secrets`、`service_account`、`callback_base_url`（上一版散在 yml 里的提交参数） |
| 批次策略 | `max_tables_per_job`、`max_concurrent_jobs`、`job_timeout_seconds` |
| 探活 | `config_hash` / `probe_status` / `probe_reason` / `probe_message` / `probe_config_hash` / `probed_at`——五态由它们派生，不另存状态列 |

字段语义、五态状态机与「`configHash` 用密文算」这类坑见
[`catalog-execution-cluster-design.md`](catalog-execution-cluster-design.md) §6 / §9；
表在库里的样子见 [`data-model.md`](data-model.md) §2.5。

---

## 5. API 设计

全部挂管理 API 基址 `/api/management/v1`（这是管理性质的能力，不走引擎用的 `/v1/{prefix}` catalog API，也就不涉及 `CatalogAccessRules` 登记）：

| 端点 | 说明 |
| --- | --- |
| `GET / PUT /optimization/catalogs/{catalog}/settings` | catalog 级默认优化设置（三项各自的默认参数） |
| `GET / PUT /optimization/tables/{catalog}/{database}/{table}/policy` | 表策略读写（总开关 + 三项 + 差异配置） |
| `POST /optimization/tables/{catalog}/{database}/{table}/run` | 立即优化（body 指定动作，可多选；产生单表批次） |
| `GET /optimization/tables/{catalog}/{database}/{table}/items` | 单表优化历史（表详情页三块历史一次取齐——批次列表不带表级明细，逐批拉详情是 N 次请求换一屏数据） |
| `GET /optimization/jobs?status=&catalog=&action=&limit=` | 批次列表（全局「优化任务」页） |
| `GET /optimization/jobs/{jobId}` | 批次详情（含 items——表内历史三块的数据源就是按表过滤的 items） |
| `POST /optimization/jobs/{jobId}/retry` / `POST .../cancel` | 重试（只重跑 FAILED item）/ 取消 |
| `GET /optimization/jobs/{jobId}/submission` | **回调端点**（optimizer 专用 token）：任务启动时拉执行清单 |
| `POST /optimization/jobs/{jobId}/items/{itemId}` | **回调端点**（同一 token）：运行中逐表上报结果 |
| `GET / PUT / DELETE /catalogs/{catalog}/execution-cluster` | 执行集群配置读写与删除（优化任务的落点；完整契约见 [`catalog-execution-cluster-design.md`](catalog-execution-cluster-design.md) §8） |
| `POST /catalogs/{catalog}/execution-cluster/kubeconfig/parse` | 解析 kubeconfig，逐 context 给 `OK / WARN / UNSUPPORTED / UNAVAILABLE` 结论（**不落库**，纯预检） |
| `POST /catalogs/{catalog}/execution-cluster/probe?full=` | 现场探活五步（`full=false` 只跑前 3 步） |

几点约定：

- **对外一律用 catalog 名与「catalog.库.表」三段名**，不用内部主键。catalog 名是 optimizer 建 REST catalog 连接时要用的那个值，主键只在服务端内部有意义；批次的 `catalog` 字段因此也是名字。外键 id 只在回调端点里按 `itemId` 出现（清单里给，任务原样带回来）。
- **catalog 级默认键形如 `optimization.<动作>.<参数>`**，动作段用 wire 名：`optimization.compact.min-input-files`、`optimization.expire.retention-hours`、`optimization.orphan.older-than-hours`。与 API 的 `action` 字段、库里的 `actions` 列同一套词——不为 catalog 属性另起一套更长的英文名，否则两处映射必然漂移。
- **回调令牌**（请求头 `X-Optimization-Token`，批次级、落库随机值）不参与 RBAC：调用方是 k8s 里的任务，没有主体身份。任务从 Job 的启动参数里拿到它。批次详情是否回显令牌由一条规则决定（授权关闭，或调用方是服务管理员）——`run` / `retry` / `cancel` / 详情四个端点共用这一条，不各判一次。
- **这批端点对两种格式都回答，`format` 是响应里带出来的，不是路径里的。** 曾经有一段时间 Iceberg catalog 上
  这些端点全 404（连 `GET .../policy` 也是），原因不是格式守卫在 controller，而是 `TableOptimizationService`
  借道了 Paimon 的 `TableLookup` → `DatabaseService.require` → `resolveForFormat(prefix, PAIMON)`。
  现在按 `catalogRepository / databaseRepository / tableRepository` 直查，格式只用来挑参数表
  （`TablePolicy.format`、`Submission.format`，供 optimizer 与前端读）。

---

## 6. Spark 优化任务（模块 `lakehouse-rest-optimizer`）

独立 maven 模块，main class `TableOptimizerJob`，打成镜像；与 `lakehouse-rest-spark` 共用父 POM
（同一个依赖世界：Spark 由集群提供、Jackson 2 对齐发行版）。

**启动参数只有三个（外加一个可选）**，由服务端生成 k8s Job 时写入：

| 参数 | 说明 |
| --- | --- |
| `--job-id` | 批次 id |
| `--management-url` | 管理 API 基址，由执行集群的 `callbackBaseUrl` **加上** `/api/management/v1` 拼出（`OptimizerJob.managementApiUrl`）；catalog API 基址再由它去掉该后缀推出，可用 `--catalog-url` 覆盖 |
| `--token` | 批次级回调令牌（请求头 `X-Optimization-Token`） |
| `--catalog-token` | **可选**：catalog 数据面令牌。批次令牌只对优化回调端点有效，不能当 catalog 凭据——任务要访问 `/v1/{prefix}` 才能拿到 Paimon 表。没开认证的部署不传这个参数 |

**执行骨架**：拉清单 → 建一次 Spark 会话 → 逐表按 compact → expire → orphan 顺序执行 →
每表回调上报结果 → 全部结束退出（全部成功/跳过 exit 0，有失败 exit 1）。

- **表清单与配置都从回调端点拉，不走命令行**：一批最多 `maxTablesPerJob` 张表（默认 20，
  取自该 catalog 的执行集群）、每张三项配置，序列化进参数会让命令行失控，
  而且会让「集群里跑的那份参数」成为策略的第二个版本。
- **每张表独立 try/catch**：一张表失败不拖死同批其它表（这正是 `PARTIAL_SUCCESS` 存在的意义）。
- **失败时打印失败的那条语句**：步骤循环内部 catch 后先打一行
  `table demo.t2: expire step failed, statement: CALL ...`（按 `statement:` 一搜就是全批的失败语句），
  再把语句塞进 `StepFailure` 抛出——上报给服务端的失败原因因此也带着它
  （`expire failed. SQL: CALL ... <- DateTimeParseException: ...`），控制台的「说明」列里就能直接照着重放，
  不必先去翻 Pod 日志。语句排在 cause 链**前面**：`error_message` 列宽 1000，后面的链可以被截，语句不能
  （任务侧仍按 900 字收口，留 100 字给它）。
- **已经上报过结果的表会被跳过**（清单里带 `status`）：pod 被驱逐后重排时，compaction 重跑
  不是「无副作用的重试」，它会重写文件。
- **三个动作都走 `CALL` 语句，不依赖 Paimon 的 Java API**：procedure 的参数名与取值语义随
  Paimon 版本变，交给目标集群的 Paimon 解释，本模块的产物就不会把 Paimon 版本焊死。
  参数名与类型是照 2.1.0 的 class 文件反解出来的，逐项对应（`compact` 八个参数全是 StringType，
  其中 `options` 是 `k1=v1,k2=v2` 字符串；`expire_snapshots` 的 `retain_max`/`retain_min`/
  `max_deletes` 是整数；`remove_orphan_files` 的 `dry_run` 是布尔），因此渲染时按类型走
  ——把整数写成 `'10'` 在 ANSI 模式下会直接抛转换错误。
- **指标**：compaction 前后各查一次 Paimon 的 `$files` 系统表（文件数 + 总大小）算前后对比；
  expire/orphan 直接用 procedure 的返回行（`deleted_snapshots_count` /
  `deletedFileCount` / `deletedFileTotalLenInBytes`）。采集口径在任务侧定，一次采集到处展示。
  这条 SQL 有三处必须写对，少一处就是解析期或解析后的失败：

  | 要素 | 写法 | 写错的后果 |
  | --- | --- | --- |
  | `$` | 表名段加反引号：`` paimon_catalog.demo.`t2$files` `` | `FROM demo.t2$files` 直接 `PARSE_SYNTAX_ERROR ... at or near '$'`——`$` 不是 Spark 未加引号标识符的合法字符 |
  | catalog 前缀 | 必须全限定 | 清单里的表名是 catalog 内的 `database.table`，少了前缀会去默认的 `spark_catalog` 找表 |
  | 反引号位置 | 只包最后一段 | 包整串（`` `demo.t2$files` ``）会变成一个单段标识符，解析能过但语义完全不同 |

  另外**指标读不到不阻塞 compaction**：系统表要自己读 manifest 与文件（对象存储还要 data-token），
  任何一环不成立都只是少两组数字。`file://` 仓库尤其如此——优化任务的 Pod 不挂载服务端那个
  hostPath 目录，`$files` 读不到文件，此时任务按「没有指标」继续跑，而不是整表 FAILED。
  重跑一次 compaction 会重写文件，代价远大于少两行数字。
- **什么都没动时上报 `SKIPPED` 而不是 `SUCCESS`**：未达阈值、没有过期快照、没有孤儿文件
  都属这一类，控制台上要能一眼分出「真的合了文件」和「扫了一遍没动」。
- **保留时长要用 `snapshot.time-retained`，不要塞给 `older_than`**：Paimon 的 `older_than`
  只认绝对时间戳（`yyyy-MM-dd HH:mm:ss`），写 `'24 h'` 会在执行期 `DateTimeParseException`。
  升级到 2.1.0 时逐项核对过：`expire_snapshots` 仍带 `options` 形参、`remove_orphan_files`
  仍然没有，两条语句因此都不用改。
  expire 有 `options` 形参可给时长；orphan 没有，只能由任务侧算成时刻，且必须按 **JVM 默认时区**
  格式化（解析方用同一时区；照 Iceberg 那样按 UTC 写，容器时区不是 UTC 时窗口就平移，orphan 会多删文件）。

单元测试覆盖参数翻译（`PaimonProceduresTests` / `IcebergProceduresTests`）、启动参数解析
（`OptimizerOptionsTests`）与系统表 SQL 的形状（`TableOptimizerJobTests`，用真实的
`SparkSqlParser` 断言「反引号写法能解析、裸 `$` 解析失败」，而不是把解析规则抄进断言）。
这几块都是「编译能过、跑起来才错」的高风险区。真跑一张表的
端到端用例属于 P3（需要一个可用的集群与仓库）。`IcebergProceduresTests` 除参数翻译外还钉住了三条
容易写错的地方：binpack 不传 `strategy`、zorder 走 `sort` + `sort_order`（且**不含** `z_order_columns`
——那是别人的参数名）、`expire` / `orphan` 的时间戳是 UTC 字面量。

### 6.5 Iceberg 与 Lance 的差异（同一模块内分派）

执行流程（跳过已上报的表、部分成功、耗时、失败上报、重试）三种格式**完全共用**，
只在两处分派，其余一处也不分：

| 分派点 | Paimon | Iceberg | Lance |
| --- | --- | --- | --- |
| `steps()`：语句怎么生成 | `PaimonProcedures.steps(...)` | `IcebergProcedures.steps(...)` | `LanceProcedures.steps(...)`（`OPTIMIZE` / `VACUUM`，parser 级命令） |
| `spark()`：会话怎么建 | `spark.sql.catalog.<c>.metastore=rest` + `data-token.enabled=true` + Paimon 扩展 | `.type=rest` + `.header.X-Iceberg-Access-Delegation=vended-credentials` + `cache-enabled=false` + Iceberg 扩展 | `.impl=rest` + `.uri=<catalogUrl>/lance` + `.parent=<catalog>` + `.headers.Authorization=Bearer <token>` + `LanceSparkSessionExtensions` |

Lance 那一路的完整说明（两条语句的映射、参数键表、`--add-opens` 为什么只能写 `ENV`、
镜像里的 arrow 类冲突）在 [`lance-table-optimization-design.md`](lance-table-optimization-design.md)，
这里只记与本节相关的三条：**格式分派改成穷尽 `switch`**（`enum Format`，`isIceberg(plan)`
已删除——它是「加第三种格式就静默走错分支」的根源）、**动作只有两项**（`orphan` 由 `VACUUM`
包含）、**指标只有增量**（没有 before/after，见 §4 的指标表）。

- **Iceberg 的 `CALL` 需要 `IcebergSparkSessionExtensions`**：只把 `iceberg-spark-runtime` 放进类路径
  是不够的，procedure 由这个扩展注册。扩展在 `spark()` 里按批次格式设置。
- **`cache-enabled=false` 不是性能选项**：批内同一张表会被反复读（每个 procedure 都会重新加载元数据），
  客户端缓存会让后一步读到前一步之前的快照。
- **`X-Iceberg-Access-Delegation: vended-credentials`**：服务端「不问不给」——带这个头才会在响应里
  下发短期凭据。仓库不在对象存储上的部署里，它只是一个没被消费的响应字段。
- **镜像要带 `iceberg-spark-runtime`**：`Dockerfile` 从 `target/jars/` 里拷；`s3://` 仓库还要自行加
  `iceberg-aws-bundle`（本轮**未内置**：本地 `~/.m2` 里那个 artifact 目录是空的，离线构建会失败）。
  版本必须与 `spring-parent` 的 `iceberg.version` 一致——优化任务是另一个依赖世界，属性不共享。
- **格式从执行清单里读**（`Plan.format`），缺字段时按 Paimon：那是本特性引入前的唯一形状。

---

## 7. 控制台

- **表详情新增「存储优化」页签**：Paimon 侧仅 `isPaimonTable` 时渲染（format/object 表没有快照与 compaction 概念，页签隐藏——与「快照/标签/分支」同一条伸缩规则）；按需加载（首次切入才请求策略与历史）。
  **Iceberg 表用的是同一个组件**：`IcebergTableView` 挂 `TableOptimizationPanel` 并显式传 `format="ICEBERG"`，
  组件内部按「策略响应的 `format` → prop → `PAIMON`」取格式，再用 `actionsFor(format)` 取那一套参数定义。
  不另做一个面板的理由与「三种格式共用任务表」一致：动作名、执行顺序、三块历史、批次链接、干跑警示
  全都不随格式变，真要分开写，这些会各有一份副本。
  **Lance 表的面板也已经就绪**（`LANCE_FIELDS` / `actionsFor` 三元 / 第三张卡按服务端的
  `ActionPolicy.supported` 置灰），但页签的白名单**刻意没有**加 `lance-table`：那种表是 Paimon
  面给一个 Lance 目录做的元数据包装，执行侧通过 Lance 的 REST 目录解析不到它；而真正的
  `table_type=LANCE` 表所在的 Lance catalog 还没有控制台浏览入口。理由与解除条件写在
  `lance-table-optimization-design.md` §7.5 与 `TableView.vue` 那段判断上方的注释里。
- 页签内布局见效果图：总开关卡（开关 + 调度间隔 + 最近批次 + 下次调度 + 立即优化）→ 三张动作卡（勾选 + **默认/自定义两档**）→ 孤儿清理干跑关闭时的警示条 → 三块历史（Compaction / 快照清理 / 孤儿文件清理，各带批次链接可跳到「优化任务」页）。
  - 「默认设置 / 自定义」不是独立开关，而是「差异为不为空」：自定义档只提交**改动过的键 + 原本就配过的键**（整份回填会把当前默认值抄成差异，运维之后调 catalog 默认值就再也影响不到这张表）。
  - 参数表单的字段定义只有一处取用入口（`utils/optimization.js` 的 `actionsFor(format)`），两套键表分别是
    `PAIMON_FIELDS` / `ICEBERG_FIELDS`；`scripts/verify-console.py` 拿它们与服务端 `OptimizationOptions` /
    `IcebergOptimizationOptions` 逐字比对，并额外断言两套键集合**不相等**（防复制粘贴）。键名写错的表现是
    保存时吃 400，编译与类型检查都不报。
- **全局「优化任务」页**（侧边栏「治理」分组新菜单）：统计卡（运行中 / 排队中 / 今日批次 / 失败 24h，与列表同用最近 200 个批次的窗口，过滤器在本地做——否则统计卡与列表是两个口径）+ 四个过滤器（状态 / 动作 / Catalog / 触发方式）+ 批次表（展开行拉表级明细）+ 详情抽屉（键值对 + 时间线 + 批次错误 + 回调令牌 + 原始响应）。`?job=` 查询参数支持从表详情的历史点批次号跳过来直接展开。
- 实现上有一个值得记住的坑：`@tab-change` 在 `v-model` 更新后**同步**抛出，此刻页签内容还没渲染、子组件 ref 还是空的——直接 `panel.value?.load()` 会被静默吃掉，而 `loadedTabs` 已记下这个页签，切回来也不会补，页面永远空白。解法是把那次加载请求记成「待发」，等组件挂载（`watch(ref)`）后再发。

效果图：`outputs/table-optimization-mockup.html`（设计稿）；实现后的逐页截图在 `outputs/table-optimization-shots/`（明暗两套 × 表详情三个形态 + 优化任务页）。

---

## 8. 分期落地

| 期 | 内容 | 验收 | 状态 |
| --- | --- | --- | --- |
| P1 | 三张表 + 策略 API + 手动「立即优化」（单表/多选）+ 优化任务模块（只做 k8s Job 提交）+ 状态跟踪 + 表页签展示 | 手动触发一批多表 compaction，批次/明细状态与指标完整落库 | ✅ |
| P2 | 调度器 + 自动组批 + catalog 级默认设置 + 重试/取消 | 开启策略后无人值守按周期出批，重试只重跑失败表 | ✅ |
| P3 | ~~Spark Operator CRD 接入~~（评审后不做，见 §9.2）、orphan 干跑确认流、快照现状卡、过期分区清理（DLF 提到但本方案暂缓）；优化任务的端到端用例 | ~~按 catalog 配置选择提交方式~~ → 改为「按 catalog 选**执行集群**」（§3.4 已落地） | 部分（其余未做） |

P1/P2 的服务端部分由 `OptimizationApiTests`（9 个用例）覆盖：策略默认值与校验、三层配置合并、
组批（合并执行 / 批满顺延 / **拿不到并发配额时不推进游标**）、提交与跟踪、回调（含鉴权与幂等）、
部分成功与重试、取消与超时、提交失败，以及**并发闸门对手动批次同样生效**（手动与重试不经过
出批路径，没有提交侧这道闸门就能绕过上限）。提交器在测试里换成可编程的桩实现——状态机是
「写错就静默走错分支」的地方，没有桩就只能靠起一个 k8s 才能覆盖。

控制台两个页面已落地并通过截图走查（`outputs/table-optimization-shots/`）：表详情三个形态
（自定义档 + 干跑警示 / 默认档 / 未开启）与优化任务页（六种批次状态同屏、展开明细、详情抽屉），
明暗两套主题各一轮。

---

## 9. 开放问题

1. ~~**服务端多副本时的调度互斥**~~ → **出批这一环已按候选方案落地**：`lakehouse_table_optimization.next_run_at`
   上的条件更新（`UPDATE ... WHERE id = ? AND next_run_at = ?`）即分布式抢占，不引入额外锁或选举组件。
   配套的一条规则是判断并发配额**先于**抢占——拿不到执行配额就不动游标，否则这一轮的调度机会
   会被白白吃掉（下一拍这张表还在，但已经「假装跑过」了）。`OptimizationApiTests` 里有对应断言。
   **其余四处已另行实现**（原先只有出批这一环）：跟踪收尾、提交崩溃后的 `SUBMITTING` 回收、
   出批配额的 read-then-write 窗口、手动「立即优化」不经过抢占。做法不再是加锁或选主，而是
   ①三通道调度租约（`lakehouse_scheduler_lease`）把同一拍的重复工作压成一份，
   ②每处状态写入都改成条件更新（`UPDATE ... WHERE 旧状态` 且 `claim_attempt` 代数递增）——
   租约只降噪，行数判定才保正确，所以租约过期、`kill -9`、双实例同拍都不会产生第二条终态事件。
   出批见本文 §6，其余见
   [`optimization-multi-instance-design.md`](optimization-multi-instance-design.md) §5 与 §13（落地记录）。
2. **目标集群是否已部署 Spark Operator** → **结论：不引入**。曾计划做成「有 operator 就用
   `SparkApplication` CRD、没有就回落 k8s Job」，评审后否决：① operator 的 `restartPolicy` /
   `backoffLimit` 自动重试与「compaction 重跑非幂等」直接冲突；② 它的 cron 触发与
   `next_run_at` 条件更新抢占重叠，两套调度器互相打架；③ CRD 仍是 beta 版本面，而我们要的
   只是一个跑完就退出的 Job。现在只有一条通道：`Fabric8JobSubmitter` 建 `batch/v1 Job`
   （`OptimizationJobSubmitter` 接口保留，但没有第二个实现，也不打算加）。
   **提交到哪里**改由 catalog 级执行集群决定（§3.4）。
3. **优化镜像的基线** → 与 `lakehouse-rest-spark` 的 Spark 版本对齐（不引入第二套 Spark），
   Paimon 运行时由镜像提供。
4. **外置表与三类非 Paimon 表明确不支持** → 已实现：非 Paimon 表与外部表在策略读写与「立即优化」
   上一律 400，策略响应里用 `supported` / `unsupportedReason` 表达原因而不只是拒绝，
   控制台因此能在同一个面板上说清「不支持，原因是 X」与「支持但你还没开」（见 §5）。
5. **短期令牌尚未落地**：`--catalog-token` 目前是该 catalog 执行集群里的一份密封静态令牌，
   与回调令牌一样写在容器参数里——命名空间里能读 pod 定义的人就能读到。收掉这个暴露面需要
   服务端为每批签一个短期令牌（`AccessTokenService` 已具备签发能力，有效期覆盖批次 deadline 即可），
   属于后续优化项。
6. **执行集群的两个未决项**（详见 [`catalog-execution-cluster-design.md`](catalog-execution-cluster-design.md) §12.2 / §13）：
   EKS 的长期 AK/SK 是这份设计的弱点（预签名 URL 只是把「长期密钥」换成「长期密钥签出的短期 URL」，
   刷新器才是正解，但它需要额外部署一个组件，本轮不做）；轻探活的 `probe-interval`（默认 30 分钟）
   暂时写死在代码里，不做成服务级键——它只影响 apiserver 请求量，不影响正确性。
