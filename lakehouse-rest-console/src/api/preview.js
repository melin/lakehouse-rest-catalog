import { get, post } from './client.js'

/**
 * 数据预览的两条端点（`/api/console/v1/tables/{tableId}/**`）。
 *
 * <p>与 `console.js` 分开只为读者方便：这两条是**唯一会读行数据**的控制台端点，
 * 而 `console.js` 里其余的都是元数据。放一起时容易把「预览」当成又一个只读元数据接口，
 * 从而忘了它需要 TABLE 级 `SELECT`（服务端判权，见 `TablePreviewController`）。
 *
 * <p>两条都不做参数校验：列名、算子、分区值、`limit` 的合法范围全由服务端判定
 * （前端只负责把不可能的选项藏起来，那是 UI 的体贴，不是契约）。
 */
export const previewApi = {
  /**
   * 按名字换内部 id。
   *
   * <p>只有一处需要它：Iceberg 表详情页按名字加载表（`LoadTableResult` 里没有内部 id），
   * 而上面两条端点按 id 寻址。拿到 id 之后，两种格式的预览走的是**同一套**请求，
   * 组件里没有任何格式分支。
   *
   * @param {string} prefix catalog 的 prefix
   * @param {string} database 库名（多级 namespace 是点号拼接的显示名）
   * @param {string} table 表名
   */
  lookupTable: (prefix, database, table) =>
    get('lookupPreviewTable', { params: { prefix, database, table } }),

  /**
   * 读一页数据。
   *
   * @param {string} tableId 表的内部 id（表详情响应里的 `id`）
   * @param {object} body `{ partition, filters, columns, limit, cursor, snapshotId }`
   *   续读时把上一页返回的 `cursor` 原样带上；`partition`/`filters`/`columns`
   *   仍要重发（服务端要用它们重新判定行），游标里的摘要会校验三者没变。
   */
  preview: (tableId, body) => post('previewTable', { params: { tableId }, body }),

  /**
   * 分区列表（分区选择器的数据源，也是「这个分区值不值得看」的依据）。
   *
   * @param {string} tableId 表的内部 id
   * @param {object} query `{ page, pageSize, keyword, snapshotId }`
   */
  partitions: (tableId, query = {}) => get('listTablePartitions', { params: { tableId, query } }),
}
