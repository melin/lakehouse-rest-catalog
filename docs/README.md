# docs/ 索引

设计文档、契约基线与 SQL 语法参考都在这里。**按「你要做什么」查这张表**——三十一份文档，
每份在表里都标了落地状态（**已落地** / **设计稿，未实现** / **存档，勿改**）。

根 [`README.md`](../README.md) 讲怎么构建、怎么跑、配置项与实现边界；这份索引回答的是
「要改一处东西，先读哪份」。改代码前的规则与陷阱另有一份
[`AGENTS.md`](../AGENTS.md) §3（面向「动手改」，本表面向「先理解」）。

## 文档分工

| 文档 | 读它的时机 |
| --- | --- |
| [`catalog-api-endpoints.md`](catalog-api-endpoints.md) | 要查 Catalog API 有哪些端点——Paimon 的 60 个与 Iceberg 的 30 个按资源分组，含基址、前缀与授权映射说明（Lance 面走 `/lance/v1/**`，端点清单见 [`lance-rest-catalog-design.md`](lance-rest-catalog-design.md) 与 `scripts/verify-lance-endpoints.py` 的台账） |
| [`management-api-overview.md`](management-api-overview.md) | 要读 Management API——Paimon 官方权限端点与服务端扩展端点各落在哪几个控制器上、几处容易读错的语义 |
| [`management-api-contract.md`](management-api-contract.md) | **存档**：由已移除的 Polaris 管理规格生成的字段与权限枚举（catalog role、64 项 privilege 已不存在）；当前契约看上一行与 [`authorization.md`](authorization.md) |
| [`data-model.md`](data-model.md) | 要改实体或看表结构——27 张表的划分、摘要列与审计列的设计理由、实体与仓储分在哪两个模块 |
| [`spark-sql-reference.md`](spark-sql-reference.md) | 要用 SQL 管理主体与授权——语句级参考，含完整示例 |
| [`spark-sql-extension.md`](spark-sql-extension.md) | 要改这个 Spark 扩展——语法扩展的做法、两个必须知道的约束、测试分层 |
| [`spark-paimon-rest-e2e.md`](spark-paimon-rest-e2e.md) | 要让 Spark 通过本服务端建 Paimon 表——连接配置、可执行的 SQL、报错对照表 |
| [`spark-iceberg-rest-e2e.md`](spark-iceberg-rest-e2e.md) | 要让 Spark 通过本服务端建 Iceberg 表——`spark.sql.catalog.*` 配置、可执行的 SQL、与 Paimon 那套的差异对照 |
| [`spark-lance-rest-e2e.md`](spark-lance-rest-e2e.md) | 要让 Spark 通过本服务端读写 Lance 表——`impl=rest` 的七个配置项、能跑哪些语句（含 `OPTIMIZE` / `VACUUM`）、**哪里跑不了**（这一面无服务端建表、无 `DROP TABLE`）、manifest 容器格式与两侧的版本账。**Spark 3.5 与 4.1 各实测一遍**（connector 0.8.0，两个 bundle 构件分属 `3.5_2.12` / `4.1_2.13`），类路径两个坑的形态两版一致 |
| [`flink-rest-catalog.md`](flink-rest-catalog.md) | 要让 Flink SQL 读写 Paimon / Iceberg——两个版本模块的划分、两套 catalog 选项（`metastore=rest` vs `catalog-type=rest`）、实测结论（含主键更新要配 `sequence.field`）、缺 Hadoop / connector / `add-opens` 的报错对照 |
| [`authorization.md`](authorization.md) | 要理解权限怎么判——实体链、授权映射、失败关闭的取舍 |
| [`console.md`](console.md) | 要改 Web 控制台或排查「页面空列表」——前端分层、端点契约表、机械校验 |
| [`console-table-detail.md`](console-table-detail.md) | 要改表详情页——页签随表格式（Paimon / Format / Object / Lance）伸缩的数据来源、为什么授权不在这页、破坏性操作与边界 |
| [`console-catalog-detail.md`](console-catalog-detail.md) | 要改 Catalog 详情页或仓库文件浏览——四个页签（概览 / 存储 / 文件列表 / 配置）、下钻与路径防护、只读的取舍、「配置」页的默认表属性（`table-default.*`）编辑与并发冲突处理；建表表单里的默认参数预填与为什么不给 Lance 表建表入口 |
| [`console-auth.md`](console-auth.md) | 要给浏览器访问控制台配登录——三种登录方式、两个开关的边界、令牌与认证链、安全考量 |
| [`semantic-layer-design.md`](semantic-layer-design.md) | 要做语义层（语义模型 / 指标口径 / 给 AI agent 供上下文）或评审该方案——**设计稿，未实现**：参照 Polaris 1.7.0 的 Ossie 语义模型并把社区批评的三件事补上（引用校验、描述复用、发现与消费）；八类使用场景、text-to-SQL 四类错误的语义对策、agent 三条接入通道（上下文包 / 只读 MCP / OpenAPI）、ai_context 提示注入对策、「语义 fail-open、策略 fail-closed」的方向对比；配套原型 [`semantic-layer-mockup.html`](semantic-layer-mockup.html)（六画面，含上下文包逐表判权与 Agent 工作流的交互演示） |
| [`polaris-capabilities-and-design.md`](polaris-capabilities-and-design.md) | 要对比 Polaris 或看整体设计——核心能力提取、映射关系、已知缺口 |
| [`credential-vending-design.md`](credential-vending-design.md) | 要判断「引擎能不能不配 AK/SK」、要给七种存储（S3 / Azure / GCS / OSS / OBS / COS / S3 兼容）接短期凭据，或评审凭据下发方案、设计创建 catalog 页的凭据配置——**P0 已实现、P1 部分实现**（P1-9 服务端取票已落；`lakehouse.rest.credential.providers` 默认空 = 只有 relay，开启后 S3 走 `s3-token`）：先分清「凭据转发」（把配置里的长期 AK/SK 原样发出去，TTL 只是建议重取时间）与「凭据下发」（用服务端身份去 STS 现签、按表路径收窄）；Gravitino 与 Polaris 的三层抽象、三个 IAM 身份与键名参考页；**2026 年 7–10 月 Gravitino `vended` 相关 PR 的九条改动逐条对照**（用 `expireTimeInMs()==0` 表达静态而不是 mode 开关、`USE_CREDENTIAL` / `USE_SECRET` 两个权限点、打码值回写、Flink 在非 REST 后端只取一次凭据、联邦 loadTable 要透传 delegation 头、服务端自身 FileIO 是第二个消费者、ADLS 多路径归一、腾讯云 COS）；本工程已落地的三条通道（Paimon `/token`、Iceberg `storage-credentials` + `/credentials`、Lance `storage_options`）与七种存储的键名族（COS 定案用 **COSN 原生** `fs.cosn.*`，且**只在 Paimon 面成立**：Iceberg 只能 `HadoopFileIO` 收不到下发凭据、Lance 是 Rust 没有 COSN 只能 S3 兼容）；**18 条缺口**（含 5 个「看起来在跑其实没生效」的：缺 `s3.session-token`、`loadTable` 的 `config` 恒为 `null`、`/credentials` 字段名与 iceberg-java 对不上、Flink 面一次都没跑过、G18 曾以为 COSN 能收临时凭据（实测 jar 后作废））；七种存储的签发 API / 收窄方式 / TTL 上限 / 坑；引擎侧四面矩阵与那段「十行配置里有四行是同一对密钥」的逐行点评；**§10 控制台三态（不下发 / 转发 / 签发）的字段、冲突、校验分工与引擎片段生成规则**；P0/P1/P2 路线与验收（P0 八项已标 ✅ 并给出对应用例名）；配套原型 [`credential-vending-mockup.html`](credential-vending-mockup.html)（可切存储类型与三态来源，右侧摘要与五段引擎片段实时变化，查询串可直开某状态） |
| [`event-framework.md`](event-framework.md) | 要加一种事件、接一个 listener 或理解事件流的边界——9 类 33 个领域事件与触发点、与 Polaris 事件枚举的逐类映射、audit_event 表、发布监听链路、按监听器的类别/类型订阅（`lakehouse.rest.event-listener.*`）、增量拉取契约（`?since=` 游标 + `?category=`）与不发清单 |
| [`iceberg-rest-catalog-design.md`](iceberg-rest-catalog-design.md) | 要看「同一份元数据同时服务 Paimon 与 Iceberg」怎么做的——双协议方案：创建时区分格式、22 张表复用只加 9 列、路径冲突与错误模型、能力边界，以及**已落地的实测记录与与方案不一致的几处**（§11） |
| [`lance-rest-catalog-design.md`](lance-rest-catalog-design.md) | 要支持 Lance、或评审「要不要做 Lance REST Catalog」——**P0 + P1 + P2 里能做的都已落地**（19 个 operation + 未实现 operation 恒 406；未做：`BatchCreateTableVersions`、索引只读代理）：Lance Namespace 规范自己划的推荐最小集与两条降级许可（只做 `Restrict` 删命名空间、`DescribeTable` 只回 `location`）为什么正好覆盖我们、54 个 operation 的三档取舍（19 做 / 35 显式 406）、为什么不做 `CreateTable`、数据面与 `managed_versioning`、为什么标签读得起而索引读不起、三种「Lance」的区分（Paimon `lance-table` / 通用表 / Lance catalog）、`uri=/lance` + catalog 作第一级的基址决策、`catalog_format=LANCE` 加 3 列 1 张新表、授权与凭据下发落点、P0/P1/P2 需求与验收脚本、控制台的浏览面与它为什么没有 `/v1/config`（§12.9）、待拍板决策 |
| [`generic-table-design.md`](generic-table-design.md) | 要把 parquet / orc / lance / vortex 目录也纳入目录服务，或评审「通用表」方案——**设计稿，未实现**：Polaris Generic Table 的事实基线、四种格式的能力矩阵、复用 `lakehouse_table` 加 `table_kind` 的三方案对比、4 个端点与权限映射、凭据下发为什么是唯一的数据面控制点、Spark 侧按格式分派（`FileTable` 子类 / `ServiceLoader<TableProvider>`）、影响面与风险 |
| [`lance-table-optimization-design.md`](lance-table-optimization-design.md) | 要给 Lance 表做 compaction / 快照清理，或评审「Lance 能不能接进现有存储优化」——**已落地**（前置是上面那份的 P0，也已落地）：两条语句覆盖三件动作（`OPTIMIZE` / `VACUUM`）导致 `orphan` 恒不可用、四层参数里 Daft 那两层在 Spark 侧不存在、指标只有增量没有前后快照、`VACUUM` 不可逆且无干跑的三层补偿、`isIceberg()` 与服务端 `isIceberg(format)` 共 11 处二元分派不改就静默走 Paimon、`--add-opens` 只能进镜像 ENV、三条抓包门禁 |
| [`job-self-healing-design.md`](job-self-healing-design.md) | 要做「Spark 业务任务失败自愈」或判断「失败该不该自动修」——**设计稿，未实现**：先把可行性压成三个前提（可观测 / 可归因 / 可执行），缺一个就降一档（只读诊断 → 建议 → 定向自动），而不是先谈模型能力；判断链五段（L0 偶发 / L1 指纹 / L2 证据 / L3 模型 / L4 风险分级），**「该不该修」插在「怎么修」之前**；catalog 侧的 8 条确定性证据是我们独有的（快照量、DDL、授权、策略、compact 记录、血缘），别人要人肉比数据，我们是几条 SQL；幂等 fail-closed、参数只对当次生效、熔断、基线时间、与自己的出批互斥；模型只做一次结构化 JSON 调用、不给工具；k8s 的 `OOMKilled` 与 YARN 的 137 分两列；配套原型 [`job-self-healing-mockup.html`](job-self-healing-mockup.html)（五画面） |
| [`metrics-design.md`](metrics-design.md) | 要加指标、接 Prometheus 或判断「这个指标该不该打标签」——**设计稿，未实现**：Polaris telemetry 的事实基线（HTTP / API / 持久化 / 缓存四类来源，`realm_id` 基数闸门与「超限即停记」、SLO 显式桶、Iceberg 客户端用量报告与运维指标的区别）、`lakehouse.*` 指标目录与 <1500 的基数预算、标签黑名单与 catalog 折叠成 `__other__`、切面替代逐端点 `@Timed`、管理端口 8081 + ServiceMonitor、告警与看板、`verify-metrics-doc.py` 一致性校验 |
| [`maven-module-design.md`](maven-module-design.md) | 要改模块布局、加协议面或动对外名字——`lakehouse-rest-*` 命名定案、两个父 POM / 两个依赖世界、四条用 enforcer 守住的依赖不变量、对外名字的持有者清单与改名判据（§4 / §10）、类的实际落点与依赖分工（§8） |
| [`optimization-multi-instance-design.md`](optimization-multi-instance-design.md) | 要多副本部署、或排查「同一个优化任务被两台机器发起」——**设计稿，未实现**：现状审计（出批的逐表 CAS 与提交的状态 CAS 已有；跟踪收尾、提交崩溃回收、出批配额、手动入口四处没有，其中收尾在多副本下必然重复）、租约 + 代理（fencing）与三条终态 CAS、锁顺序与事务拆分、部署七条前提、双实例验收 |
| [`table-optimization.md`](table-optimization.md) | 要改表存储优化或排查优化任务——三个动作的语义、批次与并发两道闸门、k8s 提交与状态跟踪、控制台两处页面 |
| [`catalog-execution-cluster-design.md`](catalog-execution-cluster-design.md) | 要给 catalog 配「优化任务跑在哪个 k8s 集群」或评审该方案——**已实现**（M1–M3 全部落地；M4 文档回填随本次提交完成）：配置从 `application.yml` 下移到 catalog（1:1）、三种集群类型的认证（kubeconfig / EKS 令牌 / AKS 令牌）、kubeconfig 逐 context 解析（`exec:` 是硬拒，不是没支持）、探活五问与状态机、fabric8 落地要点（防回落、客户端缓存、AKS scope 修正）；配套效果图 [`catalog-execution-cluster-mockup.html`](catalog-execution-cluster-mockup.html) |
| [`lineage-design.md`](lineage-design.md) | 要做 Spark SQL 血缘（表级 / 列级）或评审方案——**三层都已实现**（服务端：三张表 + 5 个端点；控制台：表详情页「血缘」页签；引擎侧：Spark 3.5/4.1 抽取器与上报器；端到端 `scripts/e2e-lineage.sh` 只在 3.5 上能跑，4.1 受 Paimon bundle 限制）：为什么必须在引擎侧抽而不是服务端重解析（五个错法）、为什么是 `analyzed` 而不是 `optimized`、参考实现 `kyuubi-spark-lineage` 的机制拆解（属性集下推、16 行节点规则、`__count__` 等三个占位、表名归一化）与「抄了什么 / 改了 10 处」、归一化模型（`~` 保留前缀、五种列的 `kind`）、上报契约与异步丢弃、run（事件）与边（状态）两张语义不同的表、图查询口径与环、控制台展现（跳数即层号的分层布局、单张 Canvas + 三档 LOD、折叠与按需展开、列级聚合与虚拟列表、性能预算）、前端落地视角（§9.11–9.14：组件清单与 0 新增依赖、表详情页「血缘」页签集成、数据流；效果图 [`lineage-mockup.html`](lineage-mockup.html)）、配置与验收；**只做 Spark，Flink 留位** |
| [`table-data-preview-design.md`](table-data-preview-design.md) | 要在表详情页加「数据预览」或评审该方案——**两种格式都已实现**（里程碑 M1 / M2 done；M3 / M4 待做）：为什么不能靠外部引擎、四种读法选型（格式自带 reader / `parquet-avro` 直读 / 嵌入式 DuckDB / 复用优化任务执行器——前三者各有「主键表读出幽灵行」之类的否决理由）、`POST /api/console/v1/tables/{tableId}/preview` 契约、三段式过滤（分区 → 裁文件 → 筛行）、**分区默认锁最新**（有序性判定 / 按文件数预算的级联收敛 / 哈希分区不编「最新」/ 空分区回退 / `partition` 与 `filters` 双通道）、无状态游标、采样与限额的口径、TABLE 级 `SELECT` 判权与 `table.previewed` 审计；配套效果图 [`table-data-preview-mockup.html`](table-data-preview-mockup.html)（右上角可切换四种演示状态） |
| [`row-filter-column-mask-design.md`](row-filter-column-mask-design.md) | 要做**行过滤 / 列脱敏**（类似 Databricks Unity Catalog 的 row filter 与 column mask）或评审该方案——**服务端 P0、Spark 引擎侧与 AST 求值器均已实现**（`DataPolicyApiTests` / `DataPolicyDisabledTests` / `PolicySparkTests`（3.5.9 与 4.1.2 双版本全绿，版本差异的三个实测结论见设计稿 §9.1.1）/ `PolicyExpressionEvaluatorTests` / `PolicyPreviewTests`）；**元数据打码已实现**（§7.3：带 mask 时快照 `statistics` 置空、预览分区回显对被脱敏分区列打码、catalog 级文件下载补上收口）；**§13 的七条 SQL 管理语句已实现**（`PolicySqlTests`，3.5 / 4.1 同一份用例；保留字加了 `ROW` / `FILTER` / `MASK` / `POLICY`，参考文档与 `verify-spark-sql-doc.py` 同步）；**`$principal_roles` 已实现**（行过滤按调用者的 principal role 过滤，服务端折叠成字面量列表、引擎零改动）；**控制台「数据策略」页签已实现**（表详情新页签 + 结构化编辑器 `TablePolicyPanel`，保存即整份替换、409 不自动重试；verify-console 白名单同步）；**控制台三屏已实现**（数据保护总览页 `/policies`、新建策略向导 `/policies/new`——与页签共用 AST 编辑器 `src/utils/policyAst.js`、主体视角试算 `/policies/:c/:d/:t/dryrun`——dry-run 端点按被模拟主体判四态）。至此本设计无未实现项（P2 / P3 另行排期）。设计内容：Spark 3.5 / 4.1 / 4.2 均无原生 API（三个版本的 `spark-catalyst` 实测 + SPARK-59726 指向 5.0）；为什么裸投影不安全、必须自定义逻辑节点；表达式白名单与 `$principal` 占位符；fail-closed 与 60 秒缓存；**预览按策略逐行求值**（行过滤读原值、同层 mask 不链式，用户条件落到被脱敏列指名拒绝）与文件下载 / 分区列举两条维持 403 的口子；「凭据下发后直读文件无法拦截」这条硬边界，三家生产实现的横向对照（Databricks / Kyuubi+Ranger / EMR+Lake Formation）与**强制等级 L0–L3**——结论是升级路径不是等 Spark 5.0，而是加网关或收掉凭据来源；**Spark SQL 管理语句的语法设计**（§13：`SET ROW FILTER` / `SET MASK` / `SHOW POLICY` 等 7 条，表达式走 `parseExpression` 按白名单转 AST，`DROP POLICY` 要会话级确认） |

## 配套效果图

交互原型，浏览器直接打开、按画面切换（`#hash` 可直达某一屏）：

| 效果图 | 对应设计 |
| --- | --- |
| [`semantic-layer-mockup.html`](semantic-layer-mockup.html) | [`semantic-layer-design.md`](semantic-layer-design.md)（六画面） |
| [`catalog-execution-cluster-mockup.html`](catalog-execution-cluster-mockup.html) | [`catalog-execution-cluster-design.md`](catalog-execution-cluster-design.md) |
| [`lineage-mockup.html`](lineage-mockup.html) | [`lineage-design.md`](lineage-design.md)（五个状态） |
| [`table-data-preview-mockup.html`](table-data-preview-mockup.html) | [`table-data-preview-design.md`](table-data-preview-design.md)（右上角可切四种演示状态） |
| [`row-filter-column-mask-mockup.html`](row-filter-column-mask-mockup.html) | [`row-filter-column-mask-design.md`](row-filter-column-mask-design.md) |
| [`job-self-healing-mockup.html`](job-self-healing-mockup.html) | [`job-self-healing-design.md`](job-self-healing-design.md)（五画面） |
| [`credential-vending-mockup.html`](credential-vending-mockup.html) | [`credential-vending-design.md`](credential-vending-design.md) §10（创建 catalog 页的凭据下发配置；可切存储类型与三态来源，查询串直开某状态，见 §10.7） |

## 文档与代码的一致性靠脚本守

`scripts/` 下的校验脚本会把文档里的表格与代码对一遍，改完文档或端点后跑一遍
（计数口径见 [`console.md`](console.md)，脚本自身会核对文档里写的「N 组 M 项」）：

| 脚本 | 守什么 |
| --- | --- |
| `verify-console.py` | 控制台端点层、组件契约、页签白名单、文档计数 |
| `verify-spark-sql-doc.py` | `spark-sql-reference.md` 与语法扩展里的语句一一对应 |
| `verify-lineage-doc.py` / `verify-iceberg-endpoints.py` / `verify-lance-endpoints.py` | 血缘、Iceberg、Lance 三份文档声明的端点与实现台账 |
| `verify-k8s-manifests.py` | `deploy/kubernetes/` 清单内部引用自洽 |

生成物**不要手改**：`sql/schema-mysql.sql`、`management-api-contract.md` 由脚本重生成。
