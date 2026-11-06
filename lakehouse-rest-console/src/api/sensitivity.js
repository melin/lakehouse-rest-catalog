import { del, get, post, put } from './client.js'

/**
 * 敏感数据识别与标签治理（`/api/management/v1/**`）的调用面。
 *
 * <p>单独一个模块的理由与 `policy.js` / `optimization.js` 同源：这些是本工程自己的
 * 扩展资源（服务端 `SensitiveTypeController` / `SensitivityScanController` /
 * `ColumnTagController` / `SensitivitySettingsController`），四份规格里都没有它们。
 *
 * <p><b>端点全挂 `/api/management/v1/**`，不是设计稿 §9 表里写的 `/api/catalogs/{c}/...`。</b>
 * 受管前缀清单（服务端 `WebConfig.addInterceptors`）只含 `/v1/**`、`/lance/v1/**`、
 * `@/api/catalog/v1/**`、`/api/management/v1/**`、`/api/console/v1/**`——
 * 照 §9 的字面挂到 `/api/catalogs/...` 会是**匿名可调**。服务端 DTO 的类注释里
 * 写了这条取舍，这里照着写。
 *
 * <p>六个与服务端语义对齐的约定，写错的表现各不相同：
 *
 * 1. **敏感类型没有 `entityVersion`**：类型表刻意不加乐观锁列（几十个低频对象，
 *    两个人同时改同一个类型的窗口极小，而冲突后果只是「一份探测器配置被覆盖」，
 *    下一轮扫描就会发现判定变了）。因此 `PUT /sensitive-types/{code}` 是
 *    **last-write-wins**，界面上不能假装有版本检查——不传版本号即可，
 *    服务端也不接受这个字段。
 * 2. **`autoApplyAllowed` 是只读推断**（白名单里有没有它），`autoApply` 才可写。
 *    两者同值 = 没人改过；`autoApply=false` 而 `autoApplyAllowed=true` = 治理员
 *    显式关的。界面要能说出「这个类型为什么不允许自动落标」是**规则不许**还是
 *    **有人关了**——这是两件不同的事。
 * 3. **`GET /column-tags` 同时回 `items` + `meta` + `coverage`**，且 `coverage`
 *    只随 `catalog` / `table` 变，**不随 `tagKey` / `level` 变**（覆盖率的分子分母
 *    算的是「这个范围里已分类多少列」，筛标签键不会改变范围的列数）。因此
 *    「筛 L4 后覆盖率还是 86%」是正确行为，不是 bug——界面上要把这两个数分开摆。
 * 4. **`PUT /column-tags` 必须带 `entityVersion`**（更新时；新建时可省略）。
 *    不匹配回 409，控制台**不自动重试**：标签是治理结论，两个人同时改必须由人裁决。
 *    `source` **不可由调用方指定**（恒 `MANUAL`）——否则「谁能伪造一条 AUTO 标签」
 *    就是绕过自动应用闸门的口子。
 * 5. **`POST /sensitivity-scans` 回 202 而不是 201**：扫描是长任务，202 表示
 *    「已受理」，响应体只有 `{scanId, catalog, status, execution}`，结果要另取。
 *    `execution` 省略 = 用生效的三层覆盖值（请求 > catalog 的 `defaultExecution` > 全局），
 *    但**服务端落库的是生效值**——界面上「我这次选的」与「实际跑的」可能是两回事。
 * 6. **`sensitivity-settings` 的 `entityVersion` 是 catalog 自己的版本号**
 *    （设置存在 catalog 行的 `properties` 里）。改扫描设置会让 catalog 的 etag
 *    一起变——同一个 catalog 的「改配置」与「改扫描设置」在服务端是一行上的冲突。
 *    留 `null` 表示跳过校验（服务端不强制），但控制台总是带上它。
 */
export const sensitivityApi = {
  // ------------------------------------------------------------------ 敏感类型与探测器

  /**
   * 类型清单（内置 + 自定义），按 `typeCode` 升序，**不分页**——类型是「几十」个，
   * 与既有列表同一取舍。
   *
   * <p>需要服务管理员：`lakehouse_sensitive_type` 没有 catalog 列（类型是全局的，
   * 不是某个 catalog 的属性），因此服务端四处判的都是 `requireServiceAdmin`。
   */
  listTypes: () => get('listSensitiveTypes'),

  /** 单个类型（`#types` 屏右侧编辑器的取数入口）。 */
  getType: (typeCode) => get('getSensitiveType', { params: { typeCode } }),

  /**
   * 新建类型。`body` 与服务端 `SensitiveTypeUpsertRequest` 逐字对齐：
   * `{typeCode, typeName, category, level, detectors, autoApply?, minConfidence?, enabled?}`。
   *
   * <p>`detectors` 是「层名 → 层配置」的**结构化对象**（不是 JSON 字符串）：
   * 服务端读模型给的就是结构化对象，前端拿一个字符串就得自己 parse，
   * 等于把 `DetectorConfig` 的解析规则抄一份到 JS 里。
   * 认不出的层服务端会**忽略而不是报错**（掉分比报错安全），因此前端可以多传。
   */
  createType: (body) => post('createSensitiveType', { body }),

  /**
   * 改类型（含内置类型——内置只禁止**删除**，不禁改；改探测器权重正是
   * §12 验收里那条「改权重后下一轮扫描判定随之改变」的入口）。
   *
   * <p>**last-write-wins**，没有乐观锁（见模块注释 1）。
   */
  updateType: (typeCode, body) => put('updateSensitiveType', { params: { typeCode }, body }),

  /**
   * 删类型。**内置类型回 409**（`builtin=true` 的那批不能删，只能停用）。
   *
   * <p>界面按服务端返回的 `builtin` 字段把删除按钮置灰——这不是「前端预判」：
   * 「哪些类型是内置」的规则始终只有服务端那一条（`BuiltinSensitiveTypes.all()`），
   * 前端读的是它算出来的**结果字段**，不是把那份清单抄一份到 JS 里。
   * 真去调也不会成功（409），但让按钮可点再报错，只会让人以为「是不是我点错了」。
   */
  deleteType: (typeCode) => del('deleteSensitiveType', { params: { typeCode } }),

  // ------------------------------------------------------------------ 扫描

  /**
   * 发起扫描，返 **202** + `{scanId, catalog, status, execution}`。
   *
   * <p>`body` 与服务端 `ScanRequest` 逐字对齐：`{catalog, scope, database, table,
   * mode, execution, sampleSize, triggerType}`。`scope` 决定后两个必填：
   * `CATALOG` 全空 / `DATABASE` 要库名 / `TABLE` 要库名 + 表名——
   * 服务端**不靠「哪几个字段有值」反推层级**（表名恰好等于库名这种巧合不该改语义），
   * 所以三个字段都要显式给（该空的填空串）。
   *
   * <p>判 `CATALOG:ALTER`（不是 `TABLE:SELECT`）：采样是「合法高权限读」，
   * 挂在 SELECT 上等于任何能查表的人都能让服务端替他读明文。
   */
  startScan: (body) => post('startSensitivityScan', { body }),

  /**
   * 扫描历史。
   *
   * <p><b>`catalog` 为空会要服务管理员。</b>服务端在 `catalog` 非空时判
   * `CATALOG:DESCRIBE`、为空时判 `requireServiceAdmin`——控制台这一屏始终带 catalog
   * （页面有 catalog 选择器），不带是刻意去查全库，普通治理员会拿到 403。
   *
   * <p>`status` / `scope` / `triggerType` 三个过滤都在服务端做（先过滤再切页）。
   */
  listScans: ({ catalog = '', status = '', scope = '', triggerType = '', page = null, pageSize = null } = {}) =>
    get('listSensitivityScans', {
      params: { query: { catalog, status, scope, triggerType, page, pageSize } },
    }),

  /** 单次扫描详情（含 `tablesFailed` 与 B 路线的 `executionNamespace` / `executionResource`）。 */
  getScan: (scanId) => get('getSensitivityScan', { params: { scanId } }),

  /**
   * 命中清单（人审队列的读侧；P0 只提供读，「批量确认 / 拒绝」属 P1）。
   *
   * <p><b>`catalog` 必填</b>：服务端硬要求。没有它这个端点就成了
   * 「一次读到所有 catalog 的识别结论」，而识别结论（类型 + 置信度）本身就是敏感信息。
   *
   * <p>`state` 只认四个大写名（`PENDING` / `AUTO_APPLIED` / `CONFIRMED` / `REJECTED`）：
   * 服务端给了认不出的值直接 400，**不静默忽略**——静默忽略会让「按 PENDING 筛」
   * 回一份全部，而调用方以为自己在看待审队列。
   */
  listFindings: ({ catalog, state = '', table = '', type = '', page = null, pageSize = null } = {}) =>
    get('listSensitivityFindings', {
      params: { query: { catalog, state, table, type, page, pageSize } },
    }),

  // ------------------------------------------------------------------ 列级标签

  /**
   * 标签清单 + 覆盖率（设计稿 §8.4 的「对外主接口」）。
   *
   * <p>`coverage` 与 `items` 同一个响应：调用方取标签时几乎总要同时知道「覆盖了多少」，
   * 分两个端点会让「看到 12 条标签」与「12 条占多少」之间出现一次可以做别的写入的窗口。
   *
   * <p>`level` 只认 `L1`–`L4` / `IMPORTANT` / `CORE` 六个大写名。
   */
  listTags: ({ catalog, table = '', tagKey = '', level = '', page = null, pageSize = null } = {}) =>
    get('listColumnTags', {
      params: { query: { catalog, table, tagKey, level, page, pageSize } },
    }),

  /**
   * 手工打标（upsert）。唯一键是 `(column_ref_hash, tag_key)`，重复 PUT 是更新不是新增。
   *
   * <p>`column` 传**空串**表示表级标签（`columnRefs.TABLE_LEVEL_COLUMN = ""`）。
   * 表级标签不参与策略展开，只用于检索与粗判（设计稿 §13 未决第 2 条的口径）。
   *
   * <p>更新必须带 `entityVersion`（`null` → 409）；新建时不带。回 **200**（不是 201）：
   * upsert 既可能新建也可能更新，调用方要的是「这条标签现在是什么」。
   */
  upsertTag: (body) => put('upsertColumnTag', { body }),

  /**
   * 删标签（回 204）。
   *
   * <p>P0 只删标签、**不写抑制项**（设计稿 §7.2 的抑制项依赖的两个前提在 P0 都不成立：
   * 没有任何路径能产生 `REJECTED`，且元数据档置信度上限 0.45 够不到 0.85 永不产 tag，
   * 所以「删了会复活」在 P0 不成立）。抑制项与 `decide` 端点一起在 P1 落地。
   * 界面上的文案不能写成「删除后不会被重新识别」——那句话今天不成立。
   */
  deleteTag: (id) => del('deleteColumnTag', { params: { id } }),

  // ------------------------------------------------------------------ catalog 级设置

  /**
   * 读 catalog 级治理设置。
   *
   * <p>三个字段都是**catalog 覆盖**（`null` = 继承全局），响应同时给三个 `resolved*`
   * 生效值：界面要回答「这个 catalog 现在实际会怎么跑」，只给覆盖值时答案是「未设置」，
   * 那要前端自己再取一次全局配置，于是同一件事有两个答案源。
   */
  settings: (catalog) => get('sensitivitySettings', { params: { query: { catalog } } }),

  /**
   * 写 catalog 级设置（整份替换）。
   *
   * <p>`body`：`{defaultExecution, maxConcurrentScans, propagationMaxDepth, entityVersion}`。
   * 传 `null` = 清除覆盖、回落全局——**没有单独的「清除」开关**，空就是空
   * （与执行集群那份请求体刻意不同：那里的秘密字段留空表示「保持原样」，因为空串是
   * 有效的秘密值）。三个字段都是可选覆盖，所以这里不存在「留空 = 不改」的歧义。
   *
   * <p>`maxConcurrentScans` 管的是 **A 路线（进程内）** 的扫描并发，与执行集群那把
   * 集群闸门 `maxConcurrentJobs` 是两件事、互不影响——界面上必须把这句话说清，
   * 否则会有人以为调大它就能让下推的 Spark 扫描多跑几个。
   */
  updateSettings: (catalog, body) => put('updateSensitivitySettings', {
    params: { query: { catalog } },
    body,
  }),
}
