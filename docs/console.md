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

控制台的构建产物**提交在仓库里**（`lakehouse-rest-spring-parent/lakehouse-rest-server/src/main/resources/static/console`），
因此**只是启动服务端不需要 Node**：

```bash
# 1) 构建（不需要 npm）
./mvnw -o package -DskipTests

# 2) 启动（h2 profile 用于本地试跑）
java -jar lakehouse-rest-spring-parent/lakehouse-rest-server/target/lakehouse-rest-server-1.0.0.jar \
  --spring.profiles.active=h2 --server.port=8080

# 3) 打开
open http://127.0.0.1:8080/console/
```

只有**改动控制台源码**时才需要 Node 20+：

```bash
./scripts/build-console.sh          # 装依赖（按需）→ 构建 → 校验一致性
```

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
    │                              TipNote（区块说明收进问号图标）、SectionTitle（表单小节头，
    │                              替代 el-divider 的分组标题）、
    │                              JsonBlock、CopyText、TimeText、PageHeader、PanelCard
    ├── composables/index.js       useResource（竞态丢弃）、刷新事件、通知与危险确认
    ├── layouts/ConsoleLayout.vue  侧边导航 + catalog 选择器 + 主题切换 + 刷新
    ├── router/index.js            路由表；侧边菜单由它聚合生成；登录守卫
    ├── store/                     auth（登录态）、session（catalog 与 meta）、
    │                              credentials（令牌与基址）、theme
    ├── styles/index.css           设计变量 --pc-* 与布局类；视图间复用的基元
    │                              （列表头 .pc-list-head、工具行 .pc-tools-row、
    │                              授权表单栅格 .pc-form-grid 等）也收敛在这里
    ├── utils/format.js            时间、字节、JSON 等展示格式化
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
| `/browse` | 目录浏览 | 按选中 catalog 的**表格式**换一套实现：Paimon catalog 是「左库右对象、三页签（表/视图/函数；语义视图的 4 条端点仍在服务端，控制台不再暴露）」；Iceberg catalog 换成 `IcebergBrowseView`（左命名空间右表/视图，见 §4.6）。Paimon 侧能力：建库、建表（Paimon 表 / Format 表 / Object 表三种表格式，Lance 表留在选项里但禁用，见 [console-catalog-detail.md](console-catalog-detail.md) §3.5）、注册表、按表 ID 定位、重命名、删除、详情抽屉。表页签列出表名 / 表格式 / 表类型（Managed 或外部表）/ 创建时间 / 元数据更新时间 / 操作六列，数据取自 `table-details`（只回表名的 `/tables` 给不出这几列）；「元数据更新时间」是服务端这条表记录的最后改动时间，快照提交与回滚也会推动它。表页签的过滤为空时说明「没有匹配 ×××」，与「库里没有表」区分开。**建表入口在「表」页签工具条的右侧**（页头只留刷新与新建数据库）：三类对象的工具条都写在 `ResourceState` 之外——那个组件命中空态时只渲染 `el-empty`、默认插槽整个不渲染，工具条若在里面，「这个库还是空的」这一刻就正好没有新建按钮 |
| `/tables/:prefix/:database/:table` | [表详情](console-table-detail.md) | 十页签：详细信息 / **血缘** / 数据预览 / 快照 / 标签 / 分支 / **文件列表** / 数据访问 / **数据策略** / **存储优化**（血缘与数据预览、数据策略都不随表格式伸缩：页签自己判空，见 [console-table-detail.md](console-table-detail.md) §1.1）；含快照与标签的**回滚**。**没有「变更」页签**：改表结构走 HTTP 或 Spark SQL，理由见该文档 §2。详细信息首屏的四张统计卡片取自 `getTableSnapshot`（三个文件统计的来路与「什么时候会空」见该文档 §2.1），内含**字段列表 / 分区列表**二级页签（名称过滤 + 分页：字段表在浏览器里切片，分区列表走远程分页且前缀过滤下推，分区按需加载）。文件列表按表目录物理内容列一层（根是表目录，出不了表外），目录名可点下钻、文件可下载、前缀过滤与页码都下推服务端。存储优化页签承载表级策略与执行历史（见 [table-optimization.md](table-optimization.md) §7）。血缘页签给三视图（图 / 邻接表 / 列映射），图是 Canvas 与「跳数即层号」的纯函数布局（见 [lineage-design.md](lineage-design.md) §9）。授权不在这张表上做——表级权限统一到「权限管理」页 |
| `/catalogs` | Catalogs | 列出 / 创建 / 详情 / 更新（带版本）/ 删除。名称链到详情页；操作列另有「浏览库与对象」直达 `/browse` |
| `/catalogs/:catalogName` | [Catalog 详情](console-catalog-detail.md) | 四页签：概览 / 存储 / 文件列表 / 配置。**存储**页回答「哪些表该做 compaction」：文件数、存储、平均文件大小、小文件占比（按库聚合），两种格式都支持，口径参考 AWS《Monitoring Iceberg workloads》但取数只用已有端点，见 [console-catalog-detail.md §3.2](console-catalog-detail.md)。文件列表按仓库物理目录列一层，目录名可点下钻、带面包屑与「返回上级」；只读，不提供删除与建目录。**配置页签**两种格式是同一套设计：都编辑「表默认参数」（`table-default.*`，见 [catalog-api-endpoints.md](catalog-api-endpoints.md#catalog-级默认表属性table-default)），Iceberg 另附只读的 `/v1/config` `defaults` / `overrides`（连接参数，非表属性）；其余 catalog 属性不在此页重复展示（概览已有） |
| `/principals` | 主体 | 列出 / 创建 / 更新 / 删除 / rotate / reset；授予与撤销角色。创建、轮换、重置后的凭据弹窗可一键导出 JSON 文件（`{principal, clientId, clientSecret}`，文件名 `<主体>-credentials.json`），密钥仍只在弹窗期间可见 |
| `/principal-roles` | 角色 | 列出 / 创建 / 更新 / 删除；查看成员（授予成员要按主体操作，在「主体」页做）。角色是**纯分组**，自身不携带权限；管理规格里的 `federated` 字段控制台不暴露——本服务不接入外部 IdP，勾了只存一个标记，且 PUT 不接受该字段，勾错改不回来 |
| `/permissions` | 权限管理 | 控制台**唯一**的授权入口（Paimon 官方权限模型，principal 直授）：页头「添加授权」按钮开弹窗填表——多选授权对象（`user:` / `role:`，可手输任意 principal）、选**作用范围**（Catalog / Database / Table / View，四级就是官方的四种资源类型；**Iceberg catalog 下 `Database` 这一级显示为 `Namespace`**，候选与文案都跟着换，见 §4.8；catalog 不需要选库，表 / 视图还要选具体对象）、权限项按范围给（catalog：Create database/Alter/Drop/Grant；db：Create table/Alter/Drop/Describe；表：Select/Insert/Delete/Describe/Alter/Drop；视图：Select/Describe/Alter/Drop）、**有效期**默认长期有效，可选「指定日期」（按所选当天 23:59:59 本地时区换算成官方的 UTC instant；日历与手输都挡掉过去的时间）；主界面是授权清单——一行 = 一个主体 × 一个作用范围，同范围的权限并列成标签、点标签上的 × 逐项撤销，过期时间按日期显示（完整时刻在悬浮提示，7 天内到期标「N 天后」，过期条目不隐藏但标注「已过期」，多项不一致取最早并标注），资源合成「作用范围」一列（`整个 catalog` / `库` / `库.对象`，标注整 catalog/整库/单表），权限用与表单一致的词（UPDATE 显示为 Insert / Delete），空态给「添加授权」入口；支持过滤与幂等撤销 |
| `/policies` | 数据保护 | 行过滤与列脱敏策略的总览页（数据来自扩展端点 `GET /api/management/v1/policies`）。四张统计卡（受保护表 / 带行过滤 / 脱敏列 / 豁免主体）与列表共用**一次全量拉取**：策略量级是「几十」（`DataPolicyService.list` 的注释口径），要跨页 KPI 与组合过滤，只有整份列表在手才数得准——这是「先过滤再切页」规则的又一实例，与「全站远程分页」的例外判据（有没有列表端点之外的整份列表需求）一致；此处列表端点本身支持分页，但页面刻意不传 `page`。列表列：表（三段名拆解，链接到表详情 `?tab=policy` 深链接）/ 行过滤 SQL（`code` 展示）/ 脱敏列（tags）/ 状态 / 版本 / 更新时间与操作者；操作列「试算」跳主体视角试算页、「详情」跳表详情的数据策略页签。表详情的「数据策略」页签见 [row-filter-column-mask-design.md](row-filter-column-mask-design.md) §8.3 |
| `/policies/new` | 新建策略向导 | 目标表三段级联（catalog / database / table，watch 级联清空）→ 拿表 schema 做候选列 → 主体范围与豁免（fail-closed 不可关）→ 行过滤 / 列脱敏编辑器（与表详情页签共用 `src/utils/policyAst.js`，保证两处建出的 AST 形状一致）→ 发布（PUT 整份替换）。**已绑定策略的表不给新建**：PUT 是整份替换，向导里再建一次会静默顶掉现有策略，此时给红色提示并引导去表详情编辑 |
| `/policies/:catalog/:database/:table/dryrun` | 主体视角试算 | 按**被模拟主体**（不是当前调用者）跑 `POST .../policies/tables/{c}/{d}/{t}/dry-run`，四态横幅：FOUND（生效中，附折叠后的行过滤 SQL）/ EXEMPT（豁免，明文）/ DISABLED（策略停用）/ NONE（无策略或白名单外——**红色**，提示「按默认放行，全部为明文」）。结果表按返回列渲染，脱敏列单元格带「脱敏 / 置空」角标。被模拟主体**不需要存在**（`$principal` 折名字字面量、角色名单查不到折 false），这正是「新建主体还没挂角色」要试算的场景。端点判权 TABLE:ALTER——试算结果本身就是策略内容，放宽到 SELECT 等于任何能读表的人可看策略文本 |
| `/optimization-jobs` | 优化任务 | 表存储优化的批次运维页（数据来自管理 API `/api/management/v1/optimization/**`，策略配置在表详情的「存储优化」页签）。四张统计卡（运行中 / 排队中 / 今日批次 / 失败 24h）与列表共用**最近 200 个批次**的窗口，状态 / 动作 / Catalog / 触发方式四个过滤器在本地做——统计卡与列表若是两个口径，「失败 3 个」就会在列表里数不出来。批次表展开行按需拉表级明细（每行指标摘要与失败原因），「批次详情」抽屉给键值对 + 时间线 + 回调令牌 + 原始响应；操作列的重试（只重跑失败表，原批次保留）与取消都带确认框。`?job=` 查询参数用于从表详情的历史点批次号跳过来直接展开 |
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

### 4.1 连接自检

首页的「连接自检」不是装饰：控制台最常见的故障是**地址或令牌配错**，
而其表现是「每个页面都是空列表」，看不出根因。它逐个探测控制台依赖的端点，
把失败的那一个连同 HTTP 状态与错误消息一起列出来，一眼能分辨
「服务端没起来」「令牌不对」「路径写错了」。

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

### 4.3 存储配置表单里的静态凭据

创建 / 编辑 catalog 时的存储配置（`components/StorageConfigFields.vue`）在
`S3` / `OBS` / `OSS` 三种类型下多出一组「静态凭据」：`accessKeyId` 与
`secretAccessKey`（密码框，可切换显示）。它是本工程在规格之外的扩展，语义与边界见
[`README.md` 的存储与凭据一节](../README.md#5-配置项)，这里只说表单侧的做法：

- **是否渲染由 `staticCredentialsEnabled` 决定**（即服务端有没有配 `credential-secret-key`）。
  取不到 meta 时按 `false` 处理——宁可少一个字段，也不要让用户填完才被 400 拒绝。
- **能力关着时不把字段藏起来**，而是在原位说明该开哪个配置项、以及生成密钥的命令
  （`openssl rand -base64 32`）。藏起来的后果是「别人告诉我这个功能存在，但我这儿看不到」。
- **编辑已有 catalog 时密钥框留空**，placeholder 显示服务端回显的 `accessKeyId`，
  意思是「已配的是这把钥匙，留空就保持」。这与 `PUT` 的「省略即保持」例外一一对应；
  提交时留空则这个键根本不进请求体。
- **「移除已保存的静态凭据」按钮**提交一对空串（二次确认），之后下发退回服务端配置那一级。
- **前端先拦一遍**同样的四条规则：有密钥没 ID、换 ID 不带新密钥、与 `storageName` 互斥、
  能力未开启。理由与下拉候选来源一致：服务端只会回一句笼统的 400，而这一层能说清是哪一条。

### 4.4 存储高级参数（折叠区）

同一个组件里，字段被拆成两张表：`BASIC_FIELDS`（主表单平铺）与 `ADVANCED_FIELDS`
（收进默认收起的「存储高级参数」）。判据只有一条：**不填就跑不起来的留在外面**
（`region` / `endpoint` / Azure 的 `tenantId` 决定仓库在哪），其余收进去。

| 存储类型 | 主表单 | 折叠区 |
| --- | --- | --- |
| `S3` | `region`、`endpoint` | `roleArn`、`externalId`、`userArn`、`stsEndpoint`、`endpointInternal`、`stsUnavailable`、`pathStyleAccess`、`kmsUnavailable`、`encryptionKeys`、`decryptionKeys`（10 项，分「STS 与角色 / 访问方式 / KMS 与加密」三组） |
| `AZURE` | `tenantId`（必填） | `multiTenantAppName`、`consentUrl`、`hierarchical` |
| `GCS` | — | `gcsServiceAccount` |
| `OBS` / `OSS` | `endpoint` | `stsUnavailable` |
| `FILE` | — | 无（折叠区整块不出现） |

三条规则与它们的理由：

- **默认收起，且不自动展开。** 初始值恒为 `false`，不由「是否有值」反推；换类型与
  换一份配置（打开对话框、切到另一个 catalog）时重置为收起。不自动展开是因为状态会被抢：
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
- **这 10 项里服务端当前只消费 2 项**（`pathStyleAccess` → 下发 `s3.path-style-access`，
  `stsUnavailable` → 决定是否下发密钥）。其余 8 项接收、落库、回显，但不参与行为——
  它们仍是管理 API 契约的一部分（形状沿用存档的那份 Polaris 规格），所以不能删控件。前端判断不了「哪几个真的生效」，
  因此折叠区里只给了一句中立说明（「是否影响引擎行为，取决于服务端的凭据实现」），
  不点名字段、不加徽标：把服务端的实现细节抄进前端，正是 §10 反对的做法。

### 4.5 `roleArn` 与静态凭据并存时只提示、不拦截

`StorageDtos` 的注释写着「静态凭据与 `roleArn` 系列互斥，由 `StorageConfigs` 拒绝」，
但 `StorageConfigs.requireSingleCredentialSource` 实际只判「静态凭据 × `storageName`」。
**服务端今天接受这种组合**，因此控制台只给一条提示（`.pc-field-note-warn`），不写进
`validateCredentials()`：前端比服务端更严，会让一份服务端认可的配置在控制台里存不回去。
要根治得在服务端补拒绝逻辑，那属于契约变更。

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

---

## 5. 契约表与机械校验

### 5.1 为什么要有一张端点表

控制台是用字符串拼 URL 的，而服务端的路径属于**契约**（catalog API 见
`spec/rest-catalog-open-api-paimon.yaml`，管理面的官方部分是 Paimon REST Management API，
见 `spec/rest-management-open-api.yaml`；catalog / principal 等扩展端点的形状源自已移除的
Polaris 规格）。路径写错时前端的表现是
「404 空列表」，看不出是前端拼错了还是服务端改了——而这批路径有 85 条，
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

它**不构建前端、不启动服务端**，只做静态比对，因此可以放进提交前的检查。23 组 150 项：

| 组 | 校验内容 |
| --- | --- |
| 1. 端点存在 | 每条路径都能在规格或扩展白名单中找到；端点名不重复 |
| 2. 扩展白名单 | 声明为规格外扩展的端点确实被使用，且每条都写明了理由（无过期条目） |
| 3. 端点引用 | `src/` 里按名字调用的端点都在 `endpoints.js` 中声明 |
| 4. 无死条目 | `endpoints.js` 里声明的端点都在 `src/` 中被调用过 |
| 5. 基址一致 | vite `base`、路由 `base`、构建输出目录、服务端静态资源位置、client 默认基址五处指向同一处；令牌端点路径与服务端下发的 `tokenEndpoint` 一致；`buildPath` 拒收既非路径参数也非 `query` 的 `params` 键（静默丢弃会变成无参请求，服务端只回一个缺参 400） |
| 6. 产物完整 | 入口页存在，且它引用的每个 assets 文件都存在 |
| 7. 产物新鲜 | 构建产物不比控制台源码旧（改了源码没重新构建会在这里暴露） |
| 8. 对话框状态 | 每个 `el-dialog` 都声明了 `destroy-on-close`（否则编辑时会残留上一次的状态） |
| 9. 下拉候选来源 | 每个 `el-select` 都声明了候选来源（`el-option` / `:options` 等）；确属自由输入的要在源码里写 `el-select-free-input` 标记显式豁免（否则「下拉是空的」这类 bug 只有打开页面才看得到） |
| 10. 静态凭据 | catalog 自带 AK/SK 这条链路横跨四个文件（服务端 DTO 的字段名、`supportsStaticCredentials` 认可的类型、meta 里的能力开关、前端表单），逐处对齐：字段名与三种类型的 record 参数逐字一致、类型集合两侧一致、**规格里确实没有这两个字段**（否则不再是扩展，要先对齐语义）、能力开关从 meta 一路传到表单 prop、能力关着时给的是可操作提示而不是把字段藏起来、读路径仍做脱敏 |
| 11. 优化参数键 | 存储优化各动作的表单字段名与服务端逐字一致：Paimon 那份对 `OptimizationOptions.KNOWN_KEYS`、Iceberg 那份对 `IcebergOptimizationOptions.KNOWN_KEYS`（键名写错的表现是保存时吃一个 400，而编译、类型检查与单元测试都不会报；两套键混用的表现更糟——`retain-min` 与 `retain-last` 被互相收下、然后静默不生效） |
| 12. catalog 表格式 | `PAIMON` / `ICEBERG` 这条链路跨六处：服务端 `CatalogFormat` 枚举 ↔ 前端兜底取值逐字一致、meta 下发的候选与默认值都来自枚举本身、新建表单从 meta 取候选与默认值且提交体带 `catalogFormat`、**编辑不回传它**（格式不可变）、列表有徽标与格式筛选（筛选与徽标用同一个归属函数）、三个页面按 catalog 格式伸缩**且在 loader 里就挡住请求**、详情页的能力清单读服务端 `/v1/config` 而不是前端抄一份、**权限页的候选来源按格式分派**（Iceberg 走 `namespaces/**` 并按层递归、Paimon 走 `databases/**`，选错来源的表现是空下拉而不是报错）、**两种格式的「配置」页签是同一套设计**（都编辑 `table-default.*`，标题按格式换名；Iceberg 另附只读的 `/v1/config` 的 `defaults` / `overrides`——那是连接参数，不是表属性；Paimon 不再重复列出概览已有的「其它属性」） |
| 13. Iceberg 浏览 | Iceberg catalog 的目录浏览走 `namespaces/**` 而不是 `databases/**`。这一组钉的是**契约形状**，不是像素：13 条 `iceberg*` 端点逐条对 Iceberg 规格（不靠第 1 组的并集蒙混）、浏览页只调 `icebergApi` 不混用 `catalogApi`、namespace 路径段用 `0x1F` 拼（点号只是显示口径）、分页键是 `pageSize` 而不是 `maxResults`、三个列表（表 / 视图 / 函数）都按 `identifiers` 取（逐表 `loadTable` 只许出现在表详情页）、建表体是 Iceberg 的 `schema` / `partition-spec`（`schema-id`、`required`、`source-id`、`field-id`）、浏览页与 API 层都不出现 `/databases`、浏览页不再提供删除命名空间的入口（按钮已移除）、两页共用的骨架样式留在全局；以及 **Iceberg 表详情页**（`IcebergTableView`）：有独立路由、只调 `icebergApi`、文件列表走 catalog 级文件端点（不用 Paimon 的 `tableId` 端点）、快照处理 `-1` 无快照哨兵、存储优化页签复用共用面板并显式传 `format="ICEBERG"`（页签自己调端点会绕过 format 分派）、建表表单的 `format-version` 提供 1/2/3 |
| 14. 存储字段分层 | catalog 表单的字段拆成「基本 / 高级」两张表，高级那张进默认收起的折叠区。判据对着**服务端 record**，不在前端另抄名单：两张表合并后与 record 的专属字段**不丢不多**、两表之间不重叠、`build()` 既**不读折叠状态**又仍读合并后的视图、`FILE` 没有高级字段、折叠区按「有没有高级字段」出现而非硬编码某类型、默认收起、收起态给出计数与「可以不管它」的说明 |
| 15. 事件页 | 事件页从「游标增量」改成「远程分页」之后，最容易退化的两件事要钉住：`listEvents` 端点路径与端点名一致、`EventsView` 真的接了 `useRemotePaging`（页码下推、`total` 取自 `page.total`）——游标只能往前看，页码模式才能往回翻历史；**事件类型也跟着下推**（类型只在前端筛时只筛得到当页，筛出空页会被读成「窗口里没有这种事件」）、事件类型清单逐个覆盖 `CatalogEventType` 的 wire 值、**类别清单逐个覆盖 `CatalogEventCategory` 的取值**、类别作为 `category` 参数随页码一起发出、目标类型显示名逐个覆盖 `CatalogEvent` 的 `TARGET_*` 常量（前端另抄名单漏了新类型的表现是那条事件显示成 wire 原文，静默）、`/events` 有独立路由并进侧边菜单 |
| 16. 提示图标 | 问号图标（`TipNote` / `FieldLabel`）必须**悬停即显示**：`trigger="hover"` 显式写明、`show-after` 为 0，且命中区比 14px 的图标本身大一圈。这条约束的起因是用户反馈「移到问号上没反应、点一下才弹」——`show-after` 的 100ms 让快速划过什么都不出现，而 14px 的命中区偏一两个像素就落在容器上（点击时整块区域都算命中，所以点击反而有效）。两个原因都不会让页面报错 |
| 17. 目录浏览的入口 | 两个浏览页（`BrowseView` / `IcebergBrowseView`）的三条工具条都写在 `ResourceState` **之外**：那个组件命中空态时只渲染 `el-empty`、默认插槽整个不渲染，「这个库还是空的」这一刻新建按钮会跟着表格一起消失（表页签当时靠页头那颗按钮躲过，代价是同一动作两个入口）。因此还断言：页头不再放「新建表」、`/browse` 不再有「语义视图」页签，以及 Iceberg 视图页签的创建入口与 `endpoints.js` 的 `icebergCreateView`、控制器上的 `@PostMapping` 三处对齐——缺一处按钮就是 404 |
| 18. Catalog 详情·存储 | catalog 详情的「存储」页：两种格式共用 `CatalogStoragePanel`（格式只作参数），数据源是管理面两个聚合端点 `/table-stats`（汇总：KPI/按库/Top10/直方图 SQL 一次算完，永远全量）与 `/table-stats/tables`（表明细远程分页，默认取全部表）——采集文件只许这两个调用，**不得残留 catalogApi / icebergApi 的逐表调用**（非快照管理的表尤其不该被问到快照端点）、「有数据的表」分子在前端算（服务端 `Totals` 没有 `withData` 字段，读不到就是「—」）、加权平均与小文件判据的口径钉在服务端聚合 SQL 里、面板不许直接调协议面或管理面（写动作在表页的「存储优化」）、「只统计 Paimon 表」以 onlyPaimon 参数在服务端过滤、明细分页/关键字/排序下推 SQL 且排序 `custom`（不得再挂「选库才请求」的门）、按库/Top10 两卡不吃堆叠边距（顶部对齐）。两个端点同时在扩展白名单里登记，控制台引用了规格之外又没登记的路径会当场报错 |
| 19. 表格远程分页 | 全站表格统一走「页码与过滤下推，页面只持有一页」。十条判据：`composables` 只导出 `useRemotePaging`（通用 `useLocalPaging` 已删——它的产物是「共 N 条 = 这一批的条数」）、11 个列表视图逐个接上它、分页器只有 `TablePager` 一个（各写一份 `el-pagination` 会漂出「这页能选 100、那页最大 50」）、16 处 `TablePager` 用法都带 `total` / `page` / `page-size`、12 个分页端点都接受 `page`（少了它前端翻页会被当成不分页，症状是「点了下一页，内容没变」）、存储明细按页码取数（不再在浏览器里编码 `pageToken`——那等于把服务端的私有 token 格式变成前端契约，服务端换编码后这一页会安静地永远停在第 1 页）、调用 API 时 `query` 写在 `params` 里（写在顶层会被静默丢弃——分页与过滤一个都传不过去）、全局样式不占用组件私有的 `pc-opt-*` 前缀（控制台的两处缩写撞过车：选择卡曾叫 `pc-opt`，与「存储优化」面板的根节点同名，整整一屏被套上选择卡的边框、内边距与 pointer 光标，hover 时边框还会变蓝；同族的 `pc-opt-dot` 更隐蔽——面板拿它当分隔符 `·`，全局却把它画成 9px 圆点。现在选择卡叫 `pc-pick*`）、带行内动作的表格把「操作」列钉在右侧（`fixed="right"`，展开箭头同理钉在左侧——列多时横向滚动是常态，操作列跟着滚走等于「滚到右边才找得到按钮」）、`total` 为 0 时不渲染分页器。**过滤必须一起下推**：本地筛只作用于当页，「筛出 3 条」与「库里有 3000 条只给了 200 条」在界面上长得一模一样 |
| 20. 数据预览 | 预览是控制台第一个**真的读行数据**的页面，它的错法都不报错、只把话说错：`TablePreviewPanel` 只许调 `previewApi` 两个方法（拼下载端点或直连协议面就绕过了 TABLE 级 `SELECT` 与采样限额）；采样提示必须在，且 `filesRead` / `filesInScope` / `filesInTable` 三个分母都在（只说「只读了 K 个文件」等于不说全表多大，只留一个分母就把「3/12（本分区）」说成「3/128（全表）」）；分区 chip 要有「自动」角标分支，且 `×` 的含义是**切到全部分区**而不是删掉自动条件；面板不许自己排序/取 max 算「最新分区」（两处实现迟早不一致，表现是「刷新一次分区变了」）；页签只对快照托管表渲染（Format / Object / Lance 表点进去只剩「不支持」）；六类失败各有各的文案（400/403/404/409/429/504——混成一句「加载失败」时用户只能刷新，而其中三类刷新没用）；「加载更多」只靠游标（`hasMore` 为假必须清空游标，否则再点一次会在同一段上重复读）；两条端点在服务端真的存在、且判权用 `Access.SELECT` 而不是 `DESCRIBE`（能看元数据不等于能看数据）；Iceberg 表详情页挂**同一个面板**而不是另写一个（它按名字加载表，因此先 `lookupTable` 换内部 id，换到才挂；换表与刷新时必须作废旧 id，否则拿旧表的 id 去读新表）；**`lookupTable` 的三个查询参数必须写进 `params.query`**（平铺在 `params` 顶层会被静默丢掉，数据预览固定回「Required parameter 'prefix' is not present」的 400） |
| 21. 血缘 | 表详情页的「血缘」页签：两个详情页都用异步组件挂 `LineagePanel`（同步 import 会让每张表的详情页为一块多数人不看的图付下载与解析成本）、页签名与正文条件一致、页签登记为「自带数据」（面板挂载即查，父组件替它发请求就是两次）、图与绘制层**不 import element-plus**（否则布局逻辑只能靠 Vue 运行时才测得到）、三个格式色只读 CSS 变量且变量定义在全局样式里（画布、图例、目录页徽标三处同源）、布局是纯函数（不读 DOM、无随机数与时钟——否则刷新一次位置全变）、LOD 阈值只有一处带滞回的实现（两处阈值不同会在临界点上闪）、**拖单张卡片只叠加视图位移**（偏移不进 `layout()`——写回布局就等于「同一份数据两次出不同坐标」，刷新一次位置全变；换根时整份清掉）、边按「颜色 + 线宽」分组后用 `Path2D` 批量描边（1500 条边逐条 stroke 会掉帧）、面板只经 `lineageApi` 取数、**控制台不声明血缘的写入口**（手工上报等于让人写一条假依赖）、列映射切到「列」视图才发第二次请求、节点超过 500 自动落邻接表且留「仍要看图」的出口、两个表格用 `el-table-v2`（虚拟滚动，也是 Canvas 的无障碍替代）、抽屉打开时画布让位、五个端点在服务端都有实现、图端点处理 `If-None-Match` 回 304（`no-cache` 而不是 `no-store`）、表被删除时级联清边（否则图上留下查不到也清不掉的节点） |
| 22. 执行集群 | catalog 级执行集群（优化任务提交到哪个 k8s、用什么身份）。这一组钉的是**多集群这件事的形状**，因为它的错法全都不报错：六个端点都必须挂在 `catalogs/{catalogName}/execution-cluster` 下（不引入一级资源——没有「列出全部执行集群」的需求，也没有「不属于任何 catalog 的执行集群」）、控制器类级路由与端点前缀逐字一致且六个动词映射齐全、三种集群类型（`SELF_HOSTED` / `EKS` / `AKS`）在服务端 `ClusterType` 与前端 `CLUSTER_TYPES` 两处取值完全相同、五种状态（`NOT_CONFIGURED` / `DISABLED` / `UNVERIFIED` / `HEALTHY` / `FAILING`）同样两处一致、**前端代码里不出现 `configHash` / `probeConfigHash`**（状态只由服务端算；两边各算一份的漂移表现是「列表说正常、点进去说待验证」，而那一屏最难解释）、`Catalog` DTO 带摘要且 `list()` 用批量 `summaries(...)` 而不是逐行 `summaryOf(...)`（列表是打开最频繁的页，N+1 在这里等于「catalog 越多越慢」）、列表有那一列并走 `clusterCell` 翻译、未配置的行按钮是主色（那一刻它是待办）、**读模型不含任何凭据明文**（`kubeconfig` 只回摘要、秘密只回 `has*`）、秘密字段有显式的 `clearCredentials` / `clearCatalogToken`（留空＝不改，清空要单独说）、`application.yml` 的 `optimization` 段只剩三个节拍键且 12 个提交参数一个不剩、`RestServerProperties.Optimization` 与之对齐（就三个字段）、fail-closed 的两个落点都在（保存策略与「立即优化」各一次 `assertDispatchable`）、出批侧在造批次之前判「可派发」、旧的两种提交器已删除、fabric8 客户端禁用 `autoConfigure` 回落并断言 master URL（缺了它，填错 apiServer 的 catalog 会静默把 Job 提交到服务端自己所在的集群）、`sql/schema-mysql.sql` 里真有这张表（实体加了没重新生成 DDL，部署时会因 `ddl-auto=validate` 起不来） |
| 23. 文档计数 | 本节这张表上方的「N 组 M 项」与脚本实际一致（`docs/console.md` 与 `README.md` 各一条）。期望值由脚本自身推出——组数是源码里分组标题的条数，项数是本次校验的总条数——因此加/删校验项时这里不用改，但**文档必须跟着改** |

第 7 组是这套机制里最实用的一条：**产物过期不会让任何东西报错**，
它只是安静地少一个按钮。

第 12 组里有两处判据值得说明，它们都不是「源码里有没有这个字符串」那么直白：

- **按格式伸缩那一项断言的是「loader 里挡了请求」，不是「模板里有 `v-if`」。**
  只在模板上藏内容，请求照样发得出去——对一个 Iceberg catalog 打 Paimon 的
  `/databases/**`，页面上会留下一串 404，看起来像故障。
  目录浏览这一条在 Iceberg 浏览落地后变成两半：**换页那一半**断言
  `<IcebergBrowseView v-if="icebergCatalog" />` 真的在，**挡请求那一半**
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

### 6.1 登出只是忘掉本机令牌

服务端**没有会话表**：令牌是自包含的 JWT，签出去之后在
`lakehouse.rest.auth.access-token.ttl`（默认 1 小时）内一直有效，没有可以作废它的接口。
因此 `auth.logout()` 不通知服务端，只清掉本机那份令牌。

这一点在界面上如实说明（顶栏按钮的提示、设置页的说明文字），
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

这样 `mvn package` 与 `Dockerfile` **都不依赖 npm**（`Dockerfile` 现在只装构建好的
jar，连 Maven 都不跑，更不涉及 Node），Java 侧构建路径上完全没有 Node。
代价是产物必须跟着源码一起提交。

### 8.2 产物要提交

`lakehouse-rest-console/.gitignore` 忽略的是 `node_modules/`、`dist/`、`.vite/`；
构建产物不在这个目录里，它落在服务端 `src/main/resources/static/console` 下，
**属于被跟踪的内容**：

- 改了控制台源码却没重新构建并提交产物 → 服务端打包出来的是上一版控制台；
- `verify-console.py` 第 7 组会查出产物比源码旧（但只在有人跑它的时候）。

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

`ConsoleApiTests`（12 个用例）覆盖服务端侧的行为，重点是**托管机制**而非页面渲染
（页面渲染属于前端职责，不适合放在服务端测试里）：

| 用例 | 断言 |
| --- | --- |
| `metaExposesEnumsUsedByConsoleForms` | meta 返回了表单需要的全部枚举组 |
| `metaPrivilegesFollowManagementSpec` | 权限分组与管理规格一致 |
| `authAsksForALoginEvenWhenTheDataPlaneIsOpen` | 默认配置下 `authEnabled=false` 但 `consoleRequired=true`，且 `methods` 以 `password` 开头——两个字段必须同时如实 |
| `authReportsTheSessionWhenATokenIsPresent` | 同一个端点带上令牌时只多出「你是谁」，不会被 401 挡掉 |
| `consoleEndpointsRejectAnonymousRequestsWhileTheGateIsOn` | 门禁开着时 `/api/console/v1/**` 拒匿名，而 `/v1/**` 仍匿名可调（门禁不是安全边界） |
| `authAdvertisesThePolarisCompatibleTokenEndpoint` | 引导下发的令牌端点就是 Polaris 那条路径 |
| `consoleRootRedirectsToTrailingSlash` | `/console` → 302 |
| `consoleDirectoryUrlForwardsToEntryPage` | `/console/` → 200 入口页 |
| `consoleEntryIsServedAtItsRealUrl` | `/console/index.html` → 200 |
| `clientSideRoutesFallBackToEntryPage` | 深链回到入口页 |
| `missingAssetStillReturns404` | 缺失 assets 仍 404（不回退） |
| `committedConsoleHtmlReferencesExistingAssets` | 已提交的入口页引用的产物都存在 |

登录链路本身另有 `ConsoleAuthEndpointTests`（31 个用例，错误码、接线、门禁范围、
默认口令与限速）与两个运行期验收脚本，见 §5.2。

仓库文件浏览（`/api/console/v1/catalogs/{catalogName}/files`）另有两类，**分开写**：

| 测试类 | 用例 | 管什么 |
| --- | --- | --- |
| `CatalogFileBrowserTests` | 21 | 服务语义：下钻、目录优先排序、路径归一化与 `..` 拒绝、404/400/501 的判据、截断标记、关开关；表目录为根（表级下钻、出不了表目录、未知表 404）；名称前缀过滤（过滤与回显、过滤先于截断、只比本层名、含 `/` 拒 400）；下载（流里字节数与元数据一致、目录与仓库根 400、关开关 501） |
| `CatalogFileEndpointTests` | 6 | 接口形状：URL、`path` / `prefix` 查询参数、状态码、响应字段名（列举与下载四条端点；下载钉 `Content-Disposition` 与 `Content-Length`，过滤钉回显与 `total` 口径） |

两者分开是因为这些错误各自独立：服务能把目录列对，不代表端点拼对了
（URL 错一位照样 404）；反之端点形状对了，服务也可能把「文件」当成目录列。

最后一条把「产物不同步」变成了服务端测试能抓到的问题，
与 `verify-console.py` 第 6 组是同一件事的两个入口（前者在 `mvnw test` 里跑，
后者在提交前跑）。

一个实现细节：断言中文内容要用 `getContentAsString(StandardCharsets.UTF_8)`。
静态资源响应头里没有 charset，`getContentAsString()` 会退回 ISO-8859-1，
断言会看到乱码——这是测试工具的行为，不是服务端的问题。

运行：

```bash
./mvnw -o -pl :lakehouse-rest-server -Dtest=ConsoleApiTests test
```

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
- **OIDC 不做单点登出。** IdP 的 `end_session_endpoint` 已发现但未接：
  控制台登出后，IdP 那边的会话仍然存在。
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
- **产物需手工同步。** 没有 CI 钩子强制 `verify-console.py`（见 §8.2）。
- **Iceberg 协议面：目录浏览与表详情已通，写路径仍不做。** 已落地：
  Catalog 列表的「表格式」徽标与格式筛选、新建 Catalog 弹窗的「表格式」选择
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
