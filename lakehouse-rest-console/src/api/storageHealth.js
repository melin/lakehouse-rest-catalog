import { managementApi } from './management.js'

/**
 * Catalog「存储」页的数据采集。
 *
 * <p>**汇总与明细是两条路，各按各的量级取数**：
 *
 * <ul>
 *   <li><b>汇总（KPI / 按库 / Top 10 / 直方图）</b>走 `GET …/table-stats`：
 *       服务端对投影 join（`lakehouse_table` left join 当前快照行）做 SQL 聚合，
 *       一次算完，响应大小与表数无关——**统计永远是全量的**，没有截断这回事。
 *       这是「预先计算」的低配形态：投影行本身就是写路径维护好的中间产物，
 *       聚合是毫秒级单表扫描；真到了热点规模，物化加在这份投影上，契约不变。</li>
 *   <li><b>逐表明细</b>走 `GET …/table-stats/tables`：远程分页，分页 / 按库 /
 *       关键字过滤 / 排序全部下推 SQL。**打开存储页不拉任何明细**——
 *       选中了库才按页取数，翻页 / 排序 / 过滤各自再取一页。</li>
 * </ul>
 *
 * <p>Format / Object / Lance 表不会再被问到任何单表端点：汇总与明细都按
 * `tableType` 在服务端过滤（`onlyPaimon`），聚合行的统计列为 null 已经覆盖语义。
 */

/** 数字归一化：`null` / 空串 / `NaN` 一律 `null`，**不要退化成 0**（0 是「确实没有」）。 */
function num(value) {
  if (value === null || value === undefined || value === '') return null
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : null
}

/** 归一化一行明细。行契约见 `utils/storageHealth.js` 的 `ROW_FIELDS`。 */
function toRow(item) {
  const snapshotId = num(item?.snapshotId)
  return {
    db: item?.database || '',
    table: item?.table || '',
    // 实体的原始取值：`PAIMON` / `ICEBERG` / `format-table` / `object-table` / `lance-table`。
    kind: item?.tableType || '',
    snapshotId,
    // 有快照但没带文件统计是**采集缺口**（`null`），与「确实没有文件」（0）是两件事。
    files: snapshotId === null ? 0 : num(item?.fileCount),
    size: snapshotId === null ? 0 : num(item?.fileSizeInBytes),
    records: snapshotId === null ? 0 : num(item?.recordCount),
    deleteFiles: snapshotId === null ? 0 : num(item?.deleteFileCount),
    updatedAt: num(item?.timeMillis),
    // 没有快照的表（刚建、还没写过）：确实是空的，不是「没拿到」。
    empty: snapshotId === null,
  }
}

/**
 * 拉整个 catalog 的存储汇总（全量，服务端算完）。
 *
 * @param {object} options
 * @param {string} options.catalogName catalog 名（即 prefix）
 * @param {boolean} [options.onlyPaimon] 只统计 Paimon 表（服务端按 `tableType` 过滤）
 * @returns {Promise<{totals: object, databases: Array<object>,
 *           topTables: Array<object>, histogram: object}>}
 *           `databases` 是全量库聚合（存储降序），`topTables` 是 Top 10，
 *           `histogram` 是 `{buckets: [5 档计数], counted, unknown}`。
 */
export function loadCatalogSummary({ catalogName, onlyPaimon = false }) {
  return managementApi.getCatalogTableStats(catalogName, { onlyPaimon }).then((payload) => ({
    totals: payload?.totals || {},
    databases: (payload?.databases || []).map((row) => ({
      db: row?.database || '',
      tables: num(row?.tables) ?? 0,
      files: num(row?.files) ?? 0,
      size: num(row?.size) ?? 0,
      small: num(row?.small) ?? 0,
      unknown: num(row?.unknown) ?? 0,
    })),
    topTables: (payload?.topTables || []).map(toRow),
    histogram: {
      buckets: (payload?.histogram?.buckets || []).map((value) => num(value) ?? 0),
      counted: num(payload?.histogram?.counted) ?? 0,
      unknown: num(payload?.histogram?.unknown) ?? 0,
    },
  }))
}

/**
 * 拉逐表明细的一页（远程分页）。
 *
 * @param {object} options
 * @param {string} options.catalogName catalog 名（即 prefix）
 * @param {string} [options.database] 只取这个库——控制台**选中库才发这个请求**
 * @param {string} [options.keyword] 按库名.表名模糊过滤（服务端 LIKE）
 * @param {boolean} [options.onlyPaimon] 只看 Paimon 表（服务端按 `tableType` 过滤）
 * @param {string} [options.sort] 排序键：files / size / avgSize / records / updatedAt / table
 * @param {string} [options.order] `asc` / `desc`（缺省 desc）
 * @param {number} [options.page] 1 起的页号（原样下推，服务端按页码切）
 * @param {number} [options.pageSize] 页大小
 * @returns {Promise<{rows: Array<object>, total: number}>}
 */
export function loadCatalogTableRows({
  catalogName, database = '', keyword = '', onlyPaimon = false,
  sort = 'size', order = 'desc', page = 1, pageSize = 20,
}) {
  return managementApi.listCatalogTableRows(catalogName, {
    database: database || undefined,
    keyword: keyword.trim() || undefined,
    onlyPaimon,
    sort,
    order,
    page,
    pageSize,
  }).then((payload) => ({
    rows: (payload?.tables || []).map(toRow),
    total: num(payload?.page?.total ?? payload?.total) ?? 0,
  }))
}
