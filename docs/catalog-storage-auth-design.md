# Catalog 存储认证设计（v2）：catalog 自己也是凭据消费者

> **状态：设计稿，未实现。** 配套原型 [`catalog-storage-auth-mockup.html`](catalog-storage-auth-mockup.html)。
>
> **v2 修订（2026-10-30）。** v1 把存储认证当成「给引擎的凭据」，全篇只回答「引擎拿到什么」。
> 这个前提漏了一半：**catalog 的数据预览与文件浏览跑在服务端进程里，用的是服务端自己的身份**。
> 于是出现一种建好就坏的配置——控制台预览 500、而引擎读表正常（或反过来）。
> v2 按「四个消费者」重做；v1 里仍然成立的部分（三种不可用的三套视觉、前端不许算档位、
> `sessionToken` 缺失）保留并标为「沿用 v1」。

---

## 0. 一句话结论

**一份 catalog 存储配置要同时喂饱四个消费者：服务端读、服务端写、引擎读、引擎写。
今天的能力分布是反的——最安全的方式（服务端代取短期票）让引擎写不了，
最不安全的方式（长期 AK/SK）四个格子全通。所以表单不能先问「用哪种认证」，
必须先问「这个 catalog 要不要给引擎写」。**

---

## 1. 事实：catalog 自己的六个功能都要碰物理文件

### 1.1 功能 → 端点 → 取票入口

| 功能 | 端点 / 触发点 | 动作 | 取票入口（代码） | 缓存 |
| --- | --- | --- | --- | --- |
| 数据预览 | `POST /api/console/v1/tables/{tableId}/preview`（判 `TABLE:SELECT`） | 读 parquet + 读清单 | Paimon：`PaimonPreviewTableFactory` → `StorageFileIoFactory#optionsFor(catalog, tableId, path)`（**表级**）<br>Iceberg：`IcebergPreviewFileIo` → `StorageFileAccess` → `#catalogOptionsFor`（**catalog 级**） | 不缓存 |
| 分区列举 | `GET /api/console/v1/tables/{tableId}/partitions` | 读清单 | 同上 | 不缓存 |
| 文件浏览 | `GET /api/console/v1/catalogs/{n}/files`、`GET .../tables/{id}/files` | `listStatus` | `CatalogFileService#open` → `#catalogOptionsFor(catalog, false)` | 缓存短期票 |
| 文件下载 | `GET .../files/download` | 读流 | 同上 | 缓存短期票 |
| 元数据物化 | 建表 / 改表时写 `schema-<n>` | **写** | `TableMetadataService` → `#optionsFor(..., allowWrite=true)` | 不缓存 |
| 删表目录 / 存储优化 | 管理 API、optimizer | **写** | `StorageFileAccess#writableFileIO` → `#catalogOptionsFor(catalog, true)` | 缓存短期票 |

### 1.2 三件由这张表直接推出的事实

1. **服务端既读又写。** `allowWrite` 不是可选优化——只读票写元数据的失败点是 `PutObject` 403，
   出现在「建完表引擎读不到 schema」这种离凭据很远的现象里。
2. **两条取票路径的作用域不同。** catalog 级按 `allowedLocations` 签，表级按表路径签。
   混用的症状是「读单个文件正常、列目录 403」，而日志里一切正常
   （见 `CredentialContext` 的类注释）。
3. **预览这条路径不缓存。** `optionsFor` 直接调 `credentialManager().vend(...)`，不经过
   `StorageCredentialCache`；每翻一页预览就签一张票。STS 配额要按「预览请求数」估，
   不是按「表数」估——这条在 §10 的容量算式里会再出现一次。

> 顺带记一个不一致（G5）：浏览走 `catalogOptionsFor`（有缓存），预览走 `optionsFor`（无缓存）。
> 两条都是服务端自己的调用，缓存策略却没有共同口径。不是 bug，但会让「为什么预览慢、
> 为什么 STS 调用量比预期高一截」很难回答。

---

## 2. 四个消费者 × 三类秘密

### 2.1 按「秘密放在哪」分类，而不是按云分类

| 类 | 含义 | 有哪些方式 |
| --- | --- | --- |
| **A 共享长期密钥** | catalog 上存一对 AK/SK，服务端与引擎**都直接持有** | S3 / OBS / OSS / COS 的静态密钥 |
| **B 服务端代取短期票** | catalog 上存的是「换取短期凭据的身份」：角色 + 签名身份、服务主体、服务账号 | S3 `roleArn`、OSS `roleArn`、Azure 服务主体、GCS 服务账号 JSON |
| **C 不存秘密** | catalog 上一个密钥都不存，两边各自用自己的环境身份 | `stsUnavailable=true`、什么都不填、Azure 托管标识、GCS ADC |

这三类的差别不是「安全等级」，而是**谁在什么时候持有密钥**。A 类没有换取动作，
所以它不依赖任何部署开关；B 类的每一步都要服务端在线；C 类把责任推给了部署方。

### 2.2 能力矩阵（本文最重要的一张表）

✓ = 今天可用；△ = 依赖部署或环境；✗ = 今天不可用（代码层面）。

| 认证方式 | 服务端读<br>（预览 / 浏览 / 下载） | 服务端写<br>（元数据 / 删目录） | 引擎读<br>（Spark / Flink） | 引擎写 | 前置条件 |
| --- | --- | --- | --- | --- | --- |
| **S3** 静态 AK/SK | ✓ 同一对钥匙 | ✓ | ✓ 拿到**永久**密钥 | ✓ | 无 |
| **S3** IAM 角色（+签名身份） | ✓ | ✓ | ✓ 短期只读票 | **✗** | 启用 `s3-token`；角色信任策略放行 |
| **S3** 临时凭证（Session Token） | — | — | — | — | **未实现**（`AwsStorageConfigInfo` 无此字段） |
| **S3** 环境身份（`stsUnavailable` / 留空） | △ 看服务端工作负载身份 | △ | △ 看引擎侧 IRSA | △ | 无 |
| **OSS** RAM 角色 | ✓ | ✓ | ✓ 短期票 | **✗** | 启用 `oss-sts` |
| **OSS** 静态 AccessKey | ✓ | ✓ | ✓ | ✓ | 无 |
| **OBS** 永久 AK/SK | ✓ | ✓ | ✓ | ✓ | 无（委托签发 P1-3 未做） |
| **COS** 永久密钥 | ✓ | ✓ | ✓ | ✓ | 无（COSN 无令牌位，永远没有临时档） |
| **Azure** 服务主体 | ✓ | ✓ | ✓ SAS | **✗** | 启用 `azure-sas` |
| **Azure** 托管标识 | — | — | — | — | 未接入 |
| **GCS** 服务账号 JSON | ✓ | ✓ | ✓ downscoped token | **✗** | 启用 `gcs-downscope` |
| **GCS** ADC（应用默认凭据） | △ | △ | △ | △ | 未接入 |
| **FILE** 本地文件系统 | ✓ | ✓ | ✓ | ✓ | 无（没有凭据概念） |

### 2.3 两个反直觉结论

**① 「引擎写 ✗」不是设计偏好，是当前代码的行为。**
`StorageCredentialManager#vend(storage, tableId, tablePath)` 三参版本默认
`allowWrite=false`，类注释写得很直白：「引擎侧的读写路径在协议上分不开
（`loadTable` 之后客户端既读也写），那里仍然一律按只读签——先收紧」。
三个引擎面（Paimon `/token`、Iceberg `/credentials`、Lance `storage_options`）
全部走这个三参重载。于是：

> **选中 B 类（签发）的 catalog，Spark / Flink 能读、不能写。**
> Paimon 的 `RESTTokenFileIO` 把票合并进 options 后重建 FileIO，写入照样用这张票，
> 于是第一次 `PutObject` 就是 403。

**② 于是 A 类在「要给引擎写」的场景里是唯一可用答案。**
矩阵里 A 类四个格子全通，B 类差一格，C 类四格全不确定。表单如果按「安全性」排序，
会在用户最需要写的那个场景里推荐一个写不了的方式——这是 v1 交互里最危险的一处
（v1 把「IAM 角色」标了 `推荐`）。

### 2.4 「服务端可读」是硬约束

用户的原话是「必须要有认证」。把它落成可执行的判据：

> **保存一个对象存储 catalog 时，必须存在至少一条「服务端能读到 `allowedLocations`」的路径。**

B 类不满足这条时**不是「降级」，是建完就坏**：`DefaultStorageCredentialManager#vendOther`
对未启用 provider 的 B 类会走到 `forwardOrUnavailable`，Azure / GCS 直接回
「只发定位元数据」，S3 回 `SOURCE_ENVIRONMENT`——服务端 FileIO 拿到一份没有密钥的 options，
预览与文件浏览当场 500。这跟「引擎读不了」是两件事：前者是控制台坏了，后者是作业坏了。

---

## 3. 交互重排：先问用途，再问方式

### 3.1 五段链

```
存储类型 ──▶ 用途（只读 / 可写） ──▶ 认证方式 ──▶ 字段组 ──▶ 两侧结果 + 保存前自检
   │              │                    │                        │
   │              │                    └─ 被用途过滤，冲突的打红标  └─ 服务端读 ✓/✗、引擎读 ✓/✗、引擎写 ✓/✗
   │              └─ 新增：catalog 要不要支持「引擎写入」
   └─ 决定可选方式集合（沿用 v1 的 R1）
```

与 v1 的四段链相比只多了一环，但这一环改变了整个页面的顺序感：
用户先回答「我要拿它干什么」，再回答「用什么身份」。

### 3.2 用途开关（新）

两个选项，写进 catalog 属性（提案 `catalog.access-mode`，服务端可推导出默认值）：

| 选项 | 含义 | 对方式列表的影响 |
| --- | --- | --- |
| **只读**（默认） | 引擎只读这个 catalog；写入由别的 catalog 或 ETL 负责 | A / B / C 全可选 |
| **可写** | Spark / Flink 要往这个 catalog 写 | B 类卡片打「引擎写不可用」红标，并在选中时给阻断提示 |

为什么默认只读：今天 B 类本来就只发读票，把默认设成「只读」是把现状说出来，
不会让存量 catalog 突然显示警告。

### 3.3 方式卡片要带「两侧结果」，不能只写「用这把身份做什么」

v1 的卡片只写了一句纯描述（「服务端用这对钥匙代表你」）。v2 每张卡片多一行：

```
┌─────────────────────────────────────────────┐
│ ○ IAM 角色（服务端代取）          短 期  · 推荐 │
│   catalog 存一个 roleArn，服务端每次读表现签一张 |
│   只覆盖这张表的票。                            │
│   服务端预览 ✓   引擎读 ✓   引擎写 ✗            │   ← 新增这一行
└─────────────────────────────────────────────┘
```

这一行必须由服务端算（§6），前端只渲染。写法上它是三个短标签，不是句子——
目的是让人在**选之前**就看见「这一选会让引擎写不了」，而不是保存后读文档才知道。

### 3.4 四种「不可用」，四种视觉（v1 三种 + 新增一种）

| 种类 | 视觉 | 什么时候出现 | 用户能做什么 |
| --- | --- | --- | --- |
| 本部署没开 | 灰底 + 虚线 + 「本部署未启用」 | 选了 B 类但 `credential.providers` 里没有对应项 | 可点，点了给出路（找运维开 / 改选 A 或 C） |
| 本工程没做 | 虚线 + 「暂未接入」+ 不可选 | S3 的临时凭证、OBS 的委托 | 只能看说明 |
| 这类存储永远没有 | **不显示** | Azure 账户密钥、COS 临时密钥 | —— |
| **引擎写不可用**（新） | 选中态下红色角标 | 用途=可写 且 方式∈B 类 | 改选 A 类，或接受「引擎只读」 |

第四种和前三种的区别：前三种是**能力不存在**，第四种是**能力存在但只覆盖一半**。
把它画成「不可用」会让人以为方式选错了；画成红色角标并写清「读没问题、写会 403」才准确。

---

## 4. 保存前自检（新）

### 4.1 为什么必须有

C 类的可读性无法在表单里推导：它取决于「服务端进程有没有一个被这个桶认可的 IAM
身份」。B 类能不能用取决于部署配置。A 类的钥匙可能填错。
三种情况的共同点是——**只有真的去 HEAD 一次才知道**；而不知道的代价是建完 catalog、
点开预览、看到一句 `failed to list ...: null`。

把「必须要有认证」变成一次 `getFileStatus`，是这句话唯一可执行的形态。

### 4.2 端点契约（提案）

```
POST /api/console/v1/catalog-storage-probe
Authorization: 控制台会话；需 CATALOG:CREATE（新建）或 CATALOG:ALTER（编辑）

{
  "storageConfigInfo": { ... },        // 与保存时同一份草稿，含打码值
  "warehouse": "s3://bucket/paimon",
  "allowedLocations": ["s3://bucket/paimon"]
}

200 OK
{
  "verdict": "OK | DENIED | NOT_FOUND | UNREACHABLE | UNSUPPORTED",
  "serverReadable": true,
  "checks": [
    { "location": "s3://bucket/paimon", "verdict": "OK", "detail": "HEAD 200" }
  ],
  "hint": null
}
```

`verdict` 取最坏的那个；`checks` 逐位置给，因为多位置时常常是「第一个通、第二个不通」。

### 4.3 判定映射

| 云侧现象 | `verdict` | 界面文案 | 常见原因 |
| --- | --- | --- | --- |
| 200 / 403 但对象不存在（HEAD 无权限时 S3 回 404） | 需要区分 | 见下 | |
| 401 / 403 `AccessDenied` | `DENIED` | 这份身份没有权限，或根本没用上这份身份 | 密钥错、角色信任策略没放行、桶策略拒了 |
| 404 `NoSuchBucket` / `NoSuchKey` | `NOT_FOUND` | 桶 / 前缀不存在 | 地址写错、区域不对 |
| 连接超时 / DNS 失败 | `UNREACHABLE` | 服务端连不上这个地址 | endpoint 填成了内网地址、出网没开 |
| 没有对应 scheme 的 FileIO | `UNSUPPORTED` | 本部署没有这个 scheme 的实现 | `lakehouse.rest.file-io.type` 与插件 |
| 没有凭据可发（C 类且服务端无身份） | `DENIED` + `hint` | 服务端没有可用身份 | 提示改用 A / B 类 |

**403 与 404 在 S3 上不可区分**（无 `s3:ListBucket` 时 HEAD 一个不存在的 key 回 403，
有 ListBucket 时回 404）。所以文案不要写「对象不存在」，写「这个位置读不到：
可能是地址不对，也可能是这份身份没有权限」——两个都指向用户下一步要查的东西。

### 4.4 安全边界（这一节比上面的契约重要）

自检端点的本质是：**让服务端用调用方提供的凭据，对调用方提供的地址发一次请求。**
这已经是 SSRF 的形状，必须有硬边界：

1. **`FILE` 类型不探测。** 对 `file:///etc/passwd` 做 HEAD 就是一个「本机文件存在性」
   预言机。本地仓库也不需要认证，没有探测的必要——这一条是硬编码的白名单，不是配置项。
2. **只 `getFileStatus`（HEAD），不 `listStatus`、不读内容。** 列表能力等于把服务端变成
   一个对象存储浏览器，而它对调用方只暴露「有没有」。
3. **只探 `allowedLocations` 前缀内的位置**，请求里另给的路径一律忽略（避免变成任意地址探针）。
4. **限频**：每用户每分钟 N 次（N 建议 6），失败也计数——否则它就是一个免费的凭据试错器。
5. **只回分类，不回原文**：云侧错误消息里常带 endpoint、账号 ID、甚至内网域名，只回上表那五档。
6. **判权**与 `CATALOG:CREATE` / `CATALOG:ALTER` 同档；不要给它单独的权限点，
   否则会出现「能探测但不能建」的空白用户。

### 4.5 自检 ≠ 授权

自检回答的是「服务端能不能打开这个位置」。谁能看哪些行、哪些列要不要脱敏，是
`DataPolicyService` 的事（行过滤 / 列脱敏，见 `docs/data-policy-design.md`）。
两件事都在预览链路上，但判据完全不同：认证失败是 500，授权不足是 403。
设计上不许把两者塞进同一个提示条——「读不到」和「不许看」给用户的下一步动作是相反的。

---

## 5. 引擎侧（Spark / Flink）真正要什么

### 5.1 两层认证，别混为一谈

```
Spark / Flink ──① 到 catalog 服务端──▶ REST Catalog ──② 到对象存储──▶ S3 / OBS / ...
                 bearer / OAuth2                       本文的票（token / credentials）
```

本文只管 ②。① 是 `docs/oauth2-*.md` 那一层，与存储类型无关。
但两层的失败长得很像（都是 403），排障时必须先分清是哪一层——所以控制台的
「引擎片段」里要把两段配置分开列，不要混成一大块。

### 5.2 续期：谁的 TTL 决定成败

| 方式 | 票的寿命 | 谁负责续 | 不续的后果 |
| --- | --- | --- | --- |
| A 静态密钥 | 永久（`expireTimeInMs()==0`） | 不需要 | —— |
| B 签发（S3） | 900–43200 秒（取 `min(TTL, 角色上限)`） | Paimon：`data-token.enabled=true` → `RESTTokenFileIO` 到期前重调 `/token`；Iceberg：`X-Iceberg-Access-Delegation: vended-credentials` + `client.refresh-credentials-endpoint` | 1 小时后读表 403 |
| B 签发（Azure） | SAS 有效期 | 同上（`VendedCredentialsProvider`） | 同上 |
| B 签发（GCS） | access token 1 小时 | 同上 | 同上 |
| C 环境身份 | 引擎自己的凭据链 | 引擎/云 SDK | —— |

**Flink 比 Spark 更需要这条。** 批作业跑完就结束，流作业会跨过任意多个 TTL。
一个 `TTL=1h` + 未开续期的 Flink 流作业，会在第 61 分钟开始报 403，
而报错位置在数据源算子，与凭据隔了十万八千里。

所以「方式」这个选择必须同时产出**客户端配置清单**（复用
`console/src/utils/engineSnippet.js` 那套生成逻辑），不能只产出服务端配置。

### 5.3 引擎写：今天无解，三条出路

| 出路 | 做法 | 代价 |
| --- | --- | --- |
| ① 用 A 类 | 这个 catalog 选静态密钥 | 引擎拿到永久密钥；泄漏窗口不可控 |
| ② 给票加写权限 | 见下方提案 | 要改 `vend` 的调用点与判权 |
| ③ 引擎自带身份（C 类） | 引擎侧配 IRSA / 托管标识 | 需要引擎集群有身份；服务端预览同样依赖部署身份 |

**② 的提案（推荐，但需要产品决策）**：把票的写权限绑定到**调用者对该表的权限**，
而不是一个全局开关：

- `/token`、`loadTable`、Lance `storage_options` 三处都已经知道「是谁在请求」（bearer 身份），
- 判一次 `TABLE:MODIFY`：有则发写票，没有则发读票；
- 于是「能写的人拿到写票、只能读的人拿到读票」，与授权模型天然一致；
- 不需要协议改动（票的内容变了，形状没变）。

风险：`loadTable` 之后客户端可能长期缓存这张票，直到过期；某人被收回写权限后，
手上的写票还能用满一个 TTL。这是「按 token 吊销不存在」的同一个问题（P2-3 已记录），
只能靠缩短 TTL 收窄，不能靠撤销。

### 5.4 按方式的客户端参数（供引擎片段生成）

| 方式 | Spark / Flink 必填 | 备注 |
| --- | --- | --- |
| A 静态 | 服务端下发的键；`data-token.enabled` 可不开 | 永久密钥，不需要刷新 |
| B 签发 | **`data-token.enabled=true`**（Paimon 面）/ `X-Iceberg-Access-Delegation: vended-credentials`（Iceberg 面） | 不开就只是一次性票 |
| C 环境 | 引擎侧的身份配置（IRSA / 托管标识 / ADC），服务端一个键都不发 | 控制台无法校验，只能提示 |

---

## 6. 凭据来源要拆成两个维度（改造 v1 的单一 mode）

### 6.1 现状与问题

`CatalogCredentialSource` 今天只给一个 `Mode`（`STS` / `RELAY` / `NONE` / `INVALID`），
它是**引擎视角**的：`RELAY` 说的是「会把长期密钥发给引擎」。v2 需要两个维度：

| 维度 | 取值 | 回答的问题 |
| --- | --- | --- |
| `serverReadable` | `yes` / `no` / `unknown` | 服务端预览与浏览能不能成 |
| `engineCredential` | `short-lived` / `long-lived` / `locators-only` / `none` | 引擎拿到什么 |

`unknown` 只出现在 C 类：服务端有没有工作负载身份，控制器不知道，只有 probe 能回答。
`yes` 的含义是「按当前配置一定能拿到凭据」，不含「这份凭据一定有权限」——后者只有 probe 知道。

### 6.2 取值表

| 类的具体形态 | `serverReadable` | `engineCredential` |
| --- | --- | --- |
| A 类（有 AK/SK） | `yes` | `long-lived` |
| B 类 + provider 已启用 | `yes` | `short-lived` |
| B 类 + provider **未**启用（S3 有钥匙） | `yes`（钥匙被转发） | `long-lived` |
| B 类 + provider **未**启用（S3 无钥匙 / Azure / GCS） | **`no`** | `locators-only` |
| B 类 + `require-catalog-signer=true` 且无签名身份 | `no`（503） | `none` |
| C 类 | `unknown` | `none` / `locators-only` |
| FILE | `yes` | `none`（自签令牌） |

第三种形态是关键：**「IAM 角色没启用」在 S3 上会因为同时填了钥匙而看起来正常**，
但那时引擎拿到的是永久密钥——界面上必须同时说这两件事，只说「转发」，
用户会以为「没启用签发也没关系」。

### 6.3 前端仍然不许算（沿用 v1）

第 24 组校验（`scripts/verify-console.py`）继续盯着：`credentialSource.js` 与
`credentialPreview.js` 不许出现四个档位字面量、不许读 `roleArn` / `stsUnavailable` /
`providers`。新的辅助模块也一样——它只把服务端返回的两个字段翻译成标签与颜色。

---

## 7. 症状 → 原因对照表（排障用）

| 症状 | 最可能的原因 | 先看哪里 |
| --- | --- | --- |
| 控制台预览 / 浏览 500，引擎读表正常 | 服务端取票档不对：B 类 provider 未启用、或 C 类服务端无身份 | `serverReadable`；日志里 `vended ... source=environment` |
| 引擎读表 403，控制台正常 | 下发的键名不对，或引擎没开续期、票已过期 | `/token` 响应体；引擎片段是否含 `data-token.enabled` |
| 引擎能读、一写就 403 | 这是今天 B 类的**正常行为**（票只读） | §2.3 |
| 预览时好时坏 | STS 缓存窗口 vs 票寿命；或多实例下各实例的票寿命不同 | `StorageCredentialCache` 的 `cacheUntilMillis` |
| 列目录 403，读单个文件正常 | 表级票被拿去做了 catalog 级动作 | 两条取票入口是否被混用 |
| 建表成功但引擎读不到 schema | 服务端写元数据拿了只读票 | `TableMetadataService` 的 `allowWrite=true` |

---

## 8. 与现有实现的差距

| # | 差距 | 影响 | 阻塞谁 |
| --- | --- | --- | --- |
| **G1** | `CatalogCredentialSource` 只有一档 mode（引擎视角） | 界面无法同时说「服务端读不了」与「引擎拿到什么」 | §6 全部 |
| **G2** | 引擎票恒只读（`vend` 三参默认 `false`） | 「可写」用途下，B 类不可用 | §2.3、§5.3 |
| **G3** | `/meta` 不下发 `credential.providers`（沿用 v1 的 B2） | 前端无法预知 B 类可用性 | §3.4 |
| **G4** | 没有 probe 端点 | 「必须有认证」无法在保存前验证 | §4 |
| **G5** | 服务端两条取票路径缓存策略不一致（浏览缓存 / 预览不缓存） | STS 调用量偏高，且难解释 | §1.2 |
| **G6** | `AwsStorageConfigInfo` 缺 `sessionToken`（沿用 v1 的 B1） | 「临时安全凭证」档做不了 | §2.2 第 3 行 |
| **G7** | Azure `skipSasTokenGeneration` / GCS `skipCredentialDownscoping` 未落 DTO | 「不签发」只能靠「不配身份」表达，语义混在一起 | §2.1 的 C 类 |

G1 与 G4 是本轮新增的，也是最要紧的两个：前者决定界面能不能说真话，后者决定
「必须要有认证」这句话有没有落点。

---

## 9. 验收

### 9.1 交互（原型与实现共用）

| # | 断言 |
| --- | --- |
| E1 | 「用途=可写」时，B 类卡片显示「引擎写不可用」，且不出现「推荐」标 |
| E2 | 切换存储类型重建方式集合；切换方式只清凭据字段，`endpoint` / `region` 保留 |
| E3 | 编辑已存配置时按字段反推方式；打码值 `******` 不影响反推结果 |
| E4 | 「本部署未启用」的卡片可点，点击后给出两条出路（找运维 / 改选） |
| E5 | 「本工程没做」的卡片不可选，且写明缺什么 |
| E6 | 「这类存储永远没有」的方式**不出现在 DOM 里** |
| E7 | 保存前自检的四种结果各有独立视觉，失败时给出下一步动作而不是只报错 |
| E8 | 自检按钮在 `FILE` 类型下不可用，并说明「本地仓库不需要认证」 |

### 9.2 脚本（第 24 组扩两条）

| # | 断言 |
| --- | --- |
| E9 | `serverReadable` / `engineCredential` 两个字段名只出现在渲染层，判定层不出现 |
| E10 | 新的 `storageAuth.js` 里不出现 `STS` / `RELAY` / `NONE` / `INVALID` 四个字面量（剥注释后） |

### 9.3 后端

| # | 断言 |
| --- | --- |
| E11 | probe 对 `file://` 一律拒绝（不 HEAD），对 `allowedLocations` 之外的位置不探测 |
| E12 | probe 的响应里不含云侧原始错误消息 |
| E13 | B 类未启用 provider 时，`serverReadable=no`（不是 `unknown`） |

---

## 10. 落地顺序

| 步 | 做什么 | 为什么排这里 |
| --- | --- | --- |
| **S1** | `CatalogCredentialSource` 拆成两个维度 + `/meta` 下发方式可用性 | 纯后端只读计算，改动小，界面立刻能说真话（G1、G3） |
| **S2** | 表单按 §3 重排（用途 → 方式 → 两侧结果） | 依赖 S1 的字段 |
| **S3** | probe 端点 + 保存前自检 | 依赖 S1（要复用同一套判定），独立于 S2 |
| **S4** | 引擎写权限（§5.3 提案 ②） | 要产品决策 + 判权改动，单独一轮 |
| **S5** | 临时凭证字段（`sessionToken` 五处联动） | 只有真实需求出现时才做，见 v1 的 B1 |

**S1 单独就能解决用户的原始问题**：在没有 S1 之前，界面无法回答「这个 catalog
建完之后控制台能不能预览」，只能等它坏。

---

## 11. 不做什么

- **不做「自动挑一种方式」**。默认值只由存储类型 + 用途决定，不从 endpoint 或
  桶名去猜（猜错的代价是「看起来配好了、实际认证失败」）。
- **不把 probe 做成「保存后静默重试」**。自检是用户按下的一次动作，不是后台任务；
  后台重试会把「凭据坏了」变成一条没人看的日志。
- **不在前端算档位**（沿用 v1）。包括「用途 → 推荐方式」这张表：推荐由服务端给。
- **不给 Azure 账户密钥、COS 临时密钥**。前者规格里没有这一项，下发等于交整桶权限；
  后者 COSN 认证包里没有令牌类（G18 实测过 jar）。
- **不做按 catalog 的签发开关**（v1 §10.8 的偏差继续保留）。部署级的
  `credential.providers` + 按 catalog 的方式选择已经够用，多一层配置只会多一处漂移。

---

## 附录：与 v1 的差异一览

| 主题 | v1 | v2 |
| --- | --- | --- |
| 主线 | 类型 → 方式 → 字段 → 来源（引擎视角） | 类型 → **用途** → 方式 → 字段 → **两侧结果 + 自检** |
| 消费者 | 只有引擎 | 服务端读 / 服务端写 / 引擎读 / 引擎写 |
| 来源 | 一个 `Mode` | `serverReadable` + `engineCredential` 两个维度 |
| 推荐标 | 标在「IAM 角色」上 | 「推荐」只在用途=只读时出现；可写用途下 B 类打红 |
| 可读性验证 | 无 | 保存前 HEAD 自检（probe 端点） |
| 引擎侧 | 一段下发键表 | 续期责任表 + 客户端参数表 + 引擎写缺口 |
| 保留 | —— | 三种不可用的视觉、前端不许算档位、`sessionToken` 缺口 |
