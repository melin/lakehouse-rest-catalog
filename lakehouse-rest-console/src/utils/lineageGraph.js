/**
 * 血缘图的纯函数层：解码、索引、过滤。
 *
 * <p><b>为什么单独一个模块。</b>服务端 {@code compact=1} 的响应是「数组 + 整数下标」，
 * 这是为解析速度设计的形状（§8.6），不适合直接喂给渲染与布局——它们要按名字取邻居。
 * 把「解码 + 建索引」放在这里，组件里就只剩状态与事件；而这一层不碰 DOM、不碰 UI 库，
 * {@code node --test} 能直接测（`tools/lineage-tests`）。
 *
 * <p>三条约定：
 *
 * <ul>
 *   <li><b>{@code graph} 不可变。</b>过滤、展开都生成新对象，绝不原地改——
 *       渲染靠「引用变了没有」判断要不要重画（§9.13）。</li>
 *   <li><b>节点身份是 {@code ref} 字符串</b>，不是下标。下标只活在 {@code compact} 的
 *       一次响应里，展开会再拉一次图，两次的下标不是一回事。</li>
 *   <li><b>过滤分两类</b>：隐藏类（引擎 / 节点类型 / 只看有列映射）改变图，淡化类
 *       （太久没更新）不改变图。这条分界写在 `applyFilters` 的返回值里，
 *       不留给调用方各自理解（§9.4(4)、§9.13 规则 4）。</li>
 * </ul>
 */

/** 写模式的单字母 ↔ 全名。服务端压成字母，界面要读得懂。 */
const WRITE_MODES = { A: 'APPEND', O: 'OVERWRITE', U: 'UPSERT', C: 'CREATE' }

/**
 * 解码一次图查询的响应。
 *
 * <p>两种形状都认：{@code compact=1} 的 `{v, root, n, e, budget}` 与默认的
 * `{root, nodes, edges, budget}`。之所以不强制只认一种：`tools/lineage-bench` 的夹具
 * 与 curl 调出来的原始响应都该能直接喂进来，多一个分支比多一层转换便宜。
 */
export function decode(payload) {
  if (!payload || (!payload.n && !payload.nodes)) {
    return emptyGraph(payload?.root || '')
  }
  return payload.v !== undefined || payload.n ? decodeCompact(payload) : decodeFull(payload)
}

function decodeCompact(payload) {
  const nodes = (payload.n || []).map((item) => ({
    ref: item[0],
    format: item[1] || null,
    kind: item[2] === 'E' ? 'EXTERNAL' : 'TABLE',
    more: item[3] === 1,
    // 紧凑形状的第 5 位（`[ref, format, kind, more, stale]`）：图只走紧凑形状，
    // 少这一位「已删除」在图上就彻底看不见了
    stale: item[4] === 1,
  }))
  const edges = []
  for (const item of payload.e || []) {
    const source = nodes[item[0]]
    const target = nodes[item[1]]
    if (!source || !target) continue
    edges.push({
      source: source.ref,
      target: target.ref,
      kind: item[2] === 'E' ? 'EXTERNAL' : 'TABLE',
      writeMode: WRITE_MODES[item[3]] || 'APPEND',
      engine: item[4] || 'SPARK',
      lastSeen: item[5] || 0,
      runCount: item[6] || 0,
      columnCount: item[7] || 0,
    })
  }
  return build(nodes[payload.root ?? 0]?.ref || '', nodes, edges, payload.budget)
}

function decodeFull(payload) {
  const nodes = (payload.nodes || []).map((node) => ({
    ref: node.ref,
    format: node.format || null,
    kind: node.objectType === 'EXTERNAL' ? 'EXTERNAL' : 'TABLE',
    more: Boolean(node.more),
    stale: Boolean(node.stale),
  }))
  const edges = (payload.edges || []).map((edge) => ({
    source: edge.source,
    target: edge.target,
    kind: edge.kind === 'EXTERNAL' ? 'EXTERNAL' : 'TABLE',
    writeMode: edge.writeMode || 'APPEND',
    engine: edge.engine || 'SPARK',
    lastSeen: edge.lastSeen || 0,
    runCount: edge.runCount || 0,
    columnCount: edge.columnCount || 0,
  }))
  return build(payload.root || '', nodes, edges, payload.budget)
}

/** 建索引：邻接表与「按 ref 取节点」。索引只为渲染与命中测试服务，不参与语义。 */
function build(root, nodes, edges, budget) {
  const byRef = new Map()
  for (const node of nodes) byRef.set(node.ref, node)
  const outgoing = new Map()
  const incoming = new Map()
  for (const edge of edges) {
    if (!outgoing.has(edge.source)) outgoing.set(edge.source, [])
    outgoing.get(edge.source).push(edge)
    if (!incoming.has(edge.target)) incoming.set(edge.target, [])
    incoming.get(edge.target).push(edge)
  }
  return {
    root,
    nodes,
    edges,
    byRef,
    outgoing,
    incoming,
    budget: {
      upstream: budget?.upstream ?? 0,
      downstream: budget?.downstream ?? 0,
      returned: budget?.returned ?? nodes.length,
      truncated: Boolean(budget?.truncated),
    },
  }
}

export function emptyGraph(root = '') {
  return build(root, [], [], { upstream: 0, downstream: 0, returned: 0, truncated: false })
}

/** 把一次 `expand` 的响应并进现有图。已存在的节点与边以新响应为准（`more` 会被推进）。 */
export function mergeGraph(base, addition) {
  if (!base || base.nodes.length === 0) return addition
  if (!addition || addition.nodes.length === 0) return base
  const nodes = new Map(base.nodes.map((node) => [node.ref, node]))
  for (const node of addition.nodes) {
    const existing = nodes.get(node.ref)
    // stale / format 只在旧值为空时补：已判定的「表不在目录里」不该被一次展开翻回去
    nodes.set(node.ref, existing
      ? { ...existing, more: node.more || existing.more, format: existing.format || node.format }
      : node)
  }
  const edges = new Map(base.edges.map((edge) => [edgeKey(edge), edge]))
  for (const edge of addition.edges) edges.set(edgeKey(edge), edge)
  const budget = {
    upstream: Math.max(base.budget.upstream, addition.budget.upstream),
    downstream: Math.max(base.budget.downstream, addition.budget.downstream),
    returned: nodes.size,
    truncated: base.budget.truncated || addition.budget.truncated,
  }
  return build(base.root, [...nodes.values()], [...edges.values()], budget)
}

export function edgeKey(edge) {
  return `${edge.source}\u0000${edge.target}\u0000${edge.engine}`
}

/** 一条边的「新鲜度」：超过 30 天没被观测到就淡化（不隐藏，§9.4）。 */
export const STALE_FADE_MS = 30 * 24 * 3600 * 1000

export function isFaded(edge, now) {
  return Boolean(edge.lastSeen) && now - edge.lastSeen > STALE_FADE_MS
}

/** 过滤条件的默认值：全选、不限。 */
export function defaultFilters() {
  return {
    engines: [],          // 空 = 全选
    showExternal: true,   // 外部节点（~file: / ~values / ~external:）
  }
}

/**
 * 应用过滤。
 *
 * <p>返回三样东西，而不是只返回一张新图：
 * <ul>
 *   <li>{@code graph}：隐藏类过滤之后的新图（布局要按它重算）；</li>
 *   <li>{@code faded}：淡化类过滤命中的边（布局**不**因它改变，§9.4(4)）；</li>
 *   <li>{@code removed}：被隐藏的节点数——空态文案要靠它区分「本来就没有」与
 *       「被过滤没了」（§9.6 的三分文案）。</li>
 * </ul>
 */
export function applyFilters(graph, filters, now = Date.now()) {
  const engines = filters?.engines?.length ? new Set(filters.engines) : null
  const showExternal = filters?.showExternal !== false

  const keepNode = (node) => showExternal || node.kind !== 'EXTERNAL'
  const keepEdge = (edge) => !engines || engines.has(edge.engine)

  const nodes = graph.nodes.filter(keepNode)
  const kept = new Set(nodes.map((node) => node.ref))
  const edges = []
  const faded = new Set()
  for (const edge of graph.edges) {
    if (!kept.has(edge.source) || !kept.has(edge.target)) continue
    if (!keepEdge(edge)) continue
    edges.push(edge)
    if (isFaded(edge, now)) faded.add(edgeKey(edge))
  }
  const removed = graph.nodes.length - nodes.length + (graph.edges.length - edges.length)
  const next = build(graph.root, nodes, edges, {
    ...graph.budget,
    upstream: graph.budget.upstream,
    downstream: graph.budget.downstream,
  })
  return { graph: next, faded, removed }
}

/** 引擎候选项：从图里现算，不写死 SPARK（将来接 Flink 时这里自动多一项）。 */
export function engineOptions(graph) {
  const set = new Set()
  for (const edge of graph.edges) if (edge.engine) set.add(edge.engine)
  return [...set].sort()
}

/** 有没有任何列级映射（决定「有列级映射」这个过滤项要不要显示）。 */
export function hasColumnInfo(graph) {
  return graph.edges.some((edge) => edge.columnCount > 0)
}

/** 与某个节点直接相连的边（hover 高亮用）。 */
export function edgesOf(graph, ref) {
  return [...(graph.outgoing.get(ref) || []), ...(graph.incoming.get(ref) || [])]
}

/** 邻居集合（hover 时把其余节点淡化到 12%）。 */
export function neighbourRefs(graph, ref) {
  const refs = new Set([ref])
  for (const edge of edgesOf(graph, ref)) {
    refs.add(edge.source)
    refs.add(edge.target)
  }
  return refs
}
