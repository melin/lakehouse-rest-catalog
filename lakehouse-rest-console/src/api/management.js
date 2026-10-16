import { del, get, post, put } from './client.js'

/**
 * 管理 API（`/api/management/v1/**`）的调用面。
 *
 * <p>规格见 `spec/polaris-management-service.yml`，字段名与规格逐字一致。
 * 三个容易踩的点，都在这里按服务端的实际约定固化下来：
 *
 * 1. **创建请求要包一层**。`POST /principals` 的请求体是
 *    `{principal: {...}}` 而不是裸的 `Principal`，catalog 与两个角色同理。
 * 2. **授权的增删动词是反直觉的**：`PUT` 新增、`POST` 撤销（与服务端
 *    `CatalogRoleController` 的映射一致，两者都返回 201）。
 * 3. **更新要带 `currentEntityVersion`**，版本不匹配时服务端返回 409——
 *    这是并发保护，控制台不能省掉这个字段。
 */
export const managementApi = {
  // ------------------------------------------------------------------ catalog
  listCatalogs: () => get('listCatalogs'),
  createCatalog: (catalog) => post('createCatalog', { body: { catalog } }),
  getCatalog: (catalogName) => get('getCatalog', { params: { catalogName } }),
  updateCatalog: (catalogName, body) => put('updateCatalog', { params: { catalogName }, body }),
  deleteCatalog: (catalogName) => del('deleteCatalog', { params: { catalogName } }),

  // ------------------------------------------------------------------ principal
  listPrincipals: () => get('listPrincipals'),
  createPrincipal: (principal, credentialRotationRequired) => post('createPrincipal', {
    body: { principal, credentialRotationRequired },
  }),
  getPrincipal: (principalName) => get('getPrincipal', { params: { principalName } }),
  updatePrincipal: (principalName, body) => put('updatePrincipal', { params: { principalName }, body }),
  deletePrincipal: (principalName) => del('deletePrincipal', { params: { principalName } }),
  rotatePrincipal: (principalName) => post('rotatePrincipal', { params: { principalName } }),
  resetPrincipal: (principalName, body) => post('resetPrincipal', { params: { principalName }, body }),
  listPrincipalRolesOfPrincipal: (principalName) => get('listPrincipalRolesOfPrincipal', {
    params: { principalName },
  }),
  grantPrincipalRole: (principalName, principalRole) => put('grantPrincipalRole', {
    params: { principalName },
    body: { principalRole },
  }),
  revokePrincipalRole: (principalName, principalRoleName) => del('revokePrincipalRole', {
    params: { principalName, principalRoleName },
  }),

  // ------------------------------------------------------------------ principal role
  listPrincipalRoles: () => get('listPrincipalRoles'),
  createPrincipalRole: (principalRole) => post('createPrincipalRole', { body: { principalRole } }),
  getPrincipalRole: (principalRoleName) => get('getPrincipalRole', { params: { principalRoleName } }),
  updatePrincipalRole: (principalRoleName, body) => put('updatePrincipalRole', {
    params: { principalRoleName },
    body,
  }),
  deletePrincipalRole: (principalRoleName) => del('deletePrincipalRole', { params: { principalRoleName } }),
  listPrincipalsOfPrincipalRole: (principalRoleName) => get('listPrincipalsOfPrincipalRole', {
    params: { principalRoleName },
  }),
  listCatalogRolesOfPrincipalRole: (principalRoleName, catalogName) => get('listCatalogRolesOfPrincipalRole', {
    params: { principalRoleName, catalogName },
  }),
  grantCatalogRole: (principalRoleName, catalogName, catalogRole) => put('grantCatalogRole', {
    params: { principalRoleName, catalogName },
    body: { catalogRole },
  }),
  revokeCatalogRole: (principalRoleName, catalogName, catalogRoleName) => del('revokeCatalogRole', {
    params: { principalRoleName, catalogName, catalogRoleName },
  }),

  // ------------------------------------------------------------------ catalog role
  //
  // 只读。控制台不再提供 catalog 角色的管理与授权（那部分留给管理 API 与 Spark SQL）：
  // 这一条被授权表单的 `role:` 候选、以及角色页的「授予 catalog 角色」共用。
  listCatalogRoles: (catalogName) => get('listCatalogRoles', { params: { catalogName } }),
}

/** 控制台元数据端点。非 Polaris 规格，见服务端 `ConsoleMetaController`。 */
export const metaApi = {
  meta: () => get('meta'),
}
