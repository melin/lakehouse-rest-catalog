import { post, get } from './client.js'

/**
 * Paimon 官方 management API 权限端点的调用面（`/v1/{prefix}/permissions*`）。
 *
 * <p>授权直接面向 principal——不透明字符串，约定形如 `user:alice`、`role:sales_writer`、
 * `group:xx`；资源用 `{type, database, table, ...}` 定位器表达，access 用官方词表
 * （SELECT / UPDATE / ALTER / DROP / DESCRIBE / CREATETABLE / ...）。
 * 赋权身份是三元组 `(resource, access, principal)`：重复授予覆盖过期时间，
 * 撤销幂等（服务端语义，见 `PermissionManagementService`）。
 *
 * <p>与 `management.js` 的 Polaris 授权（挂在 catalog role 上）是两套并行模型：
 * 服务端判定时两路来源取并集。
 */
export const permissionApi = {
  /**
   * 列出直授。过滤参数全部可选；分页参数 `maxResults` / `pageToken`。
   * 返回 `{assignments: [...], nextPageToken}`。
   */
  list: (prefix, query = {}) => get('listPermissions', { params: { prefix, query } }),

  /**
   * 授予。`assignment` 形如
   * `{resource: {type, database, table}, access, principal, expireTime?}`。
   */
  grant: (prefix, assignment) => post('grantPermission', { params: { prefix }, body: assignment }),

  /** 撤销：形状与 grant 相同（过期时间不属于身份，传不传都不影响定位）。 */
  revoke: (prefix, assignment) => post('revokePermission', { params: { prefix }, body: assignment }),
}
