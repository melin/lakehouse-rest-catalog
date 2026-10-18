# Apache Polaris 核心能力与 LakeHouse Catalog 实现对照

本文先提炼 Apache Polaris 的核心能力，再说明这些能力如何映射到
Apache Paimon 的 REST Catalog 规格，以及本仓库（Spring Boot + JPA 实现）的落点与差异。

---

## 一、Apache Polaris 是什么

Apache Polaris 是 Apache Iceberg 的开源目录（catalog）实现，最初由 Snowflake 开发并于
2024 年 6 月开源，随后捐赠给 Apache 软件基金会。

它解决的是数据湖场景下最核心的一层：**表的当前元数据指针由谁保存、由谁鉴权、由谁下发访问凭证**。
Polaris 通过实现 Iceberg 的开放 REST Catalog 协议，让多个计算引擎在同一份数据上协同读写，
从而把「目录层」从具体计算引擎中解耦出来。

---

## 二、核心能力提炼

| # | 能力 | 具体含义 |
| --- | --- | --- |
| 1 | **标准 REST Catalog 契约** | 完整实现 Iceberg 开放 REST Catalog API，任何遵循该规范的引擎（Spark、Flink、Trino、Doris、StarRocks、Dremio OSS 等）无需专用连接器即可接入 |
| 2 | **集中式统一鉴权** | 鉴权在目录服务侧统一完成，与引擎无关；同一套策略对所有引擎一致生效 |
| 3 | **RBAC 权限模型** | 双层角色：principal roles（授予服务主体的角色）与 catalog roles（在目录资源上配置权限并被授予给 principal roles）；权限可作用于 catalog / namespace / table 各级 |
| 4 | **凭证下发（credential vending）** | 引擎不持有对象存储长期密钥；目录服务校验授权后下发短期、范围受限的存储凭证 |
| 5 | **多租户 / 多 catalog** | 单实例服务多个 catalog 与多个主体；catalog 是资源组织的第一层 |
| 6 | **catalog 类型区分** | Internal（由 Polaris 托管、可读写）与 External（外部目录同步而来、只读） |
| 7 | **存储配置抽象** | 创建 catalog 时统一配置对象存储（S3 / GCS / Azure）与位置、角色、租户信息 |
| 8 | **治理面 API** | REST Management API 提供实体标签、对象权限授予、行过滤与列脱敏等细粒度治理能力 |
| 9 | **不锁定部署形态** | 可自托管（Docker / Kubernetes Helm）、可跑在自有基础设施，也可使用托管服务 |
| 10 | **稳定的 API 演化策略** | 非实验性 API 按语义化版本维护；废弃接口保留过渡期；必要时通过新增路径（如 `v1` → `v2`）而非破坏性修改推进 |

**一句话概括 Polaris 的范式**：把「表格式规范 + 开放 REST 契约 + 集中鉴权 + 凭证下发 + 多租户 + 治理面」
做成一层与引擎无关的目录服务。

---

## 三、能力到 LakeHouse Catalog 的映射

Paimon 的 REST Catalog 规格遵循同一范式，但对象模型不同：Polaris 面向 Iceberg
（快照 + manifest 清单 + 分区规格），Paimon 的规格则额外覆盖分区登记与统计、分支、标签、
流式消费者、函数与语义视图，并自带数据访问授权端点。

| Polaris 能力 | LakeHouse Catalog 对应 | 本实现落点 |
| --- | --- | --- |
| 标准 REST Catalog 契约 | REST Catalog API v1，60 个端点 | `web/` 下 10 个控制器，路径与 operationId 一一对应 |
| 统一鉴权 | 规格声明 `bearerAuth` 安全方案 | `BearerAuthInterceptor` 挂在 `/v1/**`，业务层只读 `RequestContext.principal()` |
| 多租户 / 多 catalog | 路径前缀 `{prefix}` 即 catalog 标识 | `CatalogEntity` + `CatalogService.resolve()`，支持未登记 prefix 自动登记 |
| catalog 类型（internal / external） | `register` 进来的表标记 `isExternal` | `TableEntity.external` + `POST .../databases/{database}/register` |
| 存储配置抽象 | `warehouse` + 表路径约定 | `default-warehouse` 与 `path-template` 配置项；路径按 `warehouse/database.db/table` 推导 |
| 存储类型与凭据（S3 / Azure / GCS） | `StorageConfigInfo` 判别联合 + `polaris.storage.*` 配置 | 管理面六种 `storageType` 完整建模并落库（`StorageConfigs`），其中 `OBS` / `OSS` 为超出规格的扩展（见下）；服务端侧配置面见 `RestServerProperties.Storage`、`CredentialManager`、`FileIo`，下发规则见 `StorageCredentialManager`。另有 catalog 自带的静态凭据（`accessKeyId` / `secretAccessKey`），同为超出规格的扩展（见下） |
| 凭证下发 | `GET .../tables/{table}/token` | `CredentialService.token()`：按 catalog 的存储类型分派，经 `StorageCredentialCache` 复用未过期凭据并返回 `expiresAt` |
| 查询鉴权与策略下推 | `POST .../tables/{table}/auth` | `CredentialService.auth()`：返回行过滤表达式与列脱敏映射；请求列不在 schema 中时返回 403 |
| RBAC 细粒度权限 | 由 REST Management API 承担权限面 | 已实现：主体 → principal role → catalog role → 资源授权的完整链路，并作为 `/v1/**` 的判定依据。见第六节 |
| 治理面（实体标签 / 行过滤 / 列脱敏策略） | REST Management API（独立规格） | 实体标签未实现；行过滤与列脱敏未实现，`auth` 端点返回空规则。见第八节 |
| 元数据持久化可插拔 | 规格未约束 | JPA + MySQL 8.0（默认，DDL 见 `sql/schema-mysql.sql`）/ H2 内存库 / PostgreSQL profile |
| 版本化与向后兼容 | 规格 `version: "1.0"`，路径以 `/v1` 开头 | 错误响应严格按规格（`{message, resourceType, resourceName, code}`），未知变更 action 返回 400 |

---

## 四、本实现的结构选择

### 1. 前缀即租户边界

客户端先调 `GET /v1/config?warehouse=...`，服务端在 `defaults.prefix` 中回传 prefix，
之后所有资源路径都以该 prefix 开头。这与 Polaris 用 catalog 做资源组织第一层的思路一致，
差别只是 Paimon 规格把这个标识放在 URL 前缀位置。

服务端不要求预先登记：`paimon.rest.auto-create-catalog=true` 时，任意 warehouse 值都会
自动登记为一个 catalog，便于引擎零配置接入；生产环境可关闭该开关，改为显式管理。

### 2. 鉴权与业务解耦

鉴权在拦截器统一完成，业务代码只从 `RequestContext` 读取主体名写入
`owner` / `createdBy` / `updatedBy` 审计字段。这对应 Polaris「策略统一、引擎无关」的定位：
换认证方式不需要改业务逻辑。

审计字段是 `varchar(255)`，而主体名可能很长（认不出的令牌会退化为令牌本身），
因此落库前统一经 `AuditPrincipal.of` 归一化：放得下的原样存，放不下的记
`sha256:<前 12 位>`。归一化点放在实体基类上，是「一处收口」而不是在各服务里判断——
认证方式可能继续增加，但写审计字段只有这一条路。

`auth.enabled=false`（默认）时所有请求以 `anonymous` 通过，便于本地开发。

### 3. 凭证下发按存储类型分派

下发的键由 catalog 的 `storageConfigInfo.storageType` 决定，而不是所有类型共用一个形状：

| storageType | 下发的键 | 密钥来源 |
| --- | --- | --- |
| `S3` | `s3.access-key-id`、`s3.secret-access-key`、`s3.region`、`s3.endpoint`、`s3.path-style-access` | catalog 静态凭据 → 具名存储 → 默认配置 → 环境凭据链 |
| `AZURE` | `azure.tenant-id`、`azure.account`、`azure.hierarchical`、`azure.multi-tenant-app-name`、`azure.consent-url` | 仅定位元数据 |
| `GCS` | `gcs.oauth2.token`、`gcs.oauth2.token-expires-at`、`gcs.service-account` | 服务端配置 → 环境凭据链 |
| `OBS` | `fs.obs.access.key`、`fs.obs.secret.key`、`fs.obs.session.token`、`fs.obs.endpoint` | catalog 静态凭据 → 具名存储 → 默认配置 → 环境凭据链 |
| `OSS` | `fs.oss.accessKeyId`、`fs.oss.accessKeySecret`、`fs.oss.securityToken`、`fs.oss.endpoint` | 同上 |
| `FILE` | 自包含令牌（`accessKeyId` / `securityToken` / `expiration` / `tablePath`） | 服务端自签 |

「catalog 静态凭据」这一级是本工程超出第三节规格的扩展（见第 5 点），它**优先于其余全部来源**：
catalog 自带 `accessKeyId` / `secretAccessKey` 时，具名存储与默认配置都不再看。
它与 `stsUnavailable` 还是**正交**的两件事——后者说的是「服务端不去向云申请临时凭据」，
而静态凭据是「下发用户自己填进来的长期密钥」，因此 `stsUnavailable: true` 下照常下发。
这一点决定了它的适用场景：不提供 STS 的兼容 S3 存储（MinIO、Ceph RGW、Ozone、FlashBlade 等）
按 Polaris 的路径接入时只能写 `stsUnavailable: true` 并让引擎自己找凭据，
静态凭据补上的正是这一格。

选定的实现由 `paimon.rest.credential-manager.type` 决定：`default` 走上面的分派，
`noop` 不下发（读表接口返回 501）。策略在 `StorageRuntimePolicy` 里于启动期解析，
取值非法直接启动失败。

范围限定到单表、时效由 `credential.ttl-seconds` 控制（GCS 的 `lifespan` 更短时收窄），
对应 Polaris 的短时效、按需授权模型。未过期的凭据在
`paimon.rest.storage-credential-cache.max-entries` 的容量内被复用，同一张表反复读拿到的是同一份凭据。

### 4. OBS / OSS 是超出规格的扩展

`OBS` 与 `OSS` 不在 Polaris 规格 `StorageConfigInfo.discriminator.mapping` 的取值里。
Polaris 把华为云 OBS 与阿里云 OSS 归入「兼容 S3 协议的对象存储」，靠自定义 `endpoint` 接入，
因此没有可平移的类型与配置项。

本工程单列它们，理由是**兼容 S3 协议不等于可用 `s3.*` 配置**：Paimon 的
`paimon-obs` 与 `paimon-oss` 是两个独立的 FileIO，各认自己的一套键，且两套命名风格还不一致
（华为 `fs.obs.access.key` 点分隔小写，阿里 `fs.oss.accessKeyId` 驼峰；
临时凭据的令牌一个叫 `session.token`、一个叫 `securityToken`）。走 S3 兼容层能用，
但拿不到这些厂商原生的键与凭据提供器配置。两套键名族在
`DefaultStorageCredentialManager.OBS_KEYS` / `OSS_KEYS` 里集中声明，便于对照。

代价是 `storageType` 的取值集合超出规格。影响面仅限管理 API 的按类型分派逻辑：
引擎只把 `storageConfigInfo` 原样透传、不解析 `storageType`，因此 REST Catalog 协议不受影响。

**边界。** `S3` 与 `GCS` 下发的是服务端长期密钥或配置令牌，不是 STS / OAuth 换取的临时凭据——
这需要服务端持有云侧信任关系。`AZURE` 只传定位元数据：Polaris 的 `polaris.storage.*` 里没有
Azure 账户密钥，它靠服务进程自身的 Azure 标识签 SAS，本工程没有那层标识。
`OBS` / `OSS` 同理：服务端只代持配置里的 AK/SK（或成套的临时凭据），
不代为向华为云 / 阿里云申请 STS 令牌。
接入真实临时凭据时应替换 `DefaultStorageCredentialManager` 的对应分支，这是本实现预留的外部集成点。
上面这几种来源都要求**密钥写进服务端配置**；Polaris 那侧也一样
（`polaris.storage.aws.access-key` / 具名存储），因此「同一套服务端配置打给多个 catalog
却要用不同密钥」在两边都做不到。本工程为此另加了一处扩展，见下一节。

### 5. catalog 静态凭据也是超出规格的扩展

Polaris 规格的 `AwsStorageConfigInfo` 里**没有** per-catalog 的 `accessKeyId` / `secretAccessKey`，
密钥只来自服务端配置或具名存储：`stsUnavailable: true` 的含义是「不去申请临时凭据」，
它没有顺手留一个「那就用我填的这对长期密钥」的入口。于是接入不提供 STS 的兼容 S3 存储时，
密钥只能落在服务端侧，而服务端配置是**全局**的——多租户下无法给每个 catalog 一把独立的钥匙。

本工程在 `S3` / `OBS` / `OSS` 三种 `storageConfigInfo` 上各加两个字段补上这个缺口：

| 字段 | 读写 | 说明 |
| --- | --- | --- |
| `accessKeyId` | 可读可写 | 不是秘密；回显它是为了让控制台显示「配的是哪把钥匙」，也是判断「有没有配凭据」的依据 |
| `secretAccessKey` | **只写不读** | 落库前加密，任何响应都不回显 |

几个刻意的取舍：

- **`secretAccessKey` 只写不读**，因此 `PUT` 省略它表示**保持原值**而不是清空——
  这是规格 `PUT`「整体替换」语义的唯一例外。不这样处理的话，改一次 `endpoint`
  就得重新贴一遍密钥，而密钥本该是一次性输入的东西。
  要清除则显式提交一对空串（控制台表单里有对应按钮）。
- **加密落库，fail-closed。** 用 AES-GCM（AES-256）加密后写进
  `storageConfigInfo`，密钥由 `paimon.rest.storage.credential-secret-key` 提供
  （Base64 且解码后必须恰好 32 字节）。未配该密钥时**拒绝保存**静态凭据并返回 400，
  而不是明文落库；取值非法则启动即失败。密文形态带 `v1:` 前缀，
  兼作「这是密文还是别人手工塞进来的明文」的判别依据——明文当作密文传入会明确 500。
- **与 `storageName` 互斥。** 两者都在回答「用哪组密钥」，同时出现返回 400，
  而不是定义一条谁压过谁。只给 `secretAccessKey` 不给 `accessKeyId`（或反之、
  以及换 ID 却不带新密钥）同样返回 400：无法区分是笔误还是有意为之。
- **优先级最高且与 `stsUnavailable` 正交**，理由见第 3 点。

代价同样是 `storageConfigInfo` 的字段集合超出规格。影响面比 OBS / OSS 更小：
引擎只是把 `storageConfigInfo` 原样透传，多出来的两个字段它读不懂也不会读
（真正的凭据是服务端在下发时替换成 `s3.access-key-id` 这类键的），
因此 REST Catalog 协议本身不受影响。反过来说，**用 Polaris 服务端读本工程建的 catalog**
只会多看到一个不认识的字段，不会因此失败。

### 6. 元数据模型按「当前值 + 历史版本」组织

- 表 schema：实体上保存当前版本，`paimon_table_schema` 保存全部历史版本，支撑 `rollback-schema`。
- 快照：按 `snapshot_id` 单调存放，`rollback` 丢弃目标之后的记录并回移表指针。
- 分区：以规范化 spec 键唯一标识，统计遵循规格的「负值为未测量、覆盖与累加语义不同」约定。
  唯一约束落在 spec 键的**摘要**上而非原文上，原因见第八节第 11 点。

### 7. 多态变更在服务层分派

规格中 `SchemaChange`（12 种）、`ViewChange`（6 种）、`FunctionChange`（6 种）都是以
`action` 判别的多态联合。实现上统一按 `List<Map<String,Object>>` 接收，在
`SchemaChangeService` 等处按 `action` 分派，未知 action 返回 400。
这样既保持了校验的严格性，也避免把多态解析绑死在特定 JSON 库的注解体系上。

---

## 五、Paimon 规格超出 Polaris 模型的部分

以下能力在 Iceberg / Polaris 的目录模型中没有直接对应物，属于 Paimon 表格式特有的目录职责：

| 能力 | 端点 | 说明 |
| --- | --- | --- |
| 分区登记与统计 | `.../partitions`、`/drop`、`/mark`、`/list-by-names`、`/list-by-filter` | 除分区本身外还维护记录数、文件数、体积、桶数，并支持标记分区完成 |
| 快照清单与版本快照 | `.../snapshot`、`.../snapshots`、`.../snapshots/{version}` | 直接暴露 base / delta / changelog / index manifest 指针 |
| 分支 | `.../branches` | 命名历史线，支持从标签派生、重命名、推进 |
| 标签 | `.../tags` | 快照的稳定别名 + 保留时长（`1d` / `12h` / `30m`） |
| 流式消费者 | `.../consumers`、`/reset` | 保存下游消费位点（下一个待消费快照） |
| 函数 | `.../functions` | `sql` / `file` / `lambda` 三类定义 |
| 语义视图 | `.../semantic-views` | 实验性；整篇保存模型文档，与 SQL 视图分开命名空间 |

---

## 六、管理面实现：把 RBAC 落到关系模型

管理 API 的端点契约见 `docs/management-api-contract.md`，判定细节与配置见
`docs/authorization.md`。这里只说明结构取向。

### 1. 授权只挂在 catalog role 上

链路是「主体 → principal role → catalog role → 资源授权」，两级关联都是多对多。
**权限不直接授予主体**，这是把权限的最小管理单元收在一个可复用的角色上：同一个
principal role 可以在多个 catalog 上挂不同的 catalog role，权限集合由角色链推导。

### 2. 权限蕴含关系按「有出处」编码

规格只列出权限取值，不描述蕴含（哪项权限包含哪几项）。实现上把 Polaris 访问控制文档里
**明文列举**的规则逐条编码进 `PrivilegeModel`，每条标注文档出处，文档没有明说的不做推断
（例如 `CATALOG_MANAGE_METADATA` 只说「full management」，未逐项列举，就不再向下展开）。

其中一个反直觉的取舍值得记录：`*_FULL_METADATA` 按**权限名前缀**推导，而不是取规格里
该层级的枚举全集。因为规格的层级枚举表达的是「该层级可以授予哪些权限」，而不是
「FULL_METADATA 蕴含哪些权限」——每个层级枚举里都列有 `CATALOG_MANAGE_ACCESS`，
若直接取全集，一个只应写表的角色就会获得授权管理能力，属于权限提升。

### 3. 授权判定集中在拦截器，映射表 fail-closed

管理 API 的路径不含被操作对象（主体名、角色名在请求体里），因此判定必须放在控制器内；
catalog API 的路径已含全部定位信息，判定统一放在拦截器。catalog API 的
「路径 → 所需权限」映射集中在 `CatalogAccessRules` 一张表里，**未登记映射的端点直接拒绝
而不是放行**——忘记登记会在第一次调用时立刻暴露为 403，而不是静默放行。
测试从运行时的请求映射里枚举全部端点核对覆盖率，新增端点若忘记登记会让构建失败。

### 4. 自举入口

权限判定需要一个「能创建第一个主体」的入口，否则全新部署无法启动授权体系。
实现上用配置表达：`authorization.service-admins` 给出服务管理员名单（管理规格的权限枚举里
没有「管理主体」这一层，说明 Polaris 把它交给服务管理员而非权限判定），
`bootstrap-principal` / `bootstrap-principal-role` 在启动时预置引导主体与该主体到各 catalog
`catalog_admin` 的角色链。

### 5. 乐观并发用 `entityVersion`

主体、principal role、catalog role 都带 `entityVersion`，`PUT` 请求携带
`currentEntityVersion`，不符时返回 409 由调用方重新读取。三个实体的版本字段各自独立递增。

---

## 七、Spark SQL 扩展：把管理 API 接到 SQL 里

完整说明见 `docs/spark-sql-extension.md`。这里记录两个影响架构的结论。

### 1. Spark 3.5 的 SQL 语法是封闭的，只能自备语法

`SqlBase.g4` 的 `statement` 规则没有类似 `.*? #unsupported` 的兜底分支，因此
`CREATE PRINCIPAL x` 在词法 / 语法阶段就会失败，无法用 visitor 拦下；扩展点
`ParserInterface` 也不提供「往已有语法里加规则」的能力。因此子模块自带一份只描述
管理语句的语法，在 `parsePlan` 里先试自己的解析器，解析失败（`ParseCancellationException`）
即把语句原样交回原生解析器。

判断依据是「整条语句能否匹配到 EOF」，而不是字符串前缀猜测。前者不会误吞 `SHOW TABLES`
这类原生语句，也不会因为残篇而产出两套错误信息。

### 2. ANTLR 版本必须与目标 Spark 发行版一致

这是实测踩到、后果最重的一条：ANTLR 的 ATN 序列化格式随版本变化。Spark 3.5.9 自带的
`SqlBaseLexer` 由 ANTLR 4.9.3 生成（ATN 序列化版本 3），若子模块把 `antlr4-runtime`
抬到 4.13.x（版本 4），运行时会**把 Spark 自己的 antlr4-runtime 顶掉**，于是
`SqlBaseLexer` 在类初始化阶段就失败，受损的是**整个会话的 SQL 解析**，而不只是本扩展。

因此 `antlr.runtime.version` 与 `antlr4-maven-plugin` 的版本都钉在与 Spark 一致的值上，
升级 `spark.version` 时必须同步核对。

### 3. 两个模块的依赖世界不能混

聚合 POM 不继承 `spring-boot-starter-parent`：其 `dependencyManagement` 会把 Spark 自带的
Jackson 2.15.2 抬到 2.21.x，并替换 netty 与 jersey，导致 Spark 侧出现难以定位的版本冲突。
改由 `lakehouse-rest-server` 自己继承 Spring Boot 父 POM，聚合 POM 只钉核心插件版本。

---

## 八、缺口与后续可做项

按优先级排列，均为当前实现有意留出的边界：

1. **静态令牌的映射是静态配置**。`auth.token-principals` 决定「静态令牌 → 主体名」，
   改映射要重启。新建主体不受此限：创建时返回的 `clientId` / `clientSecret` 走
   OAuth 2.0 客户端凭据（`POST /api/catalog/v1/oauth/tokens`）即可立刻换取访问令牌
   （见 [`console-auth.md`](console-auth.md)）。剩下的一处边界是访问令牌
   **无法即时撤销**，只能等 `access-token.ttl` 过期或轮换签名密钥。
2. **行过滤与列脱敏策略未实现**。`auth` 端点返回空的过滤表达式与脱敏映射，
   仅做列存在性校验；实体标签（entity tags）同样未实现。
3. **多权限授权不是原子的**。规格没有批量授权端点，`GRANT a, b ON ...` 会顺序发出多次调用，
   中途失败时前面的已经生效。
4. **`list-by-filter` 未下推谓词**。规格允许返回超集，当前直接返回全部（可按名称前缀收窄）分区，
   由客户端复核；精确实现需接入 Paimon `Predicate` 反序列化与表达式求值。
5. **分页 token 为无状态偏移量**。大结果集下的深分页效率一般，可改为基于
   `(name, id)` 的游标。
6. **凭证下发未对接云 IAM**。见第四节第 3 点。
7. **表路径未随重命名迁移**。与 Paimon 服务端一致，重命名只改目录中的名称；
   若产品上要求迁移数据，需要在服务端编排对象存储操作。
8. **快照提交只有乐观并发，无跨实例悲观锁**。多实例部署下的写冲突依赖客户端重试。
9. **`CATALOG_FULL_METADATA` 与语义模型的延迟授权未实现**。前者在规格的权限 enum 中不存在，
   后者被 Polaris 文档标注为 deferred。
10. **规格对两个只读端点漏写了错误响应**。`GET /catalogs/{catalogName}/catalog-roles` 与
    `GET /catalogs/{catalogName}/catalog-roles/{catalogRoleName}/grants` 只声明了 `200`，
    没有 `403` / `404`（33 个 operation 中 31 个声明 `403`，27 个声明 `404`）。
    本实现仍对这两个端点鉴权，即按规格未声明也校验的失败关闭方向处理，
    因此调用者在无权限时拿到的是 `403` 而非规格里不存在的 `200`。
    逐行对照见 `docs/management-api-contract.md` §4.5。
11. **无上界的关键字段以摘要参与唯一约束**。MySQL 的索引键上限是 3072 字节
    （utf8mb4 下 768 字符），而 `paimon_partition.spec_key`（1024 字符，与 `table_id` 相加）
    与 `paimon_resource_grant.namespace_key`（命名空间路径拼接，规格未限制层级数）
    放不进唯一索引，建表会被 `ERROR 1071: Specified key was too long` 拒绝。
    做法是原文入库供等值查询、SHA-256 定长摘要（`spec_hash` / `namespace_hash`）参与唯一约束，
    索引长度与值的实际长度解耦而唯一性语义不变。摘要在 `@PrePersist` / `@PreUpdate`
    里由原文重算，不依赖调用方是否记得规范化。
    注意：这是**面向 MySQL 的必要改动**，不是可选的优化。
12. **静态凭据的加密密钥不支持多代共存**。轮换 `paimon.rest.storage.credential-secret-key`
    会让库里已有的密文全部解不开，必须配合一次凭据重录。细节见
    [`../README.md`](../README.md) 第 6 节「实现说明与已知边界」第 20 条。

---

## 九、Polaris 1.x 功能盘点与采纳判断（2026-10 视角）

前八节写于对标 0.9/1.0 的阶段，只覆盖「目录 + 鉴权 + 凭证下发」这条主线。Polaris 在
2025-07 发布 1.0 后一路到 **1.8.0（2026-09-28）**，2026 年初已毕业为 Apache TLP，
能力面扩到了策略、事件、联邦、外置授权几块。本节按「值不值得抄」重排一遍，
事实依据为官方 1.8.0 发布说明、1.7 的策略文档与 1.5/1.8 的 delegation 文档。

### 9.1 能力面与我们的现状

| 层 | 能力 | Polaris 落点 | 本仓库现状 |
| --- | --- | --- | --- |
| 目录与协议 | 多 catalog、嵌套 namespace、internal/external | 基础形态 | ✅ 已有（`{prefix}` + `CatalogEntity`，external 表） |
| | ETag 条件请求（`loadTable`） | 1.0 起 | ❌ 无 `If-None-Match` |
| | 快照过滤（只要当前引用的快照） | 1.0 起 | ❌ 全量返回 |
| | 通用表（Delta / Hudi 等非 Iceberg 表） | 1.3 起转正 | ❌ Iceberg 侧只有 Iceberg 表（Paimon 侧有表种类） |
| | 外部 catalog 联邦（HMS / Glue / 外部 REST / BigQuery Metastore） | 1.4/1.5 | ❌ 无（只有「登记外部表」，不是联邦目录） |
| 安全与治理 | RBAC 两层角色 | 基础形态 | ✅ 已对齐（并扩到 Paimon + Iceberg 双格式） |
| | 凭证下发 + 刷新端点 + 作用域收窄 + STS 会话标签 | 1.0/1.4 | ⚠️ 有下发与缓存，**无刷新端点、无 STS、下发长期密钥**（§4 边界） |
| | 自身 IdP / 外部 IdP 双模 | 1.0 起 | ⚠️ 只有 OAuth2 客户端凭据 + 静态令牌映射 |
| | 外置授权（OPA / Ranger） | 1.3 / 1.5(Beta) | ❌ 无（判定在 `CatalogAccessRules` 一张表里） |
| | 行过滤 / 列脱敏 | 仍属路线图 | ❌ 无（`auth` 端点返回空规则） |
| 策略与运维 | 策略存储：4 类 `system.*` + 继承 + `applicable-policies` | 1.0 起，1.7 文档完整 | ⚠️ 等价语义散在「catalog 默认 + 表级覆盖」的开关里，无名、无版本、无来源 |
| | 策略执行 | **不做**（存了不会跑） | ✅ 有（调度 + k8s Spark 任务 + 历史） |
| 可观测与集成 | 事件框架（BEFORE/AFTER，多监听器，按类型过滤） | 1.0 / 1.5 | ❌ 无事件、无审计表 |
| | 引擎回传查询指标（行数/字节/提交） | 1.3 起 | ❌ 无 |
| | CLI / Python 客户端 / Helm / admin tool / 二进制分发 | 1.0—1.5 | ⚠️ 有 Helm 清单与校验脚本，无 CLI |

两条结论先摆在这里：

- **Polaris 缺执行器，我们缺声明层。** 它的策略是「惰性」的——官方文档与社区文章都明说
  「stored policy is inert，既不调度也不执行」；而我们的优化任务恰好只会执行、不会表达意图。
  两边的短板正好互补，所以最值得抄的是它的**策略声明层**，不是它的执行（它也没有）。
- **它有而我们不必抄的，多是「平台化」能力**（联邦、通用表、realm 多租户）。这些能力的前提是
  「做一个所有元数据的统一入口」，与本仓库「元数据 + 优化 + 权限」的定位不同，抄过来会改变产品边界。

### 9.2 值得采纳（按价值排序）

#### ① 策略声明层：把「开关」升级成可命名、可继承、可溯源的策略对象 —— P0

Polaris 的形态：`POST /polaris/v1/{prefix}/namespaces/{ns}/policies` 建策略，
`PUT .../policies/{name}/mappings` 挂到 catalog / namespace / table（含 view），
`GET .../applicable-policies` 查「对这张表最终生效的是什么、来自哪一层」。四类内置类型：
`system.data-compaction`、`system.metadata-compaction`、`system.orphan-file-removal`、
`system.snapshot-expiry`，内容按 JSON-Schema 校验，带自动递增的版本号。

我们缺的正是这三件事：

| 现在的问题 | 具体表现 | 策略层怎么解 |
| --- | --- | --- |
| 批量运维靠人点 | 新建一个库有 50 张表，要逐表开三项优化；漏一张就一直不 compact | 策略挂在 namespace 上，新表天然继承 |
| 生效值说不清 | 表级开关覆盖了 catalog 默认值，但库里只存了「最终值」，没有「从哪来」 | `applicable-policies` 返回 `appliedAt`（catalog / namespace / table）+ policy 版本 |
| 例外无法回收 | 临时把某表调激进，事后要手工改回默认值 | 删掉表级映射即回落到上层，不需要「改回去」 |

落地建议：

- 表结构加两张（或复用现有的策略行 + 一张映射表）：`optimization_policy`
  （name / type / content / version / inheritable）+ `optimization_policy_mapping`
  （policy_id / target_type / target_path）。
- 现有 `paimon_table_optimization` 的三组开关**保留**，作为「表级最后覆盖」；
  读取顺序 = 表级显式值 > 最近一层策略 > catalog 默认值。
- **任务明细里记 `policy_id` / `policy_version` / `applied_scope`**。没有这一列，
  「这次为什么用 128 MB 而不是 256 MB」永远查不清；有了它，历史本身就是审计记录。
- 继承语义选**整条替换**，不做字段级深合并。Polaris 就是这么定的（同类型只允许挂一条），
  社区也有反面意见（建议深合并），但深合并会让「生效值」无法用一条记录解释，
  排障时反而更难。要改单个参数就新写一条策略，语义显式。

#### ② 事件框架：审计、缓存失效、外部同步共用一条出口 —— P1

Polaris 在每次操作前后各发一个事件，`PolarisEvent` 带 `eventId` / `timestamp` / `user`
（含激活的角色）/ `requestId` / OpenTelemetry 上下文；1.5 起支持多监听器与按事件类型过滤。

我们没有事件（代码里没有 `ApplicationEventPublisher`，也没有审计表），
现在只有实体上的 `createdBy` / `updatedBy` 两个字段——它能回答「谁最后改的」，
回答不了「谁在什么时候删了这张表、删之前叫什么、当时带着什么权限」。

最小实现（不必照抄它的二十几个事件名）：

- 定义 6~8 个我们真正关心的领域事件：表创建/删除/重命名、提交、授权变更、
  策略变更、优化任务终态。
- 一张 `audit_event` 表（event_id / type / principal / request_id / target / 时间 / 属性 JSON），
  写库与写日志走同一个 listener。
- listener 可插拔：先 DB + 日志，再按需接 Kafka / Webhook。控制台的「刷新」也可以从
  全量轮询改成「按事件增量拉」。

#### ③ 凭据下发的三处加固 —— P1（刷新端点最优先）

Polaris 有三件我们没做的事，各自对应一个具体故障：

| 加固项 | Polaris 做法 | 我们现在的故障形态 |
| --- | --- | --- |
| 凭据刷新端点 | `loadTable` 响应里给 `client.refresh-credentials-endpoint`，客户端在过期前主动换 | 长查询跑到 TTL 之后开始 403；只能靠把 TTL 调长，等于放大泄露窗口 |
| 作用域收窄 | storage-scoped credentials（只覆盖该表路径） | 下发的密钥覆盖整个 bucket，一张表的凭据能读全库 |
| STS 会话标签 | 1.4 起带 session tags，CloudTrail 可按主体/表归因 | 云侧审计只能看到「服务端那把钥匙」，看不出是谁在读哪张表 |

刷新端点的性价比最高：协议里已经留了位置（Iceberg REST 的 credential-refresh），
客户端会调，服务端只需按同一套判权再签一次短 TTL 凭据。STS 与作用域收窄要云侧信任关系，
属于外部集成，排在它后面。

### 9.3 有条件采纳

| 能力 | 采纳条件 | 解决的问题 |
| --- | --- | --- |
| `loadTable` 的 ETag / `If-None-Match` | 无（成本最低） | 引擎与控制台反复拉同一份 metadata；大表 metadata 几 MB 起 |
| 快照过滤 | 大表快照数上百后 | 每次 load 都回全量快照列表，响应体与解析开销随历史线性涨 |
| OPA / Ranger 外置授权 | 客户已有策略系统，且要求「策略即代码、可测试」 | 权限规则锁在 Java 表里，改一条要发版；无法与企业统一策略平台对齐 |
| 外部 catalog 联邦 | 先只做**只读投影**一个外部 catalog | 客户已有 Glue/HMS，不想迁移；做双向同步则会与我们的元数据模型打架 |
| CLI 的 `setup export/apply` | 多环境（dev/staging/prod）配置迁移需求出现时 | 主体、角色、授权、策略的跨环境复制目前只能手工点 |
| 引擎回传查询指标 | 有成本归因 / 热度分析需求时（二期） | 不知道哪张表被谁读了多少，冷热分层与优化排期没有依据 |

### 9.4 不建议采纳

1. **不要学它「策略不执行」**。Polaris 的 policy 只表达意图，执行要靠外部服务；
   我们已经有调度器与 Spark 任务，把执行留在这里是优势。抄声明层，不抄这个缺口。
2. **不要照抄 policy 的 content schema**。它的四类 schema 是绑死 Iceberg procedure 参数的；
   我们要同时覆盖 Paimon 与 Iceberg（键名与指标口径都不同），继续用「动作 + 键值对」的
   现有形态更稳，策略层只负责「哪条规则、挂在哪、第几版」。
3. **remote signing 不是「Polaris 有的能力」**。Iceberg REST 规格里有这个模式，
   但 Polaris 1.8.0 的发布说明明确写着「remote signing (not implemented)」，
   请求它会返回 400 并让你「去掉 header、自己在客户端配存储凭据」。若我们要做，
   这是**差异化**而不是对齐，且要先摸清客户端支持面（Spark / Trino 各自的支持程度不同）。
4. **通用表（Delta / Hudi）**：除非有明确的多格式客户，否则只增加元数据模型的分支成本。
   Paimon 侧的表种类（`format-table` / `object-table` / `lance-table`）已经够用。
5. **realm 级多租户**：我们的隔离边界是 catalog，再加一层 realm 会让
   「catalog 名唯一性」「权限路径」「控制台切换」三处同时变复杂，收益不明显。

### 9.5 一句话结论

Polaris 这一年新增的能力里，真正该抄的只有**策略声明层**（外加事件与凭据刷新两个补丁）；
它的联邦、通用表、多 realm 是「做平台」的路线，我们抄了会模糊定位。
反过来，它缺的执行器、双格式支持、控制台，正是我们已经有的——
所以对齐的方向是「把我们的执行能力用它的声明模型表达出来」，而不是「把它的功能清单实现一遍」。
