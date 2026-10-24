import { del, get, post, put } from './client.js'

/**
 * 数据策略（`/api/management/v1/policies/**`）的调用面：行过滤 + 列脱敏 +
 * 豁免名单 + 主体白名单，一张表最多一条。
 *
 * <p>与 `optimization.js` 同理单独成模块：策略是本工程自己的扩展资源
 * （服务端 `DataPolicyController`），任何规格里都没有它，不该混进管理规格的镜像。
 *
 * <p>三个与服务端语义对齐的约定，写错的表现各不相同：
 *
 * 1. **PUT 是整份替换**（`DataPolicyService.upsert` 的注释原文：「提交完整策略」）。
 *    控制台永远提交整份草稿——行过滤、mask 列表、豁免名单、主体白名单，
 *    「这次少了一列」就是删掉那一列，不是「保持原样」。
 * 2. **新建不带 `baseVersion`，更新必须带**。表上还没有策略时带版本会被拒
 *    （期望 0）；更新时版本不匹配回 409——乐观并发，控制台**不自动重试**，
 *    提示用户刷新后重看最新版本再决定怎么改（与 Spark SQL 侧 §13.3 同一取舍）。
 * 3. **表上没有策略时 GET 回 204**（空响应体），客户端把它归一化成 `{}`——
 *    用 `detail.table` 区分「没有策略」与「有策略」。
 */
export const policyApi = {
  /** 读一张表的策略。没有策略时返回 `null`（204 归一化）。 */
  get: (catalogName, databaseName, tableName) => get('getTablePolicy', {
    params: { catalogName, databaseName, tableName },
  }),

  /**
   * 写一张表的策略（整份替换）。
   *
   * <p>`body` 形状与服务端 `PolicyUpsertRequest` 逐字对齐：
   * `{baseVersion?, enabled, rowFilter?, masks: [{column, mask, allowNullOnRequired?}],
   * exemptPrincipals: [], subjects: []}`。`rowFilter` 为 `null` 表示「不要行过滤」——
   * 整份替换语义下没有「省略 = 保留」。
   */
  put: (catalogName, databaseName, tableName, body) => put('updateTablePolicy', {
    params: { catalogName, databaseName, tableName },
    body,
  }),

  /** 删除整条策略（物理删除）。与 SQL 侧 `DROP POLICY` 不同，REST 通道不需要确认开关——确认由控制台自己做。 */
  delete: (catalogName, databaseName, tableName) => del('deleteTablePolicy', {
    params: { catalogName, databaseName, tableName },
  }),

  /**
   * 策略总览（mockup 画面 1）。`query` 可带 `catalog`（不带 = 全 catalog，
   * 需要服务管理员）、`refTable`（按表名反查）与 `page` / `pageSize`。
   * 每行带 `rowFilterSql` 与 `maskColumns`——总览页把条件平铺成 chip，
   * 治理员不点进每张表就能排查悬空策略。
   */
  list: (query = {}) => get('listPolicies', { params: { query } }),

  /**
   * 主体视角试算（mockup 画面 4）：按 `principal`（不是当前登录者）判定策略
   * 四态（NONE / DISABLED / EXEMPT / FOUND），命中时 `preview` 是已按该主体
   * 求值的预览响应（行被过滤、敏感列被 mask）。试算只读、不落库。
   */
  dryRun: (catalogName, databaseName, tableName, principal, preview = null) =>
    post('dryRunTablePolicy', {
      params: { catalogName, databaseName, tableName },
      body: { principal, preview },
    }),
}
