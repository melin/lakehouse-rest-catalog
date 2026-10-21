/**
 * Catalog「存储」页的行级派生值与口径常量。
 *
 * <p>这一页回答的是 AWS《Monitoring Iceberg workloads》里的那个问题：**哪些表该做
 * compaction、哪些库的存储长得最快**。指标名与判据跟着那篇走（活跃文件数、活跃存储、
 * 平均文件大小、小文件占比）。
 *
 * <p>整页的**汇总与图表（KPI / 按库 / Top 10 / 直方图）由服务端聚合算完**
 * （`GET /api/management/v1/catalogs/{c}/table-stats`），口径落在 SQL 里；
 * 这个文件只剩行级派生值——逐表明细里「平均文件大小」一列与小文件标签。
 * 三处口径必须写在页面上，否则读图的人会按 AWS 那套口径算，得到一个错的数：
 *
 * <ol>
 *   <li><b>平均文件大小是加权的</b>（总字节 ÷ 总文件数），不是「各表平均值的平均」。
 *       后者让「1 张 10 万文件的大表」与「1000 张单文件小表」等权，算出来的数会明显偏大。</li>
 *   <li><b>小文件是表级近似</b>：判据是「这张表的平均文件大小 &lt; 100 MB」，不是
 *       「逐个文件 &lt; 100 MB」。逐文件只能读元数据表（引擎侧能力，HTTP 接口拿不到）。
 *       这个近似会**漏掉**「大表里混着一批小文件」——它平均下来不小，但仍然需要
 *       compaction。所以这一页给的是**候选名单**，不是结论。</li>
 *   <li><b>空表不进平均</b>：没有快照的表文件数是 0，把它算进分母会把平均文件大小拉低，
 *       凭空造出一堆「小文件表」。</li>
 * </ol>
 *
 * <p>两种「没有数」要分开，混成 0 或 `—` 都会误导：
 * 表确实没写过（`files === 0`）、表写过但那一行没带文件统计（`files === null`）。
 * 第一种下是正常状态，第二种是**采集缺口**（统计落库之前写下的老快照行，
 * 或 Iceberg 早期客户端写的 summary 缺键），页面上要能区分。
 */

/** 小文件阈值：100 MB。与服务端聚合（`ManagementCatalogService#SMALL_FILE_THRESHOLD`）同一个数。 */
export const SMALL_FILE_THRESHOLD = 100 * 1024 * 1024

/** 表的一行统计。`files` / `size` 为 `null` 表示「没拿到」，不是 0。 */
export const ROW_FIELDS = ['db', 'table', 'kind', 'files', 'size', 'records', 'deleteFiles', 'updatedAt']

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

/** 一行里给表格用的派生值（平均大小与小文件标签）。 */
export function decorate(rows) {
  return (rows || []).map((row) => ({
    ...row,
    avgSize: avgFileSize(row),
    small: isSmallFileTable(row),
  }))
}
