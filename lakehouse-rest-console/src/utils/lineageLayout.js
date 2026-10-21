/**
 * 血缘图的布局：跳数即层号（§9.2）。
 *
 * <p><b>为什么不用 Sugiyama / dagre。</b>通用分层布局要做「拆长边成虚拟节点 + 多层交叉
 * 最小化 + 坐标分配」三步；本图的层已经由「离根几跳」给定，只剩最后一小步。
 * 引一个 60KB 的库换一个用不上的中间阶段不划算（§9.7）。
 *
 * <p><b>确定性是硬要求。</b>图的空间记忆靠位置：刷新一次位置全变，用户就得重新找。
 * 因此这个模块是 `(nodes, edges, root, 折叠状态)` 的纯函数——无随机数、无时间戳，
 * 所有排序都带 {@code ref} 做 tie-break，不依赖输入数组顺序（§11.6 的用例钉着这条）。
 *
 * <p>三条规则：
 * <ul>
 *   <li>{@code rank(v) = +hops(v)}（下游，画在根右边）/ {@code -hops(v)}（上游，左边）；
 *       同一节点两条路径时取 {@code min |rank|}，否则一条长链会把图横向拉到几十层。</li>
 *   <li>环由 BFS 的 visited 天然阻断，回边单独标记（画成绕行的曲线 + {@code ↺}），
 *       且不参与 rank 计算。</li>
 *   <li>层内顺序：先按 ref 字典序，再两遍重心排序（上游层看下游邻居、下游层看上游邻居）。
 *       两遍足够，第三遍的边际收益视觉上不可辨。</li>
 * </ul>
 */

/** 节点尺寸固定：让裁剪、命中测试、空间索引全部退化成整数比较（§9.2）。 */
export const NODE_W = 200
export const NODE_H = 44
export const GAP_X = 80
export const GAP_Y = 16

/** 单层折叠阈值：超过就只画前 8 个 + 一个 `+N` 占位（§9.4(2)）。 */
export const FOLD_THRESHOLD = 8

/**
 * 布局。
 *
 * @param graph  `{ root, nodes, edges, outgoing, incoming }`（`lineageGraph.decode` 的产物）
 * @param options.root            图的根（默认取 `graph.root`）
 * @param options.threshold       单层折叠阈值，默认 {@link FOLD_THRESHOLD}
 * @param options.expandedLayers  已经点开过「+N」的层（这些层不再折叠）
 * @param options.locked          已放置节点的坐标（增量合并时锁定，§9.4(1)）
 * @returns `{positions, virtual, hidden, ring, byRank, width, height}`
 */
export function layout(graph, options = {}) {
  const root = options.root || graph.root
  const threshold = options.threshold ?? FOLD_THRESHOLD
  const expanded = options.expandedLayers instanceof Set
    ? options.expandedLayers
    : new Set(options.expandedLayers || [])
  const locked = options.locked || new Map()

  const rankOf = computeRanks(graph, root)
  if (!rankOf.size) {
    return emptyLayout()
  }

  // 按层分组；不在 rankOf 里的节点（孤立点）不画——它们与根不连通
  const byRank = new Map()
  for (const node of graph.nodes) {
    const rank = rankOf.get(node.ref)
    if (rank === undefined) continue
    if (!byRank.has(rank)) byRank.set(rank, [])
    byRank.get(rank).push(node)
  }
  const ranks = [...byRank.keys()].sort((a, b) => a - b)

  // 层内初始顺序：ref 字典序（确定性），根排在自己那一层的最前
  for (const rank of ranks) {
    byRank.get(rank).sort((a, b) => {
      if (a.ref === root) return -1
      if (b.ref === root) return 1
      return a.ref.localeCompare(b.ref)
    })
  }

  // 两遍重心排序：先下游（左→右），再上游（右→左）
  orderByBarycenter(byRank, ranks.filter((rank) => rank > 0), graph, rankOf, 'up')
  orderByBarycenter(byRank, ranks.filter((rank) => rank < 0).reverse(), graph, rankOf, 'down')

  // 增量合并：已放置节点按原 y 排在前面，新节点接在后面
  if (locked.size) {
    for (const rank of ranks) {
      const layer = byRank.get(rank)
      layer.sort((a, b) => {
        const la = locked.get(a.ref)
        const lb = locked.get(b.ref)
        if (la && lb) return la.y - lb.y
        if (la) return -1
        if (lb) return 1
        return a.ref.localeCompare(b.ref)
      })
    }
  }

  const positions = new Map()
  const virtual = []
  const hidden = new Set()
  const maxRank = ranks[ranks.length - 1]
  const minRank = ranks[0]

  for (const rank of ranks) {
    const layer = byRank.get(rank)
    // 折叠：只对「真实节点超过阈值」且没被点开过的层生效；根所在层不折叠
    let visible = layer
    let folded = []
    if (rank !== 0 && layer.length > threshold && !expanded.has(rank)) {
      const ranked = [...layer].sort((a, b) => weightOf(graph, b.ref) - weightOf(graph, a.ref)
        || a.ref.localeCompare(b.ref))
      visible = ranked.slice(0, threshold)
      folded = ranked.slice(threshold)
      for (const node of folded) hidden.add(node.ref)
    }
    const total = visible.length + (folded.length ? 1 : 0)
    const x = (rank - minRank) * (NODE_W + GAP_X)

    // 未锁定的节点接在本层最后一个已锁定节点之后。
    // 为什么不能继续用 `centeredY`：那两套公式算的是两批行号，锁定的那个 y 与
    // 「第几行」之间没有关系，于是新节点会正好压在老节点上——展开一次就像掉了一层。
    let nextY = null
    for (const node of visible) {
      const at = locked.get(node.ref)
      if (at) nextY = nextY === null ? at.y : Math.max(nextY, at.y)
    }
    if (nextY !== null) nextY += NODE_H + GAP_Y

    visible.forEach((node, index) => {
      const lockedAt = locked.get(node.ref)
      let y
      if (lockedAt) {
        y = lockedAt.y
      } else if (nextY !== null) {
        y = nextY
        nextY += NODE_H + GAP_Y
      } else {
        y = centeredY(index, total)
      }
      positions.set(node.ref, { x, y, rank, index, virtual: false })
    })
    if (folded.length) {
      virtual.push({
        id: `fold:${rank}`,
        rank,
        x,
        y: nextY === null ? centeredY(total - 1, total) : nextY,
        count: folded.length,
        refs: folded.map((node) => node.ref),
      })
    }
  }

  // 归一化：最小 x/y 归到 0，避免负坐标在画布里做额外平移
  let minX = Infinity
  let minY = Infinity
  let maxX = -Infinity
  let maxY = -Infinity
  for (const position of positions.values()) {
    minX = Math.min(minX, position.x)
    minY = Math.min(minY, position.y)
    maxX = Math.max(maxX, position.x + NODE_W)
    maxY = Math.max(maxY, position.y + NODE_H)
  }
  for (const node of virtual) {
    minY = Math.min(minY, node.y)
    maxY = Math.max(maxY, node.y + NODE_H)
  }
  if (!Number.isFinite(minX)) return emptyLayout()
  for (const position of positions.values()) {
    position.x -= minX
    position.y -= minY
  }
  for (const node of virtual) {
    node.x -= minX
    node.y -= minY
  }

  return {
    positions,
    virtual,
    hidden,
    ring: ringEdges(graph, rankOf),
    byRank,
    minRank,
    maxRank,
    width: maxX - minX,
    height: maxY - minY,
  }
}

function emptyLayout() {
  return {
    positions: new Map(),
    virtual: [],
    hidden: new Set(),
    ring: new Set(),
    byRank: new Map(),
    minRank: 0,
    maxRank: 0,
    width: 0,
    height: 0,
  }
}

/**
 * 层内第 index 行的 y：以本层中线对称铺开。
 *
 * <p>返回值可能为负，由 `layout` 末尾的整体归一化平移掉——这里不加偏移量，
 * 因为「加一个 step」只是把同一个常数换个位置写，读的人还得再推一遍它为什么在那儿。
 */
function centeredY(index, total) {
  return index * (NODE_H + GAP_Y) - ((total - 1) * (NODE_H + GAP_Y)) / 2
}

/**
 * 跳数 → rank。
 *
 * <p>两个方向各做一次 BFS，再合并：取绝对值小的那个。一次 BFS 同时往两边走是不行的——
 * 那会把「上游 3 跳」与「下游 3 跳」混成同一个 visited，后到的方向再也走不过去。
 */
function computeRanks(graph, root) {
  const rankOf = new Map()
  if (!graph.byRef.has(root)) return rankOf
  const down = bfs(graph, root, graph.outgoing)
  const up = bfs(graph, root, graph.incoming)
  rankOf.set(root, 0)
  for (const [ref, hop] of down) {
    if (ref === root) continue
    rankOf.set(ref, hop)
  }
  for (const [ref, hop] of up) {
    if (ref === root) continue
    const existing = rankOf.get(ref)
    if (existing === undefined || hop < existing) {
      rankOf.set(ref, -hop)
    }
  }
  return rankOf
}

function bfs(graph, root, adjacency) {
  const hops = new Map([[root, 0]])
  const queue = [root]
  while (queue.length) {
    const current = queue.shift()
    const hop = hops.get(current)
    for (const edge of adjacency.get(current) || []) {
      const next = adjacency === graph.outgoing ? edge.target : edge.source
      if (hops.has(next)) continue
      hops.set(next, hop + 1)
      queue.push(next)
    }
  }
  return hops
}

/**
 * 回边：沿 rank 不增的边。
 *
 * <p>{@code A → B → A} 的回刷是真实存在的（定时任务把结果写回上游），
 * 不能当错误处理：BFS 的 visited 会阻断第二次展开，而这条边仍要画出来——
 * 它说明「这两张表互相喂」，删掉反而是丢信息（§9.2）。
 */
function ringEdges(graph, rankOf) {
  const ring = new Set()
  for (const edge of graph.edges) {
    const sourceRank = rankOf.get(edge.source)
    const targetRank = rankOf.get(edge.target)
    if (sourceRank === undefined || targetRank === undefined) continue
    if (targetRank <= sourceRank) {
      ring.add(`${edge.source}\u0000${edge.target}\u0000${edge.engine}`)
    }
  }
  return ring
}

/** 节点的「分量」：连接边的 runCount 之和。折叠时留分量大的（常跑的干路）。 */
function weightOf(graph, ref) {
  let weight = 0
  for (const edge of graph.outgoing.get(ref) || []) weight += edge.runCount || 0
  for (const edge of graph.incoming.get(ref) || []) weight += edge.runCount || 0
  return weight
}

/**
 * 重心排序：按相邻层邻居的平均「行号」重排本层。
 *
 * <p>{@code direction='up'} 表示「看上游邻居」（下游层用它），{@code 'down'} 反之。
 * 两遍足够：第三遍在视觉上已不可辨，而每遍是 O(n log n)（§9.2）。
 */
function orderByBarycenter(byRank, ranks, graph, rankOf, direction) {
  const indexOf = new Map()
  for (const rank of byRank.keys()) {
    byRank.get(rank).forEach((node, index) => indexOf.set(node.ref, index))
  }
  for (const rank of ranks) {
    const layer = byRank.get(rank)
    if (!layer || layer.length < 2) continue
    const barycenter = new Map()
    for (const node of layer) {
      const edges = direction === 'up'
        ? (graph.incoming.get(node.ref) || [])
        : (graph.outgoing.get(node.ref) || [])
      const neighbours = edges
        .map((edge) => (direction === 'up' ? edge.source : edge.target))
        .filter((ref) => rankOf.get(ref) !== undefined && indexOf.has(ref))
      barycenter.set(node.ref, neighbours.length
        ? neighbours.reduce((sum, ref) => sum + indexOf.get(ref), 0) / neighbours.length
        : indexOf.get(node.ref))
    }
    layer.sort((a, b) => barycenter.get(a.ref) - barycenter.get(b.ref) || a.ref.localeCompare(b.ref))
    layer.forEach((node, index) => indexOf.set(node.ref, index))
  }
}

/**
 * 自动 fit：图高超过视口 1.5 倍时缩到一屏（下限 0.25，到底了就只保证横向滚动）。
 *
 * <p>给的是全貌，而不是让用户手工缩——大多数人不缩，看到半张图就关掉了（§9.4(3)）。
 */
export function fitScale(layoutResult, viewportHeight) {
  if (!layoutResult.height || viewportHeight <= 0) return 1
  if (layoutResult.height <= viewportHeight * 1.5) return 1
  return Math.max(0.25, (viewportHeight - 24) / layoutResult.height)
}
