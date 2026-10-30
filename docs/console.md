# Web 管理控制台

控制台是 `lakehouse-rest-server` 自带的一个 Vue 单页应用，挂在 `/console/` 下，
覆盖服务端两个 API 面：

| API 面 | 基址 | 控制台覆盖 |
| --- | --- | --- |
| Catalog API | `/v1/{prefix}/**` | database / table / view / function / semantic view / tag / branch / partition / snapshot / 数据令牌与查询授权 |
| Management API | `/api/management/v1/**` | catalog / principal / principal-role；catalog-role 与 grants 既没有页面、也没有 Spark SQL 语句，只剩管理 API 本身（见 [§4](#4-页面清单)） |
| 控制台扩展 | `/api/console/v1/meta` | 只读：导出枚举取值与服务端配置摘要，**非官方规格** |

它是**运维与排查工具**，不是数据写入入口：不提供 `commitTable`（`.../tables/{table}/commit`），
理由见 [§7](#7-端点的取舍为什么没有-committable)。对快照的介入只做「读」与「回滚」两件事。

---

## 1. 快速开始

控制台的构建产物**不入库**（落在 `lakehouse-rest-spring-parent/lakehouse-rest-server/src/main/resources/static/console`，
见 [§8.2](#82-产物不入库但必须被构建出来)），因此**从源码构建 jar 之前要先构建前端**，
需要 Node 20+：

```bash
# 1) 构建控制台产物：装依赖（按需）→ 构建 → 校验一致性
./scripts/build-console.sh

# 2) 打包（这一步本身不需要 npm，但依赖上一步的产物）
./mvnw -o package -DskipTests

# 3) 启动（h2 profile 用于本地试跑）
java -jar lakehouse-rest-spring-parent/lakehouse-rest-server/target/lakehouse-rest-server-1.0.0.jar \
  --spring.profiles.active=h2 --server.port=8080

# 4) 打开
open http://127.0.0.1:8080/console/
```

只改 Java、不动前端时第 1 步可以跳过，产物还在上一轮的位置；但**干净检出必须先跑一次**——
否则打出的 jar 里没有 `/console`，而构建过程不会报任何错（守卫见 §8.2）。
已经有发布好的 jar 或镜像时，直接用即可，不需要 Node。

首次进入控制台**默认要求登录**（`lakehouse.rest.auth.console.required=true`）：
登录页第一个页签是用户名密码，默认账号密码 `admin/admin`，直接用它进去即可。
服务端另外开了 `lakehouse.rest.auth.enabled=true` 时，`/v1/**` 与管理 API 也要求令牌，
此时登录后拿到的那份令牌会自动带上——不需要再去「连接设置」手填。
见 [§6](#6-鉴权登录与连接设置)。

> **注意**：只开门禁（`console.required=true` 而 `auth.enabled=false`）时，
> 这层登录**只挡住界面**，`curl` 数据接口仍然能拿到数据。要保护数据请开 `auth.enabled`。

### 1.1 本地开发

前端要热更新时，用 Vite 的 dev server，它把 `/v1` 与 `/api` 代理到 `127.0.0.1:8080`：

```bash
./mvnw -o -pl :lakehouse-rest-server spring-boot:run -Dspring-boot.run.profiles=h2
cd lakehouse-rest-console && npm run dev        # http://127.0.0.1:5173/console/
```

dev server 的基址也是 `/console/`（与生产一致），因此不需要为开发环境改任何路径。

---

## 2. 目录结构

```
lakehouse-rest-console/
├── index.html                     入口页（含首屏占位样式）
├── vite.config.js                 base=/console/，outDir 指向服务端 resources/static/console
└── src/
    ├── api/
    │   ├── endpoints.js           ★ 全部 81 条端点的 method/path，唯一契约表
    │   ├── client.js              fetch 封装：鉴权、路径拼装、错误归一化
    │   ├── catalog.js             Catalog API 的调用函数
    │   ├── management.js          Management API 的调用函数（含创建请求的包体约定）
    │   ├── console.js             控制台扩展端点（除登录之外的那些：仓库与表目录的文件浏览）
    │   ├── console-auth.js        登录引导、用户名密码换令牌、客户端凭据换令牌（这三条不需要令牌）
    │   └── oidc.js                OIDC 授权码 + PKCE：授权 URL、state、换令牌
    ├── components/                ResourceState（三态）、PropertiesEditor、StorageConfigFields、
    │                              CredentialDialog、FieldLabel（字段备注收进问号图标）、
    │                              TipNote（区块说明收进问号图标）、
    │                              TipAlert（提示性告警的一行化：正文收进标题旁问号）、SectionTitle（表单小节头，
    │                              替代 el-divider 的分组标题）、
    │                              AutoRefreshControl（列表的定时刷新开关 + 节拍）、
    │                              JsonBlock、CopyText、TimeText、PageHeader、PanelCard
    ├── composables/index.js       useResource（竞态丢弃）、useRemotePaging、
    │                              useAutoRefresh（定时刷新：后台暂停、在途不叠加、卸载清定时器）、
    │                              刷新事件、通知与危险确认
    ├── layouts/ConsoleLayout.vue  侧边导航 + catalog 选择器 + 主题切换 + 刷新
    ├── router/index.js            路由表；侧边菜单由它聚合生成；登录守卫
    ├── store/                     auth（登录态）、session（catalog 与 meta）、
    │                              credentials（令牌与基址）、theme
    ├── styles/index.css           设计变量 --pc-* 与布局类；视图间复用的基元
    │                              （列表头 .pc-list-head、工具行 .pc-tools-row、
    │                              授权表单栅格 .pc-form-grid 等）也收敛在这里
    ├── utils/format.js            时间、字节、JSON 等展示格式化
    ├── utils/autoRefresh.js       定时刷新的默认值、节拍档位与「这一拍要不要发请求」的纯函数
    └── views/                     12 个页面，见 §4
```

`src/api/endpoints.js` 与 `scripts/verify-console.py` 是这套结构里的两根钉子，
它们的约束见 [§5](#5-契约表与机械校验)。

---

## 3. 服务端如何托管

控制台是 history 路由的单页应用，服务端要承担两件事：把真实文件当静态资源返回，
把「不是文件」的路径交给入口页（否则刷新 `/console/catalogs` 会 404）。
两者都在 `ConsoleWebConfig` 里，用的是 `PathResourceResolver` 子类，**不是**回退控制器。

**为什么不用 `@GetMapping("/console/**")`。** 控制器映射的优先级高于静态资源处理器，
一旦挂上去就会把 `assets/**` 也接管过来，于是得在控制器里自己判断「这是文件还是路由」，
再把文件请求转交回静态资源链——绕一圈，还要处理转发回自身导致的循环。
`PathResourceResolver` 本来就在这条链上，「文件不存在则退回入口页」正是它的职责范围。

回退的判据是**路径末段有没有扩展名**：

| 请求 | 结果 |
| --- | --- |
| `/console` | 302 → `/console/` |
| `/console/` | 200 入口页 |
| `/console/catalogs` | 200 入口页（前端路由） |
| `/console/tables/paimon/default/orders` | 200 入口页（前端路由，三段参数） |
| `/console/assets/index-<hash>.js` | 200 `text/javascript` |
| `/console/assets/missing.js` | **404** |

最后一行是有意为之：缺失的 `.js` 必须 404。如果也回退成入口页，一次产物不同步
（浏览器缓存里是旧 HTML，引用了已被替换掉的带哈希文件）就会表现为
「页面白屏但状态码 200」，比直接 404 难查得多。

`/console/**` **不**在鉴权拦截器范围内（`WebConfig` 只给 `/v1/**`、`/api/catalog/v1/**`、
`/api/management/v1/**`、`/api/console/v1/**` 加了拦截）。浏览器无法在文档请求上带
`Authorization` 头，要保护静态资源只能靠 Cookie 会话，而那会引入 CSRF 面——
对一组不含数据的 JS/CSS 不值得。真正的边界在 API 上（见 §6）。

---

## 4. 页面清单

路由表在 `src/router/index.js`，侧边菜单直接由它聚合生成（按 `meta.group` 分组），
不另外维护菜单配置——加一个页面只需加一条路由。

| 路由 | 页面 | 覆盖的能力 |
| --- | --- | --- |
| `/login` | 登录 | 按服务端下发的登录方式渲染页签：用户名密码（默认）/ 主体凭据（OAuth 2.0 客户端凭据）/ SSO（OIDC）/ 静态令牌（需配 `auth.tokens`）。**未开启的方式不渲染**；一个可用方式都没有时说明该开哪个配置项。门禁单开时先显示一句「只挡住界面」。不套管理布局 |
| `/auth/callback` | SSO 回调 | 校验 `state` → 用授权码换令牌 → 让服务端确认 → 回跳原页面。不套管理布局 |
| `/` | 控制台概览 | 统计块（三个数量）、服务端配置摘要、**连接自检**、catalog 清单、`GET /v1/config` 展示 |
| `/browse` | 目录浏览 | 按选中 catalog 的**表格式**换一套实现：Paimon catalog 是「左库右对象、三页签（表/视图/函数；语义视图的 4 条端点仍在服务端，控制台不再暴露）」；Iceberg catalog 换成 `IcebergBrowseView`（左命名空间右表/视图，见 §4.6）；Lance catalog 换成 `LanceBrowseView`（左命名空间右表，**可建命名空间**；建表与删除都不给，见 §4.6.1）。Paimon 侧能力：建库、建表（Paimon 表 / Format 表 / Object 表三种表格式，Lance 表留在选项里但禁用，见 [console-catalog-detail.md](console-catalog-detail.md) §3.5）、注册表、按表 ID 定位、重命名、删除、详情抽屉。表页签列出表名 / 表格式 / 表类型（Managed 或外部表）/ 创建时间 / 元数据更新时间 / 操作六列，数据取自 `table-details`（只回表名的 `/tables` 给不出这几列）；「元数据更新时间」是服务端这条表记录的最后改动时间，快照提交与回滚也会推动它。表页签的过滤为空时说明「没有匹配 ×××」，与「库里没有表」区分开。**建表入口在「表」页签工具条的右侧**（页头只留刷新与新建数据库）：三类对象的工具条都写在 `ResourceState` 之外——那个组件命中空态时只渲染 `el-empty`、默认插槽整个不渲染，工具条若在里面，「这个库还是空的」这一刻就正好没有新建按钮 |
| `/tables/:prefix/:database/:table` | [表详情](console-table-detail.md) | 十页签：详细信息 / **血缘** / 数据预览 / 快照 / 标签 / 分支 / **文件列表** / 数据访问 / **数据策略** / **存储优化**（血缘与数据预览、数据策略都不随表格式伸缩：页签自己判空，见 [console-table-detail.md](console-table-detail.md) §1.1）；含快照与标签的**回滚**。**没有「变更」页签**：改表结构走 HTTP 或 Spark SQL，理由见该文档 §2。详细信息首屏的四张统计卡片取自 `getTableSnapshot`（三个文件统计的来路与「什么时候会空」见该文档 §2.1），内含**字段列表 / 分区列表**二级页签（名称过滤 + 分页：字段表在浏览器里切片，分区列表走远程分页且前缀过滤下推，分区按需加载）。文件列表按表目录物理内容列一层（根是表目录，出不了表外），目录名可点下钻、文件可下载、前缀过滤与页码都下推服务端。存储优化页签承载表级策略与执行历史（见 [table-optimization.md](table-optimization.md) §7）。血缘页签给三视图（图 / 邻接表 / 列映射），图是 Canvas 与「跳数即层号」的纯函数布局（见 [lineage-design.md](lineage-design.md) §9）。授权不在这张表上做——表级权限统一到「权限管理」页 |
| `/catalogs` | Catalogs | 列出 / 创建 / 详情 / 更新（带版本）/ 删除。名称链到详情页；操作列另有「浏览库与对象」直达 `/browse`。页头带**定时刷新**开关与节拍（默认开启、5 秒，偏好存 localStorage；见 §4.9）。表里有一列**凭据来源**（短期签发 / 转发长期密钥 / 不下发 / 配置有误，见 §4.10）；新建 / 编辑跳到独立页面（见 §4.11），那一页的存储表单同样带一份实时预览（同一判定，输入是还没保存的草稿） |
| `/catalogs/new` | 新建 Catalog | 四节一页（单列）：① 基本信息（名称 / 表格式按钮组 / 类型）② 存储（`StorageConfigFields`，存储类型也是按钮组，**先选认证方式再出那条路的字段**，高级参数折叠）③ 凭据来源（五档状态 + 服务端判定的原文 + 允许位置 + 「还缺哪一项」+ 保存前自检）④ 引擎接入配置（五个面各一份片段 + 长作业能不能续，可复制）。**字段名在左、控件在右**（`.pc-form-label-left`），页尾不再有报文卡片。结构见 §4.11 |
| `/catalogs/:catalogName/edit` | 编辑 Catalog | 与新建共用 `CatalogFormView`：名称与表格式是只读展示（服务端把它们当不可变列，改就 400），保存带 `currentEntityVersion`，报文**不含** `catalogFormat` |
| `/catalogs/:catalogName` | [Catalog 详情](console-catalog-detail.md) | 四页签：概览 / 存储 / 文件列表 / 配置。**存储**页回答「哪些表该做 compaction」：文件数、存储、平均文件大小、小文件占比（按库聚合），两种格式都支持，口径参考 AWS《Monitoring Iceberg workloads》但取数只用已有端点，见 [console-catalog-detail.md §3.2](console-catalog-detail.md)。文件列表按仓库物理目录列一层，目录名可点下钻、带面包屑与「返回上级」；只读，不提供删除与建目录。**配置页签**两种格式是同一套设计：都编辑「表默认参数」（`table-default.*`，见 [catalog-api-endpoints.md](catalog-api-endpoints.md#catalog-级默认表属性table-default)），Iceberg 另附只读的 `/v1/config` `defaults` / `overrides`（连接参数，非表属性）；其余 catalog 属性不在此页重复展示（概览已有） |
| `/principals` | 主体 | 列出 / 创建 / 更新 / 删除 / rotate / reset；授予与撤销角色。创建、轮换、重置后的凭据弹窗可一键导出 JSON 文件（`{principal, clientId, clientSecret}`，文件名 `<主体>-credentials.json`），密钥仍只在弹窗期间可见 |
| `/principal-roles` | 角色 | 列出 / 创建 / 更新 / 删除；查看成员（授予成员要按主体操作，在「主体」页做）。角色是**纯分组**，自身不携带权限；管理规格里的 `federated` 字段控制台不暴露——本服务不接入外部 IdP，勾了只存一个标记，且 PUT 不接受该字段，勾错改不回来 |
| `/permissions` | 权限管理 | 控制台**唯一**的授权入口（Paimon 官方权限模型，principal 直授）：页头「添加授权」按钮开弹窗填表——多选授权对象（`user:` / `role:`，可手输任意 principal）、选**作用范围**（Catalog / Database / Table / View，四级就是官方的四种资源类型；**Iceberg catalog 下 `Database` 这一级显示为 `Namespace`**，候选与文案都跟着换，见 §4.8；catalog 不需要选库，表 / 视图还要选具体对象）、权限项按范围给（catalog：Create database/Alter/Drop/Grant；db：Create table/Alter/Drop/Describe；表：Select/Insert/Delete/Describe/Alter/Drop + 两个凭据点 Use credential/Use stored key，判权语义见 [`authorization.md` §3.7](authorization.md)；视图：Select/Describe/Alter/Drop）、**有效期**默认长期有效，可选「指定日期」（按所选当天 23:59:59 本地时区换算成官方的 UTC instant；日历与手输都挡掉过去的时间）；主界面是授权清单——一行 = 一个主体 × 一个作用范围，同范围的权限并列成标签、点标签上的 × 逐项撤销，过期时间按日期显示（完整时刻在悬浮提示，7 天内到期标「N 天后」，过期条目不隐藏但标注「已过期」，多项不一致取最早并标注），资源合成「作用范围」一列（`整个 catalog` / `库` / `库.对象`，标注整 catalog/整库/单表），权限用与表单一致的词（UPDATE 显示为 Insert / Delete），空态给「添加授权」入口；支持过滤与幂等撤销 |
| `/policies` | 数据保护 | 行过滤与列脱敏策略的总览页（数据来自扩展端点 `GET /api/management/v1/policies`）。四张统计卡（受保护表 / 带行过滤 / 脱敏列 / 豁免主体）与列表共用**一次全量拉取**：策略量级是「几十」（`DataPolicyService.list` 的注释口径），要跨页 KPI 与组合过滤，只有整份列表在手才数得准——这是「先过滤再切页」规则的又一实例，与「全站远程分页」的例外判据（有没有列表端点之外的整份列表需求）一致；此处列表端点本身支持分页，但页面刻意不传 `page`。列表列：表（三段名拆解，链接到表详情 `?tab=policy` 深链接）/ 行过滤 SQL（`code` 展示）/ 脱敏列（tags）/ 状态 / 版本 / 更新时间与操作者；操作列「试算」跳主体视角试算页、「详情」跳表详情的数据策略页签。表详情的「数据策略」页签见 [row-filter-column-mask-design.md](row-filter-column-mask-design.md) §8.3 |
| `/policies/new` | 新建策略向导 | 目标表三段级联（catalog / database / table，watch 级联清空）→ 拿表 schema 做候选列 → 主体范围与豁免（fail-closed 不可关）→ 行过滤 / 列脱敏编辑器（与表详情页签共用 `src/utils/policyAst.js`，保证两处建出的 AST 形状一致）→ 发布（PUT 整份替换）。**已绑定策略的表不给新建**：PUT 是整份替换，向导里再建一次会静默顶掉现有策略，此时给红色提示并引导去表详情编辑 |
| `/policies/:catalog/:database/:table/dryrun` | 主体视角试算 | 按**被模拟主体**（不是当前调用者）跑 `POST .../policies/tables/{c}/{d}/{t}/dry-run`，四态横幅：FOUND（生效中，附折叠后的行过滤 SQL）/ EXEMPT（豁免，明文）/ DISABLED（策略停用）/ NONE（无策略或白名单外——**红色**，提示「按默认放行，全部为明文」）。结果表按返回列渲染，脱敏列单元格带「脱敏 / 置空」角标。被模拟主体**不需要存在**（`$principal` 折名字字面量、角色名单查不到折 false），这正是「新建主体还没挂角色」要试算的场景。端点判权 TABLE:ALTER——试算结果本身就是策略内容，放宽到 SELECT 等于任何能读表的人可看策略文本 |
| `/optimization-jobs` | 优化任务 | 表存储优化的批次运维页（数据来自管理 API `/api/management/v1/optimization/**`，策略配置在表详情的「存储优化」页签）。四张统计卡（运行中 / 排队中 / 今日批次 / 失败 24h）与列表共用**最近 200 个批次**这个窗口，但统计卡**不随筛选变化**：卡片回答「这个系统现在什么状态」，筛选回答「我在找某个批次」——两者绑在一起时一筛状态卡片就全变 0，读起来像系统出故障。状态 / 动作 / Catalog / 触发方式四个过滤器**下推服务端**（只筛当前页等于没筛，`total` 也来自 `page.total`）。批次表展开行按需拉表级明细（每行指标摘要与失败原因），「批次详情」抽屉给键值对 + 时间线 + 回调令牌 + 原始响应；操作列的重试（只重跑失败表，原批次保留）与取消都带确认框。`?job=` 查询参数用于从表详情的历史点批次号跳过来直接展开。页头带**定时刷新**开关与节拍（默认开启、5 秒；展开中的批次明细跟着静默刷新，见 §4.9） |
| `/events` | 事件 | 目录事件流（数据来自控制台扩展端点 `/api/console/v1/events`，事件语义见 [event-framework.md](event-framework.md)）。**这里的「刷新」是带游标的增量拉取而不是全量重拉**：首次进入显示当前最新的一页（最近 200 条，游标落在「现在」），之后每次刷新只取比游标新的事件、插到列表最前面——事件是只增不改的事实，增量流保证每个事件至少被看到一次，全量重拉的间隙会漏事件。列表头右侧显示当前游标；「重置游标」清掉游标重新取最新一页（不是回放全部历史，更早的记录去查库）；顶栏的全局「刷新」也走同一条增量路径。**Catalog 与类别过滤在服务端做**（都是查询参数，换挡会重置游标——换筛选要的是「这个范围现在最新的一页」），事件类型过滤在本地做、候选按类别分组（38 个类型平铺太长），换类别时会清掉不属于该类的类型筛选。列表列：时间 / 事件（38 类中文标签）/ 目录 / 目标（类型 + 定位符）/ 主体 / 属性摘要；展开行给事件 ID、**请求 ID**（同一次 API 调用产生的所有事件共享它，按它串起「这次调用到底动了什么」）、类型与类别、完整属性 JSON |
| `/settings` | 连接设置 | API 基址、访问令牌、登录状态、主题；`/api/console/v1/meta` 原始响应 |
| `/:pathMatch(.*)*` | 未找到 | 兜底 |

### 4.0 文案与信息结构的约定

这套约定在给「权限管理」页做设计走查时成型，随后推广到全部页面。它决定了
同一个事实出现在哪里、用什么词：

- **一个事实只在一处说。** 页头副标题讲「这页干什么、改了会影响到谁」（人话）；
  接口路径出现在列表头右侧或卡片的 `hint` 里（机制）；字段含义进 `FieldLabel`
  的问号气泡。三者不互相重复——副标题里写 HTTP 动词与端点路径，等于让每个
  使用者每次都为排障者付一次阅读成本。
- **空态是行动邀请。** 列表为空时给出下一步的按钮（`ResourceState` 的
  `#actions` 插槽）；过滤到空时说清是「没有匹配 ×××」，与「本来就没有」区分。
- **一个动作一个名字。** 页头按钮 → 弹窗标题 → 提交按钮 → toast 用同一组词：
  「添加授权」打开弹窗，提交按钮是「授予（N）」，toast 是「已授予 N 条授权」。
- **授权只有一个入口。** 「权限管理」是控制台唯一的授权页面：四级资源（catalog /
  database / table / view）各有自己的一组权限词，全部在这一页里授与撤。Polaris 的
  RBAC（主体 → 角色 → catalog 角色 → 资源授权）不再有页面——它的三件套在
  控制台里只留下被引用的一小部分（授权对象的 `role:` 候选、角色页的装配），
  管理面只剩管理 API（Paimon 官方 REST Management API + 服务端扩展端点）一个入口
  （catalog role 的建档与装配在 Spark SQL 侧也已经
  没有语句了）。服务端判定不变：两路取并集。
- **表单只有一种形态。** 全站表单统一走全局基元 `.pc-form`（标签在控件上方、
  13px 弱化色、字段间距 14px），字段多且语义并列时加 `.pc-form-grid` 自动折行，
  表单下的提交行是 `.pc-form-actions`，「可重复字段行」（表字段、允许位置、
  属性键值对）是 `.pc-repeat` / `.pc-repeat-row`。唯一例外是登录页——独立版面、
  控件用 large，需要更松的节奏，不跟随控制台栅格。此前这些值散在 9 个
  视图/组件的 scoped 样式里（标签 12.5/13/14px、字段间距 10/16/18px 各三种），
  新加一个弹窗就要同步一份副本；现在改一处，全站生效。
- **长列表：全站远程分页。** 页码、页大小、过滤条件全部下推，页面只持有一页，
  底部是统一的 `TablePager`（20 / 50 / 100；`total` 为 0 时整块不渲染，空态已经
  有说明文字）。契约见 `Paging.paged`：**不传 `page` 就不分页**（老路径一次给全量，
  脚本、Spark / Flink 客户端与 curl 都不受影响），传了才按页码切并回 `page.total`。
  三条配套规则都是从真实错法里来的：
  1. **先过滤再切页**——可见性逐条判定（catalog 授权、优化批次按 catalog 授权），
     只在整份列表在手时才切得准，否则 `total` 会把不可见资源的数量泄露出去；
  2. **先折叠再切页**——授权清单的一行是「主体 × 作用范围」，折在切页之后，
     同一组就会被页边界劈成两行，界面上同一个主体出现两次；
  3. **过滤必须一起下推**——本地筛只筛得到当页，「筛出 3 条」会被读成「库里只有 3 条」。
  文件列表同样走这一套：`total` 是这一层过滤后的真实条数，不再有 `max-entries`
  截断的提示条（分页模式下 `truncated` 恒为 `false`）。前缀/表名这类缩范围的框
  仍**回车或防抖 300ms 才发请求**——逐字符是把每次按键变成一次存储列举。
  唯一还在浏览器里切片的是**随详情一起下发、没有独立列表端点**的那份数据
  （表 schema 的字段表，`useEmbeddedPaging`）；判据是「有没有列表端点」，
  不是「数据量大不大」。同理不分页的还有两处**固定窗口**：首页的 catalog 摘要表
  （读的是会话里那份已加载列表，卡片本来就是摘要）与「存储优化」里每个动作的
  执行历史（有上限 `HISTORY_LIMIT = 20`，多出来的部分用一行文字说明「共 N 条，
  仅显示最近 20 条」——那段文字就是这个表的「翻到更多」）。
- **「操作」列钉在右侧，展开箭头钉在左侧。** 表格列多，窄屏下必然要横向滚：
  操作列跟着内容一起滚走，等于「滚到右边才找得到按钮」，滚回来又不见了。
  所以凡是带行内动作的表格，操作列一律 `fixed="right"`；可展开的表格（优化批次）
  把 `type="expand"` 那列 `fixed="left"`，展开箭头同样不随滚动跑。
  这不是装饰：它决定了「一屏里能不能同时看到这是哪一行、能对它做什么」——
  两者分处滚动条两端时，读一行要来回滚两次。
- **圆角统一 2px。** 一屏里常同时出现卡片、统计块、表格、标签、按钮；圆角一大
  （曾为 6~14px，Element Plus 自己是 4px）它们各自成一个气泡，块与块之间显得碎。
  2px 只削掉直角，分层交给留白与描边。收敛在 `--pc-radius`（自有组件）与
  `--el-border-radius-base`（一个值带动 71 处：按钮 / 输入 / 下拉 / 弹窗 /
  单选按钮组……）。有两处 Element Plus 的坑：标签、卡片、消息框、通知、popover
  的圆角变量被它声明在**组件元素自己身上**（如 `.el-tag { --el-tag-border-radius: 4px }`），
  自定义属性「元素自身声明优先于继承」，`:root` 的覆盖够不着，只能在
  `styles/index.css` 里按组件类名再覆盖一层；tooltip 走 `--el-popper-border-radius`。
  刻意不动的是「胶囊形 / 圆形」：开关轨道、进度条、徽标、滑块轨道、圆形图标按钮——
  那里的圆角表达的是形状（半圆或整圆），改成方角就画错了。
- **控件高度跟设计基准走，不跟 Element Plus 的默认值走。** 控制台把 `small`
  从 24px 抬到 30px（`--el-component-size-small`），但 Element Plus 有三个组件
  把高度钉在自己的变量上、不跟随基准，已逐一补齐：按钮（`.el-button` 钉 24/32px）、
  下拉框（`.el-select__wrapper` 钉 24px）、数字输入的步进器
  （`.is-controls-right` 的 `--el-input-number-controls-height` 按 EP 原生
  24/32/40px 算好——11px 的两个「＋/－」放在 30px 的输入框里，中间空出 6px 一条缝）。
  规则集中在 `styles/index.css`，数值从基准反算（步进器 = (控件高 − 2) / 2，
  上下各内缩 1px），改基准时它们自己跟着走。
- **密度即美学。** 这是运维界面：不做渐变统计卡、不做入场动画、不做全大写小标签。
  统计块只放真正的数量（能力类信息进「服务端配置」列表），摘要页的表格只保留
  「有什么、数据在哪」，详情留给对应的管理页。
- **列表不放版本号。** 管理资源（catalog / principal / principal-role）的列表曾经
  各有一列「版本」，表详情的快照列表也有一列，现在都不显示。两类版本号都**不该由
  列表承担**：前者（`entityVersion`）是写入做乐观并发的令牌（`PUT` 要带
  `currentEntityVersion`），对「这页有哪些资源、最后谁改的」没有信息量；后者
  （快照的 `version`）定位一个快照用它的 ID 与提交时间就够了。它们的正确位置分别是
  编辑弹窗里那个禁用的「当前版本」字段、以及「按版本号查询」输入框 / 详情抽屉标题 /
  回滚确认框——真正用得着它们的地方。
- **说明收进图标，不铺在页面上。** 区块级的补充说明（口径、边界、「为什么不做」）
  用 `TipNote` 收进一个问号图标，悬停才展开——原先它们是两三行一段直接铺开的
  `.pc-tip`，一页几处就把数据挤出视野。字段级的备注仍走 `FieldLabel`
  （图标跟在字段名后），两者共用同一套图标与气泡样式。
  **提示性告警（info / warning）同一待遇**：`TipAlert` 把 `el-alert` 的正文
  收进标题旁的问号，页面只留一行「结论」。边界写在组件注释里、由 `verify-console`
  第 16 组盯着——错误（error）不收（必须一眼看到）、正文里带按钮/复制控件的
  不收（气泡里点不到，动作等于丢了）、调用点必须带标题（没标题就只剩一个
  点了没反应的图标）。

### 4.1 连接自检

首页的「连接自检」不是装饰：控制台最常见的故障是**地址或令牌配错**，
而其表现是「每个页面都是空列表」，看不出根因。它逐个探测控制台依赖的端点，
把失败的那一个连同 HTTP 状态与错误消息一起列出来，一眼能分辨
「服务端没起来」「令牌不对」「路径写错了」。

第三条探测是 `GET /v1/config?warehouse=<当前 catalog>`，**选中 Lance catalog 时不发**，
显示为「不适用」并说明理由：Lance 规范里没有这条发现端点，发了服务端只会回
`no protocol implementation is registered for catalog format LANCE`，那一行就成了
一个在报「不存在的问题」的红「失败」。

### 4.2 表单取值的来源

所有下拉框的取值都来自 `GET /api/console/v1/meta`，**不在前端硬编码**：

- `catalogTypes` / `storageTypes` / `credentialManagerTypes` / `fileIoTypes`
- `supportedStorageTypes`：本部署实际可用的存储类型子集（由 `file-io.type` 决定）。
  控制台把不可用项渲染成**禁用**而不是隐藏——看到「有 OBS 这个类型但当前部署没开」
  比看不到要好。
- `staticCredentialsEnabled`：本部署是否允许 catalog 自带静态凭据（由
  `storage.credential-secret-key` 是否配上决定），见 4.3。
- `grantTypes` 与 `privilegesByGrantType`：资源类型 → 该层级可授予的权限。
  这份对应关系由服务端的 `Privilege.allowedFor(String)` 从管理规格还原。
  前端若复制一份必然漂移，而漂移的表现是「提交后才被服务端拒绝」，排查成本高。
  **权限表单不据此渲染**：它要的是四级各一组、去掉歧义的简化词（见 §4 的
  `/permissions`），照抄官方词表反而把 `ALL`、`COLUMN` 这类选项摊到用户面前；
  这两个字段保留在响应里，由规格一致性断言（`metaPrivilegesFollowManagementSpec`）盯着。

meta 只暴露枚举取值与服务端自身的配置，不含任何主体、catalog 或凭据信息，
因此与 `/api/management/v1/**` 一样只过认证拦截器，不做细粒度授权判定。

### 4.3 存储配置表单里的凭据字段

创建 / 编辑 catalog 时的存储配置（`components/StorageConfigFields.vue`）在
`S3` / `OBS` / `OSS` / `COS` 四种类型下多出一组「静态凭据」：`accessKeyId` 与
`secretAccessKey`（密码框，可切换显示）。它是本工程在规格之外的扩展，语义与边界见
[`README.md` 的存储与凭据一节](../README.md#5-配置项)，这里只说表单侧的做法。

**凭据只有一种形态：写在 catalog 的 `storageConfigInfo` 上。** 服务端配置里一行都没有
（`lakehouse.rest.storage` 只剩落库加密密钥与连接参数），因为每个 catalog 的存储配置
本来就不一样——同一套部署里的两个 S3 catalog 可以属于两个云账号。表单因此按类型给出
四组形状：

| 存储类型 | 凭据字段 | 表单形态 |
| --- | --- | --- |
| `S3` / `OBS` / `OSS` / `COS` | `accessKeyId` + `secretAccessKey` | 两个单行框，密钥是密码框；`******` 只出现在 placeholder 里 |
| `AZURE` | `tenantId` + `clientId` + `clientSecret`（可再给 `authority` / `scope`） | 租户在主表单（决定仓库在哪），服务主体两框与两个可选框在折叠区 |
| `GCS` | `serviceAccountJson` | 单行框，整段 JSON 粘进去（模板只有单行 / 布尔 / 列表三种控件） |
| `OSS`（追加） | `roleArn` + `stsEndpoint` | 折叠区「阿里云 RAM STS」一组，与 S3 的 `roleArn` 同位置 |

三种形状的**用途不一样**，表单必须说清，否则用户会以为填了就能用：

- `accessKeyId` / `secretAccessKey` 是唯一可能**转发给引擎**的一对（没有签发材料时）；
  填了 `roleArn` 时它们改为「服务端去 AssumeRole 时出示的调用方身份」，不再转发。
- Azure 的 `clientId` / `clientSecret` 与 GCS 的 `serviceAccountJson` 是**服务端换票的身份**，
  永远不下发。把服务主体发给引擎等于把「能签任意 SAS 的身份」交出去。
- GCS 的 `serviceAccountJson` 打码位置与别家不同：它没有 ID 字段，`******` 打在 JSON 本体上。

**「具名存储」（`storageName`）已从表单移除。** 它指向的
`lakehouse.rest.storage.<厂商>.storages` 配置组在 v3 一起删掉了，字段本身退化成 DTO 上一个
不参与任何判定的标签；留着输入框会让人以为「填了它就能不填密钥」。DTO 字段保留（老客户端
仍可原样回传），但表单不再渲染它，`build()` 也会把它从提交体里删掉。

其余表单规则：
- **是否渲染由 `staticCredentialsEnabled` 决定**（即服务端有没有配 `credential-secret-key`）。
  取不到 meta 时按 `false` 处理——宁可少一个字段，也不要让用户填完才被 400 拒绝。
- **能力关着时不把字段藏起来**，而是在原位说明该开哪个配置项、以及生成密钥的命令
  （`openssl rand -base64 32`）。藏起来的后果是「别人告诉我这个功能存在，但我这儿看不到」。
- **两个字段都不预填真值。** 服务端把 `accessKeyId` 打码成 `******`、密钥根本不返回，
  因此编辑已有 catalog 时两个输入框都是空的，AK 那格的 placeholder 是
  「已保存（******），留空即保持不变」。**不把星号灌进输入框**：灌进去用户会以为那是真值，
  编辑时很容易把星号改成「星号加一个字符」，服务端不认、报错又说不清。
- **提交时回传哨兵而不是省略 AK ID。** 输入框空但库里有凭据时，`build()` 发
  `accessKeyId: "******"`——省略只能表达「两个字段都不改」，而用户只想换密钥时
  省略会变成「有密钥没 ID」，服务端只能拒。哨兵是「ID 沿用库里的、密钥换新的」的唯一表达。
- **「移除已保存的静态凭据」按钮**提交一对空串（二次确认），之后下发**不再带任何密钥**
  （服务端配置里已经没有可以回落的凭据了，引擎走自己的凭据链）。
  注意这与上面那条的区别：**`******` 不是「清除」**，清除只有一对空串这一条路。
- **前端先拦一遍**同样的规则：有密钥没 ID、换 ID 不带新密钥、填了 `roleArn` 却没给签名
  AK/SK、能力未开启。理由与下拉候选来源一致：服务端只会回一句笼统的 400，而这一层能说清是哪一条。
  判断「有没有凭据」时同时看输入框与 `storedAccessKeyId`——打码之后输入框本来就是空的，
  只看输入框会把已配好的 catalog 判成没配。**「与 `storageName` 互斥」那条规则已删**：
  具名存储配置组不在了，字段本身也退出了表单（见 §4.2）。

### 4.4 存储高级参数（折叠区）

同一个组件里，字段被拆成两张表：`BASIC_FIELDS`（主表单平铺）与 `ADVANCED_FIELDS`
（收进默认收起的「存储高级参数」）。判据只有一条：**不填就跑不起来的留在外面**
（`region` / `endpoint` / Azure 的 `tenantId` 决定仓库在哪），其余收进去。

| 存储类型 | 主表单 | 折叠区 |
| --- | --- | --- |
| `S3` | `region`、`endpoint` | `roleArn`、`externalId`、`userArn`、`stsEndpoint`、`endpointInternal`、`stsUnavailable`、`noInlinePolicy`、`pathStyleAccess`、`kmsUnavailable`、`encryptionKeys`、`decryptionKeys`（11 项，分「STS 与角色 / 访问方式 / KMS 与加密」三组） |
| `AZURE` | `tenantId`（必填） | `multiTenantAppName`、`consentUrl`、`hierarchical` |
| `GCS` | — | `gcsServiceAccount` |
| `OBS` / `OSS` | `endpoint` | `stsUnavailable` |
| `COS` | `endpoint`（COSN 的 `endpoint_suffix`）、`bucketRegion` | `appId`（只用于校验桶名）、`useHttps`（缺省开）、`stsUnavailable` |
| `FILE` | — | 无（折叠区整块不出现） |

表中是**当前**表单的实际字段。§4.3 末尾那三组缺口补上之后，Azure 行会多出
`clientId` / `clientSecret` / `authority` / `scope`，GCS 行会把 `gcsServiceAccount`
换成 `serviceAccountJson`（多行），`OBS` / `OSS` 行会拆开——OSS 多一组
`roleArn` / `stsEndpoint`。

三条规则与它们的理由：

- **默认收起，且不自动展开。** 初始值恒为 `false`，不由「是否有值」反推；  换类型与
  换一份配置（从新建页切到编辑页、或切到另一个 catalog）时重置为收起。不自动展开是因为状态会被抢：
  用户手动收起后，任何一次父组件回传都会把它再弹开，「我收起来了它自己又开」比
  「我得多点一下」糟得多。代价是收起态**必须看得见有值**——所以折叠头带计数徽标，
  下面还有一行「已填：`roleArn`、…」的摘要。
- **「已填」的判定与落盘规则一一对应。** 文本与列表按「有非空项」计，布尔**只在打开时**计。
  `false` 是默认值，算成「已填」会让每份 S3 配置都显示「已填 3 项」，计数立刻失去意义。
- **折叠只改变可见性，绝不改变提交内容。** `build()` 读的是两张表**合并后**的 `fields`，
  因此它不知道自己被分了层；对布尔项仍是既有的「无条件写入」。谁都不许在这里加
  「收起就不提交」——那会造出「展开看一眼再收起来，报文里多了几个 `false`」这种
  只能靠 diff 报文发现的行为差异。第 13 组盯着这一条，`AGENTS.md` 里也写了。

两点与需求相关的说明：

- **「不限制」的边界是「三项都不设」**：都不必填（`validate()` 不为折叠区加规则）、
  取值不校验形状（ARN / URL 的形态随云厂商与私有化部署而变，前端猜错一次就拦掉一个
  合法配置）、`encryptionKeys` / `decryptionKeys` 条数不限。它**不是**「键名任意」——
  服务端 `StorageConfigInfo` 是判别联合，未知键会被静默丢弃（这一行为有测试钉死：
  `StorageConfigApiTests.fieldsBelongingToAnotherStorageTypeAreIgnored`），
  要做「任意键」得先扩服务端契约。
- **这 11 项里服务端当前只消费 3 项**（`pathStyleAccess` → 下发 `s3.path-style-access`，
  `stsUnavailable` → 决定是否下发密钥，`noInlinePolicy` → 签发时不带 inline policy）。
  其余 8 项接收、落库、回显，但不参与行为——
  它们仍是管理 API 契约的一部分（形状沿用存档的那份 Polaris 规格），所以不能删控件。前端判断不了「哪几个真的生效」，
  因此折叠区里只给了一句中立说明（「是否影响引擎行为，取决于服务端的凭据实现」），
  不点名字段、不加徽标：把服务端的实现细节抄进前端，正是 §10 反对的做法。
- **例外：`noInlinePolicy` 打开时那条告警放在折叠区外面。** 它是唯一一条「安全属性被主动
  降级」的声明（票退回角色权限范围、无法按表收窄），收进折叠区就等于只有点开的人知道。
  判据是**字段值本身**，不是「谁推断出了什么档位」——它与第 24 组禁的那类前端推断无关。

### 4.5 `roleArn` 与静态凭据并存：允许，且签发优先

早先服务端把「静态凭据 + `roleArn`」判为互斥（400），控制台因此只给一条提示、不写进
`validateCredentials()`。**那条互斥规则已经删除**，因为两者并存是最常见的自建机房形态：
一把只允许 `sts:AssumeRole` 的钥匙，配一个角色，让服务端去换票。

于是语义变成「同一个字段、两种身份」：

| 填了什么 | 那对 AK/SK 是什么 | 走哪一档 |
| --- | --- | --- |
| 只有钥匙 | 数据面凭据 | 转发（`RELAY`） |
| 钥匙 + `roleArn`（S3）／ + `roleArn`（OSS，且启用 `oss-sts`） | 换票时的**调用方身份** | 签发（`STS`） |
| 钥匙 + `stsUnavailable=true` | 数据面凭据 | 转发（`RELAY`，不调云） |
| 只有 `roleArn` 但没钥匙 | —— | OSS 是「配置有误」（`INVALID`，阿里云 STS 必须签名）；S3 交给 SDK 默认链（`STS`） |

前端**不复制这张表**：档位只来自服务端（§24 组反向断言盯着这条）。表单要做的事只有两件——
把 `******` 哨兵按 §4.3 的规则处理，以及在 `roleArn` 填了而本部署没启用签发 provider 时
把判定结果原样显示出来（那句 detail 会点名要开的配置项）。

### 4.6 Iceberg catalog 的目录浏览

Iceberg catalog 打开 `/browse` 时渲染的是 `IcebergBrowseView`，不是 Paimon 那一页
加一串 `v-if`。分开的理由不是洁癖——两套契约的差异到了「资源都不叫同一个名字」的程度：

| | Paimon 面 | Iceberg 面 |
| --- | --- | --- |
| 资源 | `databases` | `namespaces`（层级数组） |
| 路径段 | `/v1/{prefix}/databases/{db}` | `/v1/{prefix}/namespaces/{ns}`，层级用 `0x1F`（`%1F`）拼 |
| 分页参数 | `maxResults` + `pageToken`；控制台另传 `page` 走页码模式 | `pageSize` + `next-page-token`；控制台同样传 `page` |
| 表列表内容 | `table-details`（格式 / 表类型 / 时间） | `identifiers`（**只有名字**） |
| 标识符 | `{database, object}` | `{namespace: [...], name}` |

两个子视图都是**整页**：自己渲染 `PageHeader` 与 `.pc-page` 容器。因此 `BrowseView` 把它们
并到**根层**（`v-if` / `v-else-if` / `v-else` 三个兄弟节点），不能塞进自己的 `.pc-page` 里——
嵌套会让 padding 生效两遍：标题右移 26px、整页下移 24px，Iceberg / Lance 看起来比 Paimon
「窄一圈」，而两边的 CSS 都写得没错。这个坑只有量 DOM 才看得见，所以第 12 组直接钉结构。

**能做什么**：命名空间平铺列出（含多级，点号显示）、新建（**没有删除入口**——
Iceberg 的 drop 非级联、非空回 409，误触成本高，已按需求把按钮从界面拿掉；
服务端端点与 API 层的 `dropNamespace` 保留）、建表、登记已有表（`metadata-location`）、
重命名、删除、注销（`unregister`：只摘目录条目、保留文件）、视图列表 / 详情 / **新建** / 登记 / 删除；
表名与「详情」进 **Iceberg 表详情页**（§4.7），视图详情走抽屉
（`LoadViewResponse` 原文 + 元数据摘要）。

**不做什么，以及为什么**：

- **不在列表里显示 location / 时间 / 快照数。** `identifiers` 里没有这些字段，
  要拼出来只能逐表 `loadTable`——N 次请求换一屏数据，而且一旦表多到翻页就不可行。
  这些信息在表详情页按需取（见 §4.7）。
- **建视图只做常见形状。** 表单收「字段 + 一句 SQL」，据此拼出 `schema` 与第一个
  `view-version`（`version-id` 为 1、`timestamp-ms` 取当前时间、单条 `sql` 表示、
  `default-catalog` / `default-namespace` 取当前上下文）。多方言并存、多版本、
  带完整 `summary` 的视图仍走 Spark 或客户端——控制台不复刻那份报文的全部组合，
  页面上也直说了这一点。
- **不按表 ID 定位。** Iceberg 协议面没有 `tables/id/{id}` 这条反查端点。

### 4.6.1 Lance catalog：浏览 + 建命名空间

Lance catalog 打开 `/browse` 时渲染 `LanceBrowseView`。**版式与 Paimon / Iceberg 两个浏览页
是同一套**：左命名空间树 + 右卡片（`pc-pane-head` 里一颗格式 tag、`pc-tools-row` 里的过滤框、
`el-table`、`TablePager`），点表名或操作列的「详情」进独立详情页（§4.6.2）。
早先这里是自绘的行列表 + 详情抽屉，同一件事三种交互，读代码的人要在两套结构间来回翻译。

**写只有一处——「新建命名空间」；建表、登记、重命名、删除都没有入口。**

浏览与建表曾被混在一起，所以更早的时候干脆整页不给。三件事拆开来看就清楚了：

- **浏览**只要 `ListNamespaces` / `ListTables` / `DescribeTable`，三条服务端都有（§7 的 Lance 面）；
- **建命名空间**是 `CreateNamespace`，也在 19 个 operation 里，而且它是**纯元数据登记**：
  服务端只落一行 database 记录、带一个按 `path-template` 算出来的预计位置，**不碰存储**。
  所以它可以给界面——建错了删掉就是（drop 由客户端发，页面不提供）；
- **建表**是另一回事：这台服务端对 Lance 没有建立数据的路径（没有 `CreateTable`，
  数据集由客户端写进仓库，服务端只 declare / register）。给一个能建表的界面，
  用户建出来的东西与盘上的数据集对不上，而错误现场在几小时后的客户端查询里。

于是本页的边界是「能登记容器，不能登记表」。

四条取数决定：

| 位置 | 怎么取 | 理由 |
| --- | --- | --- |
| 命名空间 | `GET /lance/v1/namespace/{id}/list`，响应是**名字数组**（`["sales"]`），页面自己按 `lanceId(prefix, 名字)` 拼回整串；`id` 从 catalog 名起逐级拼 | Lance 的 `id` 是 delimiter 拼的整串（`catalog$schema$table`），没有 `/v1/{prefix}` 那种前缀路径；规范让 `ListNamespaces` 回**相对名字**（回完整 id 客户端会拼成四段而 400），所以拼的工作在页面这一侧。**元素再用 `lanceNameOf` 归一一次**：老版本服务端回 `[{id, properties}]`，不归一的话对象会被渲染成 `[object Object]`、再拼进 id 变成 `namespace id level "[object Object]" contains characters outside [A-Za-z0-9_.-]`（400）——那条报文像「id 里有非法字符」，真因是拿对象当字符串用了 |
| 表列表 | `GET /lance/v1/namespace/{id}/table/list`，行里存**完整 id**，表格两列：`lanceLeaf(id)`（可点）与整串 | 服务端回的是完整 id 而非短名（照短名拼会得到 `cat$db$cat$db$tbl`）；详情要的也是整串，所以两列都摆出来——短名给人看，整串给复制去命令行的人。**这一页的翻页在浏览器里切（`useEmbeddedPaging`），不套 `useRemotePaging`**：`ListTables` 有 `limit` / `page_token` 但没有 `total`，服务端不告诉你有几页，远程分页的控件就画不出合法的页数。表数量按命名空间规模算，一次取回来本地切足够 |
| 详情 | 不在本页做，跳 `/lance-tables/:prefix/:namespace/:table`（§4.6.2） | 六个页签（版本 / 标签 / 文件 / 血缘 / 存储优化）各自按需发请求，塞进抽屉会变成一个自己带滚动条的页面。路由里的 `namespace` 段是**叶子名**——存储优化接口要的是 `lakehouse_database.name`，整串 id 它不认 |
| 新建命名空间 | `POST /lance/v1/namespace/{id}/create`，`id` 是**新命名空间的完整 id**（`catalog$名字`），body 里同样带 `id` 与 `properties` | 只能建两级（`LanceIds` 把 namespace 类 operation 收窄到 1–2 段，只给 catalog 时服务端回「catalog 级不支持」）。不带 `mode`：缺省 `create`，已存在时 409 原样显示；`exist_ok` / `overwrite` 是给脚本的幂等模式，控制台不提供——「点一下创建却悄悄改了别人的属性」比报一个错更难查 |

名称在提交前本地挡两道：空，以及字符集 `^[A-Za-z0-9_.-]+$`（与 `LanceIds.LEVEL` 同一个集合）。
`$` 尤其要挡——它是 id 的分隔符，放进去等于在名字里开了一层，客户端拿到的 id 与用户填的名字
就不是一回事了。这一条与「写端点只允许 create」一起由第 12 组钉住。

「读取详细元数据」按钮在详情页（§4.6.2），再发一次 `load_detailed_metadata=true`——默认不带，
因为那要多读一遍 manifest，浏览时用不上。

三处伴随的收敛（都由第 12 组钉住）：

| 位置 | 收敛成什么 | 理由 |
| --- | --- | --- |
| 目录浏览 | 可建命名空间；**不出现**「新建数据库」/「建表」/ 删除 | 数据库那一套调的是 `/v1/{prefix}/databases/**`（对 Lance catalog 是 404）；建表与删除没有服务端路径 |
| catalog 详情 | 只剩「概览」与「文件列表」 | 「存储」的数字来自快照 / 表元数据统计；「配置」编辑的 `table-default.*` 只在建表时合并 |
| 表详情 | 六个页签里**没有**「数据策略」，也没有「数据预览」 | 行过滤 / 列脱敏在 Spark 侧按 `PolicyRelation` 注入，Lance 的读取路径不经过那一层；预览更彻底——`TablePreviewFactory` 只有 Paimon 与 Iceberg 两个实现，给个页签就是给自己一个 500 |

另一处只有 Lance 才有的：**它没有 `/v1/config`**。因此详情页不读那条发现端点
（发了只会拿到 404，而 404 会渲染成「读不到」，看着像服务端坏了），
「能力清单」也改成显示**我们实现了多少**——19 个 operation / 21 条端点，其余 35 个恒 406。
那三个数字由第 12 组与 `verify-lance-endpoints.py` 的台账相加对账。

### 4.6.2 Lance 表详情

点表名进 `LanceTableView`（路由 `/lance-tables/:prefix/:namespace/:table`），与
`TableView`（Paimon）、`IcebergTableView` 三页平行——不是同一页里按格式分支：三个协议面的
取数原语彼此不重叠（Paimon 挂在 `databases/**`、Iceberg 一次 `loadTable` 给整份 metadata、
Lance 是 `POST /lance/v1/table/{id}/describe` 且 `id` 是拼接串），合在一起每个页签都要写三层 `if`。

| 页签 | 数据来源 |
| --- | --- |
| 详细信息 | `POST /lance/v1/table/{id}/describe`（带 `check_declared=true`）：四张统计卡（数据集状态 / 版本 / 分片 / 删除行）+ id / location / namespace / 表名 / `managed_versioning` 的 KV + 属性表 + schema。「读取详细元数据」再发一次带 `load_detailed_metadata=true`，那要多读一遍 manifest，默认不发 |
| 版本 | `POST /lance/v1/table/{id}/version/list`（`descending=true`、`limit=100`）：版本 / 提交时间 / manifest 大小 / E-Tag / manifest 路径，单行进抽屉看整条原文。**只读**——写版本要走 `BatchCreateTableVersions`（多版本一次提交），换个一条一条写的形式就是半个事务，宁可没有入口 |
| 标签 | `POST /lance/v1/table/{id}/tags/list`：服务端回的是 **map**（名字 → `{branch, version, manifestSize}`），页面摊平成行再按名字排序；`manifestSize` 是 Lance 面里唯一一处**驼峰**字段。无目录 = 空 map，不是 404 |
| 文件列表 | 与 Iceberg 版同一套：**catalog 级**文件端点（根 = warehouse），初始路径由表 `location` 减去 warehouse 前缀算出，下载走 catalog 级单文件端点。`location` 不在 warehouse 下时给出去处而不是 404 |
| 血缘 | 与表格式无关：依赖由引擎上报、两种协议面的表在同一张图里，所以这一项与 Paimon / Iceberg 详情页共用 `LineagePanel` |
| 存储优化 | 挂 `TableOptimizationPanel`，显式传 `format="LANCE"`：只有 `OPTIMIZE`（合并碎片）与 `VACUUM`（删旧版本 + 回收无引用文件）两项，`orphan` 在这张格式上恒不可用（表单把卡置灰，不藏）。`VACUUM` 的两个常量锁死（`delete_unverified=FALSE`、`error_if_tagged_old_versions=TRUE`），`retention-hours` 还会被 catalog 级 `max-commit-interval-hours`（默认 24h）抬高。差异细节见 [lance-table-optimization-design.md](lance-table-optimization-design.md) |

三个边界：

- **表只有登记、没有数据集（`is_only_declared`）时 describe 回 code 19。** 这句翻成
  「先由客户端写入数据，详细元数据才有内容」当提示显示，不当错误弹——它不是读坏了，
  是这张表还没被写过。服务端 `check_declared=true` 的语义就是「别替我把这种表当空表」。
- **`id` 每一处都要整串。** `fullId = catalog$namespace$table`，URL 里 `$` 编成 `%24`；
  `namespace` 用的是**叶子名**（存储优化接口要 `lakehouse_database.name`），
  两者不能混——混了的表现是 404 说这张表不存在，而表明明在列表里。
- **没有「数据预览」页签。** 服务端 `TablePreviewFactory` 只有 Paimon 与 Iceberg 两个实现，
  Lance 面没有预览端点；放一个点了才 500 的页签属于给自己挖坑。

> 页签懒加载照搬了 `TableView` 的解法：`@tab-change` 在 `v-model` 更新后**同步**抛出，
> 此时存储优化面板的 `ref` 还没挂上。所以先记 `optimizationPending`，再用
> `watch(panelRef, ...)` 等组件到位；换表时若正停在这一页签也主动重取，否则会看到上一张表的策略。

### 4.7 Iceberg 表详情

表名可点（或点「详情」），进 `IcebergTableView`（路由 `/iceberg-tables/:prefix/:database/:table`）。
与 Paimon 版 `TableView` 平行而不是同一个页面里二选一：Paimon 版的七个页签挂在
`databases/**` 上，而这一页的数据几乎全部来自**一次** `loadTable`——
`LoadTableResult` 里的 `metadata` 是整份 `TableMetadata` JSON，按需加载的单位完全不同。

| 页签 | 数据来源 |
| --- | --- |
| 详细信息 | 当前快照 `summary` 的统计卡（记录数 / 文件数 / 大小）+ 元数据 KV + 当前 schema 字段表 + 当前分区 spec；还没有提交时显示 `current-snapshot-id = -1` 的「无快照」说明，而不是把 0 当统计 |
| 快照 | `metadata.snapshots[]`（按时间倒序）与 `metadata.refs`（Iceberg 把标签与分支合成一张 map，不像 Paimon 面拆两个页签）；快照详情抽屉给整行原文 |
| 文件列表 | **catalog 级**文件端点（根 = warehouse）：初始路径由 `metadata.location` 减去 warehouse 前缀算出，之后在表目录里下钻；下载走 catalog 级单文件端点。location 不在 warehouse 下（登记的外部表最常见）时给出去处而不是 404 |
| 存储优化 | **挂的是与 Paimon 表同一个面板**（`TableOptimizationPanel`，显式传 `format="ICEBERG"`）：三项动作与 Paimon 同名、按顺序执行，参数键与默认值按 Iceberg 的 procedure 走（`strategy` 多一个 `zorder`、`retain-last` 而非 `retain-min`、目标文件 512 MB）。策略存的是同一批表，执行历史与 `/optimization-jobs` 同一批批次。差异细节见 [table-optimization.md](table-optimization.md) §2.5 |

两个边界：

- **文件列表为什么走 catalog 级端点。** Paimon 面那条 `/api/console/v1/tables/{tableId}/files`
  要的是 Paimon 的内部表 id，而 `LoadTableResult` 里没有它，Iceberg 协议面也没有
  反查端点；catalog 级端点（根 = warehouse）不依赖 id，`location` 减去 warehouse
  就能得到初始路径，代价是要多取一次 catalog 信息（只在第一次切到文件页签时发生）。
- **快照没有「按版本号查单个」的表单。** Paimon 面有独立的 snapshot-by-version 端点，
  Iceberg 协议面没有对应物——列表就在 metadata 里，一次全给了。
  快照的回滚 / 过期也不在控制台做：协议面的更新提交（`POST .../tables/{table}`）
  是 metadata 代数的 `updates` / `requirements`，控制台拼不出一轮合法的提交（与 §7 同一理由）。

页头另有一个「权限」按钮，跳「权限管理」页并带上 `?catalog=<prefix>`。**表级授权不在这一页做**
（与 Paimon 表详情同一条约定，见 §4 的 `/tables/:prefix/:database/:table` 行）：
入口只负责把人送到唯一那个授权页，并选定正确的 catalog。

> **页签懒加载的一个老坑，这一页照搬了 `TableView` 的解法。** 存储优化面板的策略要按
> `catalog/database/table` 现取，因此必须在页签真正显示之后才加载；而 `@tab-change` 在
> `v-model` 更新后**同步**抛出，此时 `ref` 指向的面板组件还没挂载，直接调它的方法会静默无声。
> 所以先记一个 `optimizationPending`，再用 `watch(panelRef, ...)` 等组件到位；换表时若当前
> 正停在这个页签，也主动重取一次（否则会看到上一张表的策略）。

**命名空间平铺的代价**：`GET /namespaces` 只回**直接子级**，要平铺就得按层递归。
深度上限 4 层、条目上限 500 个（`MAX_NAMESPACE_DEPTH` / `MAX_NAMESPACE_COUNT`）——
护栏挡的是「某天有人建了几千个 namespace，打开页面发几千个请求」。
显示用点号拼接是**有损**的（层级名本身可以含点），发请求一律用 `0x1F`；
这个取舍与 §4.6 的来源见 `iceberg-rest-catalog-design.md` §4.6。

**多级命名空间必须逐级存在，控制台因此按 `mkdir -p` 建。** 实测：只建 `a.b`
而不建 `a` 时，请求会成功，但 `a.b` 在顶层列表里**永远不出现**（服务端按
「正好深一层」判定直接子级），本页的递归也走不到它——建成功了却看不见。
所以新建命名空间填 `a.b` 时，前端先建 `a` 再建 `a.b`，属性只落在最深一级；
中途某级已存在（409）按预期跳过。这个行为对客户端同样成立：用客户端建
多级命名空间而不建父级，同样不可见。

### 4.8 权限管理的两套候选来源

「权限管理」是同一页面对两种 catalog 格式，不是两个页面。差异只在**「库」那一级的
候选从哪来、叫什么**，其余（授权对象、权限词、有效期、清单与撤销）完全一致：

| | Paimon catalog | Iceberg catalog |
| --- | --- | --- |
| 那一级叫什么 | `Database` | **`Namespace`** |
| 候选来源 | `GET /v1/{prefix}/databases`（扁平，一次全给） | `listAllNamespaces`（`namespaces?parent=` 按层递归，深度 4 / 条数 500 上限） |
| 显示形式 | 库名原样 | 多级用点号拼接（`accounting.tax`） |
| 对象候选 | `GET .../databases/{db}/tables` / `.../views`，取名字数组 | `GET .../namespaces/{ns}/tables` / `.../views`，取 `identifiers[].name` |
| 空范围标签 | 「（整库）」 | 「（整 namespace）」 |

三条实现约定：

- **格式判断先补齐 catalog 列表。** `icebergCatalog = isIcebergCatalog(session.catalogByName(...))`，
  在这之前先 `ensureCatalogs()`。深链接（`/permissions?catalog=xxx`，Iceberg 表详情的
  「权限」按钮就是这么跳的）进来时 catalog 列表可能还没到，此时若「猜 Paimon」，
  就会拿 `databases/**` 去打一个 Iceberg catalog——一串 404，看着像故障而不像「格式不同」。
  判断不出来就**不发请求**，而不是发一个错的。
- **候选集合与浏览页是同一份。** `listAllNamespaces` 下沉到 `src/api/iceberg.js`，
  浏览页（§4.6）与权限页共用它和同一组上限。各写一份 BFS 迟早给出不同的集合，
  表现是「浏览页里看得见的 namespace，权限页授不了」。
- **提交值与显示值同一个字符串。** 表单里选的是 `accounting.tax`，发给服务端的也是它。
  Iceberg 的请求体里 namespace 是数组、路径里是 `%1F` 拼接，这两处编码都在 API 层做；
  若页面里先编一次、API 层再编一次，会得到 `accounting%1Ftax` 字面量这样的双级命名空间。
  服务端判定这一侧的比较口径见 [authorization.md](authorization.md) §2.6。

### 4.9 列表的定时刷新

`/catalogs` 与 `/optimization-jobs` 这两个列表都是「等状态变」的页面：前者的执行集群一列
（探活、保存都在别处发生，这张表是唯一能一眼看出「哪个 catalog 的优化不会跑」的地方），
后者的批次状态（提交中 → 运行中 → 部分成功，一个批次通常几分钟）。让人反复点「刷新」
比每 5 秒查一次贵得多，因此两页页头各有一个开关 + 节拍选择。

**默认开启、5 秒一次**，偏好按页存在 `localStorage`（`lakehouse.console.autoRefresh.<page>`），
两个页面互不影响——优化任务页需要更密的节拍时，不必连带把 catalogs 也调密。节拍只给
3 / 5 / 10 / 30 / 60 五档，**不给自由输入**：低于 3 秒只是把同一个查询连打几遍
（列表端点是分页查询，不是增量游标），高于 60 秒与手动刷新没有区别。

四条实现约定，每条都对应一种「不报错但把人带偏」的错法：

- **每一拍都走静默刷新（`silentLoad`），不是 `load` / `reload`。** 后者会置 `loading`，
  于是表格每 5 秒盖一次遮罩、`ResourceState` 也切到加载态——看起来像一直在加载。
  静默刷新保持当前页码（翻到第 3 页就还是第 3 页），只换数据。
- **静默失败不清空表格。** 失败时保留上一份数据，把原因放到控件上的一颗警示角标里
  （悬浮给完整原因）。反过来做（清空 + 红色错误面板）会让有数据的一屏每 5 秒变空一次，
  看起来像「数据被删了」。
- **后台标签页暂停，切回前台补一拍。** 页签开着过夜不该继续轮询；而切回来时若还等
  下一个整拍（最长 60 秒），这段时间页面看起来是实时的，其实是最旧的那一刻。
- **页面可以拦某一拍——但更省心的做法是不在同一个页面上。** 优化任务页在批次详情抽屉
  打开时不刷（抽屉里是打开时的快照，背后列表跳动会让人以为「点开的不是这一行」）。
  catalogs 原来也这么拦（新建 / 编辑弹窗打开时不刷），改成独立页面（§4.11）之后这条就不需要了：
  列表页整个卸载，定时器跟着清掉（第 23 组钉着「离开页面时清掉定时器」）。弹窗的代价正在这里——
  表格还挂在后台，行里的 `entityVersion` 会与表单里那份悄悄分叉，保存时得到一个
  莫名其妙的 409。展开中的批次明细**照常刷**（那才是「跑到哪张表了」的答案），
  同样走静默路径。

手动「刷新」与它并存：两者是同一件事的自动与手动两种模式，所以控件就摆在刷新按钮左边。
顶栏的全局刷新广播（`onConsoleRefresh`）仍走可见路径——那是用户明确按下去的。

### 4.10 「凭据来源」：五档标记

catalog 列表多一列、详情页多一段，说的都是同一件事：**引擎读这张表时拿到的钥匙从哪来**。

五档的定义与风险见 [catalog-storage-auth-design.md](catalog-storage-auth-design.md) §7；控制台这一侧的关键是
**「转发长期密钥」与「短期签发」在客户端配置上完全一样**（Spark / Flink 一行都不用改），
差别全在服务端发的是哪把钥匙。也就是说：配置看起来一样，安全性差一个量级，
唯一能区分它们的地方就是界面上的这个标记——因此它必须在列表页出现，不能只藏在详情的折叠区里。

v3 把 v2 的 `STS` / `RELAY` / `NONE` 收敛成五档，主要是把 `NONE` 拆开：
它原来把「本地文件」（正常状态，永远不会有密钥）和「一个密钥都没有」（问题，建于 v3 之前）
挤在同一档，而两者对用户的意思相反——前者什么都不用做，后者要补一种方式。

判定**只在服务端做**（`CatalogCredentialSource`）：判据是「凭据管理器走哪条分支」，
要读 catalog 自己的 `storageConfigInfo`（有没有 `roleArn` / 凭据 / `stsUnavailable`）
与 `lakehouse.rest.credential.providers`（本部署有没有启用签发）——后者前端拿不到；
把优先级抄进 JS 必然与 `DefaultStorageCredentialManager` 漂移，而漂移的表现是
「界面说在签发、实际在转发」。

| 档 | 界面标签 | 什么时候出现 |
| --- | --- | --- |
| `VENDED` | 短期签发 | S3 + `roleArn` + 签名 AK/SK + 启用了 `s3-token`；OSS + `roleArn` + AK/SK + 启用了 `oss-sts`；Azure + 服务主体 + `azure-sas`；GCS + `serviceAccountJson` + `gcs-downscope` |
| `STATIC` | 转发长期密钥 | catalog 上有密钥、但**没有**能接手的签发：没 `roleArn`、provider 未启用（含「填了 `roleArn` 但本部署没开 `s3-token`」——行为真的退回转发），或 `stsUnavailable=true` |
| `LOCAL` | 本地文件系统 | `FILE`：没有凭据这回事，服务端回一个自包含令牌。**它不是「缺配置」** |
| `LEGACY_ENV` | 无可用凭据 | 对象存储上一个密钥都没配（v3 之前的存量）、`stsUnavailable=true` 且无密钥、Azure 没有服务主体、GCS 没有服务账号且未启用签发。**只读状态**：编辑时选一种方式补上 |
| `INVALID` | 配置有误 | 声明了身份却缺签名材料：有 `roleArn` 却没 AK/SK（新配置在写入口就 400）、启用了 `azure-sas` 却缺服务主体、启用了 `gcs-downscope` 却没有 `serviceAccountJson`、没有 `storageConfigInfo` |

存量映射是**无损**的：`roleArn`+密钥且 provider 未启用 → `STATIC`（不是 `INVALID`），
`stsUnavailable`+密钥 → `STATIC`，`FILE` → `LOCAL`，一个密钥都没有 → `LEGACY_ENV`。
`LEGACY_ENV` 的编辑页有横幅、卡片不选中，且保存前必须跑一次自检。

五条实现约定：

- **它是推断，不是运行时事实。**「短期签发」只说明**会去签**，签不签得成（角色不存在、
  信任关系错）要到读表那一刻才知道。详情页把这句话写在问号里，不假装能预检。
- **批量端点**（`GET /api/console/v1/catalog-credential-sources`）一次回全部可见 catalog：
  列表页一屏几十行，逐行请求就是 N+1，而这件事本身不访问存储、不调云。
  可见性照抄管理 API（未开授权或服务管理员看全部，其余人只看自己持有一条赋权的 catalog）——
  否则它就成了「用分类信息探测 catalog 是否存在」的旁路。
- **判定失败只弱化这一列**，不让整个列表报错：catalog 列表本身是好的，缺的只是一个标注。
  表格里显示「—」而不是「不下发」——把「没问到」显示成「不下发」是在替服务端下一个它没下的结论。
- **详情页顺带说明取票要哪个权限点**（`USE_CREDENTIAL`，转发档还要 `USE_SECRET`）。
  升级到「两个权限点」之后最常踩的就是这一条，而报 403 的人第一眼看的正是这一页。
- **第 2 节先选「认证方式」，再填那条路需要的字段**（`utils/storageAuthMethods.js`）。
  方式卡片回答「用哪条路」，字段表按 `method` 过滤——切方式时上一种方式的字段会在下一次
  `build()` 里被丢掉，否则存下去的是一份「`roleArn` 还在、密钥倒换了」的配置，界面上看不出来。
  本部署没启用的方式**照常渲染但不可选**（灰底虚线 + 写明缺哪个 `credential.providers` 项）；
  本工程没接入的（OSS 的临时 AccessKey、OBS 的 IAM 委托）同样不可选，
  并写明缺什么。「环境身份 / Azure 托管标识 / GCS ADC」三种 v3 已删，**不出现在 DOM 里**；
  S3 的「临时安全凭证（Session Token）」也一并删掉——它不是没做，而是与「IAM 角色」重复，
  留一张永远不可选的卡片只会让人以为该去补 `sessionToken` 字段。
- **保存前自检**（`POST /api/console/v1/catalog-storage-probe`）回答的是另一个问题：
  上面的标签说「凭据从哪来」，自检说「这份凭据现在能不能用」。一个 `VENDED` 的 catalog
  同样可能因为角色信任关系写错而读不了。读档只发一次 `HEAD`；写档要 `PUT` 一个 0 字节对象
  到 `_lakehouse_probe/` 再删掉，**必须显式勾选**才发（默认等于「点一下就往别人桶里写文件」）。
  `FILE` 下两档都不适用并说明原因；每个主体每分钟限次；只回分类（OK / DENIED / NOT_FOUND /
  UNREACHABLE / UNSUPPORTED / CONFIG_ERROR）不回云侧原文。边界全部在服务端
  （`ConsoleStorageProbeService`）：`file://` 与无 scheme 的路径硬拒——`HEAD` 一个本地路径
  就是一个「本机文件存在性」预言机。**`CONFIG_ERROR` 与 `UNSUPPORTED` 必须分开**：后者是
  「本部署没有这种 scheme 的 FileIO」（去查 `lakehouse.rest.file-io.type` 与插件），前者是
  「服务端没装配起这份凭据」（去查配置）——合并成一档时，最常见的编辑态打码配置会被报成
  「这个存储不支持探测」，指向一个完全不相干的方向。编辑态请求要带 `catalog` 名，
  服务端据此把库中那份真凭据合并进来；不带名字又带 `******` 的草稿直接 400 并说明缺什么。
- **表单里有一份预览**（`POST /api/console/v1/catalog-credential-source`，紧跟在「存储类型」
  下面）：把这一份**还没保存的**配置发给服务端，回来的是与列表页同一个判定的
  `{ mode, detail }`。它治的是一个具体的坑——`roleArn` 填了、但本部署没启用 `s3-token` 时，
  结果与「什么都没填」完全一样（都退回环境凭据链），字段上看不出来，保存后要回列表页
  才看得见，而那时用户已经在另一步上了。
  三个细节：改用 POST 是因为要传一整份配置（它可能带打码的 `accessKeyId`，
  塞进查询串会进访问日志）；判定失败就把这一条**整根藏起来**，不保留上一次的结论
  （旧结论配上新表单是一句假话）；草稿还没有「允许位置」时不问——那时还谈不上保存，
  答出来的「不下发」只是一句与下一步无关的噪音。
  `utils/credentialSource.js` 只负责翻译，`composables/credentialPreview.js` 只负责去抖、
  发请求与丢弃过期响应，**两者都不许自己推断档位**（脚本第 24 组反向断言：
  不读判据字段、不出现档位字面量）。

**「作废已缓存的凭据」目前只有 HTTP 入口，控制台没有按钮**
（`POST /api/management/v1/catalogs/{catalogName}/credentials/revoke`，判 `CATALOG:ALTER`）。
不放进界面的理由不是省事，而是这个动作**没有界面能表达的确定性**：它清的是服务端缓存，
已经发到引擎手里的票会一直有效到自己的到期时刻（四家云都没有按 token 吊销的 API）。
一个按钮放在那里，点下去的人会以为「泄露已经被止住了」，而实际上只有「下一次签发用新配置」
这件事发生了。真要做一个入口，得连「当前有多少张票在外面、分别什么时候到期」一起做出来，
那是另一件事（需要记录每次下发，本文档不做）。

### 4.11 新建 / 编辑 catalog：为什么是一整页

这一页原来是一个 `el-dialog`。改成独立页面（`/catalogs/new`、
`/catalogs/:catalogName/edit`，共用 `CatalogFormView`）的理由只有一条，但足够：
**弹窗能装下一张表单，装不下一页的「后果」。** 这个对象上真正危险的字段只有一处
（凭据从哪来），而它的档位在客户端配置上有两档长得完全一样（§4.10）——说不清这件事，
表单填完也等于没填。页面能给的是一整节把它讲开（第 3 节）和一段可以直接复制给业务方的
接入片段（第 4 节）——这两样在弹窗里一滚就没了。

**「生效摘要」那一栏后来去掉了。** 它原先常驻右栏（协议面 / 存储 / 凭据来源 / 作用域 /
风险 / 长作业）。去掉的理由是它答的问题各自有更近的位置：判定回到第 3 节（那里本来
就写着「你现在在哪一档」）、续期回到第 4 节的片段下方（读它的人正对着配置）。
留在右栏的代价是整页被挤成两栏，而这一页的字段都不长，右栏一半是空白。

**页尾那张「将提交的报文」也去掉了。** 它常驻页尾占一整块，而它回答的问题（「我填的
会被翻译成什么」）对着表单本身读更快。去掉之后**提交体只剩一个来源**：`buildPayload()`
——保存走它，创建分支从它取 `storageConfigInfo`，页面上不再有第二处「拼一份给人看」的
地方。这条约定反而更容易被破坏（页面不再显示报文，谁也当场看不出另拼了一份），
脚本第 12 组钉着它。

**字段名与控件在同一行**（`label-position="right"` + `label-width="var(--pc-form-label-w)"`
+ 类 `pc-form-label-left`，全局样式在 `styles/index.css`）。理由是这个页面要「对照」而不是
「依次填」：第 2 节选了什么，第 3 节就落在哪一档，两节被拉出一屏之外就读不出因果。
标签在上时每个字段占两行，一屏只放得下五六个。**列宽只有一处定义**（那个 CSS 变量）：
模板传变量名而不是数值，`StorageConfigFields` 里跟字段对齐的说明行也只引它。
全站其它表单不跟着改——它们的标签普遍比控件还长，占一列会把控件挤到右边去。

四节的分工按「这一节错了，下一步会发生什么」排：

| 节 | 内容 | 错了会怎样 |
| --- | --- | --- |
| 1 基本信息 | 名称、表格式（按钮组）、类型 | 名称同时是客户端连接用的 prefix，创建后不能改；表格式服务端当不可变列 |
| 2 存储 | `StorageConfigFields`（存储类型也是按钮组；**先选认证方式，再出那条路需要的字段**；高级参数折叠） | 同时决定凭据发给哪一家云、用哪一族键名 |
| 3 凭据来源 | 五档状态 + 服务端判定的原文 + 允许位置 + 「要落到这一档还缺哪一项」+ 保存前自检（读/写两档） | 两档在客户端完全一样，界面不说清就只能靠猜（§4.10） |
| 4 引擎接入 | 五个面各一份片段 + 「长作业能不能续」，可复制 | 复制出去的东西错了，失败在业务方那边，控制台不报错 |

格式与存储类型用**按钮组**（`.pc-seg`，全局样式）而不是大卡片：卡片要留位置放说明与
路径，而三种格式之间的差别已经由下方那句 `catalogFormatNote` 承担（随选中项变），
七种存储之间的差别写在它们上方的说明里——离得越远越读不出对比。选中态是实心主色，
因为七个小按钮并排时只靠描边分不出选的是哪个。

存储类型显示的是中文名（`AWS S3` / `阿里云 OSS` / `Azure ADLS` / `本地文件`，见
`utils/storageType.js`），提交体里仍是枚举值；排列顺序取这张表的键序而不是 meta 的枚举
顺序——后者是定义顺序（`S3, GCS, AZURE, OBS, …`），照着排会让 GCS 夹在 S3 与 Azure 中间。

**「本部署未接入」的类型灰掉但不隐藏**：「能选 S3、不能选 OSS」会让人去查
`lakehouse.rest.file-io.type`，直接不显示只会像界面漏了一项。这条判据在改用按钮组之后
由 `isUnsupported()` 自己承担——它原先由 `el-option` 的 `disabled` 承担，Element Plus
替页面挡着；`<span>` 没有这个属性，不判就会让未接入的类型看起来可选，只在提交时被服务端
拒绝。脚本第 12 组钉着它。

三条实现约定：

- **状态只有一个来源：`form.storageConfigInfo`。** 存储表单是子组件，内部副本靠 props
  同步、靠 `update:modelValue` 回传编译后的提交体；保存与第 3 节的判定都直接读它，
  不另存一份「摘要用的状态」——两份迟早对不上，而对不上的表现是「存进去的不是刚填的」。
- **判定的东西一律问服务端。** 五档由 `POST /api/console/v1/catalog-credential-source` 答
  （§4.10），页面不认识 provider 优先级、也不推理会不会签发。**那张状态卡不是开关**：
  真正决定档位的字段在第 2 节里，「点一下就能落到这一档」在缺字段时落不下去——点了
  「签发」却没有 `roleArn`，判定仍是「不下发」，卡片当场又灭，看起来像坏了。因此把
  「你现在在哪一档」与「要落到这一档缺什么」分开写，缺的那一项给一个跳回第 2 节的入口。
- **创建态与编辑态的报文不同。** 更新**不带** `catalogFormat`（服务端改格式一律 400），
  创建**必须带**（不带的话服务端用部署默认值，会出现「表单里选 Paimon、建出来是 Iceberg」）。
  页面不再显示这份报文，但 `buildPayload()` 的两个分支照实分叉这件事仍然成立——
  它是唯一的提交体来源，`function buildPayload(` 在文件里也只允许出现一次。

引擎片段由 `utils/engineSnippet.js` 生成（纯函数，不碰 Vue，用例在 `tools/snippet-tests/`）。
它**按面各写一份，不做抽象**：`token.provider=bear` 不是 `bearer`、Iceberg 的 delegation 头
靠 `header.*` 注入、Flink 用 `CREATE CATALOG` 而 Spark 用 `spark.sql.catalog.*`、Lance 要多一个
`delimiter` 且基址写到 `/lance` 为止——抽象成「通用配置」的结果是每一面都跑不起来。
行是结构化的（`{kind, key, value, text}`）而不是裸字符串：页面要按 `key=value` 上色，而这个
拆分只该发生一次（在页面里 `split('=')` 会把 `s3.access-key` 这种带点的键和注释里的等号一起切坏）。
Lance 只有 Spark 一个页签：connector 没有 Flink 版，摆一个出来只会让人去找它。

**三个 Spark 面默认各带四行治理配置**（`spark.lakehouse.rest.lineage.enabled` /
`policy.enforced` / `management.url` / `token`）：这两件事默认不开，而且**是否生效只由客户端决定**——
服务端开着也没用，作业不带上这四行就什么都采不到，症状是「血缘图一直空、脱敏看着像没生效」，
治理页面上没有任何一处会说明原因。有四条约束：

- 键上**没有** catalog 前缀（session 级）。挂上前缀不报错也不生效：Spark 把它当成一个没人读的
  catalog 属性收下，配置看着完全正当。
- `management.url` 沿用片段自己的服务端地址并补 `/api/management/v1`。两处填成不同机器时
  catalog 能连、血缘全丢，报错只是一条 404。
- 片段**不自己补 `spark.sql.extensions`**（Lance 面除外，它本来就有一行）：这个键是逗号分隔的
  整串，多写一行等于覆盖用户已有的扩展。Paimon / Iceberg 两面只用注释提醒把
  `ManagementSparkExtensions` 合进自己那一串；Lance 面直接把两个扩展拼在同一个值里，
  片段里这个键因此只有一行。
- Flink 面**不给**这四行：`spark.lakehouse.rest.*` 由 Spark 侧扩展读，写进 `CREATE CATALOG`
  只是一条没人认的选项。

一个纯机器判据容易漏的地方：`/catalogs/new` 与 `/catalogs/:catalogName/edit` **必须排在
`/catalogs/:catalogName` 之前**。vue-router 按声明顺序匹配，顺序反了 `new` 会被当成一个
catalog 名，「新建」打开的是「叫 new 的那个 catalog 的详情页」（404），而菜单和按钮看起来
都是对的。第 12 组钉这一条，也钉「列表页里不再出现 `el-dialog`」——两套表单并存时改一处
忘一处，症状是「保存成功但那个值没变」。

---

## 5. 契约表与机械校验

### 5.1 为什么要有一张端点表

控制台是用字符串拼 URL 的，而服务端的路径属于**契约**（catalog API 见
`spec/rest-catalog-open-api-paimon.yaml`，管理面的官方部分是 Paimon REST Management API，
见 `spec/rest-management-open-api.yaml`；catalog / principal 等扩展端点的形状源自已移除的
Polaris 规格）。路径写错时前端的表现是
「404 空列表」，看不出是前端拼错了还是服务端改了——而这批路径有一百多条
（准确条数由脚本第 1 组打印，这里不写死：写死的数字只会悄悄过期）
靠人工比对不现实。

因此所有路径收在 `src/api/endpoints.js` 一张表里，视图层不手写 URL 字符串，
并交给 `scripts/verify-console.py` 与服务端规格逐条比对。

```js
// 形如
listDatabases: { method: 'GET', path: '/v1/{prefix}/databases' },
```

`{名字}` 占位由 `client.js` 的 `buildPath()` 替换并 `encodeURIComponent` 编码；
缺失参数直接抛错，不会拼出半个 URL。

查询串只有**一种**写法：`{ params: { query: {...} } }`——`buildPath` 只读
`params.query`，写在顶层键的 `query` 会被静默丢掉：请求照样发出去、服务端照样 200，
只是分页与过滤一个都没传过去，症状是「表格显示了全量」「点下一页内容不变」。
这类错编译期抓不到，因此 `request()` 见到顶层 `query` 直接抛错，第 19 组校验也逐字节
检查调用形状。（`management.js` 五个调用曾经全写在顶层：`catalog` / `principal` /
`principal role` 三个列表的远程分页与存储页的 `onlyPaimon` / 按库过滤都白写了。）

### 5.2 校验脚本

```bash
python3 scripts/verify-console.py      # 退出码 0 通过 / 1 不一致 / 2 环境不满足
PYTHON=/path/to/python3 scripts/verify-console.py   # 多解释器时指定装了 PyYAML 的那个
```

它**不构建前端、不启动服务端**，只做静态比对，因此可以放进提交前的检查。27 组 201 项：

| 组 | 校验内容 |
| --- | --- |
| 1. 端点存在 | 每条路径都能在规格或扩展白名单中找到；端点名不重复 |
| 2. 扩展白名单 | 声明为规格外扩展的端点确实被使用，且每条都写明了理由（无过期条目） |
| 3. 端点引用 | `src/` 里按名字调用的端点都在 `endpoints.js` 中声明 |
| 4. 无死条目 | `endpoints.js` 里声明的端点都在 `src/` 中被调用过 |
| 5. 基址一致 | vite `base`、路由 `base`、构建输出目录、服务端静态资源位置、client 默认基址五处指向同一处；令牌端点路径与服务端下发的 `tokenEndpoint` 一致；`buildPath` 拒收既非路径参数也非 `query` 的 `params` 键（静默丢弃会变成无参请求，服务端只回一个缺参 400） |
| 6. 产物完整 | 入口页已构建，且它引用的每个 assets 文件都存在；**产物确实不入库**（`static/console` 被 `.gitignore` 排除、`index.html` 没被跟踪）。后两条反过来挡「`git add -f` 把产物又塞回索引」——那会让 `.gitignore` 里那段说明重新变成空话，而症状只是多了一批文件 |
| 7. 产物新鲜 | 构建产物不比控制台源码旧（改了源码没重新构建会在这里暴露） |
| 8. 对话框状态 | 每个 `el-dialog` 都声明了 `destroy-on-close`（否则编辑时会残留上一次的状态） |
| 9. 下拉候选来源 | 每个 `el-select` 都声明了候选来源（`el-option` / `:options` 等）；确属自由输入的要在源码里写 `el-select-free-input` 标记显式豁免（否则「下拉是空的」这类 bug 只有打开页面才看得到） |
| 10. 静态凭据 | catalog 自带 AK/SK 这条链路横跨四个文件（服务端 DTO 的字段名、`supportsStaticCredentials` 认可的类型、meta 里的能力开关、前端表单），逐处对齐：字段名与三种类型的 record 参数逐字一致、类型集合两侧一致、**规格里确实没有这两个字段**（否则不再是扩展，要先对齐语义）、能力开关从 meta 一路传到表单 prop、能力关着时给的是可操作提示而不是把字段藏起来、读路径仍做脱敏 |
| 11. 优化参数键 | 存储优化各动作的表单字段名与服务端逐字一致：Paimon 那份对 `OptimizationOptions.KNOWN_KEYS`、Iceberg 那份对 `IcebergOptimizationOptions.KNOWN_KEYS`、Lance 那份对 `LanceOptimizationOptions.KNOWN_KEYS`，并断言三套键**两两不相等**、Lance 的 `orphan` 是空集（键名写错的表现是保存时吃一个 400，而编译、类型检查与单元测试都不会报；三套键混用的表现更糟——`retain-min` / `retain-last` / `target-rows-per-fragment` 被互相收下、然后静默不生效） |
| 12. catalog 表格式 | `PAIMON` / `ICEBERG` / `LANCE` 这条链路跨六处：服务端 `CatalogFormat` 枚举 ↔ 前端兜底取值逐字一致、meta 下发的候选与默认值都来自枚举本身、新建表单从 meta 取候选与默认值且提交体带 `catalogFormat`、**编辑不回传它**（格式不可变；判据读的是 `buildPayload()` 的两个分支，不是「调用点附近像不像」）、**新建 / 编辑是独立页面而不是列表页里的弹窗**（`/catalogs/new` 与 `/catalogs/:catalogName/edit` 必须排在 `/catalogs/:catalogName` 之前，否则 `new` 会被当成 catalog 名；`CatalogsView` 里不许再出现 `el-dialog`——两套表单并存时改一处忘一处，症状是「保存成功但那个值没变」）、列表有徽标与格式筛选（筛选与徽标用同一个归属函数）、三个页面按 catalog 格式伸缩**且在 loader 里就挡住请求**、详情页的能力清单读服务端 `/v1/config` 而不是前端抄一份、**权限页的候选来源按格式分派**（Iceberg 走 `namespaces/**` 并按层递归、Paimon 走 `databases/**`，选错来源的表现是空下拉而不是报错）、**两种格式的「配置」页签是同一套设计**（都编辑 `table-default.*`，标题按格式换名；Iceberg 另附只读的 `/v1/config` 的 `defaults` / `overrides`——那是连接参数，不是表属性；Paimon 不再重复列出概览已有的「其它属性」）。**Lance 另有三处独有**：详情页不发 `/v1/config`（规范里没有这个握手，发了只会拿到 404，而 404 会以「读不到」出现在概览上）、基址换成 `/lance/v1/namespace`、以及那一栏的三个数字（端点 / operation / 恒 406）与 `verify-lance-endpoints.py` 的台账**相加**对账（21 = 6+8+5+2，19 = 21 − 2 条兼容形态，35 = `NOT_IMPLEMENTED_OPERATIONS`）；浏览页对 Lance 挂独立组件 `LanceBrowseView`（`/lance/v1/namespace/{id}/list` + `/table/list` + `describe`，**写只有建命名空间**：端点表里只登记了 `lanceCreateNamespace`，表侧的 declare / register / deregister / rename / drop 一条都没有，组件也不引 `confirmDanger`——「有建、没有删」）、`describe` 带 `check_declared` 以回答「表到底建出来没有」；表详情页对 `lance-table` 藏掉「数据策略」（它的读取路径不经过策略注入层，那一页只能是「存得下、永不生效」），而**真正的 Lance 表走独立详情页 `LanceTableView`**（路由 `/lance-tables/:prefix/:namespace/:table`，整串 id 只在一处拼、共用优化面板时显式传 `format="LANCE"`、不给「数据预览」与「数据策略」两栏） |
| 13. Iceberg 浏览 | Iceberg catalog 的目录浏览走 `namespaces/**` 而不是 `databases/**`。这一组钉的是**契约形状**，不是像素：13 条 `iceberg*` 端点逐条对 Iceberg 规格（不靠第 1 组的并集蒙混）、浏览页只调 `icebergApi` 不混用 `catalogApi`、namespace 路径段用 `0x1F` 拼（点号只是显示口径）、分页键是 `pageSize` 而不是 `maxResults`、三个列表（表 / 视图 / 函数）都按 `identifiers` 取（逐表 `loadTable` 只许出现在表详情页）、建表体是 Iceberg 的 `schema` / `partition-spec`（`schema-id`、`required`、`source-id`、`field-id`）、浏览页与 API 层都不出现 `/databases`、浏览页不再提供删除命名空间的入口（按钮已移除）、两页共用的骨架样式留在全局；以及 **Iceberg 表详情页**（`IcebergTableView`）：有独立路由、只调 `icebergApi`、文件列表走 catalog 级文件端点（不用 Paimon 的 `tableId` 端点）、快照处理 `-1` 无快照哨兵、存储优化页签复用共用面板并显式传 `format="ICEBERG"`（页签自己调端点会绕过 format 分派）、建表表单的 `format-version` 提供 1/2/3 |
| 14. 存储字段分层 | catalog 表单的字段拆成「基本 / 高级」两张表，高级那张进默认收起的折叠区。判据对着**服务端 record**，不在前端另抄名单：两张表合并后与 record 的专属字段**不丢不多**、两表之间不重叠、`build()` 既**不读折叠状态**又仍读合并后的视图、`FILE` 没有高级字段、折叠区按「有没有高级字段」出现而非硬编码某类型、默认收起、收起态给出计数与「可以不管它」的说明 |
| 15. 事件页 | 事件页从「游标增量」改成「远程分页」之后，最容易退化的两件事要钉住：`listEvents` 端点路径与端点名一致、`EventsView` 真的接了 `useRemotePaging`（页码下推、`total` 取自 `page.total`）——游标只能往前看，页码模式才能往回翻历史；**事件类型也跟着下推**（类型只在前端筛时只筛得到当页，筛出空页会被读成「窗口里没有这种事件」）、事件类型清单逐个覆盖 `CatalogEventType` 的 wire 值、**类别清单逐个覆盖 `CatalogEventCategory` 的取值**、类别作为 `category` 参数随页码一起发出、目标类型显示名逐个覆盖 `CatalogEvent` 的 `TARGET_*` 常量（前端另抄名单漏了新类型的表现是那条事件显示成 wire 原文，静默）、`/events` 有独立路由并进侧边菜单 |
| 16. 提示图标 | 问号图标（`TipNote` / `FieldLabel`）必须**悬停即显示**：`trigger="hover"` 显式写明、`show-after` 为 0，且命中区比 14px 的图标本身大一圈。这条约束的起因是用户反馈「移到问号上没反应、点一下才弹」——`show-after` 的 100ms 让快速划过什么都不出现，而 14px 的命中区偏一两个像素就落在容器上（点击时整块区域都算命中，所以点击反而有效）。两个原因都不会让页面报错。**提示性告警（info / warning）的正文统一收进标题旁的问号**（`TipAlert`，16 个文件 28 处调用点）：复用 `TipNote`（不另写 el-tooltip）、每个调用点必须带标题、`type` 必须是字面量且不得是 error（错误必须直显）、带按钮 / 复制控件的正文留在可见区（气泡里点不到） |
| 17. 目录浏览的入口 | 两个浏览页（`BrowseView` / `IcebergBrowseView`）的三条工具条都写在 `ResourceState` **之外**：那个组件命中空态时只渲染 `el-empty`、默认插槽整个不渲染，「这个库还是空的」这一刻新建按钮会跟着表格一起消失（表页签当时靠页头那颗按钮躲过，代价是同一动作两个入口）。因此还断言：页头不再放「新建表」、`/browse` 不再有「语义视图」页签，以及 Iceberg 视图页签的创建入口与 `endpoints.js` 的 `icebergCreateView`、控制器上的 `@PostMapping` 三处对齐——缺一处按钮就是 404 |
| 18. Catalog 详情·存储 | catalog 详情的「存储」页：两种格式共用 `CatalogStoragePanel`（格式只作参数），数据源是管理面两个聚合端点 `/table-stats`（汇总：KPI/按库/Top10/直方图 SQL 一次算完，永远全量）与 `/table-stats/tables`（表明细远程分页，默认取全部表）——采集文件只许这两个调用，**不得残留 catalogApi / icebergApi 的逐表调用**（非快照管理的表尤其不该被问到快照端点）、「有数据的表」分子在前端算（服务端 `Totals` 没有 `withData` 字段，读不到就是「—」）、加权平均与小文件判据的口径钉在服务端聚合 SQL 里、面板不许直接调协议面或管理面（写动作在表页的「存储优化」）、「只统计 Paimon 表」以 onlyPaimon 参数在服务端过滤、明细分页/关键字/排序下推 SQL 且排序 `custom`（不得再挂「选库才请求」的门）、按库/Top10 两卡不吃堆叠边距（顶部对齐）。两个端点同时在扩展白名单里登记，控制台引用了规格之外又没登记的路径会当场报错 |
| 19. 表格远程分页 | 全站表格统一走「页码与过滤下推，页面只持有一页」。十条判据：`composables` 只导出 `useRemotePaging`（通用 `useLocalPaging` 已删——它的产物是「共 N 条 = 这一批的条数」）、11 个列表视图逐个接上它、分页器只有 `TablePager` 一个（各写一份 `el-pagination` 会漂出「这页能选 100、那页最大 50」）、16 处 `TablePager` 用法都带 `total` / `page` / `page-size`、12 个分页端点都接受 `page`（少了它前端翻页会被当成不分页，症状是「点了下一页，内容没变」）、存储明细按页码取数（不再在浏览器里编码 `pageToken`——那等于把服务端的私有 token 格式变成前端契约，服务端换编码后这一页会安静地永远停在第 1 页）、调用 API 时 `query` 写在 `params` 里（写在顶层会被静默丢弃——分页与过滤一个都传不过去）、全局样式不占用组件私有的 `pc-opt-*` 前缀（控制台的两处缩写撞过车：选择卡曾叫 `pc-opt`，与「存储优化」面板的根节点同名，整整一屏被套上选择卡的边框、内边距与 pointer 光标，hover 时边框还会变蓝；同族的 `pc-opt-dot` 更隐蔽——面板拿它当分隔符 `·`，全局却把它画成 9px 圆点。现在选择卡叫 `pc-pick*`）、带行内动作的表格把「操作」列钉在右侧（`fixed="right"`，展开箭头同理钉在左侧——列多时横向滚动是常态，操作列跟着滚走等于「滚到右边才找得到按钮」）、`total` 为 0 时不渲染分页器。**过滤必须一起下推**：本地筛只作用于当页，「筛出 3 条」与「库里有 3000 条只给了 200 条」在界面上长得一模一样 |
| 20. 数据预览 | 预览是控制台第一个**真的读行数据**的页面，它的错法都不报错、只把话说错：`TablePreviewPanel` 只许调 `previewApi` 两个方法（拼下载端点或直连协议面就绕过了 TABLE 级 `SELECT` 与采样限额）；采样提示必须在，且 `filesRead` / `filesInScope` / `filesInTable` 三个分母都在（只说「只读了 K 个文件」等于不说全表多大，只留一个分母就把「3/12（本分区）」说成「3/128（全表）」）；分区 chip 要有「自动」角标分支，且 `×` 的含义是**切到全部分区**而不是删掉自动条件；面板不许自己排序/取 max 算「最新分区」（两处实现迟早不一致，表现是「刷新一次分区变了」）；页签只对快照托管表渲染（Format / Object / Lance 表点进去只剩「不支持」）；六类失败各有各的文案（400/403/404/409/429/504——混成一句「加载失败」时用户只能刷新，而其中三类刷新没用）；「加载更多」只靠游标（`hasMore` 为假必须清空游标，否则再点一次会在同一段上重复读）；两条端点在服务端真的存在、且判权用 `Access.SELECT` 而不是 `DESCRIBE`（能看元数据不等于能看数据）；Iceberg 表详情页挂**同一个面板**而不是另写一个（它按名字加载表，因此先 `lookupTable` 换内部 id，换到才挂；换表与刷新时必须作废旧 id，否则拿旧表的 id 去读新表）；**`lookupTable` 的三个查询参数必须写进 `params.query`**（平铺在 `params` 顶层会被静默丢掉，数据预览固定回「Required parameter 'prefix' is not present」的 400） |
| 21. 血缘 | 表详情页的「血缘」页签：两个详情页都用异步组件挂 `LineagePanel`（同步 import 会让每张表的详情页为一块多数人不看的图付下载与解析成本）、页签名与正文条件一致、页签登记为「自带数据」（面板挂载即查，父组件替它发请求就是两次）、图与绘制层**不 import element-plus**（否则布局逻辑只能靠 Vue 运行时才测得到）、三个格式色只读 CSS 变量且变量定义在全局样式里（画布、图例、目录页徽标三处同源）、布局是纯函数（不读 DOM、无随机数与时钟——否则刷新一次位置全变）、LOD 阈值只有一处带滞回的实现（两处阈值不同会在临界点上闪）、**拖单张卡片只叠加视图位移**（偏移不进 `layout()`——写回布局就等于「同一份数据两次出不同坐标」，刷新一次位置全变；换根时整份清掉）、边按「颜色 + 线宽」分组后用 `Path2D` 批量描边（1500 条边逐条 stroke 会掉帧）、面板只经 `lineageApi` 取数、**控制台不声明血缘的写入口**（手工上报等于让人写一条假依赖）、列映射切到「列」视图才发第二次请求、节点超过 500 自动落邻接表且留「仍要看图」的出口、两个表格用 `el-table-v2`（虚拟滚动，也是 Canvas 的无障碍替代）、抽屉打开时画布让位、五个端点在服务端都有实现、图端点处理 `If-None-Match` 回 304（`no-cache` 而不是 `no-store`）、表被删除时级联清边（否则图上留下查不到也清不掉的节点） |
| 22. 执行集群 | catalog 级执行集群（优化任务提交到哪个 k8s、用什么身份）。这一组钉的是**多集群这件事的形状**，因为它的错法全都不报错：五个端点都必须挂在 `catalogs/{catalogName}/execution-cluster` 下（不引入一级资源——没有「列出全部执行集群」的需求，也没有「不属于任何 catalog 的执行集群」）、控制器类级路由与端点前缀逐字一致且六个动词映射齐全、三种集群类型（`SELF_HOSTED` / `EKS` / `AKS`）在服务端 `ClusterType` 与前端 `CLUSTER_TYPES` 两处取值完全相同、五种状态（`NOT_CONFIGURED` / `DISABLED` / `UNVERIFIED` / `HEALTHY` / `FAILING`）同样两处一致、**前端代码里不出现 `configHash` / `probeConfigHash`**（状态只由服务端算；两边各算一份的漂移表现是「列表说正常、点进去说待验证」，而那一屏最难解释）、`Catalog` DTO 带摘要且 `list()` 用批量 `summaries(...)` 而不是逐行 `summaryOf(...)`（列表是打开最频繁的页，N+1 在这里等于「catalog 越多越慢」）、列表有那一列并走 `clusterCell` 翻译、未配置的行按钮是主色（那一刻它是待办）、**读模型不含任何凭据明文**（`kubeconfig` 只回摘要、秘密只回 `has*`）、秘密字段有显式的 `clearCredentials` / `clearCatalogToken`（留空＝不改，清空要单独说）、`application.yml` 的 `optimization` 段只剩三个节拍键且 12 个提交参数一个不剩、`RestServerProperties.Optimization` 与之对齐（就三个字段）、fail-closed 的两个落点都在（保存策略与「立即优化」各一次 `assertDispatchable`）、出批侧在造批次之前判「可派发」、旧的两种提交器已删除、fabric8 客户端禁用 `autoConfigure` 回落并断言 master URL（缺了它，填错 apiServer 的 catalog 会静默把 Job 提交到服务端自己所在的集群）、`sql/schema-mysql.sql` 里真有这张表（实体加了没重新生成 DDL，部署时会因 `ddl-auto=validate` 起不来） |
| 23. 定时刷新 | Catalog 与优化任务列表的定时刷新（§4.9）。这一组钉的是它的**静默性**与**唯一实现**，因为错法全都不报错：默认值（开启 + 5 秒）只在 `utils/autoRefresh.js` 定义一次、节拍档位只有一份且控件按它 `v-for` 渲染、两个列表页都真的接了 `useAutoRefresh` 且偏好键不重名、**每一拍只走 `silentLoad`**（定时器实参块里不出现 `load()` / `reload()`——它们会置 `loading`，症状是表格每 5 秒闪一次遮罩）、静默失败时保留上一份数据（`rows.value = []` 在整个 composables 里只能出现在非静默分支一次）、失败原因有地方显示、后台标签页跳过并在切回时补一拍、上一拍没回来不发下一拍、离开页面清掉定时器、优化任务页连展开中的批次明细一起刷 |
| 24. 凭据来源 | catalog 列表与详情页的「凭据来源」（五档：短期签发 / 转发长期密钥 / 本地文件系统 / 无可用凭据 / 配置有误）。这一组钉的是**分层**而不是像素：判定只有服务端一份（`CatalogCredentialSource`，判据是 `DefaultStorageCredentialManager` 走哪条分支，要读 `lakehouse.rest.storage.*` 与 `credential.providers`）、四档取值与控制台的词表逐字对应、**翻译层与方式表里不读判据字段**（`roleArn` / `stsUnavailable` / `providers` 一旦出现在「可用性判定」里，就说明有人开始在前端算来源——那份逻辑会与凭据管理器漂移，症状是「列表说在签发、实际在转发」）、列表页与详情页共用同一份翻译且判定失败不影响表格本身（catalog 列表是好的，缺的只是一个标注） |
| 25. 登出 | 「退出登录」什么时候要离开本应用去 IdP 结束 SSO 会话（RP-initiated logout）。三条判据都不报错：必须只在 **IdP 发过票** 时跳（否则把口令登录的人扔到别人的登录页上，本机令牌却已清）、地址只能来自服务端下发的 `end_session_endpoint`（`issuer + "/logout"` 在 Keycloak 上碰巧对，在 Auth0 / Azure 上是另一条路径，拼错就是一个 404 页面）、且必须在清令牌**之前**算（它要读令牌与来源，顺序反了就永远算不出地址，现象是登出悄悄退回「只清本机」） |
| 26. 引擎片段 | catalog 详情页「引擎接入配置」那几行**复制出去**的配置里的续期契约。四条判据，错法全都不报错（片段照常显示、照常复制成功，只是粘进引擎后行为不对，而且要跑满一个令牌 TTL 才现形）：Paimon 两面的 `token.provider` **默认仍是 `bear`**（默认 `oauth2` 会让还没有 provider jar 的部署在建 catalog 时直接失败，报的是「找不到 factory」）、Iceberg 两面的 `scope` 与服务端 `ClientCredentialsService.SCOPE_ALL` **逐字相同**（脚本直接读 Java 常量，不抄第二份——抄了就会漂，而漂的表现是「配置永远 400 invalid_scope」，且发生在第一次刷新之后）、`RENEWAL` 分**控制面 / 数据面**两列（合成一列时「数据票能续」会被读成「主体令牌也能续」，而后者才是长作业真正挂掉的那条）、`oauth2.credential` 只以注释出现且片段里只有占位符（`<主体令牌>` / `<AK>` / `<SK>`）；另加一条接线判据：详情页真的把 `renewable` 传给了片段生成器，且开关只为 Paimon 面显示 |
| 27. 文档计数 | 本节这张表上方的「N 组 M 项」与脚本实际一致（`docs/console.md` 与 `README.md` 各一条）。期望值由脚本自身推出——组数是源码里分组标题的条数，项数是本次校验的总条数——因此加/删校验项时这里不用改，但**文档必须跟着改** |

第 7 组是这套机制里最实用的一条：**产物过期不会让任何东西报错**，
它只是安静地少一个按钮。

第 12 组里有两处判据值得说明，它们都不是「源码里有没有这个字符串」那么直白：

- **按格式伸缩那一项断言的是「loader 里挡了请求」，不是「模板里有 `v-if`」。**
  只在模板上藏内容，请求照样发得出去——对一个 Iceberg catalog 打 Paimon 的
  `/databases/**`，页面上会留下一串 404，看起来像故障。
  目录浏览这一条在 Iceberg / Lance 浏览落地后变成两半：**换页那一半**断言
  `<IcebergBrowseView v-if="icebergCatalog" />` 与
  `<LanceBrowseView v-else-if="lanceCatalog" />` 真的在、并且在 `v-else` 的 `.pc-page`
  **之前**（见 §4.6 末尾：嵌套会让 padding 叠加两遍），**挡请求那一半**
  断言 Paimon 的五个 loader 仍保留守卫——`watch`、刷新与「重试」都会绕过模板
  直接调 `load()`，只换页不挡请求仍然会发出去。
- **能力清单那一项断言「读 `/v1/config`」并且「源码里没有端点字面量」。**
  Iceberg 的 `endpoints` 是服务端**下发**给客户端的原文（客户端拿它做等值比较），
  前端自己抄一份，抄错了会显示一个「看上去对、实际不生效」的能力表，
  而这一块的全部价值就是排障时不用去读文档。

编写这类校验时值得注意：**要能证明它抓得住错误**。把 `addColumn` 的 `fieldNames`
改成单数、或把某个动作名改掉，脚本应当报出具体的字段/动作名而不是通过——空跑的校验
比没有校验更糟。

第 19 组就是这么来的：**「加了校验组要顺手改文档」是一条靠人记的约定，而它真的漏过一次**
——`docs/console.md` 写 37 项、`README.md` 写 38 项、脚本实际 38，两个数字都没人对账，
直到有人去数。现在这条约定变成了一条断言。它的盲区是拦不住「连第 19 组一起删掉」，
因此文档里的组数仍值得在 review 时扫一眼——**没有哪个自校验能覆盖它自己**。

唯一允许出现在规格之外的端点是 `EXTENSION_ENDPOINTS` 里的：

```python
EXTENSION_ENDPOINTS = {
    ("GET", "/api/console/v1/meta"): "控制台元数据（枚举取值与服务配置摘要），非 Polaris 规格",
    # 仓库文件浏览：列 catalog 仓库里某个目录的一层内容（`?path=` 相对仓库根）。
    # 它读的是物理目录（表目录下有哪些文件、有没有残留），三份规格都不涉及文件系统
    ("GET", "/api/console/v1/catalogs/{catalogName}/files"): "控制台仓库文件浏览（列一层目录，只读，可配置关闭）",
    ("GET", "/api/console/v1/auth"): "控制台登录引导（可用认证方式、令牌端点、当前令牌状态）",
    ("POST", "/api/console/v1/login"): "控制台用户名密码登录（默认方式，响应是驼峰字段而非 OAuth 形状）",
    ("POST", "/api/catalog/v1/oauth/tokens"): "OAuth 2.0 客户端凭据令牌端点（RFC 6749 第 4.4 节）",
    # Paimon 官方 REST Management API 的权限端点（principal 直授）。
    # wire 契约在官方 rest-management-open-api.yaml（副本 spec/ 下）
    ("GET", "/v1/{prefix}/permissions"): "Paimon 官方权限端点：列出 principal 直授",
    ("POST", "/v1/{prefix}/permissions/grant"): "Paimon 官方权限端点：授予 principal 直授",
    ("POST", "/v1/{prefix}/permissions/revoke"): "Paimon 官方权限端点：撤销 principal 直授（幂等）",
}
```

每加一条都要写理由，且必须在控制台里真的被调用，否则脚本报「过期条目」。

`verify-console.py` 是**静态**的：它证明前端写下的路径与规格一致，证明不了登录链路真的通。
登录另有两层对着**运行中的实例**跑的验收（都需要以 `--lakehouse.rest.auth.enabled=true` 启动）：

```bash
# HTTP 层：真实报文、错误码、限速、用户名密码登录、静态令牌、SPA 深链回退——63 项
BASE=http://127.0.0.1:8080 CLIENT_ID=<clientId> CLIENT_SECRET=<secret> \
  ./scripts/sweep-console-auth.sh

# 前端集成：跑 src/store/auth.js 与 src/api/* 的真实代码——59 项
BASE=http://127.0.0.1:8080 CLIENT_ID=<clientId> CLIENT_SECRET=<secret> \
  npm run check:login     # 在 lakehouse-rest-console/ 下
```

（`CLIENT_ID` / `CLIENT_SECRET` 只在开了授权时才会由启动日志自动给出。
没开授权时先用默认账号密码登录、再调管理 API 建一个主体，命令见
[`console-auth.md` §11](console-auth.md#11-控制台侧的实现位置)。）

`check:login` 用 vite 的 `ssr` 构建把前端模块**原样**打成 Node 可执行产物（不是另写一份仿真），
装好 `localStorage` 替身并包住 `fetch`，因此能断言「前端实际发出的请求长什么样」——
字段名是 `client_id` 还是 `clientId`、体是表单还是 JSON、令牌写到哪个 key、
失败有没有回滚。这一层抓到过一个真实的 bug（登录失败时把正在用的有效令牌一起弄丢了），
而它在服务端测试与静态校验里全绿。用法见 README 第 7 节「端到端验收脚本」。

### 5.3 加一个页面的完整清单

1. `src/api/endpoints.js`：加端点条目（路径必须能在规格里查到，否则进白名单并写理由）。
2. `src/api/catalog.js` 或 `management.js`：加调用函数。
3. `src/views/`：加视图；删改功能要接 `confirmDanger()`，错误走 `notifyError()`。
   说明文字**不要**直接铺在页面上（表单页会被说明文字占满）：字段备注用
   `FieldLabel` 收进字段名后的问号图标，区块级的口径与边界用 `TipNote` 收进
   问号图标——两者悬停显示，短文本传 `text` 属性，带 `code` / 分段的富文本用
   默认插槽（见两个组件的文档注释）。表单分组标题用 `SectionTitle`
   （强调条 + 渐隐细线），不要用 `el-divider content-position="left"`——
   它的分组标题又淡又小，看上去不像分组倒像掉了一半的字。
4. `src/router/index.js`：加路由；想出现在侧边栏就写 `meta: { nav: true, icon, group }`。
   不需要令牌就能看的页面（登录页、SSO 回调）写 `meta: { public: true }`——
   它同时决定「不套管理布局」（`App.vue`）与「守卫不拦」（见 §6.2）。
5. `./scripts/build-console.sh`：重新构建，第 3、4、6、7 组校验会在这里把关。

---

## 6. 鉴权、登录与连接设置

有两个开关，管的是两件不同的事，**不要混为一谈**：

| 配置项 | 默认 | 管什么 |
| --- | --- | --- |
| `lakehouse.rest.auth.console.required` | `true` | 浏览器打开控制台要不要先登录；只作用于控制台界面与 `/api/console/v1/**` |
| `lakehouse.rest.auth.enabled` | `false` | `/v1/**`、`/api/catalog/v1/**`、`/api/management/v1/**` 要不要带令牌 |

**默认部署的真实状态**：浏览器被拦在登录页，但数据接口匿名可调
（`curl /v1/config` 返回 200）。这层门禁解决的是「误入的人看到一堆管理表单」，
**不是安全边界**——要保护数据必须把 `auth.enabled` 设为 `true`。
服务端在「门禁开着而 `auth.enabled=false`」时会在启动日志里告警，登录页与设置页也都会写明。

`auth.enabled=true` 后，上述前缀全部要求 `Authorization: Bearer <token>`。
**豁免只有三条**，都是精确路径：`GET /api/console/v1/auth`（登录引导）、
`POST /api/console/v1/login`（用户名密码换令牌）与
`POST /api/catalog/v1/oauth/tokens`（客户端凭据换令牌）——它们要回答的正是「怎么登录」，
放在鉴权之后会形成死循环。

浏览器登录有三种方式，可同时启用（完整设计见
[`console-auth.md`](console-auth.md)）：

| 方式 | 凭据 | 令牌由谁签发 | 默认 |
| --- | --- | --- | --- |
| 用户名 + 密码 | 服务端配置里的账号，默认 `admin/admin` | 本服务端（HS256） | **开启** |
| OAuth 2.0 客户端凭据 | 主体的 `client_id` / `client_secret` | 本服务端（HS256） | 开启 |
| OIDC 授权码 + PKCE | 外部 IdP（Keycloak / Auth0 / Okta…） | IdP（RS256 / ES256） | 关闭 |

用户名密码排在登录页第一个页签：**它不需要先建主体**，是「装好就能进控制台」的那条路。
账号只是「开门的钥匙」，不进授权链路——默认主体名与账号同名，因此
`admin` 这个账号登录后能不能看到东西，取决于它有没有被授予角色（启动日志会就这种
「登录成功但什么也看不到」告警）。

三者产出的都是一个 Bearer 令牌，此后走同一条通道。第四个入口是
`lakehouse.rest.auth.tokens` 里的静态令牌（给机器用，**配置了才出现**，排在最后一页签）。

**登录页不判断有哪些登录方式。** 支持哪种、令牌端点在哪儿、控制台要不要登录，
全部由 `GET /api/console/v1/auth` 在运行时下发——控制台是构建期打包的静态资源，
读不到服务端配置，硬编码一份必然漂移，而漂移的表现是「点了登录按钮没反应」。

令牌存在本机 `localStorage`：

| localStorage 键 | 含义 |
| --- | --- |
| `lakehouse.console.token` | 访问令牌（登录页或设置页写入的**同一个**位置） |
| `lakehouse.console.baseUrl` | API 基址，默认空串（同源） |
| `lakehouse.console.catalog` | 当前选中的 catalog prefix |
| `lakehouse.console.theme` | `light` / `dark` |

键前缀与服务端配置前缀同一个词根（`lakehouse.console.*`）。**不做旧键兼容读取**——
静默兼容会把两套前缀长期留在代码里；旧键一律视为不存在（读到空令牌就跳登录页，
不会「已登录却处处 401」）。

OIDC 登录期间还会在 `sessionStorage` 里放三个短命键
（`lakehouse.console.oidc.verifier` / `.state` / `.returnTo`），它们是 PKCE 的中间状态，
**用完即弃**，成功后立刻清掉。放 sessionStorage 而不是 localStorage 有两个原因：
它只在一次往返里有意义；两个标签页同时登录时不会互相覆盖。

请求头由 `client.js` 统一附加，视图层不碰。令牌失效（401）时的提示语是明确的
「未通过鉴权：请在『连接设置』里填写访问令牌」，而不是干巴巴的 HTTP 状态码。

`baseUrl` 默认空串即同源——生产构建后控制台与服务端同端口，不填就是对的；
只有把控制台部署到别处（例如独立的静态站点托管）时才需要覆盖。

### 6.1 登出：清本机令牌，SSO 时连 IdP 的会话一起结束

服务端**没有会话表**：令牌是自包含的 JWT，签出去之后在
`lakehouse.rest.auth.access-token.ttl`（默认 1 小时）内一直有效，没有可以作废它的接口。
因此 `auth.logout()` 不通知服务端，只清掉本机那份令牌。

**用 SSO 登录时还有第二层要结束。** 浏览器与 IdP 之间那份会话不归本机管：清了令牌之后
那个人在浏览器里仍是登录状态，换个人点「登录」会直接进来，中间没有一次密码。
因此 `auth.logout()` 会先把一件东西算出来——**去 IdP 登出的地址**——再清令牌，然后把地址
交给调用方整页跳转（`window.location.assign`）：

| 情况 | 登出后浏览器去哪儿 |
| --- | --- |
| 令牌是 IdP 发的（`session.source === 'oidc'`）且发现文档给了 `end_session_endpoint` | IdP 的登出端点；配了 `oidc.post-logout-redirect-uri` 才会自动回到 `/console/login`，否则停在 IdP 自己的「已退出」页 |
| 其余情况（口令 / 客户端凭据 / 静态令牌，或 IdP 没有那个端点） | 停在控制台，按服务端是否要求登录决定跳不跳登录页 |

三个判据都不能松：**只在 IdP 发过票时跳**（否则把口令登录的人扔到一个与他无关的
IdP 页面上，而本机令牌已经清了）、**地址只来自服务端下发的 `end_session_endpoint`**
（`issuer + "/logout"` 在 Keycloak 上碰巧对，在 Auth0 / Azure 上是另一条路径）、
**地址在清令牌之前算**（它要读令牌本身与来源，顺序反了就永远算不出，现象是登出悄悄
退回「只清本机」）。三条都由 `verify-console.py` 第 25 组钉住，前端纯函数
`logoutRedirectUrl` 另有一组 node 用例（`tools/oidc-tests/`）。

这一点在界面上如实说明（顶栏按钮的提示会随来源变化、设置页的说明文字），
否则会给人「已经把自己踢下线了」的错觉。

### 6.2 路由守卫

守卫按「控制台要不要登录」+「手上的令牌算不算数」决定拦不拦，
依据是 `/api/console/v1/auth` 的 `authEnabled`、`consoleRequired` 与
`session.authenticated`。判据只有一个出处——`auth.loginRequired()`：
`authEnabled || consoleRequired`，与服务端 `Auth.Console#loginRequired` 是同一个式子
（写两处迟早漂移，而漂移的表现是「守卫放行了、页面却全是 401」）。

- **门禁单开时也拦。** `consoleRequired=true` 而 `authEnabled=false` 时，
  浏览器照样要先登录——这正是本次改动的目标。
- **问不到服务端时放行**，不把人困在登录页。连不上时该看到的是各页面自己的错误提示
  （带 HTTP 状态与原因），而不是一句「请先登录」——后者会把「服务端没起来」
  误导成「凭据不对」。
- **令牌过期先清掉再判断**（`auth.dropExpiredToken()`）。带着一个必然 401 的令牌继续走，
  每个页面都会显示「未通过鉴权」，看起来像权限不足而不是需要重新登录。
- `redirect` 查询参数**只接受站内路径**（以单个 `/` 开头）。它会被原样交给
  `router.replace`，放行绝对 URL 等于开放重定向。

### 6.3 凭证只在本机

控制台不代持任何凭据，也不请求服务端把令牌持久化；OIDC 用公开客户端 + PKCE，
前端构建产物里没有任何密钥。

---

## 7. 端点的取舍：为什么没有 `commitTable`

`POST /v1/{prefix}/databases/{database}/tables/{table}/commit` 在规格里存在，
但 `endpoints.js` **有意不包含它**：

它是写入方（Spark / Flink 的 writer）提交快照的接口，请求体里要带 manifest list
等**由写入方在本地生成的文件路径**。控制台拼不出一个真实的快照——
提供一个这样的表单，只会让人提交出一张指向不存在文件的表，
而且这张表的元数据是「合法」的，坏在数据侧，事后极难定位。

与之相对，`rollbackTable` 被实现了：它以**已存在的** instant 为目标，
参数是快照 ID 或标签名，控制台可以从列表里选，不存在凭空捏造的问题。
回滚是不可逆操作，它的确认框是强警告形式。

同一原则适用于别处：**控制台只做它能凭已有数据做对的事**。

---

## 8. 构建与提交约定

### 8.1 产物落位

`vite.config.js` 把 `outDir` 直接指向服务端：

```js
build: {
  outDir: '../lakehouse-rest-spring-parent/lakehouse-rest-server/src/main/resources/static/console',
  emptyOutDir: true,
}
```

这样 `Dockerfile` 只装构建好的 jar（连 Maven 都不跑，更不涉及 Node），Java 侧构建路径上
完全没有 Node。但**产物本身不入库**，所以 `mvn package` 之前必须先跑
`./scripts/build-console.sh`——Maven 不会替你构建前端。

### 8.2 产物不入库，但必须被构建出来

`lakehouse-rest-console/.gitignore` 忽略的是 `node_modules/`、`dist/`、`.vite/`；
真正的产物落在服务端 `src/main/resources/static/console` 下，由仓库根的 `.gitignore` 排除。
**它是构建产物，不进版本库**：

- 产物文件名带内容哈希，一次前端改动就是 80 多个文件的增删——进库之后每次 review 都是
  一屏 diff 噪音，真正改动的那几个 `.vue` 反而被淹没；
- 半个产物比没有产物更难查：`index.html` 提交了、assets 漏了，页面白屏而
  `git status` 一片干净，只有新克隆的仓库跑起来才发现。

代价是「改了前端」与「打了个能用的 jar」之间隔着一道手工步骤。所以这道步骤有三层守卫，
都在**产物缺失或半套**时明确失败，而不是让 jar 安静地少一个 `/console`：

| 层 | 时机 | 失败信息 |
| --- | --- | --- |
| `verify-console.py` 第 6 组 | 构建前，静态检查 | 入口页不存在 → 提示先跑 `build-console.sh`；入口页引用的 assets 缺失 → 逐个列出 |
| `build-image.sh` | 出镜像前 | `unzip -l` 查 jar 里有没有 `static/console/index.html`，没有就拒绝构建镜像 |
| `ConsoleApiTests` | 测试时 | classpath 上读不到入口页 → 提示「产物不入库，先跑 `build-console.sh`」 |

**这件事在两个方向上都翻过车，值得记住**：最初 `.gitignore` 里有一行 `static/console`，
而本文 §1、`README.md`、`server/pom.xml` 三处都写着「已提交」——文件都在磁盘上、
索引里一个没有，`git status` 一片干净，直到有人从干净检出构建才发现 jar 里没有 `/console`。
当时的修法是改成入库，第 6 组两条断言查「目录没被忽略 + 入口页已被跟踪」。
2026-10-30 又改回不入库（diff 噪音与半套产物的代价更实在），第 6 组随之反转成
「目录确实被忽略 + 入口页确实没被跟踪」。

两次反转里不变的是那条判据：**文档写的状态必须与 `git` 的实际状态一致**，
机械检查只是把这句话变成能失败的断言。所以别用 `git add -f` 把产物塞回索引——
那会让第 6 组第二条立刻失败，而这正是它存在的意义。

`.dockerignore` 排除了 `lakehouse-rest-console/node_modules/`（几十万个文件，
纯粹的上下文体积），保留源码——镜像不需要它，但留着便于在容器内排查。

### 8.3 增量构建的一个坑：target/classes 会累积旧产物

控制台的产物按内容哈希命名（`index-CG9zHvOr.js`），每次前端重建文件名都会变；
而 `maven-resources-plugin` 只做「复制 / 覆盖」，**从不删除** `target` 里已经不存在于
`src` 的文件。于是增量执行 `mvn package` 时，`target/classes/static/console` 会累积
历次构建的旧文件——实测一次增量构建就多出 20 个死文件（含上一版 1.1 MB 的 index chunk），
打进 jar 里就是白白胖胖的几 MB，而且看 jar 内容会误以为有两个入口 chunk。

这条守卫在 POM 里，因此与「谁」执行构建无关——`Dockerfile` 现在直接装你构建出的
那个 jar，不再自己跑 Maven。受影响的是**本地改完控制台后只跑 `package`** 的场景，
而那恰好是最常见的场景。因此在 `lakehouse-rest-spring-parent/lakehouse-rest-server/pom.xml` 里
把 `maven-clean-plugin` 的一个执行绑到了 `initialize`：

```xml
<execution>
  <id>clean-derived-console-assets</id>
  <phase>initialize</phase>
  <goals><goal>clean</goal></goals>
  <configuration>
    <excludeDefaultDirectories>true</excludeDefaultDirectories>
    <filesets>
      <fileset><directory>${project.build.outputDirectory}/static/console</directory></fileset>
    </filesets>
  </configuration>
</execution>
```

早于 `process-resources`，所以随后的复制是干净的一次；
`excludeDefaultDirectories` 置真以避免误删整个 `target`（那会把增量构建变成全量构建）。

判断它是否生效：

```bash
ls lakehouse-rest-spring-parent/lakehouse-rest-server/target/classes/static/console/assets | wc -l   # 应与源码目录一致
```

### 8.4 产物体积

主 chunk 约 1.1 MB（gzip 368 KB），其中绝大部分是 Element Plus 的全量组件与图标。
控制台是内网管理界面，这个体积可以接受；真要做按需引入，
改的是 `src/main.js` 的图标注册与 `vite.config.js` 的 `manualChunks`，
不影响上述任何约定。

---

## 9. 测试

`ConsoleApiTests`（14 个用例）覆盖服务端侧的行为，重点是**托管机制**而非页面渲染
（页面渲染属于前端职责，不适合放在服务端测试里）：

| 用例 | 断言 |
| --- | --- |
| `metaExposesEnumsUsedByConsoleForms` | meta 返回了表单需要的全部枚举组 |
| `metaAccessesFollowOfficialMatrix` | 权限分组的取值与管理规格的权限矩阵一致 |
| `authAsksForALoginEvenWhenTheDataPlaneIsOpen` | 默认配置下 `authEnabled=false` 但 `consoleRequired=true`，且 `methods` 以 `password` 开头——两个字段必须同时如实 |
| `authReportsTheSessionWhenATokenIsPresent` | 同一个端点带上令牌时只多出「你是谁」，不会被 401 挡掉 |
| `consoleEndpointsRejectAnonymousRequestsWhileTheGateIsOn` | 门禁开着时 `/api/console/v1/**` 拒匿名，而 `/v1/**` 仍匿名可调（门禁不是安全边界） |
| `authAdvertisesThePolarisCompatibleTokenEndpoint` | 引导下发的令牌端点就是 Polaris 那条路径 |
| `credentialSourcesCoverEveryVisibleCatalog` | 批量端点对**每个可见 catalog** 都给出一条判定（漏可见性等于用分类信息探测 catalog 是否存在） |
| `credentialSourcePreviewJudgesTheDraftBeforeItIsSaved` | 草稿判定走的是同一个 `CatalogCredentialSource.of`：还没保存的配置也能答出档位（§4.10） |
| `consoleRootRedirectsToTrailingSlash` | `/console` → 302 |
| `consoleDirectoryUrlForwardsToEntryPage` | `/console/` → 200 入口页 |
| `consoleEntryIsServedAtItsRealUrl` | `/console/index.html` → 200 |
| `clientSideRoutesFallBackToEntryPage` | 深链回到入口页 |
| `missingAssetStillReturns404` | 缺失 assets 仍 404（不回退） |
| `consoleHtmlReferencesExistingAssets` | 当前构建的入口页引用的产物都存在（半套产物的表现是白屏） |

登录链路本身另有 `ConsoleAuthEndpointTests`（31 个用例，错误码、接线、门禁范围、
默认口令与限速）与两个运行期验收脚本，见 §5.2。

仓库文件浏览（`/api/console/v1/catalogs/{catalogName}/files`）另有两类，**分开写**：

| 测试类 | 用例 | 管什么 |
| --- | --- | --- |
| `CatalogFileBrowserTests` | 21 | 服务语义：下钻、目录优先排序、路径归一化与 `..` 拒绝、404/400/501 的判据、截断标记、关开关；表目录为根（表级下钻、出不了表目录、未知表 404）；名称前缀过滤（过滤与回显、过滤先于截断、只比本层名、含 `/` 拒 400）；下载（流里字节数与元数据一致、目录与仓库根 400、关开关 501） |
| `CatalogFileEndpointTests` | 6 | 接口形状：URL、`path` / `prefix` 查询参数、状态码、响应字段名（列举与下载四条端点；下载钉 `Content-Disposition` 与 `Content-Length`，过滤钉回显与 `total` 口径） |

两者分开是因为这些错误各自独立：服务能把目录列对，不代表端点拼对了
（URL 错一位照样 404）；反之端点形状对了，服务也可能把「文件」当成目录列。

最后一条把「产物是半套」变成了服务端测试能抓到的问题，
与 `verify-console.py` 第 6 组是同一件事的两个入口（前者在 `mvnw test` 里跑，
后者在 `build-console.sh` 结尾跑）。

一个实现细节：断言中文内容要用 `getContentAsString(StandardCharsets.UTF_8)`。
静态资源响应头里没有 charset，`getContentAsString()` 会退回 ISO-8859-1，
断言会看到乱码——这是测试工具的行为，不是服务端的问题。

运行：

```bash
./mvnw -o -pl :lakehouse-rest-server -Dtest=ConsoleApiTests test
```

前端另有一层**纯函数单测**（`node --test`，不需要浏览器也不需要构建）：

```bash
cd lakehouse-rest-console && npm test          # tools/*-tests/*.test.mjs（目前 96 项）
npm run test:auto-refresh                      # 只跑定时刷新的判定（7 个用例）
npm run test:snippet                           # 只跑引擎接入片段（13 个用例）
npm run test:storage                           # 只跑存储类型的显示名 / 顺序 / 可选性（7 个用例）
```

放在这一层的都是「看一眼界面验不出来」的性质：血缘的布局是纯函数（同一份数据两次出同一批坐标）、
定时刷新该不该发这一拍（后台标签页 / 在途请求 / 页面拦截）、偏好收敛（坏 localStorage 不能变成
`setInterval(fn, NaN)`）、**复制出去的那十几行对不对**——`token.provider` 是 `bear` 不是
`bearer`、Flink 的 DDL 只有最后一行不带逗号、Lance 的基址写到 `/lance` 为止、COS 在三个面上是
三种说法（片段错了不会让控制台报任何错，失败现场在业务方那边），以及**「这个存储类型在本部署
能不能选」**——这一条在界面上验不到，因为默认部署支持全部七种、永远看不到灰态。

---

## 10. 已知边界

- **不做写入路径。** 不提供 `commitTable`（见 §7）。控制台不产生数据，
  只改元数据与授权。
- **不做细粒度授权判定。** `/api/console/v1/meta` 只过认证不看权限，
  因为它不暴露任何具体资源信息；`/api/console/v1/auth` 与 `/api/console/v1/login`
  连认证都不过（它们要在登录之前就能被调用），暴露的内容按「对匿名访问者是否敏感」筛过，
  且换令牌的那两条自带失败限速。
- **门禁不是安全边界。** `console.required=true` + `auth.enabled=false` 时，
  界面被拦在登录页而数据接口匿名可调（见 §6）。这句结论在三处界面上写着：
  登录页、设置页的「控制台门禁」一行、以及首页的服务信息表。
- **控制台口令是明文配置。** `console.password.users` 没有哈希存储与轮换流程，
  改口令要改配置并重启；默认 `admin/admin` 时启动日志会告警。
- **不代持凭据。** 令牌只在浏览器 `localStorage` 里；OIDC 用公开客户端 + PKCE，
  构建产物里没有任何密钥。
- **登出无法作废服务端令牌。** 令牌是自包含的 JWT，服务端没有会话表。
  要立刻失效只能轮换签名密钥（会作废所有人的令牌）。详见
  [`console-auth.md` §12](console-auth.md#12-已知边界)。
- **SSO 的单点登出只在 IdP 提供端点时成立。** 登出会把浏览器送到
  `end_session_endpoint`（§6.1）；IdP 没给这个端点、或本次登录不是 SSO 时，
  退回「只清本机令牌」，IdP 那边的会话仍然存在。回跳地址要单独配
  （`oidc.post-logout-redirect-uri`），不配就停在 IdP 的「已退出」页。
- **无审计视图。** 服务端记录了操作日志，但控制台目前没有对应的展示页；
  需要时按 §5.3 的清单加。
- **文件浏览只读，且可关。** `/api/console/v1/catalogs/{catalogName}/files` 与
  `/api/console/v1/tables/{tableId}/files` 只做列目录：不提供删除、上传、建目录。
  另有对应的**单文件下载**（`.../files/download?path=<文件路径>`，流式响应、不缓冲），
  下载同样是只读——把已有的字节搬出去，不产生任何写入；对目录是 400（列它去）。
  全部关掉用 `lakehouse.rest.file-browser.enabled=false`（端点返回 501，消息里带这个键）。
  它们走的授权面与其余 console 端点相同——只过认证拦截器，不做资源级判定；
  这与 `/api/console/v1/meta` 一致，而它们暴露的是**存储布局**（表目录下有哪些文件），
  比 meta 敏感，因此关掉它们的开关是给「只想开控制台看元数据」的部署准备的。
  前者的根是仓库、后者以表目录为根（出不了表外），取舍见
  [`console-catalog-detail.md`](console-catalog-detail.md) 与
  [`console-table-detail.md`](console-table-detail.md)。
- **无 i18n。** 界面为中文单语，文案直接写在组件里。
- **产物要手工构建。** 产物不入库，出 jar 之前必须跑 `scripts/build-console.sh`；
  没有 CI 钩子替你做这一步（守卫是 `build-image.sh` 与 `ConsoleApiTests`，见 §8.2）。
- **Iceberg 协议面：目录浏览与表详情已通，写路径仍不做。** 已落地：
  Catalog 列表的「表格式」徽标与格式筛选、新建 Catalog 页的「表格式」三卡选择
  （默认值取 `meta.service.defaultCatalogFormat`，**创建后不可改**，编辑表单不回传它）、
  catalog 详情的格式展示与「协议与能力」卡片（读 `GET /v1/config?warehouse=` 的
  `endpoints` 原文，不是前端抄的清单）、**目录浏览的 Iceberg 版**（`IcebergBrowseView`：
  命名空间平铺 / 建表（format-version 支持 1/2/3）/ 登记 / 改名 / 删除 / 注销 /
  视图列表与详情，见 §4.6）、以及 **Iceberg 表详情页**（`IcebergTableView`：
  详细信息 / 快照 / 文件列表 / 存储优化四个页签，见 §4.7），
  以及**权限管理页对 Iceberg catalog 的适配**（「库」那一级换成 Namespace，
  候选按层递归，见 §4.8）。
  仍未落地的是**写路径**：快照回滚 / 过期、refs 的管理都要走协议面的更新提交
  （`POST .../tables/{table}`，metadata 代数的 `updates` / `requirements`），
  控制台拼不出一轮合法的提交（与 §7 同一理由）。
  取舍与理由见 [`iceberg-rest-catalog-design.md`](iceberg-rest-catalog-design.md) §11.1 第 11 条。
- **表格式有两个层级，别混。** `src/utils/catalogFormat.js` 管的是 **catalog 级**的
  `catalogFormat`（`PAIMON` / `ICEBERG`，决定服务哪套 REST 协议）；
  `src/utils/tableKind.js` 管的是 **表级**的 `schema.options.type`
  （`table` / `format-table` / `object-table` / `lance-table`，Paimon 自己的表格式）。
  `ICEBERG` 只属于前者，不在 `TABLE_KIND_LABELS` 里。两者正交：一个 Iceberg catalog
  下不存在这四种 Paimon 表格式，反过来 Paimon catalog 下也不该出现 `ICEBERG`
  这个表格式。判别顺序是「先 catalog 格式、再表格式」——把顺序倒过来，
  会拿表级的判别去解释一个 catalog 级的属性。
