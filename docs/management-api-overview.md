# Management API 概览

管理面分成两半，边界是「官方规范了什么」：

| 半边 | 基址 | 依据 | operation |
| --- | --- | --- | --- |
| **权限端点**（官方） | `/v1/{prefix}/permissions` | Paimon REST Management API 的 Permission model | 3 |
| **扩展端点**（本服务端） | `/api/management/v1/**` | catalog / principal / principal role 的生命周期 + 存储优化 + 表统计聚合 | 35 |

官方 Paimon 管理 API 只有 10 个 operation（labels / permissions / policies），其中
labels 与 policies 本服务端未实现；权限那 3 个是**当前唯一的契约**，规格见
`spec/paimon-rest-management-api.yaml`。

> **已不是契约的文件。** `spec/polaris-management-service.yml` 与
> `docs/management-api-contract.md` 保留为**形状来源与历史对照**：catalog / principal /
> principal role 这三组扩展端点的报文形状沿用了它，但里面的 catalog role、grants、
> 64 项 privilege 都已不存在。阅读实际行为请看本文与
> [`authorization.md`](authorization.md)。

| 想知道什么 | 看哪份 |
| --- | --- |
| 有哪些端点、分成几类 | 本文 |
| 权限怎么判定、12 项 access 怎么落 | [`authorization.md`](authorization.md) |
| 用 SQL 而不是 HTTP 调这套接口 | [`spark-sql-reference.md`](spark-sql-reference.md) |
| catalog API 的 60 个端点 | [`catalog-api-endpoints.md`](catalog-api-endpoints.md) |

## 1. 控制器划分

| 控制器 | operation | 覆盖 |
| --- | --- | --- |
| `PermissionController` | 3 | **官方**：列赋权、授予、撤销（`/v1/{prefix}/permissions**`）。另有 1 条**控制台自己的**聚合视图 `GET /permissions/scopes`（折叠 + 分页，见 §2 最后一条） |
| `PrincipalController` | 10 | 主体的增删改查、`reset` / `rotate` 凭据、主体 ↔ principal role 装配 |
| `PrincipalRoleController` | 6 | principal role 的增删改查、其下的主体查询 |
| `ManagementCatalogController` | 7 | 管理面的 catalog 列表、创建、查询、更新、删除，以及存储页的两个聚合端点：`GET /catalogs/{catalogName}/table-stats`（KPI / 按库 / Top 10 / 直方图 SQL 聚合一次算完，全量；可选 `database` 参数只下钻 KPI / Top 10 / 直方图，按库聚合不随它过滤）与 `GET …/table-stats/tables`（逐表明细远程分页，按库 / 关键字 / onlyPaimon 过滤与排序下推 SQL，`maxResults` 默认 2000 / 上限 5000）。替代控制台逐表 snapshot / loadTable 的扫描 |
| `OptimizationController` | 12 | 存储优化的 catalog 设置、表策略、批次与回调 |

`PrincipalRoleController` 从 9 降到 6：`listCatalogRolesOfPrincipalRole`、
`grantCatalogRole`、`revokeCatalogRole` 三条随 catalog role 一起删除。

## 2. 关键语义

详细说明与设计理由见 [`authorization.md`](authorization.md)。

- **授权只有一套**：官方三元组 `(resource, access, principal)`，落在
  `lakehouse_permission_assignment`。principal role 是**纯分组**，自己不携带权限。
- **不做权限蕴含**：只有 `ALL` 是聚合值（「All accesses applicable to the resource」），
  其余一律要求相等——官方没有给出 `ALTER` 含 `SELECT` 这种关系。
- **两个 `_ALL` 是后代作用域**：`CATALOG_ALL` / `DATABASE_ALL` 覆盖后代，
  **不含容器本身**。要改 catalog 属性要 `CATALOG:ALTER`，要建库要 `CATALOG:CREATEDATABASE`。
- **乐观并发**：`PUT` 请求携带 `currentEntityVersion`，与当前版本不符时返回 `409`。
- **状态码**：创建与授予类 `201`，`PUT` 更新与 `reset` / `rotate` 为 `200`，删除为 `204`，
  无权限 `403`，不存在 `404`，版本冲突 `409`。
- **响应形状**：单体资源接口大多返回**裸对象**，只有主体相关接口返回
  `{"principal":{…},"credentials":{…}}`；列表接口是具名数组。这是最容易读错的一处，
  实测结论见 [`spark-sql-extension.md`](spark-sql-extension.md) 第 3.3 节。
- **列表端点统一支持页码模式**：`catalog` / `principal` / `principal role` /
  优化批次这几个列表都认 `page` + `pageSize`（1-based，默认 20、上限 200），
  响应多一个 `page: { total, page, pageSize, hasNext }`；不传就还是老行为（一次给全量，
  `page` 为 `null`）。约定与取舍见
  [`catalog-api-endpoints.md`](catalog-api-endpoints.md) 的「页码模式」一节。
  存储页的逐表明细 `GET …/table-stats/tables` 同样两种翻法并存（页码模式默认 20、
  上限 200；`maxResults` + `pageToken` 默认 2000、上限 5000），两种都在 SQL 里
  `limit/offset` 截断。
  **先过滤再切页**：可见性（catalog 授权、优化批次按 catalog 授权）逐条判定，
  收齐全量才切，否则 `total` 会把不可见资源的数量泄露出去。
- **授权清单的「一行」是服务端给的**：`GET /v1/{prefix}/permissions/scopes` 把
  「同主体 × 同作用范围」的多条赋权折叠成一行，再切页。折在切页之后是不行的——
  同一组会被页边界劈成两行，界面上同一个主体出现两次。这是控制台视图，不是官方契约：
  官方那 3 个端点只给平铺的逐条赋权。
- **catalog 详情的读权限落在 `CATALOG_ALL:DESCRIBE`**：官方对照表里 `CATALOG` 只有
  `ALL / ALTER / DROP / GRANT / CREATEDATABASE`，没有任何读权限。
- **存储配置支持六种类型**。`storageConfigInfo` 是按 `storageType` 判别的联合，
  Java 侧也按子类型建模（`S3` / `AZURE` / `GCS` / `OBS` / `OSS` / `FILE` 各有自己的字段集合），
  非法组合在类型层面就不成立。`AZURE` 的 `tenantId` 按 `required` 校验（缺失返回 400）；
  `S3` 的废弃字段 `currentKmsKey` / `allowedKmsKeys` 不接收也不返回。
  其中 `OBS`（华为云）与 `OSS`（阿里云）是本工程的扩展：规格把这两家云归入
  「兼容 S3 协议的对象存储」，单列它们是为了让凭据下发能产出厂商原生的
  `fs.obs.*` / `fs.oss.*` 键族。
  落库时整份配置存进一列 JSON，`storage_type` 与 `allowed_locations_json` 是它的投影列。
  `allowedLocations` 的首项即该 catalog 的仓库位置，表路径由它推导。

## 3. 覆盖率与契约校验

- `scripts/management-sweep.sh` 对着运行中的实例跑遍扩展端点（catalog / principal /
  principal role / 权限 / 优化）。
- 权限端点的语义由三个 Java 测试类守住，而不是只对着文档比对：
  - `AuthorizationTests` —— 判定本身（作用域、`ALL`、过期、撤销、principal role 转发）；
  - `CatalogEndpointAuthorizationTests` —— 映射表与运行时端点集合一致（失败关闭）；
  - `PermissionManagementTests` —— 权限端点的增删查、词表校验、分页与过滤。
- `scripts/verify-management-contract.py` 仍在校验 `docs/management-api-contract.md`
  与 Polaris 规格逐行一致——那是**存档文档**的自洽性，不再代表服务端行为。
