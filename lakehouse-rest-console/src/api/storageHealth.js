import { ApiError } from './client.js'
import { catalogApi } from './catalog.js'
import { icebergApi, listAllNamespaces, namespaceLabel } from './iceberg.js'
import { isSnapshotManaged } from '../utils/tableKind.js'

/**
 * Catalog「存储」页的数据采集。
 *
 * <p><b>为什么在控制台算，而不是加一个服务端聚合端点。</b>这一页要的数在两侧都已经有了：
 * Paimon 侧是快照行上的三列（`fileCount` / `fileSizeInBytes` / `recordCount`），
 * Iceberg 侧是表元数据里当前快照的 `summary`（`total-data-files` / `total-files-size` /
 * `total-records`）。加端点意味着再写一遍 DTO、再走一遍权限、再维护一处与两种格式的
 * 差异；而这一页的使用频率是「偶尔打开看一眼」，不值得。代价是**请求数 = 表数**，
 * 所以下面有 `MAX_TABLES` 与并发上限两道闸，页面上也标了「统计到第几张」。
 *
 * <p>**两种格式的差异只在这个文件里**。上层拿到的是同一个 `{rows, total, truncated, failed}`，
 * 视图不需要知道 Iceberg 的 summary 是字符串还是数字、Paimon 的空表是 404 还是 0。
 */

/** 一次最多统计多少张表。超过就截断并告诉调用方（页面显示「统计了前 200 张，共 N 张」）。 */
export const MAX_TABLES = 200

/**
 * 同时在飞的请求数。
 *
 * <p>取 6 不是拍的：对象存储上一张表要读一次元数据文件（几十毫秒），并发 6 时
 * 200 张表约 2 秒；再往上加对服务端和浏览器都没有好处（浏览器同域并发连接本来就有上限），
 * 而「打开页面就把服务端打满」对管理面是很糟的行为。
 */
const CONCURRENCY = 6

/** 数字归一化：`null` / 空串 / `NaN` 一律 `null`，**不要退化成 0**（0 是「确实没有」）。 */
function num(value) {
  if (value === null || value === undefined || value === '') return null
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : null
}

/**
 * 拉这个 catalog 每张表的文件统计。
 *
 * @param {object} options
 * @param {string} options.catalogName catalog 名（即 prefix）
 * @param {string} options.format `PAIMON` / `ICEBERG`
 * @param {string} [options.database] 只统计这个库；空串 = 整个 catalog。
 *   过滤发生在**列表阶段**而不是对已采回的行做筛选——整 catalog 采一遍再滤掉，
 *   等于为了一张表把另外 199 张的快照都请求了一遍。
 * @param {boolean} [options.onlyPaimon] 只统计 Paimon 表（仅 PAIMON 格式有意义）。
 *   Format / Object / Lance 表没有快照，混进来只会变成一列「无快照」，
 *   把「有数据的表」的分母撑大。判定用 `table-details` 里的 `schema.options.type`
 *   （`isSnapshotManaged`），不是猜表名。
 * @param {(progress: {done: number, total: number}) => void} [options.onProgress] 每张表完成时回调
 * @param {number} [options.limit] 覆盖 {@link MAX_TABLES}（测试用）
 * @returns {Promise<{rows: Array<object>, total: number, scanned: number,
 *           truncated: boolean, failed: number, databases: Array<string>}>}
 *           `databases` 是**全量**库名（不分页截断），供「按库过滤」下拉用——
 *           列库是便宜请求（库名而已），表才有早停。
 */
export async function loadCatalogStorage({
  catalogName, format, database = '', onlyPaimon = false, onProgress, limit = MAX_TABLES,
}) {
  const listed = format === 'ICEBERG'
    ? await listIcebergTables(catalogName, limit, database)
    : await listPaimonTables(catalogName, limit, database, onlyPaimon)

  const picked = listed.targets.slice(0, limit)
  const rows = []
  let done = 0
  let failed = 0

  await forEachLimit(picked, CONCURRENCY, async (target) => {
    try {
      rows.push(await loadOne(catalogName, format, target))
    } catch {
      // 单张表失败（并发下被别的客户端删了、或者这一张表没有读权限）不该让整页失败：
      // 计一个数，页面上说「N 张表读取失败」，其余照常展示。
      failed += 1
    } finally {
      done += 1
      onProgress?.({ done, total: picked.length })
    }
  })

  return {
    rows,
    // `total` 是**已经数到的**表数，不是全量：早停之后没人知道总数，页面也就不该显示总数。
    total: listed.targets.length,
    scanned: picked.length,
    truncated: !listed.complete,
    failed,
    databases: listed.databases,
  }
}

async function loadOne(prefix, format, target) {
  return format === 'ICEBERG' ? icebergRow(prefix, target) : paimonRow(prefix, target)
}

/**
 * Paimon 侧：列库（全量，给下拉用）→ 逐库列表。
 *
 * <p>表列表走 `table-details` 而不是只回表名的 `/tables`：表格式的开关要读
 * `schema.options.type`，只有前者带 schema。两个端点分页约定一致
 * （`maxResults` + `nextPageToken`），请求次数不变，只是响应更大——
 *换来的是不用为判格式再对每张表多打一次 getTable。
 *
 * <p>分页参数是 `maxResults` + `nextPageToken`（camelCase），与 Iceberg 面的
 * `pageSize` + `next-page-token` 不是同一套——混用时服务端不报错，只是按默认页大小返回，
 * 表现是「库只列出了 50 个」。
 */
async function listPaimonDatabases(prefix) {
  const names = []
  let token = ''
  for (let page = 0; page < 50; page += 1) {
    const payload = await catalogApi.listDatabases(prefix, { maxResults: 200, pageToken: token || undefined })
    for (const db of payload?.databases || []) names.push(db)
    token = payload?.nextPageToken || ''
    if (!token) break
  }
  return names
}

async function listPaimonTables(prefix, limit, database, onlyPaimon) {
  const databases = await listPaimonDatabases(prefix)
  // 选中了库就只列这个库；没选中才遍历全部。
  const scope = database ? databases.filter((name) => name === database) : databases
  const targets = []
  outer:
  for (const db of scope) {
    let token = ''
    for (let page = 0; page < 50; page += 1) {
      const payload = await catalogApi.listTableDetails(prefix, db, {
        maxResults: 200,
        pageToken: token || undefined,
      })
      for (const detail of payload?.tableDetails || []) {
        if (onlyPaimon && !isSnapshotManaged(detail)) continue
        targets.push({ db, levels: null, table: detail?.name })
      }
      // 多收一张就停：判断得出来「还有」，而再往下翻没有任何用处。
      // 一个 catalog 建了 500 个库时，「全部列完再截断」意味着 500 次请求，换来的只是
      // 一个「共有 N 张表」的数字——而这个数字在截断状态下本来就不能当合计用。
      if (targets.length > limit) break outer
      token = payload?.nextPageToken || ''
      if (!token) break
    }
  }
  return { targets, complete: targets.length <= limit, databases }
}

/** Iceberg 侧：先平铺命名空间（BFS），再逐个列表。早停规矩与 Paimon 侧一致。 */
async function listIcebergTables(prefix, limit, database) {
  const namespaces = await listAllNamespaces(prefix)
  const databases = namespaces.map(namespaceLabel)
  // 命名空间标签（多级用「.」拼）反查 levels；找不到（库里刚被删）等于空结果。
  const scope = database
    ? namespaces.filter((levels) => namespaceLabel(levels) === database)
    : namespaces
  const targets = []
  outer:
  for (const levels of scope) {
    let token = ''
    for (let page = 0; page < 50; page += 1) {
      const payload = await icebergApi.listTables(prefix, levels, { pageSize: 200, pageToken: token || undefined })
      for (const identifier of payload?.identifiers || []) {
        const namespace = Array.isArray(identifier?.namespace) ? identifier.namespace : levels
        targets.push({ db: namespaceLabel(namespace), levels: namespace, table: identifier?.name })
      }
      if (targets.length > limit) break outer
      token = payload?.['next-page-token'] || ''
      if (!token) break
    }
  }
  return { targets, complete: targets.length <= limit, databases }
}

/**
 * 一张 Paimon 表：`GET /v1/{prefix}/databases/{db}/tables/{t}/snapshot`。
 *
 * <p>没有快照时服务端回 404（`SNAPSHOT`），这是**正常状态**（表刚建、还没写过），
 * 不是错误。这里把 404 折成一行 0 文件，页面上显示「无快照」。
 * 代价是「表在列出来之后被删掉」也会落到同一个分支——那一行同样显示 0/无快照，
 * 对一页只读的统计来说这个偏差可以接受（刷新就没了）。
 */
async function paimonRow(prefix, target) {
  const base = { db: target.db, table: target.table, levels: null, snapshots: null, deleteFiles: null }
  try {
    const payload = await catalogApi.tableSnapshot(prefix, target.db, target.table)
    // 响应是**两层**：`{snapshot: {snapshot: {...}, fileCount, fileSizeInBytes, recordCount,
    // lastFileCreationTime}}`。外层是「快照 + 聚合统计」，内层才是 Paimon 的 Snapshot 本体。
    // 只读一层的后果不是报错，而是文件数恒为 `null`——页面会把整个 catalog 说成「缺统计」。
    const stats = payload?.snapshot || null
    const files = num(stats?.fileCount)
    return {
      ...base,
      files,
      size: num(stats?.fileSizeInBytes),
      records: num(stats?.recordCount),
      updatedAt: num(stats?.lastFileCreationTime) ?? num(stats?.snapshot?.timeMillis),
      snapshotId: num(stats?.snapshot?.id),
      // 文件数拿不到时不能算空表：这是**采集缺口**（见下面 404 分支的说明）。
      empty: stats === null || files === 0,
    }
  } catch (cause) {
    if (cause instanceof ApiError && cause.status === 404) {
      return { ...base, files: 0, size: 0, records: 0, updatedAt: null, snapshotId: null, empty: true }
    }
    throw cause
  }
}

/**
 * 一张 Iceberg 表：`GET /v1/{prefix}/namespaces/{ns}/tables/{t}`。
 *
 * <p>当前快照的 `summary` 里带了累计统计，键名是 kebab-case 的字符串，值可能是字符串
 * 也可能是数字（取决于写元数据的是哪个客户端），所以一律过 {@link num}。
 *
 * <p>`current-snapshot-id` 为 `-1` 或缺席时表是空的（只有 schema，没写过）——
 * 这时 `files` 记 0 而不是 `null`：**确实没有文件**，与「没拿到统计」是两件事。
 */
async function icebergRow(prefix, target) {
  const payload = await icebergApi.loadTable(prefix, target.levels, target.table)
  const metadata = payload?.metadata || {}
  const snapshots = Array.isArray(metadata.snapshots) ? metadata.snapshots : []
  const currentId = num(metadata['current-snapshot-id'])
  const current = currentId === null
    ? null
    : snapshots.find((item) => num(item['snapshot-id']) === currentId) || null
  const summary = current?.summary || {}
  const files = num(summary['total-data-files'])

  return {
    db: target.db,
    table: target.table,
    levels: target.levels,
    files: current ? files : 0,
    size: current ? num(summary['total-files-size']) : 0,
    records: current ? num(summary['total-records']) : 0,
    deleteFiles: current ? num(summary['total-delete-files']) : 0,
    updatedAt: num(metadata['last-updated-ms']) ?? num(current?.['timestamp-ms']),
    snapshotId: currentId,
    snapshots: snapshots.length,
    empty: current === null,
  }
}

/** 限并发地跑完一批任务。 */
async function forEachLimit(items, limit, worker) {
  const queue = items.slice()
  const size = Math.max(1, Math.min(limit, queue.length))
  await Promise.all(Array.from({ length: size }, async () => {
    while (queue.length) {
      const item = queue.shift()
      await worker(item)
    }
  }))
}
