# Maven 模块布局与命名方案：从「Paimon 专用」到「格式中立」

[`iceberg-rest-catalog-design.md`](iceberg-rest-catalog-design.md) 定了「一份元数据同时服务
Paimon 与 Iceberg」的协议方案。本文件解决它的前置问题：**代码怎么分模块、artifact 叫什么名字**。

两件事必须分开看，因为它们可以分别落地、代价也差一个数量级：

| 事项 | 性质 | 代价 | 是否必须 |
| --- | --- | --- | --- |
| **命名**（artifactId、包名、配置前缀、对外名字） | 纯命名，不动一行逻辑 | 小到大，取决于「除了本仓库还有谁写它」（§4 / §10） | 是（本文件的要求） |
| **模块拆分**（服务端 1 个 jar → 4 个 jar + 1 个新父 POM） | 搬迁 + 依赖边界 | 中（约 120 个类换目录，包名不变所以无 import 改动） | 建议，但有明确的「不拆」判据（§6） |

本文件给出结构、取舍与判据；各模块自己的落地记录（Iceberg 面 / Spark 拆版本 / Flink）见 §12–§14。

---

## 1. 结论

| 问题 | 定案 |
| --- | --- |
| 命名族 | **`lakehouse-rest-*`**，groupId 仍是 `io.github.melin` |
| 骨架模块 | `lakehouse-rest-parent`（聚合）、`lakehouse-rest-spring-parent`（服务端世界的父）、`-core`、`-storage`、`-server` |
| 协议面模块 | `lakehouse-rest-protocol-paimon`、`lakehouse-rest-protocol-iceberg` —— **格式名只允许出现在协议面模块的 artifactId 里**（§2.2） |
| 集群侧模块 | `lakehouse-rest-optimizer`；Spark 侧自 §13 起拆成 `lakehouse-rest-spark-parent` + `-common` + `-3.5` + `-4.1`（一份源码、两个版本产物）；Flink 侧（§14）拆成 `lakehouse-rest-flink-parent` + `-1.20` + `-2.3`（同样一份源码、两个版本产物） |
| Maven 项目数 | **15**（构成见 §3.1 的结构树：聚合 + 两个父 POM + 服务端 5 jar + Spark 4 件 + Flink 3 件 + optimizer） |
| Java 包名 | **`io.github.melin.lakehouse.rest`**，与 artifactId 的 `lakehouse` 对齐；协议面各独占一层格式子包（§11） |
| Spring 配置前缀 | **`lakehouse.rest.*`**。客户端键 `spark.lakehouse.rest.*` 与 k8s 资源名各有各的判据，都不跟它走（§4） |
| k8s 资源名 | **`lakehouse-rest*`**，与镜像名同词根；改名要连带改 selector、Secret 引用与 Service DNS（§4） |
| 加新格式面 | 按 §5 的顺序，新格式的代码从第一天就落进新的 `-protocol-<格式>` 模块 |
| 当前形态 | 15 个 Maven 项目（见上一行）；四个服务端模块嵌在 `spring-parent/` 下（§9）；类的实际落点见 §8 |

---

## 2. 命名定案

### 2.1 为什么要一个格式中立的名字

`paimon-rest-server` 这样的名字把「实现」写进了「产品」：它说明的是这个服务端
服务哪种表格式，而格式恰恰是会增加的那一维。加一个格式就要重新思考命名，加第三个格式（仓库里
已经出现 Lance，见 [`console-table-detail.md`](console-table-detail.md) 的页签清单）
就再想一次。中立名的收益不是好看，是**再也没有下一次改名**：

- **骨架**（聚合器、可执行物、共享库）按**问题域**命名 → `lakehouse-rest-*`；
- **协议面**（每个只服务一种格式）按**格式**命名 → `-protocol-paimon` / `-protocol-iceberg`。

第二条不是自相矛盾，而是分层的必然：`-protocol-iceberg` 里写着「Iceberg」是正确的，
因为它**就是** Iceberg 的实现；将来加 `-protocol-hudi` 时，骨架一行都不用动。

> 若「一个 paimon 都不许出现在 artifactId 里」是硬要求：唯一满足它的做法是
> 把两个协议面合回一个 `lakehouse-rest-protocol` 模块、用包名（`.protocol.paimon`
> / `.protocol.iceberg`）区分格式。**不推荐**——那等于把两个依赖世界（Paimon 与
> Iceberg）重新塞回一个编译单元，而这正是本方案要拆开的东西，且边界只能靠 review 守，
> 不能靠构建守。下面的方案按推荐路径写。

### 2.2 命名分层规则（写进 AGENTS.md 的那一条）

| 层 | 命名依据 | 例 |
| --- | --- | --- |
| 骨架 / 部署物 / 共享库 | 问题域，中立 | `lakehouse-rest-{parent,spring-parent,core,storage,server}` |
| 协议面 | 格式名 + `protocol-` 前缀 | `lakehouse-rest-protocol-paimon`、`-protocol-iceberg` |
| 引擎侧客户端 / 任务 | 引擎名 | `lakehouse-rest-spark`、`lakehouse-rest-optimizer` |
| Java 包 | `io.github.melin.lakehouse.rest` | 与模块边界正交；但**对外不是自由的名字**——它出现在用户的 `spark.sql.extensions` 里，动它要按 §10 的判据先问「谁写过它」 |
| 部署资源名 | `lakehouse-rest*` | namespace `lakehouse-rest`、Service `lakehouse-rest-server`，与镜像同词根（§4） |

### 2.3 候选名与取舍

| 候选 | 评价 |
| --- | --- |
| **`lakehouse-rest-*`** | **采用。**「湖仓」是 Paimon 与 Iceberg 的共同上位词；`lakehouse-rest-server` 读起来就是「湖仓 REST catalog 服务端」，语义不欠不溢 |
| `lakehouse-catalog-*` | 备选。少一个 `rest`，但 `catalog` 在本仓里是**资源实体**（`lakehouse_catalog` 表、`CatalogService`），拿它当产品名会与实体名抢词 |
| `table-format-*` / `openlake-*` | 语义正确但偏长偏泛，且读不出「这是个服务端」 |
| `catalog-rest-*` | 拒绝：`rest-catalog` 是**协议名**不是产品名，`rest-catalog-server` 等于「REST 目录服务的服务器」 |
| `paimon-iceberg-*` | 反例：把两个格式都写进名字，第三种格式到来时作废——正是要避免的模式 |
| 保留 `paimon-rest-*` | 拒绝：产品已不是 Paimon 专用；名字会长期误导新读者 |

---

## 3. 模块结构

### 3.1 目标结构

```
io.github.melin:lakehouse-rest-parent                     pom   聚合器 + 集群世界的父
├── lakehouse-rest-spring-parent                          pom   服务端世界的父（Boot 版本锚点）
│   ├── lakehouse-rest-core                               jar   领域与共享契约
│   ├── lakehouse-rest-storage                            jar   对象存储访问
│   ├── lakehouse-rest-protocol-paimon                    jar   Paimon 协议面（60 端点）
│   ├── lakehouse-rest-protocol-iceberg                   jar   Iceberg 协议面（35 端点里实现的 30 个）
│   └── lakehouse-rest-server                             jar   可执行：装配 / 管理 API / 控制台宿主
├── lakehouse-rest-spark-parent                           pom   Spark 侧的聚合（复用根 POM 的依赖世界）
│   ├── lakehouse-rest-spark-common                       jar   管理 API 的 REST 客户端（纯 Java）
│   ├── lakehouse-rest-spark-3.5                          jar   Spark 3.5 / Scala 2.12 / ANTLR 4.9.3
│   └── lakehouse-rest-spark-4.1                          jar   Spark 4.1 / Scala 2.13 / ANTLR 4.13.1
│       （shared/ 不是模块：两个版本模块共用的源码目录，见 §13）
├── lakehouse-rest-flink-parent                           pom   Flink 侧的聚合（复用根 POM 的依赖世界）
│   ├── lakehouse-rest-flink-1.20                         jar   Flink 1.20.1 + Paimon 2.0.0 + Iceberg 1.11.0
│   └── lakehouse-rest-flink-2.3                          jar   Flink 2.3.0 + Paimon 2.0.0（2.2 连接器）+ Iceberg 1.12.0
│       （shared/ 同上：catalog 选项与端到端用例的唯一一份，见 §14）
└── lakehouse-rest-optimizer                              jar   k8s 优化任务
```

> 磁盘布局与这棵树**一致**：四个服务端模块的目录就嵌在 `lakehouse-rest-spring-parent/`
> 里面，所以 `spring-parent` 的 `<module>` 写裸名字、子模块的 `<parent>` 不用写
> `<relativePath>`。嵌套布局换来的是「父子关系在磁盘上看得见」与少一条要记着写的配置。

Java 包与这棵树**部分对齐**——对齐到「面」这一级，不对齐到模块：

```
io.github.melin.lakehouse.rest            core / storage / server 三者共用
├── {config,domain,dto,service,support,web}
├── paimon.{dto,service,support,web}      protocol-paimon 独占
└── iceberg.{dto,service,support,web}     protocol-iceberg 独占（见 §12）
```

> 共用是为了「搬类不改 import」（§7 那条：组件扫描按包不按 jar）；独占是为了让协议面的
> 边界写进包名，而不只是 artifactId。两者的取舍见 §11。

依赖方向（箭头 = 编译期依赖），三条不变量用 maven-enforcer 的 `bannedDependencies` 机械守住：

```
lakehouse-rest-server
  ├─→ lakehouse-rest-protocol-paimon ──→ core, storage, org.apache.paimon
  ├─→ lakehouse-rest-protocol-iceberg ─→ core, storage, org.apache.iceberg
  ├─→ lakehouse-rest-storage ──────────→ core, org.apache.paimon(仅 FileIO)
  └─→ lakehouse-rest-core ─────────────→ ∅ 格式依赖
```

| # | 不变量 | 守它的方式 |
| --- | --- | --- |
| 1 | `core` 不依赖任何格式（`org.apache.paimon:*`、`org.apache.iceberg:*` 一律禁用） | core 的 POM 里 `bannedDependencies` |
| 2 | 两个协议面互不依赖 | protocol-iceberg 里禁 `lakehouse-rest-protocol-paimon`，反之亦然 |
| 3 | `iceberg-core` 只出现在 protocol-iceberg | 其余模块禁 `org.apache.iceberg:*`（这也把 `iceberg-rest-catalog-design.md` §8 风险 7 的承诺从「靠自觉」变成「编译期事实」） |

> 已核对这条可行：服务端主源码里**真正 `import org.apache.paimon` 的只有 4 个类**
> （`StorageFileIoFactory`、`CatalogFileService`、`TableKinds`、`TableMetadataService`），
> 另有 2 处只出现在注释里（`TableService` 的 javadoc、`TableDtos` 的说明）。
> 也就是说「把格式代码收进模块」的成本比类数看起来小得多：格式依赖本来就集中在这几处。

### 3.2 为什么是两个父 POM

根 POM 现在只做聚合，理由已经写在里面（Boot 的 `dependencyManagement` 会把 Spark 的
Jackson 2.15.2 抬到 Boot 的版本）。拆成 5 个服务端模块后，这条理由**放大**而不是消失：

- 如果让 5 个模块各自 `spring-boot-starter-parent` 作父（`<relativePath/>`），
  `paimon.version`、将来的 `iceberg.version`、Boot 版本就要在 4 个 POM 里各写一遍；
- 如果让根 POM 继承 Boot 作父，Spark / optimizer 会被 Boot 的依赖管理污染——**这条已经
  被明确否决过**，不能为了省一个 POM 又踩回去。

所以引入 `lakehouse-rest-spring-parent`：**一个依赖世界一个父**。它继承
`spring-boot-starter-parent`，钉住服务端世界共用的版本与插件，只聚合 `core` /
`storage` / `protocol-*` / `server`。

> 名字没叫 `-server-parent` 是为了避免与 `-server` 混淆；叫 `-spring-parent` 是直接说
> 「它锚的是哪个依赖世界」，与根 POM 注释里的措辞一致。

副作用（要写进 README 的构建章节）：

| 场景 | 命令 |
| --- | --- |
| 全量构建 | 从仓库根 `mvn -o install -DskipTests`（行为不变） |
| 只构建服务端世界 | `cd lakehouse-rest-spring-parent && mvn -o install`（reactor 里只有 5 个模块，Spark 不会进来） |
| 起服务端 | `mvn -o -pl :lakehouse-rest-server -am spring-boot:run`（`-am` 会带上 core / storage / 两个面） |

> 实现在磁盘上是**平铺**的（`spring-parent` 只是一个 POM，与它父的模块同级），
> 因此上面第三条 `-pl :lakehouse-rest-server` 能直接按 artifactId 选中——
> 不需要写成嵌套路径。见 §8.1。

### 3.3 每个模块凭什么独立

| 模块 | 装什么 | 凭什么值得一个模块 |
| --- | --- | --- |
| `-core` | 22 个实体的仓储（26 张表里 core 占 22 张，执行集群等 4 张在 server）、共享服务（catalog/database/table/快照、授权、凭据接口）、共享 DTO、共享 Web 组件（`/v1/config`、错误模型基类、两个认证拦截器、`CatalogAccessRules`、`RestServerProperties`）、协议面 SPI | 两个协议面都要用它，而它自己**一行格式代码都不该有**——这条约束只有在它是个模块时才守得住 |
| `-storage` | `StorageFileIoFactory`、`CatalogFileService`、`Paths`、`StorageConfigs`、`FileIoType`、凭据管理器的实现与缓存 | 它是唯一需要「往仓库写文件」的模块（Paimon 的 `FileIO` 在这里承担的是**通用文件系统客户端**：s3 插件加载、凭据刷新、路径模板）。若 Iceberg 面另引一套 hadoop-aws 做同样的事，就会有两套 s3 凭据解析逻辑——设计文档 §5.5 要的「凭据统一」当场破功 |
| `-protocol-paimon` | `paimon/web/` 下 9 个控制器、`paimon/dto/` 的 Paimon 契约、`TableMetadataService`（schema/snapshot 物化）、`TableKinds`、`SchemaChangeService`、`CredentialService` | 唯一允许 `import org.apache.paimon` 的服务端模块（`storage` 除外，且只用于 FileIO）；包名独占 `rest.paimon.*`（§11） |
| `-protocol-iceberg` | 35 个端点、Iceberg DTO、元数据代数（update 应用 / requirements 校验 / CAS）、投影派生（`schemas[]`/`snapshots[]`/`refs` → 行）、Iceberg 错误模型、`iceberg/preview/`（数据预览的读取器与只读 `FileIO`） | 唯一允许 `import org.apache.iceberg` 的模块；`iceberg-core` / `iceberg-data` / `iceberg-parquet` 带来的 avro、parquet 等依赖被关在这里 |
| `-server` | 启动类、管理 API 控制器（33 个 operation）、控制台宿主与静态资源、安全装配、优化调度与 k8s 提交、`application.yml` | **唯一产出可执行 jar 与镜像**的模块。镜像里有什么，看这一个 POM 就够了 |

### 3.4 跨模块的判断表（几个没写在包名里的类）

多数类的落点看包名就定了，下面这些是**需要判断**的，判断依据统一为
「它 import 了哪个格式的包」+「它是协议契约还是平台行为」：

| 类 | 落点 | 依据 |
| --- | --- | --- |
| `ConfigController`（`GET /v1/config`） | core | 两套协议共用同一个端点，按 `catalog_format` 组装两份响应（设计文档 §5.2） |
| `OAuthTokenController`（`POST /v1/oauth/tokens`） | core | Iceberg 客户端在拿到 catalog 配置**之前**就要换令牌，路径不带 prefix，与协议面无关（设计文档 §2.1 第 3 点） |
| `GlobalExceptionHandler` | core 基类 + 各面各一个 `@RestControllerAdvice` | 错误体形状按格式分派（设计文档 §2.3）；基类管兜底与日志，面内覆盖形状 |
| `CatalogAccessRules` / `AuthorizationInterceptor` / `BearerAuthInterceptor` | core | 按路径登记，与协议无关；两个面的请求都要过（Iceberg 路径逐条登记） |
| `RestServerProperties`（`lakehouse.rest.*`） | core | 配置项被协议面与装配都读（配置前缀见 §4） |
| `TableKinds` | protocol-paimon | import 了 Paimon 的对象表/格式表类型。「表种类」是 Paimon 特有概念——Iceberg catalog 里的表没有这一套，`protocol-iceberg` 里的类一处都不引用 `TableKinds`，所以它留在协议面而不是 core。控制台按**先看 catalog 格式、再看表种类**的顺序伸缩（`docs/console.md` §10） |
| `TableMetadataService` | protocol-paimon | `SchemaManager` / `SnapshotManager` / `JsonSerdeUtil` 都是 Paimon 的类 |
| `StorageFileIoFactory` / `CatalogFileService` / `Paths` | storage | FileIO 提供者 |
| `Optimization*`（任务记录、批次、调度、`KubernetesJobSubmitter`） | server | 它是**平台行为**（记账 + 提交 k8s），不是协议契约；`optimizer` 模块通过管理 API 回调，与协议面无关 |
| `Console*`（`ConsoleMetaService`、`Console*Controller`） | server | 控制台是服务端自己的用户面 |
| `dto/TypeDtos`、`CommonDtos`、`GrantDtos`、`PermissionDtos`、`Privilege`、`StorageDtos` | core | 公共类型：类型映射、错误码构造、授权词表、存储配置 |
| `dto/TableDtos`、`DatabaseDtos`、`Branch/Tag/Partition/Consumer/SemanticView/Function*Dtos` | protocol-paimon | Paimon 契约；Iceberg 侧的 `TableMetadata` 等用自己的类型（来自 `iceberg-core`） |
| `dto/ViewDtos` | 两面**各一份** | view 的两套契约形状不同（Iceberg 的 view 与 Paimon 的语义视图不是一回事） |
| `dto/Management*`、`Console*`、`Optimization*` | server | 管理 API 与控制台各自的契约 |

### 3.5 协议面 SPI（顺手解决一个既有工作项）

每个模块导出一个描述符，让「能力清单」由**实现自报**而不是手抄：

> **实际落地的形状与下面这份草案不同**（见 §12.2）：实际接口是 core 里的
> `CatalogProtocol`，只有 **3 个方法**（`format()` / `configResponse()` / `rename()`），
> 落在**协议面**模块而不是装配层。草案里的 `basePath()` 目前两个面都是 `/v1`，
> 没有第二个取值就没有抽象；`order()` 被「先查库里的 prefix 格式、查不到才看请求体」
> 这条更准的判据取代（`order()` 只能决定先问哪个面，而真冲突那两条的判据在请求体里）；
> `endpoints()` 没有进接口——清单是 **Iceberg 面自己的常量**（`IcebergEndpoints.SUPPORTED`），
> 因为只有 Iceberg 侧需要下发它，由 `scripts/verify-iceberg-endpoints.py` 做三方比对，
> 比加一个每面都要实现、Paimon 面只能返空的接口方法更直接。

```java
public interface CatalogProtocolFace {
    CatalogFormat format();          // PAIMON / ICEBERG
    String basePath();               // 目前都是 /v1
    Set<String> endpoints();         // 本面声明支持的端点（Iceberg 必须下发给客户端）
    int order();                     // rename 这类同路径端点的兜底顺序
}
```

价值有三处，都不是新发明：

1. `/v1/config` 的 `endpoints` 清单由面自报 → 设计文档 §9 要求的
   「清单 ↔ 实际 handler」契约测试有了明确的落点（比对 Spring 的
   `RequestMappingHandlerMapping` 里属于该面 basePath 的登记项）；
2. rename 的同路径冲突可以在装配层解决（`order()` 决定先问哪个面，因为主判据是 prefix）；
3. 将来加格式 = 加一个模块 + 一个实现，装配层不动。

---

## 4. 对外名字：谁持有、谁故意保留旧词

模块与 artifact 名之外，还有一批名字散落在部署与配置里。它们要不要跟着改，
只看一个问题：**除了本仓库，还有谁会写它**（判据的展开见 §10）。

| 名字 | 谁持有 | 现状 |
| --- | --- | --- |
| `lakehouse.rest.*`（Spring 配置前缀） | 用户的 `application.yml` | 当前前缀 |
| `spark.lakehouse.rest.{token,management.url,catalog.url,management.timeoutSeconds}` | 用户的 Spark 配置 | **故意保留**：客户端键一旦发布就是配置 API，改它等于一次要带迁移工具的破坏性发布 |
| k8s 资源名 / namespace `lakehouse-rest*` | 集群里的对象，以及集群外按这些名字写的东西（selector、Secret / ConfigMap 引用、Service DNS、NetworkPolicy、ServiceMonitor、`kubectl get -l` 脚本） | 当前名：与镜像名同词根。改它要连带改 Deployment / Service / PDB 三处 selector 与全部引用点，见 `deploy/kubernetes/README.md` §2.2 |
| 元数据库 `lakehouse_catalog` / 表前缀 `lakehouse_` | 本服务自己的元数据库 | 客户端全走 REST、不直连库；表划分见 [`data-model.md`](data-model.md) |
| `spark.sql.extensions` 的 FQCN | 用户的 Spark 配置 | 写当前名 `io.github.melin.lakehouse.rest.spark.ManagementSparkExtensions`；为旧包名留过的兼容壳已删除——壳的数量等于「历史的对外名字数」，不是「改过几次」，确认没有部署再用旧名就撤 |
| Maven 坐标 `lakehouse-rest-*` | 远端仓库 | 从未 `deploy`（无 `distributionManagement`），产物只有本地 `install` 与镜像分发，**不需要 relocation**。将来一旦对外发布过坐标，改名要补一个旧坐标 POM，用 Maven 原生 `<distributionManagement><relocation>` 重定向，不要自造兼容层 |

---
## 5. 再加一个格式面时的操作顺序

模块边界先于新格式的代码落地：控制器、DTO 与投影派生从第一天就住进新的 `-protocol-<格式>`，
而不是先长在 `server` 里、等边界定好再搬第二次。每一步独立提交、独立验证、独立回滚：

1. 先按 §6 的判据确认「要拆」；
2. 新建 `-protocol-<格式>`，嵌在 `spring-parent/` 下，包树对称地独占 `rest.<格式>.*`（§11）；
3. 协议面只依赖 core（不变量 2）；格式依赖（如 `iceberg-core`）只进协议面与 server 的装配（不变量 3，§8.2）；
4. server 只加一行依赖与装配；端点契约用静态比对脚本钉住（§12 的做法）；
5. 每一步之后 enforcer 三条不变量必须仍然全绿。

---
## 6. 「要不要拆」的判据

拆到 4 个服务端模块的收益是**编译期护栏**（§3.1 三条不变量）与**测试分区**；
代价是约 120 个类换目录、一套两个父 POM 的构建结构、以及每次加功能要想一次
「这段代码属于哪个面」。诚实地说：**模块边界在这里是编译期与测试期的隔离，不是运行期的**
——所有模块最终都进同一个 fat jar、同一个 JVM。所以别指望它解决依赖冲突之外的运行期问题。

| 情形 | 建议 |
| --- | --- |
| 近期要落双协议、且预期会有第三种格式（Lance 之类） | **拆**（按 §5 的顺序）。护栏的收益随格式数量增长 |
| 不拆模块，Iceberg 代码进 `server` | 可以做，但 `iceberg-core` + Paimon 的依赖会共存于一个 POM，边界只能靠 review；设计文档 §8 风险 7 的承诺退化成口头约定 |
| 只有 1–2 人维护、半年内不会再动模块边界 | 可以维持单模块。等真正需要第二个面时再拆，代价不变（搬迁是机械的，见 §11 的坑清单） |

不建议的中间态：**只拆 core 不拆协议面**。那样两个协议面都在 `server` 里，
`iceberg-core` 仍然进 `server`，三条不变量一条都守不住，白付了一半搬迁成本。

---

## 7. 方案文档之间的衔接

| 文档 | 关系 |
| --- | --- |
| [`iceberg-rest-catalog-design.md`](iceberg-rest-catalog-design.md) | 协议方案（本文件是它的前置：代码放哪、叫什么） |
| [`data-model.md`](data-model.md) | 表结构与元数据库库名（`lakehouse_catalog`）的现状见它 |
| [`catalog-api-endpoints.md`](catalog-api-endpoints.md) | Paimon 60 端点的落点由 §3.3 决定（多数进 `-protocol-paimon`） |
| [`management-api-overview.md`](management-api-overview.md) | 33 个 operation 全部留在 `-server` |
| [`spark-sql-extension.md`](spark-sql-extension.md) | `spark.sql.extensions` 的 FQCN 约束来自它 |
| [`console.md`](console.md) | 控制台构建产物的落点与 `verify-console.py` 的路径约定 |

---

## 8. 类的落点与依赖分工

§3.3 / §3.4 的两张表按「名字看着像谁」写，实际落点按**依赖图**定——搬迁前逐类核过
「搬完会不会出现反向依赖」（真按原文搬会产生 46 条反向边）。实测规模：
**core 117 · storage 9 · protocol-paimon 38 · server 46**（共 210 个类）。
读 §3.3 / §3.4 时要配合本节的差异。

### 8.1 六处与「名字看着像谁」不同的落点

| # | 类 | 方案原定 | 实际落点 | 为什么必须改 |
| --- | --- | --- | --- | --- |
| 1 | `TableService`、`DatabaseService`、`ViewService`、`FunctionService`、`Branch/Tag/Partition/Consumer/SemanticView` 服务、`CredentialService`、`TableLookup` | core（「共享服务」） | **protocol-paimon** | 它们**返回 Paimon 契约 DTO**（`TableDtos` / `PartitionDtos` / …），而 DTO 按方案在 protocol-paimon。服务留 core ⇒ core → protocol-paimon 反向依赖 ×20。服务与它产出的契约同模块才对 |
| 2 | `Codecs`、`SchemaSupport`、`TableDefaults` | （§3.4 未提） | **protocol-paimon** | 只被上面那批服务用；`Codecs` 还引用 `SchemaSupport`，两者不能分家 |
| 3 | `ManagementEnums` | server | **core** | 它不只是管理 API 的取值枚举：`StorageDtos`、`StorageConfigs`、`FileIoType`、`VendedStorageCredential`、`CatalogAccessRules`、`AuthorizationService` 都在读它。做成两类模块都能看见，只能放 core |
| 4 | `ConsoleDtos` | server | **core** | 被三处引用：core 的 `OAuthTokenController`、storage 的 `CatalogFileService`、server 的控制台三个控制器。它自己零出边，是个纯记录容器 |
| 5 | `StorageConfigs`、`FileIoType` | storage | **core** | 两者都不碰 FileIO，是「存储配置的校验/归一化」与「本部署接了哪些存储」的**词表**；而 core 的 `CatalogService` 要用 `StorageConfigs`，留在 storage 就是 core → storage 反向依赖。storage 因此只剩真正读写仓库的类 |
| 6 | `CatalogRole/Principal/PrincipalRole/ResourceGrant/ManagementCatalog` 服务、`ManagementMappers`、`LoginAttemptLimiter` | core（「授权」）/ server | **server**（`LoginAttemptLimiter` → **core**） | RBAC 那批服务只被管理 API 控制器与 `DataInitializer` 使用，没有任何 core 类依赖它们，而它们要用 server 的 `ManagementDtos`；`LoginAttemptLimiter` 反过来被 core 的 `ClientCredentialsService` 用，所以进 core |

另外两处数量修正：

- **Paimon 协议面是 9 个控制器，不是 10 个**。`ConfigController` 与 `OAuthTokenController`
  是两个协议面共用的（前者按 `catalog_format` 组装两份响应，后者路径不带 prefix，
  客户端在拿到 catalog 配置之前就要换令牌），都在 core。
- `WebConfig`（只登记两个拦截器，与协议无关）在 **core**，与
  `AuthorizationInterceptor` / `BearerAuthInterceptor` / `CatalogAccessRules` 同处。

**落点正确性的保证**：包名与类名不变，没有任何 import 需要动——「编译通过」本身就是
「搬对了」的证明，再加上构建期跑一遍依赖图确认零反向依赖。

### 8.2 父 POM 与依赖分工

| 放哪 | 内容 | 理由 |
| --- | --- | --- |
| `spring-parent` 的 `<dependencies>` | `spring-boot-starter-webmvc`、`-data-jpa`、`-validation`、`lombok` | 五个模块最终进**同一个 fat jar、同一个 JVM**，所以「谁需要 webmvc」只影响编译期可见性，不影响产物内容。集中一处，各模块的 POM 就只剩「它比别的模块多需要什么」 |
| `storage` / `protocol-paimon` | `paimon-core`（编译期） | 存储层只用 `fs` / `catalog.CatalogContext` / `options` 三个包；协议面用 `schema` / `utils` / `table.object` / `types` |
| `server` | `paimon-core` + `paimon-format` + `paimon-s3` | 这三个决定**可执行产物里装了哪几个文件系统插件与格式实现**，是部署决策 |
| `server` | `hadoop-client-api` + `hadoop-client-runtime`（`runtime`，3.3.4） | parquet 读取（两个协议面都是）需要 `FileInputFormat` / `Configuration`，而 parquet-hadoop 把它们声明成 `provided`。**没有它们，元数据一切正常、第一次预览读数据 500**——取舍见 `table-data-preview-design.md` §13 |

**Paimon 绝不放进父 POM 的 `<dependencies>`**：那样 core 会拿到它，不变量 1 当场破功。
enforcer 会拦下来，但更好的做法是别让这种事有机会发生。

### 8.3 `maven-enforcer` 的 `searchTransitive` 语义（易错处）

三条不变量的守卫写法并不统一，这是有意的：

| 模块 | `searchTransitive` | 禁什么 | 为什么 |
| --- | --- | --- | --- |
| core | 默认（true，查整棵树） | `org.apache.paimon:*`、`org.apache.iceberg:*`、两个协议面模块 | 「core 的依赖树里不出现格式包」才是要守的事实，哪怕是某个共享库顺手带进来的 |
| storage | 默认（true） | `org.apache.iceberg:*`、两个协议面模块 | 允许 paimon（FileIO 当通用文件系统客户端），不允许 Iceberg |
| protocol-paimon | 默认（true） | `org.apache.iceberg:*`、`lakehouse-rest-protocol-iceberg` | 不变量 2 与 3 |
| server | **false（只禁直接声明）** | `org.apache.iceberg:*` | 可执行产物最终要靠 `server → protocol-iceberg` 把 iceberg-core 带进来。要守的不是「产物里有它」，而是「除了那个面，没有第二个地方**声明**它」——默认的 true 会把这条规则读成「server 不许装配 Iceberg 面」，与设计相反 |

### 8.4 `paimon.version` 钉在 2.0.0

`paimon.version` 定为 **2.0.0**，不是「越新越好」：Paimon **2.1.0 把
`org.apache.paimon.schema.SchemaManager` 从类改成了接口**，而 `TableMetadataService`
还在用 `new SchemaManager(fileIO, path)`。升到 2.1.0 是一次独立的代码迁移
（迁移 `SchemaManager` 的用法、两处 `paimon.version` 一起改），不要顺手夹带。
另一个教训值得记：POM 里写 2.1.0 而代码编不过的现象**只在全量编译时出现**——
增量编译在源码未变时会整模块跳过，平时看起来是好的。

### 8.5 遗留

| # | 事项 | 说明 |
| --- | --- | --- |
| 1 | Paimon 升到 2.1.0 | 需要先迁移 `SchemaManager` 的用法（见 §8.4） |
| 2 | 测试分区 | 全部集成测试留在 `server`：它们需要完整应用上下文。真正划算的分区是把 `JwtTests` / `CredentialCipherTests` 这类**纯单元测试**随被测类下移——但那要在多个模块各配一份 surefire，收益不大 |

---
## 9. 目录层级与构建命令

四个服务端模块嵌在 `lakehouse-rest-spring-parent/` 之下：父 POM 的 `<module>` 不带 `../`，
子模块不写 `<relativePath>`（默认值 `../pom.xml` 恰好正确）。

**`-pl` 必须写 `:artifactId`，不能写裸名字。** `-pl` 收的是 `[groupId]:artifactId` **或相对路径**，
裸名字按后者解析；模块嵌套之后相对路径不再与 artifactId 同名，裸名字会报
`Could not find the selected project in the reactor`。统一用带冒号的坐标形式，它只看坐标、
与目录层级无关：

```bash
mvn -o -pl :lakehouse-rest-server -am clean package -DskipTests
mvn -o test -pl :lakehouse-rest-spark
```

Spark 模块的目录虽然在仓库根下、裸名字也还能用，但「哪个模块在哪一层」不该是要背下来的
知识——全仓统一带冒号（README、脚本、Dockerfile、k8s 清单同一步调）。

---
## 10. 判据：一个名字要不要跟着模块改名

包名能改、配置键与 k8s 资源名不跟着改，判据只有一条：**除了本仓库，还有谁会写它**
（逐项清单见 §4）。两个推论值得固定下来：

- **兼容壳的数量 = 历史的对外名字数，不是改过包名的次数。** 每多留一个壳，
  「哪些名字现在还有人在用」这个问题就多失去一个答案；确认没有部署再用旧名就撤。
- **对外契约的判断以「已发布」为准，不以「曾经写过」为准。** 从未提交、从未发布过的名字
  不欠任何人一个兼容层。

判断一个名字属于哪一类，只需要问「除了本仓库，还有谁会写它」；答案会变（比如配置键
核实过「尚无部署持有旧键」后从「不改」改成「一次性对齐」），但判据本身不变。

---
## 11. 包名分层：`rest.paimon.*` 与两条坑

协议面在**包名**上也要分得开：Paimon 面独占 `rest.paimon.{web,service,dto,support}`，
Iceberg 面对称地独占 `rest.iceberg.*`；core / storage / server 共用
`rest.{web,service,domain,dto,support,config}`。

**为什么是「先格式后分层」**（`rest.paimon.web` 而不是 `rest.web.paimon`）：协议面是这套
工程里最粗的一条切分线——两个面互不依赖、注定分模块、各带一套格式依赖（§3.1 不变量 2、3），
「面」比「层」更接近稳定边界。每个面独占 `rest.` 下的一棵子树，删掉一整面、或让某一面
自己演进内部分层（如 Iceberg 面要加 `rest.iceberg.metadata`），都不会碰到另一面。
协议面包名不设兼容壳（不在对外契约里，§10 的判据）。

### 11.1 坑一：同包引用不写 import，拆包时整片失效

旧布局下 core 的 `rest.dto.CommonDtos` 与 Paimon 面的 `rest.dto.TableDtos` **同包**，
后者直呼 `CommonDtos.Column`、从不写 import；`rest.service.CatalogService`（core）、
`rest.service.StorageFileIoFactory`（storage）同样被 Paimon 面的 service 直呼。
包名加一层，同包关系断掉，这些引用集体编译失败。

关键在于**这类边在「import 计数」里完全不可见**——它们本来就没有 import 行，
按「谁 import 了它」做审计会得出「core / storage 各 0 处」的假象。
server 侧也有同类边（如 `ManagementCatalogService → DatabaseService`），
说明这不只是协议面内部的事。

> **可复用的判据**：把类从一个包搬到另一个包时，要查的不是「它 import 了谁」，而是
> 「谁**在同一个包里**直呼它」。后者不会在 import 列表里留下任何痕迹，只有编译器会告诉你。

### 11.2 坑二：脚本拼 FQCN 时路径分隔符漏转换

生成 `import` 语句的脚本若从 `a/b/c` 形式的包路径拼 FQCN，漏掉 `/` → `.` 的转换会产出
`import a.b.c/pack.Class;` 这种非法语句；而且它在单层包的调用侧不暴露，错到第二次编译
才现形。教训：这类脚本的预演输出要**逐条读**，而不是只看条数——
`paimon/service` 与 `paimon.service` 在打印里只差一个字符。

---
## 12. `-protocol-iceberg`：加一个面

这是唯一一次**新建**的服务端模块（Spark / Flink 侧的拆分见 §13 / §14），因此它才是这份文件里
三条不变量的第一次真检验：新增一个协议面，骨架一行不改，而规则自己会拦住越界。

### 12.1 加一个面 = 加一行依赖

| 位置 | 改动 |
| --- | --- |
| `spring-parent/pom.xml` | `<module>` 清单加一条 |
| `lakehouse-rest-protocol-iceberg/pom.xml` | 新建：`<parent>` 不用写 `<relativePath>`（嵌套布局的收益，§9.1）、依赖 core + storage + `iceberg-core` |
| `lakehouse-rest-server/pom.xml` | **加一行** `lakehouse-rest-protocol-iceberg` |
| `lakehouse-rest-core` / `-storage` / `-protocol-paimon` | **零改动**（前三处已定好 `CatalogProtocol` SPI 与 `CatalogProtocols` 注册表的位置） |

那一行依赖**少写的表现是运行时 500**，不是编译错：core 通过 Spring 收集
`List<CatalogProtocol>`，少一个实现就意味着某个格式没有处理链。实测的报错正是
`no protocol implementation is registered for catalog format ICEBERG`——
这句话够用，但它出现在**第一次请求某个 Iceberg catalog** 时，而不是启动时。
这也是为什么新增 HTTP 用例值得：那个用例当场抓到了这行漏写的依赖。

### 12.2 SPI 实际落成 3 个方法，且落在协议面

§3.5 的草案（`CatalogProtocolFace`，4 个方法，含 `endpoints()` / `order()`）没有照抄。
实际是 core 里的 `CatalogProtocol`：

| 方法 | 为什么是它 |
| --- | --- |
| `format()` | 装配层要靠它把「收到的请求属于哪个面」换成「用哪个实现」 |
| `configResponse(...)` | `/v1/config` 的响应体按格式二选一——Iceberg 侧多一份 `endpoints`，Paimon 面根本没有这个字段，所以两份响应必须是两个类型，不能给一个类型加「按格式可空」的注解 |
| `rename(...)` | 两条同路径端点只能是「core 收请求 → 按格式转给对应面」。协议面之间禁止互相 import，core 又不认识 `{namespace:[...]}` 这种 Iceberg 形状的标识符，因此**只有 JSON 树能过这条边界**，绑定留在各面里 |

`basePath()` 没进接口：目前两个面都是 `/v1`，一个取值的抽象不叫抽象。
`order()` 没有解决问题——`order()` 只能决定「先问哪个面」，而两条 rename 的真判据是
**请求体里的字段名**（`{namespace:[...]}` vs `{database, object}`）。所以实际做法是
「先查库里 prefix 的格式，查不到才看请求体」，比顺序猜测准。
`endpoints()` 也没进接口：只有 Iceberg 侧需要下发清单，它落成 Iceberg 面自己的常量
（`IcebergEndpoints.SUPPORTED`），由 `verify-iceberg-endpoints.py` 与控制器映射做三方比对。
让 Paimon 面实现一个永远返回空集的方法，是把「一个面的私有需求」写成两个面的义务。

**实现者是一个 `@Component`，这本身就是不变量 2 的实现方式**：core 通过 Spring 收集
`List<CatalogProtocol>`，因此 core 不 import 任何一个协议面。
把它换成「core 里 new 一个」，core 就必须依赖该模块——规则会在 enforcer 那一步拦下。

### 12.3 三条不变量的实际处置（两处要关掉默认值）

| 不变量 | 落地时的情况 |
| --- | --- |
| 1 · core 不含任何表格式依赖 | 照旧；`CatalogFormat` 枚举、`CatalogProtocol` 接口、`CatalogProtocols` 注册表都在 core，它们只提格式**名字**，不引任何一个格式库 |
| 2 · 两个协议面互不依赖 | 照旧；两个模块各在 `bannedDependencies` 里禁对方。唯一真冲突的产物是 core 的 `CatalogRenameController` |
| 3 · `iceberg-core` 只出现在 `protocol-iceberg` | 照旧，但需要**两处配套**：① `storage` 的 `paimon-core` 必须 `<optional>true</optional>`——它是唯一允许依赖 Paimon 的非协议模块，而 `searchTransitive` 会把它传给 `protocol-iceberg`，于是 iceberg 面被自己的规则误杀；② `server` 的 `bannedDependencies` 要 `searchTransitive=false`（只禁直接声明）——它的可执行产物**必须**通过 `server → protocol-iceberg` 把 `iceberg-core` 带进来，默认的 true 会把这条规则读成「server 不许装配 Iceberg 面」，与设计正好相反 |

第二条那个「必须关掉默认值」的坑，§8.4 已经记过一次（`searchTransitive` 的语义）；
这次是同一个坑的另一面：**规则越严越要写清它是查直接声明还是查整棵树**，
否则下一个人会以为规则坏了。

### 12.4 包名对称已落地，无需再动

`rest.iceberg.{dto,service,support,web}` 与 `rest.paimon.*` 平级而不重合，§11 定的
形状一条不差。这条对称的价值在这次体现了：**新增一整面类不用改任何 `@ComponentScan`**，
因为扫描是按包树扫的，而两面都在 `rest.*` 之下。

### 12.5 计数与验收

| 项 | 值 |
| --- | --- |
| Maven 项目数 | 4 → **9** |
| 服务端 Artifact | 5 个模块（core / storage / protocol-paimon / protocol-iceberg / server） |
| 服务端用例 | 318 → **331**（新增 `IcebergRestCatalogApiTests` 10 个、`IcebergClientEndToEndTests` 3 个，并修好被新端点顶爆的 `CatalogEndpointAuthorizationTests`） |
| 全量用例 | 414 → **427**（服务端 331 + Spark 96）。服务端侧 `mvn -o test -pl :lakehouse-rest-server -am` 实测 **331 run / 0 failures / 0 errors / BUILD SUCCESS** |
| 校验脚本 | 新增 `verify-iceberg-endpoints.py`（15 项，不依赖 PyYAML），另四个脚本全绿 |

这次落地**先后三次被构建与测试拦住**，而三处都不是靠读文档发现的：
enforcer 抓出 `paimon-core` 的传递泄露；HTTP 用例抓出 server 漏写的那一行依赖；
同一批用例抓出「Paimon 面缺格式门禁」与「错误形状对集合路径判错」。

---

## 13. Spark 侧按版本拆分（3.5 / 4.1）

### 13.1 为什么必须拆

目标很直接：同一套管理语法要能同时装在 Spark 3.5 与 4.1 的集群上。三处约束决定了
「一个 jar 通吃」不可行，任何一处单独存在就已经足够：

| 约束 | Spark 3.5.9 | Spark 4.1.2 | 后果 |
| --- | --- | --- | --- |
| Scala 二进制版本 | 2.12.18 | 2.13.17 | class 文件互不兼容；`scala.collection.Seq` 在两边是不同类型 |
| ANTLR 运行时 | 4.9.3 | 4.13.1 | **生成代码的版本**决定 ATN 序列化版本（3 vs 4），跨版本反序列化直接抛异常 |
| `ParserInterface` 抽象成员 | 7 个 | 8 个（多 `parseRoutineParam`） | 共享类里实现它 → 3.5 报「不是父类型的成员」；不实现 → 4.1 报「需要抽象类」 |

另外还有一条只能靠实测发现的：**Jackson**。根 POM 的 dependencyManagement 把
`jackson-databind` 钉在 2.15.2（Spark 3.5 的版本）。这个钉版对 4.1 是错的——
Spark 4.1 用 2.21.2，其 `jackson-module-scala` 初始化时校验 databind 的主次版本，
不匹配的表现是「会话能建、普通 SELECT 能过，但任何走 `ParseException` /
`RDDOperationScope` 的路径都炸」。修法是在 4.1 模块里覆盖 `jackson2.version`，
而不是去改根 POM（根 POM 还要服务 3.5）。

### 13.2 结构与职责

```
lakehouse-rest-spark/
├── pom.xml                                  lakehouse-rest-spark-parent（pom，聚合 + 共享构建配置）
├── shared/                                  不是模块：两个版本模块共用的源码目录
│   ├── src/main/antlr4/…/ManagementSql.g4   语法（唯一一份）
│   ├── src/main/scala/…                     Spark 层共享源码（唯一一份）
│   └── src/test/{scala,resources}           测试（唯一一份，两个版本各跑一遍）
├── lakehouse-rest-spark-common/             jar   REST 客户端（纯 Java + java.net.http）
├── lakehouse-rest-spark-3.5/                jar   Spark 3.5.9 / Scala 2.12.18 / ANTLR 4.9.3
└── lakehouse-rest-spark-4.1/                jar   Spark 4.1.2 / Scala 2.13.17 / ANTLR 4.13.1
```

| 模块 | 放什么 | 为什么不能放到别处 |
| --- | --- | --- |
| `-common` | `ManagementApiClient`、`ManagementApiException` | 唯一能真正共用一份 class 的部分：不碰 Spark / Scala / ANTLR |
| `-3.5` / `-4.1` | 版本属性、一个 `LakehouseRestSqlParser`、一个测试委托桩 | 其余全部来自 `shared/`；这两个类的差异就是 13.3 那两行 |
| `shared/` | 语法 + Scala 层 + 测试 | Scala 代码没法进 `-common`：2.12 编出的 class 在 2.13 的会话里加载不了 |

`shared/` 挂进编译靠 `build-helper-maven-plugin` 的三个 execution（main 源、test 源、
test 资源），路径写 `${project.basedir}/../shared/...`——相对各自的模块目录，比继承一个
属性更容易核对。语法则由 `antlr4-maven-plugin` 的 `<sourceDirectory>` 直接指过去，
**每个模块各自生成一份**：生成代码必须与本模块的 ANTLR 运行时同版本（见 13.1 第二行）。

### 13.3 版本差异只有两处

| 文件 | 3.5 | 4.1 |
| --- | --- | --- |
| `LakehouseRestSqlParser.scala` | 只接出两个构造方式 | 多 `override def parseRoutineParam` |
| `SparkParserDelegate.scala`（测试） | 空类体 | 多实现 `parseRoutineParam` |

**做法是「共享抽象基类 + 版本具体子类」。** 共享侧叫 `AbstractLakehouseRestSqlParser`，
实现两个版本**都有**的 7 个方法；具体子类在两个版本里同名（`LakehouseRestSqlParser`）且暴露
同样的两个构造方式，所以共享代码和测试照常 `new LakehouseRestSqlParser(...)`，不需要工厂或反射。
测试桩同理：`ParserDelegateStub`（抽象）在共享测试里，`SparkParserDelegate` 在各自模块里。

这一点是这次拆分里唯一有设计取舍的地方：另一条路是把整个 `LakehouseRestSqlParser`
复制成两份，代价是 100 行注释每次改动要写两遍，而它恰恰是最需要写清楚「为什么先试自己的
语法」的地方。抽象基类的代价是多一层继承，但漂移风险降到零。

### 13.4 构建与测试

```bash
# 两个版本各自全量（含共享测试）
mvn -o -pl :lakehouse-rest-spark-3.5 -am test
mvn -o -pl :lakehouse-rest-spark-4.1 -am test

# 整个 Spark 侧（common + 两版）：-f 指向聚合 POM 才会带上它的 modules
mvn -o -f lakehouse-rest-spark/pom.xml -DskipTests install
```

`-pl` 必须写**版本模块**的坐标；`-pl :lakehouse-rest-spark` 现在指向聚合 POM，
只会构建那个 POM 本身——既不编代码也不跑测试（`-pl` 选中的是「这个项目」，
不会顺着 `<modules>` 往下走；要连子模块一起构建就用上面的 `-f`）。
`-am` 也不能省：`-common` 是编译期依赖，不带 `-am` 时 Maven 会去 `~/.m2` 取上一次装的
`-common`（改了客户端却没重装时，症状是「改了没生效」）。

脚本与文档里所有 `-pl :lakehouse-rest-spark` 已同步成 `-3.5`（`scripts/e2e-spark-sql.sh`
与 `docs/spark-*-e2e.md`）；`scripts/verify-spark-sql-doc.py` 的三个源码路径也改到
`shared/` 与 `-common/`，它读不到文件会直接报错，不会静默放过。

### 13.5 实测

| 模块 | 用例 | 结果 |
| --- | --- | --- |
| `-3.5` | 107 | 0 失败 / 37 跳过（需要 e2e 参数的实时服务端用例） |
| `-4.1` | 107 | 0 失败 / 37 跳过（同上） |

同一份测试源码在两版上各跑一遍，是这次拆分顺带得到的收益：**「语法改一处、两边都验」**，
不需要靠人工记得在另一个版本上再跑一次。

### 13.6 4.1 侧的已知边界

1. **Paimon 还没有 Spark 4.1 的 bundle。** Paimon 2.0.0 只发布到 `paimon-spark-4.0_2.13`，
   4.1 模块用它（同属 Spark 4 的 API 面）。因此 4.1 上**凡是真建 SparkSession 的用例一例也跑不了**
   （`PaimonTableDdlTests`、`ManagementSqlLiveServerTests`、`LineageLiveServerTests` 等）：
   Paimon 的 Spark 扩展在装配会话状态时加载 `org.apache.spark.sql.paimon.shims.Spark4Shim` 失败
   （`java.util.ServiceConfigurationError: … Unable to get public no-arg constructor`），
   包上去的表现是 `IllegalArgumentException: Error while instantiating
   'org.apache.spark.sql.internal.SessionStateBuilder'`，栈里一个 Paimon 的类名都不在头顶，
   极具误导性。**这条一度写成「起真服务端就能跑」，实测不成立**——服务端起不起都在同一个地方炸。
   4.1 侧能验的只有「不建整会话」的那部分（抽取器单测、解析、对桩执行）。Paimon 发布 4.1 bundle
   后改 `-4.1/pom.xml` 的 `paimon.spark.artifact` 一行即可。
2. **JDK 上限照旧**：两个版本都只能在 JDK 17 / 21 上跑测试（JDK 24 移除了 Security
   Manager，Hadoop 的 `getSubject` 会失败），由 `SparkJdkRequirement` 在建会话前拦下。
3. **`spark-hive` 仍是测试期依赖**：Paimon 的 Spark 扩展初始化时会引用
   `HiveUDFExpressionBuilder`，集群上由发行版提供。

---

## 附：一页速览

```
命名分层       ：骨架中立 · 协议面带格式名 · k8s 资源名与 Spark 客户端键不动（§4）
模块           ：parent → spring-parent → { core · storage · protocol-paimon · protocol-iceberg · server }
                 parent → { spark-parent → { common · 3.5 · 4.1 } · optimizer }（§13）· flink 3.5 / 4.1（§14）
三条不变量     ：core 无格式依赖 · 两面互不依赖 · iceberg-core 只在 protocol-iceberg（enforcer 守，§8.3）
目录           ：四个模块嵌在 spring-parent/ 下（`-pl` 要写 :artifactId，§9）
Java 包        ：io.github.melin.lakehouse.rest；协议面各独占一层格式子包（§11）
                 拆包必查「同包直呼」的引用——它们不写 import，编译器才会告诉你（§11.1）
对外名字的判据 ：能不能改看「除了本仓库还有谁写它」（§4 / §10）
实测规模       ：core 117 · storage 9 · protocol-paimon 38 · server 46（共 210 个类）
实际落点的差异 ：见 §8.1（六处，均由搬迁前的依赖图算出）
```

---
## 14. Flink 侧的两个版本模块

**动机**：服务端要能被 Flink SQL 直接读写，而不是只有 Spark。Flink 的
`CatalogFactory` / Table 工厂按版本编译，1.20 与 2.x 的字节码不兼容——与 Spark 3.5 / 4.1
是同一类问题，解法也照搬。

**结构**：`lakehouse-rest-flink-parent`（pom）+ `-1.20` + `-2.3`，共用
`lakehouse-rest-flink/shared/src/{main,test}/java`。主源码**不 import 任何 Flink 类型**
（只拼 catalog 选项与 `CREATE CATALOG` 字符串），否则 shared 就必须选一个版本编译。

**与 Spark 模块的三处不同**：

1. 没有 `-common`：Flink 侧不需要自研 REST 客户端，连接器自己会讲 REST；
   管理 API 的调用只出现在测试里（`FlinkE2eSupport`），用 JDK HttpClient 直连。
2. 没有 SQL 语法扩展：Flink 的解析入口与 Spark 的 `ParserInterface` 不是一回事，
   复制一套只会多一处要跟着服务端词表改的地方。
3. 版本属性只在本模块内：`flink.version` / `paimon.flink.artifact` / `iceberg.flink.artifact`，
   不进根 POM——根 POM 的 `spark.version` 那组已经够多，再混进来分不清谁绑谁。

**版本取舍**：1.20 钉 1.20.1（Paimon 2.0.0 连接器就是按它编的）；2.3 用 2.3.0 +
Iceberg 1.12.0（`iceberg-flink-runtime-2.3` 从 1.12.0 才有）。Paimon 没有 2.3 构件，
2.2 的连接器实测在 2.3.0 上可用——这是实测结论，Paimon 发布 2.3 构件后应换回。

**验收**：`scripts/e2e-flink-sql.sh` 自起 h2 实例，两个版本各跑 Paimon（建主键表、
主键更新）与 Iceberg（建表、写入、读回），共 12 个用例。细节（两套选项键名、
`sequence.field` 的必要性、缺 Hadoop / connector / `add-opens` 的报错）见
[`flink-rest-catalog.md`](flink-rest-catalog.md)。
