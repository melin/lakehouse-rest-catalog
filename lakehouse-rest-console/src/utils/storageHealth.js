/**
 * Catalog「存储」页的算术与口径。
 *
 * <p>这一页回答的是 AWS《Monitoring Iceberg workloads》里的那个问题：**哪些表该做
 * compaction、哪些库的存储长得最快**。指标名与判据跟着那篇走（活跃文件数、活跃存储、
 * 平均文件大小、小文件占比），但数据来源必须落在本服务端**已有的读端点**上：
 * 那篇文章让客户端跑 `select count(*) from t.files` 之类的元数据表查询，而元数据表是
 * **引擎侧**的能力（Spark / Athena 才有），控制台的 HTTP 接口拿不到文件清单。
 *
 * <p>因此三处口径必须写在页面上，否则读图的人会按 AWS 那套口径算，得到一个错的数：
 *
 * <ol>
 *   <li><b>平均文件大小是加权的</b>（总字节 ÷ 总文件数），不是「各表平均值的平均」。
 *       后者让「1 张 10 万文件的大表」与「1000 张单文件小表」等权，算出来的数会明显偏大。</li>
 *   <li><b>小文件是表级近似</b>：判据是「这张表的平均文件大小 &lt; 100 MB」，不是
 *       「逐个文件 &lt; 100 MB」。逐文件只能读元数据表。这个近似会**漏掉**「大表里混着
 *       一批小文件」——它平均下来不小，但仍然需要 compaction。所以这一页给的是
 *       **候选名单**，不是结论。</li>
 *   <li><b>空表不进平均</b>：没有快照的表（刚建、还没写过）文件数是 0，
 *       把它算进分母会把平均文件大小拉低，凭空造出一堆「小文件表」。</li>
 * </ol>
 *
 * <p>三种「没有数」要分开，混成 0 或 `—` 都会误导：
 * 表确实没写过（`files === 0`）、表写过但那一行没带文件统计（`files === null`）、
 * 整页还在加载。第一种是正常状态，第二种是**采集缺口**（Paimon 快照行在统计落库之前
 * 写入时三列全空，见服务端 `TableService#getTableSnapshot`），页面上要能区分。
 */

/** 小文件阈值：100 MB。与 AWS 那篇一致；改这里就够，`fileSizeBuckets` 的分桶边界跟着它走。 */
export const SMALL_FILE_THRESHOLD = 100 * 1024 * 1024

const MB = 1024 * 1024
const GB = 1024 * MB

/** 表的一行统计。`files` / `size` 为 `null` 表示「没拿到」，不是 0。 */
export const ROW_FIELDS = ['db', 'table', 'files', 'size', 'records', 'deleteFiles', 'updatedAt', 'snapshots']

/** 这张表的平均文件大小（字节）。没有文件或没拿到大小时是 `null`。 */
export function avgFileSize(row) {
  if (!row || !row.files || row.size === null || row.size === undefined) return null
  return row.size / row.files
}

/** 表级「小文件」判据：平均文件大小小于阈值。见文件头第 2 条。 */
export function isSmallFileTable(row) {
  const avg = avgFileSize(row)
  return avg !== null && avg < SMALL_FILE_THRESHOLD
}

function ratio(part, whole) {
  return whole > 0 ? part / whole : 0
}

/**
 * 整页的汇总。
 *
 * @param {Array<object>} rows 每张表一行
 * @returns `{tables, withData, empty, unknown, files, size, records, deleteFiles,
 *           avgFileSize, smallTables, smallFiles, smallSize, smallShare}`
 *          `unknown` 是「有快照但没带文件统计」的表数，它既不算空表也不算有数据——
 *          算成任一边都会让另一边的比例偏。
 */
export function summarize(rows) {
  const list = Array.isArray(rows) ? rows : []
  let files = 0
  let size = 0
  let records = 0
  let deleteFiles = 0
  let withData = 0
  let empty = 0
  let unknown = 0
  let smallTables = 0
  let smallFiles = 0
  let smallSize = 0
  // 平均值的分母只算「拿到了大小」的那些文件：某张表没带统计时，它的文件数
  // 不能进分子，否则平均会被压低一截，看起来像全是小文件。
  let filesWithSize = 0

  for (const row of list) {
    if (row.files === null || row.files === undefined) {
      if (row.empty) empty += 1
      else unknown += 1
      continue
    }
    if (row.files === 0) {
      empty += 1
      continue
    }
    withData += 1
    files += row.files
    records += row.records || 0
    deleteFiles += row.deleteFiles || 0
    if (row.size !== null && row.size !== undefined) {
      size += row.size
      filesWithSize += row.files
    }
    if (isSmallFileTable(row)) {
      smallTables += 1
      smallFiles += row.files
      smallSize += row.size || 0
    }
  }

  return {
    tables: list.length,
    withData,
    empty,
    unknown,
    files,
    size,
    records,
    deleteFiles,
    filesWithSize,
    avgFileSize: filesWithSize > 0 ? size / filesWithSize : null,
    smallTables,
    smallFiles,
    smallSize,
    // 分母用「有数据的表」而不是全部表：空表不参与判定，见文件头第 3 条。
    smallShare: ratio(smallTables, withData),
  }
}

/**
 * 按库聚合，按存储降序。
 *
 * <p>`limit` 之内原样列出，超出部分并成一行「其余 N 个库」——一个 catalog 建了 40 个库时，
 * 40 行条形图里前 3 行占满宽度，后面 37 行全是细线，看不出任何东西。
 * 并起来的那一行仍然带真实合计，所以「总和」不会因为合并而变小。
 */
export function groupByDatabase(rows, { limit = 8 } = {}) {
  const map = new Map()
  for (const row of rows || []) {
    const key = row.db || '（未命名）'
    const item = map.get(key) || { db: key, tables: 0, files: 0, size: 0, small: 0, unknown: 0 }
    item.tables += 1
    if (row.files === null || row.files === undefined) {
      if (!row.empty) item.unknown += 1
    } else {
      item.files += row.files
      item.size += row.size || 0
      if (isSmallFileTable(row)) item.small += 1
    }
    map.set(key, item)
  }
  const list = [...map.values()].sort((left, right) => right.size - left.size || right.files - left.files)
  if (list.length <= limit) return list
  const head = list.slice(0, limit - 1)
  const rest = list.slice(limit - 1)
  head.push({
    db: `其余 ${rest.length} 个库`,
    tables: rest.reduce((sum, item) => sum + item.tables, 0),
    files: rest.reduce((sum, item) => sum + item.files, 0),
    size: rest.reduce((sum, item) => sum + item.size, 0),
    small: rest.reduce((sum, item) => sum + item.small, 0),
    unknown: rest.reduce((sum, item) => sum + item.unknown, 0),
    merged: true,
  })
  return head
}

/**
 * 平均文件大小的分布。
 *
 * <p>边界取 1 MB / 10 MB / 100 MB / 1 GB：前四档按数量级铺开，第三档的上界正好是
 * 小文件阈值——于是「前三个桶 = 小文件表」这件事在图上**看得出来**，不需要读图例。
 */
export function fileSizeBuckets(rows) {
  const specs = [
    { label: '< 1 MB', max: MB },
    { label: '1–10 MB', max: 10 * MB },
    { label: '10–100 MB', max: SMALL_FILE_THRESHOLD },
    { label: '100 MB–1 GB', max: GB },
    { label: '≥ 1 GB', max: Infinity },
  ]
  const buckets = specs.map((spec) => ({ ...spec, count: 0, files: 0, size: 0, small: spec.max <= SMALL_FILE_THRESHOLD }))
  let counted = 0
  let unknown = 0
  for (const row of rows || []) {
    const avg = avgFileSize(row)
    if (avg === null) {
      if (!row.empty) unknown += 1
      continue
    }
    const bucket = buckets.find((item) => avg < item.max)
    if (!bucket) continue
    bucket.count += 1
    bucket.files += row.files
    bucket.size += row.size || 0
    counted += 1
  }
  return { buckets, counted, unknown }
}

/** 存储（或文件数）最大的前 `count` 张表；没有拿到大小的表排在最后且不参与。 */
export function topTables(rows, count = 10, key = 'size') {
  return (rows || [])
    .filter((row) => row[key] !== null && row[key] !== undefined && row[key] > 0)
    .sort((left, right) => right[key] - left[key])
    .slice(0, count)
}

/** 一行里给表格排序用的派生值（`el-table` 的 `sort-by` 只认字段）。 */
export function decorate(rows) {
  return (rows || []).map((row) => ({
    ...row,
    avgSize: avgFileSize(row),
    small: isSmallFileTable(row),
  }))
}
