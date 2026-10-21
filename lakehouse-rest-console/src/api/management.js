import { del, get, post, put } from './client.js'

/**
 * 管理 API（`/api/management/v1/**`）的调用面。
 *
 * <p>catalog / principal / principal role 的生命周期是**服务端扩展**：官方 Paimon
 * management API 把这三者看作部署配置，只规范了权限端点（见 `permission.js`）。
 * 因此这里只留生命周期操作，凡是「谁能读什么」都走 `permission.js`——catalog role
 * 那套 RBAC 已整体移除，本文件不再有与之相关的调用。
 *
 * <p>三个容易踩的点，都在这里按服务端的实际约定固化下来：
 *
 * 1. **创建请求要包一层**。`POST /principals` 的请求体是
 *    `{principal: {...}}` 而不是裸的 `Principal`，catalog 与两个角色同理。
 * 2. **角色装配的增删动词是反直觉的**：`PUT` 新增、`DELETE` 撤销（两者都返回 201）。
 * 3. **更新要带 `currentEntityVersion`**，版本不匹配时服务端返回 409——
 *    这是并发保护，控制台不能省掉这个字段。
 */
export const managementApi = {
  // ------------------------------------------------------------------ catalog
  /**
   * catalog 列表。`query` 给 `page` / `pageSize` / `format` —— 控制台的表格走远程分页。
   *
   * <p>`query` 必须写在 `params` 里（`{ params: { query } }`）：拼 URL 的
   * {@code buildPath} 只读 `params.query`，写在顶层是**看不见 fail**的——
   * 请求照样发出去、照样 200，只是服务端没收到任何过滤与分页参数。
   */
  listCatalogs: (query = {}) => get('listCatalogs', { params: { query } }),
  createCatalog: (catalog) => post('createCatalog', { body: { catalog } }),
  getCatalog: (catalogName) => get('getCatalog', { params: { catalogName } }),
  updateCatalog: (catalogName, body) => put('updateCatalog', { params: { catalogName }, body }),
  deleteCatalog: (catalogName) => del('deleteCatalog', { params: { catalogName } }),
  // 存储页的两个聚合端点（服务端扩展，非官方规格）。
  // 汇总：KPI / 按库 / Top 10 / 直方图服务端一次算完；`query.onlyPaimon` 过滤 format 类表。
  getCatalogTableStats: (catalogName, query = {}) => get('catalogTableStats', {
    params: { catalogName, query },
  }),
  // 逐表明细的一页（远程分页）：`query` 给 database / keyword / onlyPaimon / sort / order /
  // page / pageSize；`total` 是过滤后的总行数。两条都 DOM 里看不见 fail，
  // 因此 `query` 只能写在 `params` 里（见 `listCatalogs` 的说明）。
  listCatalogTableRows: (catalogName, query = {}) => get('catalogTableRows', {
    params: { catalogName, query },
  }),

  // ------------------------------------------------------------------ principal
  /** 主体列表。`query` 给 `page` / `pageSize`。 */
  listPrincipals: (query = {}) => get('listPrincipals', { params: { query } }),
  createPrincipal: (principal, credentialRotationRequired) => post('createPrincipal', {
    body: { principal, credentialRotationRequired },
  }),
  getPrincipal: (principalName) => get('getPrincipal', { params: { principalName } }),
  updatePrincipal: (principalName, body) => put('updatePrincipal', { params: { principalName }, body }),
  deletePrincipal: (principalName) => del('deletePrincipal', { params: { principalName } }),
  rotatePrincipal: (principalName) => post('rotatePrincipal', { params: { principalName } }),
  resetPrincipal: (principalName, body) => post('resetPrincipal', { params: { principalName }, body }),
  listPrincipalRolesOfPrincipal: (principalName, query = {}) => get('listPrincipalRolesOfPrincipal', {
    params: { principalName, query },
  }),
  grantPrincipalRole: (principalName, principalRole) => put('grantPrincipalRole', {
    params: { principalName },
    body: { principalRole },
  }),
  revokePrincipalRole: (principalName, principalRoleName) => del('revokePrincipalRole', {
    params: { principalName, principalRoleName },
  }),

  // ------------------------------------------------------------------ principal role
  /** 角色列表。`query` 给 `page` / `pageSize`。 */
  listPrincipalRoles: (query = {}) => get('listPrincipalRoles', { params: { query } }),
  createPrincipalRole: (principalRole) => post('createPrincipalRole', { body: { principalRole } }),
  getPrincipalRole: (principalRoleName) => get('getPrincipalRole', { params: { principalRoleName } }),
  updatePrincipalRole: (principalRoleName, body) => put('updatePrincipalRole', {
    params: { principalRoleName },
    body,
  }),
  deletePrincipalRole: (principalRoleName) => del('deletePrincipalRole', { params: { principalRoleName } }),
  listPrincipalsOfPrincipalRole: (principalRoleName, query = {}) => get('listPrincipalsOfPrincipalRole', {
    params: { principalRoleName, query },
  }),
}

/** 控制台元数据端点。非 Polaris 规格，见服务端 `ConsoleMetaController`。 */
export const metaApi = {
  meta: () => get('meta'),
}
