# AGENTS.md

给在本仓库里干活的编码代理看的操作说明。**人类读者请先看 [`README.md`](README.md)**——
它更完整；本文只写「代理必须先知道、且不知道就会做错」的东西。

> 本仓库所有文档、注释、提交信息都是**中文**，注释风格是
> **解释「为什么」而不是复述「做了什么」**（见第 7 节）。

---

## 0. 一句话

用 Spring Boot + JPA 实现 **Apache Paimon 的 REST Catalog API**、**Apache Polaris 的
REST Management API**（RBAC 管理面）与一个 **Vue 3 管理控制台**；另有 Spark SQL 语法扩展，
用 SQL 管理主体、角色与授权。

三个对外面 + 一个界面（记牢这四个基址，改动都要落在对应面上）：

| 面 | 基址 | 规格来源 | 鉴权 |
| --- | --- | --- | --- |
| Catalog API（数据面） | `/v1/{prefix}/...` + `/v1/config` | `spec/rest-catalog-open-api.yaml` | `paimon.rest.auth.*` |
| Management API | `/api/management/v1/...` | `spec/polaris-management-service.yml` | 同上 |
| Console API + OAuth | `/api/console/v1/...`、`/api/catalog/v1/oauth/tokens` | 自有（`docs/console-auth.md`） | 同上（登录端点豁免） |
| 控制台界面 | `/console/`（SPA 深链回退 `index.html`） | — | 开关 `paimon.rest.auth.console.required` |

---

## 1. 硬约束（违反会坏构建或坏部署）

1. **JDK：测试可 17/21，服务端运行时只用 17。**
   - **测试用 17 或 21，不能 24+**：Spark 3.5 建测试会话要经过 Hadoop 的
     `Subject.getSubject`，该方法自 JDK 24 永久移除（JEP 486）→ 每个建会话的用例各报一条
     `UnsupportedOperationException: getSubject is not supported`，堆栈指向 Hadoop 内部，
     **与本仓库代码无关，且没有任何 JVM 参数能绕过**。`-DskipTests` 打包不受影响。
   - **服务端运行时必须 17，不能 18+**：JDK 18 的 `InetAddress` 解析器 SPI（JEP 418）
     撞上 `paimon-s3` 插件包的多版本 jar 布局 → `PluginFileIO` 切过类加载器后，那块作用域里
     **任何** `InetAddress` 解析都抛 `ServiceConfigurationError` → 对象存储的表写不出 schema /
     快照。症状：「S3 表建得出来、REST 读写也正常，桶里却没有 `schema/` 与 `snapshot/`」。
     `Dockerfile`（运行镜像）钉 17 就是为了这条，机理详见其顶部注释与 README 第 6 节第 21 条。
   ```bash
   JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test   # 测试（macOS；通用写 /path/to/jdk-21）
   JAVA_HOME=/path/to/jdk-17 mvn -pl :lakehouse-rest-server spring-boot:run   # 起服务端
   ```
2. **仓库里没有 Maven Wrapper。** 没有 `mvnw`、没有 `.mvn/`。README 与 `docs/console.md`
   里多处写的 `./mvnw` 在本仓库**跑不起来**，一律换成系统 `mvn`（3.9+）。
   **镜像里也没有 Maven**：`Dockerfile` 只装预先构建好的 jar，所以「没装 Maven 就用
   Docker 构建」这条退路不存在——构件必须在宿主机或 CI 上先 `mvn package` 出来。
3. **ANTLR 运行时版本必须精确等于目标 Spark 自带的版本**（现钉 `4.9.3`，对应 Spark 3.5.9）。
   写高了会把 Spark 的 `antlr4-runtime` 顶掉，症状是**整个会话连 `SELECT 1` 都解析不了**
   （`Could not deserialize ATN with version 3 (expected 4)`）。改 `spark.version` 时
   同步核对根 `pom.xml` 的 `antlr.runtime.version`。
4. **两个父 POM ＝ 两个依赖世界。根聚合器刻意不继承 `spring-boot-starter-parent`。**
   根 `pom.xml`（`lakehouse-rest-parent`）是**纯聚合器**，同时是 `lakehouse-rest-spark` 与
   `lakehouse-rest-optimizer` 的父 POM；服务端那套（core / storage / protocol-paimon / server）
   挂在 `lakehouse-rest-spring-parent` 下，**由它**继承 Boot 父 POM。合并的后果：Boot 的
   `dependencyManagement` 会把 Spark 侧的 Jackson 2.15.2 抬到 2.21.x、替换 netty/jersey，
   Spark 模块直接坏。**不要把 Boot 提为根聚合器的父。**
   两条附带约束：

   - **`<relativePath>` 不用写——四个服务端模块的目录就嵌在 `spring-parent/` 里面。**
     子目录到父 POM 的距离是默认值：`../pom.xml` 恰好就是 `spring-parent/pom.xml`。
     反过来才是坑：若把它们放成仓库根下的**平级兄弟**目录，就必须显式写
     `<relativePath>../lakehouse-rest-spring-parent/pom.xml</relativePath>`——默认值会解析到
     根聚合器，那里没有这个 artifactId，Maven 便回退去远程仓库找，**离线构建（`-o`）直接失败**。
   - **`-pl` 一律带冒号：`-pl :lakehouse-rest-server`。** `-pl` 收的是
     `[groupId]:artifactId` 或**相对路径**，裸名字按后者解析。模块挪进 `spring-parent/` 之后
     `-pl lakehouse-rest-server` 直接报
     `Could not find the selected project in the reactor`。带冒号的写法只看坐标、
     与目录层级无关，所以 `-pl :lakehouse-rest-spark` 也照样写（Spark 目录确实还在根下）。
   - **共享依赖放 `spring-parent` 的 `<dependencies>`，但 `paimon-core` 不进。**
     服务端共用 webmvc / data-jpa / validation / lombok，写在 `spring-parent` 里；
     `paimon-core` 一旦也写进去，`core` 会连带拿到 Paimon，enforcer 立刻红（见第 10 条）。
5. **`sql/schema-mysql.sql` 是生成物，不要手改**（`scripts/gen-mysql-ddl.sh` 从 JPA 实体生成，
   22 表 / 21 唯一约束 / 23 索引，下次生成会覆盖）。**改了实体就必须重跑生成**，
   否则启动时 `ddl-auto=validate` 直接失败。
6. **构建服务端 jar（含控制台）用 `clean package`，不要裸 `package`。** 控制台产物按内容哈希命名，
   `maven-resources-plugin` 只覆写不删除 → `target/classes/static/console` 累积历次旧产物
   （jar 里出现多个 `index-*.js`）。服务端 POM 已把一次 `clean` 绑到 `initialize` 兜底。
7. **测试分模块跑，不要用 `-Dtest=` 一次性筛全仓。** `lakehouse-rest-spark` 里没有匹配的测试类时，
   surefire 报 `No tests matching pattern` 并让整个构建 **BUILD FAILURE**（像回归，其实不是）。
   限单模块 + `-Dsurefire.failIfNoSpecifiedTests=false`：
   ```bash
   mvn -o test -pl :lakehouse-rest-server -Dtest=StaticCredentialApiTests -Dsurefire.failIfNoSpecifiedTests=false
   ```
8. **Spark 的 `spark.sql.extensions` 只能「包装」，不能「改写」语法。** Spark 3.5 的
   `SqlBase.g4` 没有兜底分支，本模块自带一份只描述管理语句的小语法，「能否整句匹配到 EOF」
   成功才接管，失败原样交回原生解析器。**管理语句的识别依据是语法，不是字符串前缀。**
9. **Spark 模块里被 Scala 引用的 Java 类型不能写成 `record`。** IDE 混合编译让 scalac 直接
   解析 Java 源码取签名，而 Scala 2.12 的 Java 解析器不认识 `record` → 这些类型在 IDE 里
   整体消失（`value X is not a member of object ...`，连带 lambda 报 `missing parameter type`）；
   Maven 不受影响（`sendJavaToScalac=false`，scalac 读的是 class 文件），于是「Maven 绿、IDE 红」。
   被 Scala 摸到（**按名字或靠返回值推断都算**，如 `client.listCatalogs().asScala.map { c => c.name() }`）
   的类型一律写普通静态嵌套类，见 `ManagementApiClient` 里五个摘要类型及其上方注释。
10. **模块依赖方向由 maven-enforcer 守着，破方向即构建失败。** 三条不变量：
    `core` 不含任何表格式依赖（`org.apache.paimon:*` / `org.apache.iceberg:*` 一律禁止）；
    两个协议面（`protocol-paimon` 与 `protocol-iceberg`）**互不依赖**；
    `iceberg-core` 只准出现在 `protocol-iceberg`。
    合法依赖图：`core ← storage ← protocol-paimon / protocol-iceberg ← server`（箭头读作「被谁依赖」），
    `spark` / `optimizer` 不参与这条链。三个容易踩的点：
    - **`searchTransitive` 的默认值是 `true`**（查整棵依赖树），`core` / `storage` /
      `protocol-paimon` 靠它拦住「传递依赖里混进格式库」。但 `server` 必须显式写
      `<searchTransitive>false</searchTransitive>`——可执行模块最终要**经由**
      `server → protocol-iceberg` 传递地拿到 `iceberg-core`，查整树会把自己误杀。
    - **`storage` 的 `paimon-core` 必须 `<optional>true</optional>`。** 它是唯一允许依赖
      Paimon 的非协议模块，而 `searchTransitive=true` 会把它传给 `protocol-iceberg`——
      那个面自己的规则于是把自己误杀。它对外只暴露「路径 + 文本」，optional 才是它真正的
      语义（见 `StorageFileAccess`）。
    - **「某模块要用 Paimon 类型」几乎总是分类问题，不是加依赖问题。** 往 `core` 加 Paimon
      依赖是最典型的方向性错误；正确做法是把那个类型挪到 `protocol-paimon`。
      具体的六处偏离（哪些类实际落在哪、为什么）见
      [`docs/maven-module-design.md`](docs/maven-module-design.md) §8.2。

---

## 2. 命令速查

```bash
# ---- 构建 / 测试（JDK 17 或 21）----
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -o -DskipTests clean install   # 全量（9 个模块，含 enforcer 校验）
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -o -DskipTests -pl :lakehouse-rest-server -am clean package
                                                # 只要服务端 jar（-am 会连上游 core/storage/两个协议面一起构建）
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -o test        # 全量测试

# ---- 容器镜像（Dockerfile 只装 jar、不编译，见第 8 节）----
docker build -t lakehouse-rest-server:0.0.1 .                   # 当前架构
./scripts/build-image.sh                                     # amd64 + arm64（多架构）

# ---- 本地起服务（h2 profile 免 MySQL）----
java -jar lakehouse-rest-spring-parent/lakehouse-rest-server/target/lakehouse-rest-server-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=h2 --server.port=8080
open http://127.0.0.1:8080/console/        # 默认账号密码 admin/admin

# ---- 端到端验收（都要先有一个运行中的实例）----
BASE=http://127.0.0.1:8080 ./scripts/api-sweep.sh            # Catalog API 全部 60 个 operation
BASE=http://127.0.0.1:8080 ./scripts/management-sweep.sh     # Management API 全部 33 个 operation
./scripts/e2e-spark-sql.sh                                   # 自起 h2 实例，跑 Spark 四件套
BASE=... CLIENT_ID=... CLIENT_SECRET=... STATIC_TOKEN=... ./scripts/sweep-console-auth.sh
cd lakehouse-rest-console && BASE=... CLIENT_ID=... CLIENT_SECRET=... npm run check:login

# ---- 代码生成 / 文档校验（改规格或实体后）----
PYTHON=/path/to/python3 ./scripts/build-console.sh        # 装依赖→构建→校验（含产物新鲜度）
python3 scripts/verify-console.py                         # 只校验不构建（多解释器时用 PYTHON=...）
python3 scripts/gen-management-contract.py                # 重生成 docs/management-api-contract.md
python3 scripts/verify-management-contract.py             # 反解文档与规格逐行比对
python3 scripts/verify-spark-sql-doc.py                   # Spark SQL 文档 ↔ 语法文件/PermissionDtos/客户端
python3 scripts/verify-k8s-manifests.py                   # K8s 清单
./scripts/gen-mysql-ddl.sh                                # 重生成 sql/schema-mysql.sql
```

- 这些 Python 脚本都解析 OpenAPI 规格，依赖 **PyYAML**（`python3 -m pip install --user PyYAML`）。
  宿主机常有多个解释器，装了 PyYAML 的未必是默认那个——用
  `PYTHON=/path/to/python3 ./scripts/xxx.sh` 指定。
- `-o`（离线）在依赖已缓存时可用。
- **`-pl` 一律写成 `-pl :artifactId`。** `-pl` 收的是坐标或**相对路径**，裸名字按后者解析；
  服务端四个模块嵌进 `lakehouse-rest-spring-parent/` 之后，`-pl lakehouse-rest-server`
  会直接报 `Could not find the selected project in the reactor`（另见第 1 节第 4 条）。
- `api-sweep.sh` / `management-sweep.sh` 会建库建表再清理，**要求目标实例是初始状态**。

---

## 3. 仓库地图

```
pom.xml                      lakehouse-rest-parent：纯聚合器（spring-parent + spark + optimizer）
lakehouse-rest-spring-parent/   服务端世界的父 POM（继承 Boot 4.1.1）
  pom.xml                    只有 POM、无源码；<modules> 列出下面四个（相对本文件解析，不带 ../）

  lakehouse-rest-core/     与表格式无关的地基（86 个类）——唯一不依赖任何格式库的服务端模块
    config/               RestServerProperties、Bearer 鉴权、授权拦截器、CatalogAccessRules、
                        RequestContext、WebConfig、ClockConfig、JsonContentTypeConfig
    web/                  ConfigController（`/v1/config`）、OAuthTokenController、GlobalExceptionHandler
    service/              CatalogService（catalog 增删改查）、授权判定与 Paimon 权限模型、
                        登录与令牌（AccessToken / Oidc / ClientCredentials / TokenAuthentication）、
                        LoginAttemptLimiter、PermissionManagementService
    domain/entity/        19 个实体（catalog 侧 12 + 管理侧 7）+ AuditedEntity 基类 + JsonConverters
    domain/repo/          19 个 Spring Data 仓储
    dto/                  与规格对应的共享词表：Common / Console / Grant / Permission / Storage /
                        Type Dtos + ManagementEnums + Privilege（生成物）
    support/              Json、ApiException、AuditPrincipal、StorageConfigs、CredentialCipher、
                        Digests、Jwt/Jwks、Paging、Patterns、Values、FileIoType、
                        VendedStorageCredential、ResourceType、Secrets

  lakehouse-rest-storage/  FileIO 构造与仓库文件访问（8 个类）——
                        唯一「非协议面」却允许依赖 paimon-core 的模块（只用它的 FileIO）
    service/              StorageFileIoFactory、StorageRuntimePolicy、StorageCredentialManager
                        及 Default / Noop 实现与 StorageCredentialCache、CatalogFileService
    support/              Paths

  lakehouse-rest-protocol-paimon/   LakeHouse Rest Catalog 协议面（35 个类）
    paimon/web/           9 个控制器（Table / Database / View / Function / Branch / Tag /
                        Partition / Consumer / SemanticView），路径与规格一一对应
    paimon/service/       同名资源的 service，及 TableMetadataService（schema/快照物化）、
                        SchemaChangeService、TableLookup、CredentialService
    paimon/dto/           与 rest-catalog-open-api.yaml 的 schema 对应的 9 个 DTO 文件
    paimon/support/       Codecs、SchemaSupport、TableDefaults、TableKinds
    ↑ 唯一一个包名比 core / storage / server 多一层 `.paimon` 的模块，理由见第 7 节

  lakehouse-rest-server/   可执行服务端（39 个类；测试也在这一模块：29 个测试类 + `TestClock` 支撑类）
    PaimonRestServerApplication.java   启动类（组件扫描按**包**不按 jar，见第 7 节）
    web/                  Management API 4 个（Principal / PrincipalRole / CatalogRole /
                        ManagementCatalog）+ PermissionController；控制台 4 个（ConsoleAuth /
                        ConsoleMeta / ConsoleFile / Optimization）
    service/              RBAC 管理 service、ConsoleMetaService、ManagementMappers，
                        优化调度（Batch / Dispatcher / Job / ConfigResolver / TableOptimization +
                        submit/ 下三个提交器）
    domain/entity|repo/   3 个优化实体 + 3 个仓储（其余实体在 core）
    dto/                  ManagementDtos、OptimizationDtos
    support/              OptimizationOptions、OptimizationStatus
    config/               ConsoleWebConfig、DataInitializer、OptimizationSchedulingConfig
    resources/            application*.yml；static/rest-catalog-open-api.yaml 与
                        polaris-management-service.yml（运行时可从 /<name> 下载）、
                        console/（前端构建产物，见第 8 节陷阱 1）

lakehouse-rest-console/    Vue 3 + Vite（Element Plus），构建产物直接落进服务端 classpath
lakehouse-rest-spark/      Scala 2.12 + Spark 3.5 + ANTLR 的语法扩展（16 条管理语句）：
                        ManagementSql.g4（语法）→ PaimonRestSqlParser.scala（注入
                        ParserInterface）→ ManagementAstBuilder.scala（语法树 → 命令）→
                        ManagementCommands.scala（13 个 RunnableCommand → HTTP）；
                        client/ 为 JDK HttpClient + Jackson 2 的 REST 客户端；
                        **另有旧包名 `io.github.melin.paimonrest.spark` 下的一个空壳**，
                        只为兼容既有部署写死的 `spark.sql.extensions`（见第 7 节）
lakehouse-rest-optimizer/  表优化执行体（被 k8s 提交起来跑，不经服务端 classpath）：
                        PaimonProcedures、TableOptimizerJob、OptimizerOptions、OptimizationApiClient
deploy/kubernetes/      kustomize 清单（有独立 README）
spec/                   两份 OpenAPI 规格的仓库副本（运行时可从 /<name> 下载）
sql/                    MySQL 建表语句（生成物）
scripts/                验收 / 生成 / 校验 / 镜像构建脚本
docs/                   16 份设计文档
```

服务端四模块的**类数**：core 86 · storage 8 · protocol-paimon 35 · server 39（共 168）。
core / storage / server **共用同一个包树**（`io.github.melin.lakehouse.rest.*`），所以「按包扫描」
的 Spring 组件扫描与 JPA 实体扫描在拆分前后**行为完全一致**，搬类不需要改任何 `import`——
编译通过本身就是搬对了的证据（详见 [`docs/maven-module-design.md`](docs/maven-module-design.md) §8）。
**例外是 Paimon 协议面**：它独占 `rest.paimon.{web,service,dto,support}` 子树，与 core 的
`rest.{dto,service,support,web}` 平级而不重合，为的是给 Iceberg 面留出对称的
`rest.iceberg.*`（见第 7 节最后一条与设计文档 §11）。`rest.paimon.*` 仍是主类所在包的子包，
组件扫描照旧覆盖。
包名本身改过三次（`io.github.melin.paimonrest` → `io.github.melin.paimon.rest` →
`io.github.melin.lakehouse.rest` → 各协议面加 `.paimon` 段），那是与模块边界无关的另一件事：
目录 `git mv` + 全仓字符串替换，一次做完。最终名与 artifactId 的 `lakehouse` 对齐；空壳只保留
**最初**那个（`spark.sql.extensions` 的存量值），中间名从未提交、从未发布，所以不需要壳
（见第 7 节）。

`docs/` 读哪份：

| 文档 | 什么时候读 |
| --- | --- |
| `catalog-api-endpoints.md` | Catalog API 的端点、基址、前缀、授权映射；**两个协议面各一章**——Paimon 的 60 个与 Iceberg 的 26 个（含 9 个不声明的、各端点的成功码、错误模型） |
| `management-api-overview.md` | 33 个 operation 落在哪几个控制器、易读错的语义 |
| `management-api-contract.md` | 管理 API 字段与权限枚举**逐字契约**（生成物，勿手改） |
| `data-model.md` | 改实体 / 看 22 张表划分、摘要列与审计列的理由、实体与仓储分在哪两个模块、Iceberg 协议面新增的 9 个列（§1.1） |
| `table-optimization.md` | 改表存储优化：三层配置合并、批次与调度游标、组批与提交、前后指标；**两种表格式共用一套策略表与批次表**，参数键按格式分派（§2.5 是 Iceberg 的对应物，§6.5 是执行侧差异） |
| `maven-module-design.md` | 改模块划分 / 加模块（再加一个格式面就照 §12 那份清单走）：依赖图、两条父 POM、三条 enforcer 不变量、五段落地记录（§8–§12） |
| `spark-sql-reference.md` | 语句级参考（第 10 节示例会被测试真跑） |
| `spark-sql-extension.md` | 改 Spark 扩展：做法、两个约束、测试分层 |
| `spark-paimon-rest-e2e.md` | Spark 经本服务端建表：连接配置、可执行 SQL、报错对照、三种非 Paimon 表（Format / Object / Lance）的读写边界（§4.4） |
| `spark-iceberg-rest-e2e.md` | 让 Spark 通过本服务端建 **Iceberg** 表：`spark.sql.catalog.*` 配置（含 `rest.auth.type` 与 `scope=PRINCIPAL_ROLE:ALL` 两个坑）、可执行 SQL、与 Paimon 那套的差异对照、一期不做的 9 个端点会怎么撞上来。**第 0 节显式分层标注验证状态：协议层与官方 Java 客户端层已实测，Spark 侧未跑**，所以 SQL 是参考而非实测记录 |
| `authorization.md` | 权限实体链、授权映射、失败关闭的取舍；**多级 namespace 的判定口径在 §2.6**（两条链存的形状不同，按点号拼成路径再比，逐段比会静默不命中） |
| `console.md` | 改控制台 / 排查「页面空列表」：分层、端点契约表、机械校验、加页面清单 |
| `console-table-detail.md` | 改表详情页：页签随**表格式**（表级 `schema.options.type`）伸缩的数据来源（详细信息含字段/分区列表、表目录文件列表、「为什么没有权限页签」）、按需加载与非 Paimon 表不问快照端点、破坏性操作 |
| `console-catalog-detail.md` | 改 Catalog 详情页 / 仓库文件浏览：三页签、下钻与路径防护、单文件下载、只读取舍、「配置」页的默认表属性（`table-default.*`）编辑与并发冲突处理；建表表单里的默认参数预填（§3.4）与为什么不给 Lance 表建表入口（§3.5）；**「协议与能力」卡片**（§3.1.1：客户端基址、端点清单、不声明支持）以及 Iceberg catalog 下只剩「概览」与「文件列表」两页可用（§9） |
| `console-auth.md` | 配控制台登录：三种登录方式、两个开关的边界、令牌链、安全考量 |
| `polaris-capabilities-and-design.md` | 对比 Polaris、看整体设计与已知缺口 |
| `iceberg-rest-catalog-design.md` | 让同一基址、同一份元数据同时服务原生 Iceberg REST 客户端：双协议方案、两套面的差异与代价、catalog 建时区分格式；**已落地的实测记录与与方案不一致的十六处**（§11） |

---

## 4. 核心设计不变量（改代码前先理解）

- **前缀路由**：`{prefix}` 是 catalog 的对外标识，由 `GET /v1/config` 下发。未登记的 prefix 在
  `paimon.rest.auto-create-catalog=true`（默认）时自动登记 → 客户端可零配置接入，但
  **写错 prefix 会得到一个新的空 catalog 而不是报错**。
- **认证与授权分离**：`BearerAuthInterceptor` 只回答「调用者是谁」，RBAC 判定在授权层；
  分别由 `paimon.rest.auth.*` 与 `paimon.rest.authorization.*` 控制，可以只开认证不开授权。
- **Catalog API 的授权映射集中在一张表**（`config/CatalogAccessRules.java`）：`/v1/**` 路径 →
  所需权限，**未登记映射的端点直接拒绝而不是放行**。`CatalogEndpointAuthorizationTests`
  从运行时请求映射枚举全部端点核对覆盖率——**新增端点若忘了登记映射，构建会失败**（有意设计）。
- **多态变更不绑死在某个 JSON 库上**：`SchemaChange` / `ViewChange` / `FunctionChange`
  统一按 `List<Map<String,Object>>` 接收，服务层按 `action` 分派。
- **JSON 文档列**：schema、函数定义、分区 spec 等以 JSON 文本存单列，实体字段保持强类型；
  复杂结构走 `AttributeConverter`（`JsonConverters`）。
- **审计主体名归一化**（`support/AuditPrincipal.java`）：`owner` / `created_by` / `updated_by`
  是 `varchar(255)`，主体名无长度上界（未映射令牌本身 272 字符）→ 超长记 `sha256:<前 12 位>`，
  **绝不截断**（截断等于把凭据片段明文入库）。改这三个列的逻辑别绕过它。
- **存储配置收口在 `support/StorageConfigs.java`**：`validate` / `blank` / `copyWith` / `of` /
  `withoutSecrets` / `mergeStaticCredentials`。新增存储类型或字段时这几处都要同步，
  漏一处就是「读得出来写不进去」。
- **catalog 静态凭据**（超出 Polaris 规格的扩展，见 `README.md` §5）：`secretAccessKey`
  **只写不读**（AES-GCM 加密落库，响应永不回显）→ `PUT` 省略它表示**保持**，这是规格
  「整体替换」语义的**唯一例外**；清除要显式提交一对空串；与 `storageName` 互斥；
  下发优先级**高于一切服务端来源，且排在 `stsUnavailable` 判断之前**。
- **schema 与快照都要物化进仓库**（`TableMetadataService`）：`RESTCatalog.supportsVersionManagement()`
  恒返回 `true`，客户端因此走 `CatalogSnapshotCommit`（把快照 POST 给服务端），**自己那份写
  `snapshot-<n>` 的代码根本不会被实例化**；服务端必须替它写 `snapshot-<id>` + `LATEST`
  （回滚反向清理）。写的是**客户端原文**——规格未建模的字段（如 `properties` 序列号水位）
  丢了不报错，但会悄悄改变去重语义。`version` 列存的是**快照文件格式版本**
  （恒为 `Snapshot.CURRENT_VERSION`＝3），**不是自增序号**；`snapshots/{version}` 按
  `RESTApi.loadSnapshot` 契约解析：`EARLIEST` / `LATEST` / **数字即快照 id** / 其余当标签名。

---

## 5. 按改动类型走的清单

| 改动 | 步骤 |
| --- | --- |
| **加 Catalog API 端点** | ① 控制器 + DTO 都进 `lakehouse-rest-protocol-paimon`（`web/` 与 `dto/`），字段名与规格逐字一致；② `lakehouse-rest-core` 的 `config/CatalogAccessRules.java` 登记路径 → 权限（漏了构建红）；③ 测试 `PaimonRestCatalogApiTests` 之类 + `CatalogEndpointAuthorizationTests`（**测试目前全在 `lakehouse-rest-spring-parent/lakehouse-rest-server/src/test/`**，见 `maven-module-design.md` §8.6）；④ 更新 `docs/catalog-api-endpoints.md` |
| **改 JPA 实体 / 加表** | ① 改 `domain/entity`——**先判断放哪个模块**：catalog 侧 12 实体与 RBAC 管理侧 7 实体在 `lakehouse-rest-core`（被协议面复用，下沉避免协议层反向依赖 server），表存储优化 3 实体在 `lakehouse-rest-server`；继承 `AuditedEntity` 才有审计列；② `./scripts/gen-mysql-ddl.sh` 重生成 `sql/schema-mysql.sql`（**必须**）；③ 摘要列（SHA-256）或唯一约束变化时同步 `docs/data-model.md` |
| **改管理 API / 权限** | ① 实现落在 `lakehouse-rest-server`（`web/` + `service/` + `dto/ManagementDtos`），词表类在 `lakehouse-rest-core`（`dto/ManagementEnums`、`dto/Privilege`）→ 改规格副本与实现后 `python3 scripts/gen-management-contract.py`；② 权限枚举变了 → `scripts/gen-management-privileges.py` 重生成 `dto/Privilege.java`；官方权限词表（access / ResourceType）变了 → 跑 `verify-spark-sql-doc.py`（比对语法/PermissionDtos/客户端三方）；③ 管理语句跟随改动时进 `lakehouse-rest-spark`（语法 + AstBuilder + Command 三处） |
| **跨模块搬类 / 加模块** | ① **先算依赖图再动手**——凭「看起来该属于这里」搬会把方向搬反，本仓实测出现过 46 条反向边；工具在 `outputs/maven-module-refactor/module-graph.py`（分类表 `RULES`、允许方向 `ALLOWED`）；② 包名不动 → 不需要改任何 `import`，`mvn -o -DskipTests clean install` 编译通过＝搬对了，enforcer 红＝方向搬反；③ 同步 `docs/maven-module-design.md`（§8 落地记录）与本文第 3 节的类数 |
| **改控制台** | ① 按 `docs/console.md` §5.3 清单做（endpoints.js → api/*.js → views/ → router → 构建）；② `./scripts/build-console.sh` 重新构建（**产物必须一起提交**，见第 8 节陷阱 1）；③ 页面渲染不写进服务端测试，服务端侧只覆盖托管机制（`ConsoleApiTests`） |
| **改配置项** | ① `lakehouse-rest-core` 的 `config/RestServerProperties.java` 加字段 + javadoc（默认值、留空含义、非法取值行为）；② `lakehouse-rest-server` 的 `application.yml` 加注释示例；③ `README.md` §5 配置表加一行。涉及存储凭据时，`docs/polaris-capabilities-and-design.md` 的「下发优先级」表也加一级 |

---

## 6. 验证阶梯（按序自检，别只跑单元测试）

| 层 | 命令 | 抓什么 |
| --- | --- | --- |
| 单元/集成 | `mvn -o test`（服务端 332 + Spark 96，共 428；Spark 侧 25 个跳过；optimizer 另有 25 个，`-pl :lakehouse-rest-optimizer` 单独跑） | 行为回归。注意 Spark 模块的 `example/PaimonS3Test` 当前会在 BUILD FAILURE 停下（缺 `spark.sql.extensions`，与 S3 环境无关）；要单独看服务端结果用 `mvn -o test -pl :lakehouse-rest-server` |
| 端点多面 | `verify-console.py`、`verify-management-contract.py`、`verify-spark-sql-doc.py`、`verify-k8s-manifests.py` | **跨文件的机械一致性**：前端字段名 vs 服务端 record、文档表格 vs 规格、语法文件 vs PermissionDtos vs 客户端 |
| 契约（桩） | `ManagementSqlExecutionTests`（真实 SparkSession + 桩服务端） | 扩展真被加载、真执行 |
| 契约（真服务端） | `./scripts/e2e-spark-sql.sh` | 客户端与服务端的**实际**契约（桩只能证明客户端与自己的假设自洽） |
| HTTP 冒烟 | `api-sweep.sh` / `management-sweep.sh` / `sweep-console-auth.sh` | 规格端点全覆盖、真实报文与错误码 |
| 前端登录逻辑 | `npm run check:login` | 前端**发出的请求形状**与登录态流转（服务端测试看不到） |

两条经验：

- **写了机械校验就要做变异测试**：故意把被校验的东西改错（DTO 字段改名、前端类型列表少一项），
  确认校验脚本**报得出来**。不做的校验往往是空跑。
- 新增校验组要顺手把「总数」写进 `README.md` / `docs/console.md`。**这条现在由机制兜着**
  （`verify-console.py` 第 15 组断言这两处写的「N 组 M 项」与脚本实际一致，也断言那一组自己）——
  在此之前它只是句人情话，真漏过一次：`docs/console.md` 写 37、`README.md` 写 38、实际 38。
- **前端字段分层的改动要连 `build()` 一起看**：catalog 表单的字段拆成了
  `BASIC_FIELDS` / `ADVANCED_FIELDS`（高级那张进折叠区），而 `build()` 读的是两者合并后的
  `fields`。改这两张表时不要顺手让折叠状态参与提交——展开看一眼再收起就改变报文，
  只能靠 diff 才发现。第 13 组盯着这一条。

---

## 7. 代码与文档约定

- **注释写「为什么」**，尤其「为什么不选另一种做法」——本仓库注释密度就是这个风格，
  见 `pom.xml` 顶部、`CredentialCipher`、`StorageConfigs`、`AuditPrincipal`。
- **Java**：Java 17（record / sealed）；javadoc 引用代码用 `{@code ...}`；服务端是
  Spring Boot 4 / Jackson 3 / Hibernate 7，**Spark 模块是 Jackson 2**，两边 JSON 工具不要互串。
- **Strict 分层**：`web → service → domain/repo`；DTO 不泄漏实体，实体不外传
  （对外一律过 DTO，存储配置对外必须先 `withoutSecrets`）。跨模块方向见第 1 节第 10 条。
- **包名是 `io.github.melin.lakehouse.rest.*`。core / storage / server 共用同一个包树，
  两个协议面各占一层子树。**
  共用是刻意的：Spring 组件扫描与 JPA 实体扫描按**包**匹配，不认 jar，所以把一个类从
  `server` 搬到 `core` 不需要改一行 `import`，也不需要动任何 `@ComponentScan` 配置。
  **代价**是包名不再能告诉你类在哪个模块——判断方式只有「看它在哪个目录」，
  以及用 `outputs/maven-module-refactor/deps.py <类名>` 查它的出入边。
- **协议面独占 `rest.<格式>.*` 子树**（Paimon 面是 `rest.paimon.{web,service,dto,support}`），
  与上一条的「共用」不冲突：共用针对跨格式的平台代码，独占针对「注定有两个、且互不依赖」的
  协议面；Iceberg 面按同一形状铺 `rest.iceberg.*` 即可。**从共享包树里往外搬类必须补 `import`**
  ——旧布局下 `rest.dto.TableDtos` 与 `rest.dto.CommonDtos` 同包，源码里直呼其名、不写 import；
  分包后这类引用会整片失效，症状是编译器报「程序包 X 不存在」或「找不到符号」。详见设计文档 §11。
- **改包名属于「对外契约」级改动：唯一被写进用户配置的名字是 `spark.sql.extensions` 的值。**
  所以 `lakehouse-rest-spark` 里保留了一个旧包名下的空壳
  `io.github.melin.paimonrest.spark.ManagementSparkExtensions`（只继承新实现、不覆盖方法），
  让既有部署不改配置也能升级。注意留的是**最初**那个名字：中间用过的
  `io.github.melin.paimon.rest` 从未提交、从未发布，不为它留壳——壳的数量等于历史的对外
  名字数，不是改过的次数。**对全仓做包名批量替换时必须把壳排除**，否则旧部署会静默失效——
  症状是 Spark 会话初始化时的 `ClassNotFoundException`，离「改过包名」很远。
  同类的「不能随意改」的名字还有 `paimon.rest.*` 配置前缀、k8s 资源名、`paimon_` 表前缀，
  见 `docs/maven-module-design.md` §4.3 与 §8.6。
- **错误走 `ApiException`**，由 `GlobalExceptionHandler` 统一成规格要的报错形状；
  不要在控制器里手写响应体。
- **文档里的代码块要与代码同步**——`SparkSqlDocExamplesTests` 会真跑
  `docs/spark-sql-reference.md` 第 10 节示例，**改坏示例会让构建失败**。
- **生成物一律不要手改**：`sql/schema-mysql.sql`、`docs/management-api-contract.md`、
  `dto/Privilege.java`。要改就改生成器或规格再重跑。
- **控制台里「表格式」有两个层级，别混**：catalog 级是 `catalogFormat`（`PAIMON` / `ICEBERG`，
  `utils/catalogFormat.js`，创建后不可改），表级是 `schema.options.type`（`table` /
  `format-table` / `object-table` / `lance-table`，`utils/tableKind.js`）。`ICEBERG`
  **只属于前者**——它不在 `TABLE_KIND_LABELS` 里，拿它去比表级类型表会永远不匹配。
  判别顺序是先 catalog 后表，见 `docs/console.md` §10。
- **没有 CI**：上面所有校验都是手工/脚本触发的，「我没跑」＝「没人跑」。

---

## 8. 陷阱清单（都实测踩过）

1. **控制台产物目录被 `.gitignore` 忽略，但文档说它应当被跟踪**（`docs/console.md` §8.2、
   `README.md` §9 都写着「产物提交在仓库里」）→ **fresh clone + `docker build` 会得到
   一个没有控制台的服务端 jar**。这个不一致修掉之前，别假定干净检出里有控制台产物。
2. **改了控制台源码没重建** → 打包的是上一版控制台，**不报错**，只是安静地少一个按钮。
   `verify-console.py` 第 7 组查产物新鲜度，但要有人跑它。判是否干净：
   `ls lakehouse-rest-spring-parent/lakehouse-rest-server/target/classes/static/console/assets | wc -l` 应与源码目录一致。
3. **`e2e-spark-sql.sh` 里五个测试类必须同批运行**：一个 JVM 只能有一个 SparkContext，
   而 `spark.sql.extensions` 只在建会话时生效。把用桩的 `ManagementSqlExecutionTests`
   混进同一批会让 `LiveSparkSession` 报错（有意守卫，不是 bug）。
4. **`sweep-console-auth.sh` / `check:login` 一分钟跑超 5 次会被服务端自己的失败限速拦下**
   ——不是回归。两者缺 `BASE` / `CLIENT_ID` / `CLIENT_SECRET` 时不跑假失败，直接退出码 2。
5. **shell 里可能有 `http_proxy`** 指向本地代理，`curl http://127.0.0.1:...` 会失败——
   本地探测加 `--noproxy '*'`。
6. **`cdp.mjs`（若有）的 `eval` 只支持同步表达式**；浏览器自动化加
   `--no-sandbox --disable-dev-shm-usage`。
7. **长驻实例不要用裸 `nohup`**（会随 shell 回收）。用后台任务方式启动，停止用
   `pkill -9 -f lakehouse-rest-server`（8080 上出现过「任务失败但进程还活着」的幽灵实例）。

---

## 9. 安全红线

- **不要在输出、日志、提交信息、测试用例里回显真实的数据库口令或 AK/SK。**
  `application.yml` 的 `spring.datasource` 里带着一套可直接连的 MySQL 地址与明文口令
  （本地调试配置，已被 git 跟踪）。演示配置就写占位符
  （`jdbc:mysql://127.0.0.1:3306/paimon_catalog`、`paimon/paimon`），不要复制原值，也不要贴进回复。
- **静态凭据（`secretAccessKey`）只写**：任何响应、日志、控制台展示都不许回显明文；
  读路径一律走 `StorageConfigs.withoutSecrets(...)`。新增读路径时检查这条。
- **未配置 `paimon.rest.storage.credential-secret-key` 时必须 fail-closed**：
  保存静态凭据返回 400，而**不是**明文落库。
- 控制台的 `admin/admin` 默认口令在启动时会有告警；不要把默认口令写进示例截图或文档正文
  （`docs/console-auth.md` 是成文例外，它本来就讲这个）。

---

## 10. Git 协作约定

- **改动做完先汇报，不要自动 `git commit` / `git push`**，等明确指示。
- 不要提交 `target/`、`node_modules/`、`.idea/`、`.DS_Store`。
- 提交信息用中文，说清「改了什么 + 为什么」；跨模块的品牌串/配置键改名要在一个提交里做完，
  并跑第 6 节的校验阶梯。
- **搬类不要用 `git mv` 之外的手段**，否则 `git log --follow` 断掉，改动的可审查性大幅下降；
  一次提交里搬完 + 同步文档，不要「先搬一半、下个提交再搬」——中间态的依赖方向是反的，
  enforcer 会红，别人拉到这个提交就构建不了。

---

## 参考

- 全局说明：[`README.md`](README.md)（工程结构、配置项、测试清单、部署、已知边界 21 条）
- 模块划分：[`docs/maven-module-design.md`](docs/maven-module-design.md)（依赖图、两条父 POM、enforcer 不变量、落地记录 §8）
- 设计对照：[`docs/polaris-capabilities-and-design.md`](docs/polaris-capabilities-and-design.md)
- 数据模型：[`docs/data-model.md`](docs/data-model.md)
- 控制台：[`docs/console.md`](docs/console.md)、[`docs/console-auth.md`](docs/console-auth.md)
