# 表存储优化

> 状态：**服务端与优化任务模块已实现**（P1 + P2 的链路部分），**控制台页签与「优化任务」页尚未落地**，
> P3 未做。落地过程中与原方案的偏差都写在对应小节里，并注明了改动的理由——本文同时是「当时为什么这么设计」的记录。
>
> 各节的实现状态：§2 动作映射 ✅ · §3 执行架构 ✅ · §4 数据模型 ✅（`sql/schema-mysql.sql` 已是 22 张表）·
> §5 API ✅ · §6 优化任务模块 ✅（`lakehouse-rest-optimizer`）· §7 控制台 ⏳ 待做 · §8 分期见文末。
>
> 对标对象：阿里云 DLF 的表存储优化（Compaction / Snapshot Expire / Orphan File Remove 三个子页签）与 AWS Lake Formation 的 Enable optimization（三项勾选 + 默认/自定义配置 + 三块历史）。**产品分层以 AWS 为基准**（总开关 → 三项独立启用 → 默认/自定义 → 历史三块），DLF 的快照详情卡、「立即清理」按钮与中文文案吸收进来。执行链路是本方案的自有部分：DLF 由服务端托管调度，AWS 由托管 optimizer（按 DPU 计费），我们用**先落库、再异步提交 k8s 运行 Spark 任务、多表合并执行**的方式实现。

---

## 1. 对标与取舍

| 维度 | 阿里云 DLF | AWS Lake Formation | 本方案 |
| --- | --- | --- | --- |
| 入口 | 表详情「存储优化」页签，内部分三个子页签 | 表级「Enable optimization」独立页 | 表详情新增「存储优化」页签（仅 Paimon 表显示，同「快照/标签」的伸缩规则） |
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
| Paimon 原生 | `CALL sys.expire_snapshots(older_than => ..., retain_max => ..., retain_min => ...)` |
| 可配参数 | `保留时间`（快照保留时长，默认 24 h——DLF 截图里是 1 h，但 1 h 对可回溯性太激进，默认取保守值）；`最少保留个数`（默认 10）；`执行间隔`（默认 24 h，对齐 AWS 的 deletion run rate） |
| 安全性 | Paimon expire 自身会尊重 consumer 位点与标签引用，不会删被挂住的快照；服务端仍在 item 上记录 `删除快照数 / 释放空间` |
| 展示 | 配置卡下方放「快照现状」卡（吸收 DLF）：当前 snapshot 个数、最早 snapshot 的 ID / 提交时间 / 提交类型 / 表总行数——数据源就是已有的 `paimon_snapshot` 表 |

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

## 3. 执行架构

### 3.1 总体流程

```
控制台/管理 API 写策略
        │
        ▼
┌─────────────────────────┐   每分钟扫描（服务端调度器）
│ paimon_table_optimization│ ──────────────────────────┐
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
2. **分组**：同一批只放**同一个 catalog** 的表——它们共享 warehouse 与存储凭据、共享一份 k8s 提交配置，优化任务用同一 catalog 配置建一次 REST catalog 即可处理全部表。
3. **上限**：每批最多 `max-tables-per-job`（默认 20）张表，超出拆成下一批；全局并发批次 `max-concurrent-jobs`（默认 2），超出留 `PENDING` 排队。并发是**两道闸门**，数的口径不同：出批侧数「已承诺的批次」（含排队中的 `PENDING`），决定**还该不该造新批次**；提交侧数「已放出去的」（`SUBMITTING`/`RUNNING`），决定**造好的批次放不放行**。两道都要有：手动「立即优化」与重试都是直接建批次，不经过出批那条路，少了提交侧这道就能绕过上限——而这条上限本来就是给集群留余量用的（每个批次一个 Spark 驱动）。放不出去的批次留在 `PENDING` 队列里按创建时间先后等下一拍，不是被丢掉。
4. **单表立即执行复用同一条路**：控制台「立即优化」= 产生一个单 item 的批次，走完全相同的提交与跟踪链路，不另写一条执行路径。
5. **防重复**：同一张表存在未终态 item 时不再入批；手动触发与调度触发撞车时以先落库者为准，后来者对同表报「已有进行中的优化任务」。

为什么不「一表一 Spark 任务」：表规模上去后 driver/executor 的固定开销（每个任务一个 pod、一次镜像拉取、一次 SparkContext 启动）会把 k8s 调度打满，而优化本身是低频、轻负载操作；Paimon 的三个 procedure 都接受表名参数，一个任务内逐表顺序执行即可。批次上限 20 是防止单批跑太久——一批挂了重试的代价要可控（重试只重跑 `FAILED` 的 item，见 3.5）。

### 3.4 k8s 提交与跟踪

| 项 | 设计 |
| --- | --- |
| 提交方式 | 优先 **Spark Operator 的 `SparkApplication` CRD**（声明式、自带重试与 TTL，状态好读）；集群没装 operator 时回落 **k8s Job 包 spark-submit 镜像**。两种都实现成同一个 `OptimizationJobSubmitter` 接口的两个实现，按 catalog 配置选择 |
| 镜像 | 优化 jar（新模块，见 §6）+ paimon-spark 运行时打成一个镜像；服务端**只传** `--job-id`、`--management-url`、短期 token 三个参数 |
| 明细获取 | 优化任务启动后**回调管理 API 拉取本批次的表清单与每表配置**，不走命令行参数——避免表多了参数超长，也保证「库里是什么就执行什么」 |
| 状态跟踪 | 服务端定时轮询（默认 30 s）CRD/pod 状态推进 job 状态；**表级结果由优化任务逐表回调上报**（每完成一张表 POST 一次 item 结果：成功/失败 + 前后指标），比解析日志可靠 |
| 终态判定 | k8s 侧到达终态 **且** 全部 item 到达终态，两者对上才收尾；只有 k8s 终态而 item 仍非终态（如任务没逐表回调就崩了）→ 整批置 FAILED，错误信息取 pod 退出码与日志尾部 |
| 超时 | 每批 deadline（默认 2 h），超时置 FAILED 并清理 k8s 资源 |
| 重试 | job 级「重试」= 只把 `FAILED` 的 item 组成新批次重跑，成功的不动 |

### 3.5 安全

- 优化任务使用专用主体 `optimizer`（管理 API 建），权限收敛为：本批次表的 `TABLE_READ_META` + `TABLE_WRITE_DATA`（compact/expire 需要写提交）+ `TABLE_DROP_DATA`（orphan 清理需要）——按 AWS 那张「Grant required permissions」提示卡的思路，权限在启用优化时由服务端自动装配，并在 UI 上显式告知。
- 下发给任务的 token 是**短期 token**（有效期 ≥ 批次 deadline 即可），不复用控制台登录态。
- 回调端点要求 job 专属 token；审计列记录触发者（`SCHEDULED` / 手动触发时的主体名）。

---

## 4. 数据模型（新增 3 张表）

沿用既有约定：实体继承 `AuditedEntity`（审计列），复杂结构 `@Convert` + `JsonConverters`，乐观并发 `entityVersion`；改实体后重跑 `scripts/gen-mysql-ddl.sh` 并同步 `docs/data-model.md`。

**`paimon_table_optimization`（策略，与表 1:1）**

| 列 | 说明 |
| --- | --- |
| `id` / `table_id` | 主键 / 表外键（唯一约束） |
| `enabled` | 总开关（关 = 三项全停，不产生批次） |
| `compaction_enabled` / `compaction_config_json` | 是否启用 + 配置差异（`strategy`、`minInputFiles`、`targetFileSizeMb`、排序列；为 `null` 的键回落 catalog 默认） |
| `snapshot_expire_enabled` / `snapshot_expire_config_json` | 同上（`retentionHours`、`retainMin`、`intervalHours`） |
| `orphan_remove_enabled` / `orphan_remove_config_json` | 同上（`olderThanHours`、`intervalDays`、`dryRun`） |
| `schedule_interval_seconds` | 调度间隔（扫描该表的周期，默认 3600，对齐 DLF 的 compaction 间隔） |
| `next_run_at` / `last_job_id` | 下次到期时间 / 最近一次批次 |

**`paimon_optimization_job`（批次）**

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

**`paimon_optimization_job_item`（表级明细，批次 1:N）**

| 列 | 说明 |
| --- | --- |
| `id` / `job_id` / `table_id` | 主键 / 批次外键 / 表外键 |
| `actions` | 该表执行的动作（批内可按表裁剪：某表只开了两项就只跑两项） |
| `status` | `PENDING / RUNNING / SUCCESS / FAILED / SKIPPED` |
| `metrics_json` | 前后指标（合并前/后文件数与大小、删除快照数、删除孤儿文件数、释放空间） |
| `error_message` / `reported_at` | 单表失败原因 / 回调时间 |

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

几点约定：

- **对外一律用 catalog 名与「catalog.库.表」三段名**，不用内部主键。catalog 名是 optimizer 建 REST catalog 连接时要用的那个值，主键只在服务端内部有意义；批次的 `catalog` 字段因此也是名字。外键 id 只在回调端点里按 `itemId` 出现（清单里给，任务原样带回来）。
- **catalog 级默认键形如 `optimization.<动作>.<参数>`**，动作段用 wire 名：`optimization.compact.min-input-files`、`optimization.expire.retention-hours`、`optimization.orphan.older-than-hours`。与 API 的 `action` 字段、库里的 `actions` 列同一套词——不为 catalog 属性另起一套更长的英文名，否则两处映射必然漂移。
- **回调令牌**（请求头 `X-Optimization-Token`，批次级、落库随机值）不参与 RBAC：调用方是 k8s 里的任务，没有主体身份。任务从 Job 的启动参数里拿到它。批次详情是否回显令牌由一条规则决定（授权关闭，或调用方是服务管理员）——`run` / `retry` / `cancel` / 详情四个端点共用这一条，不各判一次。

---

## 6. Spark 优化任务（模块 `lakehouse-rest-optimizer`）

独立 maven 模块，main class `TableOptimizerJob`，打成镜像；与 `lakehouse-rest-spark` 共用父 POM
（同一个依赖世界：Spark 由集群提供、Jackson 2 对齐发行版）。

**启动参数只有三个（外加一个可选）**，由服务端生成 k8s Job 时写入：

| 参数 | 说明 |
| --- | --- |
| `--job-id` | 批次 id |
| `--management-url` | 管理 API 基址；catalog API 基址由它去掉 `/api/management/v1` 推出，可用 `--catalog-url` 覆盖 |
| `--token` | 批次级回调令牌（请求头 `X-Optimization-Token`） |
| `--catalog-token` | **可选**：catalog 数据面令牌。批次令牌只对优化回调端点有效，不能当 catalog 凭据——任务要访问 `/v1/{prefix}` 才能拿到 Paimon 表。没开认证的部署不传这个参数 |

**执行骨架**：拉清单 → 建一次 Spark 会话 → 逐表按 compact → expire → orphan 顺序执行 →
每表回调上报结果 → 全部结束退出（全部成功/跳过 exit 0，有失败 exit 1）。

- **表清单与配置都从回调端点拉，不走命令行**：一批最多 20 张表、每张三项配置，
  序列化进参数会让命令行失控，而且会让「集群里跑的那份参数」成为策略的第二个版本。
- **每张表独立 try/catch**：一张表失败不拖死同批其它表（这正是 `PARTIAL_SUCCESS` 存在的意义）。
- **已经上报过结果的表会被跳过**（清单里带 `status`）：pod 被驱逐后重排时，compaction 重跑
  不是「无副作用的重试」，它会重写文件。
- **三个动作都走 `CALL` 语句，不依赖 Paimon 的 Java API**：procedure 的参数名与取值语义随
  Paimon 版本变，交给目标集群的 Paimon 解释，本模块的产物就不会把 Paimon 版本焊死。
  参数名与类型是照 2.0.0 的 class 文件反解出来的，逐项对应（`compact` 八个参数全是 StringType，
  其中 `options` 是 `k1=v1,k2=v2` 字符串；`expire_snapshots` 的 `retain_max`/`retain_min`/
  `max_deletes` 是整数；`remove_orphan_files` 的 `dry_run` 是布尔），因此渲染时按类型走
  ——把整数写成 `'10'` 在 ANSI 模式下会直接抛转换错误。
- **指标**：compaction 前后各查一次 `表$files`（文件数 + 总大小）算前后对比；
  expire/orphan 直接用 procedure 的返回行（`deleted_snapshots_count` /
  `deletedFileCount` / `deletedFileTotalLenInBytes`）。采集口径在任务侧定，一次采集到处展示。
- **什么都没动时上报 `SKIPPED` 而不是 `SUCCESS`**：未达阈值、没有过期快照、没有孤儿文件
  都属这一类，控制台上要能一眼分出「真的合了文件」和「扫了一遍没动」。
- **时间窗口用相对时长**（`older_than => '24 h'`）而不是绝对时间戳：绝对时间戳要由任务侧按
  某个时区格式化、再由 Paimon 按会话时区解析，两个时区不一致时窗口会整体平移，
  而窗口平移在 orphan 清理上的后果是多删文件。

单元测试覆盖参数翻译（`PaimonProceduresTests`）与启动参数解析（`OptimizerOptionsTests`），
共 18 例——这两块是「编译能过、跑起来才错」的高风险区，必须钉住。真跑一张表的端到端用例
属于 P3（需要一个可用的集群与 Paimon 仓库）。

---

## 7. 控制台

- **表详情新增「存储优化」页签**：仅 `isPaimonTable` 时渲染（format/object/lance 表没有快照与 compaction 概念，页签隐藏——与「快照/标签/分支」同一条伸缩规则）；按需加载（首次切入才请求策略与历史）。
- 页签内布局见效果图：总开关卡（开关 + 调度间隔 + 最近批次 + 下次调度 + 立即优化）→ 三张动作卡（勾选 + **默认/自定义两档**）→ 孤儿清理干跑关闭时的警示条 → 三块历史（Compaction / 快照清理 / 孤儿文件清理，各带批次链接可跳到「优化任务」页）。
  - 「默认设置 / 自定义」不是独立开关，而是「差异为不为空」：自定义档只提交**改动过的键 + 原本就配过的键**（整份回填会把当前默认值抄成差异，运维之后调 catalog 默认值就再也影响不到这张表）。
  - 参数表单的字段定义只有一份（`utils/optimization.js` 的 `ACTIONS`），`scripts/verify-console.py` 拿它与服务端 `KNOWN_KEYS` 逐字比对——键名写错的表现是保存时吃 400，编译与类型检查都不报。
- **全局「优化任务」页**（侧边栏「治理」分组新菜单）：统计卡（运行中 / 排队中 / 今日批次 / 失败 24h，与列表同用最近 200 个批次的窗口，过滤器在本地做——否则统计卡与列表是两个口径）+ 四个过滤器（状态 / 动作 / Catalog / 触发方式）+ 批次表（展开行拉表级明细）+ 详情抽屉（键值对 + 时间线 + 批次错误 + 回调令牌 + 原始响应）。`?job=` 查询参数支持从表详情的历史点批次号跳过来直接展开。
- 实现上有一个值得记住的坑：`@tab-change` 在 `v-model` 更新后**同步**抛出，此刻页签内容还没渲染、子组件 ref 还是空的——直接 `panel.value?.load()` 会被静默吃掉，而 `loadedTabs` 已记下这个页签，切回来也不会补，页面永远空白。解法是把那次加载请求记成「待发」，等组件挂载（`watch(ref)`）后再发。

效果图：`outputs/table-optimization-mockup.html`（设计稿）；实现后的逐页截图在 `outputs/table-optimization-shots/`（明暗两套 × 表详情三个形态 + 优化任务页）。

---

## 8. 分期落地

| 期 | 内容 | 验收 | 状态 |
| --- | --- | --- | --- |
| P1 | 三张表 + 策略 API + 手动「立即优化」（单表/多选）+ 优化任务模块（只做 k8s Job 提交）+ 状态跟踪 + 表页签展示 | 手动触发一批多表 compaction，批次/明细状态与指标完整落库 | ✅ |
| P2 | 调度器 + 自动组批 + catalog 级默认设置 + 重试/取消 | 开启策略后无人值守按周期出批，重试只重跑失败表 | ✅ |
| P3 | Spark Operator CRD 接入、orphan 干跑确认流、快照现状卡、过期分区清理（DLF 提到但本方案暂缓）；优化任务的端到端用例 | 按 catalog 配置选择提交方式 | 未做 |

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

1. ~~**服务端多副本时的调度互斥**~~ → **已按候选方案落地**：`paimon_table_optimization.next_run_at`
   上的条件更新（`UPDATE ... WHERE id = ? AND next_run_at = ?`）即分布式抢占，不引入额外锁或选举组件。
   配套的一条规则是判断并发配额**先于**抢占——拿不到执行配额就不动游标，否则这一轮的调度机会
   会被白白吃掉（下一拍这张表还在，但已经「假装跑过」了）。`OptimizationApiTests` 里有对应断言。
2. **目标集群是否已部署 Spark Operator** → 目前只实现了 k8s Job 通道；CRD 接入留给 P3，
   `OptimizationJobSubmitter` 接口已经为它留好位置（换实现不动调用方）。
3. **优化镜像的基线** → 与 `lakehouse-rest-spark` 的 Spark 版本对齐（不引入第二套 Spark），
   Paimon 运行时由镜像提供。
4. **外置表与三类非 Paimon 表明确不支持** → 已实现：非 Paimon 表与外部表在策略读写与「立即优化」
   上一律 400，策略响应里用 `supported` / `unsupportedReason` 表达原因而不只是拒绝，
   控制台因此能在同一个面板上说清「不支持，原因是 X」与「支持但你还没开」（见 §5）。
5. **短期令牌尚未落地**：`--catalog-token` 目前是一份配置里的静态令牌，与回调令牌一样写在
   容器参数里——命名空间里能读 pod 定义的人就能读到。收掉这个暴露面需要服务端为每批签一个
   短期令牌（`AccessTokenService` 已具备签发能力，有效期覆盖批次 deadline 即可），
   属于后续优化项。
