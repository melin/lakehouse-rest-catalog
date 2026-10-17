import { del, get, post } from './client.js'

/**
 * Iceberg REST Catalog（`/v1/{prefix}/namespaces/**`）的调用面。
 *
 * <p>与 {@link ./catalog.js} 是**同一个基址的两套契约**，不是同一个接口的两套写法。
 * 三处差异必须在这里吸收掉，不能上浮到视图里：
 *
 * 1. **namespace 是层级数组**，路径里那一段用 0x1F（URL 编码 `%1F`）拼接。
 *    拼错分隔符的表现是服务端把 `a%1Fb` 当成**一个**层级，于是列出来的东西
 *    与预期不符而不报错。控制台显示层用点号拼接（见 {@link namespaceLabel}），
 *    但发请求一律用 0x1F——点号是合法的层级名字符，用它拼路径在
 *    「层级名里含点」时会被切错。
 * 2. **分页参数叫 `pageSize`**（Paimon 面是 `maxResults`），响应的下一页令牌叫
 *    `next-page-token`（kebab-case，Paimon 面是 `nextPageToken`）。用错参数名时
 *    服务端不报错，只是按默认页大小返回——表现为「列表莫名其妙只有 50 条」。
 * 3. **报文是 kebab-case**：`partition-spec`、`metadata-location`、`schema-id`。
 *    这不是命名风格偏好，是规格逐字要求；客户端库按这个名字拼。
 *
 * <p>规格见 `spec/rest-catalog-open-api-iceberg.yaml`。与 catalog.js 一样，
 * 这里只做参数搬运，合法与否由服务端判定。
 */

/**
 * 层级之间的分隔符。与服务端 `NamespacePaths` 的存储口径一致：
 * 库名落库时是 `a\u001Fb`，解析时同时接受这个分隔符与点号。
 */
export const NAMESPACE_SEPARATOR = '\u001F'

/** 层级数组 → 路径里的那一截（让 `buildPath` 去做 URL 编码）。 */
export function namespacePath(levels) {
  return (levels || []).join(NAMESPACE_SEPARATOR)
}

/**
 * 层级数组 → 界面上的平铺名字。
 *
 * <p>一期不做层级树：`["accounting","tax"]` 显示成 `accounting.tax`。
 * 这个显示是有损的——层级名本身可以含点，`a.b` 与 `a` 下的 `b` 会长得一样。
 * 代价换来的是「一眼看完所有命名空间」，对以单层为主的部署更划算；
 * 真需要区分时看表详情里的 `namespace` 数组。见 `docs/console.md` §4.6。
 */
export function namespaceLabel(levels) {
  return (levels || []).join('.')
}

/**
 * 界面上的名字 → 层级数组。
 *
 * <p>只在「深链接进来、命名空间列表还没到」时用（那时只有 URL 上的字符串），
 * 列表到手后一律用列表里那份数组——它能区分「层级名里带点」的情况。
 */
export function namespaceLevels(label) {
  const text = (label || '').trim()
  return text ? text.split('.') : []
}

export const icebergApi = {
  // ------------------------------------------------------------------ namespace
  /** 列**直接子级**：不带 `parent` 是顶层。要平铺全部层级得自己按层递归。 */
  listNamespaces: (prefix, query = {}) => get('icebergListNamespaces', { params: { prefix, query } }),
  /** `body` 形如 `{namespace: [...], properties: {}}`。 */
  createNamespace: (prefix, body) => post('icebergCreateNamespace', { params: { prefix }, body }),
  /**
   * 删命名空间。
   *
   * <p>**非空时服务端回 409**，与 Paimon 面的级联删除刻意不同（见
   * `IcebergNamespaceService` 的注释）：Iceberg 客户端期望先删表再删命名空间，
   * 控制台照这个语义提示，而不是把 409 翻译成一句「删除失败」。
   */
  dropNamespace: (prefix, levels) => del('icebergDropNamespace', {
    params: { prefix, namespace: namespacePath(levels) },
  }),

  // ------------------------------------------------------------------ table
  listTables: (prefix, levels, query = {}) => get('icebergListTables', {
    params: { prefix, namespace: namespacePath(levels), query },
  }),
  /**
   * 建表。`body` 形如 `{name, schema, 'partition-spec', properties}`。
   *
   * <p>`schema` 是 Iceberg 的 `Schema` JSON（`{type:'struct', fields:[{id,name,required,type}]}`），
   * 不是 Paimon 的字段列表——两者不通用，服务端用 `SchemaParser` 解析。
   * 不传 `stage-create`：服务端明确拒绝（多表事务没实现，暂存的表没有提交路径）。
   */
  createTable: (prefix, levels, body) => post('icebergCreateTable', {
    params: { prefix, namespace: namespacePath(levels) }, body,
  }),
  /** 登记仓库里已存在的表：`body` 是 `{name, 'metadata-location'}`。 */
  registerTable: (prefix, levels, body) => post('icebergRegisterTable', {
    params: { prefix, namespace: namespacePath(levels) }, body,
  }),
  loadTable: (prefix, levels, table) => get('icebergLoadTable', {
    params: { prefix, namespace: namespacePath(levels), table },
  }),
  dropTable: (prefix, levels, table) => del('icebergDropTable', {
    params: { prefix, namespace: namespacePath(levels), table },
  }),
  /** 重命名。`source` / `destination` 都是 `{namespace: [...], name}`。 */
  renameTable: (prefix, source, destination) => post('icebergRenameTable', {
    params: { prefix }, body: { source, destination },
  }),
  /** 只摘目录条目、保留仓库文件（与 drop 的区别在这里，也只在这里）。 */
  unregisterTable: (prefix, levels, table) => post('icebergUnregisterTable', {
    params: { prefix, namespace: namespacePath(levels), table },
  }),

  // ------------------------------------------------------------------ view
  listViews: (prefix, levels, query = {}) => get('icebergListViews', {
    params: { prefix, namespace: namespacePath(levels), query },
  }),
  loadView: (prefix, levels, view) => get('icebergLoadView', {
    params: { prefix, namespace: namespacePath(levels), view },
  }),
  dropView: (prefix, levels, view) => del('icebergDropView', {
    params: { prefix, namespace: namespacePath(levels), view },
  }),
}

/**
 * 列的是一页就取完，还有下一页时把令牌带回来。
 *
 * <p>抽出来是因为三个列表（namespace / table / view）的分页约定完全一致，
 * 而它们的**响应字段名各不相同**（`namespaces` / `identifiers`）。
 * 视图里只需要「给我全部，或者给我这一页加个令牌」这一件事。
 */
export async function collectPages(fetchPage, field, { pageSize, limit = 20 } = {}) {
  const items = []
  let token = ''
  for (let page = 0; page < limit; page += 1) {
    const payload = await fetchPage({
      pageSize,
      pageToken: token || undefined,
    })
    items.push(...(payload?.[field] || []))
    token = payload?.['next-page-token'] || ''
    if (!token) return { items, nextPageToken: '' }
  }
  return { items, nextPageToken: token }
}

/**
 * 递归深度上限。
 *
 * <p>`GET /namespaces` 只回**直接子级**，要平铺就得按层递归：父级数 n 时
 * 请求数是「层的节点数」而不是固定值。Iceberg 的多级命名空间在实践中很浅
 * （`accounting.tax` 这种），设 4 层足够；真正的护栏是下面的条目上限——
 * 它挡住「某天有人建了 5000 个 namespace，打开页面发 5000 个请求」。
 */
export const MAX_NAMESPACE_DEPTH = 4
export const MAX_NAMESPACE_COUNT = 500

/**
 * 平铺列出全部命名空间（层级数组的列表）。
 *
 * <p>按层 BFS：每层调用一次 `GET /namespaces?parent=…`，把子级收进结果、
 * 再把有子级的排进队列。父级值用 0x1F 拼（见 {@link namespacePath}）——
 * 用点号拼在「层级名含点」时会被服务端切错。
 *
 * <p>放在 API 层而不是某个视图里：浏览页要它来铺左列表，权限页要它来当
 * 「namespace / database」候选——两处各写一遍 BFS，迟早一处改了深度上限、
 * 另一处没改，表现为两个页面对同一个 catalog 看到不同的命名空间集合。
 */
export async function listAllNamespaces(prefix, { pageSize } = {}) {
  const collected = []
  const queue = [[]]
  while (queue.length && collected.length < MAX_NAMESPACE_COUNT) {
    const parent = queue.shift()
    const page = await collectPages(
      (query) => icebergApi.listNamespaces(prefix, {
        ...query,
        parent: parent.length ? namespacePath(parent) : undefined,
      }),
      'namespaces',
      { pageSize },
    )
    for (const levels of page.items) {
      if (!Array.isArray(levels) || !levels.length) continue
      collected.push(levels)
      if (levels.length < MAX_NAMESPACE_DEPTH) queue.push(levels)
    }
  }
  return collected
}
