# Catalog 级执行集群配置（WebUI 设计稿）

> 一句话：**把「优化任务跑在哪个 k8s 集群、用什么身份跑」从服务端全局配置挪到每个 catalog 上**，
> 提交改用 fabric8 SDK，自建集群给 kubeconfig，EKS / AKS 用令牌；控制台不动一级菜单，
> 只在 catalog 列表加一列和一格按钮。
>
> 配套效果图：[`catalog-execution-cluster-mockup.html`](catalog-execution-cluster-mockup.html)（六个画面，
> 可直接在浏览器里点）。**M1–M3 已落地**（表与端点、fabric8 提交器与按 catalog 调度、列表列与抽屉），
> 落地过程中与原稿不一致的六处见文末 §15；分期见 §13。

---

## 0. 现状与要改的三件事

现在「优化任务怎么提交」全部写在 `application.yml` 的 `lakehouse.rest.optimization` 下（16 个键），
提交实现是 `KubernetesJobSubmitter` 直接用 `RestClient` 打 apiserver 的建/查/删三个动词，认证读
`/var/run/secrets/.../token`。它成立的前提是**「本服务端就活在那个集群里」**——一个部署一套集群。

这个前提在两种情况下塌掉：

| 情况 | 表现 |
| --- | --- |
| 多个 catalog 分属不同集群（开发 / 测试 / 生产，或不同云） | 一份全局 `namespace` / `image` / `api-server` 无法同时正确 |
| 服务端跑在集群外，或跑在 A 集群要往 B 集群提交 | `kubernetes.default.svc` 连不上；也没有任何能向 B 证明身份的东西 |

要改的三件事，本稿逐条落地：

1. **配置下移到 catalog**：一个 catalog 一份执行集群配置（1:1），提交参数的唯一来源是它。
2. **提交改用 fabric8**：三种认证形态（kubeconfig / EKS 令牌 / AKS 令牌）都能塞进同一个
   `KubernetesClient`，不必自己维护 TLS + Bearer + 云厂商签名三条 HTTP 路径。
3. **界面不动一级菜单**：catalog 列表加一列「执行集群」、操作列加一格「执行集群」按钮，
   抽屉里配完整份配置。

---

## 1. 五个先定下来的决定

### 1.1 不加一级菜单，理由不是「省事」

「执行集群」**不是一类独立的资源**——它的生命周期完全被 catalog 决定（catalog 删了它就没有意义），
数量也恒等于 catalog 数。给它一个顶级菜单，会立刻长出三个新问题：空列表页（一个新部署进来先看到
一张空表）、孤儿配置（先建集群再建 catalog，或反过来）、以及「到底以哪边为准」的双写。放在
catalog 上，这三件事都不存在。

代价是「我有 10 个 catalog 都跑在同一个集群，要填 10 次」。这个代价用**复制**消掉，不用菜单消掉：
抽屉里有「复用其他 catalog 的连接」——只带连接与认证（`apiServer` / CA / kubeconfig / 令牌 /
namespace），不带批次策略与回调地址（那两项本来就该逐 catalog 不同）。见 §4.4。

### 1.2 配置的宿主是 catalog，1:1，单独一张表

不往 `catalog` 实体上加 15 个列：

- `catalog` 是**协议面**的实体（`/v1/{prefix}` 的元数据与存储位置），它被 Paimon / Iceberg 两套协议
  与 Spark / Flink 客户端共用。往里加 oracle 的 k8s 凭据，等于让「读一张表的元数据」与
  「服务端能不能往某个集群提交 Job」耦合在一起。
- 执行集群配置是**运维面**的东西，它是可选的（很多部署根本不用优化功能），而 `catalog` 的列必须
  对每行都成立。
- 删 catalog 要连带删它——外键 + `ON DELETE CASCADE`，一行 SQL 说清楚。

### 1.3 提交改用 fabric8，是为了「三种身份」而不是「更好看」

现在的 `RestClient` 版能跑，是因为只有一种身份（本集群 SA 令牌）。三种身份意味着三套 TLS 与
Bearer 的组合，手写会变成三个 `if` 分支乘以「CA 从哪来」「令牌怎么续」两维：

| 身份 | TLS | 认证 | 我们自己写要处理 |
| --- | --- | --- | --- |
| kubeconfig | 客户端证书或 CA | Bearer token 或 mTLS | 解析 YAML、选 context、`certificate-authority-data` 是 base64、`insecure-skip-tls-verify` |
| EKS | 集群 CA | STS 预签名 URL（15 分钟内有效） | AWS SigV4 预签名、`x-k8s-aws-id` 头、令牌过期重签 |
| AKS | 集群 CA | AAD 访问令牌（约 1 小时） | OAuth2 client credentials、云环境 authority、scope |

fabric8 的 `OAuthTokenProvider` 正好是「每次请求现取一个令牌」的接口，两个云厂商各实现一个类即可
（参考实现在 `bee` 仓里已经有两份，见 §11.4）。这比自己维护三条 HTTP 路径省下的不是代码量，
是**「令牌什么时候过期」这件事只有一个地方要想**。

### 1.4 `optimization` 段的 16 个键：3 个留、1 个删、12 个下移

| 键 | 处置 | 理由 |
| --- | --- | --- |
| `scheduler-enabled` | **留（代码默认值）** | 它描述「本服务端要不要跑调度循环」，不是「往哪提交」 |
| `scan-interval` | **留（代码默认值）** | 同上，节拍是服务端自己的事 |
| `dispatch-interval` | **留（代码默认值）** | 同上 |
| `submitter` | **删** | `k8s` / `log` 的选择被「配了执行集群 / 没配」取代——`log` 那种「假装提交」在新模型里就是「没配置」 |
| `namespace` | 下移 | 每个集群的命名空间不同 |
| `image` | 下移 | 镜像在 ACR / ECR 里，各集群的仓库与路径不同 |
| `image-pull-policy` | 下移 | 跟着镜像仓库的 tag 策略走 |
| `service-account` | 下移 | 各集群的 SA 不同（IRSA / Workload Identity 都挂在 SA 上） |
| `api-server` | 下移 | 就是「哪个集群」本身 |
| `token-file` | 下移 | 只在 kubeconfig 形态里以 `tokenFile` 出现 |
| `ca-file` | 下移 | 变成 `caCertPem`（粘贴内容，不是服务端路径——服务端读不到你本机的文件） |
| `management-url` | 下移，改名 `callbackBaseUrl` | 同一个服务端在不同集群里的可达地址不同（集群内 Service 名 / 集群外域名 / NodePort） |
| `catalog-token` | 下移 | 它下发给任务用于访问 `/v1/{prefix}`；不同集群的任务可能是不同主体 |
| `max-tables-per-job` | 下移 | 跟集群算力与镜像启动开销绑在一起 |
| `max-concurrent-jobs` | 下移 | 「一个集群同时跑几个批次」——按集群计才有意义 |
| `job-timeout` | 下移 | 同上 |

**`application.yml` 里 `optimization` 整段删除**（不留空注释块）。三个节拍键改为只在
`RestServerProperties` 里有默认值，要调的部署用启动参数覆盖：

```bash
java -jar lakehouse-rest-server.jar --lakehouse.rest.optimization.dispatch-interval=5s
```

> 为什么要连注释一起删干净：yml 里留一个「已废弃」的 `namespace:` 会让下一个人以为它还有效，
> 而实际行为是「填了也不看」——这比报错更难查。这条和 `docs/table-optimization.md` §9.2 里
> 「配置只有一个来源」是同一条原则。

### 1.5 凭据只进不出

`kubeconfig` / `secretAccessKey` / `clientSecret` / `catalogToken` 都是能直接换到集群权限的东西。
读写规则复用 catalog 静态凭据那套（`CredentialCipher`，AES-GCM）：

| 字段 | 写 | 读回 |
| --- | --- | --- |
| `kubeconfig` | 整段（≤ 64 KiB） | `已配置 · 2 个 context · server 10.20.30.41:6443` |
| `accessKeyId` | 明文 | **明文**（它不保密，靠它认「配的是哪把钥匙」） |
| `secretAccessKey` | 密封 | `hasSecret: true` |
| `clientSecret` | 密封 | `hasClientSecret: true` |
| `catalogToken` | 密封 | `hasCatalogToken: true` |

**留空 = 保持原样**（不传该字段就是不改），要清空得显式传开关——**但只有 `catalogToken` 有这个开关**：
其余四个秘密（kubeconfig / AK-SK / AKS 密钥）是建客户端的必需材料，清掉之后连探活都跑不了，
「清空」等于删掉整份配置（那是 `DELETE`），因此不提供 `clearCredentials`（见 §15 第 2 条）；
否则「打开抽屉什么都不改就保存」会把凭据清成空串，这类事故在静态凭据那块已经踩过一次。

---

## 2. 三种集群类型 → 三种认证

`clusterType` 只有三个值，认证方式由它决定（不存在「EKS + kubeconfig」这种组合，见 §3.4）：

| clusterType | 认证 | UI 上的第二段 | 适合 |
| --- | --- | --- | --- |
| `SELF_HOSTED` | `KUBECONFIG` | 粘贴 kubeconfig → 选 context | 自建集群、机房内集群、任何「手里有一份 kubeconfig」的托管集群 |
| `EKS` | `EKS_TOKEN` | 区域 + 集群名 + 访问密钥 | AWS EKS |
| `AKS` | `AKS_TOKEN` | 云环境 + 租户/客户端/密钥 | Azure AKS |

### 2.1 自建集群：kubeconfig 是唯一入口

不提供「手填 apiServer + token + CA」三个框。理由：这三样恰好就是 kubeconfig 里的 `clusters[].server`、
`users[].token`、`clusters[].certificate-authority-data`，让人分三个框手抄一遍，唯一的产出是抄错。
kubeconfig 还能表达「选哪个 context」——多集群 kubeconfig 是常态。

### 2.2 EKS：STS 预签名，不是 AK/SK 直传

EKS 的 apiserver 认的不是 AWS 的 AK/SK，而是 `aws eks get-token` 产出的那个串。它本质是一个
**用 SigV4 预签名的 STS `GetCallerIdentity` URL**（`k8s-aws-v1.` 前缀 + base64），EKS 的
authenticator 拿它回查 STS，确认真的是你就放行。

所以「用令牌认证」在 EKS 上等于：本地拿 AK/SK 签一个 URL，15 分钟内有效，过期重签
（参考实现 `AwsEksOAuthTokenProvider`）。两点必须写进界面：

- **集群名大小写敏感**，它被塞进 `x-k8s-aws-id` 头，写错的表现是 401 而不是「找不到集群」；
- 服务端要能出网到 `sts.<region>.amazonaws.com`（中国区 `.amazonaws.com.cn`）。在无公网出口的
  机房里，令牌签得出来但换不到结果——探活第 1 步会把这句话说出来，而不是等到提交时 401。

### 2.3 AKS：参考实现里的 scope 需要一处修正

参考实现 `AzureAksOAuthTokenProvider` 取的是 `https://management.core.windows.net//.default`
（Azure **管理面**）。AKS 的 apiserver 校验的 audience **不是**管理面，而是 AKS 自己的 AAD
server application id：

```
6dae42f8-4368-4678-94ff-3960e28e3630/.default      # 公共云
6dae42f8-4368-4678-94ff-3960e28e3630/.default      # 中国云（app id 相同，authority 不同）
```

拿管理面 scope 的令牌去打 AKS apiserver，会得到 `401 Unauthorized: Invalid audience`——这个错误
看起来像「SP 没授权」，实际是 audience 不对，非常容易误诊。因此：

- `scope` 做成**可填字段**，默认 AKS audience，留空则用默认值；
- 探活第 2 步专门识别 `Invalid audience` 并把上面这句话原样给出来（§5）。

另外参考实现里有 `subscriptionId` 参数，**它参与构造但不参与取令牌**（构造函数里赋值后从未读取）：
这个设计里**不设这个字段**——一个填了也不影响结果的必填框，只会在被人问起时解释不清。集群在
哪个订阅是「查文档」的事，不是「服务端要知道」的事。

### 2.4 为什么没有 GKE

同样的形状可以平移（`gcloud` / Workload Identity 换令牌），但本仓没有第二个云的实际环境可验，
不写一个「照抄能编译、真跑没验过」的第四种。`clusterType` 是枚举，加值是加一个 case，不影响表结构。

---

## 3. kubeconfig 解析：逐 context 给结论

粘贴 kubeconfig 后**先解析、不保存**（`POST .../kubeconfig/parse`，纯函数，不落库），返回每个
context 的判断。一个 kubeconfig 里通常有多个 context，其中只有一部分是服务端真能用的：

| context 里的认证 | 结论 | 文案 |
| --- | --- | --- |
| `client-certificate-data` + `client-key-data` | **可用** | 客户端证书认证，fabric8 直接支持 |
| `token` | **可用** | 静态令牌；`tokenFile` 也行，但要求服务端能读到那个路径 |
| `username` + `password` | **可用（不推荐）** | Basic 认证；k8s 1.19 起 apiserver 默认关闭，多半会 401 |
| `exec:`（`aws` / `gcloud` / `kubelogin`） | **不支持** | 服务端不能替你执行 `aws eks get-token`；EKS 请改用令牌方式 |
| `auth-provider:`（legacy `gcp` / `azure` / `oidc`） | **不支持** | 旧式 auth-provider 依赖交互式刷新，已在 1.26 前后陆续移除 |
| 没有 user 段 | **不可用** | 匿名访问；apiserver 默认不给匿名建 Job 的权限 |
| `insecure-skip-tls-verify: true` | **可用 + 警告** | 不校验 apiserver 证书，中间人可以换掉它；生产不建议 |
| `certificate-authority: /some/path` | **警告** | 这是**服务端**的路径；读不到就退回 JVM 信任库，自签 CA 必然握手失败 |
| context 指向的 `cluster` / `user` 不存在 | **不可用** | kubeconfig 自身坏了，指出缺哪个名字 |

### 3.1 `exec` 为什么不支持：这是安全边界，不是能力缺失

`exec:` 段的语义是「执行这个命令行，读它的 stdout 当令牌」。一旦服务端照着做，任何能改
执行集群配置的人就能在**服务端进程所在的机器**上执行任意命令——这是 RCE，不是「顺便支持一下
AWS CLI」。所以它必须是硬拒，而且拒绝文案要给出替代路径（切到 EKS 令牌方式），不能只说「不支持」。

同理，`tokenFile` 只在**服务端本地**有意义；kubeconfig 里 99% 的 `tokenFile` 指向的是用户自己
机器的路径。解析结果里对这类路径直接标警告，而不是等保存后第一次提交才 500。

### 3.2 解析结果的形状

```
{
  "contexts": [
    { "name": "paimon-dev", "cluster": "dev", "server": "https://10.20.30.41:6443",
      "auth": "client-certificate-data", "verdict": "OK",
      "note": "客户端证书认证" },
    { "name": "eks-prod", "cluster": "prod", "server": "https://ABC.gr7.ap-northeast-1.eks.amazonaws.com",
      "auth": "exec:aws", "verdict": "UNSUPPORTED",
      "note": "服务端不能替你执行 aws eks get-token；EKS 请用令牌方式" },
    { "name": "aks-china", "cluster": "aks", "server": "https://aks-china-xxxx.hcp.chinacloudapi.cn:443",
      "auth": "auth-provider:azure", "verdict": "UNSUPPORTED",
      "note": "旧式 auth-provider 需要交互式刷新" }
  ],
  "currentContext": "paimon-dev",
  "warnings": ["certificate-authority 指向路径 /etc/k8s/ca.crt，服务端读不到；请改用 certificate-authority-data"]
}
```

界面按这张表渲染成「每行一个 context + 一行结论」，**默认选中 `current-context`**（如果它的结论是
可用）；不可用的行仍然显示（禁用选择），因为「我的 context 去哪了」比「这里有个红叉」更让人困惑。

### 3.3 保存后再看 kubeconfig

不回显原文，只回摘要：`已配置 · 3 个 context（paimon-dev 生效）· https://10.20.30.41:6443`。
想换 context 不用重新粘贴整段——抽屉里「切换 context」是个下拉，只改 `contextName`。

### 3.4 `clusterType` 与认证方式的组合是封闭的

界面上选「EKS」之后，第二段不会再出现 kubeconfig 输入框。这不是 UI 偷懒：EKS 的 kubeconfig
里 `exec: aws` 是标准形态，允许这种组合就等于允许 §3.1 那条 RCE 路径；而「EKS + 静态令牌」
在 AWS 侧也没有对应的签发方式。封闭的组合让「配置自相矛盾」这件事在表单层面就不可能发生。

---

## 4. 抽屉的表单结构

宽度 720px，右侧滑出，五段（效果图画面 2）：

```
┌ 执行集群配置 — paimon                                     [未配置] ✕ ┐
│                                                                     │
│  ① 集群类型   [自建/托管 · kubeconfig] [EKS · 令牌] [AKS · 令牌]     │
│                                                                     │
│  ② 连接       kubeconfig（粘贴）  [解析]                             │
│               context 列表（逐行结论）                               │
│               apiServer（只读回显） CA（来自 kubeconfig）            │
│                                                                     │
│  ③ 运行位置   namespace*  image*  imagePullPolicy  serviceAccount   │
│               callbackBaseUrl*                                      │
│                                                                     │
│  ④ 批次策略   maxTablesPerJob  maxConcurrentJobs  jobTimeout        │
│               catalogToken（可选，留空表示沿用）                     │
│                                                                     │
├─────────────────────────────────────────────────────────────────────┤
│ [停用]  [复用其他 catalog 的连接]        [验证连接]  [保存]          │
└─────────────────────────────────────────────────────────────────────┘
```

### 4.1 字段表

| 字段 | 必填 | 默认 | 校验 | 留空语义 |
| --- | --- | --- | --- | --- |
| `clusterType` | 是 | `SELF_HOSTED` | 枚举 | — |
| `kubeconfig` | 仅 SELF_HOSTED，首次必填 | — | ≤ 64 KiB；解析出至少一个可用 context | 留空 = 不改（已有配置时） |
| `contextName` | 是（SELF_HOSTED） | `current-context` | 必须是刚解析出的可用 context | — |
| `apiServer` | 是（EKS/AKS） | EKS 可从集群名拼 `https://<id>.<region>.eks.amazonaws.com` 但**不自动填** | `https://` 开头，≤ 255 | — |
| `caCertPem` | 否 | 空 = JVM 信任库 | 能被 `CertificateFactory` 解析 | **不传 = 保持原样；传空串 = 清空**（与四个秘密不同，它是非敏感文本，「空」是有意义的状态） |
| `region` | 是（EKS/AKS） | EKS `ap-northeast-1`；AKS `public` | — | — |
| `clusterName` | 是（EKS） | — | 1–100，**大小写敏感** | — |
| `accessKeyId` | 是（EKS） | — | 16–128 | 留空 = 不改 |
| `secretAccessKey` | 首次必填（EKS） | — | 32–256 | 留空 = 不改 |
| `sessionToken` | 否（EKS） | — | — | 临时凭据才需要 |
| `tenantId` / `clientId` / `clientSecret` | 是（AKS） | — | UUID / 非空 | 密钥留空 = 不改 |
| `scope` | 否（AKS） | AKS audience | 以 `/.default` 结尾 | 空 = 默认 |
| `namespace` | 是 | `default` | RFC 1123 label | — |
| `image` | 是 | 空 | 含 `:` 或 `@`（拒 `latest` 之外也给警告） | — |
| `imagePullPolicy` | 是 | `IfNotPresent` | 三选一 | — |
| `imagePullSecrets` | 否 | 空 | 逗号分隔，RFC 1123 名字，≤ 8 个 | 空 = 用命名空间的默认拉取凭据 |
| `serviceAccount` | 否 | 空 = 命名空间默认 | RFC 1123 | — |
| `callbackBaseUrl` | 是 | 空 | `http(s)://`，不以 `/` 结尾 | — |
| `catalogToken` | 否 | 空 | — | 留空 = 不改 |
| `maxTablesPerJob` | 是 | 20 | 1–500 | — |
| `maxConcurrentJobs` | 是 | 2 | 1–50 | — |
| `jobTimeout` | 是 | 2h | 5m–24h | — |

`image` 为什么必填而不是有部署级默认值：集群可以多个，镜像只可能有一个「对的那个」；给个默认值
只会让「填错集群的镜像」更难发现（表现是 `ErrImagePull`，看起来像网络问题）。

### 4.2 `callbackBaseUrl` 是这一屏最容易填错的一项

它要满足的是**反向可达**：集群里的 Pod 得能访问它。所以同一个服务端在不同集群里这一项不同：

| 任务跑在哪 | `callbackBaseUrl` 应该是 |
| --- | --- |
| 服务端所在集群 | `http://paimon-rest.paimon.svc.cluster.local:8080`（Service 名，**不是** pod 名——driver 可能起在别的节点） |
| 另一个集群（同 VPC / 对等连接） | 那个集群能解析到的地址，通常是内网 LB |
| 另一个云 / 公网 | 对外域名 + 网关；此时 `catalogToken` 必须有值 |

表单里给一句「填 Pod 里的 `curl` 能通的地址」，并在探活第 5 步明确说清它只验了一个方向（§5）。

### 4.3 复制配置：`[复用其他 catalog 的连接]`

下拉列出「已配置执行集群的 catalog」，选中后**服务端复制**（`POST .../copy-from`），不是客户端把
另一份凭据读出来再写回去——客户端手里不该出现任何密文对应的明文。复制范围写死：

- 复制：`clusterType` 与整段认证、`apiServer`、`caCertPem`、`namespace`、`image`、`imagePullPolicy`、
  `serviceAccount`、`catalogToken`
- 不复制：`callbackBaseUrl`（同集群时通常一样，但它是**方向性**的，抄错最难查）、批次策略、`contextName`
    之外的探活结果

复制完状态是**待验证**，不是「正常」——复制来的是别人的连接事实，不是你这份配置的验证结论。

### 4.4 停用而不是删除

`enabled=false` 的语义是「这个 catalog 不跑优化」，配置留着。它和「删除」的区别在恢复成本：
停用后重新启用只要一次点击，删除后要把凭据全填一遍。列表列上两者都显示为灰，但文案不同
（`已停用` / `未配置`）。

---

## 5. 探活：五问，两档

`POST .../probe` 返回一组步骤，每一步是 `PASS / WARN / FAIL / SKIP` + 一句结论 + 一句处置。
（效果图画面 5）

| # | 步骤 | 做什么 | 关键结论 |
| --- | --- | --- | --- |
| 1 | `credential` | kubeconfig 解析成功 / EKS 真调一次 `sts:GetCallerIdentity` / AKS 真取一次 AAD 令牌 | EKS 顺带显示 ARN；AKS 显示租户与到期时间；失败时区分「密钥错」与「出不了网」 |
| 2 | `identity` | `GET /version` + `SelfSubjectReview`（1.28+，回显用户名/组；老集群回退到 `/version`） | 把 `401` / `403` / `x509` / `unknown host` 分开说 |
| 3 | `rbac` | 三个 `SelfSubjectAccessReview`：`create jobs`、`delete jobs`、`get pods` + `get pods/log`（排障要看日志） | 逐项 allowed/denied，denied 时给出该绑的 Role 名 |
| 4 | `admission` | **dry-run** 建一个 Job（`dryRun=All`，`fieldValidation=Strict`）+ 查 `serviceAccount` 是否存在 | 准入层能拦的都在这一步暴露：配额、PodSecurity、SA 不存在、镜像策略 |
| 5 | `callback` | 服务端 HTTP GET `callbackBaseUrl` 的健康端点（5s 超时） | **只能说「服务端能到这个地址」**，反向要另外验（见下） |

### 5.1 为什么第 5 步必须自己声明局限

回调是 **Pod → 服务端**，探活是 **服务端 → 地址**。两个方向可以完全相反：服务端在集群外能访问
公网域名，而集群里的 Pod 出不去；或者 Service 名在服务端所在命名空间解析不了。所以这一步的文案
写「服务端可达」，并把真正的验证命令给出来：

```bash
kubectl -n paimon-opt run cb-probe --rm -it --restart=Never \
  --image=curlimages/curl -- curl -sS -m 5 -o /dev/null -w '%{http_code}\n' \
  https://lakehouse.internal:8443/api/management/v1/ping
```

不能自动做这一步：替用户在目标集群里起一个 Pod 意味着我们要有 `create pods` 权限，而优化任务本身
只需要 `create/delete jobs`——为了一个探活去扩大权限，是把最小权限原则反过来用。

### 5.2 轻探活与全探活

| | 触发 | 做什么 | 代价 |
| --- | --- | --- | --- |
| 轻 | 调度扫描时，`lastProbeAt` 超过 `probe-interval`（默认 30 分钟） | 第 1–3 步 | 每个 catalog 每半小时 3–4 个请求 |
| 全 | 用户点「验证连接」/ 保存时 | 1–5 步（含 dry-run 建 Job） | 会在 apiserver 的审计日志里留下一次 dry-run 创建 |

分开的理由：dry-run 虽然不落库，但它逐条走准入链路，在大集群上是**可见的动作**；每半小时来一次
会让审计日志变噪音。而「令牌还有效吗、RBAC 还在吗」这两件事恰恰是轻探活能覆盖的。

---

## 6. 状态机与列表列

```
        保存                     探活全绿
  ──────────────► 待验证 ──────────────────► 正常 ◄────┐
       (无配置)      │                        │        │ 轻探活失败
                    │ 探活失败                │ 配置改动 │
                    ▼                        ▼        │
                   异常 ◄────────────────────┘     异常 ─┘
                    │
                停用/删除
```

| 状态 | 判定 | 列表 chip | 调度影响 |
| --- | --- | --- | --- |
| `NOT_CONFIGURED` | 无记录 | 灰 `未配置` | **不建批次**，游标不推进 |
| `DISABLED` | `enabled=false` | 灰 `已停用` | 同上 |
| `UNVERIFIED` | 有记录，`configHash != probeConfigHash`（从没探过，或探过之后改了配置） | 黄 `待验证` | 照常调度（旧配置可能仍然可用），但列表里可见 |
| `HEALTHY` | 最近一次探活 `PASS` | 绿 `正常` | 照常 |
| `FAILING` | 最近一次探活 `FAIL` | 红 `异常` + 短因（`凭据失效` / `权限不足` / `TLS` / `网络`） | **暂停本 catalog 的派发**，游标不推进；PENDING 批次不判失败 |

`configHash` 是「参与连接与运行的全部字段」的 SHA-256（不含批次策略与探活时间戳）。它让
「改完没验证」这件事在列表上是可见的——没有它，改错配置和没改配置长得一模一样。

**不做过期降级**：`HEALTHY` 不会因为「三小时没探活」变成灰。理由是轻探活每 30 分钟一次，
超过这个间隔说明调度本身停了——那是一个更该报警的问题，不该让每一行的颜色都去表达它。
只在列上给「最近验证 · 26 分钟前」的文字。

---

## 7. 列表页的改动（画面 1）

### 7.1 加一列，加一格按钮

| 名称 | 表格式 | 类型 | **执行集群（新）** | 存储类型 | 仓库位置 | 更新时间 | 操作 |
| --- | --- | --- | --- | --- | --- | --- | --- |

列宽 168，两行：

```
[正常]                          ← chip，四种颜色
EKS · paimon-opt                ← clusterType 简称 · namespace
```

- **一行两个信息**，而不是拆成两列（类型 / 状态）：它们回答同一个问题「这个 catalog 的优化跑在
  哪、现在能不能跑」，拆开之后 320px 的宽度换来的是两张都对不上的列。
- chip 的 tooltip 放详细信息：`apiServer`、最近验证时间、失败原因原文。
- 列头提供一个筛选（`全部 / 已配置 / 有问题 / 未配置`），与现有「格式筛选」同一形状。

操作列加一格图标按钮 `执行集群`（`Connection` 图标），放在「编辑」之后、「浏览库与对象」之前：
配置是一件「改这个 catalog 的运行方式」的事，与编辑相邻。四格按钮的宽度从 200 增加到 232。

### 7.2 没配的 catalog 不该只有一个灰 chip

未配置 = 优化功能对它是关的。所以：

- chip 之外，副行写 `优化不会运行`（不是空着）——灰色小字最容易被读成「正在加载」；
- 行内的「执行集群」按钮在这个状态下用 `type="primary"` 的描边样式（不是灰色次要按钮），
  它此刻是**待办事项**，不是可选动作。

### 7.3 表格列数已经不少了

当前 8 列（含操作）。加完是 9 列，`仓库位置` 已经 `min-width: 230`。在 1280 宽的窗口下会横向
滚动，这是可接受的（列都可压），但**不要再加第二列**（比如「最近探活」）——那一条放进 chip 的
tooltip。

---

## 8. API 契约

挂在 catalog 下，不引入新资源路径段（这也呼应「不加一级菜单」）：

| 方法 | 路径 | 语义 |
| --- | --- | --- |
| `GET` | `/api/management/v1/catalogs/{catalog}/execution-cluster` | 读配置（密文只回 `has*`），404 = 未配置 |
| `PUT` | `/api/management/v1/catalogs/{catalog}/execution-cluster` | 整份替换（含 `enabled`），带 `entityVersion` 乐观锁 |
| `DELETE` | `/api/management/v1/catalogs/{catalog}/execution-cluster` | 删除（要 `confirm-destructive`，与既有约定一致） |
| `POST` | `/api/management/v1/catalogs/{catalog}/execution-cluster/kubeconfig/parse` | 解析 kubeconfig，**不落库**，供表单即时反馈 |
| `POST` | `/api/management/v1/catalogs/{catalog}/execution-cluster/probe` | 五问探活；`?full=false` 只做 1–3 |
| `POST` | `/api/management/v1/catalogs/{catalog}/execution-cluster/copy-from` | 从另一个 catalog 复制连接与认证 |

### 8.1 列表摘要不额外发请求

`GET /catalogs` 的每行直接带一个摘要对象（一次 left join，不是 N+1）：

```json
{
  "name": "paimon",
  "type": "INTERNAL",
  "catalogFormat": "PAIMON",
  "executionCluster": {
    "configured": true,
    "enabled": true,
    "status": "FAILING",
    "statusReason": "CREDENTIAL_INVALID",
    "clusterType": "EKS",
    "namespace": "paimon-opt",
    "apiServer": "https://ABC.gr7.ap-northeast-1.eks.amazonaws.com",
    "lastProbeAt": 1761462600000,
    "lastProbeMessage": "sts:GetCallerIdentity 返回 InvalidClientTokenId"
  }
}
```

`status` 由服务端算（§6 的判定），前端不重算。理由是 `configHash != probeConfigHash` 这种判据
一旦在两边各写一份，迟早会漂——而漂的表现是「列表说正常，点进去说待验证」。

### 8.2 错误码

| 场景 | 码 | message 要点 |
| --- | --- | --- |
| 未配置就提交优化 | 400 | `catalog paimon 未配置执行集群，优化任务不会提交；请先在 catalog 列表点「执行集群」` |
| kubeconfig 解析失败 | 400 | 指出 YAML 行号与缺的字段（`clusters[0].cluster`） |
| 选了不支持认证的 context | 400 | `context eks-prod 的认证方式是 exec:aws，服务端不能执行本地命令；EKS 请改用令牌方式` |
| EKS 集群名写错 | 探活 FAIL（不是 400） | 401 会被翻译成「集群名可能不匹配（大小写敏感）」 |
| AKS audience 错 | 探活 FAIL | `Invalid audience` → 指出 scope 应该是 AKS 的 app id |
| 乐观锁冲突 | 409 | 带当前 `entityVersion`，要求刷新 |

---

## 9. 数据模型

新表 1 张（`docs/data-model.md` 当时写「23 张表」——那口径漏了行过滤/列脱敏 2 张；落地后实际是 **26 张**）：

```sql
CREATE TABLE lakehouse_catalog_execution_cluster (
  id                  BIGINT       NOT NULL AUTO_INCREMENT,
  catalog_id          BIGINT       NOT NULL,
  enabled             TINYINT(1)   NOT NULL DEFAULT 1,
  cluster_type        VARCHAR(16)  NOT NULL,          -- SELF_HOSTED / EKS / AKS
  context_name        VARCHAR(128) DEFAULT NULL,      -- 仅 SELF_HOSTED
  api_server          VARCHAR(255) NOT NULL,
  ca_cert_pem         TEXT         DEFAULT NULL,
  kubeconfig_sealed   TEXT         DEFAULT NULL,      -- CredentialCipher
  auth_config_sealed  TEXT         DEFAULT NULL,      -- EKS / AKS 的 JSON，CredentialCipher
  namespace           VARCHAR(63)  NOT NULL,
  image               VARCHAR(255) NOT NULL,
  image_pull_policy   VARCHAR(16)  NOT NULL DEFAULT 'IfNotPresent',
  image_pull_secrets  VARCHAR(255) DEFAULT NULL,      -- 逗号分隔的 Secret 名
  service_account     VARCHAR(63)  DEFAULT NULL,
  callback_base_url   VARCHAR(255) NOT NULL,
  catalog_token_sealed TEXT        DEFAULT NULL,
  max_tables_per_job  INT          NOT NULL DEFAULT 20,
  max_concurrent_jobs INT          NOT NULL DEFAULT 2,
  job_timeout_seconds INT          NOT NULL DEFAULT 7200,
  config_hash         CHAR(64)     NOT NULL,
  probe_status        VARCHAR(8)   DEFAULT NULL,      -- PASS / FAIL / NULL
  probe_reason        VARCHAR(32)  DEFAULT NULL,      -- 短因，列表用
  probe_message       VARCHAR(512) DEFAULT NULL,
  probe_config_hash   CHAR(64)     DEFAULT NULL,      -- 探活时的 configHash
  probed_at           DATETIME     DEFAULT NULL,
  entity_version      BIGINT       NOT NULL DEFAULT 0,
  created_at          DATETIME     NOT NULL,
  updated_at          DATETIME     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_exec_catalog (catalog_id),
  CONSTRAINT fk_exec_catalog FOREIGN KEY (catalog_id)
    REFERENCES lakehouse_catalog (id) ON DELETE CASCADE
);
```

- **一行一 catalog**（`uk_exec_catalog`），删 catalog 级联。
- `probe_*` 放同一张表而不是单独一张探活历史表：界面上只用到「最近一次」，留历史是给自己加
  一张只有写入没有读取的表。（真要趋势，那是 metrics 的事，见 `metrics-design.md`。）
- 凭据列全部 `_sealed` 后缀，一眼能看出「这列不是明文」。

实体放在 `lakehouse-rest-core`（被协议面复用）还是 `lakehouse-rest-server`（只有运维面用）？
——**server**，与 `OptimizationJobEntity` 同侧。协议面不需要读它。

---

## 10. 运行时接线

### 10.1 提交器：从「一个 bean」变成「按 catalog 取一个」

现在 `OptimizationJobSubmitter` 是单例 bean，注入 `RestServerProperties`。改成：

```
CatalogExecutionClusterRepository  →  ExecutionClientFactory
                                          ↓ (catalog → KubernetesClient, 带缓存)
                                    Fabric8JobSubmitter.submit(catalogName, request)
```

`OptimizationDispatcher` 的 `submitter` 字段改成按 catalog 取。`LoggingJobSubmitter` /
`KubernetesJobSubmitter` 一并删除——前者在新模型里等价于「没配置」，后者被 fabric8 实现取代。

### 10.2 四种「没有可用的执行集群」

| 情况 | 派发时 | 界面 |
| --- | --- | --- |
| 未配置 / 已停用 | 跳过，批次留在 `PENDING`，**游标不推进** | 列表灰 chip；优化页横幅 |
| 探活 `FAILING` | 跳过（不打 apiserver），`PENDING` 不判失败 | 红 chip；横幅带原因 |
| 客户端构建失败（如凭据解密失败） | 该批次 `FAILED`，`error_message` 写明 | 红 chip |
| 提交时 4xx（RBAC 被收回） | 该批次 `FAILED`，并立刻把探活标成 `FAILING` | 红 chip |

**为什么欠配置时不是「判失败」**：一个 catalog 刚建好、还没配执行集群，是正常的中间状态；
把它判失败会在优化历史上写一堆红色批次，而那些批次从未被提交过——「失败」这个词在这里
会误导人去找根本不存在的执行侧日志。

### 10.3 不补跑、不推进游标

沿用现有语义：跳过不等于失败，`next_run_at` 不动，修好后从原位置继续。这条必须在界面上说清楚，
否则用户会以为「配置期间错过的调度会补」。（效果图画面 6 的横幅里就有这句。）

### 10.4 策略保存时 fail-closed

给一个没有执行集群的 catalog 保存优化策略 → 400（§8.2 第一条）。理由：允许保存等于允许
「配了但永远不会跑」，而这类静默失效最难发现。

例外：`enabled=false`（停用）时允许保存——那是刻意的关闭，不是遗漏。

---

## 11. fabric8 落地要点

### 11.1 依赖与版本

```xml
<dependency>
  <groupId>io.fabric8</groupId>
  <artifactId>kubernetes-client</artifactId>
  <version>${fabric8.version}</version>
</dependency>
```

- 本仓父 POM 是 **Spring Boot 4.1.1 + Java 17**。fabric8 内部用 Jackson 2（`com.fasterxml`），
  而 Boot 4 的 MVC 侧已经走 Jackson 3（`tools.jackson`）——两套并存，互不干涉，但镜像里会多一份
  JSON 栈（约 3 MB）。这是引入 fabric8 的真实代价，写在这里以免被当成「零成本」。
- **锁一个具体 minor，不要用区间**：fabric8 的 `Version` / `Serialization` 在小版本间有过行为
  变化，而本工程的 enforcer 会拦依赖收敛问题，升级请当成一次独立改动。
- `kubernetes-client` 默认带 `kubernetes-httpclient-okhttp`；如果与服务端已有的 HTTP 客户端
  出现传递冲突，可以显式排除并只留 `kubernetes-httpclient-jdk`。

### 11.2 绝不允许「配置不全就回落到默认」

fabric8 的老习惯是 `Config.autoConfigure()`：配置缺了就去找 `~/.kube/config`、再不行去集群内
service account。多集群场景下这是**最危险的行为**——一个填错 `apiServer` 的 catalog 会静默地
把 Job 提交到服务端自己所在的集群，而界面上一切正常。

因此只走显式路径：

```java
// SELF_HOSTED
Config config = Config.fromKubeconfig(kubeconfigText);   // 完整 config，不 autoConfigure
Config resolved = new ConfigBuilder(config)
        .withNamespace(namespace)
        .withRequestTimeout(Duration.ofSeconds(10))
        .build();
```

`Config.fromKubeconfig` 拿到的 context 由 `contextName` 决定（`Config.fromKubeconfig(context, text, null)`）。

### 11.3 客户端要缓存，而且必须关

`KubernetesClient` 是重对象：内置 OkHttp 的 dispatcher 与连接池、Watch 的线程池。每次提交新建
一个，几百个批次之后线程数与 fd 都会涨上去，而现象是「跑一夜之后服务端变慢」。

```
缓存 key   = catalogId
缓存 value = (client, configHash)
失效       = configHash 变（改了配置）/ 停用 / 删除 / 空闲 10 分钟
上限       = 32（超过按 LRU 关掉最旧的）
淘汰动作   = client.close()   // 不关就漏线程，这条必须有测试守
```

配置改动走 `PUT` 时就地 `invalidate`，不要等下一次提交时才发现 hash 变了——那样「改完配置立刻点
验证连接」会用旧 client，看起来像「改了没生效」。

### 11.4 两个 `OAuthTokenProvider`

参考实现在 `bee/core/kubernetes` 下，移植时改三处：

| 参考实现 | 本仓怎么改 | 为什么 |
| --- | --- | --- |
| `AwsEksOAuthTokenProvider(region, ak, sk, clusterName)` | 增加 `sessionToken` 支持（`AwsSessionCredentials`） | STS 临时凭据是推荐形态，没有 sessionToken 就只能用长期密钥 |
| `AzureAksOAuthTokenProvider(..., subscriptionId, ...)` | **删掉 `subscriptionId`**；`scope` 可配，默认 AKS audience | §2.3：它不参与取令牌；而管理面 scope 会得到 `Invalid audience` |
| 两个类都在 catch 里 `throw new RuntimeException(e.getMessage(), e)` | 抛带**结论**的异常：`InvalidClientTokenId` → 「密钥无效或已停用」；`audience` → 「scope 不对」 | 这两句话是探活第 1/2 步的产出，必须在 provider 层就能分辨 |

EKS 侧还有一个细节：预签名 URL 的有效期按参考实现写 15 分钟，但**不要缓存**这个串超过 10 分钟
（fabric8 会在每次请求时调 `getToken()`，天然不缓存，前提是我们别自己加一层）。

### 11.5 提交与状态

```java
Job job = new JobBuilder()
        .withNewMetadata().withName("paimon-optimize-" + jobId)
            .withNamespace(namespace)
            .addToLabels("app", "paimon-optimizer")
            .addToLabels("paimon-rest-job", jobId).endMetadata()
        .withNewSpec()
            .withBackoffLimit(0)                       // 重试是批次级语义，k8s 不该自己重试
            .withTtlSecondsAfterFinished(3600)
            .withNewTemplate().withNewSpec()
                .withRestartPolicy("Never")
                .withImagePullSecrets(pullSecretRefs)       // 私有仓库才需要；漏了的表现是 ErrImagePull
                .addNewContainer().withName("optimizer").withImage(image)
                    .withImagePullPolicy(imagePullPolicy)
                    .withArgs(args...).endContainer()
                .endSpec().endTemplate()
            .endSpec()
        .build();
client.batch().v1().job().inNamespace(namespace).resource(job).create();
```

状态读法沿用现有语义（`status.succeeded` / `status.failed` 两个计数器）——它比逐条读 conditions 稳。

### 11.6 超时是这一节最要紧的一行

调度循环是单线程的（`docs/table-optimization.md` §6）。fabric8 的调用是同步阻塞的，一个卡住的
集群（防火墙丢包、apiserver 过载）会把**所有** catalog 的分派都拖住。所以：

- `withRequestTimeout(10s)` + `withConnectionTimeout(10s)`；
- 派发侧对每个 catalog 单独 try/catch，异常只影响它自己；
- 探活与提交不共用超时（探活 dry-run 可以给 30s）。

---

## 12. 安全

### 12.1 需要的最小权限

**k8s 侧**（kubeconfig / EKS / AKS 都一样，只在这几个 namespace 上）：

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata: { name: paimon-optimizer-submit, namespace: paimon-opt }
rules:
  - apiGroups: ["batch"]
    resources: ["jobs"]
    verbs: ["create", "get", "list", "delete"]
  - apiGroups: [""]
    resources: ["pods", "pods/log"]        # 排障要读日志
    verbs: ["get", "list"]
```

不给 `secrets`、不给 `pods/exec`、不给 `deletecollection`。

**AWS 侧**（EKS 令牌方式只需要 STS + 描述集群）：

```json
{
  "Version": "2012-10-17",
  "Statement": [
    { "Effect": "Allow", "Action": "sts:GetCallerIdentity", "Resource": "*" },
    { "Effect": "Allow", "Action": "eks:DescribeCluster", "Resource": "arn:aws:eks:ap-northeast-1:123456789012:cluster/prod" }
  ]
}
```

EKS 的 apiserver 只认「这个 IAM 身份是谁」，真正的权限由上面那份 Role 与
`aws-auth` / EKS Access Entry 的映射决定——**IAM 策略给到 `AdministratorAccess` 不会有任何帮助，
只会扩大泄漏面**，这句要写进抽屉里的说明。

### 12.2 长期 AK/SK 是这份设计的弱点，要直说

kubeconfig 与 AKS 的 client secret 都是「服务端持有的一份长期凭据」，EKS 的 AK/SK 也一样。
它不可避免（服务端要能自主提交，不能等人来登录），能做的只有缩小后果：

| 措施 | 状态 |
| --- | --- |
| 专用 IAM 用户 / 专用 AAD 应用，只授上面那两条 | 设计里给出模板，部署时照抄 |
| 每个环境一把钥匙，不共用 | 逐 catalog 配置天然满足 |
| 轮转周期写进运维手册（90 天），轮转时先 `PUT` 新密钥再观察轻探活 | 待补（§13） |
| 未来接 assume-role / Workload Identity / IRSA | 不做：那要求「服务端在目标云里有身份」，与「一份配置连多个云」冲突，属于另一个方案 |
| 落库加密 + 读回掩码 + 审计 | 本稿覆盖 |

另外：`catalogToken` 会作为**命令行参数**写进 Pod spec（现在的实现就是这样，`--token`）。同一命名空间
里能读 pod 定义的人就能读到它——这个暴露面在当前实现里已被接受（作用域是「一个批次的结果上报」），
本稿不改，但在界面上把它标成「可选，留空则任务无法访问数据面」。

### 12.3 日志与审计

- 任何日志行都不出现 kubeconfig 原文、secret、token；只打 `apiServer` 的 host、namespace、Job 名。
- 配置的增删改发领域事件（`event-framework.md` 已有框架），审计里记「谁在什么时候把 paimon 的执行
  集群从 A 改成 B」——排障时第一个要回答的就是这个。
- 探活结果只记结论与原因码，不记原始响应体（里面可能有集群版本、账号 id 之类）。

---

## 13. 分期与未决

| 期 | 内容 | 说明 | 状态 |
| --- | --- | --- | --- |
| M1 | 服务端：表 + 实体 + 5 个端点 + `CredentialCipher` 接入 + 列表摘要 | 不含真实提交，探活第 1 步用假实现也可 | ✅ 实际交付 6 个端点（多一个 `copy-from`） |
| M2 | `ExecutionClientFactory` + fabric8 提交器 + 两个 TokenProvider + 调度接线 | 删 `KubernetesJobSubmitter` / `LoggingJobSubmitter` | ✅ |
| M3 | 前端：列表列 + 抽屉 + 三种类型表单 + 探活结果面板 + 横幅 | 按效果图六画面 | ✅ |
| M4 | 文档回填（`table-optimization.md` §9.2 / `console.md` / `README` / 计数）与 `verify-console` 新守卫 | 见 §14 | ✅（23 组 150 项） |

未决，需要拍板的三件：

1. **`probe-interval` 放哪**：本稿放在代码默认值（30 分钟），也可做成服务级键。它不影响正确性，
   只影响 apiserver 的请求量。
2. **AKS 是否需要 `subscriptionId`**：本稿删了（§2.3）。如果将来要「从订阅里列出集群」这个便利
   功能，那时再加，且它会是可选字段而不是认证字段。
3. **GKE**：等有真实环境再写（§2.4）。

---

## 14. 验收清单

**前端**

- [ ] catalog 列表 9 列，`执行集群` 在两行内表达「状态 + 类型·namespace」，五种状态各有样例
- [ ] 操作列 5 格（编辑 / 执行集群 / 浏览 / 权限 / 删除），宽度 232，1280 宽下不折行
- [ ] 抽屉三种类型切换时，第二段整体替换（EKS 不出现 kubeconfig 输入框）
- [ ] kubeconfig 解析后逐 context 给结论，不可用的行禁用但不隐藏
- [ ] 探活结果五步，第 5 步带「只说了一个方向」的声明与手工验证命令
- [ ] 未配置时行内按钮是主色（待办），不是灰色次要按钮

**服务端**

- [ ] `GET` 不回任何密文；`PUT` 留空不改、`clearX` 才清
- [ ] 乐观锁 409 带当前版本
- [ ] 未配置 / 停用 / 异常三种情况下派发跳过、`PENDING` 不判失败、游标不推进
- [ ] `client.close()` 在淘汰与配置变更时被调用（测试守线程数）
- [ ] `exec:` 的 kubeconfig 被硬拒，错误文案给出替代路径

**文档与机械校验**

- [ ] `application.yml` 的 `optimization` 段整段消失，`verify-console` 加一条「无残留键」守卫
- [ ] `docs/table-optimization.md` §9.2 的提交参数一节改写为「见执行集群」
- [ ] `docs/data-model.md` 23 → **26** 张表（含补齐漏记的 2 张策略表）；`README.md` / `AGENTS.md` 的文档计数 +1
- [ ] `verify-console.py` 新守卫：`/execution-cluster` 端点 ×6、`clusterType` 三值、无 `optimization.namespace`

---

## 15. 落地记录：与原稿不一致的六处

M1–M3 按本稿实现，下列六处**刻意偏离**，原因都写在这里，免得后来者以为是漏做：

1. **端点是 6 个，不是 5 个。** 多出的一个是 `POST .../execution-cluster/copy-from`（§4.3 的
   「复用其他 catalog 的连接」）。没有把它做成 `PUT` 里带 `sourceCatalog` 的开关：`PUT` 是
   「整份替换」，而复制是「从别处取值再替换」，两种语义挤进一个幂等动词后，前端就无法表达
   「我只想复制、别动我已有的批次策略」。做成独立端点后，请求体只有 `sourceCatalog` +
   必填的 `callbackBaseUrl`，一目了然。
2. **`clearCredentials` 被删除，只保留 `clearCatalogToken`。** 原稿 §1.5 设想「四个秘密都配清空开关」，
   落地时发现那是个**永远失败的按钮**：kubeconfig / AK-SK / AKS 密钥是构造 `KubernetesClient`
   的必需材料，服务层在所有类型下都要求它们存在，传 `clearX=true` 只会得到 400。
   「清空必需凭据」的真实语义等于**删掉整份配置**（`DELETE`），所以不提供这个开关。
   只有 `catalogToken`（可选项）保留清空——「有这个令牌」与「没有这个令牌」都是合法状态。
3. **`caCertPem` 的留空语义反过来了。** 原稿照抄执行目标设计里 `caPem` 的「留空 = 保持原样」，
   落地改成 **不传 = 保持原样、传空串 = 清空**。理由是它是**非敏感文本**，而「用 JVM 信任库」
   是一个合法、且用户必须能表达的状态；沿用「留空 = 保持」的话，一旦填过 CA 就再也退不回默认信任库。
   这与第 2 条并不矛盾：**敏感字段没有「空」状态，非敏感字段的空是有意义的**。
4. **catalog 列表操作列宽 232 → 240。** 五格按钮（编辑 / 执行集群 / 浏览 / 权限 / 删除）在 1280 宽下
   232 会折行，第 5 格被挤到第二行。
5. **`docs/data-model.md` 是 23 → 26 张表，不是 24。** 本稿按「当时 23 张 + 1」预估，
   实际基线漏记了行过滤 / 列脱敏那 2 张（`lakehouse_data_policy` / `lakehouse_column_mask`），
   落地时一并补上；`README` / `AGENTS` / `maven-module-design` / `lineage-design` 里的表数、
   唯一约束数与索引数也一起对齐到 26 / 24 / 31。
6. **`verify-console` 落到 23 组 150 项**（原稿只说「加几条守卫」）。第 22 组 19 项里有三条值得留着：
   「前端代码里不出现 `configHash` / `probeConfigHash`」（状态只在服务端判定，前端只翻译）、
   「旧的两种提交器已删除」（防有人把 `KubernetesJobSubmitter` 捡回来）、
   「yml 的 `optimization` 段没有残留键」（上一版的 12 个提交参数不许写回来）。

**测试落点**：`ExecutionClusterApiTests`（11 例，含「凭据只进不出」「留空不改且密文不变」
「409 版本冲突」「kubeconfig 解析不落库」「`exec:` 硬拒」）与 `KubeconfigParserTests`（12 例，纯单元）；
`OptimizationApiTests` 9 例改为按 catalog 配一份可派发的执行集群后再跑（`probeConfigHash = null`
即「待验证」，可直接派发）。全量 496 例中 4 例既有红与本改动无关
（`CatalogEndpointAuthorizationTests` / `PermissionManagementTests` 缺
`/v1/{prefix}/permissions/scopes` 的授权规则；`DataPolicyDisabledTests` / `LineageDisabledTests`
的前提与 `application.yml` 实为 `true` 相反）。
