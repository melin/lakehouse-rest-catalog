/**
 * 控制台调用的**全部**服务端端点，集中在这一处声明。
 *
 * <p>为什么不把路径散在各个视图里：控制台是用字符串拼 URL 的，
 * 而服务端的路径属于契约的一部分（catalog API 见 `spec/rest-catalog-open-api-paimon.yaml`，
 * 管理面官方部分见 `spec/rest-management-open-api.yaml`，扩展端点见
 * `scripts/verify-console.py` 的白名单）。
 * 路径写错时前端的表现是 404 空列表，看不出是前端拼错了还是服务端改了，
 * 因此把它们收成一张表，交给 `scripts/verify-console.py` 与服务端路由逐条比对——
 * 前端引用了服务端不存在的路径，或者服务端路由改名后前端没跟上，都能在离线状态下查出来。
 *
 * <p>`path` 用 `{名字}` 占位，由 `client.js` 做替换并做 URL 编码。
 */
export const ENDPOINTS = {
  // ------------------------------------------------------------------ 控制台元数据与认证
  // 本工程扩展端点（非 Polaris 规格）。
  //
  // 后三条在服务端鉴权范围之外（`WebConfig` 用精确路径排除），这是刻意的：
  // 它们要回答的正是「怎么登录」，把它们放在鉴权之后会形成死循环。
  // 代价是它们能被匿名调用，因此暴露的内容按「对匿名访问者是否敏感」筛过一遍，
  // 且两条换令牌的端点自带失败限速。
  meta: { method: 'GET', path: '/api/console/v1/meta' },
  // 仓库文件浏览：列出 catalog 仓库里某个目录的一层内容（`?path=` 给相对仓库根的路径）。
  // 与 meta 一样是本工程的扩展端点，服务端实现见 ConsoleFileController；
  // 它读的是物理目录（表目录里有哪些文件、有没有残留），不是元数据。
  listCatalogFiles: { method: 'GET', path: '/api/console/v1/catalogs/{catalogName}/files' },
  // 同一套浏览能力、根换成表目录（`?path=` 相对表目录）：表详情页的「文件列表」。
  // tableId 是表详情响应里的 id；以表目录为根，天生列不出表外的东西。
  listTableFiles: { method: 'GET', path: '/api/console/v1/tables/{tableId}/files' },
  // 单文件下载（`?path=` 必填，必须指到文件）：流式回给浏览器，仍走同一开关与路径防护。
  downloadCatalogFile: { method: 'GET', path: '/api/console/v1/catalogs/{catalogName}/files/download' },
  downloadTableFile: { method: 'GET', path: '/api/console/v1/tables/{tableId}/files/download' },
  // 目录事件流：游标是 `audit_event` 的自增 id，`?since=` 只回比它新的事件，
  // 省略表示首次拉取、回当前最新一页（增量契约见 `docs/event-framework.md` §4）。
  // 两种表格式的事件在同一个流里，`?catalog=` 只是查询参数、不是两套 API。
  listEvents: { method: 'GET', path: '/api/console/v1/events' },
  // 数据预览：POST 而非 GET 的三个理由（结构化过滤条件、会写审计事件、游标是不透明长串）
  // 写在 docs/table-data-preview-design.md §5。响应显式 `no-store`。
  previewTable: { method: 'POST', path: '/api/console/v1/tables/{tableId}/preview' },
  // 分区选择器的数据源。与预览同一道门（TABLE 级 SELECT）：分区值与文件数本身就是
  // 数据分布信息。`?keyword=` 按分区值前缀过滤，`?page=`/`?pageSize=` 走全站远程分页。
  listTablePartitions: { method: 'GET', path: '/api/console/v1/tables/{tableId}/partitions' },
  // 名字 → 内部 id。只给 Iceberg 表详情页用：它按名字加载表（LoadTableResult 里没有内部
  // id），而上面两条按 id 寻址。判权与预览同一道门（TABLE 级 SELECT）。
  lookupPreviewTable: { method: 'GET', path: '/api/console/v1/tables/lookup' },
  // 登录引导：服务端支持哪些认证方式、令牌端点在哪儿、手上这个令牌算不算数。
  auth: { method: 'GET', path: '/api/console/v1/auth' },
  // 用户名 + 密码换令牌：控制台的默认方式，账号在服务端配置里（默认 admin/admin）。
  // 响应是本工程的驼峰字段（`accessToken` / `expiresInSeconds`），不是 OAuth 的 snake_case。
  passwordLogin: { method: 'POST', path: '/api/console/v1/login' },
  // 用 Polaris 的令牌端点路径，而不是本工程的 /api/console/：按 OAuth 2.0 写的客户端库、
  // 以及 Polaris 官方 console（默认 VITE_OAUTH_TOKEN_URL 就是这个地址）都能直接指向
  // 本服务端。响应字段也是 RFC 6749 的 snake_case，不走本工程其余 DTO 的驼峰。
  token: { method: 'POST', path: '/api/catalog/v1/oauth/tokens' },

  // ------------------------------------------------------------------ catalog API：服务发现
  config: { method: 'GET', path: '/v1/config' },

  // ------------------------------------------------------------------ catalog API：database
  listDatabases: { method: 'GET', path: '/v1/{prefix}/databases' },
  createDatabase: { method: 'POST', path: '/v1/{prefix}/databases' },
  getDatabase: { method: 'GET', path: '/v1/{prefix}/databases/{database}' },
  dropDatabase: { method: 'DELETE', path: '/v1/{prefix}/databases/{database}' },
  alterDatabase: { method: 'POST', path: '/v1/{prefix}/databases/{database}' },

  // ------------------------------------------------------------------ catalog API：table
  listTables: { method: 'GET', path: '/v1/{prefix}/databases/{database}/tables' },
  listTableDetails: { method: 'GET', path: '/v1/{prefix}/databases/{database}/table-details' },
  listTablesGlobally: { method: 'GET', path: '/v1/{prefix}/tables' },
  createTable: { method: 'POST', path: '/v1/{prefix}/databases/{database}/tables' },
  registerTable: { method: 'POST', path: '/v1/{prefix}/databases/{database}/register' },
  getTable: { method: 'GET', path: '/v1/{prefix}/databases/{database}/tables/{table}' },
  getTableById: { method: 'GET', path: '/v1/{prefix}/tables/id/{tableId}' },
  alterTable: { method: 'POST', path: '/v1/{prefix}/databases/{database}/tables/{table}' },
  dropTable: { method: 'DELETE', path: '/v1/{prefix}/databases/{database}/tables/{table}' },
  renameTable: { method: 'POST', path: '/v1/{prefix}/tables/rename' },
  rollbackTable: { method: 'POST', path: '/v1/{prefix}/databases/{database}/tables/{table}/rollback' },
  // 有意**不**包含 commitTable（`.../tables/{table}/commit`）：
  // 它是写入方（Spark / Flink 的 writer）提交快照的接口，请求体里要带
  // manifest list 等由写入方在本地生成的文件路径。控制台拼不出一个真实的快照，
  // 提供一个这样的表单只会让人提交出一张指向不存在文件的表。
  // 控制台对快照的介入只做「读」与「回滚」两件事。
  tableSnapshot: { method: 'GET', path: '/v1/{prefix}/databases/{database}/tables/{table}/snapshot' },
  listSnapshots: { method: 'GET', path: '/v1/{prefix}/databases/{database}/tables/{table}/snapshots' },
  getSnapshotByVersion: { method: 'GET', path: '/v1/{prefix}/databases/{database}/tables/{table}/snapshots/{version}' },
  tableToken: { method: 'GET', path: '/v1/{prefix}/databases/{database}/tables/{table}/token' },
  tableAuth: { method: 'POST', path: '/v1/{prefix}/databases/{database}/tables/{table}/auth' },

  // ------------------------------------------------------------------ catalog API：tag / branch / partition
  listTags: { method: 'GET', path: '/v1/{prefix}/databases/{database}/tables/{table}/tags' },
  createTag: { method: 'POST', path: '/v1/{prefix}/databases/{database}/tables/{table}/tags' },
  getTag: { method: 'GET', path: '/v1/{prefix}/databases/{database}/tables/{table}/tags/{tag}' },
  dropTag: { method: 'DELETE', path: '/v1/{prefix}/databases/{database}/tables/{table}/tags/{tag}' },
  listBranches: { method: 'GET', path: '/v1/{prefix}/databases/{database}/tables/{table}/branches' },
  createBranch: { method: 'POST', path: '/v1/{prefix}/databases/{database}/tables/{table}/branches' },
  dropBranch: { method: 'DELETE', path: '/v1/{prefix}/databases/{database}/tables/{table}/branches/{branch}' },
  renameBranch: { method: 'POST', path: '/v1/{prefix}/databases/{database}/tables/{table}/branches/{branch}/rename' },
  forwardBranch: { method: 'POST', path: '/v1/{prefix}/databases/{database}/tables/{table}/branches/{branch}/forward' },
  listPartitions: { method: 'GET', path: '/v1/{prefix}/databases/{database}/tables/{table}/partitions' },

  // ------------------------------------------------------------------ catalog API：权限（Paimon 官方 management API）
  // 授权只有这一套模型：三元组 `(resource, access, principal)`，access 取官方 12 项。
  // grant / revoke 的资源定位在请求体里，因此不做成路径参数。
  listPermissions: { method: 'GET', path: '/v1/{prefix}/permissions' },
  // 控制台授权清单的一页：**已按「主体 × 作用范围」折叠**（一条赋权一处、一组一行），
  // 折叠在服务端做，因为先按赋权切页会把同一组切到两页上。`keyword` 也在服务端匹配。
  listPermissionScopes: { method: 'GET', path: '/v1/{prefix}/permissions/scopes' },
  grantPermission: { method: 'POST', path: '/v1/{prefix}/permissions/grant' },
  revokePermission: { method: 'POST', path: '/v1/{prefix}/permissions/revoke' },

  // ------------------------------------------------------------------ catalog API：view
  listViews: { method: 'GET', path: '/v1/{prefix}/databases/{database}/views' },
  createView: { method: 'POST', path: '/v1/{prefix}/databases/{database}/views' },
  getView: { method: 'GET', path: '/v1/{prefix}/databases/{database}/views/{view}' },
  dropView: { method: 'DELETE', path: '/v1/{prefix}/databases/{database}/views/{view}' },
  renameView: { method: 'POST', path: '/v1/{prefix}/views/rename' },

  // ------------------------------------------------------------------ catalog API：function
  listFunctions: { method: 'GET', path: '/v1/{prefix}/databases/{database}/functions' },
  createFunction: { method: 'POST', path: '/v1/{prefix}/databases/{database}/functions' },
  getFunction: { method: 'GET', path: '/v1/{prefix}/databases/{database}/functions/{function}' },
  dropFunction: { method: 'DELETE', path: '/v1/{prefix}/databases/{database}/functions/{function}' },

  // ------------------------------------------------------------------ catalog API：semantic view
  listSemanticViews: { method: 'GET', path: '/v1/{prefix}/databases/{database}/semantic-views' },
  getSemanticView: { method: 'GET', path: '/v1/{prefix}/databases/{database}/semantic-views/{semanticView}' },
  upsertSemanticView: { method: 'POST', path: '/v1/{prefix}/databases/{database}/semantic-views/{semanticView}' },
  dropSemanticView: { method: 'DELETE', path: '/v1/{prefix}/databases/{database}/semantic-views/{semanticView}' },

  // ------------------------------------------------------------------ catalog API：Iceberg 协议面
  // 同样是 `/v1/{prefix}` 基址，但资源是 `namespaces/**` 而不是 `databases/**`，
  // 报文也是 kebab-case（`partition-spec` / `next-page-token`）。两套路径按 prefix
  // 对应的 catalog 格式分派，控制台按页面选择其中一套，从不混用。
  //
  // 这些路径逐条对得上 `spec/rest-catalog-open-api-iceberg.yaml`，
  // 由 `verify-console.py` 第 1 组与服务端控制器双向比对。
  //
  // 有意**不**登记的三类：
  // · `plan` / `tasks` / `sign`：服务端不实现（见 `IcebergEndpoints` 的类注释），登记进来就是死条目；
  // · `POST .../tables/{table}`（提交 update）与 `POST /v1/{prefix}/transactions/commit`：
  //   请求体里是 metadata 代数相关的 `updates` / `requirements`，控制台拼不出一轮
  //   合法的提交——与 Paimon 侧不登记 `commitTable` 同一个理由；
  // · `HEAD` 两个存在性检查：控制台用不到（列表已经告诉它有没有），
  //   而 `client.js` 的动词糖只有 GET/POST/PUT/DELETE。
  icebergListNamespaces: { method: 'GET', path: '/v1/{prefix}/namespaces' },
  icebergCreateNamespace: { method: 'POST', path: '/v1/{prefix}/namespaces' },
  icebergDropNamespace: { method: 'DELETE', path: '/v1/{prefix}/namespaces/{namespace}' },
  icebergListTables: { method: 'GET', path: '/v1/{prefix}/namespaces/{namespace}/tables' },
  icebergCreateTable: { method: 'POST', path: '/v1/{prefix}/namespaces/{namespace}/tables' },
  icebergRegisterTable: { method: 'POST', path: '/v1/{prefix}/namespaces/{namespace}/register' },
  icebergLoadTable: { method: 'GET', path: '/v1/{prefix}/namespaces/{namespace}/tables/{table}' },
  icebergDropTable: { method: 'DELETE', path: '/v1/{prefix}/namespaces/{namespace}/tables/{table}' },
  // 与 Paimon 的 `renameTable` **同一条路径**，请求体不同（Iceberg 的 identifier 里
  // namespace 是数组）。两个面各有一个名字，是为了让调用点读得懂自己在调哪套契约；
  // 路径重复不影响 `verify-console.py` 的比对（它按 METHOD+path 做集合运算）。
  icebergRenameTable: { method: 'POST', path: '/v1/{prefix}/tables/rename' },
  // 只摘目录条目、不动仓库里的文件（规格里的 `unregister`，成功码 200 而不是 204）。
  icebergUnregisterTable: { method: 'POST', path: '/v1/{prefix}/namespaces/{namespace}/tables/{table}/unregister' },
  icebergListViews: { method: 'GET', path: '/v1/{prefix}/namespaces/{namespace}/views' },
  // 建视图（成功码 200）：请求体要 `name` + `schema` + `view-version` 两段元数据。
  icebergCreateView: { method: 'POST', path: '/v1/{prefix}/namespaces/{namespace}/views' },
  icebergLoadView: { method: 'GET', path: '/v1/{prefix}/namespaces/{namespace}/views/{view}' },
  icebergDropView: { method: 'DELETE', path: '/v1/{prefix}/namespaces/{namespace}/views/{view}' },
  // 登记仓库里已有的一份 ViewMetadata（成功码 200）。
  icebergRegisterView: { method: 'POST', path: '/v1/{prefix}/namespaces/{namespace}/register-view' },
  // 函数只读两个：写路径在 Paimon 面（`/v1/{prefix}/databases/{db}/functions…`），
  // 规格在 Iceberg 侧也只定义了这两个 GET。
  icebergListFunctions: { method: 'GET', path: '/v1/{prefix}/namespaces/{namespace}/functions' },
  icebergLoadFunction: { method: 'GET', path: '/v1/{prefix}/namespaces/{namespace}/functions/{function}' },

  // ------------------------------------------------------------------ Lance 协议面：浏览 + 建命名空间
  // 第三个协议面，**不在 `/v1/{prefix}` 下**：资源树是 `/lance/v1/namespace/{id}` 与
  // `/lance/v1/table/{id}`，`{id}` 是 `catalog$schema$table` 这样的拼接串（一段路径，
  // 不是三段）。因此这几条路径里的占位符叫 `id` 而不是 `prefix` / `namespace`。
  //
  // 路径与 `spec/lance-namespace-rest-openapi.yaml` 逐字一致（规格写的是 `/v1/...`，
  // 服务端把它挂在 `/lance` 前缀下；`verify-console.py` 第 1 组按这个前缀换算后比对）。
  //
  // **写端点只有一条：`create`（建命名空间）。**表那一侧的 declare / register /
  // deregister / rename / drop 一个都不登记——这台服务端对 Lance 没有建立数据的路径
  // （`docs/lance-rest-catalog-design.md` §3.2、§7），而命名空间是纯元数据登记，
  // 服务端确实实现了它（19 个 operation 里的 `CreateNamespace`）。
  lanceListNamespaces: { method: 'GET', path: '/lance/v1/namespace/{id}/list' },
  lanceListTables: { method: 'GET', path: '/lance/v1/namespace/{id}/table/list' },
  lanceDescribeTable: { method: 'POST', path: '/lance/v1/table/{id}/describe' },
  lanceCreateNamespace: { method: 'POST', path: '/lance/v1/namespace/{id}/create' },
  // 详情页的「版本」与「标签」两个页签。**两条都按 POST 登记**：规格里这两个
  // operation 定义的请求形态就是 POST（表标识在路径里，body 可空），服务端另外
  // 接了 `ListTableVersions` 的 GET 形态作为兼容扩展，但控制台不往扩展上靠——
  // 走规格里的那一条，换一个实现也用得上。
  //
  // 版本写路径（`version/create`、`version/delete`）与标签写路径一个都不登记：
  // 控制台对 Lance 是只读的（见 `api/lance.js` 的类注释），登记了就有死条目
  // （`verify-console.py` 第 4 组会响）。
  lanceListVersions: { method: 'POST', path: '/lance/v1/table/{id}/version/list' },
  lanceListTags: { method: 'POST', path: '/lance/v1/table/{id}/tags/list' },

  // ------------------------------------------------------------------ 管理 API：catalog
  listCatalogs: { method: 'GET', path: '/api/management/v1/catalogs' },
  createCatalog: { method: 'POST', path: '/api/management/v1/catalogs' },
  getCatalog: { method: 'GET', path: '/api/management/v1/catalogs/{catalogName}' },
  updateCatalog: { method: 'PUT', path: '/api/management/v1/catalogs/{catalogName}' },
  deleteCatalog: { method: 'DELETE', path: '/api/management/v1/catalogs/{catalogName}' },
  // 控制台「存储」页的两个聚合端点（服务端扩展）：汇总一次算完 KPI/按库/Top10/直方图，
  // 逐表明细远程分页（分页/过滤/排序下推 SQL），替代逐表的 snapshot / loadTable。
  catalogTableStats: { method: 'GET', path: '/api/management/v1/catalogs/{catalogName}/table-stats' },
  catalogTableRows: { method: 'GET', path: '/api/management/v1/catalogs/{catalogName}/table-stats/tables' },
  // ------------------------------------------------------------------ 执行集群（优化任务提交到哪里）
  // 六个端点都挂在 catalog 下，**没有独立的一级资源**：「这个 catalog 的执行集群」
  // 是 catalog 的一个属性（一行一 catalog），既不需要「列出全部执行集群」，
  // 也不存在「不属于任何 catalog 的执行集群」。因此控制台不多一个菜单项。
  //
  // 读接口**永远不回凭据明文**（kubeconfig / secretAccessKey / clientSecret /
  // catalogToken 都只回 `has*` 布尔与可公开标识）；写接口里这些字段「留空 = 保持不变」，
  // 要清空只有 catalogToken 需要开关（`clearCatalogToken`）：它是可选的；
  // 另外四个秘密是建客户端的必需材料，清空等于删掉整份配置。
  // 设计见 docs/catalog-execution-cluster-design.md §8。
  getExecutionCluster: { method: 'GET', path: '/api/management/v1/catalogs/{catalogName}/execution-cluster' },
  updateExecutionCluster: { method: 'PUT', path: '/api/management/v1/catalogs/{catalogName}/execution-cluster' },
  deleteExecutionCluster: { method: 'DELETE', path: '/api/management/v1/catalogs/{catalogName}/execution-cluster' },
  // 只解析、不落库：表单里粘完 kubeconfig 立刻能看到「每个 context 能不能用」，
  // 不必先保存失败一次才知道。exec: / auth-provider: 两种认证会被硬拒（服务端执行
  // 本地命令 = RCE 边界），因此这两个结论必须在这一步就出现。
  parseExecutionClusterKubeconfig: { method: 'POST', path: '/api/management/v1/catalogs/{catalogName}/execution-cluster/kubeconfig/parse' },
  // 五问探活。`query.full=false` 只做第 1–3 步（凭据、身份、权限），
  // 不 dry-run 建 Job——调度扫描走的就是这一档。
  probeExecutionCluster: { method: 'POST', path: '/api/management/v1/catalogs/{catalogName}/execution-cluster/probe' },

  // ------------------------------------------------------------------ 管理 API：principal
  listPrincipals: { method: 'GET', path: '/api/management/v1/principals' },
  createPrincipal: { method: 'POST', path: '/api/management/v1/principals' },
  getPrincipal: { method: 'GET', path: '/api/management/v1/principals/{principalName}' },
  updatePrincipal: { method: 'PUT', path: '/api/management/v1/principals/{principalName}' },
  deletePrincipal: { method: 'DELETE', path: '/api/management/v1/principals/{principalName}' },
  rotatePrincipal: { method: 'POST', path: '/api/management/v1/principals/{principalName}/rotate' },
  resetPrincipal: { method: 'POST', path: '/api/management/v1/principals/{principalName}/reset' },
  listPrincipalRolesOfPrincipal: { method: 'GET', path: '/api/management/v1/principals/{principalName}/principal-roles' },
  grantPrincipalRole: { method: 'PUT', path: '/api/management/v1/principals/{principalName}/principal-roles' },
  revokePrincipalRole: { method: 'DELETE', path: '/api/management/v1/principals/{principalName}/principal-roles/{principalRoleName}' },

  // ------------------------------------------------------------------ 管理 API：principal role
  // 纯分组。它自己不带任何权限：授予 `role:<name>` 的赋权由服务端按成员关系转发给成员，
  // 因此这里没有「给角色挂 catalog 角色」这一组端点——catalog role 已整体移除。
  listPrincipalRoles: { method: 'GET', path: '/api/management/v1/principal-roles' },
  createPrincipalRole: { method: 'POST', path: '/api/management/v1/principal-roles' },
  getPrincipalRole: { method: 'GET', path: '/api/management/v1/principal-roles/{principalRoleName}' },
  updatePrincipalRole: { method: 'PUT', path: '/api/management/v1/principal-roles/{principalRoleName}' },
  deletePrincipalRole: { method: 'DELETE', path: '/api/management/v1/principal-roles/{principalRoleName}' },
  listPrincipalsOfPrincipalRole: { method: 'GET', path: '/api/management/v1/principal-roles/{principalRoleName}/principals' },

  // ------------------------------------------------------------------ 管理 API：表存储优化
  // 挂管理 API 而不是 catalog API：优化策略与批次是**服务端自己的资源**，
  // 不是 Paimon 元数据的一部分（引擎不需要也不该看到它们）。
  //
  // 路径里的 catalog 一律是**名字**（即 prefix），不是内部主键——优化任务拿它去建
  // REST catalog 连接，服务端的权限判定也按名字走。
  //
  // 有意**不**包含优化任务的两个回调端点（`GET /jobs/{id}/submission`、
  // `POST /jobs/{id}/items/{itemId}`）：它们由 k8s 里的任务带着批次令牌调用，
  // 控制台既不是调用方，也拿不到那个令牌，列在这里只会变成永远没人调的死条目。
  optimizationSettings: { method: 'GET', path: '/api/management/v1/optimization/catalogs/{catalogName}/settings' },
  updateOptimizationSettings: { method: 'PUT', path: '/api/management/v1/optimization/catalogs/{catalogName}/settings' },
  tableOptimizationPolicy: { method: 'GET', path: '/api/management/v1/optimization/tables/{catalogName}/{databaseName}/{tableName}/policy' },
  updateTableOptimizationPolicy: { method: 'PUT', path: '/api/management/v1/optimization/tables/{catalogName}/{databaseName}/{tableName}/policy' },
  runTableOptimization: { method: 'POST', path: '/api/management/v1/optimization/tables/{catalogName}/{databaseName}/{tableName}/run' },
  // 单表历史。与批次列表分开：列表按设计不带表级明细，前端要拼出「这张表最近几次
  // 跑成什么样」得逐个批次拉详情，那是 N 次请求换一屏数据。
  tableOptimizationHistory: { method: 'GET', path: '/api/management/v1/optimization/tables/{catalogName}/{databaseName}/{tableName}/items' },
  listOptimizationJobs: { method: 'GET', path: '/api/management/v1/optimization/jobs' },
  getOptimizationJob: { method: 'GET', path: '/api/management/v1/optimization/jobs/{jobId}' },
  // 重试只重跑失败的表，因此它会**新建**一个批次（返回 201 与新的批次号，`retryOf` 指向原批次）。
  retryOptimizationJob: { method: 'POST', path: '/api/management/v1/optimization/jobs/{jobId}/retry' },
  cancelOptimizationJob: { method: 'POST', path: '/api/management/v1/optimization/jobs/{jobId}/cancel' },

  // ------------------------------------------------------------------ 管理 API：血缘
  // 挂管理 API 而不是 catalog API：血缘是**跨 catalog** 的关系（prefix 只是其中一个维度），
  // 而 catalog API 的每个端点都必须在 `CatalogAccessRules` 登记权限映射——
  // 血缘不是「表上的操作」，不该挤进那张表（`LineageController` 的类注释有完整理由）。
  //
  // 三条都是 GET、都按 `ref`（`catalog.namespace.table`）寻址，区别只在粒度：表级结构给图，
  // 列级映射给「列映射」表格，批量列边给图上的「列级」开关。分成三条是因为合起来会让边
  // 的数量变成「表数 × 列数」；column-edges 的节点集合由调用方给（§8.7，图已被过滤/展开过，
  // 服务端按根重算对不上）。
  //
  // 有意**不**包含的三条：`POST /lineage/runs` 是引擎侧上报入口（控制台手工上报等于
  // 让人写一条假依赖），`GET /lineage/runs` 与 `/runs/{runId}` 目前没有界面在用它们。
  lineageTables: { method: 'GET', path: '/api/management/v1/lineage/tables' },
  lineageColumns: { method: 'GET', path: '/api/management/v1/lineage/columns' },
  lineageColumnEdges: { method: 'GET', path: '/api/management/v1/lineage/column-edges' },

  // ------------------------------------------------------------------ 管理 API：数据策略（行过滤 / 列脱敏）
  // 本工程自己的资源（`DataPolicyController`）：一张表最多一条策略，按三段名寻址。
  // 引擎侧的 Spark 扩展与 SQL 语句（§13）读写的是同一个对象，这里只是第三个入口。
  //
  // **PUT 是整份替换**：行过滤、mask 列表、豁免名单、主体白名单以提交为准——
  // 「这次少了一列 mask」= 删掉那条 mask，不是「不动它」。新建（表上还没有策略）时
  // 不能带 baseVersion；更新必须带当前版本，不匹配回 409（乐观并发，不自动重试）。
  getTablePolicy: { method: 'GET', path: '/api/management/v1/policies/tables/{catalogName}/{databaseName}/{tableName}' },
  updateTablePolicy: { method: 'PUT', path: '/api/management/v1/policies/tables/{catalogName}/{databaseName}/{tableName}' },
  deleteTablePolicy: { method: 'DELETE', path: '/api/management/v1/policies/tables/{catalogName}/{databaseName}/{tableName}' },
  // 策略总览（mockup 画面 1）：跨表列表。判权在控制器——带 catalog 过滤要 CATALOG:DESCRIBE，
  // 全 catalog 总览要服务管理员；`query` 里走 `catalog` / `refTable` / 分页。
  listPolicies: { method: 'GET', path: '/api/management/v1/policies' },
  // 主体视角试算（mockup 画面 4）：按请求体里的 principal（不是当前调用者）判定
  // 策略四态并用预览求值链读一页数据。判权 TABLE:ALTER——试算结果本身就是策略内容。
  // 只读、不落库：唯一副作用是预览自身的 TABLE_PREVIEWED 审计（操作者本人）。
  dryRunTablePolicy: { method: 'POST', path: '/api/management/v1/policies/tables/{catalogName}/{databaseName}/{tableName}/dry-run' },
}

/** 端点名 → `METHOD path`，供校验脚本与调试输出复用。 */
export function describe(name) {
  const endpoint = ENDPOINTS[name]
  if (!endpoint) throw new Error(`unknown endpoint: ${name}`)
  return `${endpoint.method} ${endpoint.path}`
}
