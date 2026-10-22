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

/**
 * 列级卡片的尺寸（§9.5(1)）：
 *
 * <ul>
 *   <li>{@link CARD_TITLE_H}：标题行（表短名 + 上下游计数），始终显示；</li>
 *   <li>{@link CARD_META_H}：元信息行（库徽标 + 「N列」开关），始终显示；</li>
 *   <li>{@link CARD_ROW_H}：列清单行高；</li>
 *   <li>{@link CARD_BODY_ROWS}：展开后滚动体**固定可见行数**——高度不随列数变，
 *       列多时在卡片内滚（§9.5(1)：固定高度 + 滚动，而不是把宽表撑成一条长卡）。</li>
 * </ul>
 */
export const CARD_TITLE_H = 26
export const CARD_META_H = 22
export const CARD_ROW_H = 22
export const CARD_BODY_ROWS = 6
export const CARD_BODY_MAX_H = CARD_BODY_ROWS * CARD_ROW_H + 8
export const CARD_PAD = 8

/** 卡片头部的总高度（标题 + 元信息）：列清单从它下面开始。 */
export const CARD_HEADER_H = CARD_TITLE_H + CARD_META_H

/**
 * 展开后的滚动体高度：内容不足一屏时按内容收，超过一屏固定为 {@link CARD_BODY_MAX_H}。
 *
 * <p>`rows` 兼容两种形状：`buildCards` 给的是**数组**，测试与调用方也允许直接给
 * 行数——数组不能参与 `+`（`[] + 0` 会静默变成字符串 `'0'`），先归一成数字。
 *
 * @param {{rows: Array|number, overflow: number}} card `lineageColumns.buildCards` 的一项
 * @returns {number} 0 = 没有可见行（展开与收起一样高）
 */
export function cardBodyHeight(card) {
  const count = Array.isArray(card?.rows) ? card.rows.length : (card?.rows || 0)
  const visibleRows = count + (card?.overflow ? 1 : 0)
  if (!visibleRows) return 0
  return Math.min(visibleRows * CARD_ROW_H + 8, CARD_BODY_MAX_H)
}

/**
 * 卡片总高度：头部（标题 + 元信息）始终在，滚动体只在展开时占位。
 *
 * @param card  `lineageColumns.buildCards` 的一项
 * @param expanded 是否展开（「N列」开关点开）
 */
export function cardHeight(card, expanded = false) {
  const body = expanded ? cardBodyHeight(card) : 0
  return CARD_HEADER_H + body + CARD_PAD
}

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
 * @param options.cardOf          列级卡片（`lineageColumns.buildCards` 的产物）。给出时进入
 *                                卡片模式：节点高度按卡片高度算（`positions` 多一个 `h`）、
 *                                不折叠（每张卡都要露出列）、层内按实际高度堆叠；`locked`
 *                                被忽略——卡片高度一变，旧 y 只会把新卡钉在错位上
 * @param options.expandedRefs    列级卡片里「N列」开关已点开的 ref 集合（卡片模式才生效）。
 *                                展开的卡多一块固定高度的滚动体，高度见 `cardHeight`；
 *                                收起的卡只有头部两行
 * @returns `{positions, virtual, hidden, ring, byRank, width, height}`
 */
export function layout(graph, options = {}) {
  const root = options.root || graph.root
  const cardOf = options.cardOf instanceof Map ? options.cardOf : null
  const expandedRefs = options.expandedRefs instanceof Set ? options.expandedRefs : new Set()
  const threshold = cardOf ? Infinity : (options.threshold ?? FOLD_THRESHOLD)
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
    // 折叠：只对「真实节点超过阈值」且没被点开过的层生效；根所在层不折叠。
    // 卡片模式不折叠——每张卡都要露出自己的列清单
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

    if (cardOf) {
      // 卡片模式：层内按实际高度居中堆叠。公式与 centeredY 同源（以中线对称），
      // 只是每张卡的步长由它自己的行数与展开状态决定
      const heights = visible.map((node) => cardHeight(cardOf.get(node.ref), expandedRefs.has(node.ref)))
      const totalH = heights.reduce((sum, h) => sum + h, 0) + GAP_Y * Math.max(0, visible.length - 1)
      let y = -totalH / 2
      visible.forEach((node, index) => {
        positions.set(node.ref, { x, y, h: heights[index], rank, index, virtual: false })
        y += heights[index] + GAP_Y
      })
      if (folded.length) {
        // 卡片模式 threshold=Infinity，理论上走不到；留着以防阈值被外部改小
        virtual.push({
          id: `fold:${rank}`, rank, x,
          y: -totalH / 2 + totalH + GAP_Y,
          count: folded.length,
          refs: folded.map((node) => node.ref),
        })
      }
      continue
    }

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
    // 卡片模式 positions 带 h；表模式缺省 44
    maxY = Math.max(maxY, position.y + (position.h || NODE_H))
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

/**
 * 手动位移：把 `ref → {dx, dy}` 叠在布局坐标上，返回一份新的 positions。
 *
 * <p><b>为什么偏移不进 `layout()`。</b>这一层的卖点是确定性——同一份输入两次算出同样的坐标
 * （刷新一次位置全变，用户就得重新找表）。用户手动拖过的卡片如果写回布局，确定性立刻破掉，
 * 而且没有任何自然的清除时机。所以拖动是**视图层**的叠加：布局照旧算，画与命中时加一次
 * 位移；换根 / 换数据时由调用方把偏移整份丢掉。
 *
 * <p>没有偏移时返回**原来那个 Map**（不是拷贝）：每帧都拷一份 positions 在 500 节点下
 * 是白花的内存与 GC。返回值的元素仍是布局里那些对象（没有偏移的节点不复制）。
 *
 * @param positions `layout()` 的产物
 * @param offsets   `ref → {dx, dy}`，缺项的节点不动
 * @returns 叠加后的 Map（无偏移时就是入参本身）
 */
export function shiftPositions(positions, offsets) {
  if (!(offsets instanceof Map) || !offsets.size) return positions
  const shifted = new Map()
  for (const [ref, at] of positions) {
    const offset = offsets.get(ref)
    shifted.set(ref, offset ? { ...at, x: at.x + offset.dx, y: at.y + offset.dy } : at)
  }
  return shifted
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

/**
 * 自动 fit 的完整视口：`fitScale` 定缩放，这里定平移，返回 `{ k, x, y }` 三个都能
 * 直接喂给视图状态（`screen = (world − view) * k`）。
 *
 * <p><b>符号陷阱（踩过）：</b>图比视口矮时要**居中**，world 0 应落在屏幕
 * `(viewportHeight − height·k) / 2` 处——由 `screen = (0 − view.y)·k` 解出
 * `view.y = (height − viewportHeight/k) / 2`，**是负的**。第一版写成
 * `Math.max(0, (viewportHeight/k − height) / 2)`，符号整个反了：小图被平移出画布
 * 顶部，用户只能拖回来才能看到。超过视口的图不居中（`Math.min` 截到 0），
 * 顶行贴着上沿、向下滚——这也是看大图的习惯方向。
 *
 * <p>横向同理：窄图水平居中，宽图左边贴沿向右滚（血缘是从左往右读的）。
 */
export function fitView(layoutResult, viewportWidth, viewportHeight) {
  const k = fitScale(layoutResult, viewportHeight)
  const centeredY = (layoutResult.height - viewportHeight / k) / 2
  const centeredX = (layoutResult.width - viewportWidth / k) / 2
  return { k, x: Math.min(0, centeredX), y: Math.min(0, centeredY) }
}
