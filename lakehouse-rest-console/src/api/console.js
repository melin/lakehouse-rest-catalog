import { download, get, post } from './client.js'

/**
 * 控制台扩展端点（`/api/console/v1/**`）里除登录之外的那些。
 *
 * <p>与 `management.js`（Polaris 管理规格的镜像）和 `catalog.js`（catalog API）
 * 分开：这些端点在两份规格里都没有，是本工程为控制台加的，服务端把它们放在
 * 独立的命名空间下（见 `ConsoleMetaController` 的说明）。归在一起的好处是
 * 「哪些能力是规格外的」在目录结构上就能看出来——排查「引擎客户端能不能调这个」
 * 这类问题时，答案就在调用方引的是哪个模块。
 *
 * <p>登录相关的三条（`/auth`、`/login`、令牌端点）不在这里，它们在
 * `console-auth.js`：那三条在服务端鉴权范围之外，且失败处理与重试语义完全不同
 * （登录失败要提示凭据、数据请求失败要提示令牌过期）。
 */
export const consoleApi = {
  /**
   * 列出 catalog 仓库里某个目录的一层内容。
   *
   * @param {string} catalogName catalog 的 prefix
   * @param {string} path 相对**仓库根**的目录路径；空串或省略表示仓库根。
   *   条目返回的 `path` 已经是这个形状，下钻时原样传回即可——
   *   控制台不需要知道 scheme 与桶名，那是服务端按 catalog 的 warehouse 拼的。
   * @param {string} prefix 名称前缀过滤（服务端过滤后再截到上限）；空串表示不过滤
   */
  listCatalogFiles: (catalogName, path = '', prefix = '', paging = {}) => get('listCatalogFiles', {
    params: { catalogName, query: { path, prefix, page: paging.page, pageSize: paging.pageSize } },
  }),

  /**
   * 列出某张表的目录里的一层内容（表详情页的「文件列表」）。
   *
   * @param {string} tableId 表的内部 id（表详情响应里的 `id`），不是 `db.table` 名字对
   * @param {string} path 相对**表目录**的路径；空串或省略表示表目录本身。
   *   与 {@link listCatalogFiles} 同一套约定，只是根换成了表目录——
   *   所以这个端点天生列不出表目录之外的东西，下钻出界是服务端挡的。
   * @param {string} prefix 名称前缀过滤（服务端过滤后再截到上限）；空串表示不过滤
   */
  listTableFiles: (tableId, path = '', prefix = '', paging = {}) => get('listTableFiles', {
    params: { tableId, query: { path, prefix, page: paging.page, pageSize: paging.pageSize } },
  }),

  /**
   * 下载仓库里的一个文件（`?path=` 必填且必须指到文件）。
   * 文件名由服务端的 `Content-Disposition` 给出；目录与仓库根是 400（用列表去看）。
   */
  downloadCatalogFile: (catalogName, path) => download('downloadCatalogFile', {
    params: { catalogName, query: { path } },
  }),

  /** 下载某张表目录下的一个文件，路径相对表目录，出不了表外。 */
  downloadTableFile: (tableId, path) => download('downloadTableFile', {
    params: { tableId, query: { path } },
  }),

  /**
   * 增量拉目录事件。响应是 `{ events, nextCursor, hasMore }`，事件按游标（自增 id）
   * 升序；契约见 `docs/event-framework.md` §4——不提供往回翻页，
   * 要看更早的历史就重置游标（省略 `since`）重新取最新一页。
   *
   * @param {object} [query]
   * @param {number} [query.since] 游标：只回比它新的事件；省略表示首次拉取，
   *   回当前最新一页（游标落在「现在」）。传 0 合法——表示「从流的开头拉」，
   *   `client.js` 只跳过 `null` / 空串，不会把 0 吞掉。
   * @param {string} [query.catalog] 可选的目录过滤；省略回全部目录
   * @param {number} [query.limit] 页大小，默认 100、上限 1000
   */
  listEvents: (query = {}) => get('listEvents', { params: { query } }),

  /**
   * 每个可见 catalog 的凭据从哪来：`{ sources: [{ catalog, mode, detail }], credentialProviders }`。
   *
   * <p>三档（短期签发 / 转发长期密钥 / 不下发）在后两档上客户端配置完全一样，
   * 界面上这一处标记是唯一能区分它们的地方（设计稿 §10.1）。判定在服务端做：
   * 判据要读 `lakehouse.rest.storage.*` 与 `credential.providers`，前端拿不到。
   *
   * <p>批量拿（一次回全部可见 catalog）而不是逐行：列表页一屏几十行，
   * 逐行就是 N+1，而这件事本身不访问存储、不调云。
   */
  credentialSources: () => get('credentialSources'),

  /**
   * 判定**表单里还没保存**的那份存储配置：`{ mode, detail }`，与
   * {@link credentialSources} 同一个词表、同一段服务端代码。
   *
   * <p>为什么表单在填的时候就需要它：`roleArn` 填了、但本部署没启用 `s3-token` 时，
   * 结果与什么都没填一模一样（都退回环境凭据链）。这件事从字段上看不出来，
   * 保存后要回列表页才看得见——而那时用户已经在另一步上了。
   *
   * <p>传的是表单的提交体（`build()` 的产物，可能含打码的 `accessKeyId`）：
   * 判定只问「配没配」，不读密钥本身，打码值不影响结论。
   */
  previewCredentialSource: (storageConfigInfo) => post('credentialSourcePreview', {
    body: storageConfigInfo,
  }),
}
