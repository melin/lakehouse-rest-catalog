import { get, post, put } from './client.js'

/**
 * 表存储优化（`/api/management/v1/optimization/**`）的调用面。
 *
 * <p>单独一个模块而不是并进 `management.js`：那一个是 Polaris 管理规格的镜像
 * （字段名与 `spec/polaris-management-service.yml` 逐字一致），而优化是本工程自己
 * 加的扩展——挂在管理 API 的基址下，但两份规格里都没有它。混在一起会让
 * 「哪些接口是上游规格的」变得看不出来，改规格的时候也就说不清哪些能动。
 *
 * <p>路径里的 catalog 一律是**名字**（即 prefix），不是内部主键：
 * 优化任务拿它去建 REST catalog 连接，服务端权限判定也按名字走。
 *
 * <p>两个回调端点（执行清单与逐表上报）**不在这里**：调用方是 k8s 里的任务，
 * 它带着批次令牌，控制台既不调用也没有那个令牌。
 */
export const optimizationApi = {
  // ------------------------------------------------------------------ catalog 级默认设置
  /**
   * 读 catalog 级优化默认值。
   *
   * <p>响应里同时有 `properties`（原样的 `optimization.*` 属性）与 `defaults`
   * （拆好的三项动作：差异 + 生效值）。页面只读前者决定表单里显示什么，
   * 用后者显示「不改的话实际会用什么参数」——合并规则只有服务端一份。
   */
  settings: (catalogName) => get('optimizationSettings', { params: { catalogName } }),

  /**
   * 写 catalog 级默认值。
   *
   * <p>逐键合并：只提交改动过的键，空值表示清除该键（回落内置默认）。
   * 因此调用方**只提交改动的键**，不要整份 `properties` 回填——那会把别人
   * 手工写进 catalog 属性的其它键一起带上，而更新走的是乐观并发
   * （`entityVersion` 不匹配时 409），回填越全越容易撞冲突。
   */
  updateSettings: (catalogName, properties) => put('updateOptimizationSettings', {
    params: { catalogName },
    body: { properties },
  }),

  // ------------------------------------------------------------------ 表策略
  /**
   * 读一张表的策略。
   *
   * <p>`supported` / `unsupportedReason` 说的是这张表**能不能**开优化
   * （非 Paimon 表、外部表不行），与 `enabled`（用户开没开）是两件事。
   * 两项动作的 `config` 是用户显式写过的**差异**，`effective` 是三层合并后的
   * 实际取值，`usingDefaults` = 差异为空。
   */
  policy: (catalogName, databaseName, tableName) => get('tableOptimizationPolicy', {
    params: { catalogName, databaseName, tableName },
  }),

  /**
   * 写表策略。逐字段可选：没提到的动作完全不动，`config` 为 `null` 表示保持原样、
   * 空对象表示清空回默认。控制台按「一张动作卡一次保存」的方式调用它，
   * 不整表提交——两个人同时在两张卡上改，整表提交会让后保存的那次静默覆盖前一次。
   */
  updatePolicy: (catalogName, databaseName, tableName, body) => put('updateTableOptimizationPolicy', {
    params: { catalogName, databaseName, tableName },
    body,
  }),

  /**
   * 立即优化一张表。
   *
   * <p>不传动作就跑该表所有已启用的动作。手动执行**不受总开关约束**
   * （开关管的是「要不要定期自动做」），但指定的动作必须是已启用的，否则服务端 400。
   * 服务端返回**新建的批次**（201），批次要等下一个提交节拍才真正投到 k8s。
   */
  run: (catalogName, databaseName, tableName, actions) => post('runTableOptimization', {
    params: { catalogName, databaseName, tableName },
    body: actions && actions.length ? { actions } : {},
  }),

  /** 单表的优化历史（表详情页三块历史），最近的在前，最多 100 条。 */
  history: (catalogName, databaseName, tableName) => get('tableOptimizationHistory', {
    params: { catalogName, databaseName, tableName },
  }),

  // ------------------------------------------------------------------ 批次
  /**
   * 批次列表，最近的在前。
   *
   * @param {object} 过滤条件。`catalog` 是 catalog 名；`status` 取批次状态枚举名；
   *   `action` 取动作 wire 名（`compact` / `expire` / `orphan`）——**不是**显示名。
   *   留空表示不过滤（服务端会按调用者能看到的 catalog 过滤）。
   */
  jobs: ({ catalog = '', status = '', action = '', limit = 0 } = {}) => get('listOptimizationJobs', {
    params: { query: { catalog, status, action, limit: limit || '' } },
  }),

  /** 批次详情（含表级明细）。列表不带明细，展开某一批时才拉这一条。 */
  job: (jobId) => get('getOptimizationJob', { params: { jobId } }),

  /** 重试只重跑失败的表：返回的是**新批次**（201），`retryOf` 指向原批次。 */
  retry: (jobId) => post('retryOptimizationJob', { params: { jobId } }),

  /** 取消批次：未提交的直接作废，已在跑的会连带删掉执行侧资源。 */
  cancel: (jobId) => post('cancelOptimizationJob', { params: { jobId } }),
}
