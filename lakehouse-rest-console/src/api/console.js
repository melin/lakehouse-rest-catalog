import { download, get } from './client.js'

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
  listCatalogFiles: (catalogName, path = '', prefix = '') => get('listCatalogFiles', {
    params: { catalogName, query: { path, prefix } },
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
  listTableFiles: (tableId, path = '', prefix = '') => get('listTableFiles', {
    params: { tableId, query: { path, prefix } },
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
}
