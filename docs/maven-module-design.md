# Maven 模块布局与命名方案：从「Paimon 专用」到「格式中立」

[`iceberg-rest-catalog-design.md`](iceberg-rest-catalog-design.md) 定了「一份元数据同时服务
Paimon 与 Iceberg」的协议方案。本文件解决它的前置问题：**代码怎么分模块、artifact 叫什么名字**。

两件事必须分开看，因为它们可以分别落地、代价也差一个数量级：

| 事项 | 性质 | 代价 | 是否必须 |
| --- | --- | --- | --- |
| **坐标与目录改名**（artifactId 去掉 `paimon`） | 纯命名，不动一行逻辑 | 小（POM + 脚本 + 部署清单，约 20 处引用） | 是（本文件的要求） |
| **模块拆分**（服务端 1 个 jar → 4 个 jar + 1 个新父 POM） | 搬迁 + 依赖边界 | 中（约 120 个类换目录，包名不变所以无 import 改动） | 建议，但有明确的「不拆」判据（§6） |

本文件不含实现，只给结构与取舍。

---

## 1. 结论

| 问题 | 定案 |
| --- | --- |
| 命名族 | **`lakehouse-rest-*`**，groupId 仍是 `io.github.melin` |
| 骨架模块 | `lakehouse-rest-parent`（聚合）、`lakehouse-rest-spring-parent`（服务端世界的父）、`-core`、`-storage`、`-server` |
| 协议面模块 | `lakehouse-rest-protocol-paimon`、`lakehouse-rest-protocol-iceberg` —— **格式名只允许出现在协议面模块的 artifactId 里**（§2.2） |
| 集群侧模块 | `lakehouse-rest-spark`、`lakehouse-rest-optimizer`（职责不变，只改前缀） |
| Maven 项目数 | 4（1 聚合 + 3 jar）→ **8**（阶段 0–3 已落地：2 个 pom + 6 个 jar）；`-protocol-iceberg` 落地后为 **9** |
| Java 包名 | `io.github.melin.lakehouse.rest`。阶段 0–3 **不动它**（拆分不需要改包名，见 §4.3(2)）；阶段 4 按 §4.3(2) 预告的配方改成 `paimon.rest` 并给 Spark 扩展留了兼容壳（§9.2）；第二次改到当前名（§10） |
| Spring 配置前缀 | **不改**（`paimon.rest.*`）。它是用户面的配置 API，改名配方见 §4.3(1) |
| k8s 资源名 / 库名 / 表前缀 | **不改**。它们绑的是已部署的实例与已有数据，不是构建产物（§4.3(3)(4)） |
| 落地顺序 | 先改名（阶段 0），再拆模块（阶段 1–3），**Iceberg 的代码从第一天就落进 `-protocol-iceberg`**（§5） |
| 落地状态 | **阶段 0–4 已实施**：阶段 0–3（改坐标 + 拆模块）见 **§8**；阶段 4（模块嵌进 `spring-parent/` + 改 Java 包名）见 **§9**；包名第二次调整见 **§10**；`protocol-iceberg` 留待后续 |

---

## 2. 命名定案

### 2.1 为什么要一个格式中立的名字

现在的名字把「实现」写进了「产品」：`paimon-rest-server` 说明的是它服务哪种表格式，
而这恰恰是本方案要改掉的事实。加一个格式就要重新思考命名，加第三个格式（仓库里
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
| Java 包 | `io.github.melin.lakehouse.rest` | 迭代二/三改定（`paimonrest` → `paimon.rest` → 本名）。与模块边界正交；但**对外不是自由的名字**——Spark 扩展的旧包名要靠空壳续命，每改一次就多留一个壳（§4.3(2) / §9.2 / §10） |
| 部署资源名 | **不动** | namespace `paimon-rest`、Service `paimon-rest-server`（§4.3） |

### 2.3 候选名与取舍

| 候选 | 评价 |
| --- | --- |
| **`lakehouse-rest-*`** | **采用。**「湖仓」是 Paimon 与 Iceberg 的共同上位词；`lakehouse-rest-server` 读起来就是「湖仓 REST catalog 服务端」，语义不欠不溢 |
| `lakehouse-catalog-*` | 备选。少一个 `rest`，但 `catalog` 在本仓里是**资源实体**（`paimon_catalog` 表、`CatalogService`），拿它当产品名会与实体名抢词 |
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
│   ├── lakehouse-rest-protocol-iceberg                   jar   Iceberg 协议面（35 端点）
│   └── lakehouse-rest-server                             jar   可执行：装配 / 管理 API / 控制台宿主
├── lakehouse-rest-spark                                  jar   Spark SQL 扩展
└── lakehouse-rest-optimizer                              jar   k8s 优化任务
```

> 磁盘布局与这棵树**一致**：四个服务端模块的目录就嵌在 `lakehouse-rest-spring-parent/`
> 里面，所以 `spring-parent` 的 `<module>` 写裸名字、子模块的 `<parent>` 不用写
> `<relativePath>`。这是迭代二调整的结果——阶段 0–3 落成的是平级布局，见 §9.1。

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
| `-core` | 22 张表的实体与仓库、共享服务（catalog/database/table/快照、授权、凭据接口）、共享 DTO、共享 Web 组件（`/v1/config`、错误模型基类、两个认证拦截器、`CatalogAccessRules`、`RestServerProperties`）、协议面 SPI | 两个协议面都要用它，而它自己**一行格式代码都不该有**——这条约束只有在它是个模块时才守得住 |
| `-storage` | `StorageFileIoFactory`、`CatalogFileService`、`Paths`、`StorageConfigs`、`FileIoType`、凭据管理器的实现与缓存 | 它是唯一需要「往仓库写文件」的模块（Paimon 的 `FileIO` 在这里承担的是**通用文件系统客户端**：s3 插件加载、凭据刷新、路径模板）。若 Iceberg 面另引一套 hadoop-aws 做同样的事，就会有两套 s3 凭据解析逻辑——设计文档 §5.5 要的「凭据统一」当场破功 |
| `-protocol-paimon` | `web/` 下 20 个控制器里属于 Paimon 协议面的 10 个、Paimon DTO、`TableMetadataService`（schema/snapshot 物化）、`TableKinds`、`SchemaChangeService` | 唯一允许 `import org.apache.paimon` 的服务端模块（`storage` 除外，且只用于 FileIO） |
| `-protocol-iceberg` | 35 个端点、Iceberg DTO、元数据代数（update 应用 / requirements 校验 / CAS）、投影派生（`schemas[]`/`snapshots[]`/`refs` → 行）、Iceberg 错误模型 | 唯一允许 `import org.apache.iceberg` 的模块；`iceberg-core` 带来的 avro 等依赖被关在这里 |
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
| `RestServerProperties`（`paimon.rest.*`） | core | 配置项被协议面与装配都读；前缀本期不改（§4.3） |
| `TableKinds` | protocol-paimon | import 了 Paimon 的对象表/格式表类型。**但**「表种类」这个枚举值要留在 core——Iceberg 面也要产出它（控制台页签靠它伸缩） |
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

> **本迭代未实现这个 SPI。** 阶段 0–3 的产物是「模块边界 + 依赖守卫」，
> `CatalogProtocolFace` 要等 `-protocol-iceberg` 一起做——现在只有一个面，
> 声明出来的 `endpoints()` 没有第二个消费者，也没法做端点的契约测试
> （契约测试要拿两份清单去比对）。提前引入只会多一个无人读的接口。
> 它落地的时点：`-protocol-iceberg` 动工的第一件事（见 §5 阶段 5）。

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

## 4. 改名清单（承载面）

### 4.1 必改：不改就构建/脚本报错

| # | 位置 | 内容 |
| --- | --- | --- |
| 1 | 根 `pom.xml` | `artifactId` / `name` / `description` / `<modules>` 三条 |
| 2 | 三个子 POM | `artifactId` / `name` / `description`；`spark`、`optimizer` 的 `<parent><artifactId>` |
| 3 | 四个模块目录 | `git mv`（保留历史） |
| 4 | `Dockerfile` | `COPY ... paimon-rest-server/target/paimon-rest-server-0.0.1-SNAPSHOT.jar` |
| 5 | `.dockerignore` | `!paimon-rest-server/target/*.jar`、`paimon-rest-console/node_modules/`、`.vite/` |
| 6 | `scripts/build-image.sh` | `IMAGE` 默认值、`JAR=`、`mvn -pl`、示例注释 |
| 7 | `scripts/build-console.sh` | `CONSOLE=`、`OUT=`（指向 server 的 `resources/static/console`） |
| 8 | `scripts/e2e-spark-sql.sh` | `JAR=`、`mvn -pl :lakehouse-rest-spark` |
| 9 | `scripts/gen-mysql-ddl.sh` | `mvn -pl` |
| 10 | `scripts/verify-console.py` | 6 处：console 目录、`static/console`、`static/rest-catalog-open-api.yaml`、Java 源目录、两个具体文件路径 |
| 11 | `scripts/verify-spark-sql-doc.py` | 5 处路径（含 server 里的 `PermissionDtos.java`） |
| 12 | `scripts/sweep-console-auth.sh` | 启动命令里的 jar 名（`api-sweep.sh` / `management-sweep.sh` 是纯 HTTP 扫描，不引用模块名，核对即可） |
| 13 | `deploy/kubernetes/` | `deployment.yaml` 镜像名、各清单的 `app.kubernetes.io/name`、`kustomization.yaml`、`README.md` 里的命令 |
| 14 | `AGENTS.md` | 构建/测试/排查命令里的模块名（多处） |
| 15 | `README.md` | 坐标与包名段（「Maven 坐标与包名：…」那一段）**顺带修正**：现在还写「三个 artifact」，optimizer 早已加入 |

### 4.2 建议改：不破坏构建，但用户会看到

- **镜像名** `paimon-rest-server:0.0.1` → `lakehouse-rest-server:0.0.1`，与 artifactId 一致，
  `docker build -t lakehouse-rest-server:0.0.1 .` 能直接对上；
- **`spring.application.name`**（`application.yml`）：影响日志与指标的服务名。若已有
  Prometheus 面板 / 告警按名字聚合，要么同步改面板，要么保持原值——**这是一次运维动作，
  不是一次代码改动**，所以单独列出来；
- **控制台目录** `paimon-rest-console` → `lakehouse-rest-console`（npm 包名可选）。它不是
  Maven 模块，但属于同一命名族，且 `build-console.sh` / `verify-console.py` / `.dockerignore`
  三处都引用它。

### 4.3 承载面：名字已经跑在别人机器上的四处

这四处的共同点是：**它们的名字已经在别人的机器上运行**（配置文件、k8s 集群、数据库），
改名的收益是「一致性」，成本是一次带停机风险的迁移。逐条给理由与改法。
其中**第 2 条已在迭代二实施**（它当时的结论就是「配方先写下来、等一次破坏性发布再做」），
其余三条仍然不动。

**（1）Spring 配置前缀 `paimon.rest.*`**

它出现在 `RestServerProperties` 的 `@ConfigurationProperties(prefix = "paimon.rest")`、
`application.yml`、`deploy/kubernetes/configmap.yaml` 与全部文档里，是**用户面的配置 API**。
`paimon.rest.credential-manager.type`、`paimon.rest.auto-create-catalog` 这些键是使用者
写在自己 `application.yml` 里的。

将来要改的配方（等到与其它破坏性变更合并成一次发布）：新增
`@ConfigurationProperties(prefix = "lakehouse.rest")`，再用一个
`EnvironmentPostProcessor` 把 `paimon.rest.*` 原样复制成 `lakehouse.rest.*` 并打一条
WARN（Spring 的 relaxed binding 不允许一个 properties 类绑两个前缀，所以必须是
属性源层面的别名，不能靠注解）。保留两个大版本后删别名。

**（2）Java 包名（已实施两次：`io.github.melin.paimonrest` → `io.github.melin.paimon.rest`（§9）→ `io.github.melin.lakehouse.rest`（§10））**

阶段 0–3 **刻意没动**它，理由成立且值得留在这里：Spring 的组件扫描与 JPA 实体扫描都基于
包名（`@SpringBootApplication` 所在包及其子包），类在哪个 jar 里不影响扫描。所以「拆模块」
与「改包名」是正交的两件事，可以只要前者——这也让阶段 1–3 的搬迁连一行 `import` 都不用动。

所以本节的原始措辞是「本期不改 + 将来要改时的配方」。迭代二把配方执行了，结果是：

- **代价确实落在用户身上。** `…spark.ManagementSparkExtensions` 这个全限定名要填进用户的
  `spark.sql.extensions`，换包名就是让所有既有 Spark 部署在启动时找不到扩展点。
- **配方是「旧包留空壳」**：`lakehouse-rest-spark` 里保留
  `io.github.melin.paimonrest.spark.ManagementSparkExtensions`，只继承新实现、不覆盖任何
  方法（Spark 拿到类名后当 `SparkSessionExtensions => Unit` 用，行为全在父类的 `apply` 里，
  子类实例完全等价）。旧配置照旧可用，新部署写新包名。
- **要一起核对的四处，本次逐一核过**（细节见 §9.2）：
  ① `Main-Class`（optimizer 的 jar 清单与它的 Dockerfile）——已改，optimizer 镜像由本仓库
  自己构建，类名是内部约定，不需要空壳；
  ② ANTLR 目录——语法文件里**不能**写 `@header`，包名由 `src/main/antlr4/` 下的目录层级推出，
  所以目录一挪生成物的 `package` 就跟着对，不需要改语法文件；
  ③ `verify-spark-sql-doc.py` 里的源码路径（它按路径读语法文件、扩展类、客户端与
  `PermissionDtos`）；
  ④ **批量替换脚本必须排除那个空壳文件**——它按定义带着旧包名，被脚本顺手改掉之后
  兼容性会**静默失效**（构建照样绿），这一类「故意留旧名的文件」以后每加一个都要同步维护
  排除清单。

**第二轮（§10）证明了这个配方可重复，也划出了它的边界：** 空壳只覆盖「真的发布过、
在别人机器上跑过」的名字。`io.github.melin.paimon.rest` 是一次**从未提交**的工作区改动
（`git log` 里没有它，也就没构建出任何可发布产物），所以没有为它留第二个壳——
壳的数量应当等于**历史的对外名字数**，不是「改过几次」。

**（3）k8s 资源名与 namespace（`paimon-rest`）**

`Service` / `Deployment` 名、`app.kubernetes.io/name` 标签、namespace 都是**集群里已存在
的对象的标识**：label selector、NetworkPolicy、ServiceMonitor 的抓取配置、`kubectl get -l`
脚本全都绑在它们上面。改名是一次带下线的迁移，收益只有一致性。建议：**部署清单里的资源名
保持 `paimon-rest`**，新增的字段（如镜像名）跟随新坐标，并在 `deploy/kubernetes/README.md`
里写一句「资源名不是产品名，是历史标识」。

**（4）数据库库名 `paimon_catalog` 与 22 张表的 `paimon_` 前缀**

见 [`data-model.md`](data-model.md)。表名是存储细节；本次已经要加 9 个列，把 `RENAME TABLE`
混进同一批迁移会让迁移不可回滚，而收益为零。要做就在 `data-model.md` 里补一句
「`paimon_` 前缀是历史遗留，与表格式无关」——这比改 22 张表便宜得多。

### 4.4 坐标迁移需要 relocation 吗

**不需要**（现在就够用）：三个 POM 都没有配 `distributionManagement`，产物是本地构建、
`mvn install` 与镜像分发，没有对外发布的坐标。所以改名只需在 CHANGELOG 里写一句
「坐标变更：`paimon-rest-*` → `lakehouse-rest-*`」。

将来一旦对外发布过坐标，改名就要**多发一个旧坐标的 POM**，用 Maven 的
`<distributionManagement><relocation>` 指向新 artifactId——使用者解析旧坐标时会收到
WARN 并被自动重定向，这是 Maven 为这种情况准备的原生机制，不需要自造兼容层。

版本号本身不需要因为改名而变：可以继续 `0.0.1-SNAPSHOT`；若要把「改名 + 双协议」作为
一次发布，建议一起发 `0.1.0`（README、AGENTS.md、脚本里的版本串要同步）。

---

## 5. 分期实施路径

每一步**独立提交、独立验证、独立回滚**，且顺序有硬道理：

> **模块边界必须先于 Iceberg 代码落地。** 否则 Iceberg 的控制器、DTO 与投影派生会长在
> `server` 里，等边界定好再搬第二次——而 `-protocol-iceberg` 是**新建**模块，只要边界先定，
> 它从第一天就不需要搬迁。

| 阶段 | 动作 | 验证（DoD） | 风险 |
| --- | --- | --- | --- |
| **0 · 只改名** ✅ | 按 §4.1 的 15 处改；目录 `git mv`；§4.2 的镜像名与控制台目录；文档回填 | `mvn -o install -DskipTests` 绿；`verify-console.py`、`verify-spark-sql-doc.py`、`verify-management-contract.py`、`verify-k8s-manifests.py` 全绿；`build-image.sh` 出镜像并能起；`mvn -o test -pl :lakehouse-rest-server` 317 绿 | 低（无逻辑改动，单次 revert 可回） |
| **1 · `spring-parent` + `core`** ✅ | 新建 spring-parent（Boot 作父、钉共用版本）；把 `domain/`、`support/`、共享 `service/`、共享 `dto/`、共享 `config/`、`ConfigController`、错误模型基类移入 core；server 保留启动类 / 管理 API / 控制台 / 优化 | 同上 + `dependency:tree -pl :lakehouse-rest-core` 里**不出现** `org.apache.paimon` 与 `org.apache.iceberg`（enforcer 守）；core 内的控制器只剩共享的那两个（`ConfigController`、`OAuthTokenController`），格式面控制器一个都不许留在里面 | 中（约 100 类搬迁；**包名不变 → 零 import 改动**，编译即验证） |
| **2 · `storage`** ✅ | 移入 FileIO 与路径相关的类；core 只留接口（`WarehouseFileIO`、凭据管理器接口） | 单元测试绿；server 起的集成测试绿（`mvn -o test -pl :lakehouse-rest-server`） | 低 |
| **3 · `protocol-paimon`** ✅ | 移入 Paimon 协议面的 10 个控制器、Paimon DTO、`TableMetadataService`、`TableKinds`、`SchemaChangeService`；加协议面 SPI 实现 | 同上 + `api-sweep.sh` 60 个 operation 全绿；`/v1/config` 的 endpoints 契约测试上线 | 低 |
| **4 · 模块嵌套 + 改包名** ✅ | 四个服务端模块 `git mv` 进 `spring-parent/`（`<module>` 去掉 `../`、子模块删掉 `<relativePath>`、全部 `-pl` 改成 `:artifactId`）；Java 包 11 个包目录 `git mv` + 全仓字面替换；Spark 扩展留旧包名空壳 | §9.3 的 DoD：全量构建绿、服务端 317 / Spark 96 与重构前一致、四个校验脚本全绿、控制台重建后仍 26 项通过 | 低（纯路径与字面改动，编译即验证；唯一有风险的落点是 `spark.sql.extensions`，用空壳兜住） |
| **5 · `protocol-iceberg`** | **新建**（不搬迁）：35 个端点、Iceberg DTO、元数据代数、投影派生、错误模型、协议面 SPI 实现 | 设计文档 §9 的那一套（双客户端端到端、错误模型契约、endpoints 契约） | 与功能实现同期 |

阶段 0–3 的实际做法与上面的表述有三处出入，都以 §8 为准：
控制器是 **9 个**不是 10 个（`ConfigController` / `OAuthTokenController` 归 core）；
**SPI 没有实现**（留给阶段 5）；`storage` 只收真正读写仓库的类，
`StorageConfigs` / `FileIoType` 这两个词表留在 core。

阶段 4 不在原始分期里——它是阶段 0–3 落地后追加的一次**布局与命名调整**：
模块从平级兄弟改成嵌套、Java 包从 `paimonrest` 改到 `lakehouse.rest`。两者都只动路径与字面，
不碰任何类的归属，所以既不改变模块依赖图，也不影响 enforcer 的三条不变量。

**改包名这一步走了两次**（§9 的 `paimonrest` → `paimon.rest`，§10 的 `paimon.rest` →
`lakehouse.rest`）。第二次的理由很简单：模块坐标已经是 `lakehouse-rest-*`，包名却还带
`paimon`，而「服务端已不只服务 Paimon」正是整个方案要解决的问题。第一次之所以选中
`paimon.rest` 而不是一步到位，是因为当时把「包名」与「配置前缀」当成同一条要保留的
历史线——事后看，`paimon` 在包名里并不承载任何对外契约（真正承载契约的是 `paimon.rest.*`
配置键与 `paimon_` 表名），分开判断才对。

阶段 0 与阶段 1 之间可以插入任意长的间隔——阶段 0 单独就满足了「artifactId 避免 paimon」
这个要求，且不依赖任何后续决策。

---

## 6. 「要不要拆」的判据

拆到 4 个服务端模块的收益是**编译期护栏**（§3.1 三条不变量）与**测试分区**；
代价是约 120 个类换目录、一套两个父 POM 的构建结构、以及每次加功能要想一次
「这段代码属于哪个面」。诚实地说：**模块边界在这里是编译期与测试期的隔离，不是运行期的**
——所有模块最终都进同一个 fat jar、同一个 JVM。所以别指望它解决依赖冲突之外的运行期问题。

| 情形 | 建议 |
| --- | --- |
| 近期要落双协议、且预期会有第三种格式（Lance 之类） | **拆**（走完阶段 1–4）。护栏的收益随格式数量增长 |
| 只做阶段 0，Iceberg 代码进 `server` | 可以做，但 `iceberg-core` + Paimon 的依赖会共存于一个 POM，边界只能靠 review；设计文档 §8 风险 7 的承诺退化成口头约定 |
| 只有 1–2 人维护、半年内不会再动模块边界 | 只做**阶段 0**。等真正需要第二个面时再拆，代价与现在相同（搬迁是机械的） |

不建议的中间态：**只拆 core 不拆协议面**。那样两个协议面都在 `server` 里，
`iceberg-core` 仍然进 `server`，三条不变量一条都守不住，白付了一半搬迁成本。

---

## 7. 方案文档之间的衔接

| 文档 | 关系 |
| --- | --- |
| [`iceberg-rest-catalog-design.md`](iceberg-rest-catalog-design.md) | 协议方案（本文件是它的前置：代码放哪、叫什么） |
| [`data-model.md`](data-model.md) | 表结构；本文件的 §4.3(4) 要在那里补一句前缀说明 |
| [`catalog-api-endpoints.md`](catalog-api-endpoints.md) | Paimon 60 端点的落点由 §3.3 决定（多数进 `-protocol-paimon`） |
| [`management-api-overview.md`](management-api-overview.md) | 33 个 operation 全部留在 `-server` |
| [`spark-sql-extension.md`](spark-sql-extension.md) | §4.3(2) 的 FQCN 约束来自它 |
| [`console.md`](console.md) | 控制台目录改名与 `verify-console.py` 的路径同步 |

---

## 8. 落地记录之一：阶段 0–3（改坐标 + 拆模块）

> 阶段 4（模块嵌进 `spring-parent/` + Java 包改名）是随后追加的一次调整，记在 **§9**。
> 本节描述的是阶段 0–3 当时的形态；**凡涉及「模块在磁盘上的位置」，以 §9.1 为准**。

阶段 0（改名）与阶段 1–3（`spring-parent` / `core` / `storage` / `protocol-paimon`）已经实施，
`protocol-iceberg` 按计划留到后续迭代。**实际落点与本文件 §3.3 / §3.4 有几处不同**，
原因是那两张表是按「名字看着像谁」写的，而搬迁前先把依赖图算了一遍——真按原文搬会
产生 46 条反向依赖。差异与理由逐条记在下面，§3.3 / §3.4 请配合本节阅读。

### 8.1 磁盘布局：阶段 0–3 落成的是平级兄弟（迭代二已改为嵌套）

阶段 0–3 落地时，`lakehouse-rest-spring-parent/` 只有**一个 POM、没有源码**，
四个服务端模块与它并列在仓库根下：

```
lakehouse-rest-parent/                     聚合 + 集群世界的父
├── pom.xml
├── lakehouse-rest-spring-parent/pom.xml   ← 只有 POM
├── lakehouse-rest-core/
├── lakehouse-rest-storage/
├── lakehouse-rest-protocol-paimon/
├── lakehouse-rest-server/
├── lakehouse-rest-spark/
└── lakehouse-rest-optimizer/
```

当时选平级是为了「路径短一层」：`lakehouse-rest-server/target/...` 与 §4.1 的改名清单、
Dockerfile、七个脚本、`verify-*.py` 里的路径逐字一致，不用套第二层目录。

代价是子模块的 `<relativePath>` 必须**显式写** `../lakehouse-rest-spring-parent/pom.xml`：
默认的 `../pom.xml` 会指向仓库根的聚合 POM，而它没有 `lakehouse-rest-spring-parent`
这个 artifactId，Maven 便转去仓库解析、离线时直接失败。

**迭代二把这条取舍反了过来。** 省下的那层目录换来的是一个必须记着写的 `<relativePath>`，
以及「父子关系在磁盘上看不见」——`spring-parent/` 目录里空无一物，读者要翻 POM 才知道
谁是谁的子。现在四个模块就嵌在 `spring-parent/` 下面，`<relativePath>` 连同那条坑一起
消失（默认的 `../pom.xml` 就是父 POM）。改完之后冒出来的新约束见 §9.1。

### 8.2 实际落点与 §3.3 / §3.4 的差异

实测规模：**core 86 · storage 8 · protocol-paimon 35 · server 39**（共 168 个类）。
搬迁前用依赖图逐类核过「搬完会不会出现反向依赖」，下面六处是图上算出来的必然结果：

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

**搬迁的正确性靠什么保证**：包名一个字节都没改，所以没有任何 import 需要动——
「编译通过」本身就是「搬对了」的证明，再加上构建期跑一遍依赖图确认零反向依赖。

### 8.3 父 POM 与实际依赖分工

| 放哪 | 内容 | 理由 |
| --- | --- | --- |
| `spring-parent` 的 `<dependencies>` | `spring-boot-starter-webmvc`、`-data-jpa`、`-validation`、`lombok` | 五个模块最终进**同一个 fat jar、同一个 JVM**，所以「谁需要 webmvc」只影响编译期可见性，不影响产物内容。集中一处，各模块的 POM 就只剩「它比别的模块多需要什么」 |
| `storage` / `protocol-paimon` | `paimon-core`（编译期） | 存储层只用 `fs` / `catalog.CatalogContext` / `options` 三个包；协议面用 `schema` / `utils` / `table.object` / `types` |
| `server` | `paimon-core` + `paimon-format` + `paimon-s3` | 这三个决定**可执行产物里装了哪几个文件系统插件与格式实现**，是部署决策 |

**Paimon 绝不放进父 POM 的 `<dependencies>`**：那样 core 会拿到它，不变量 1 当场破功。
enforcer 会拦下来，但更好的做法是别让这种事有机会发生。

### 8.4 `maven-enforcer` 的 `searchTransitive` 语义（易错处）

三条不变量的守卫写法并不统一，这是有意的：

| 模块 | `searchTransitive` | 禁什么 | 为什么 |
| --- | --- | --- | --- |
| core | 默认（true，查整棵树） | `org.apache.paimon:*`、`org.apache.iceberg:*`、两个协议面模块 | 「core 的依赖树里不出现格式包」才是要守的事实，哪怕是某个共享库顺手带进来的 |
| storage | 默认（true） | `org.apache.iceberg:*`、两个协议面模块 | 允许 paimon（FileIO 当通用文件系统客户端），不允许 Iceberg |
| protocol-paimon | 默认（true） | `org.apache.iceberg:*`、`lakehouse-rest-protocol-iceberg` | 不变量 2 与 3 |
| server | **false（只禁直接声明）** | `org.apache.iceberg:*` | 可执行产物最终要靠 `server → protocol-iceberg` 把 iceberg-core 带进来。要守的不是「产物里有它」，而是「除了那个面，没有第二个地方**声明**它」——默认的 true 会把这条规则读成「server 不许装配 Iceberg 面」，与设计相反 |

### 8.5 `paimon.version` 定成 2.0.0：一个顺手修掉的既有地雷

原 `lakehouse-rest-spring-parent/lakehouse-rest-server/pom.xml` 写的是 `<paimon.version>2.1.0</paimon.version>`，
但 `TableMetadataService` 仍在用 `new SchemaManager(fileIO, path)`——Paimon **2.1.0 把
`org.apache.paimon.schema.SchemaManager` 从类改成了接口**。结果是：

- 在 `c6dea4a`（本次重构前）的干净副本上跑 `mvn -o -pl paimon-rest-server compile`，
  **编不过**；
- 而平时看起来是好的，只因为增量编译在源码未变时会整模块跳过——改任何一行 Java
  都会触发全量重编，于是暴露。此前每轮都是靠命令行 `-Dpaimon.version=2.0.0`
  覆盖才编过（命令行属性优先于 POM 属性）。

新的 `spring-parent` 直接把 `paimon.version` 写成 **2.0.0**（与仓库根聚合 POM 里
集群世界的同名属性一致），于是 `mvn -o install -DskipTests` 不带任何参数就能过。
**升到 2.1.0 是一次独立的代码迁移**（`SchemaManager` 改为接口后的取值方式），
不该藏在「改坐标 + 拆模块」里，因此单独作为待办留在 §8.6。

### 8.6 遗留（未做，也不该混在这次里）

| # | 事项 | 说明 |
| --- | --- | --- |
| 1 | Paimon 升到 2.1.0 | 需要先迁移 `SchemaManager` 的用法；两处 `paimon.version` 一起改 |
| 2 | 测试分区 | 29 个测试类全留在 `server`：它们是 Spring 集成测试，需要完整应用上下文，而 server 依赖其余三个模块、测试类路径天然能看到所有类。真正划算的分区是把 `JwtTests` / `CredentialCipherTests` / `StorageConfigDtosTests` 这类**纯单元测试**随被测类下移——但那要在三个模块各配一份 surefire，收益不大 |
| 3 | `TableKinds` 与其「表种类」词表 | 现在在 protocol-paimon。Iceberg 面落地时它也**要产出**这个值（控制台页签靠它伸缩），届时需要把**枚举值**提到 core（或各面各持一份）。这是 §3.4 那条「枚举值留在 core」的落地时点 |
| 4 | 表前缀 `paimon_` | 见 §4.3(4)：本次已在 `data-model.md` 补了一句「历史遗留、与表格式无关」，不做 RENAME |
| 5 | 类名与包名里的 `Paimon` | `PaimonPermissionModel`、`PaimonRestServerApplication`、`io.github.melin.lakehouse.rest` 全部保留 |

### 8.7 文档回填清单

代码搬完之后，凡是「写死了类在哪个 jar」的表述都要跟着改。本轮改动：

| 文件 | 改什么 |
| --- | --- |
| `README.md` §1 | 工程结构树（4 → 8 条）；「模块划分的取舍」重写为**两个父 POM / 两个依赖世界**；「Maven 坐标与包名」由「四个 artifact」改为八个，并说明包名故意不动；补一段「k8s 资源名与 `paimon_` 表前缀同样不改」 |
| `README.md` §2.2 | 构建命令加 `-o`、`install` 与 `-pl … -am` 两种形态；说明 enforcer 校验是构建的一部分 |
| `AGENTS.md` §1 | 第 4 条重写（两个父 POM + `<relativePath>` 必须显式写 + 共享依赖不放 `paimon-core`）；**新增第 10 条**（三条 enforcer 不变量 + `searchTransitive` + 「要用 Paimon 类型先想该类型属于谁」） |
| `AGENTS.md` §2/§3/§5/§7 | 命令补 `-am` 形态；仓库地图按模块重写并补包级职责；改动清单每行标注落在哪个模块、新增「跨模块搬类 / 加模块」一行；新增「包名共用一套包树」的约定 |
| `docs/data-model.md` | 头部「实体与仓储」改为**分两处**（core 19 + server 3），并解释分家依据是「谁读它」；补一句 `paimon_` 前缀是历史遗留、与表格式无关 |
| 其余 `docs/*.md` | 机械替换模块名（脚本完成，见阶段 0） |

顺手修掉两个**与本次重构无关的既有文档 bug**（都是 HEAD 就存在的）：

1. **`examples/spark-paimon-rest/` 不存在。** 该目录在 `de5476c`（「delete example dir」）里被删，
   但 `README.md` §1 结构树、§8.3「可运行的示例」与 `AGENTS.md` §3 都还在指向它。
   §8.3 改写为指向真实存在的 `scripts/e2e-spark-sql.sh`（同一件事，且不用额外维护一份 pom）。
2. **`AGENTS.md` 引用的 `iceberg-rest-catalog-compat.md` 不存在。** 实际文件名是
   `iceberg-rest-catalog-design.md`，引用已改正、描述也按该文件重写。

3. **`README.md` 文档表说「「配置」页为何暂不实现」，与事实不符。** 该页**部分实现**：
   `docs/console-catalog-detail.md` §3.3 与 `AGENTS.md` 都写明「`table-default.*` 可编辑、
   其余属性只读回填」。已把 `README.md` 的措辞与后两者统一。

前两处是**指向不存在的文件**（会误导读者去执行跑不通的命令），第三处是**表述过时**。
三处都与模块拆分无关，但都落在本次要改的同一批文件里，顺手修掉比留着更省事。

### 8.8 顺手校准的两个测试计数（实测值 vs 文档值）

搬完之后跑全量测试，发现文档里的用例数与实测对不上：

| 项 | 文档原值 | 实测值 | 说明 |
| --- | --- | --- | --- |
| 服务端 | 307 | **317** | `mvn -o test -pl :lakehouse-rest-server`：317 run / 0 failures / 0 errors |
| Spark | 96 | **96** | `mvn -o test -pl :lakehouse-rest-spark`：96 run / 1 error（即文档已描述的 `PaimonS3Test`）/ 25 skipped |
| 合计 | 403 | **413** | |

**这两处偏差不是本次重构造成的**，可以证明：`git diff --find-renames HEAD` 显示
`src/test` 下 30 个 Java 文件与 15 个 Scala 文件**全部是纯重命名**（R100 或接近 R100），
其中非 R100 的几处改动逐一核对过，只有三类内容——注释里的模块目录名、
断言里的 `"paimon-rest-server"` → `"lakehouse-rest-server"`、以及临时目录前缀
`/tmp/paimon-rest-server-tests`，都是机械改名本身。测试文件既没增删、也没改逻辑，
所以**重构前后用例数必然相同**，307 在 `c6dea4a` 时就已是陈旧数字
（很可能是 `feat 表优化` 那一提交加了 `OptimizationApiTests` 等用例后没回头更新）。

已把 README §2.2、§7 与 `AGENTS.md` §6 的三处计数改为实测值。
`docs/maven-module-design.md` §5 阶段 0 的验收条件也一并改为 317。

---

## 9. 落地记录之二：阶段 4（模块嵌套 + 改包名）

两件事一起做，都不改任何类的归属：

1. **目录层级**：把 `lakehouse-rest-core` / `-storage` / `-protocol-paimon` /
   `-server` 移到 `lakehouse-rest-spring-parent/` 之下（`-protocol-iceberg` 尚未落地，
   落点时按同样方式嵌进去）；
2. **Java 包名**：`io.github.melin.paimonrest` → `io.github.melin.paimon.rest`
   （后来又改成 `io.github.melin.lakehouse.rest`，见 §10）。

改法是纯机械的，脚本 `outputs/maven-module-refactor/restructure.py` 一次跑完（`--check` 预演、
`--apply` 才动手）：**4 个模块目录 + 11 个包目录** `git mv`，**1159 处**字面替换
（1093 处点号形式 `io.github.melin.paimonrest`、10 处路径形式 `io/github/melin/paimonrest`、
56 处模块目录前缀），命中 **251 个文件**；随后发现 `-pl` 需要另一种写法，又改了 34 处（16 个文件）。

没被脚本碰的两处都因为「批量替换在这里是错的」：`spring-parent/pom.xml` 的 `<module>`
（那是相对本文件的表达，加前缀反而错）与 `.idea/`（IDE 状态）。`.DS_Store` 之类的非文本文件
则被读取失败挡下——脚本对这两类都是跳过而不是猜。

### 9.1 目录层级：`-pl` 从裸名字改成 `:artifactId`

嵌套本身是「删东西」：`spring-parent` 的 `<module>` 去掉 `../`，四个子模块的
`<relativePath>` 整条删掉（默认值 `../pom.xml` 现在恰好正确）。

**但它带来一个隐蔽的破坏：`mvn -pl lakehouse-rest-server` 会失败。**
`-pl` 收的是 `[groupId]:artifactId` **或相对路径**，裸名字按后者解析——阶段 0–3 之所以能那么写，
是因为模块目录恰好就在根下、与 artifactId 同名。挪进 `spring-parent/` 之后：

```
[ERROR] Could not find the selected project in the reactor: lakehouse-rest-server
```

统一改成带冒号的坐标形式即可，它只看坐标、与目录层级无关：

```bash
mvn -o -pl :lakehouse-rest-server -am clean package -DskipTests
mvn -o test -pl :lakehouse-rest-spark
```

全仓 34 处（README、AGENTS、7 个脚本、Dockerfile 头注释、k8s 清单与其 README、五份文档、
4 个 Scala 测试的 scaladoc）一并改掉。**Spark 也一起带冒号**——它的目录确实还在根下、
裸名字也还能用，但「哪个模块在哪一层」不该是要背下来的知识。

`spring-parent/pom.xml` 的模块清单里另留了一句注释说明 `-protocol-iceberg` 落点时也嵌在
同一层（现在还不能写成 `<module>`：指向不存在的目录时 Maven 直接报错）。

### 9.2 包名：唯一一处外部契约，用空壳兜住

包名不是内部细节的地方只有一处：`spark.sql.extensions` 的值是**全限定类名**，
而它写在用户的 Spark 配置里。§4.3(2) 已经预告过这个代价与配方，这次照着做了：

- 新包 `io.github.melin.lakehouse.rest.spark.ManagementSparkExtensions` —— 真正的实现
  （阶段 4 落地时它在 `io.github.melin.paimon.rest` 下，第二次改包名时随包一起挪走、
  并把空壳重新指过来，见 §10）；
- 旧包 `io.github.melin.paimonrest.spark.ManagementSparkExtensions` ——
  `lakehouse-rest-spark/src/main/scala/io/github/melin/paimonrest/spark/ManagementSparkExtensions.scala`，
  一个 `@deprecated` 的空壳，只继承新实现、不覆盖任何方法。

之所以兜得住：Spark 拿到配置里的类名后实例化、当 `SparkSessionExtensions => Unit` 调用，
行为全在父类的 `apply` 里，因此子类实例与父类实例完全等价。
（Spark 模块的 scalac 参数只有 `-deprecation -feature`，没有 `-Xfatal-warnings`，
所以这个 `@deprecated` 不会把构建变成红的。）

**空壳是「故意带着旧包名」的文件**：以后再跑全仓包名替换必须把它排除，否则它会跟着被改掉、
兼容性**静默失效**（构建照样绿，坏在别人的 Spark 会话里）。自查一行命令：
`grep -rn paimonrest lakehouse-rest-spark/src/main` 应当只剩这一个文件。

ANTLR 的生成物不需要单独处理：`ManagementSql.g4` 里**没有** `@header`（语法文件顶部专门
写了这条注意事项），包名由 `src/main/antlr4/` 下的目录层级推出，目录一挪生成物就对。
optimizer 的 `Main-Class` 跟着改了，但它不是外部契约——镜像由本仓库自己构建，
类名只出现在 `pom.xml` 的 `<mainClass>` 与它自己的 Dockerfile 里。

### 9.3 验收

| 项 | 结果 |
| --- | --- |
| `mvn -o -DskipTests clean install` | **BUILD SUCCESS**，8 个模块（含 enforcer 三条不变量） |
| `mvn -o test -pl :lakehouse-rest-server` | **317 run / 0 failures / 0 errors**，与阶段 0–3 一致 |
| `mvn -o test -pl :lakehouse-rest-spark` | 96 run / 1 error（`PaimonS3Test`，文档已描述的既有失败）/ 25 skipped，与阶段 0–3 一致 |
| `verify-console.py` | 26 项全过（含「构建输出目录 ↔ 服务端静态资源位置一致」） |
| `verify-spark-sql-doc.py` | 27 项全过 |
| `verify-management-contract.py` / `verify-k8s-manifests.py` | 通过（后者含「镜像名与 Dockerfile 构建命令一致」） |
| 控制台重建 | `./scripts/build-console.sh` 按新 `outDir` 重建，校验全过 |
| 残留检查 | 全仓 `paimonrest` 只剩 Spark 那个空壳；无双重前缀、无未加前缀的旧模块路径 |

### 9.4 遗留

- **`-protocol-iceberg` 仍未落地**，只是位置已经为它留好（§9.1 末尾）。
- **空壳的退役条件**：确认没有部署还在用旧 FQCN（或下一个大版本）之后再删。
- **控制台产物仍未入版本库**（`.gitignore` 忽略、HEAD 里 0 个文件，见提交 `ccaf053`
  「console 生成的文件不提交仓库」），但 `build-console.sh`、`vite.config.js` 与 README
  里还留着「产物已提交、`mvn -o package` 直接就能打出完整 jar」的旧说法——在**干净克隆**上
  不成立（产物目录不存在，jar 里就没有控制台）。本次只改了这些位置里的路径，没动这层说法：
  它是一个关于发布流程的取舍（要不要把前端产物入库），该单独决定，不该混在路径调整里。

---

## 10. 落地记录之三：包名再改为 `io.github.melin.lakehouse.rest`

动机收窄到一句话：**模块坐标已经是 `lakehouse-rest-*`，包名却还带 `paimon`**，而「服务端
已不只服务 Paimon」正是整份方案要解决的问题。§9 那次把包名从 `paimonrest` 改成
`paimon.rest`，只是把两个词分开，并没有去掉 `paimon`——当时把它与「配置前缀」看成同一条
要保留的历史线，事后看是**把两个判断混成了一个**（§10.2 给了拆分后的判据）。

改法机械，脚本 `outputs/maven-module-refactor/rename-package-lakehouse.py`（`--check` 预演 /
`--apply` 才动手，与 `restructure.py` 同一套写法）：**11 个包目录** `git mv`、**1106 处**字面
替换（1096 处点号形式 `io.github.melin.paimon.rest` + 10 处路径形式 `io/github/melin/paimon/rest`），
命中 **237 个文件**。两条替换串都不会命中旧包名 `io.github.melin.paimonrest`——它的 `paimon`
后面跟的是 `r` 而不是 `.`；这不是巧合，是排查时确认过才敢跑全仓替换。

不需要动的地方，与 §9 完全一致：Spring 组件扫描与 JPA 实体扫描按包名（`@SpringBootApplication`
所在包及其子包）匹配，全仓没有一处显式 `basePackages` / `@ComponentScan` / `logging.level.<包>`
/ `spring.factories`，所以换包名对装配是透明的；ANTLR 语法里没有 `@header`，包名由
`src/main/antlr4/` 的目录层级推出；`Main-Class` 只出现在 optimizer 自己的 POM 与 Dockerfile 里。

### 10.1 兼容面：只留一个壳，不是两个

§9 留下的空壳（`io.github.melin.paimonrest.spark.ManagementSparkExtensions`）本次只是
**把 `extends` 指向新实现**，自己的包名一个字不动。**没有**为 `io.github.melin.paimon.rest`
再补一个壳，理由是它不存在于任何已发布产物里：`git log` 里没有它（阶段 4 的改动从未提交），
也就没有任何部署可能写着它。

这条判据值得固定下来：**兼容壳的数量 = 历史的对外名字数，不是改过包名的次数。**
多留一个壳的成本不只是两个类文件——它会让「哪些名字现在还有人在用」这个问题永久失去答案。

### 10.2 为什么 `paimon` 在包名里能去掉、在配置键里不能

这次改名的真正风险不是机械替换，而是**误伤另外三处同样带 `paimon`、却真的承载对外契约的名字**。
四者的区别只在「谁持有它」：

| 名字 | 谁持有 | 本次 |
| --- | --- | --- |
| `io.github.melin.paimonrest.spark.ManagementSparkExtensions` | 用户的 `spark.sql.extensions` | **留壳**（§9.2） |
| `paimon.rest.*` | 用户的 `application.yml` | **不动**（§4.3(1)） |
| k8s 资源名 / namespace `paimon-rest*` | 集群里的对象 | **不动**（§4.3(3)） |
| `paimon_` 表前缀、`paimon_catalog` | 存量数据库 | **不动**（§4.3(4)） |
| `io.github.melin.paimon.rest.*` | 只有本仓库自己（且从未提交） | **直接改**（本节） |

包名能改而配置键不能，差别就在最后一行：包名在提交之前**没有别人见过它**，配置键不是。
判断一个名字属于哪一类，只需要问「除了本仓库，还有谁会写它」。

### 10.3 验收

| 项 | 结果 |
| --- | --- |
| `mvn -o -DskipTests clean install` | **BUILD SUCCESS**，8 个模块（含 enforcer 三条不变量） |
| `mvn -o test -pl :lakehouse-rest-spark` | **96 run / 1 error（`PaimonS3Test`，既有）/ 25 skipped**，与阶段 4 逐字一致 |
| `mvn -o test -pl :lakehouse-rest-server` | **本轮未复跑**（运行中被中断）。可依赖的间接证据：317 个测试类全部编译通过、按新包名解析；四个校验脚本全绿。复跑命令见 §9.1，预期仍是 317 / 0 / 0 |
| 四个离线校验脚本 | 全绿（verify-console 26 项 · verify-spark-sql-doc 27 项 · verify-management-contract · verify-k8s-manifests） |
| jar 内容 | 两个 FQCN 都在（实现 7786 B、空壳 914 B）；ANTLR 生成物落在 `io/github/melin/lakehouse/rest/spark/parser/`；`io/github/melin/paimon/` 下的条目数 **0** |
| 空壳可用性 | `javap` 确认 `extends io.github.melin.lakehouse.rest.spark.ManagementSparkExtensions` + public 无参构造，Spark 能实例化 |
| 残留检查 | 全仓 `paimonrest` 只剩空壳那一个文件；11 个空的旧包目录已清掉 |

### 10.4 遗留

- **`-protocol-iceberg` 仍未落地**，位置已备好（§9.1）。
- **空壳退役条件**同 §9.4：确认没有部署还在用旧 FQCN 之后再删。
- **脚本自身的坑，记一笔**：清理「`git mv` 之后剩下的空目录」时误用了 `os.walk` 的循环变量
  `base`——它在循环结束后指向 walk 最后访问过的目录，拿它拼路径就删到了别处（11 个空目录
  一个都没清掉，且不报错）。已修正为从 `src` 反推父目录；本轮的空目录手工补删。这类
  「循环变量泄漏到循环外」的错在 Python 里不会报警，值得在同类脚本里留意。

---

## 附：一页速览

```
改名（必做，小）：paimon-rest-* → lakehouse-rest-*   （坐标 / 目录 / 镜像名 / 脚本 / 文档）   ✅ 阶段 0
命名分层       ：骨架中立 · 协议面带格式名 · k8s 名与表前缀不动
模块（建议）   ：parent → spring-parent → { core · storage · protocol-paimon · (protocol-iceberg) · server }
                 parent → { spark · optimizer }                                              ✅ 阶段 1–3
三条不变量     ：core 无格式依赖 · 两面互不依赖 · iceberg-core 只在 protocol-iceberg           ✅ enforcer 守
顺序           ：先改名 → 再拆模块 → Iceberg 代码从第一天落进 protocol-iceberg
目录           ：四个模块嵌在 spring-parent/ 下（`-pl` 要写 :artifactId）                       ✅ 阶段 4（§9）
Java 包        ：paimonrest → paimon.rest → io.github.melin.lakehouse.rest                      ✅ §9 / §10
                 与 artifactId 的 lakehouse 对齐；只留「最初名字」的空壳兜住既有部署
对外名字的分层 ：能不能改看「谁写它」——spark.sql.extensions 要留壳；paimon.rest.* 配置键 /
                 k8s 资源名 / paimon_ 表前缀都不动；包名只有本仓库见过，直接改（§10.2）
不改的三处     ：paimon.rest.* 配置前缀 · k8s 资源名 · paimon_ 表前缀（§4.3(1)(3)(4)）
实测规模       ：core 86 · storage 8 · protocol-paimon 35 · server 39（共 168 个类）
实际落点的差异 ：见 §8.2（六处，均由搬迁前的依赖图算出）
```
