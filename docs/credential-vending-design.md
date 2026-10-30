# 存储凭据下发（Credential Vending）：现状、缺口与七种存储的落地设计

> **状态：P0 已实现、P1 部分实现（P1-7 两个权限点、P1-8 打码与哨兵、P1-9 服务端取票、P1-12 COS 落地、P1-6 控制台标记（列表 / 详情 / 表单预览）已做；P1-13 经核查作废，见 v6/v7/v8 修订块与 §8、§10.8）；P2 做了 P2-1（singleflight + 限流退避）、P2-2（身份分层开关）、P2-3（凭据作废入口），P2-4 明确不做（§11.2），P2-5 只给算式（§9.2）。** 本文回答三个问题：① Gravitino 与 Polaris 都做了 Credential
> Vending，**本工程有没有考虑、还差哪些功能**；② AWS / Azure / GCS / 阿里云 OSS / 华为云 OBS /
> 腾讯云 COS 与 S3 兼容存储**分别怎么落地**；③ Spark 访问 Catalog 时**能不能不把 AK/SK 写进配置**。
> §10 另外回答第四个：**控制台上怎么配**（附交互原型）。
>
> 与本工程其它文档的关系：端点清单看 [`catalog-api-endpoints.md`](catalog-api-endpoints.md) 与
> [`lance-rest-catalog-design.md`](lance-rest-catalog-design.md) §7；与 Polaris 的整体对照看
> [`polaris-capabilities-and-design.md`](polaris-capabilities-and-design.md) §9 的「③ 凭据下发的三处加固」
> （那三条是本文的起点，本文把它展开成可落地的设计）；**行过滤 / 列脱敏在凭据下发之后会失效**这条硬边界
> 见 [`row-filter-column-mask-design.md`](row-filter-column-mask-design.md)，本文 §9.5 只做衔接。
>
> **本次修订（v2）。** 读完 Gravitino 2026 年 7–10 月 `vended` 相关 PR 之后，改掉了三处判断、补了四处缺口：
>
> 1. **§5.8 的 `credential.mode` 开关不成立**——Gravitino 用「凭据自带的 `expireTimeInMs()` 是否为 `0`」
>    表达「静态 / 短期」，而不是一个配置开关（§3.4.1）。P0-1 因此改写。
> 2. **补两个权限点与打码回写**（G13 / G14）。这两条是 #13539 直接点出来的，我们一处都没有。
> 3. **补 Flink 面**（§7.6）。原稿只有三条协议面，没有引擎维度；#14032 证明 Flink 在非 REST 后端上
>    会静默拿到「什么也没发」的凭据。
> 4. 另加三处衔接：联邦时 header 要透传（#12950，§4 的 G17）、服务端自身 FileIO 是第二个凭据消费者
>    （#11853，§5.7）、ADLS 多路径归一（#13928，§5.5）。
>
> 新增 **§10 控制台交互**与配套原型 [`credential-vending-mockup.html`](credential-vending-mockup.html)。
>
> **本次修订（v3）。** 补齐 Polaris 全量 `vended` PR 与 Gravitino `page=2` 之后，再改四处：
>
> 1. **G12 从「待证」变「已证」**——Gravitino #10895 给出了 Lance `storage_options` 的确切键名
>    （`aws_access_key_id` / `aws_secret_access_key` / `aws_session_token` / `expires_at_millis`），
>    并确认它是**按请求、按 READ/WRITE privilege 现签**（§3.5.1、§7.4）。
> 2. **§5.8 的「无 STS 退化」与 Polaris 分歧**——Polaris #2672 的 `stsUnavailable=true` 是「**不 vend
>    任何凭据**，只发非凭据属性」，而不是我们写的「退回转发静态密钥」。两家的选择不一样，本文明确
>    表态并给出分叉条件（§3.5.2、§5.8）。
> 3. **补 S3 兼容存储的第二个坑**——VAST 等实现不支持 inline session policy，AssumeRole 直接 501，
>    需要一个 `noInlinePolicy` 选项（Polaris #5425，§3.5.3、§6.2）。
> 4. **Azure 键名从三种形态变四种**，并补 per-account/container 的 SAS 铸法、bare 键只在单账户时发、
>    以及 Azure/GCS 的 skip flags（Polaris #4991/#5578/#5884，§3.5.4、§6、§10.2）。
>
> **本次修订（v4）：腾讯云 COS 从「候选」改为「正式支持」，且定为 COSN 原生。**
>
> 1. **`storageType` 从六种变七种**，加 `COS`（§6.1、§10.2）。
> 2. **键名族定为 `fs.cosn.*`，不是 `s3.*`**。Paimon 有官方的 `COSNFileIO`
>    （`paimon-cosn` 构件，URI scheme `cosn://`），它**只认 `fs.cosn.` 前缀**且键名大小写敏感
>    （源码里专门做了大小写还原，因为 Hadoop `Configuration` 会把 key 小写化）。
>    三件套是 `fs.cosn.userinfo.secretId` / `fs.cosn.userinfo.secretKey` /
>    `fs.cosn.userinfo.sessionToken`（§5.3、§6.3）。
> 3. **COSN 原生只在 Paimon 面成立**，另两面各有代价：Iceberg **没有 COS FileIO**，只能
>    `HadoopFileIO` + `fs.cosn.*`，而 `HadoopFileIO` **不吃** `storage-credentials` 的 `config`
>    → 凭据下发在 Iceberg 面是**断的**；Lance 是 Rust（`object_store`），**没有任何 COSN 实现**，
>    只能退回 S3 兼容（`aws_*` 键 + COS 的 S3 端点）。三条结论在 §6.3 与 §7.4。
> 4. **临时凭据有一个静默失效点**：COSN 的默认 provider 链里 `SimpleCredentialProvider` 排在
>    `SessionTokenCredentialProvider` **之前**，配了 `sessionToken` 却不显式设
>    `fs.cosn.credentials.provider`，会**静默用长期密钥签名**（token 被忽略）。与 G4（S3 不发
>    `s3.session-token`）是同一类问题，列为 **G18**（§4）。
>
> **本次修订（v5）：P0 已落地（代码在 `lakehouse-rest-storage` / 两个协议面），默认不启用 STS。**
>
> 1. **`VendedStorageCredential` 多两个分量**：`provider`（`relay` / `s3-token`）与
>    `expireTimeInMs`（静态 `0`、短期真实到期毫秒）。保留 4 参构造器，既有调用点零改动。
>    `shortLived()` 与 `remainingMillis(now)` 由它派生——**「静态还是短期」现在只有一个判据**。
> 2. **新增 `CredentialProvider` 链**，默认**空**（等价于只有 `relay`）。启用方式：
>    `lakehouse.rest.credential.providers: s3-token`。写了不认识的名字**启动即失败**
>    （与 `credential-manager.type` 同一取舍）。链只在 S3 分支上尝试，且插在
>    「静态凭据 / `stsUnavailable`」之后、「具名存储 / 默认配置 / 环境」之前——
>    前两者是调用方的明确决定，策略不能覆盖决定。
> 3. **`S3TokenCredentialProvider`**：`CredentialScope`（从表 location 派生三组路径）+
>    `S3SessionPolicy`（三段式 policy，`ListBucket` 的 `Resource` 是桶、前缀走 `s3:prefix`）+
>    `AssumeRoleClient`（AWS SDK v2 `sts` + `url-connection-client`，客户端按「区域 + 端点」缓存）。
>    `RoleSessionName` = `lh-<principal>-<tableId>`，按 STS 的字符集规整。
> 4. **`StorageCredentialCache` 拆成两个过期时刻**：`expiresAtMillis`（凭据寿命，进协议响应）
>    与 `cacheUntilMillis`（还能复用多久）。短期凭据按 `ttl × 0.15` 缩短，**静态凭据仍满 TTL**
>    ——它没有「快过期」这回事，缩短只会让转发档每次读表都重解析配置。
> 5. **Iceberg 面**：`loadTable` 的 `config` 在短期凭据时给
>    `client.refresh-credentials-endpoint`（相对路径，层级用 `%1F` 编码）；
>    `/credentials` **同时输出 `storage-credentials` 与 `credentials`**（G5 的双字段名）。
> 6. **Lance 面**：`storage_options` 由 `s3.*` 译成 `aws_access_key_id` /
>    `aws_secret_access_key` / `aws_session_token` / `aws_region` / `aws_endpoint`（G12），
>    `expires_at_millis` 优先取凭据自己的寿命。
> 7. **未做的（仍是 P1）**：Azure SAS / OSS / OBS / GCS 的签发、两个权限点、
>    服务端自身 FileIO 独立取票、COS 落地。§8 的 P0 行已标 ✅，P1 行保持原状。
>    （**v6 补上「服务端自身 FileIO 独立取票 / P1-9」、v7 补上「COS 落地 / P1-12」、
>    v8 补上「AK ID 打码与回写哨兵 / P1-8」；P1-13 经核查作废——COS 的 FileIO
>    没有临时凭据入口**，其余仍按原状。）
>
> **本次修订（v6）：P1-9 落地——服务端自己也按「catalog 的允许位置」取票。**
>
> 起因是 §5.7 那条「服务端也是凭据消费者」：`StorageFileIoFactory` 虽然自己取票（没复用用户的票），
> 但作用域是**被访问的那个路径**，而且 `allowWrite` 恒为 `false`。接上 STS 之后的两个后果分别是
> 「列目录 / 删表目录 403」与「写元数据 403」，两者都报在存储层、与 catalog 无关。
>
> 1. **作用域分两类，判据是「谁在用」**：引擎的票按「用户 + 表」签（`vend`）；服务端自己的票按
>    catalog 的整份 `allowedLocations` 签（`vendForCatalog`）。`CredentialContext` 因此从「一个路径」
>    变成「一组位置」，`CredentialScope.resolveAll` 按 `bucket + prefix` 去重，跨桶合法（S3 策略能表达）。
> 2. **`S3SessionPolicy` 支持多作用域，但单作用域的输出逐字节不变**：多个位置合成一条语句
>    （`Resource` 与 `s3:prefix` 都接受数组），理由是 policy 有 2048 字符内联上限，
>    逐个位置叠语句会先撞 `PolicyTooLarge`。
> 3. **写权限显式传**：`StorageFileAccess` 的 `write` / `delete` / `deleteDirectory` 走可写票，
>    其余读；`TableMetadataService`（物化 schema / snapshot）用**表级可写票**，不放大到 catalog 级。
> 4. **缓存键必须分开**：`StorageCredentialCache.catalogKey` 带 `catalog|` 前缀，且读写各一份。
>    共用的后果是「用户先读表、服务端随后删目录，服务端拿到只覆盖表的票」——删到一半 403。
> 5. **只缓存短期票**：静态转发档仍每次重解析配置（换钥匙立刻生效），短期票必须缓存，
>    否则浏览一次目录就是十几次 STS 调用。
>
> 用例：`ServerSideCredentialTests`（9 例：作用域覆盖 / 跨桶 / 写语句 / 会话名 / 认不出位置时拒绝签 /
> 空位置 / 默认链不签 / 缓存键不撞 / 短期缓存且静态不缓存）。
>
> **仍未验的还是那一半**：`AssumeRole` 真实调用与「只配 `roleArn` 的 catalog 上优化任务能跑通」
> 需要真实 S3，用例只断言了传下去的命令与缓存行为（同 P0-8 的偏差）。
>
> **本次修订（v7）：COS 落地（P1-12），同时作废 P1-13——COS 走不了临时凭据。**
>
> 1. **COS 的服务端实现已就位**：`StorageType.COS`、`TencentCosStorageConfigInfo`、
>    `FileIoType.COS("cosn")`（对外取值就是 Paimon 的 FileIO scheme）、
>    `fs.cosn.*` 键族、`lakehouse.rest.storage.cos` 配置组、`paimon-cosn` 依赖
>    （含一个 99MB 的 `paimon-plugin-cosn` 子目录，见 POM 注释）、控制台表单与三面提示。
> 2. **两处字段语义按验证结果收窄**，与 v4 写的不同：
>    - `endpoint` **只表示 COSN 的 `fs.cosn.bucket.endpoint_suffix`**，不再兼作「给 Lance 用的
>      S3 兼容端点」。一个字段被两种实现各按自己的语义解读，配置读起来是对的、跑起来才是错的；
>     要用 Lance 请另建 S3 类型 catalog。
>    - 新增 `useHttps`（缺省 `true`）。COSN 自己的缺省是 `fs.cosn.useHttps=false`，即
>      **不配就走明文**；本工程把这个默认值翻过来，并留一个可以显式关掉的出口。
>    - 新增 `appId`，但它**不是下发键**（COS 的 appId 是桶名的一部分），只用于写入口校验
>      「位置里的桶名确实以 `-<appId>` 结尾」。填错时不校验的话，第一次读表才 404。
> 3. **勘误（重要）：§6.3.4 的 G18 前提不成立，P1-13 作废。** 直接读了
>    `paimon-cosn-2.1.0.jar` 而不是照抄文档，三条证据：
>    - `CosNConfigKeys` 里**没有** `sessionToken` 这一项（只有 `secretId` / `secretKey` / `region` /
>      `endpoint_suffix` / `useHttps` / `credentials.provider` 等）；
>    - 认证包 `org.apache.hadoop.fs.cosn.auth` 只带 `SimpleCredentialsProvider` 与
>      `EnvironmentVariableCredentialsProvider`，**没有** `SessionTokenCredentialProvider`；
>    - 在整个 3 万条目的 jar 里搜 `sessionToken` / `BasicSessionCredentials`：`org/apache/hadoop/fs/cosn/**`
>      下零命中。
>
>    所以 v4 写的 `fs.cosn.credentials.provider=org.apache.hadoop.fs.auth.SessionTokenCredentialProvider`
>    **这个类名根本不存在**（连包名都不同：是 `fs.cosn.auth`，不是 `fs.auth`）。发它是往
>    `CosNUtils` 的反射实例化里塞一个不存在的类，结果是启动 FileIO 就抛
>    「… is not cos credential provider」。结论：**COS 在这条链上只能转发长期 AK/SK**
>    （或让引擎用自己的 CVM 委托身份），「服务端签一张短期票给 COSN」这条路走不通——
>    这不是我们没做，是上游 FileIO 没有这个入口。因此：
>    - 不下发 `fs.cosn.userinfo.sessionToken`，也不下发 `fs.cosn.credentials.provider`；
>    - `lakehouse.rest.storage.cos` 配了 `sessionToken` 时**直接 500**（`putSecrets` 里判断
>      「这一族有没有令牌键」），而不是静默丢掉或发一份缺令牌的凭据。
> 4. **用例**：`StorageCredentialResolutionTests` 新增 7 例（键名大小写 / `useHttps` 双态 /
>    不下发令牌与 provider 类名 / 配了令牌就失败 / `stsUnavailable` 保留定位 / 环境回退 /
>    具名存储优先与找不到即失败）、`StorageConfigDtosTests` 新增 2 例（字段往返含 `useHttps=false`
>    这种「显式的否」、`appId` 与桶名比对）、`StaticCredentialApiTests` 把 COS 并入静态凭据的
>    HTTP 语义、`ConsoleApiTests` 同步取值集合。
> 5. **未验**：`cosn://` 的端到端（「不配 AK 也能读」需要真实 COS 账号）。键名这一层已由用例
>    钉住，端到端这一层没有——与 P0-8 同类的偏差，别把它当「已验完」。

> **本次修订（v8）：AK ID 打码 + 回写哨兵（P1-8），G14 关闭。**
>
> 1. **打码只有一个出口**：`StorageConfigs#withoutSecrets` 把 `accessKeyId` 换成
>    `StorageConfigs.MASKED`（六个星号）。列表与详情都经 `ManagementCatalogService#toDto`
>    这同一处，所以不会出现「列表星号、详情真值」；以后新加的响应路径也必须走它，
>    绕过去的写法会静默漏一个。
> 2. **回写认哨兵**：`mergeStaticCredentials` 见到 `******` 按「沿用库里那份」处理。
>    规则表见 §3.4.4，四条里有两条是新语义（「只改名字」与「只换密钥」），
>    另两条是把旧的错法挡在门外（库里没有原值时 400；把星号当密钥 400）。
> 3. **打码那份进不了下发通道**：`staticCredentials` 见到 `******` 直接 500。它只会在
>    「把对外响应当成内部配置」时出现——放过去的表现是引擎拿六个星号签名，
>    报错落在存储侧认证失败上，与真正的原因完全脱钩。
> 4. **`******` 与空串不是一回事**，这条守不住就会静默删凭据：打码是「有值但不告诉你」，
>    空串是「这里不要值了」。因此 `******` + 空密钥按「两个都不变」处理，
>    清除必须显式提交一对空串（控制台的「移除已保存的静态凭据」正是这么发的）。
> 5. **范围**：只覆盖 catalog 的 `storageConfigInfo`。执行集群（`ExecutionClusterDtos`）
>    的 `accessKeyId` 仍回显，那是另一套凭据（kubeconfig / EKS）、另一条契约，
>    它的注释与用例都明确写着「AK ID 不保密、用于认钥匙」。**两处口径不同是有意的**，
>    别照着一边去改另一边——真要统一，得先动那条契约。
> 6. **用例**：`StaticCredentialApiTests` 新增 5 例（打码回传 + 省略密钥 / 只改名字 /
>    只换密钥 / 新建就给哨兵 400 / 把哨兵当密钥 400），并把原有三处
>    「AK ID 回显」断言改成断言打码；`StorageCredentialResolutionTests` 的脱敏单测
>    同步改名改断言（`maskingReplacesTheKeyIdAndDropsTheSecret`），并加一条
>    `vendingAMaskedConfigFailsLoudly` 盯住上面第 3 条护栏。


---

## 0. 结论速览

### 0.1 三个问题各一句话（外加「控制台怎么配」）

| 问题 | 一句话回答 |
| --- | --- |
| ① 有没有考虑 | **有，P0 已落地**：三条协议面的下发通道、七种存储的键名族、TTL 缓存、静态凭据的加密落库都在；**S3 的「向云申请短期凭据」也已接通**（`s3-token` provider + session policy 收窄），但**默认关闭**（`lakehouse.rest.credential.providers` 为空）。其余五家的签发器仍是 P1（§8） |
| ② 七种存储 | S3 / OSS / OBS / COS 走 STS `AssumeRole`（OSS、OBS、COS 各有自己的接口与 policy 收窄方式，COS 用 CAM policy），Azure 走 User Delegation SAS，GCS 走 downscoped token，**S3 兼容存储（MinIO / Ceph / Ozone）多数没有 STS，必须保留现在的静态转发**。逐种见 §6；腾讯云 COS 定案用 **COSN 原生**，见 §6.3。**COS 的 `AssumeRole` 那半句已被 v7 推翻**：COSN 的 FileIO 不认临时凭据（没有令牌键、没有 session-token provider），所以 COS 只能转发长期 AK/SK |
| ③ Spark 能不能不配 AK/SK | **Paimon 能（现在就能）、Iceberg 能但有三处卡点、Lance 今天不能**；Flink 只有 Paimon 面能，且没验过。逐条结论与那段配置的逐行点评见 §7。**COS 要单独看**：只有 Paimon 面能用 COSN 原生接到下发凭据、Iceberg 面下发断链、Lance 面根本没有 COSN（§6.3） |
| ④ 控制台怎么配 | **三选一，不是一个开关**：不下发 / 转发长期密钥 / 签发短期凭据。后两档的客户端配置**完全一样**，所以标记必须做在控制台上。见 §10 与原型 [`credential-vending-mockup.html`](credential-vending-mockup.html) |

### 0.2 「凭据转发」与「凭据下发」的区别（本文最重要的一句话）

两者在报文上长得几乎一样，安全属性完全不同：

| | 凭据转发（现状） | 凭据下发（目标） |
| --- | --- | --- |
| 密钥从哪来 | 服务端配置 / catalog 配置里的长期 AK/SK | 服务端拿自己的身份去云的 STS 现签 |
| 密钥寿命 | **永久**（直到有人轮换） | 15 分钟 – 12 小时，由云决定 |
| 作用域 | catalog 的整个 bucket | 单表路径 + 读写分离 |
| 泄漏后 | 永久可用，只能轮换密钥并停机 | 到期自然失效，无需任何人去撤销 |
| 我们的 `ttlSeconds` 是什么 | 建议重取时间（元数据） | 凭据本身的寿命（事实） |
| 缓存/过期的意义 | 只是少读几次配置 | 决定引擎能不能继续读 |

Gravitino 自己也踩过这个坑：它的 `s3-secret-key` provider 只是把配置里的静态密钥转发出去
（`S3SecretKeyCredential.expireTimeInMs()` 返回 `0`），社区在评审文档时专门要求把「临时凭据」的措辞
改掉——**只有 `s3-token`（STS `AssumeRole`）才是真正的 credential vending**。我们现在的位置就是
Gravitino 的 `s3-secret-key`，不是 `s3-token`。而那个 `0` 后来成了整套设计的关键：它把「静态 / 短期」
从配置约定变成了响应事实（§3.4.1）。

---

## 1. 为什么这件事迟早要做

文章把来龙去脉讲得很清楚，这里只留与本工程相关的三条。

**Hadoop 的三个前提迁到云上全失效。** Kerberos 绑 JVM（DuckDB / Polars / Daft 不在 JVM 里）；
对象存储没有 NameNode，不认 Delegation Token；于是「把长期 AK/SK 写进 Spark 配置」成了默认解。
三类过渡方案（静态 AK/SK、IAM Role + Instance Profile、Bucket Policy）**权限粒度都停在 bucket**，
而数据治理的单位是 schema / table / column——两套语言对不上，治理只能施行到应用层，靠「相信引擎」。
那篇文档引的 Capital One 事故（1 亿用户 PII、根因是 IAM Role 覆盖整个 bucket）就是「相信」的代价。

**catalog 是唯一收得回执行点的地方。** 权限检查绕不过去，就把执行点收回来：引擎用自己的身份向
catalog 要数据访问权，catalog 判权之后再去云上换一张**只覆盖这张表**的票。catalog 于是从
「元数据 store」变成「访问控制平面」——本工程的定位正好是后者，这件事对我们不是可选项。

**代价是 catalog 变成 critical path。** 所有引擎每次读表都要先过它。这条在 §9 单独讲，
它是「要不要做」之外唯一真正需要拍板的事。

---

## 2. 现状：本工程已经有什么

先盘清家底，避免重造。**以下都是已落地代码，不是设计。**

### 2.1 三条下发通道（一个管理器，三种协议形状）

| 协议面 | 端点 | 触发条件 | 响应形状 | 客户端行为 |
| --- | --- | --- | --- | --- |
| Paimon | `GET /v1/{prefix}/databases/{db}/tables/{t}/token` | 客户端开 `data-token.enabled=true` 时**按需**调 | `{token:{...}, expiresAt, expiresAtMillis}` | `RESTTokenFileIO` 在剩余寿命不足时**重调本端点**（就是它的续期机制） |
| Iceberg | `GET /v1/{prefix}/namespaces/{ns}/tables/{t}/credentials` | 客户端显式调（规格里的 `loadCredentials`） | `{storage-credentials:[{prefix,config}]}` | — |
| Iceberg | `loadTable` 响应里的 `storage-credentials` | **仅当** `X-Iceberg-Access-Delegation: vended-credentials` | 同上 | `S3FileIO` 按 `prefix` 给每个路径段单独建一个 client（`clientByPrefix()`），`prefix` 要满足 `startsWith("s3")`（`ROOT_PREFIX` 就是字面量 `"s3"`）才会被采用 |
| Lance | `POST /lance/v1/table/{id}/describe?vend_credentials=true`（`declare` / `rename` 同） | 查询参数 | `storage_options` + `expires_at_millis` | connector 0.8.0 把它读成**一份静态 map**（见 §7.4） |

三个入口最后都落到同一对组件上：`StorageRuntimePolicy` 选出的 `StorageCredentialManager`
（`DefaultStorageCredentialManager`）与 `StorageCredentialCache`。**键名族、密钥从哪一级取、
缓存与过期时刻的算法都只有一份**——这是当初「表统一」的直接收益，也是现在加 STS 只需要改一处的
原因。

### 2.2 七种存储现在发的键

`DefaultStorageCredentialManager` 的 `vend()` 按 `storageType` 分派，产出各引擎 FileIO 真实认的键：

| storageType | 定位键 | 密钥键 | 过期相关键 | 当前来源 |
| --- | --- | --- | --- | --- |
| `S3` | `s3.region`、`s3.endpoint`、`s3.path-style-access` | `s3.access-key-id` + `s3.secret-access-key`（规格名）**与** `s3.access-key` + `s3.secret-key`（Paimon 引擎名） | **无** | `stsUnavailable` → `s3-token` 签发 → catalog 上的 AK/SK → 环境凭据链 |
| `AZURE` | `azure.tenant-id`、`azure.account`、`azure.multi-tenant-app-name`、`azure.consent-url`、`azure.hierarchical` | 启用 `azure-sas` 时有 `adls.sas-token*`；服务主体**永不下发** | `adls.sas-token-expires-at-ms` | 只下发定位元数据（服务主体只用于签发） |
| `GCS` | `gcs.service-account` | 启用 `gcs-downscope` 时有 `gcs.oauth2.token`；`serviceAccountJson` **永不下发** | `gcs.oauth2.token-expires-at` | 没有服务账号 → 引擎的 ADC；有则换票 |
| `OBS` | `fs.obs.endpoint` | `fs.obs.access.key` + `fs.obs.secret.key`（catalog 上的长期凭据，没有令牌可发） | 无 | catalog 上的 AK/SK → 环境凭据链 |
| `OSS` | `fs.oss.endpoint` | `fs.oss.accessKeyId` + `fs.oss.accessKeySecret`（启用 `oss-sts` 时另加 `fs.oss.securityToken`） | STS 票自带到期 | `stsUnavailable` → `oss-sts` 签发 → catalog 上的 AK/SK → 环境链 |
| `FILE` | — | 自签随机串（`PAIMON-<tableId>` / 随机 secret / 随机 token） | `expiration`（ISO8601）+ `tablePath` | 本地文件系统，仅审计用 |
| `COS` | `fs.cosn.bucket.region`、`fs.cosn.bucket.endpoint_suffix`、`fs.cosn.useHttps` | `fs.cosn.userinfo.secretId` + `fs.cosn.userinfo.secretKey` | **无**（这一族没有令牌键，§6.3.4） | 四级来源同 S3，但**没有 STS 签发那一级** |

> **配置面后记（2026-10-30）**：本表原先的「四级来源」里有三级来自服务端配置
> （默认凭据 / 具名存储 / `gcp.token`）。那份配置已经整个删掉：**对象存储凭据一律写在
> catalog 的 `storageConfigInfo` 上**（S3/OBS/OSS/COS 的 AK/SK、Azure 的
> `clientId`+`clientSecret`+`authority`+`scope`、GCS 的 `serviceAccountJson`、
> OSS 的 `roleArn`+`stsEndpoint`），以 `credential-secret-key` 加密落库、对外只回 `******`。
> 理由：每个 catalog 的存储配置本来就不一样，而全局配置只能靠 `storageName` 这个额外字段
> 区分「这次该用哪把钥匙」——它只是把问题挪了个位置。
> 因此下文 §5.5 / §6 里凡是写「`lakehouse.rest.storage.aws.access-key`」
> 「`lakehouse.rest.storage.azure.*`」「`.oss.role-arn`」「`.gcp.token`」的段落，
> 都应读作**「catalog 上对应的字段」**；P1-1 / P1-2 / P1-4 / P1-12 四行里的
> 「身份放在服务端配置」也已改为从 catalog 读。行为上唯一新增的一条：
> 配了 `roleArn` 时，catalog 上那对 AK/SK 是**服务端据以 assume 的身份**
> （不是转发给引擎的钥匙），所以「静态凭据与 `roleArn` 互斥」这条 400 规则被删除了。

> 本节是**现状**——七种，COS 已按 v7 落地。COS 的键名族见 §6.3.1，
> 与 OBS / OSS 同构的只有「四级来源」这层结构；**令牌那一列是空的**，
> 且这一列不能靠加一个键补上（§6.3.4 的证据）。

两处容易误读、但已经写死在代码注释里的取舍，值得在这里重述：

- **S3 必须发两套键名。** Paimon 的 `paimon-s3` 把 `s3.*` 按前缀翻译成 `fs.s3a.*`，再做一次小范围
  镜像（`fs.s3a.access-key → fs.s3a.access.key`）；`s3.access-key-id` 只会变成
  `fs.s3a.access-key-id`——Hadoop 不认识这个键，密钥被**静默丢弃**，最后报
  `NoAuthWithAWSException`，而该异常类在插件类加载器里序列化不到 driver，Spark 上只显示
  `UnknownReason`。
- **OBS 与 OSS 的键名族不能互套。** 华为是点分隔小写 `fs.obs.access.key` / `fs.obs.session.token`，
  阿里是驼峰 `fs.oss.accessKeyId` / `fs.oss.securityToken`。两家云都兼容 S3 协议，但
  `paimon-obs` 与 `paimon-oss` 是两个独立 FileIO，谁都不认对方那套。

### 2.3 缓存与 TTL

`StorageCredentialCache`：LRU（访问序），键 = `catalogId | 整份存储配置 JSON | 表路径`，
容量 `storage-credential-cache.max-entries`（默认 10000），条目在**凭据过期时刻**自然失效
（读路径顺手删除，不用定时任务）。TTL 基准 `credential.ttl-seconds`（默认 3600）；GCS 配了
`lifespan` 时取两者较小值，且 `gcs.oauth2.token-expires-at` 与响应里的 `expiresAt` 同源。

键里放**整份配置 JSON** 而不是只放存储类型，是为了让「改了 S3 端点」这类修改在缓存过期前不生效
——漏改的代价是命中率下降，不会返回错误结果。

### 2.4 catalog 级静态凭据

`AwsStorageConfigInfo` / `*StorageConfigInfo` 上有 `accessKeyId` / `secretAccessKey`，落库前经
`CredentialCipher`（AES-256-GCM，密文带 `v1:` 前缀）加密，密钥来自
`lakehouse.rest.storage.credential-secret-key`（`openssl rand -base64 32`）。**未配置密钥时写路径
直接 400**，不「先明文存下来以后再说」。解密失败直接 500，不退回服务端默认凭据——退回等于把
「这张表该用哪把钥匙」从调用方明确指定降级成部署方默认，会把配置变更掩盖成权限问题。

### 2.5 两个开关

- `credential-manager.type`：`default` / `noop`。`noop` 恒拒（501），用来表达「本部署不提供该能力」，
  与「回一个空的成功响应」明确区分——后者会让引擎在真正读数据时才失败，错误现场离原因很远。
- `stsUnavailable`（每种存储都有）：规格语义是「服务端不再为该 catalog 下发凭据」。现在的执行是
  **仍然下发定位配置，但不给密钥**，由引擎用自己的身份访问。**catalog 静态凭据排在这个开关之前**
  ——否则兼容 S3（通常没有 STS，配置里照惯例写 `stsUnavailable: true`）会变成「填了密钥却收不到密钥」。

### 2.6 现状的一句话总结

> 我们做完了「协议形状」与「键名族」；S3 的「签发」在 P0 里接通了（默认关闭），
> 其余五家的签发器还在 P1。

也就是说：**报文对了、客户端能吃了、S3 也能换票了**——但默认仍是转发长期密钥，
因为「把一条会调云的路径默认打开」不是一个可以随手做的变更（见 v5 修订块第 2 条）。
与 Polaris / Gravitino 的差距，从「接口与签发器都没有」缩小到「签发器只有一家、默认关闭」。

---

## 3. 对标：Gravitino 与 Polaris 分别怎么做

### 3.1 Gravitino：三层抽象 + 按 URI scheme 路由

```java
interface Credential {                     // 结果：类型 + 过期时刻 + 键值
  String credentialType();  long expireTimeInMs();  Map<String,String> credentialInfo();
}
interface CredentialGenerator<T extends Credential> {   // 真正调云 API 的那一层
  void initialize(Map<String,String> props);  T generate(CredentialContext ctx);
}
interface CredentialProvider {             // 暴露给上层
  boolean supportsScheme(String scheme);   // s3a / gs / abfss …
  String credentialType();  Credential getCredential(CredentialContext ctx);
}
```

`CredentialContext` 带着「读哪些路径、写哪些路径、是哪个用户」（S3 是
`PathBasedCredentialContext{readPaths, writePaths, userName}`）。Catalog 每次 `loadTable` 从表的
location 里取 URI scheme，**路由到唯一一个认领该 scheme 的 provider**，上层不需要知道底下是哪家云。

三家云的实现彼此完全不同，但都实现 `CredentialGenerator<T>`：

| provider | 调什么 | 产出 |
| --- | --- | --- |
| `s3-token` | `StsClient.assumeRole(roleArn, sessionName, durationSeconds, policy)` | AK/SK/sessionToken/expiration |
| `aws-irsa` | 用 Pod 的 web identity（`AWS_ROLE_ARN` / `AWS_WEB_IDENTITY_TOKEN_FILE`）换 STS，再按路径现生成 policy | 同上四件套；**catalog 级请求不带 policy**（#13928） |
| `gcs-token` | `DownscopedCredentials.refreshAccessToken(CredentialAccessBoundary)` | 降权后的 access token |
| `adls-token` | `DataLakeServiceClient.getUserDelegationKey(...)` → 拼 SAS | SAS token |
| ~~`cos-token`~~ | 腾讯云 STS `AssumeRole` + CAM policy | AK/SK/sessionToken/expiration（#12199）——**Gravitino 有，我们不做**：COSN 的 FileIO 没有令牌入口，签了没人消费（§6.3.4） |
| `azure-account-key` / `s3-secret-key` / `oss-secret-key` | 什么都不调 | **配置里的静态密钥**（`expireTimeInMs()=0`） |

到 2026 年 10 月这个名单已经有八个以上（`aws-secret-key`、`dlf-secret-key` 也各成一个 provider）。
规律是**每家一对**：`X-token` 签短期、`X-secret-key` 转发静态——不是一个 provider 内部两种模式（§3.4）。

S3 的 session policy 写法（简化）：`s3:GetObject` / `s3:GetObjectVersion` 的 Resource 限定到表路径，
`ListBucket` 用 `s3:prefix` 条件限定到同一前缀，另加 `s3:GetBucketLocation`；`PutObject` /
`DeleteObject` **只对 writePaths**。session policy 与 role 自身策略是**交集**，只能收窄不能放大。

缓存上它用「按剩余寿命的比例失效」：`DEFAULT_CREDENTIAL_CACHE_EXPIRE_RATIO = 0.15`——1 小时的 token
缓存只留 9 分钟。理由很实在：缓存到快过期才给出去，引擎拿到手用几秒就 403。代价是命中率下降、
STS 调用变多（AWS STS 单账号约 250 TPS）。

### 3.2 Polaris：三个 IAM 身份 + 键名参考页

Polaris 把「谁在签名」拆得很清楚，三份身份混用是 `AccessDenied` 的头号来源：

| # | 身份 | 谁用 | 干什么 |
| --- | --- | --- | --- |
| 1 | Polaris 服务身份 | 服务进程 | 给自己的 `sts:AssumeRole` 签名 |
| 2 | catalog access role | catalog（被 assume） | 持有 bucket 的真实 S3 / KMS 权限 |
| 3 | vended credentials | Iceberg 客户端 | 表加载时下发的短期 session 凭据 |

第 2 个身份**按 catalog 建**，ARN 在建 catalog 时登记；第 3 个**每次 loadTable 现签、从不落库**。
本工程 `AwsStorageConfigInfo` 里其实已经有 `roleArn` / `externalId` / `userArn` / `stsEndpoint`
这几个字段（规格兼容），**只是没有一处代码用它们去 assume**——字段在位、逻辑缺席，这是最容易
让人误判「已经支持了」的那种状态。

Polaris 1.8 的 vended credentials 参考页给出了客户端契约（这段是本文 §5.3 键名表的依据）：

| 属性 | 类型 | 说明 |
| --- | --- | --- |
| `s3.access-key-id` / `s3.secret-access-key` / `s3.session-token` | **credential** | STS 会话凭据三件套 |
| `s3.session-token-expires-at-ms` | **credential** | 会话令牌到期时刻（毫秒） |
| `client.region` | config | S3 与 STS 的区域 |
| `client.refresh-credentials-endpoint` | config | **客户端可用来续期的地址** |
| `s3.endpoint` / `s3.path-style-access` | config | 仅 S3 兼容存储（MinIO / Ozone）才出现 |
| `adls.sas-token.<account>.dfs.core.windows.net` 等三种形态 | credential | Azure：按 DNS / 按账户 / 裸 SAS，分别给不同客户端世代用 |

Azure 走 User Delegation SAS，**有效期上限是 7 天**（平台限制，不是 Polaris 的选择）。1.4 起
STS 带 session tags，CloudTrail 能按主体/表归因——`polaris-capabilities-and-design.md` 把它列为
我们的第三条缺口。

### 3.3 三家摆一起

| 维度 | Gravitino | Polaris | 本工程 |
| --- | --- | --- | --- |
| 按什么选签发器 | URI scheme（`s3a`/`gs`/`abfss`） | catalog 的存储类型 + 配置 | `storageType` 枚举（设计含七种，COS 见 §6.3） |
| S3 短期凭据 | `s3-token`（AssumeRole + session policy） | AssumeRole + inline session policy | **无**（只有静态转发） |
| S3 兼容存储 | `s3-secret-key`（明确标注是静态） | 同 | 静态转发（默认路径） |
| Azure | User Delegation SAS / account key | User Delegation SAS（≤7 天） | **只发定位信息** |
| GCS | downscoped token | downscoped token | 配置里的静态 token |
| OSS / OBS | `oss-token` / 静态 | 未在参考页列出 | 静态转发 |
| 续期 | 引擎侧自动（client 侧改造） | `client.refresh-credentials-endpoint` + `VendedCredentialsProvider` | **没有 refresh 广告** |
| 审计归因 | userName 进 session policy / session name | session tags（1.4+） | 无 |
| 缓存失效 | 剩余寿命 × 0.15 | 缓存键含 principal / 读写位置 / session tags | 到期时刻 |

### 3.4 2026 年 7–10 月的九条改动，分别改掉了本文的哪一条

`pulls?q=vended` 一页 25 条，近一半是 7 月之后。这里不按时间排，按「它证明了什么」排——
只看会改变判断的，纯文档与测试基建的略过。

| PR | 改了什么 | 对本文的哪一条下了判决 |
| --- | --- | --- |
| [#13539](https://github.com/apache/gravitino/pull/13539)（9/30） | 权限拆成 `USE_SECRET` 与 `USE_CREDENTIAL`；`s3` / `oss` / `cos` / `aws` / `dlf` 的 access key **ID** 一并 `hidden`，读取时返回 `******`；客户端把 `getSecrets()` 与「`expireTimeInMs == 0` 的 credential」合并成一次性配置 | **§5.8 的 mode 开关不成立**（§3.4.1）；新增 G13（判权）、G14（打码与回写） |
| [#13599](https://github.com/apache/gravitino/pull/13599)（9/30） | `aws-secret-key` / `dlf-secret-key` 各自成 provider；显式给了 `credential-providers` 时**不再**自动追加 `s3-secret-key`（避免顶掉 `s3-token`） | 一家存储是**一对 provider**，不是一个 mode。另：显式列表与自动探测的优先级要想清楚（§3.4.2） |
| [#12199](https://github.com/apache/gravitino/pull/12199)（8/31） | 腾讯云 COS：`cos-token`（CAM policy 收窄）+ `cos-secret-key`，配 `cos-role-arn` / `cos-region` / `cos-app-id` / `cos-external-id` / `cos-token-expire-in-secs` | **定案接入 COS 并采用 COSN 原生**（§6.3）；CAM 的 `Policy` / `Statement` / `Effect` / `Condition` / `StringLike` 五个模型类可直接照搬 |
| [#13928](https://github.com/apache/gravitino/pull/13928)（10/19） | `aws-irsa` 允许不配 `s3-role-arn`（回落 `AWS_ROLE_ARN`）；**catalog 级请求不带 session policy**；ADLS 多路径合成一个目录级 SAS，`abfs` / `abfss` / 账户名大小写 / 尾斜杠归一 | §5.5 作用域要覆盖 `write.data.path` 与 `write.metadata.path`；§5.7 加一条「catalog 级票天生比表级宽」 |
| [#14032](https://github.com/apache/gravitino/pull/14032)（10/22） | Flink 对 **Iceberg 非 REST 后端**只在开 catalog 时取一次凭据：`aws-irsa` 直接**报错**，`s3-token` / `gcs-token` / `adls-token` / `oss-token` / `cos-token` 只 **warn**（catalog 级什么也没发，引擎静默回落到节点凭据）；文档明写「非 REST 只能拿静态，要可刷新必须 REST + `data-access=vended-credentials`」 | §7 缺 Flink 这一维度，补 §7.6 |
| [#13778](https://github.com/apache/gravitino/pull/13778)（10/27） | `loadTable` 响应里开始带 tag 选出的**行过滤 / 列脱敏**（read restrictions），并进 ETag；顺带修了快照过滤会丢掉这些字段的问题 | §9.5 的冲突不是「将来」——`loadTable` 现在同时在承载「读限制」与「凭据」两样东西 |
| [#12950](https://github.com/apache/gravitino/pull/12950)（9/9） | 联邦 IRC 的 `loadTable` 漏传 `X-Iceberg-Access-Delegation`，远端回「只有元数据」；修法是走同一条认证 REST GET，并把远端 `refresh-credentials-endpoint` **重写成近端地址** | 新增 G17：联邦时 header 要透传、refresh 端点要改写 |
| [#11853](https://github.com/apache/gravitino/pull/11853)（7/1，open） | vended credentials 也注入**服务端自己的** Iceberg catalog 配置（server-side `S3FileIO`），不只是发给客户端；`supportsCredentials()` 返回 `null` 要防御 | 补 §5.7：凭据有**两个**消费者 |
| [#12751](https://github.com/apache/gravitino/pull/12751)（9/3） | 保留字 + `hidden` 属性从 API 响应里省略 | G14 的同源问题：打码与省略要在**响应出口**统一做，别在每个 DTO 上手写一遍 |

> 官方文档同步给出了几条可直接抄的规则：`credential-cache-expire-ratio` 默认 `0.15`、
> `credential-cache-max-size` 默认 `10000`、敏感属性从 `GET /catalogs/{c}` 响应里剔除、
> 凭据只能通过 `GET /objects/catalog/{c}/credentials` 取。其中 `10000` 与我们
> `storage-credential-cache.max-entries` 的默认值相同；`0.15` 是我们打算采用的比例，
> **但还没实现**（见 P0-6）——别把它当成已有行为读。

#### 3.4.1 最重要的一条：用「到期时刻」表达语义，不要用 mode 开关

Gravitino 的客户端（`CredentialInfos`，#13539 描述）做的是这件事：

```java
Map<String, String> oneShot = new HashMap<>(secrets);        // 目录侧的非敏感配置
credentials.stream()
    .filter(c -> c.expireTimeInMs() == 0)                    // 只把「不会过期」的那种并进来
    .forEach(c -> oneShot.putAll(c.credentialInfo()));
```

也就是说：**静态密钥与短期票是同一个接口的两个实例**，区别只在 `expireTimeInMs()`——
`s3-secret-key` 返回 `0`，`s3-token` 返回真实到期毫秒。客户端因此不需要知道「当前是什么模式」，
它只看这一个数。

这比上一版写的 `credential.mode: relay | sts` 好在三处：

1. **少一个开关就少一种半截状态。** 配置开关必然会出现「开关写 sts、但配置里没有 roleArn」这种组合，
   而它只能靠校验拦；`expireTimeInMs` 是**响应的一部分**，客户端拿到的是事实，不是约定。
2. **允许多个 provider 并存。** Gravitino 刻意不让 `s3-secret-key` 顶掉 `s3-token`；
   将来「表 A 用 STS、表 B 走转发」不需要改协议、也不需要新增配置。
3. **协议适配层的判据从「配置」变成「凭据」。** 发几个键、要不要广告 refresh 端点，
   都由这一份凭据自己的到期时刻决定——不存在「配置说 sts、其实没签」的错位。

所以 P0-1 改写成：**新增 `credential-providers` 列表（默认只有一个 relay 实现），
并给 `VendedStorageCredential` 加 `expireTimeInMs()`**；§5.8 的表据此重写。

#### 3.4.2 自动探测：填了 roleArn 就升到 STS

Gravitino 对关系型 catalog **不要求**显式写 `credential-providers`，它按属性推断
（官方文档「Auto-Detection of Credential Providers」）：

| 目录里有什么 | 启用哪个 provider |
| --- | --- |
| `s3-access-key-id` + `s3-secret-access-key` | `s3-secret-key`（静态） |
| 再加上 `s3-role-arn` | **换成** `s3-token`（STS），不再是静态 |
| `oss-access-key-id` + `oss-secret-access-key` | `oss-secret-key`，有 `oss-role-arn` 则 `oss-token` |
| `jdbc-user` + `jdbc-password` | `jdbc-user-password` |

对控制台这是一条现成规则：**用户填了 Role ARN 就该看到「已升级为签发」，而不是再去找另一个开关**
（§10.3 的原型按这条做）。注意它与显式列表的优先级——Gravitino 的处理是「显式给了列表就只用列表，
但 Glue 仍会补 `aws-secret-key`、**不**补 `s3-secret-key`」。这条不对称很容易被抄错。

#### 3.4.3 为什么要两个权限点，而不是「能 / 不能取凭据」

| 权限 | 能做什么 | 不能做什么 |
| --- | --- | --- |
| `USE_SECRET` | 完整 `getSecrets()`，**含云 access key 对** | 不能调 `getCredentials()` |
| `USE_CREDENTIAL` | `getCredentials()`；`getSecrets()` 里**去掉**云 access key 对 | 拿不到目录里存的长期密钥 |

合成一个之后，「能跑查询」和「能拿走长期密钥」就成了同一件事，而这恰好是要分开的两个风险：
前者是**日常**，后者是**一次就够**。对我们，`CatalogAccessRules` 是按 `<对象>:<动作>` 拼路径判权的，
加两个点号权限即可（`CATALOG:USE_CREDENTIAL` / `CATALOG:USE_SECRET`），不需要新机制（G13）。

**落地（P1-7）分两处判，因为同一个端点可能发两种东西：**

| 步骤 | 判什么 | 在哪判 |
| --- | --- | --- |
| 1 | `USE_CREDENTIAL` | `CatalogAccessRules`：`/token` 与 `/credentials` 从 `TABLE:SELECT` 改为它 |
| 2 | `USE_SECRET`（仅当票里含长期密钥对） | 两个协议面的凭据服务，拿到凭据之后；Iceberg 的 `loadTable` 委派同样先判 `USE_CREDENTIAL`，只「不给」不报错 |
| 1+2 | 同两项 | **Lance 面**：`vend_credentials=true` 是 query 参数、路径不进映射表，两道都在 `LanceCatalogSupport#requireVending` 里判 |

第二步进不了映射表，是因为判据是**签发结果**而不是路径：同一个 `/token`，`relay` 发的是
catalog 里存的 AK/SK，`s3-token` 发的是现签的短期票；用配置反推就会造出「开关说走 STS、
其实退回了转发」——正是 §3.4.1 否掉 mode 开关的同一条理由。判据落在
`VendedStorageCredential#carriesLongTermKeyPair()`（`hasSecrets() && !shortLived()`），
于是「什么算长期密钥」只有一个定义，两个协议面不会漂移。

两处失败姿势有意不同：`/token` 与 `/credentials` 唯一的用途就是签发，静默回空会让引擎
拿着空配置去读数据，所以 403 并点名缺的 access；`loadTable` 的
`X-Iceberg-Access-Delegation` 同时承载元数据读取，规格允许服务端一个机制都不选，
所以那里只「不带凭据」+ WARN，不 403。

`USE_SECRET` **不含** `USE_CREDENTIAL`：只有它时取不到票。这抄的是官方对这两个点的
互斥描述（`USE_SECRET` 不能调 `getCredentials()`），也与本工程「除 `ALL` 外不做蕴含推断」
的一贯口径一致。

#### 3.4.4 打码值不能回写

#13539 顺手修了一个坑：缩短敏感关键字表会让**已经打码好的** `******` 解不出来
（原文是 "strand masked values"）。对我们更直接，也更难发现：

`StorageConfigFields.vue` 原本靠 `storedAccessKeyId` 判断「已保存了什么」，而这个值来自**回显的**
`accessKeyId`。一旦照 Gravitino 把它打码成 `******`，而保存路径没有「收到 `******` 视为未修改」这个分支，
第一次「只改个名字」的保存就会把库里的 AK ID 写成六个星号，SK 却仍是旧的——
**密钥对错位，错误现场在存储侧**，报 `InvalidAccessKeyId`，与「我刚改了什么」完全联系不上。

所以打码要同时满足两条（G14）：**只在一个出口打**（详情报文，不要列表与详情各写一遍）；
**回写要认打码值**（与现有 `secretAccessKey` 的「空串 = 保持」同一个思路，只是多一个哨兵值）。

**v8 已实现（P1-8）。** 打码在 `StorageConfigs#withoutSecrets` 一处产生，哨兵在
`StorageConfigs#mergeStaticCredentials` 一处消费，取值统一为 `StorageConfigs.MASKED`。
写路径的完整规则：

| 请求里的 `accessKeyId` / `secretAccessKey` | 结果 | 说明 |
| --- | --- | --- |
| `******` / 省略或空 | 两个字段都沿用库里那份 | 「只改个名字」的保存走这条，也是控制台每次提交的默认形态 |
| `******` / 新密钥 | 沿用库里的 AK ID，只换密钥 | **唯一**能表达「只轮换密钥」的组合：省略 AK ID 会变成「有密钥没 ID」而被拒 |
| `******` / 库里没有原值 | 400 | 这个值的意思是「沿用你库里那份」，新建 catalog 时没有那份；静默存下去等于存了一把叫六个星号的钥匙 |
| 真值 / 真值 | 换新 | 与原行为一致 |
| `""` / `""` | 清除 | 显式清除；`******` 不能当清除用（见上） |
| `******` 出现在 `secretAccessKey` 位 | 400 | 它不是密钥，存进去只会在签名时失败 |

`******` 与「空串」必须分开，这条守不住就会静默删凭据：打码是「有值但不告诉你」，
空串是「这里不要值了」。因此 `******` + 空密钥按「两个都不变」处理，清除只能靠一对空串——
控制台的「移除已保存的静态凭据」正是这么发的。

控制台跟着改了两处：**AK 输入框不再预填星号**（预填会被当成真值继续编辑，改一个字符就变成
一把不存在的钥匙），改为留空 + 占位提示「已保存（******），留空即保持不变」；
`build()` 在「输入框空但库里有」时回传哨兵，而不是省略字段——省略只能表达「两个都不改」。

### 3.5 第二批：Polaris 全量与 Gravitino `page=2`，仍是「改哪一条」

`pulls?q=vended` 在 Polaris 有 3 页、Gravitino 有 2 页。上节只覆盖了 Gravitino 第一页里 7 月之后
的九条；这一节补剩下那批，仍然只写「会改变我们判断」的，其余（文档、Docker、测试基建）略过。

| PR | 改了什么 | 对本文的哪一条下了判决 |
| --- | --- | --- |
| Gravitino [#10895](https://github.com/apache/gravitino/pull/10895)（open） | Lance `describeTable` 的凭据下发：把 `S3TokenCredential` / `GCSTokenCredential` / `S3SecretKeyCredential` 转成 Lance `storage_options` 的 `aws_access_key_id` / `aws_secret_access_key` / `aws_session_token` / `expires_at_millis`，按调用者 READ / WRITE privilege 现签，经 `vendCredentials` 请求标志门控 | **G12 有答案了**（§3.5.1）；Lance 的 vending 走 `aws_*` 键，不是 `s3.*` |
| Gravitino [#11294](https://github.com/apache/gravitino/pull/11294)（6/5） | Iceberg REST 侧四种存储（S3 / GCS / OSS / ADLS）的凭据**刷新** | 印证 §7.5：G3/G4/G5 补齐后 Iceberg 长作业能自续，方向没错 |
| Gravitino [#9839](https://github.com/apache/gravitino/pull/9839)（2/3） | local / HDFS 表位置**跳过** vending（`shouldGenerateCredential` + `isLocalOrHdfsLocation`）；PyIceberg 的 REST client 总是要 vending，服务端必须能识别并跳过 | 我们 `FILE` 分支应同义：**文件系统不产凭据**，不是「签一张本地票」 |
| Gravitino [#10800](https://github.com/apache/gravitino/pull/10800)（7/13） | `planTableScan` 端点也收 `X-Iceberg-Access-Delegation` 并在响应 `storage-credentials` 里给票；**缓存的 plan 不泄露凭据**（vended 命中缓存仍现签、非 vended 命中缓存不给） | vending 端点面在扩大；缓存与凭据的隔离要像它那样显式做 |
| Gravitino [#10081](https://github.com/apache/gravitino/pull/10081) / [#11264](https://github.com/apache/gravitino/pull/11264)（5/15、6/10） | `jdbc-user-password` provider：Iceberg / Paimon / JDBC catalog 的**元数据后端 JDBC 凭据**也走 credential API；空密码合法（StarRocks） | 凭据 vending 的范畴已从「数据存储」扩到「元数据后端」——我们若给 Paimon 加 JDBC metastore，会撞上同一件事 |
| Polaris [#5513](https://github.com/apache/polaris/pull/5513)（10/5，+3968 行） | `S3CredentialVendingMechanism`：CDI bean + `@Identifier`，启动时解析（坏 bean 启动即失败）；空值 = `DEFAULT`（映射 STS）且总被允许、显式写 `DEFAULT` 是 400；realm allowlist `SUPPORTED_S3_CREDENTIAL_VENDING_MECHANISMS`（默认 `[STS]`）在「存储处」与「使用处」都查；机制自带 `validate(current, updated)` | §5.1 的「provider + 工厂」有了现成骨架可抄（§3.5.5）；也给控制台的「签发机制选择器」一个参照（§10.2） |
| Polaris [#2672](https://github.com/apache/polaris/pull/2672)（9/29） | `stsUnavailable=true` 时不调 STS，但仍把 `s3.endpoint` 等非凭据属性放进 `AccessConfig`；**不 vend 任何凭据**（连静态都不转发） | §5.8 的「退回转发」与它分歧（§3.5.2） |
| Polaris [#2711](https://github.com/apache/polaris/pull/2711)（10/3） | 请求 `vended-credentials` 时 S3 凭据**必须**被 vend，否则报错 | 印证 §5.8「不静默降级」 |
| Polaris [#5425](https://github.com/apache/polaris/pull/5425)（open） | VAST Data 等 S3 兼容存储不支持 inline session policy，AssumeRole 返回 **501**；加 `noInlinePolicy` 选项，为 true 时省略 policy | §6.2「S3 兼容」补第二个坑（§3.5.3） |
| Polaris [#5884](https://github.com/apache/polaris/pull/5884)（open） | `AzureStorageConfigInfo.skipSasTokenGeneration` / `GcpStorageConfigInfo.skipCredentialDownscoping`；为 true 时跳过且**不 vend**，`DefaultAccessDelegationModeResolver` 把它当 `stsUnavailable` 处理 | Azure / GCS 也有了 S3 的 `stsUnavailable` 等价物（§6.2） |
| Polaris [#5578](https://github.com/apache/polaris/pull/5578)（open） | Azure 之前要求所有 location 同 account+container，否则抛异常；改为**按 account/container/endpoint 分 scope 铸 SAS**，bare 键只在单一 account 时发 | §6.2 Azure：`write.metadata.path` / `write.data.path` 跨账户时要 per-scope（§3.5.4） |
| Polaris [#4991](https://github.com/apache/polaris/pull/4991)（7/9） | 新增 bare 键 `adls.sas-token` + `adls.account-name`（PyIceberg / adlfs / fsspec 需要）；`adls.account-host` **有意省略**（会让客户端打 DFS 端点而非 Blob） | §6.1 Azure 键名从三种形态变四种（§3.5.4） |
| Polaris [#3750](https://github.com/apache/polaris/pull/3750)（4/8） | `AccessDelegationModeResolver` 按「客户端请求头 + STS 可用性 + subscoping 配置」在 `VENDED_CREDENTIALS` 与 `REMOTE_SIGNING` 之间选，不再无脑偏好 VENDED | §7.5 补一条：delegation 头不是「要不要票」，是「能票给票、不能票转签名」 |
| Polaris [#5512](https://github.com/apache/polaris/pull/5512)（9/15） | GCS 的 `gs://bucket`（无尾斜杠）会让 `substring(1)` 抛 500；用安全 `trimLeadingSlash` | §5.5 作用域：bucket 根路径要容忍空 path |
| Polaris [#5731](https://github.com/apache/polaris/pull/5731)（open） | 非凭据属性（region / endpoint / path-style / refresh endpoint）也放进每个 `storage-credentials` 条目的 `config`，而不是只放顶层 table config——否则 `/credentials` 端点拿不到完整配置 | §5.3 键名契约：定位键与凭据键要**成对**下发 |
| Polaris [#408](https://github.com/apache/polaris/pull/408)（1/7） | 凭据生命周期用 `PolarisConfiguration` 可配；**GCS 缺席** | §5.4 TTL：每存储一个 TTL 上限，别当成全局一个 |
| Polaris [#5591](https://github.com/apache/polaris/pull/5591)（9/29） | 客户端发 `X-Iceberg-Access-Delegation` 但 catalog 一个机制都满足不了时 **400 fail-fast**（含 `stsUnavailable:true` 仍坚持要票、以及未实现的 remote signing） | §7.5：无法满足时明确 400，别回一个「看似成功但没票」的响应 |
| Polaris [#3327](https://github.com/apache/polaris/pull/3327) / [#3823](https://github.com/apache/polaris/pull/3823) / [#3414](https://github.com/apache/polaris/pull/3414) / [#4707](https://github.com/apache/polaris/pull/4707) | STS session tags：realm label + 逐字段选择 + `trace_id` 端到端关联；GCS 侧经 Workload Identity Federation 做 principal 归因 | §5.6 审计归因的具体形态：`trace_id` 进 session tag 是现成的「哪次调用签的票」实现 |

#### 3.5.1 G12 的答案：Lance 认 `aws_*` 键，且是 per-request 现签

Gravitino #10895 把 Lance 的 vending 讲清楚了，直接回答了我们列为「待证」的 G12：

1. **键名**。`CredentialPropertyUtils` 把 token 凭据转成 Lance `storage_options` 的
   `aws_access_key_id` / `aws_secret_access_key` / `aws_session_token` / `expires_at_millis`——
   正是 §7.2 那段配置里 `aws_*` 那套，**不是**我们服务端现在发的 `s3.*` 族。所以 G12 的结论是
   **真的对不上**：我们 `DefaultStorageCredentialManager` 在 Lance 分支 `putAll` 的是 `s3.access-key` /
   `s3.secret-key`，Lance Rust 侧 object_store 认的是 `aws_*`，下发了客户端也不认。
2. **粒度**。按调用者 privilege（READ / WRITE）现签，经 `describeTable` 请求里的 `vendCredentials`
   标志门控——与我们 `describe?vend_credentials=true` 的查询参数语义一致，但 privilege 的判定它放进了
   `MetadataAuthzHelper`（我们对应的是 `CatalogAccessRules`，见 G13）。
3. **状态**。这条 PR 是 **open**（未合并），键名结论已可用，但「社区已经把它做完了」这句不能说——
   只能说「社区给的方向与我们一致，键名以它为准」。

落地影响写进 §7.4：Lance 分支的键名要换成 `aws_*`，且这是**修 G12 的一半**；另一半（connector 只读
一次、长作业不续）是客户端的问题，服务端补多少键都救不了。

#### 3.5.2 `stsUnavailable` 的语义：Polaris 是「不 vend」，不是「退回转发」

这里有一处分歧要明确表态。我们 §5.8 原写「`stsUnavailable=true` → 退回 `S3SecretKeyCredential`（转发
两件套）」，Polaris #2672 的选择是：

> `stsUnavailable=true` 时不调 STS，但**仍把 `s3.endpoint` 等非凭据属性放进 AccessConfig**，并且
> **不 vend 任何凭据**（连 catalog 里的 root 密钥都不转发）。

两者差在哪：Polaris 的 `stsUnavailable` 表达的是「**这个存储没有 STS，服务端签不出票**」，于是引擎
得**自己**解决身份（节点角色 / 自己配 AK）——它**没有**把 catalog 存的长期 AK/SK 当作「退而求其次的
票」发出去。Gravitino 的 `s3-secret-key` 才是「转发静态密钥」，但那是**显式配了 AK/SK 才成立**的独立
provider，不是 `stsUnavailable` 的降级。

对我们，正确语义是两句话各管一件事：

- `stsUnavailable=true` 只回答「**能不能签短期票**」：不能。它**不**隐含「那我去转发静态密钥」。
- 「**转不转发静态密钥**」是另一个独立决定：取决于 catalog 是否存了静态 AK/SK（`source=relay` 那档），
  与 `stsUnavailable` 无关。

因此 §5.8 的表要改成：`stsUnavailable=true` → 产出「无凭据」（只发定位键）；「转发静态密钥」只在
catalog 显式配了 AK/SK 且用户选了那档时才发生，二者**正交**。前端原型 §10.2 里 OBS「选 AK 档只能
退化回转发」那句也要按这个改——它不是 `stsUnavailable` 的后果，是「没配委托就没得签，只剩转发」的
独立表达。

#### 3.5.3 S3 兼容存储的第二个坑：AssumeRole 带 inline policy 会 501

§6.2 原本只写「MinIO / Ceph 的 STS 要额外配置、Ozone 视版本」。Polaris #5425 补了更硬的一条：
**VAST Data 等 S3 兼容实现的 STS 不接受 inline session policy，收到 `Policy` 参数直接返回 501**。

这直接动摇了我们「session policy 收窄」在 S3 兼容上的可行性——收窄靠的就是那个 inline policy，
没有它，退化成「整桶票」就失去了 vending 的意义。Polaris 的解法是加 `noInlinePolicy` 选项：
为 true 时省略 policy（牺牲收窄，换取能跑）。落到我们 §6.2 是：**S3 兼容存储默认不带 inline policy，
界面上要显式告诉用户「此存储下无法按表收窄」**——这比 501 之后才发现好。

**落地时改了默认值（P1-11，2026-10-30）。** 上面那句「默认不带」在本工程里**没有照做**，
默认是「带」，`noInlinePolicy` 必须由 catalog 显式打开。理由是那条判据根本不存在：
MinIO / Ceph RGW / Ozone 与 VAST 在这里都是 `storageType=S3` 加一个自定义 `endpoint`，
**没有字段能把「接受 policy」和「拒绝 policy」分开**。真按「有 endpoint 就不带」去推断，
所有**接受** policy 的自建存储会静默失去收窄——而表征与「真能跑」完全一样，
正是本文反复警告的那类「看起来生效了、实际什么都没收窄」。Polaris 能做「默认不带」
是因为它的 S3-compatible 是一个显式存储类型；我们不是。

代价是 VAST 的运维会先吃一次 501。补偿办法放在界面上，不放在默认值里：
① 字段边上的 `hint` 直接写「收到 `Policy` 会回 501」；② 打开后折叠区**外面**出现一条
告警「无法按表收窄」；③ 「保存后凭据来源」那句由服务端判定的原文也改口径，
不再说「按表收窄」。三处都是「当场说清楚」，而不是替全部自建存储关掉收窄。

#### 3.5.4 Azure 键名第四种形态，与 per-scope 铸 SAS

#4991 补了 bare 键（`adls.sas-token` + `adls.account-name`），#5578 补了 per-scope：

- **键名四形态**（§6.1 表要改）：bare `adls.sas-token` / `adls.account-name`（PyIceberg / adlfs /
  fsspec 用）→ `adls.sas-token.<account>`（Iceberg 1.7.x 用）→ `adls.sas-token.<account>.<endpoint>`
  （per-account-host，标准形态）→ 到期键。`adls.account-host` **有意不发**——它会让 fsspec 客户端打
  DFS 端点而非 Blob 端点。
- **per-scope**：Iceberg 的 `write.metadata.path` / `write.data.path` 可以落在**不同** account/container，
  之前 Azure 要求全同，直接抛异常。正确做法是按 account/container/endpoint 分 scope、每个 scope 铸一张
  container 级 SAS；bare 键在**单一 account** 时才发（一个 bare 值表达不了多个 account）。

#### 3.5.5 机制抽象：Polaris #5513 给了「provider 工厂」的现成骨架

§5.1 说「从 switch 变成一组 provider + 工厂」，Polaris 已经把这个抽象做成了可抄的形状：

- 每个机制是一个 CDI bean，`@Identifier` 标识（`STS` / `DEFAULT` / 未来 `CLOUDFLARE_R2` 等），
  注册表**启动时**解析——坏 bean 启动即失败，而不是第一个请求才炸。
- **空值 = `DEFAULT`**（映射 STS），空值总被允许、显式写 `DEFAULT` 是 400；realm allowlist 管
  「显式值能不能用」，默认 `[STS]`。
- 机制自带 `validate(current, updated)`：create 时 `current` 为 null，update 时新机制看到旧配置作
  `current`，转换规则写在机制里。
- 两档错误分开报：「不在 realm allowlist」与「allowlist 里有但服务端没装这个 bean」，都在 create /
  update / vend 三处查。

抄它不是因为它是「正确」的唯一解，而是因为它把三个我们容易漏的点都钉住了：**坏 bean 早失败**、
**allowlist 与可用性分开**、**换机制的转换规则有归属**。我们落地 §5.1 时照这个骨架，能少踩「配了
一个服务端不支持的机制、存进去才发现」这种坑。

---

## 4. 差距清单

> **本节是写文档时的快照**（2026 年 8 月），逐条的状态以 §8 的 P0/P1 表为准。
> 下面几行已经闭环，保留原文是为了记住「当时是怎么错的」：G6 → P1-1（Azure SAS）、
> G7 → P1-4（GCS downscope）、G8 → P1-2（OSS STS；OBS 仍缺，见 P1-3）、
> G9 → P1-5（session tags）、G10/G11 → 见 P2-2/P2-3。

按「会不会让现有实现作废」排序。每条都能用代码或抓包证伪，不是感觉。

| # | 缺口 | 具体形态 | 后果 | 级别 |
| --- | --- | --- | --- | --- |
| G1 | **没有签发器** | `DefaultStorageCredentialManager` 只从配置取密钥，全仓没有 `AssumeRole` / `generateUserDelegationKey` / downscope 调用 | TTL 是元数据，密钥永久有效；「泄露窗口」这层保护等于不存在 | 致命 |
| G2 | **作用域是整桶** | `vend(storage, tableId, tablePath)` 收了 `tablePath`，但只有 `FILE` 分支用了它；S3/OSS/OBS 分支不参与任何收窄 | 一张表的凭据能读全库 | 高 |
| G3 | **没有 refresh 广告** | `IcebergTableService.load()` 的 `LoadTableResponse` 第三个参数（`config`）**一直是 `null`** | 客户端不知道去哪续；长作业跑到 TTL 之后开始 403，只能靠调大 TTL（= 放大泄露窗口） | 高 |
| G4 | **缺 3 个键** | S3 分支不发 `s3.session-token` 与 `s3.session-token-expires-at-ms` | Iceberg 的 `VendedCredentialsProvider` 对这两个键做 `Preconditions.checkState`，缺一个就抛 `Invalid S3 Credentials: s3.session-token not set`——**即使把 G3 补上，续期链路也会在第一次刷新时炸** | 高 |
| G5 | **`/credentials` 字段名可能对不上** | 规格副本写 `storage-credentials`；iceberg-java 1.10/1.11 的 `LoadCredentialsResponse` 方法名是 `credentials()` 且**没有** `@JsonProperty` | 刷新请求反序列化后拿到空列表 / `null`，报 `Invalid S3 Credentials: empty`。落地前必须抓一次真实响应（附录 A） | 高（待证） |
| G6 | Azure 只发定位 | `SOURCE_AZURE_METADATA_ONLY`，注释已承认「本工程没有那层标识」 | Azure 用户拿不到 SAS，只能靠引擎自己的身份 | 中 |
| G7 | GCS 只有静态 token | `lakehouse.rest.storage.gcp.token` 一个字符串，无 downscope、无路径边界 | 一个 token 覆盖整个 GCP 项目能碰到的所有桶 | 中 |
| G8 | OSS / OBS 无 STS | `sessionToken` 只能手填进配置，没人签给它 | 想用临时凭据得自己手工换 | 中 |
| G9 | 无审计归因 | 无 session name / session tags | 云侧审计只能看到「服务端那把钥匙」，看不出是谁读了哪张表 | 中 |
| G10 | 无吊销 | 缓存只靠自然过期；没有「作废这份凭据」的入口 | 疑似泄露时只能轮换密钥 + 重启 | 中 |
| G11 | 服务端自身是静态身份 | 服务端访问仓库也走同一套凭据；没有「能 AssumeRole 但不能读写」的双层 role | 服务被攻破即以仓库权限暴露 | 中 |
| G12 | **Lance 键名口径对不上** | Lance 的 `storage_options` 发的是 `s3.*` 族，而 Lance Rust 侧 object_store 认 `aws_access_key_id` / `aws_secret_access_key` / `aws_session_token` / `expires_at_millis`（Gravitino #10895 的 `CredentialPropertyUtils` 就是这么转的） | **已证**：下发了但客户端没认。表现为仍要手写 §7.2 那些 `aws_*` 配置。修一半在服务端（换键名），另一半在客户端（connector 只读一次、长作业不续，服务端救不了） | 高 |
| G13 | **判权只有一个粒度** | 能读 catalog 元数据的人就能调三条下发通道；没有「取凭据」与「取密钥」之分（对照 §3.4.3 的两个权限点） | 等于「看得到表」= 「拿得到钥匙」，而这正是要分开的两个风险 | 高 |
| ~~G14~~ | **AK ID 明文回显、且没有打码回写的哨兵（v8 已关闭）** | 原状：`accessKeyId` 在详情报文里原文返回，保存路径只有「空串 = 保持」一条规则。现为：出口打码成 `******`、写入口认这个哨兵，两条同时成立（§3.4.4） | 打码而不认哨兵时的后果仍要记住：第一次保存就会把 AK ID 写成六个星号而 SK 未变 → 密钥对错位（§3.4.4 的规则表就是为堵这条） | ~~中~~ 已闭环 |
| G15 | **服务端自身 FileIO 没有独立取票通道** | 优化任务、数据预览、元数据读走的都是 `StorageRuntimePolicy` 选出的同一个 manager，用的是部署配置里的身份（细化 G11） | 服务进程被攻破即以仓库权限暴露；「只能 AssumeRole、不能读写」的双层身份无法表达 | 中 |
| G16 | **Flink 面没验过** | Paimon 面靠 `data-token.enabled` 与 Spark 共用 `RESTTokenFileIO`；Iceberg 面在 Flink 只有 REST 一种走法（不存在 #14032 的非 REST 问题）；但两条都没跑过 e2e | 文档若照抄 Spark 的说法，会给一个没验过的承诺 | 低（待验） |
| G17 | **联邦时 header 不转发、refresh 端点不回写** | 我们目前没有做联邦/多级 catalog，一旦做，`loadTable` 的 delegation 头与远端 refresh 地址都要处理（对照 #12950） | 近端日志写着「vending: true」，远端收到的是「啥也没要」，引擎第一次读数据才失败 | 低（尚未涉及） |
| ~~G18~~ | **COS 临时凭据曾以为会静默降级（v7 已澄清，作废）** | 原文假定 COSN 有 `SessionTokenCredentialProvider` 且默认链顺序会让 `SimpleCredentialProvider` 抢先。核对 paimon-cosn 2.1.0 后：`CosNConfigKeys` 没有会话令牌项、认证包里也没有这个 provider（§6.3.4） | **这里不存在「静默降级」，因为临时凭据这条路根本走不通**。真正的风险换成了另一种：配了 `sessionToken` 却以为它在生效——所以本工程在配了令牌时直接 500 | ~~高~~ → 已消解（改为「不支持」的显式失败） |

> G4 / G5 / G12 / G16 / G18 都是「看起来在跑、其实没生效」的类型：不报错、不抛异常，只在特定路径上失败。
> 这几条决定了本文所有落地项的前置顺序——**先证明报文能被客户端吃掉，再谈签不签得出来**。
> G13 / G14 则是另一类：功能全对，**权限边界与错误处理没跟上**，出问题时表现为「配置被改坏了」。

---

## 5. 目标设计

### 5.1 分层

```
请求（loadTable / token / describe）
        │
        ├─ 判权（现有 authorizationService，一条都不改）
        │
        ├─ 作用域计算 ScopeResolver：表路径 → {readPaths, writePaths, listPrefixes}
        │
        ├─ 签发 CredentialProvider（按 storageType 或 URI scheme 选）
        │     ├─ S3Provider        → sts:AssumeRole + session policy
        │     ├─ OssProvider       → AssumeRole（acs:ram）
        │     ├─ ObsProvider       → CreateTemporaryAccessKeyByAgency
        │     ├─ AzureProvider     → generateUserDelegationKey → SAS
        │     ├─ GcsProvider       → downscoped token（CredentialAccessBoundary）
        │     └─ RelayProvider     → 现有静态转发（S3 兼容存储 / stsUnavailable）
        │
        ├─ 缓存 StorageCredentialCache（改成「剩余寿命 × 比例」失效）
        │
        └─ 协议适配：Paimon token / Iceberg storage-credentials + config / Lance storage_options
```

与现状的差别只有中间那层：`StorageCredentialManager` 从「一个 switch」变成「一组 provider + 一个
按配置挑选的工厂」。**接口不用改**（`vend(storage, tableId, tablePath)` 已经够用），改的是实现与
配置面。另外 `VendedStorageCredential` 要补一个 `expireTimeInMs()`——它是协议适配层唯一的判据
（静态给 `0`，短期给真实到期毫秒，见 §3.4.1），其余逻辑（发几个键、要不要广告 refresh 端点）
全部由它派生，不再有第二种「模式」来源。

**为什么按 `storageType` 而不是 URI scheme 选。** Gravitino 按 scheme 是因为它的 catalog 可以混存
多云的路径；本工程的 catalog 建的时候就绑定了 `storageType`（`StorageConfigs.validate` 会校验
warehouse 与类型一致），一个 catalog 只会是一种存储。按 `storageType` 选更窄、更容易在启动时
就校验出「配置了 sts 却没有 roleArn」这类错误。将来若要支持混合路径，再在 provider 内部按 scheme
二次分派，不影响这层。

### 5.2 一次 loadTable 的完整链路（目标态）

```
引擎 ──① GET /v1/{p}/namespaces/{ns}/tables/{t}  (Authorization: Bearer <主体令牌>)
      ── X-Iceberg-Access-Delegation: vended-credentials
服务端
      ② 判权：principal 对 TABLE 是否有 SELECT（现有逻辑）
      ③ ScopeResolver：location = s3://wh/db/t.lance
            readPaths  = [s3://wh/db/t/]
            listPrefix = wh/db/t
            writePaths = []          ← 只读请求不给写
      ④ S3Provider：sts:AssumeRole(roleArn, "lh-<principal>-<tableId>", 3600, sessionPolicy)
      ⑤ 缓存：键=catalogId|storage json|tablePath，条目寿命 = min(ttl, 剩余×…)
引擎 ◀──⑥ 200 { metadata, storage-credentials:[{prefix:"s3://wh/db/t/", config:{
              s3.access-key-id, s3.secret-access-key, s3.session-token,        ← relay 模式只有前两个
              s3.session-token-expires-at-ms, client.region }}],
            config:{ client.refresh-credentials-endpoint: "/v1/{p}/namespaces/{ns}/tables/{t}/credentials" } }  ← 仅 sts 模式
引擎 ──⑦ 直读 s3://wh/db/t/**（不经过我们）
引擎 ──⑧ 到期前 5 分钟，GET refresh endpoint 换新（VendedCredentialsProvider）
```

三个细节值得单独记：

- **`prefix` 要能匹配上**。`S3FileIO` 用 `credential.prefix()` 建 per-prefix client，过滤条件是
  `prefix.startsWith("s3")`（`ROOT_PREFIX` 是字面量 `"s3"`，所以 `s3a://` 也会命中，不是只认
  `s3://`）；我们发的 `table.getPath()` 是 `s3://...`，形状对。但**不能**只发 `db/table` 这种相对路径
  ——那种前缀匹配不到任何一个 client，凭据会被静默忽略。
- **`refresh-credentials-endpoint` 走 `config`（不是 credentials）**。它是非敏感配置，Polaris 的
  参考页把 kind 标成 `config`；`VendedCredentialsProvider` 从 properties 里读它，而 properties 是
  `LoadTableResult.config` 与 catalog 属性合并后的结果。
- **相对路径要能被解析**。`AwsClientProperties` 用 `RESTUtil.resolveEndpoint(catalog.uri, endpoint)`
  解析，所以给相对路径（`/v1/.../credentials`）比给绝对地址稳——后者在反向代理/多域名部署下容易错。

### 5.3 键名契约（落地时照这张表填）

| 存储 | 定位键（config） | 密钥键（credential） | 过期键 | 续期落点 |
| --- | --- | --- | --- | --- |
| S3（原生） | `client.region` | `s3.access-key-id`、`s3.secret-access-key`、`s3.session-token`（`relay` 模式只给前两个，见 §7.5 第 2 条） | `s3.session-token-expires-at-ms` | `client.refresh-credentials-endpoint`（仅 `sts` 模式广告；Iceberg 客户端自动续） |
| S3（兼容：MinIO / Ceph / Ozone） | `s3.endpoint`、`s3.path-style-access` | 同上（若该实现有 STS）；否则静态两件套 | 同上 | 同上；无 STS 时靠 TTL 覆盖单次作业 |
| Azure ADLS | — | `adls.sas-token.<account>.<endpoint>`（标准），另给 `<account>`、裸 `adls.sas-token`+`adls.account-name` 三种形态；`adls.account-host` 不发（§3.5.4） | SAS 自带 `se` | 无标准续期；靠 SAS 有效期（≤7 天） |
| GCS | `gcs.service-account` | `gcs.oauth2.token` | `gcs.oauth2.token-expires-at` | 无标准续期；靠 TTL |
| OSS | `fs.oss.endpoint` | `fs.oss.accessKeyId`、`fs.oss.accessKeySecret`、`fs.oss.securityToken` | **无标准键** | 无（Hadoop 侧不会续） |
| OBS | `fs.obs.endpoint` | `fs.obs.access.key`、`fs.obs.secret.key`、`fs.obs.session.token` | **无标准键** | 无（同上） |
| COS（COSN 原生） | `fs.cosn.bucket.region`、`fs.cosn.bucket.endpoint_suffix`、`fs.cosn.useHttps` | `fs.cosn.userinfo.secretId`、`fs.cosn.userinfo.secretKey`；**没有令牌键**（§6.3.4），因此不存在「临时档」 | **无** | Paimon 面靠 `RESTTokenFileIO` 重取（它把 token 合并进 options 再重建 FileIO）；Iceberg 面**断链**、Lance 面不适用（§6.3） |
| Paimon 引擎（S3） | 同 S3 | 额外**同时**给 `s3.access-key`、`s3.secret-key`（理由见 §2.2） | — | Paimon 的 `RESTTokenFileIO` 重调 `/token` |
| Lance（Rust 侧） | `aws_region`、`aws_endpoint`、`allow_http` | `aws_access_key_id`、`aws_secret_access_key`、`aws_session_token`（**已确认**，Gravitino #10895 与 G12） | `expires_at_millis`（Lance 自己的外层字段） | 无（connector 读成静态 map） |

> 这张表落地时还要带一条（Polaris #5731）：**定位键要与密钥键成对下发**——`region` / `endpoint` /
> `path-style-access` / `refresh-credentials-endpoint` 这些非凭据属性，也要进每个 `storage-credentials`
> 条目的 `config`，而不只是顶层 table config。否则 `/credentials` 端点（只返回结构化凭据条目）会拿不到
> 完整的客户端配置，`VendedCredentialsProvider` 拼不出能用的 FileIO。

> **OSS / OBS / Azure / GCS / COS 的共同问题：客户端没有续期落点。** 它们的 FileIO 不会因为「快过期了」
> 自己去换票。所以对这五种存储，`ttlSeconds` 必须 ≥ 单次作业最长时长，或者接受"长作业必须分片"。
> 这不是设计缺陷，是生态现状；写进文档比假装能续更重要。
>
> COS 有一处不同：Paimon 的 `COSNFileIO` 外面包着 `RESTTokenFileIO`，而后者按 token 作缓存键、
> 拿合并后的 options **重建** FileIO，所以 Paimon 面是能续的；Iceberg 面走 `HadoopFileIO`，
> 连下发的凭据都收不到，谈不上续；Lance 面没有 COSN（§6.3）。

### 5.4 TTL、缓存、刷新三者怎么配合

| 量 | 取值建议 | 理由 |
| --- | --- | --- |
| STS 请求的 `DurationSeconds` | 3600（可配，下限 900） | 太短 → STS 调用变密（单账号约 250 TPS）；太长 → 泄露窗口变大 |
| `credential.ttl-seconds`（下发给客户端） | 与上面**同源**，不要各配一个 | 两个值不一致 → 客户端以为还能用，实际已经过期 |
| 缓存条目寿命 | `credential.ttl × 0.15`（剩余寿命比例） | 缓存到快过期才给出去等于把风险转嫁给引擎。Gravitino 的 `credential-cache-expire-ratio` 默认也是 `0.15`，容量默认 `10000`，与我们 `max-entries` 的默认值相同——数值撞了，但我们的 0.15 还没实现（P0-6） |
| 客户端预取 | 到期前 5 分钟（`VendedCredentialsProvider` 内部固定） | 服务端不需要关心，但影响 TTL 下限：TTL ≤5 分钟时永远在刷新 |
| 刷新并发 | singleflight（同 key 只让一个请求真正调 STS） | 一批引擎同时启动会打出惊群 |

**`ttlSeconds` 必须从「建议」变成「事实」**：现在它只是响应里的一个数字，接上 STS 之后它才等于
密钥寿命。这也是为什么 §8 把「凭据来源与剩余寿命可观测」放进 P0——否则排障时无法回答
「这份凭据是真的 15 分钟，还是假的 1 小时」。

### 5.5 作用域怎么算

表的 `location` 已知，三组路径由它派生：

| 组 | 值 | 用在哪 |
| --- | --- | --- |
| `readPaths` | `location`（含尾部 `/`），**并上** `write.data.path` / `write.metadata.path`（表可以自定义这两个位置） | `s3:GetObject` 的 Resource；ADLS SAS 的 `sr` 范围 |
| `listPrefixes` | location 去掉 `s3://bucket/` 之后的部分 | `s3:ListBucket` 的 `s3:prefix` 条件（**必须**，否则引擎列目录被拒） |
| `writePaths` | 只读请求为空；写请求为 `location` →（若有）`write.data.path` / `write.metadata.path` | `s3:PutObject` / `s3:DeleteObject` / `s3:AbortMultipartUpload` |

三个容易漏的点：

- **`s3:GetBucketLocation` 要给**。只给 GetObject + ListBucket 时，部分客户端 SDK 会在初始化阶段
  报一个与权限完全不像的错。
- **`ListBucket` 的 Resource 是 bucket，不是对象**，前缀必须走 `Condition`，不能拼进 ARN。
- **Iceberg 的元数据文件与数据文件在同一 location 下**，所以不用额外加分片；但如果将来给
  `metadata/` 单独开写权限，要记得它也在 `readPaths` 里。
- **GCS 的 bucket 根路径要容忍空 path**。`gs://bucket`（无尾斜杠）解析出的 raw path 是空的，
  `substring(1)` 会直接抛异常（Polaris #5512 就是踩这个，表现为 500）。前缀裁剪要像 AWS 的
  `trimLeadingSlash` 那样先判斜杠再截，别假设 path 一定以 `/` 开头。

**多路径不是「取第一个」。** 表可以同时声明 `location`、`write.data.path`、`write.metadata.path`，
三者可能落在同一个前缀下的不同子目录。Gravitino #13928 之前**直接拒绝**这类表（它要求恰好一个唯一路径），
之后的处理是：把等价的写法归一（`abfs` / `abfss`、账户名大小写、尾斜杠），若其中一个请求路径是其余的
父级，就合成**一个目录级 SAS**；不满足这个条件、或跨账户跨容器的，给明确错误而不是悄悄放宽。
SQL 侧这条要照抄——它比「给三条路径各签一张票」既省调用也更好审计。

**catalog 级请求天生比表级宽。** 同一个 IRSA provider 在 #13928 里被分成两档：**带路径的请求收在
session policy 里，catalog 级请求不带 policy**（原文："catalog-level requests do not include one"）。
这不是疏漏，是它确实要覆盖整个 catalog。对我们的要求是：把「catalog 级票」与「表级票」当成两种东西，
**不要拿 catalog 级票去应答一次表级读取**——那等于把收窄整个跳过。§5.7 里服务端自己的 FileIO 恰好需要
catalog 级票，那里要显式写明这一点。

### 5.6 审计归因

`AssumeRole` 的 `RoleSessionName` 用 `<catalog>_<principal>_<tableId>`（Polaris 用 session tags 更细）。
两个原因：一是 CloudTrail / ActionTrail 里能直接看出"谁读了哪张表"；二是出问题时能按 session name
反查是哪次调用签的。成本为零——**不做的唯一理由是忘了**。

注意文章里那句提醒：**不要把敏感元数据塞进 session tag**，IAM session tag 是常见的泄露点（它会被
写进云侧审计日志，也可能被 ABAC 策略引用）。

**落地（P1-5）：`RoleSessionName` 已有，session tags 做成可开且默认关。** 两者是同一件事的两种表达，
能力不同：session name 是一个字符串，云侧只能做前缀/包含匹配；tag 是结构化键值对，能按
`lh:principal = alice` 直接过滤、也能被 ABAC 当条件引用。落地发三个 tag：

| tag | 值 | 为什么是它 |
| --- | --- | --- |
| `lh:principal` | 调用者标识 | 「谁」——唯一来自请求输入的一维，必须按 STS 字符集规整（见下） |
| `lh:scope` | 表 id，服务端自己取票时是 `catalog:<id>` | 「哪张表」。与 session name 里的后缀同源，便于两边对照 |
| `lh:access` | `read` / `write` | 「读还是写」。只读票被拿去写会 403，而那 403 在存储侧，与签票时的决定隔着好几层 |

**为什么默认关，而不是默认开。** 这不是保守，是一个会让 `AssumeRole` **直接失败**的外部前提：
AWS 要求被 assume 的角色信任策略放行 `sts:TagSession`，没放行时带 tag 的调用回 `AccessDenied`。
默认打开等于把一次纯运维配置塞进升级动作里——升级完所有读表 503，报错还是「权限不足」，
而配置里什么都没改。所以：先在信任策略里加 `sts:TagSession`，再开
`lakehouse.rest.credential.session-tags: true`。关闭时**不传 `Tags` 集合**（不是传空数组），
请求与改造前逐字节相同。

**值的规整按 tag 自己的字符集**（字母、数字、空格与 `_ . : / = + - @`），不是照抄 session name 的
`[\w+=,.@-]`：两者集合不同（tag 允许 `:` `/` 空格，不允许 `,`）。目录服务的主体标识常带逗号
（`cn=alice,ou=people`），原样发出去会在签名阶段 `ValidationError`。非法字符换成 `-`，
空值给 `unknown`/`service` 占位——空串会被 STS 拒，而报错只提 value。

### 5.7 服务端身份要分层，且服务端自己也是凭据消费者

**先说第二个消费者。** 本文前面所有内容都假设凭据是「发给引擎的」。其实还有一处：服务端自己的 FileIO
——优化任务的 `compact` / `expire`、数据预览、建表时写元数据，读的都是同一批文件。
Gravitino #11853 就是在补这件事：把 vended credentials 也注入**服务端侧**的 Iceberg catalog 配置
（server-side `S3FileIO`），并顺手防御了 `supportsCredentials()` 返回 `null`。

对我们意味着三件事：

1. **两处凭据要分开算**。引擎的票按「用户 + 表」签（§5.5 的三组路径）；服务端的票按「catalog 的
   允许位置」签——优化任务要跨表扫，给它表级票会直接失败。这就是 §5.5 最后那段说的
   「catalog 级票比表级宽」，这里正是它唯一合理的用处。
2. **服务端身份与取票身份要分层**。现在服务端访问仓库用的是同一套凭据（`StorageFileIoFactory` 走
   `StorageRuntimePolicy`）。目标态是两层：服务端身份**只能** `sts:AssumeRole` 到 data access role，
   **不能**直接读写 bucket。好处是服务被攻破时攻击者拿到的只是「能换票」，而每张票都受 session policy 约束。
3. **可选能力要防御 `null`**。`supportsCredentials()` 这类方法在三家实现上的返回值不一定非空，
   Gravitino 专门为它改过一行（#11853 的 review 意见）。我们的 `StorageCredentialManager`
   接口如果将来加类似的 `supports*()`，别假设一定返回值。

落地方式与部署环境有关（EKS 用 IRSA、ECS 用任务角色、自建机房只能静态 AK/SK 但可以限制其只有
`sts:AssumeRole` 一条权限）——这条属于运维配置，代码侧只需保证「读配置里的 roleArn 去 assume，
而不是拿配置里的 AK/SK 直接当访问凭据」。

#### 5.7.1 已落地：两类作用域，两个入口

| 谁在用 | 入口 | 作用域 | 写权限 |
| --- | --- | --- | --- |
| 引擎（Paimon `/token`、Iceberg `/credentials`、Lance `storage_options`） | `vend(storage, tableId, tablePath[, allowWrite])` | **一张表**的 location | 目前恒 `false`：协议上读写分不开，先收紧 |
| 服务端自己（浏览目录、删表目录、按快照核对文件） | `vendForCatalog(storage, catalogId, allowedLocations, allowWrite)` | catalog 的整份 `allowedLocations` | 按动作如实传 |
| 服务端写元数据（Paimon schema / snapshot） | `vend(..., allowWrite=true)` | **一张表**（不放大到 catalog 级） | `true` |

三条实现约束：

1. **`ListBucket` 的 `Resource` 仍是桶，前缀仍走 `s3:prefix`**——多个位置合成一条语句
   （两个数组都接受多值），不是每个位置一条语句：policy 有 2048 字符的内联上限，
   叠语句会先撞 `PolicyTooLarge`。
2. **缓存键必须分开**（`StorageCredentialCache.catalogKey` 带 `catalog|` 前缀，读写各一份）。
   共用的后果是「用户先读表、服务端随后删目录，服务端拿到只覆盖表的票」——删到一半 403，
   而日志里一切正常。
3. **只缓存短期票**。静态转发档每次重解析配置（管理 API 换了钥匙立刻生效）；短期票必须缓存，
   否则浏览一次目录就是十几次 STS 调用，而云侧配额是单账号数百 TPS 量级。

### 5.8 没有 STS 时怎么退化

判据用现有的 `stsUnavailable`（规格里就有，语义正好）。**注意这里不再有「mode」这个变量**——
上一版写的是 `credential.mode: relay | sts`，读完 Gravitino 之后改掉了（§3.4.1）：模式是**结果**，
不是输入。决定结果的是这份凭据自己的 `expireTimeInMs()`。

再修一处（§3.5.2）：**`stsUnavailable` 只回答「能不能签短期票」，不隐含「那我去转发静态密钥」**。
转不转发静态密钥是另一个独立决定，取决于 catalog 是否存了 AK/SK。两者正交，别写成一条。

| 情形 | 产出的凭据 | `expireTimeInMs()` | 发的键 | 广告 refresh 端点 |
| --- | --- | --- | --- | --- |
| `storageType=S3` 且配了 `roleArn` 且 `stsUnavailable!=true` | `S3TokenCredential` | 真实到期毫秒 | 四件套（含 `s3.session-token` 与到期时刻） | 是 |
| `stsUnavailable=true` | **不产凭据** | — | 只有定位键（endpoint / region / path-style） | 否 |
| catalog 显式配了静态 AK/SK、且选了「转发」 | `S3SecretKeyCredential` | **`0`** | 两件套（`s3.access-key-id` / `s3.secret-access-key`，外加 Paimon 引擎名） | **否** |
| 兼容 S3（MinIO / Ceph / Ozone）未配 `roleArn` | 同「转发」或「无凭据」，看有没有配静态 AK/SK | `0` 或 — | 同上 | 否 |
| assume 失败（权限 / 网络 / 限流） | — | — | **失败可见**：客户端明确要凭据时回 503（Polaris #2711 也是这个态度） | — |

四条不能违反的推论：

- **静态凭据绝不能编 session token 兜底。** 客户端拿到四个键会构造 `AwsSessionCredentials`，
  而假 token 会被 S3 以 `InvalidToken` 拒绝每一次请求。所以「补齐 G4 缺的三个键」这件事**只在真 STS 时做**。
- **`0` 是协议级的信号，不是占位值。** 它同时表达「不会过期」与「别去刷新」，客户端两边都靠它判断。
- **不静默降级。** assume 失败时若客户端明确要凭据，回 503（或 400，见 §7.5 的 delegation 行为）
  而不是换成静态转发——静默降级会让「本来就该报错」变成「跑到一半才 403」。Lance 侧 `storageOptions()`
  已经是这个行为，照它来。
- **不为「不下发」造第五种状态。** 它就是「没有凭据」；`stsUnavailable=true` 是表达它的规格字段，
  不要另加一个开关。Polaris #2672 连静态密钥都不转发，我们把「转发」保留为独立档位——这是本文
  与它唯一的分歧点，理由写在 §3.5.2。

---

## 6. 七种存储的落地映射

### 6.1 总表

| 存储 | 签发 API | 怎么收窄 | TTL 上限 | 客户端认的键 | 主要坑 |
| --- | --- | --- | --- | --- | --- |
| AWS S3 | `sts:AssumeRole`（`Policy` + `DurationSeconds` + `Tags`） | inline session policy：GetObject/ListBucket(prefix)/GetBucketLocation/(写)Put+Delete | role 的 `MaxSessionDuration`（默认 1h，最长 12h）；**role chaining 时上限 1h** | `s3.*` 四件套 | session policy 与 role policy **取交集**；policy 文档有 2048 字符上限（内联时） |
| Azure ADLS | `generateUserDelegationKey`（需要 `Microsoft.Storage/storageAccounts/blobServices/generateUserDelegationKey/action`）+ 拼 SAS | SAS 的 `sr`（container/blob）+ `sp`（r/w/d/l/a/c）+ 路径；`write.data.path` / `write.metadata.path` 跨 account/container 时**按 scope 各签一张** | **7 天**（平台硬上限） | `adls.sas-token.*` 四种形态 | 键名四形态对应不同客户端世代；`adls.account-host` 有意不发（会把 fsspec 客户端导去 DFS 端点）；Hadoop ABFS 用的是另一套（`fs.azure.sas.fixed.token.<account>.dfs.core.windows.net`），别混 |
| GCP GCS | `DownscopedCredentials` + `CredentialAccessBoundary`（STS token exchange） | `availableResource`（`projects/_/buckets/<b>/objects/<prefix>*`）+ `availablePermissions`（`inRole:roles/storage.objectViewer`）+ 可选 `availabilityCondition` | **1 小时** | `gcs.oauth2.token` + `-expires-at` | 需要源凭据具备 token 交换权限（服务账号的 `roles/iam.serviceAccountTokenCreator`）；boundary 的 resource 必须写到 object 级，写 bucket 级会被拒 |
| 阿里云 OSS | RAM STS `AssumeRole` | `Policy` 收窄到 `acs:oss:*:*:<bucket>/<prefix>/*` | `DurationSeconds` 900–3600；角色 `MaxSessionDuration` 最长 12h | `fs.oss.*`（含 `securityToken`） | endpoint 必须与 region 匹配；`securityToken` 走 `x-oss-security-token` 头；Hadoop 侧不续期 |
| 华为云 OBS | IAM `POST /v3.0/OS-CREDENTIAL/securitytokens`（`CreateTemporaryAccessKeyByAgency`） | `policy` 字段（**仅 OBS 认这条限制**，≤2048 字符，与委托权限取交集） | `duration_seconds` 15 分钟 – 24 小时 | `fs.obs.*`（含 `session.token`） | 得先建委托（agency）；请求头带 `x-security-token`；policy 里用 `obs:prefix` 条件限定前缀 |
| 腾讯云 COS | ~~腾讯云 STS `AssumeRole`，policy 用 CAM 格式~~ **v7：不做签发**——COSN 的 FileIO 不收临时凭据，签出来没人消费（§6.3.4） | 只能转发长期 AK/SK；CAM policy 的写法留作备查（§6.3.3） | — | `fs.cosn.*`（`secretId` / `secretKey` / 区域 / 端点 / `useHttps`） | **COSN 原生只覆盖 JVM 面**：Iceberg 只能 `HadoopFileIO` 且下发断链、Lance 无 COSN 只能 S3 兼容；**不能设 `fs.cosn.credentials.provider`**（那个类不存在） |
| S3 兼容（MinIO / Ceph RGW / Ozone / VAST） | 有的实现有 STS、有的没有；有 STS 的也可能**不接受 inline session policy**（VAST 返回 501） | 有则同 S3，但要能关 policy；无则静态 | — | 同 S3 | **必须保留静态转发路径**；有 STS 但拒绝 inline policy 的，由 catalog 显式开 `noInlinePolicy`（默认仍带 policy，牺牲收窄换能跑，§3.5.3 有为什么不是默认） |

### 6.2 逐条补充

**AWS S3。** 最关键的一句：session policy **只能收窄**。所以 data access role 的权限应该是
「整个 warehouse 的读写」，session policy 再压到一张表；反过来（role 只有一张表、policy 想放宽）
是无效的。`RoleSessionName` 有字符集限制（字母数字与 `+=,.@-_`），`<catalog>_<principal>_<table>`
里的点号与斜杠要替换掉。另外 **role chaining 的 1 小时上限**是个常见坑：如果服务端身份本身就是
一个 assumed role，它再去 assume 另一个 role 时 `DurationSeconds` 会被压到 3600——这恰好与我们的
默认值一致，但改成 2 小时会静默失败。

**Azure ADLS。** 三件事：① 需要服务端身份（Managed Identity / 应用注册）持有
`generateUserDelegationKey` 权限，这正好补上现在 `SOURCE_AZURE_METADATA_ONLY` 的缺口；
② SAS 的键名有**四种形态**（bare `adls.sas-token`+`adls.account-name`、按账户 `adls.sas-token.<account>`、
按 account+endpoint `adls.sas-token.<account>.<endpoint>`、到期键），不同客户端世代认不同的，**一起发**
最省事，但 `adls.account-host` 有意不发（§3.5.4）；③ **不要**和 Hadoop ABFS 的
`fs.azure.sas.fixed.token.*` 混用——那是 Spark 直读 ABFS 的通道，不是 Iceberg ADLSFileIO 的通道，
两套键分属两层。

第四条（Polaris #5578）：**多 location 不能整请求一票**。Iceberg 的 `write.data.path` /
`write.metadata.path` 可以跨 account/container，Azure 之前要求全同、否则抛异常。正确做法是按
account/container/endpoint 分 scope 各铸一张 container 级 SAS；bare 键只在**单一 account** 时发
（一个 bare 值表达不了多个 account）。我们 §5.5 的「多路径合成目录级 SAS」在 Azure 上也要照这条
per-scope 处理，不能假设全同。

第五条（Polaris #5884）：`skipSasTokenGeneration` 是 Azure 的 `stsUnavailable` 等价物——为 true 时
跳过 SAS 且**不 vend**，delegation resolver 把它当「不能 vend」处理（§3.5.2 的语义同样适用）。

**GCS。** 两条路：downscoped token（推荐，能按 object 前缀收窄）或
`ImpersonatedCredentials.generateAccessToken(lifetime)`（只能按服务账号收窄，粒度粗）。前者需要
`google-auth-library` 的 `DownscopedCredentials`，后者实现简单。无论哪条，**有效期都 ≤1 小时**，
所以要指望客户端续期——而 Iceberg 的 GCS FileIO 没有 `refresh-credentials-endpoint` 的等价物，
只能靠 TTL 覆盖作业时长。

**阿里云 OSS。** STS 的 `Policy` 与 AWS 的 session policy 语义一致（交集）。两个易错点：endpoint
必须与 region 匹配（跨区会签名失败，报错却像网络问题）；`fs.oss.securityToken` 这个名字与 OBS 的
`fs.obs.session.token` 只是风格不同，**不能互相套用**（代码里已经用 `OSS_KEYS` / `OBS_KEYS` 两个
常量分开表达）。

**华为云 OBS。** 它的临时凭据接口在 IAM 上（不是 OBS 上），请求体是
`auth.identity.assume_role{agency_name, domain_id, duration_seconds}` +
`auth.identity.policy`。`policy` 这个字段有两条限定值得记：**只有 OBS 服务认它**（访问 ECS 等其它
服务时不生效），字符数 ≤2048。因此「收窄到表」在 OBS 上是可行的，但只对 OBS 生效——如果这个
catalog 的引擎还拿同一份凭据访问别的云服务，那部分不受约束。

**S3 兼容。** MinIO 与 Ceph RGW 都有 STS，但都要额外配置（MinIO 需要 `MINIO_ROOT_USER` 之外的
策略绑定），Ozone 的 S3 网关视版本而定。工程上的选择是：**不猜**。没配 `roleArn` 就走静态转发，
控制台把「本 catalog 下发的是长期凭据」标出来——把风险显性化，比假装支持更负责。

第二条坑（Polaris #5425）：**有的 S3 兼容 STS 不接受 inline session policy**——VAST Data 收到
`Policy` 参数直接 501。这动摇了「session policy 收窄」在它上面的可行性（收窄靠的就是这个 policy）。
对策是加 `noInlinePolicy` 选项：为 true 时省略 policy，代价是退化成整桶票。**S3 兼容存储默认不带
inline policy，并在界面明说「此存储下无法按表收窄」**——比 501 之后才发现好。

### 6.3 腾讯云 COS：定案用 COSN 原生（`storageType=COS`）

**结论先行：COS 走 COSN（`cosn://` + `fs.cosn.*`），不走 S3 兼容。** 但「COSN 原生」这句话
只对 JVM 面成立——三个面必须分开说，否则会写出一个「Paimon 能用、Iceberg 静默失败、
Lance 直接起不来」的方案。

#### 6.3.1 键名族：`fs.cosn.*`，且大小写敏感

| 用途 | 键 | 说明 |
| --- | --- | --- |
| AK | `fs.cosn.userinfo.secretId` | **不是** `accessKeyId`；COSN 的命名是 `secretId`，且大小写敏感 |
| SK | `fs.cosn.userinfo.secretKey` | |
| 区域 | `fs.cosn.bucket.region` | 形如 `ap-guangzhou`；`COSNLoader#requiredOptions` 要求它与端点至少给一个 |
| 端点 | `fs.cosn.bucket.endpoint_suffix` | 形如 `cos.ap-guangzhou.myqcloud.com`；公有云可不填 |
| 是否 HTTPS | `fs.cosn.useHttps` | **缺省 `false`（明文）**，因此本工程默认下发 `true`，见 v7 修订块 |
| 实现类 | `fs.cosn.impl=org.apache.hadoop.fs.CosFileSystem` | Paimon 的 `COSNFileIO` 自己 `new CosNFileSystem()`，不必配；Iceberg 的 `HadoopFileIO` 需要 |
| URI | `cosn://<bucket>-<appid>/<path>` | appId 是 bucket 名的一部分，不是独立参数 |
| ~~临时令牌~~ | ~~`fs.cosn.userinfo.sessionToken`~~ | **不存在这个键**（v7 勘误）。`CosNConfigKeys` 里没有它，认证包也没有读它的 provider |
| ~~凭据提供器~~ | ~~`fs.cosn.credentials.provider=org.apache.hadoop.fs.auth.SessionTokenCredentialProvider`~~ | **那个类名不存在**（v7 勘误）。真实包名是 `org.apache.hadoop.fs.cosn.auth`，里面只有 `SimpleCredentialsProvider` 与 `EnvironmentVariableCredentialsProvider`；配一个不存在的类会在初始化 FileIO 时抛「… is not cos credential provider」 |

**为什么键名不能套 S3 的。** Paimon 的 `COSNFileIO` 源码里写死了
`CONFIG_PREFIXES = {"fs.cosn."}`——它只把**这个前缀**的键搬进 Hadoop `Configuration`，
`fs.s3a.*` 或 `s3.*` 一个都不认。它还专门做了一张 `CASE_SENSITIVE_KEYS` 还原表，因为 Hadoop 的
`Configuration` 会把 key 小写化，而 `fs.cosn.userinfo.secretId` 里那个大写 `I` 必须保住——
写错大小写不会报错，只会让 AK 变成空串。

#### 6.3.2 三个协议面对 COSN 的支持度（本次定案的核心）

| 面 | 能不能用 COSN 原生 | 凭据下发能不能到客户端 | 结论 |
| --- | --- | --- | --- |
| Paimon（Spark / Flink） | ✅ 官方 `COSNFileIO`（`paimon-cosn` 构件，scheme `cosn://`） | ✅ `RESTTokenFileIO` 把 token 合并进 options 后**重建** FileIO，`COSNFileIO.configure()` 重新读 `fs.cosn.*` | **能用，且能续** |
| Iceberg（Spark / Flink） | ⚠️ **Iceberg 没有 COS FileIO**，只能 `io-impl=org.apache.iceberg.hadoop.HadoopFileIO` + `fs.cosn.*` | ❌ `HadoopFileIO` 用构造时传入的 Hadoop `Configuration`，**不消费** `storage-credentials` 条目的 `config` | **能跑，但拿不到下发凭据**，必须客户端自配 `spark.hadoop.fs.cosn.*` |
| Lance（Spark） | ❌ connector 是 Rust（`object_store`），**没有任何 COSN 实现** | — | **只能退回 S3 兼容**：`s3://` + `aws_*` 键 + COS 的 S3 端点 |

这三行不是「实现程度」的差别，是**架构差别**：

- **Paimon 能**，因为 `FileIO` 有 `configure(CatalogContext)` 这个注入口，而 `RESTTokenFileIO`
  恰好会在 token 变化时用它重建 FileIO。COSN 只是众多 FileIO 实现之一，走同一条注入口。
- **Iceberg 不能**，因为 `HadoopFileIO` 的凭据入口是 Hadoop `Configuration`，而 Iceberg 的
  `storage-credentials[].config` 是给 `S3FileIO` / `GCSFileIO` / `ADLSFileIO` 这类「结构化 FileIO」
  用的。两套注入面不通，**这不是我们能修的**，属于 Iceberg 上游设计。
- **Lance 不能**，因为它是 Rust，Hadoop 的 `CosNFileSystem` 是 JVM 类。

**所以「支持 COS」在控制台上必须分面提示**：同一个 catalog，Paimon 面的片段写 `cosn://`；
Iceberg 面的片段要写「`io-impl=HadoopFileIO` + 自配 `fs.cosn.*`，服务端下发的凭据到不了这里」；
Lance 面只能写 S3 兼容那套。**把三面写成一样，等于让两个面静默失败**（§10.4）。

#### 6.3.3 签发：CAM policy，不能照抄 IAM 字符串

> **v7 说明：本节是「如果哪天 COSN 支持临时凭据」的备查资料，当前不落地。**
> 原因见 §6.3.4——COSN 的 FileIO 没有令牌入口，签出来的短期票没有消费者。
> 一旦上游补上（或改用自定义 `COSCredentialsProvider`），这份 CAM policy 的形状仍然有效：
> 它记的是腾讯云与 AWS 在「收窄到前缀」这件事上的写法差异，与能不能签发无关。

| 维度 | 值 |
| --- | --- |
| 签发 API | 腾讯云 STS `AssumeRole`（`RoleArn` / `RoleSessionName` / `DurationSeconds` / `Policy`） |
| policy 格式 | **CAM**（`version` + `statement[].action/resource/effect/condition`），与 AWS IAM JSON、阿里云 RAM 都不是同一套语法 |
| 收窄到表 | `resource` 写成 `qcs::cos:<region>:uid/<appid>:<bucket>/<prefix>/*`，前缀匹配靠 `StringLike` |
| 目录侧属性 | `cos-role-arn`、`cos-region`、`cos-app-id`、`cos-external-id`、`cos-token-expire-in-secs`；静态档共用 `cos-access-key-id` / `cos-access-secret-key`（形状照抄 Gravitino [#12199](https://github.com/apache/gravitino/pull/12199)，它为此单独建了 `Policy` / `Statement` / `Effect` / `Condition` / `StringLike` 五个模型类） |
| 与静态档的关系 | 同 S3：**共用一套 AK/SK 配置面，加一个 `roleArn` 就升级**（§3.4.2） |

值得单独记一句：**CAM policy 与 IAM policy 不通用**。同一个「收窄到前缀」的意图，在 AWS 上是
`Resource: arn:aws:s3:::b/prefix/*` + `Condition: {s3:prefix}`，在腾讯云上 resource 的写法与
条件键名都不同。抄代码可以，抄 policy 字符串不行。

#### 6.3.4 临时凭据：这条路走不通（G18 作废，v7 勘误）

**结论先行：paimon-cosn 2.1.0 的 FileIO 不接受临时凭据，因此 COS 只能转发长期 AK/SK。**
v4 曾据此设计「三件套 + `fs.cosn.credentials.provider` 成对下发」，判定为 G18；
读完实际构件后确认那个前提不成立，本节保留原文结构但把结论与依据换掉——
否则下一个人还会照着一份错的清单去实现。

三条证据（都来自 `~/.m2/.../paimon-cosn-2.1.0.jar` 本身，不是二手文档）：

1. **`CosNConfigKeys` 里没有会话令牌这一项。** 它的键表只有 `user.agent` /
   `credentials.provider` / `userinfo.secretId` / `userinfo.secretKey` /
   `bucket.region` / `bucket.endpoint_suffix` / `useHttps` / `tmp.dir` / `buffer.size` ……
   没有任何 `sessionToken` 常量。
2. **认证包里没有 session-token provider。** `org.apache.hadoop.fs.cosn.auth` 只有
   `AbstractCOSCredentialsProvider` / `COSCredentialsProviderList` /
   `SimpleCredentialsProvider` / `EnvironmentVariableCredentialsProvider`。
3. **整个 jar 搜不到相关字节。** 在 3 万条目里检索 `sessionToken` 与
   `BasicSessionCredentials`，`org/apache/hadoop/fs/cosn/**` 下零命中。

推论有三条，每条都会踩：

- **`fs.cosn.credentials.provider` 不能指向一个不存在的类。** v4 写的
  `org.apache.hadoop.fs.auth.SessionTokenCredentialProvider` 连包名都错（真实是
  `org.apache.hadoop.fs.cosn.auth`），`CosNUtils` 反射实例化时会抛
  「… is not cos credential provider」，而报错位置在 FileIO 初始化，指向的不是配置。
- **发一个没人读的令牌键等于「发了但不生效」。** 这正是 P0-8/G12 那一类故障，
  所以本工程**不发** `fs.cosn.userinfo.sessionToken`。
- **配了 `sessionToken` 直接失败。** `DefaultStorageCredentialManager.putSecrets` 在
  「这一族没有令牌键」且令牌非空时抛 500，并在消息里点名
  `lakehouse.rest.storage.cos`——静默丢掉令牌的后果是「以为在用临时凭据、实际在用长期密钥」，
  权限根本没有按预期收窄。

**那 COS 还有没有短期凭据的路？** 有的，但不在我们这一侧：运维实现一个自定义
`COSCredentialsProvider` 并通过引擎配置 `fs.cosn.credentials.provider` 指过去，
由它自己去取票。服务端不下发这个键，也就不会替调用方做这个决定——
一旦下发，等于把「引擎用谁的票」这件事从引擎侧挪到了 catalog 元数据里，
而 COS 这条链上没有任何东西能保证那个类在引擎的类路径上。

**反向仍适用**：转发档（长期密钥）**绝不能**设 `credentials.provider`——
`SimpleCredentialsProvider` 本来就在默认链里，不设反而正确。

#### 6.3.5 落地清单（v7 已实现，六处都落了）

1. `ManagementEnums.StorageType` 加 `COS` ✅
2. `StorageDtos` 加 `TencentCosStorageConfigInfo`，注册 `WIRE_COS` ✅ ——字段与 v4 的两处不同：
   `endpoint` **只表示** `fs.cosn.bucket.endpoint_suffix`（不再兼作 S3 兼容端点），
   并新增 `useHttps`（缺省 `true`）；`appId` 保留但**只用于写入口校验桶名**
   （`StorageConfigs.requireCosBucketMatchesAppId`），不是下发键。
3. `FileIoType` 加 `COS("cosn", EnumSet.of(FILE, COS))` ✅ ——**wire 名必须是 `cosn`**，
   它同时是 Paimon 的 FileIO scheme（`COSNLoader#getScheme()`）。写成 `cos` 会得到
   「不支持的 scheme」，看起来像没装插件。
4. `DefaultStorageCredentialManager` 加 `COS_KEYS` / `cos()` / switch 分支 ✅ ——
   **但不下发 `fs.cosn.credentials.provider`**（§6.3.4 勘误）：`KeyFamily.sessionToken`
   对 COS 是 `null`，`putSecrets` 遇到「配了令牌却没有令牌键」直接 500。
5. `RestServerProperties.Storage` 加 `Cos`（复用 `CloudCredentials`）✅ ——复用的理由不再是
   「它要 `sessionToken`」（COSN 用不了），而是三家用同一个「默认 + 具名存储」模型，
   形状一致才能共用 `objectStoreSecrets` 那四级来源，少一份复制粘贴就少一次键族串错。
6. 依赖里加 `paimon-cosn` ✅ ——Paimon 的 FileIO 走 SPI
   （`META-INF/services/org.apache.paimon.fs.FileIOLoader`），不加它 `cosn://` 会落到
   「不支持的 scheme」：服务端自己的 FileIO（优化任务、数据预览、元数据读）与引擎侧都受影响。
   声明在 `lakehouse-rest-server`（与 `paimon-s3` 同一处，因为「产物里装哪几个插件」是部署决策），
   父 POM 的 `dependencyManagement` 里带版本。**它有 99MB**：构件本身只有 2KB 的 `COSNLoader`，
   体积全在内嵌的 `paimon-plugin-cosn/` 子目录（shaded Hadoop + COS SDK，三万个条目），
   `PluginLoader` 要的就是这个目录资源，剥不掉。

> 与 v4 的差异不止一处，都记在 v7 修订块里。这里保留「六处」这个结构，
> 是为了让后来对照时能一眼看出哪一处被改了语义。
## 7. 引擎侧：能不能不把 AK/SK 写进配置（Spark 与 Flink）

### 7.1 协议面 × 引擎的现状

| 面 | 现在客户端要不要配密钥 | 机制 | 长作业（>1h）能不能续 |
| --- | --- | --- | --- |
| Paimon（Spark） | **不要** | `data-token.enabled=true` → `RESTTokenFileIO` 按需调 `/token` | **能**（它自己重调） |
| Paimon（Flink） | **不要** | 同一个 `RESTTokenFileIO`，配置项同名（`token.provider=bear` + `data-token.enabled=true`） | **能**（同一机制；但没跑过 e2e，见 §7.6） |
| Iceberg（Spark） | 不要（但要额外两个开关） | `X-Iceberg-Access-Delegation: vended-credentials` + `client.refresh-credentials-endpoint` | **能**，前提是补上 G3 / G4 / G5 |
| Iceberg（Flink） | 不要 | 同上；Flink 侧只有 `catalog-type=rest` 一种走法，另需 `cache-enabled=false` | 理论上能，与 Spark 同一条代码路径；未验 |
| Lance | **要**（今天） | `describe` 的 `storage_options` 只被读一次 | **不能**（connector 侧没有 provider） |

> 这张表是**协议面 × 引擎**的维度。腾讯云 COS 是**存储维度**上的额外一层限制，两者要叠起来看：
> 同样是 Paimon 面，换 COS 之后「能续」仍成立（`RESTTokenFileIO` 按 token 重建 FileIO）；
> 但 Iceberg 面换 COS 就连凭据都收不到（`HadoopFileIO` 不吃 `storage-credentials` 的 `config`），
> Lance 面根本没有 COSN（只能退回 S3 兼容）。逐面见 §6.3.2。

### 7.2 逐行点评提到的那段配置

```scala
.config("...aws_region", "us-east-1")                     // ① 定位，保留
.config("...aws_endpoint", "http://172.18.6.181:9330")    // ② 定位，保留
.config("...aws_access_key_id", "McvV...")                // ③ 长期密钥，要去掉
.config("...aws_secret_access_key", "PbIC...")            // ④ 长期密钥，要去掉
.config("...allow_http", "true")                          // ⑤ 明文 HTTP，内网也别默认开
.config("...header.Authorization", "Bearer " + token)     // ⑥ 目录访问令牌，必须有
.config("...s3.endpoint", "http://172.18.6.181:9330")     // ⑦ 与 ② 重复
.config("...s3.access-key", "McvV...")                    // ⑧ 与 ③ 重复（另一套键名）
.config("...s3.secret-key", "PbIC...")                    // ⑨ 与 ④ 重复
.config("...s3.path.style.access", "true")                // ⑩ 内网路径风格，保留
```

十行里有 **四行是同一对密钥的重复**（③④ 与 ⑧⑨ 分别对应 Lance dataset 层的 `aws_*` 与 S3 层的
`s3.*`），两行是同一个端点的重复（②⑦）。这不是谁写错了——是**两套键名族同时存在**（Lance 的
Rust 侧认 `aws_*`，S3 FileIO 层认 `s3.*`），用户为了让两条路径都通只能都写。目标态里这四行应该
由 `storage_options` 自动落位，配置里只剩 ①②⑥⑩ 四行（外加 `uri` / `parent`）。

**⑥ 那行要单独说。** `header.Authorization` 里的 token 是**目录访问令牌**，有效期 1 小时
（`lakehouse.rest.auth.access-token.ttl`，默认 `1h`）；而作业超过 1 小时后，下一次目录 RPC 会 401。
这与"存储凭据过期"是**两件事**，要分开治理：机器账号用静态令牌（`lakehouse.rest.auth.tokens` +
`token-principals`，无过期），人工用 OAuth 且靠客户端续期。别把它们混成一个"过期问题"。

### 7.3 五种可选方案

| 方案 | 做法 | 适用 | 代价 |
| --- | --- | --- | --- |
| **A. catalog 下发（推荐）** | Iceberg 加 delegation 头 + refresh endpoint；Paimon 开 `data-token`；Lance 靠 `storage_options` | 三面都覆盖 | 服务端得先有真 STS（本文主体） |
| B. 客户端凭据插件 | Iceberg `client.credentials-provider`；Hadoop `fs.s3a.credentials.provider`；Spark 侧自定义 `AwsCredentialsProvider` | 无 STS 的环境、或要接自建 STS | 每引擎写一次，Spark / Flink / Lance 不通用；插件类加载器又是一层坑 |
| C. 引擎自己 assume | 给引擎一个只能 `sts:AssumeRole` 的身份（IRSA / ECS 任务角色） | 云上托管集群 | 引擎仍有身份，只是从"能读写"降级成"能换票" |
| D. 作业启动时注入环境变量 | 启动脚本里 `aws sts assume-role` 后导出 `AWS_*` | 过渡期最省事 | 泄露窗口 = 作业时长；且落到进程环境，容器里容易被 dump |
| E. 数据面网关 | 引擎不直连对象存储，全部走代理 | 需要行/列级控制时 | 吞吐、成本、签名校验复杂度；见 §9.5 |

**推荐组合**：A 为主，C 作为 A 不可用时的降级，D 只用于一次性排障。B 只在必须兼容无 STS 的
自建存储时才写，且只写一份（Hadoop 的 `CustomAWSCredentialsProvider` 能同时服务 S3A 与 Iceberg，
因为 Iceberg 的 S3FileIO 最终也落到 AWS SDK 的 provider 链接口）。

### 7.4 Lance 为什么今天只能把密钥写进配置（与 G12）

connector 0.8.0 的 `BaseLanceNamespaceSparkCatalog` 确实会读 `DescribeTableResponse.getStorageOptions()`
并在 loadTable 时塞进 `LanceSparkReadOptions`——**但只读一次，存成静态 map**，整个查询生命周期
都不再换。对比 0.4.0：那时 `LanceSparkReadOptions` / `LanceRuntime` 还引用
`LanceNamespaceStorageOptionsProvider`（一个 `StorageOptionsProvider`，实现 `fetchStorageOptions()`
供 Lance 内核在需要时回调）；0.8.0 的这套线被拆掉了，`StorageOptionsProvider` 只剩在
`OpenDatasetBuilder` / `WriteDatasetBuilder` 这种底层 builder 上，Spark 侧没有接线。

结论：**Lance 的长作业一定会在凭据过期后失败**，除非 TTL 配得比作业还长。这条已经写进
[`spark-lance-rest-e2e.md`](spark-lance-rest-e2e.md) §3.1，不在代码注释里留着。

G12 的另一半（§3.5.1）已经由 Gravitino #10895 定案：**键名也要换**。我们 `LanceCatalogSupport.storageOptions()`
在 `vendCredentials` 时 `putAll` 的是 `s3.access-key` / `s3.secret-key`，而 Lance 的 Rust 侧
object_store 认的是 `aws_access_key_id` / `aws_secret_access_key` / `aws_session_token` /
`expires_at_millis`。所以即便把 connector 的「只读一次」修掉，今天的键名仍是「下发了但客户端没认」。
服务端这半是能改的：Lance 分支的键名换成 `aws_*` 族，并在有真会话时带 `aws_session_token` +
`expires_at_millis`；客户端那半（长作业续期）不是服务端能救的，取决于 Lance connector 把
`StorageOptionsProvider` 接回 Spark 侧（0.4.0 有、0.8.0 拆掉了）。两半都写清楚，别只修一半就宣称
「Lance 已经支持 vending」。

### 7.5 Iceberg 那条路要先修的三个卡点（按顺序）

1. **G5 字段名**：先抓 `/credentials` 的真实响应，确认客户端（1.10 / 1.11）能不能解出来。
   顺便确认 `storage-credentials` 与 `credentials` 哪个是当前客户端认的——**两个都发**最省事
   （多一个字段不会被拒，缺一个就空列表）。
2. **G4 缺键**：S3 分支必须能产出 `s3.session-token` 与 `s3.session-token-expires-at-ms`。
   **但只在真的有 STS 会话时给**——`VendedCredentialsProvider` 拿到四个键后会构造
   `AwsSessionCredentials`，如果给的是一把静态 AK/SK 配一个随手编的 session token，S3 会以
   `InvalidToken` 拒绝每一次请求。所以正确做法是**按凭据类型分叉**（`expireTimeInMs()` 是不是 `0`，
   见 §3.4.1），不是无脑补齐：

   | 凭据 | 发的键 | 广告 refresh 端点 | 客户端行为 |
   | --- | --- | --- | --- |
   | 短期（有真会话，`expireTimeInMs > 0`） | 四件套（含 session-token 与到期时刻） | 是 | 走 `VendedCredentialsProvider`，到期前自己换 |
   | 静态（`expireTimeInMs == 0`） | 两件套（`s3.access-key-id` / `s3.secret-access-key`，外加 Paimon 引擎名） | **否** | 退回 `AwsBasicCredentials`，不刷新（本来也不需要） |

   这条分叉必须写进测试：静态那一份断言「四个键里只有两个」且 `config` 里没有 refresh 端点。
3. **G3 广告**：`loadTable` 的 `config` 从 `null` 改成带 `client.refresh-credentials-endpoint`。

第四件是行为语义，不是卡点但容易被搞错（Polaris #3750 / #5591）：**`X-Iceberg-Access-Delegation`
不是「要不要票」的布尔开关，是「能票给票、不能票转 remote signing」的能力协商**。Polaris 用
`AccessDelegationModeResolver` 按「客户端请求 + STS 可用性 + subscoping 配置」在
`VENDED_CREDENTIALS` 与 `REMOTE_SIGNING` 之间选；当客户端要的机制一个都满足不了（`stsUnavailable:true`
仍坚持要票、或要了未实现的 remote signing）时，**400 fail-fast**，而不是回一个「看似成功但没票」的
响应。我们 §5.8 的「回 503」与这里的「回 400」是同一件事的两种表达——选一个并写进测试即可，关键是
**别静默**。

三件都做完，`spark.sql.catalog.<c>.header.X-Iceberg-Access-Delegation=vended-credentials` 才真正
等于「不配 AK/SK 也能跑长作业」。这也是本仓库 `TableOptimizerJob` 已经在用的那行配置——
它今天的实际效果是「拿到静态转发的那把钥匙」，不是「拿到短期票」。

### 7.6 Flink：一个「静默拿到空凭据」的现成教训（Gravitino #14032）

上一版本文没有引擎维度，只有协议面。Gravitino 10 月那个修复说明了为什么不能这么写：
**同一个协议面，不同引擎的取票时机不一样**。

它的做法与理由：

| 它的行为 | 为什么这么做 | 我们的对应 |
| --- | --- | --- |
| Iceberg **非 REST 后端** + `aws-irsa` → 打开 catalog 时**直接报错** | Flink 连接器只在开 catalog 时取一次 catalog 级凭据，之后永不刷新；`aws-irsa` 的票约 1 小时后 `ExpiredToken`。报错比让作业跑一小时再挂好 | 我们不在 catalog 级发票（每张表单独发），所以不适用；但**若将来加 catalog 级缓存就要照它做** |
| 同一后端 + `s3-token` / `gcs-token` / `adls-token` / `oss-token` / `cos-token` → 只 **warn** | 这些 provider 在 catalog 级「什么也没发」，Iceberg 于是静默回落到 Flink 节点上的凭据——**看起来配了，其实没用** | 我们的 `stsUnavailable=true` 也是静默的。同意保持静默（报错会挡住那些本来就该用节点身份的部署），但**控制台必须标出来**（§10） |
| 文档明写「Flink 对非 REST 后端只拿静态凭据；要可刷新的按表凭据，必须 REST 后端 + `data-access=vended-credentials`」 | 让用户不用猜 | 我们的对应写法：`flink-rest-catalog.md` 里明说 `data-token.enabled` 只在 Paimon 面有效，Iceberg 面是另一套键 |

我们自己的边界（三条，按确定程度排）：

1. **Paimon 面能续，而且是同一个机制。** Flink 的 Paimon `RESTCatalog` 与 Spark 共用
   `paimon-common` 里的 `RESTTokenFileIO`，配置项同名（`token.provider=bear` + `data-token.enabled=true`）。
   这是读代码得出的结论，不是实测。
2. **Iceberg 面不存在 #14032 那个问题。** 因为 Flink 侧只有 `catalog-type=rest` 一种走法，
   没有「非 REST 后端」这条路径。但 `cache-enabled=false` 是本仓库 `flink-rest-catalog.md` §2
   列出的必填项，别漏。
3. **两条都没跑过 e2e**（Flink 的用例全落在 `file://`，凭据那条路一次都没走过）。所以在 §8 里它是
   「待验」，不是「已支持」——文档给承诺之前，先跑一遍。

---

## 8. 需要做哪些功能（按优先级）

### P0：让它变成真的（不改协议形状）— **全部已实现**

> 落地位置：`lakehouse-rest-core`（`VendedStorageCredential`）、
> `lakehouse-rest-storage`（`CredentialProvider` / `S3TokenCredentialProvider` /
> `AssumeRoleClient` / `AwsSdkAssumeRoleClient` / `CredentialScope` / `S3SessionPolicy` /
> `StorageCredentialCache`）、`lakehouse-rest-protocol-iceberg`（`IcebergCredentialService`）、
> `lakehouse-rest-protocol-lance`（`LanceStorageOptions`）。
> 用例：`VendingCredentialProviderTests`（19 例）。
>
> **开启方式**（默认关闭，关闭时输出与改造前逐字节相同）：
>
> ```yaml
> lakehouse.rest:
>   credential:
>     providers: [s3-token]      # 默认空 = 只有 relay
>     ttl-seconds: 3600          # 同时是 STS 的 DurationSeconds 与下发的 ttl
>     cache-expire-ratio: 0.15   # 短期凭据在缓存里只留 15%
> ```
>
> catalog 侧：`storageConfigInfo.roleArn` 必填（服务端会去 assume 它），
> `region` / `stsEndpoint` 可选；**签名用的那对 AK/SK 也写在同一个 catalog 上**
> （后记：原先写在这里的 `lakehouse.rest.storage.aws.access-key` 已删除）。
> catalog 上没有钥匙时才交给 SDK 默认凭据链（实例角色 / IRSA）。

| # | 做什么 | 验收（可机械核对） |
| --- | --- | --- |
| ✅ P0-1 | 新增 `credential-providers` 列表（默认只有 relay 一个实现），并给 `VendedStorageCredential` 加 `expireTimeInMs()`（静态 `0`、短期真实到期） | 两种实现各一个测试类；默认配置下输出与今天逐字节相同；断言「静态的 `expireTimeInMs()` 恒为 `0`」→ `VendingCredentialProviderTests` 的 `disabledProviderChainKeepsTheRelayBehaviour` / `stsProviderIsSkippedWhenCatalogCarriesStaticCredentials` |
| ✅ P0-2 | `S3CredentialProvider`：`AssumeRole` + session policy（§5.5 三组路径）+ `RoleSessionName` | 单测断言 policy JSON 的三段（GetObject / ListBucket 带 `s3:prefix` / 写分离）；抓包断言 `DurationSeconds` → `policyKeepsListBucketResourceAtBucketLevel` / `policyAddsWriteStatementOnlyWhenWriteIsRequested` / `stsProviderVendsFourKeysAndAdvertisesItsOwnName` |
| ✅ P0-3 | S3 分支按凭据自己的 `expireTimeInMs()` 产出：短期给四件套，静态只给两件套（不编 session token） | 断言两种实现的键集合；静态那份不出现 `s3.session-token` → `stsProviderIsSkippedWhenCatalogCarriesStaticCredentials` |
| ✅ P0-4 | `loadTable` 的 `config` 给 `client.refresh-credentials-endpoint`（相对路径） | 断言 `config` 非空且键名逐字一致 → `refreshEndpointIsAdvertisedOnlyForShortLivedCredentials` / `multiLevelNamespaceIsEncodedWithTheSpecSeparator` |
| ✅ P0-5 | `/credentials` 同时输出 `credentials` 与 `storage-credentials` | 两种字段名都能被解出来 → `loadCredentialsResponseCarriesBothFieldNames` |
| ✅ P0-6 | 缓存改「剩余寿命 × 比例」失效（默认 0.15，可配） | 注入固定签发时刻：1h 的票在 8 分钟时命中、10 分钟时未命中 → `cacheKeepsShortLivedCredentialsForOnlyAFractionOfTheirLifetime`；静态凭据仍满 TTL → `cacheKeepsStaticCredentialsForTheFullTtl` |
| ✅ P0-7 | 可观测：provider 名 + `expireTimeInMs` + 剩余寿命打进日志/诊断端点 | 短期凭据每次签发打一条 **INFO**：`vended provider=s3-token ... expiresIn=3600s`；转发档每次下发打一条 **DEBUG**：`provider=relay source=... expiresIn=none`（转发是高频路径，升到 INFO 会把日志淹掉；排障时开 debug 即可） |
| ✅ P0-8 | Lance 的 `storage_options` 键名换成 `aws_*` 族（G12 已证，Gravitino #10895），有真会话时带 `aws_session_token` + `expires_at_millis` | e2e 里「不配 `aws_access_key_id` 也能读」这一条通过（**尚未跑**，见文末）；`LanceStorageOptions` 的换名与透传由 `lanceStorageOptionsUseAwsKeyNames` / `lanceStorageOptionsPassThroughNonObjectStoreKeys` 守住；客户端长作业续期仍不在服务端能力内（§7.4） |

> **P0-8 的偏差要说清楚**：设计写「e2e 里不配 AK 也能读」作为验收，实际改的是键名，
> 而 `LanceCatalogLiveTests` 里那条用例仍需要真实的 S3 环境（`S3Environment` 门禁）。
> 也就是说：**键名这一半已修，端到端那一半未验**。把它当「已修好」会重演 G12 的老问题
> ——「看起来在跑、其实没生效」。

### P1：把缺口补齐（七种存储 + 权限与打码）

| # | 做什么 | 关键前提 |
| --- | --- | --- |
| ✅ P1-1 | Azure User Delegation SAS（四种键名一起发；`write.data.path` / `write.metadata.path` 跨 account/container 时按 scope 各签一张，bare 键只在单账户时发） | 落地：`AzureSasCredentialProvider` + `HttpUserDelegationKeyClient`（AAD 令牌缓存 1h、提前 2 分钟刷新）+ `AzureSasToken`（`sv=2020-12-06`，24 段待签串一个字段不省）+ `AzureLocation`（abfs/abfss/wasb/wasbs 四种 scheme 解析，`blobHost()` 把 dfs 换成 blob）。**与设计的偏差**：①作用域按**账户**收敛——同一账户多个位置取共同父目录，跨容器时拒绝签发（一个账户一个下发槽位，签两张会互相覆盖且不报错）；②`adls.account-host` 故意不发（会把 fsspec 导去 DFS 端点，PyIceberg 只认 Blob）；③服务身份放在 `lakehouse.rest.storage.azure`（租户优先取 catalog 的 `tenantId`），不是 catalog 字段。用例：`AzureSasVendingTests` 17 条（键名四形态 / 目录级 `sdd=3` / `sr=c` 无 `sdd` / 跨账户发多张且不发 bare / 三条齐全才报「签发」/ 签名随路径与密钥变化） |
| ✅ P1-2 | OSS STS（`acs:ram`）+ policy 收窄 | 落地：`OssStsCredentialProvider` + `HttpOssStsClient` + `OssStsPolicy` + `AliyunRpcSignature`（percentEncode / 规范化查询串 / `HMAC-SHA1(secret + "&")`，全脱网可测）。角色放在 `lakehouse.rest.storage.oss.role-arn`（部署级，与 Azure 同一理由）。**三处必须记住的边界**：①`DurationSeconds` 只收 900–3600，超出**夹紧**并打 INFO（TTL 是部署级配置，不该被一个存储类型反过来限制别的类型）；②hadoop-aliyun 不会自己换票 → 票到期后长作业 403，调大 TTL 也不行；③policy 的资源是 `acs:oss:*:*:<bucket>/<prefix>/*`，`ListObjects` 的资源是**桶**、前缀走 `Condition.StringLike."oss:Prefix"`。用例：`OssStsVendingTests` 18 条 |
| P1-3 | OBS `CreateTemporaryAccessKeyByAgency` + `policy` 收窄 | **未做，且不是「照着 OSS 抄一遍」的活**：这条链要先拿 IAM token（`POST /v3/auth/tokens`，需要**账号名 + 用户名 + 口令**），再用 `X-Auth-Token` 调 `securitytokens`。也就是说它给部署引入了一类新密钥（口令），而不是复用 AK/SK——这与前面三家的姿势不同，需要单独决定「口令存哪、怎么加密、怎么轮换」。在有一套真实 IAM 与委托（agency）之前硬写，得到的最多是「看起来实现了」：签名对不对、token 寿命怎么缓存，都无法验证。**建议按 Azure 那套形状落地**（`IamTokenClient` + `ObsAgencyClient` + policy 复用 `OssStsPolicy` 的资源拼法，只把 `acs:oss:` 换成 OBS 的资源写法） |
| ✅ P1-4 | GCS downscoped token（`CredentialAccessBoundary`） | 落地：`GcsDownscopedCredentialProvider` + `HttpGcsDownscopeClient` + `GcsBoundary`。**唯一不需要新凭据的一家**：交换的是 `lakehouse.rest.storage.gcp.token` 这个已经在配置里的令牌，所以启用条件只有「有源令牌」。三处反直觉：`options` 是 **JSON 文本**不是对象；`availableResource` 必须写到**对象层**且协议相对（`//storage.googleapis.com/projects/_/buckets/<b>/objects/<prefix>*`）；权限用 `inRole:roles/storage.objectViewer`（写再加 `objectCreator`，**不是** `objectAdmin`）。规则上限 10 条，超了在本地拒绝签发而不是让 STS 拒整份。用例：`GcsDownscopeVendingTests` 11 条 |
| ✅ P1-5 | 审计归因：`RoleSessionName` / session tags | `RoleSessionName` 随 P0-2 已落地（`lh-<principal>-<scopeId>`，按 STS 字符集规整）；session tags 落在 `lakehouse.rest.credential.session-tags`（**默认 false**），带 `lh:principal` / `lh:scope` / `lh:access` 三个 tag。用例：`sessionTagsAreLeftOutOfTheRequestUnlessTheDeploymentOptsIn`（默认不带）、`sessionTagsNameThePrincipalTheScopeAndTheDirection`、`sessionTagValuesAreSanitisedToTheStsCharset`（逗号规整 + 空值占位）。**默认关的原因写在 §5.6**：信任策略不放行 `sts:TagSession` 时带 tag 会 `AccessDenied`，默认开会把升级变成故障。**未验**：没有真实 STS，`Tag` 集合只在命令对象上断言 |
| ◑ P1-6 | 控制台：创建页三态来源 + 参数 + 引擎片段；catalog 列表与详情标出「长期凭据 / 短期凭据 / 不下发」 | **已落地「标记」这一半**：判定在服务端（`CatalogCredentialSource`，四档 `STS` / `RELAY` / `NONE` / `INVALID`），经批量端点 `GET /api/console/v1/catalog-credential-sources` 下发；catalog 列表多一列、详情页多一段（含「取票要哪个权限点」与「能否续期」）。**与 §10 的偏差**：原型里那三个**每 catalog 的新字段**（`credentialProviders` / `credentialTtlSeconds` / `credentialReadOnly`）P0 没有采用——来源是由现有 `storageConfigInfo` + 全局 `credential.providers` 推断出来的，因此不需要新字段、也不需要接口形状变更。**已补**：新建 / 编辑表单里的实时预览（`POST /api/console/v1/catalog-credential-source`，输入是还没保存的草稿，服务端用同一个判定）——它替掉了原型的「行内提示」，因为要说的不止 `roleArn` 一条（见 §10.9）。**未做**：创建页的三态选择器、`stsUnavailable` 冲突框、五面引擎片段（§10.2–§10.6），理由是选择器会与「存储配置本身就能表达来源」重复（见 §10.8 / §10.9） |
| ✅ P1-7 | **判权拆两个权限点**：`CATALOG:USE_CREDENTIAL` 与 `CATALOG:USE_SECRET`（§3.4.3） | 落地形态：`/token` 与 `/credentials` 改判 `USE_CREDENTIAL`（`/auth`、`/plan` 仍是 `SELECT`），票里含长期密钥对时再判 `USE_SECRET`；`VendedStorageCredential#carriesLongTermKeyPair()` 是唯一判据。用例：`CredentialAccessSplitTests` 五条（只有取票权→403 点名 `USE_SECRET`；两个都有→照常下发；只有 `USE_SECRET`→403 点名 `USE_CREDENTIAL`；都没有→403；判据只看载荷不看存储类型）+ `LanceCredentialAccessTests` 四条（Lance 面的两道判定、短期票免 `USE_SECRET`、不带参数不判权也不带密钥）+ `CatalogEndpointAuthorizationTests` 的映射与作用域断言。**行为变更**：细粒度 `SELECT` 授权升级后需补 `USE_CREDENTIAL`，`ALL` 不受影响 |
| P1-8 | ✅ **AK ID 打码 + 打码回写哨兵**（§3.4.4）：`withoutSecrets` 一个出口打码，`mergeStaticCredentials` 认哨兵；`******` + 空密钥 = 两个都不动，`******` + 新密钥 = 只换密钥，库里没有原值或把哨兵当密钥则 400 | 用例：`StaticCredentialApiTests` 的 `maskedAccessKeyIdSurvivesARenameOnlyUpdate`（设计稿点名的验收）、`maskedAccessKeyIdWithOmittedSecretKeepsBoth`、`maskedAccessKeyIdWithANewSecretRotatesOnlyTheSecret`、`maskedAccessKeyIdOnANewCatalogIsRejected`、`maskedValueAsSecretIsRejected`；范围仅 catalog 的 `storageConfigInfo`，执行集群那套凭据仍回显 |
| ✅ P1-9 | **服务端自身 FileIO 走独立取票**（§5.7），作用域是 catalog 的允许位置，不复用任何用户的表级票 | 两类作用域分开（`vend` vs `vendForCatalog`）、写权限显式传、缓存键不撞 → `ServerSideCredentialTests`（9 例）；「只配 `roleArn` 的 catalog 上优化任务跑通」**需真实 S3，未跑** |
| P1-10 | **联邦/多级 catalog 的 header 透传与 refresh 端点回写**（G17，对照 #12950） | 现在没有联邦，留位；做的时候按 #12950 的两条断言写用例 |
| ✅ P1-11 | **S3 兼容存储的 `noInlinePolicy` 选项**（§3.5.3）：为 true 时省略 inline session policy，界面明说「此存储下无法按表收窄」 | 落地：字段加在 `AwsStorageConfigInfo`（record，紧跟 `stsUnavailable`）；`S3TokenCredentialProvider` 在 true 时传 `policy=null` 并打一条 WARN（「票的作用域是角色自身，不是这张表」）；`CatalogCredentialSource` 的 STS 档改成「无法按表收窄」的口径，那条原文直接显示在表单的「保存后凭据来源」里。用例：`noInlinePolicyOmitsTheSessionPolicyWithoutChangingTheTicket`（断言 `policy()` 是 **null**，不是空串）、`noInlinePolicyStillRefusesLocationsItCannotScope`（不带 policy 时「认不出作用域就不签」更重要）、`noInlinePolicyConflictsWithStsUnavailable`（400，语义互斥）、`noInlinePolicySurvivesPersistenceAndEveryRewrite`（`copyWith` / `withCredentials` 漏字段会让它「保存一次就悄悄丢」）。**与设计的偏差**：默认值不是「不带」而是「带」，理由见 §3.5.3 末尾——判据（接受/拒绝 policy）在 `storageType=S3` 上加 `endpoint` 这个形态里根本不存在。**未验**：VAST 真机没跑过，`policy=null` 这一侧只有单测；「省略 policy 后仍 501」只可能是该实现连 `DurationSeconds` 都不支持，那是另一条路 |
| ✅ P1-12 | **腾讯云 COS 落地**（§6.3.5 六处）：`StorageType.COS` / `TencentCosStorageConfigInfo` / `FileIoType.COS("cosn")` / `COS_KEYS` / `Storage.Cos` / `paimon-cosn` 依赖 | 键名与来源由用例钉住：`cosVendsItsOwnKeyFamilyWithExactCase`（大小写敏感的 `secretId` / `secretKey` + 区域 + 端点 + `useHttps`）/ `cosDefaultsToHttpsUnlessExplicitlyDisabled` / `cosKeepsLocationKeysWhenStsUnavailable` / `cosFallsBackToEnvironmentWhenNothingConfigured` / `cosPrefersNamedStorageAndFailsOnUnknownName` / `cosConfigCarriesEndpointRegionAndAppId` / `cosAppIdMustMatchTheBucketName`；静态凭据的 HTTP 语义并入 `obsOssAndCosAlsoAcceptStaticCredentials`。**「`cosn://` 端到端跑一次」需真实 COS 账号，未跑**（同 P0-8 的偏差） |
| ❌ P1-13 | **~~COS 临时档必发 `fs.cosn.credentials.provider`~~（G18）——经核查作废**：COSN 的 FileIO 既没有令牌键、也没有 session-token provider，签出来的票没有消费者（§6.3.4） | 反向验收：`cosNeverVendsASessionTokenOrAProviderClass` 断言这两类键一个都不出现；`cosRefusesToVendATemporaryCredential` 断言「配了 `sessionToken` 就 500 并点名 `lakehouse.rest.storage.cos`」。**上游补上这个 provider 之前，本节不能按原样实现** |

### P2：工程化

| # | 做什么 | 为什么排后面 |
| --- | --- | --- |
| ✅ P2-1 | 刷新路径 singleflight + STS 限流退避 | 落地：`StorageCredentialCache#resolve`（同键并发未命中只签一次、失败不缓存、**作废代数 fencing** 让作废期间完成的签发不写回）+ `ThrottlingGuard`（限流重试 200ms 起翻倍、耗尽后按 provider 冷却、冷却期内直接 503 不打云）+ `ThrottledException`（继承 `ApiException`，仍是 503，分类由各云客户端做）。用例：`StorageCredentialCacheTests` 7 条、`ThrottlingGuardTests` 7 条 |
| ✅ P2-2 | 服务端身份分层（只 `sts:AssumeRole`） | 落地：`credential.require-catalog-signer`（**默认关**）。打开后「配了 `roleArn` 却自带钥匙为空」的 catalog 直接 503 并点名该配置项，**不再退回服务进程的默认凭据链**；拒绝发生在调云之前（用例断言 `FakeSts.last` 为空）。默认关的理由：整个服务共用一个工作负载身份（IRSA）是很多部署的正常形态，改成默认拒绝会让它们升级当天全量读不了表。运维侧的 IAM 最小集见 §11.1。用例：`VendingCredentialProviderTests#requireCatalogSignerRefusesToFallBackToTheServerIdentity`（503 + 一次 STS 都没打）、`#requireCatalogSignerStillSignsWhenTheCatalogCarriesItsOwnKeys` |
| ✅ P2-3 | 「凭据作废」入口（只能靠缩短 TTL + 云侧条件拒绝，无真正吊销） | 落地：`POST /catalogs/{name}/credentials/revoke`（判 `CATALOG:ALTER`），清掉该 catalog 的**两种**缓存键（表级票 + 服务端 catalog 级票）并返回被清的条数。**回执里写死边界**：`alreadyIssuedStillWork=true` + `note` 明说已经发出去的票到期前仍然有效。云侧没有按 token 吊销的 API（G10），这个端点按不了「紧急撤销」那个按钮。用例：`CredentialRevokeApiTests` 2 条（作废后下一读表换新票 / 回执不撒谎 / 第二次清到 0 仍 200 / 未知 catalog 404） |
| ❌ P2-4 | Spark 侧统一插件（B 方案） | **未做**，见 §11：它只在「无 STS 的自建存储 + 引擎侧 FileIO 不消费下发凭据」这个交集里才有用，而这两条目前都不成立；硬写出来只能验证「编译通过」 |
| ◑ P2-5 | 多实例部署下缓存与 STS 调用的配额评估 | 见 §9.2 的算式与结论：**不做共享缓存**，按「实例数 × 并发表数 / 缓存窗口」估。这一项没有代码产物，只有一条运维公式 |

---

## 9. 风险与未决

**9.1 catalog 变成 critical path。** 引擎每次读表都要先过我们。现在的 catalog 挂了只影响 DDL 与
元数据读，接上凭据下发之后**所有读数据也会挂**。这条没有技术解法，只有运维解法：多副本、健康检查、
降级策略（缓存里还有未过期的票时允许继续读）。要明确写进 SLA。

**9.2 STS 配额与延迟。** AWS STS 单账号约 250 TPS（文章给的量级），且每次 loadTable 多一次网络
往返。缓存比例调到 0.15 会让命中率下降、调用变多——这是刻意的取舍（宁可多调，也不给快过期的票）。
需要在上线前按「引擎数 × 表数 / TTL」估一遍。

**P2-5 的算式（多实例配额评估）。** 一条能直接代数的式子，取代「按经验估一下」：

```
每个实例每分钟的签发次数 ≈ 活跃表数 / (ttl_seconds × cache-expire-ratio / 60)
全站调用量               = 上式 × 实例数            # 每个实例各签各的，没有共享缓存
```

代入默认值（`ttl=3600`、`ratio=0.15` → 缓存窗口 9 分钟）：

| 部署 | 活跃表 | 每实例每分钟 | 全站每分钟 | 占 250 TPS 配额 |
| --- | --- | --- | --- | --- |
| 单个 3 副本集群 | 500 | 56 | 167 | 1.1% |
| 单个 3 副本集群 | 5000 | 556 | 1667 | 11% |
| 两个集群（各 3 副本） | 5000 | 556 | 3333 | 22% |

**三个结论，比数字本身更重要：**

1. **瓶颈不是持续流量，是尖峰。** 稳态下几十上百倍的余量很宽；危险的是「一批引擎 9 分钟里陆续
   重启」——那会把一个缓存窗口内的签发压缩到几分钟里，瞬时 TPS 可以到稳态的十几倍。
   P2-1 的 singleflight 削掉的是**同一张表**的重复，削不掉「不同表一起到期」这件事；
   真正兜住尖峰的是 `ThrottlingGuard` 的冷却（把打满变成一段明确的 503，而不是全站变慢）。
2. **不必引入共享缓存。** 表数在 5000 这个量级、单账号配额 250 TPS 的前提下，多实例各签各的
   只吃掉个位数到二十几个百分点。为省这点调用去加一层 Redis，等于给一条已经很关键的路径
   再插一个可能挂的依赖（§9.3 的结论仍然成立）。
3. **要调的旋钮只有一个。** 真到配额紧张，先调大 `cache-expire-ratio`（0.15 → 0.3），
   而不是调大 `ttl-seconds`：前者只影响「缓存多久」，后者会**同时放大泄露窗口**，
   而泄露窗口是接上 STS 想缩小的那个东西。


**9.3 缓存一致性与多实例。** 每个实例各自缓存、各自 assume。同一张表在两台机器上会拿到两份不同
的票（都有效，但审计里会出现两条 session）。可以接受；如果要收敛，得引入共享缓存（Redis），
代价是又一条 critical path。**建议不做。**

**9.4 五种存储没有客户端续期落点**（OSS / OBS / Azure / GCS / COS，见 §5.3）。这意味着 TTL 必须覆盖
单次作业时长。**这是本文最容易被忽略的一条**：接上 STS 之后，"凭据 1 小时过期"在某些存储上仍是
硬约束，只是从"永久密钥"降级成"1 小时窗口"。COS 要分面看：Paimon 面靠 `RESTTokenFileIO` 能续，
Iceberg 面连凭据都收不到，所以「TTL 覆盖作业时长」这条对 COS 只适用于 Iceberg 面。

**9.5 与行过滤 / 列脱敏的硬冲突。** 一旦引擎拿到路径级凭据直读文件，服务端的 `PolicyRelation`
注入就绕过去了。[`row-filter-column-mask-design.md`](row-filter-column-mask-design.md) 的能力表里
「直读存储文件不绕过」这一行的结论就是 **❌ 做不到**，并把它归在 L0；那份文档给的升级路径是
「加一层网关（L1）或收掉凭据来源（L2）」。**凭据下发不会改变这个结论**——它把"永久密钥直读"
换成"短期票直读"，缩小的是时间窗口，不是能力边界。两者不能同时要：要么放弃行/列级强制（只做
路径级），要么所有数据访问都回网关（方案 E，吞吐与成本另算）。**这是产品决策，不是技术选型**，
必须在动手前拍板——否则做完 vending 才发现策略要重做一遍。

**9.6 兼容 S3 的静态路径不能删。** MinIO / Ceph / Ozone 在很多内网环境里没有 STS。删掉 relay
等于把这些人挡在门外。要保留，并且**在控制台上把风险显性化**（"本 catalog 下发的凭据永不过期"）。

**9.7 `credential-secret-key` 轮换的爆炸半径。** 换了密钥，所有 catalog 的静态凭据都解不开，
表现为 500（而不是降级）。这是刻意的（不给安全承诺开后门），但轮换流程要写进运维手册：先双密钥
可解 → 再重写全部密文 → 最后下掉旧密钥。~~目前没有"双密钥"能力，是第一件要做的基础设施。~~

**已实现（2026-10-30）。** `lakehouse.rest.storage.credential-secret-key-previous` 补上了
第一步：解封先试当前钥、再试上一把；**加密永远用当前钥**（若两把都能加密，库里会永远一半新
一半旧，轮换就永远结束不了）。旧钥解成功的每一次都打一条 INFO——它同时是「这部分密文还没被
重写」的唯一信号，第三步「删掉 previous」就取决于这条日志有没有了。

两个配置错误启动即失败：配了 previous 却没有当前钥（会造出「新存不了、旧的还能读」的怪状态）、
两把填成一样（轮换看着配好了、实际什么都没发生）。

**剩下的第二步仍是手动的**：把还带旧密文的 catalog 逐个重新保存（`PUT` 时原样再交一次
`secretAccessKey`）。没有做批量重写端点——那需要扫全表、逐行解密再加密，是一个独立的后台任务，
且失败时要不要回滚没有便宜的答案；在那之前，「按日志点名重存」是可执行的做法。

**9.8 凭据进日志。** `VendedStorageCredential` 的 `source` 刻意不进 `token()`，就是为了避免
"诊断信息混进引擎配置"。新增 provider 时要守住同一条：**密钥只进响应体，不进日志、不进事件
payload、不进审计明细**。

**9.9 一份 COS 配置在三个面上不是同一件事。** Paimon 面能续、Iceberg 面收不到下发凭据（要自配
Hadoop 配置）、Lance 面得换成 S3 兼容（§6.3.2）。用户看到的是「一个 catalog」，三个引擎看到的却是
三种不同的事实。最典型的误判是「同一个 COS catalog，Spark·Paimon 作业能跑、Spark·Iceberg 却 403」——
这不是配置写错，是架构差异，但现场看起来像同一份配置的两个 bug。**控制台必须分面提示**
（§10.4），文档也必须把这条写在显眼处，否则支持成本会全部落在「为什么有时候行有时候不行」上。

---

## 10. 控制台：创建 catalog 时的凭据下发配置

配套原型：[`credential-vending-mockup.html`](credential-vending-mockup.html)（单页，右侧摘要与四段引擎
片段随选择实时变化；支持查询串直开某个状态，见 §10.7）。**这一章只设计交互，不改控制台源码。**

### 10.1 为什么是三选一，不是一个开关

「是否开启凭据下发」这个问法少了一维：**「开启」有两种，而且它们在客户端配置上完全一样。**

| 档位 | 服务端做什么 | 客户端配置要不要改 | 风险 |
| --- | --- | --- | --- |
| **不下发** | 只发定位键（endpoint / region / path-style） | **要**：引擎得自己配 AK 或自带云身份 | 中：身份在引擎侧，我们看不见也管不了 |
| **转发服务端密钥** | 把配置里的长期 AK/SK 原样发出去（今天的行为） | **不要** | **高**：密钥永不过期，泄漏只能轮换 |
| **签发短期凭据** | 现签、按表收窄、读写分离、到期失效 | **不要** | 低 |

关键观察在第二、三行的「不要」：**后两档在 Spark / Flink 侧一行都不用改**，差别全在服务端发的是哪把钥匙。
也就是说「配置看起来一样，安全性差一个量级」——唯一能区分它们的地方就是控制台上的标记。
如果把「转发 / 签发」藏进高级参数，所有人的认知都会停在默认值上。

由此推出一条硬要求：**catalog 列表页也要有这个标记**，不能只在详情的折叠区里。原型把它放在创建页右栏
「生效摘要」与列表徽标两处；落地时摘要那一栏去掉了（判定回到第 3 节的三档卡片），标记留在
列表徽标与表单内的草稿预览上——**这条要求本身没变**：两档在配置上等价，标记是唯一的区分。

### 10.2 按存储类型出哪些参数

| 存储 | 签发者（二选一或唯一） | 参数 | 必须写在旁边的备注 |
| --- | --- | --- | --- |
| S3 | 服务端身份（IRSA / 任务角色）/ AK-SK | `roleArn`、`externalId`、`stsEndpoint`、`region`、`stsUnavailable`、`noInlinePolicy` | 填了 `roleArn` 就自动升级（§3.4.2）；`stsUnavailable` 与「转发」是两回事（§3.5.2）；`noInlinePolicy` 是 S3 兼容存储收窄不了时的退路（§3.5.3） |
| OSS | 同上 | `roleArn`、`externalId`、`region` | endpoint 与 region 必须匹配，否则签名失败但报错像网络问题 |
| OBS | **只有委托（agency）** | `agency`、`domainId` | 华为的临时凭据接口要走委托（或联邦令牌），没有「拿 AK/SK 直接换票」这条路径——没配委托就没得签，只剩转发，界面要当场说（§3.5.2） |
| AZURE | 托管身份 / 应用注册 | `tenantId`、`account`、`clientId`、`clientSecret`、`skipSasTokenGeneration` | 签的是目录级 SAS，平台上限 7 天；`skipSasTokenGeneration` 是 Azure 的「不 vend」开关（§6.2） |
| GCS | 服务账号文件 / ADC | `serviceAccountFile`、`skipCredentialDownscoping` | 同一个文件既要签降权令牌、又要供服务端自己的 GCSFileIO 用；`skipCredentialDownscoping` 是 GCS 的「不 vend」开关（§6.2） |
| COS（定案 COSN 原生，§6.3） | **只有 AK-SK / 具名存储（没有签发）** | `appId`、`bucketRegion`、`endpoint`、`useHttps`、`stsUnavailable` | **COS 没有「签发」档**：COSN 的 FileIO 不收临时凭据，`roleArn` 在这一类型上没有意义（§6.3.4）。**「COSN 原生」只覆盖 Paimon 面**：同一份配置生成三条不同片段（Paimon `cosn://`、Iceberg `HadoopFileIO` 且拿不到下发凭据、Lance 退回 S3 兼容）。三面写成一样就是让两个面静默失败（§6.3.2） |

**只有 S3 的「AK-SK」档要额外提示一句**：那把 AK 的权限应当只有 `sts:AssumeRole` 一条，
不能同时能读写桶——否则把「短命票」换成了「一把权限更大的长命钥匙」。

### 10.3 自动升级与三类冲突

| 情形 | 界面行为 | 为什么 |
| --- | --- | --- |
| 填了 `roleArn`，来源还停在「转发」 | 行内提示「将升级为签发」并**真的切过去** | 抄 Gravitino 的自动探测（§3.4.2）；静默升级不行（用户以为还在转发），所以必须说出来 |
| 勾了 `stsUnavailable=true` 又选「签发」 | **冲突框 + 保存按钮禁用** | 语义互斥，不是格式问题；服务端拒绝时说不清是哪一条 |
| 选了「转发」但部署没配 `credential-secret-key` | 沿用现有 `staticCredentialsEnabled` 那套：输入框禁用 + 告诉用户去开哪个配置项 | 这条已经实现，不重复设计 |
| `roleArn` 与静态 AK 同时填 | **只提示不拦** | 服务端今天接受这个组合；前端比服务端严，会让一份服务端认可的配置在控制台里存不回去 |

最后一行是现有代码里已经写下的取舍（`StorageConfigFields.vue` 的 `credentialConflict`），新设计必须沿用，
否则同一个人在两处看到两种严格程度。

### 10.4 「提示 Spark / Flink 怎么配」的四条生成规则

这一段是本次交互设计的重点：**片段不是文档的搬运，它是唯一能证明「刚才那个选择生效了」的东西。**

1. **只生成当前 catalog 真实可用的键**，不生成「建议值」。`uri` 用当前访问控制台的 origin，
   `warehouse` 用 catalog 名（不是路径）——这两条写错的表现都是「一条语句都不通」，而且报错不提原因。
2. **「不下发」必须显式写出要自己配的键**，并把 `data-token.enabled` 关掉。少一行不是简化——
   它会让用户在第一次读数据时才发现，而失败现场在存储侧。
3. **每个面的坑写在同一屏内**：`token.provider=bear` 不是 `bearer`、Lance 的 `delimiter=$`、
   Iceberg 的 delegation 头靠 `header.*` 注入。放到「更多文档」里等于没写。
4. **必须写明能不能续期**。因为除「不下发」外，另两档的配置长得一样（§10.1），
   「能不能撑过长作业」是唯一还能区分它们的运行时信息。

五面片段的差异：

| 面 | 签发时能删掉密钥配置吗 | 能续期吗 | 那一行注释必须说什么 |
| --- | --- | --- | --- |
| Spark · Paimon | 能 | 能 | `token.provider=bear`（不是 `bearer`） |
| Spark · Iceberg | 能 | 补 G3/G4/G5 之后才能 | delegation 头是 `header.` 前缀注入，不是内置开关 |
| Spark · Lance | 能（`storage_options`） | **不能** | 作业时长必须 < 有效期，否则中途开始 403（§7.4） |
| Flink · Paimon | 能 | 能（同一机制，未 e2e） | `data-token.enabled` 只在 Paimon 面有效（§7.6） |
| Flink · Iceberg | 能 | 补 G3/G4/G5 之后才能（未 e2e） | `catalog-type=rest` + `cache-enabled=false`；delegation 头靠 `header.*` 注入 |

**存储维度上的例外：腾讯云 COS。** 上表是「面」的维度，COS 会在这个维度上再切一刀，而且
切法与「面」正交（§6.3.2）：

| 面 | 选了 COS 之后片段怎么变 | 必须同时说的一句话 |
| --- | --- | --- |
| Spark / Flink · Paimon | `warehouse` 用 `cosn://<bucket>-<appid>/...`，密钥行写 `fs.cosn.userinfo.*` | **COS 没有「临时档」**：COSN 不收会话令牌，别去设 `fs.cosn.credentials.provider`——那个类不存在，会出现「… is not cos credential provider」（§6.3.4） |
| Spark / Flink · Iceberg | **不能写 `cosn://` 就完事**：要显式 `'io-impl' = 'org.apache.iceberg.hadoop.HadoopFileIO'`，并把 `fs.cosn.*` 放进 Hadoop 配置（`spark.hadoop.*`） | **服务端下发的凭据到不了这一面**（`HadoopFileIO` 不吃 `storage-credentials`），必须客户端自配 |
| Spark · Lance | **整段换成 S3 兼容**：`s3://` + `aws_access_key_id` / `aws_secret_access_key` / `aws_endpoint` 指向 COS 的 S3 端点 | Lance 是 Rust，没有 COSN；`cosn://` 它会当成不认识的 scheme |

所以 COS 这个选项在控制台上**不能只改 `warehouse` 前缀**，它要连片段生成逻辑一起分叉——
这是「选 COS」与「选 S3」在交互上的实质区别，也是本次定案最容易被漏掉的一条。

### 10.5 与现有存储表单的关系：再加一张字段表，不要加进折叠区

`StorageConfigFields.vue` 已经把「存储类型 → 字段集」做成数据驱动（`BASIC_FIELDS` / `ADVANCED_FIELDS`），
凭据这一段应当**再加一张 `CREDENTIAL_FIELDS`**，而不是塞进 `ADVANCED_FIELDS`。理由是两张表的语义不同：

- `ADVANCED_FIELDS` 的约定是「全部留空即用服务端默认值」，收起的成本是「用户以为有必填项」；
- 凭据来源的默认值恰好是**最危险的那一档**（转发长期密钥）。把它收进折叠区，等于让所有人在不知情下
  选中高风险项。

另外两条落地约束，都是为了避免新旧语义打架：

1. **「移除已保存凭据」那套语义不能被叠进来源选择器。** 现有实现里「空串 = 保持」「成对空串 = 清除」
   「不出现 = 不变」三种含义已经很难解释；再让一个三态单选去承担它，会变成四种。
   所以：来源是来源，AK/SK 的两个输入**只在「转发」档出现**。
2. **引擎片段不进表单区。** 它是「复制给人用」的东西，不是需要校验的配置；放在同一页的
   独立卡片里（第 4 节），与保存动作解耦。**不做折叠**：折叠之后「填的过程中一直看得见」
   这条就没了，而那是它从文档搬进页面的唯一理由。

### 10.6 校验分工：前端只拦「说了也白说」的

| 情形 | 前端 | 服务端 | 为什么 |
| --- | --- | --- | --- |
| 选了签发但没填 `roleArn` | 拦（按钮禁用 + 行内提示） | 400 | 报错信息能贴着字段显示 |
| `stsUnavailable` 与签发并存 | 拦（冲突框） | 400 | 语义互斥，越早说越好 |
| 未配 `credential-secret-key` 却选转发 | 拦（沿用现有） | 400 | 已经实现 |
| 有效期超过该存储上限 | 滑块 `max` 就是上限 | 400 | 滑块不拦住不等于云不拒；上限随类型变（§10.2） |
| `roleArn` + 静态 AK 并存 | **只提示** | 接受 | 前端比服务端严会让合法配置存不回去 |
| assume 失败（角色不存在 / 信任关系错） | 拦不了 | 503 | 只有真调一次才知道；不要假装能预检 |

### 10.7 原型怎么用

[`credential-vending-mockup.html`](credential-vending-mockup.html) 是单页静态原型，不需要构建：

- **交互**：点存储类型、点三态来源、拖动有效期、切换五个引擎页签（Spark·Paimon / Spark·Iceberg / Spark·Lance / Flink·Paimon / Flink·Iceberg），右侧摘要与报文预览实时跟着变；
- **查询串直开某个状态**（评审时不用手点）：
  `?storage=OSS&source=relay&engine=spark-lance`、`?source=none&storage=AZURE`、
  `?storage=GCS&engine=flink-paimon&rw=1&ttl=900&conflict=1`、
  **`?storage=COS&source=sts&engine=spark-iceberg`**（看 COS 三面的片段怎么分叉，§6.3.2）；
- **`conflict=1`** 演示「`stsUnavailable` 与签发冲突」，保存按钮会变成禁用；
- **报文预览**里绿底是本设计新增的字段（`credentialProviders` / `credentialTtlSeconds` /
  `credentialReadOnly`），其余是现有 `StorageConfigInfo` 已有的。

原型里刻意保留了两处「做不到」的演示：OBS 选 AK-SK 会**自动退回转发**（那家没有 AK 换票的接口），
Lance 在签发档仍会标注「只读一次、长作业会过期」。这两条不是为了好看，是为了让评审时先看见边界。

### 10.8 落地偏差：不新增每 catalog 的凭据字段（P1-6 第一半）

原型里那三个新字段——`credentialProviders` / `credentialTtlSeconds` / `credentialReadOnly`——
**P0 没有采用，P1-6 也没有补**，这不是遗漏，是两条理由：

1. **来源本来就可推断。** `StorageConfigInfo` 已经有 `roleArn` / `stsUnavailable` /
   `storageName` / 静态凭据，`credential.providers` 在服务端全局。把它们合起来走一遍
   凭据管理器同一条优先级，就能得出三档（实现见 `CatalogCredentialSource`）。
   再加一个「来源」字段，等于把同一件事写两遍——而两份写法一旦不一致，
   以哪一份为准就没人说得清。
2. **第三个字段会与「谁能用」打架。** `credentialReadOnly` 的语义是「这张表只发只读票」，
   而读写分离今天由 session policy 按**调用上下文**（`allowWrite`）决定，
   不是 catalog 的属性。放进 catalog 就成了「同一张表在两条路径上权限不同」，
   与 §5.5 的作用域规则冲突。

于是落地形态改成：**服务端算、控制台只翻译**，端点
`GET /api/console/v1/catalog-credential-sources`（批量、只读、可见性照抄管理 API）。
四档里多出的 `INVALID` 是原型没有的：它覆盖两种**配置错误**——引用了不存在的具名存储、
COS 配了 `sessionToken`（COSN 没有令牌位）——这两种在原型里都只能等到取票 500 才暴露。

### 10.9 落地偏差：表单预览用「草稿判定」，不做三态选择器（P1-6 第二半）

原型的创建页是一个三选一（签发 / 转发 / 不下发）+ 每档的参数 + 引擎片段。落地形态是
**三张只读卡片 + 服务端判定的原文**（`CatalogFormView` 第 3 节），当前那一档打「当前」标、
另外两档写明「要落到这一档，第 2 节里必须有哪个字段」；判定本身仍是一个只读预览，
紧跟在「存储类型」下面，内容是那句「保存后凭据来源：<标签> —— <走的是哪条分支>」。

它不是选择器，因为三档的选择在表单上**已经由现有字段表达**（填静态凭据 / 填 `roleArn` /
勾 `stsUnavailable` / 留空走环境链）；再造一个三选一去改同一批字段，会生出
「选了转发但静态凭据是空的」这类只在界面上存在的状态，而服务端并不认识它。

实现上刻意与列表页共用同一个判定：

- 端点 `POST /api/console/v1/catalog-credential-source`，请求体就是管理 API 里那一份
  `storageConfigInfo`（同一套多态反序列化），服务端调同一个 `CatalogCredentialSource.of`
  ——因此不会出现「表单里说会签发、建完在列表里显示转发」这种两处各算一遍的错位。
- 用 POST 而不是给批量端点加草稿参数：两者答的问题不同。批量那条是「这个 catalog
  现在会怎样」（库里查得到，还要按可见性过滤），这条是「我这样填完保存会怎样」
  （库里还没有，无从谈可见性）。附带的好处是草稿可能带打码的 `accessKeyId`，
  放在请求体里不会进访问日志。
- 前端只有一个组合函数（`composables/credentialPreview.js`）：去抖 400ms、丢弃过期响应、
  失败就把整条藏起来。它**不认识任何判据字段，也不出现档位字面量**——
  脚本第 24 组对它与翻译层用同一套反向断言。这条护栏不是形式主义：这个组件里
  `roleArn` / `storageName` / 静态凭据全都在手边，「顺手 if 一下就能推断档位」
  看起来比发一次请求便宜，而那正是漂移的起点。

**三态选择器仍然不做**（理由同上，另加一条实测出来的：三档的判据字段在第 2 节里，
「点一下就能落到这一档」在缺字段时落不下去——点了「签发」却没有 `roleArn`，判定仍是
「不下发」，卡片当场又灭，看起来像坏了。页面上因此把「你现在在哪一档」与「要落到这一档
缺什么」分开写，缺的那一项给一个跳回第 2 节的入口）。

**五面引擎片段已经做了**（`lakehouse-rest-console/src/utils/engineSnippet.js`），落点是
创建 / 编辑页的第 4 节，是**页签 + `<pre>` + 复制**，不是原型的折叠卡：这段东西的价值
在「填的过程中一直看得见」，折叠起来就退回成文档的搬运。三条实现约定与 §10.4 的四条规则
一一对应：

- **按面各写一份，不做抽象**。`token.provider=bear` 不是 `bearer`、Iceberg 的 delegation
  头靠 `header.*` 注入、Flink 用 `CREATE CATALOG` 而 Spark 用 `spark.sql.catalog.*`、
  Lance 要多一个 `delimiter` 且基址写到 `/lance` 为止——抽象成「通用配置」的结果是
  每一面都跑不起来。
- **`warehouse` 写 prefix（catalog 名），物理路径只进注释**。REST 客户端的 `warehouse`
  就是 catalog 名，路径由服务端解析；把 `s3://…` 塞进某个键会让人以为改这里就能换仓库，
  那要重建一个 catalog。
- **注释写进片段里，而不是只写在页面上**。复制出去的是那十几行，页面上的提醒不会跟着走，
  而最容易踩的几条（`bear` 不是 `bearer`、Lance 的凭据只读一次、COS 只有 Paimon 面认）
  都藏在复制之后才发作。

Lance 只有 Spark 一个页签：connector（`org.lance.spark`）没有 Flink 版，摆一个出来只会
让人去找它。片段这一层的用例在 `tools/snippet-tests/`（`npm test` 里跑，
`npm run test:snippet` 单独跑）：它错了不会让控制台报任何错——复制出去的东西只在业务方
那边失败。

---

## 11. P2-2 与 P2-4：身份分层的最小 IAM，以及 Spark 统一插件为什么不做

### 11.1 服务端身份应该只拿到 `sts:AssumeRole`

代码侧 P2-2 只做了一件事——`credential.require-catalog-signer`（默认关，见 §8 的 P2 表）。
真正的分层在 IAM 里，形态就是下面两条：**服务端的身份能 assume，不能读写数据**；
**角色上才挂数据面权限，并且按 catalog 分角色**。

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": "sts:AssumeRole",
    "Resource": [
      "arn:aws:iam::111122223333:role/lakehouse-data/team-a-*",
      "arn:aws:iam::111122223333:role/lakehouse-data/team-b-*"
    ]
  }]
}
```

三条容易被忽略的配套：

1. **`Resource` 要写成前缀通配，不要写 `*`。** 写成 `*` 之后，服务端一旦被拿下，
   攻击者可以 assume 账号里任何一个角色——包括管理员。
2. **被 assume 的角色要信这个身份**（`TrustPolicy` 的主体是服务端的角色 ARN / IRSA 的
   OIDC 主体），且**只信它一个**；顺手放一个 `"Principal": {"AWS": "*"}` 等于前面全白做。
3. **`stsUnavailable=true` 的 catalog 与这条无关。** 那一档压根不签，走转发；
   把它也算进「服务端身份」的讨论只会让人以为转发档也能被这条 IAM 收紧。

`require-catalog-signer=true` 之后，「用哪把钥匙去 assume」就从「部署身份」下移到
「catalog 自己带的那对 AK/SK」。这时那对钥匙的权限应该是**只有 `sts:AssumeRole`**——
它能 assume 到的角色才是数据面权限的边界。这一点在代码里没有开关可验证（要真实云），
所以它只写在文档里，靠 IAM 评审兜住。

### 11.2 Spark 统一插件（B 方案）：现在不做，理由是它解决不了任何现存问题

设计稿最早把它列进 P2 时，假设是「引擎拿不到凭据的时候需要一个统一插件去取」。
现在逐条核对，这个假设在四条路径上都不成立：

| 引擎 × 协议面 | 现状 | 还需不需要插件 |
| --- | --- | --- |
| Paimon（Spark / Flink） | `RESTTokenFileIO` 自己调 `/token`，按 token 作缓存键重建 FileIO | **不需要**：它已经把「取票」这件事做完了 |
| Iceberg（Spark） | `X-Iceberg-Access-Delegation: vended-credentials` + `client.refresh-credentials-endpoint` | **不需要**：`iceberg-aws` 自带 `VendedCredentialsProvider` |
| COS（Paimon 面） | `COSNFileIO` 包在 `RESTTokenFileIO` 外，能续 | **不需要** |
| COS（Iceberg / Lance 面） | 收不到凭据（§6.3.4） | **插件也救不了**：COSN 没有令牌位，问题在存储实现，不在取票方式 |

也就是说，**只有一种情形需要 B 方案**：某个自建存储既没有 STS（所以只能转发长期密钥），
它的 FileIO 又**不消费下发的 `credentials`**（所以连转发都拿不到）。这两条同时成立时，
一个「在 Spark 侧主动调管理 API 取票并写进 Hadoop `Configuration`」的插件才有意义。

而这两种失败模式在**别的层**已经有更便宜的答案：

- 「没有 STS」是运维事实，用 `stsUnavailable=true` 表达，转发档照常工作（§9.6）；
- 「FileIO 不消费 credentials」是兼容性问题，症状是「配了也读不了」，
  应该用启动自检报出来（哪个 catalog 的哪种存储会让凭据落空），而不是再写一条取票链路替它兜底。

**结论：不写。** 真要写，前提是先有一个具体的存储实现与一套能跑通的实测环境；
在没有它们的情况下写出来，最多能验证「编译通过」——那正是本文档反复批评的
「看起来在跑、其实没生效」。等出现真实需求时，按 §7.1 的表新增一行，比现在猜着写要省得多。

---

## 12. 附录

### A. 怎么观察现状（不启动引擎，纯 curl）

```bash
# 换一张主体令牌（有 TTL，默认 1h）
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/catalog/v1/oauth/tokens \
  -d grant_type=client_credentials -d client_id=... -d client_secret=... \
  -d scope=PRINCIPAL_ROLE:ALL | jq -r .access_token)

# ① Paimon 面：看下发了哪些键、expiresAt 是多少
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://127.0.0.1:8080/v1/<prefix>/databases/<db>/tables/<t>/token" | jq

# ② Iceberg 面：显式要凭据
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://127.0.0.1:8080/v1/<prefix>/namespaces/<ns>/tables/<t>/credentials" | jq

# ③ Iceberg 面：loadTable 带 delegation 头，并检查 config 里有没有 refresh 端点
curl -s -H "Authorization: Bearer $TOKEN" \
  -H "X-Iceberg-Access-Delegation: vended-credentials" \
  "http://127.0.0.1:8080/v1/<prefix>/namespaces/<ns>/tables/<t>" | jq '.config, ."storage-credentials"'

# ④ Lance 面：describe 要凭据
curl -s -H "Authorization: Bearer $TOKEN" -X POST \
  "http://127.0.0.1:8080/lance/v1/table/<cat>%24<ns>%24<t>/describe?vend_credentials=true" | jq .storage_options
```

判断标准很简单：**响应里有 `s3.secret-access-key` 但没有 `s3.session-token`** → 现在是转发，不是下发。
`expiresAt` 比当前时间大 3600 秒，但云上那把钥匙并没有到期时间——这就是 §0.2 说的事。

### B. 客户端源码里的四处判据（换版本时会漂，改之前先核）

| 位置 | 判据 |
| --- | --- |
| `iceberg-aws` 的 `VendedCredentialsProvider.credentialFromProperties()` | 四个键齐全才认静态值，否则去拉刷新端点；`prefetchAt = expiresAt - 5min` |
| `iceberg-aws` 的 `AwsClientProperties.credentialsProvider(...)` | **只有** `client.refresh-credentials-endpoint` 非空时才返回 `VendedCredentialsProvider`；否则退回静态 |
| `iceberg-aws` 的 `S3FileIO.clientByPrefix()` | 按 `credential.prefix()` 建 per-prefix client，过滤 `startsWith("s3")`；配置合并顺序是「catalog 属性 ← credential.config」 |
| `iceberg-core` 的 `RESTSessionCatalog.newFileIO(...)` | 把 `LoadTableResult` 的 `credentials` 转成 `StorageCredential` 交给实现了 `SupportsStorageCredentials` 的 FileIO |

### C. 术语

| 词 | 本文的含义 |
| --- | --- |
| 凭据下发 / vending | catalog 用**自己的身份**向云申请短期、按路径收窄的凭据并下发给引擎 |
| 凭据转发 / relay | 把配置里的长期密钥原样发给引擎（本工程现状） |
| 作用域收窄 | 把凭据的权限从整个 bucket 压到单表路径，并分离读写 |
| 续期 | 客户端在凭据到期前主动换一张新的（服务端只需提供端点与过期时刻） |
| 双角色 | 服务端身份只能 assume 到 data access role，本身不能读写数据 |
