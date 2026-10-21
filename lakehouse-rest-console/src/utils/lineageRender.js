/**
 * 血缘图的绘制层：一帧画什么、怎么画（§9.3）。
 *
 * <p><b>无状态。</b>`drawFrame(ctx, snapshot)` 只读参数、只写画布：没有缓存的可变字段，
 * 也不 import 任何 UI 库。这样它可以被一个 30 行的测试页直接调用（`tools/lineage-bench`），
 * 而不必挂起整个控制台。
 *
 * <p><b>单张 Canvas + 三档 LOD。</b>500 节点 / 1500 边时，DOM 方案在样式重算与 1500 个
 * `path` 上已经越过 16ms 的帧预算；Canvas 批量 path 是 3–8ms。代价是「Canvas 没有 DOM」——
 * 无障碍、`Ctrl+F`、CSS 继承三样都得单独补（见 `LineageAdjacency` 与组件的 aria 属性）。
 *
 * <p><b>格式色只读 CSS 变量。</b>`--fmt-paimon` / `--fmt-iceberg` / `--fmt-external` 与目录页
 * 的格式徽标同源：两处色不一样，用户会以为是两种东西。这个文件里**不允许出现硬编码色值**，
 * `scripts/verify-console.py` 钉着这一条。
 */

const FONT_STACK = '-apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif'

/** 三档 LOD 的视觉参数。数值与 §9.3 的表一一对应。 */
const LOD = [
  { node: 'round', label: 'full', edge: 2, arrow: true, bar: 4 },
  { node: 'rect', label: 'tail', edge: 1.5, arrow: false, bar: 3 },
  { node: 'thin', label: 'none', edge: 1, arrow: false, bar: 0 },
]

/**
 * LOD 档位（§9.3），带滞回：`k` 在临界点附近抖动时不能来回换档。
 *
 * <p>进档阈值（0.9 / 0.55）比退档阈值（0.86 / 0.52）高一点：进档要「明显够大」，
 * 退档要「明显变小」。第三档回到高档用的是 0.93 / 0.58，因为缩到最小再拉回来时，
 * 视觉上更迟钝才不会闪。
 *
 * @param k    当前缩放
 * @param prev 上一档（0 高档 / 1 中档 / 2 低档）
 */
export function lodFor(k, prev = 0) {
  if (prev === 0) return k >= 0.86 ? 0 : (k >= 0.52 ? 1 : 2)
  if (prev === 1) return k >= 0.9 ? 0 : (k >= 0.52 ? 1 : 2)
  return k >= 0.93 ? 0 : (k >= 0.58 ? 1 : 2)
}

/**
 * 从 CSS 变量读一次主题色。
 *
 * <p>Canvas 拿不到 CSS 继承，只能主动读。启动时读一次缓存成对象，主题变化时重读并置
 * `dirty` —— 每次重绘都 `getComputedStyle` 会在拖拽时变成明显的卡顿（§9.3）。
 */
export function readTheme(doc = document) {
  const style = getComputedStyle(doc.documentElement)
  const read = (name, fallback) => (style.getPropertyValue(name) || '').trim() || fallback
  return {
    surface: read('--pc-surface', '#ffffff'),
    text: read('--pc-text', '#1f2937'),
    dim: read('--pc-text-dim', '#6b7280'),
    border: read('--pc-border', '#e2e7f0'),
    accent: read('--pc-accent', '#1d4ed8'),
    // 三个格式色随本功能进 index.css，与目录页的徽标同源
    paimon: read('--fmt-paimon', '#2563eb'),
    iceberg: read('--fmt-iceberg', '#0f9d8f'),
    external: read('--fmt-external', '#8b93a3'),
  }
}

/** 节点主色：外部节点单列一色，因为「点不开」这件事比「哪种格式」更重要。 */
export function colorOf(node, theme) {
  if (!node || node.kind === 'EXTERNAL') return theme.external
  const format = (node.format || '').toUpperCase()
  if (format === 'ICEBERG') return theme.iceberg
  if (format === 'PAIMON') return theme.paimon
  // 目录里查不到格式（旧边、外部引擎）：用中性色，不猜
  return theme.external
}

/** 线宽 ∝ log10(columnCount+1)：1 列 1px、10 列 2px、100 列 3.5px（§9.5(1)）。 */
export function edgeWidth(columnCount) {
  if (!columnCount) return 1
  return 1 + Math.log10(columnCount + 1)
}

/**
 * 画一帧。
 *
 * @param ctx      Canvas 2D 上下文
 * @param snapshot `{positions, virtual, edges, labels, view, width, height, dpr, theme, lod,
 *                   hoverRef, hoverEdge, faded, ring, focusRef, reducedMotion}`
 */
export function drawFrame(ctx, snapshot) {
  const {
    positions = new Map(),
    virtual = [],
    edges = [],
    nodes = [],
    view = { x: 0, y: 0, k: 1 },
    width = 0,
    height = 0,
    dpr = 1,
    theme,
    lod = 0,
    hoverRef = null,
    hoverEdge = null,
    faded = new Set(),
    ring = new Set(),
    focusRef = null,
    neighbours = null,
  } = snapshot

  const style = LOD[lod] || LOD[0]
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  ctx.clearRect(0, 0, width, height)
  ctx.translate(-view.x * view.k, -view.y * view.k)
  ctx.scale(view.k, view.k)

  // 视口在「图坐标」里的范围：只画这一块（裁剪是单点收益最大的优化）
  const bounds = {
    x0: view.x,
    y0: view.y,
    x1: view.x + width / view.k,
    y1: view.y + height / view.k,
  }

  const nodeAt = new Map(nodes.map((node) => [node.ref, node]))
  const place = (ref) => positions.get(ref)

  // ---- 边：按 (颜色, 线宽) 分组，每组一个 Path2D，最后一次 stroke -------------
  // 1500 条边因此从 1500 次 draw call 降到 ≤ 6 次
  const groups = new Map()
  const arrows = []
  for (const edge of edges) {
    const a = place(edge.source)
    const b = place(edge.target)
    if (!a || !b) continue
    if (!visible(a, b, bounds)) continue
    const key = edgeKey(edge)
    const isFaded = faded.has(key)
    const color = isFaded ? theme.border : colorOf(nodeAt.get(edge.target), theme)
    const w = edgeWidth(edge.columnCount) * (lod === 2 ? 0.8 : 1)
    const bucket = `${color}|${w.toFixed(2)}|${isFaded ? 1 : 0}`
    if (!groups.has(bucket)) groups.set(bucket, { color, width: w, faded: isFaded, path: new Path2D() })
    const path = groups.get(bucket).path
    const geometry = edgePath(a, b, ring.has(key))
    path.moveTo(geometry.x0, geometry.y0)
    path.bezierCurveTo(geometry.c1x, geometry.c1y, geometry.c2x, geometry.c2y, geometry.x1, geometry.y1)
    // 箭头单独收集：`fill` 不能与 `stroke` 共用一个 path（线宽与填充色的语义不同）。
    // 悬停边不再换色——它的颜色已经是目标表的格式色，再加深就与「选中」混淆了
    if (style.arrow && !isFaded) {
      arrows.push({ ...geometry, color })
    }
  }

  for (const group of groups.values()) {
    ctx.globalAlpha = group.faded ? 0.22 : 1
    ctx.strokeStyle = group.color
    ctx.lineWidth = group.width
    ctx.stroke(group.path)
  }
  ctx.globalAlpha = 1

  // 悬停的那条边补画一次：批量描边之后它的颜色与其余同格式边完全一样，不补就看不出
  // 「这个 tooltip 说的是哪一条」。只多一次 draw call，而且只在悬停时发生
  if (hoverEdge) {
    const a = place(hoverEdge.source)
    const b = place(hoverEdge.target)
    if (a && b) {
      const geometry = edgePath(a, b, ring.has(edgeKey(hoverEdge)))
      ctx.strokeStyle = theme.accent
      ctx.lineWidth = edgeWidth(hoverEdge.columnCount) + 1.5
      ctx.beginPath()
      ctx.moveTo(geometry.x0, geometry.y0)
      ctx.bezierCurveTo(geometry.c1x, geometry.c1y, geometry.c2x, geometry.c2y,
        geometry.x1, geometry.y1)
      ctx.stroke()
    }
  }

  if (style.arrow) {
    for (const arrow of arrows) {
      ctx.fillStyle = arrow.color
      drawArrow(ctx, arrow)
    }
  }

  // ---- 节点 ----------------------------------------------------------------
  const labelBoxes = []
  const order = [...nodes].sort((a, b) => {
    // 根与 hover 的节点最后画，保证压在最上面
    const rankA = a.ref === hoverRef ? 2 : a.ref === focusRef ? 1 : 0
    const rankB = b.ref === hoverRef ? 2 : b.ref === focusRef ? 1 : 0
    return rankA - rankB
  })
  for (const node of order) {
    const at = place(node.ref)
    if (!at) continue
    if (!visible(at, { x: at.x + 200, y: at.y + 44 }, bounds)) continue
    const color = colorOf(node, theme)
    const dimmed = hoverRef && neighbours && !neighbours.has(node.ref)
    ctx.globalAlpha = dimmed ? 0.18 : 1
    drawNode(ctx, at, node, color, theme, style, node.ref === focusRef)
    if (style.label !== 'none') {
      drawLabel(ctx, at, node, theme, style.label, labelBoxes, node.ref === hoverRef)
    }
    ctx.globalAlpha = 1
  }

  // 折叠占位节点：虚线框 + `+N`，点它展开该层（§9.4(2)）
  for (const fold of virtual) {
    ctx.save()
    ctx.setLineDash([4, 3])
    ctx.strokeStyle = theme.dim
    ctx.fillStyle = theme.surface
    ctx.lineWidth = 1
    ctx.beginPath()
    roundRect(ctx, fold.x, fold.y, 84, 28, 6)
    ctx.fill()
    ctx.stroke()
    ctx.restore()
    ctx.fillStyle = theme.dim
    ctx.font = `11px ${FONT_STACK}`
    ctx.textBaseline = 'middle'
    ctx.textAlign = 'center'
    ctx.fillText(`+${fold.count}`, fold.x + 42, fold.y + 14)
    ctx.textAlign = 'left'
  }
}

function edgeKey(edge) {
  return `${edge.source}\u0000${edge.target}\u0000${edge.engine}`
}

function visible(a, b, bounds) {
  const pad = 40
  const minX = Math.min(a.x, b.x)
  const maxX = Math.max(a.x, b.x)
  const minY = Math.min(a.y, b.y)
  const maxY = Math.max(a.y, b.y)
  return maxX >= bounds.x0 - pad && minX <= bounds.x1 + pad
    && maxY >= bounds.y0 - pad && minY <= bounds.y1 + pad
}

/**
 * 一条边的几何：正向边从右中到左中的三次贝塞尔；回边（ring）从上方绕行。
 *
 * <p>回边必须绕到层外，否则会横穿节点本身——`A → B → A` 在图上会变成一条压在两个
 * 矩形上的直线，看起来像「同一层里的兄弟关系」。
 */
function edgePath(a, b, isRing) {
  const x0 = a.x + 200
  const y0 = a.y + 22
  if (isRing || b.x <= a.x) {
    const x1 = b.x + 200
    const y1 = b.y + 22
    const lift = 46
    return {
      x0, y0,
      c1x: x0 + 60, c1y: y0 - lift,
      c2x: x1 + 60, c2y: y1 - lift,
      x1, y1,
    }
  }
  const x1 = b.x
  const y1 = b.y + 22
  const mid = (x0 + x1) / 2
  return { x0, y0, c1x: mid, c1y: y0, c2x: mid, c2y: y1, x1, y1 }
}

function drawArrow(ctx, geometry) {
  // 箭头方向取贝塞尔末段的切线：控制点 2 → 终点
  const angle = Math.atan2(geometry.y1 - geometry.c2y, geometry.x1 - geometry.c2x)
  const size = 6
  ctx.beginPath()
  ctx.moveTo(geometry.x1, geometry.y1)
  ctx.lineTo(geometry.x1 - size * Math.cos(angle - Math.PI / 7),
    geometry.y1 - size * Math.sin(angle - Math.PI / 7))
  ctx.lineTo(geometry.x1 - size * Math.cos(angle + Math.PI / 7),
    geometry.y1 - size * Math.sin(angle + Math.PI / 7))
  ctx.closePath()
  ctx.fill()
}

function drawNode(ctx, at, node, color, theme, style, isFocus) {
  if (style.node === 'thin') {
    ctx.fillStyle = color
    ctx.fillRect(at.x, at.y + 16, 200, 12)
    return
  }
  const height = style.node === 'rect' ? 32 : 44
  ctx.fillStyle = theme.surface
  ctx.strokeStyle = isFocus ? theme.accent : theme.border
  ctx.lineWidth = isFocus ? 2 : 1
  ctx.beginPath()
  if (style.node === 'round') {
    roundRect(ctx, at.x, at.y, 200, height, 6)
  } else {
    ctx.rect(at.x, at.y, 200, height)
  }
  ctx.fill()
  ctx.stroke()
  // 左侧格式色条：与目录页徽标同色，是「这是什么表」的唯一视觉线索
  ctx.fillStyle = color
  ctx.fillRect(at.x, at.y, style.bar, height)
  // MORE 徽标：该方向上还有一跳没展开。点它只拉这一个节点的邻居（§9.4(1)）——
  // 不画的话，图看起来「到头了」但其实没有
  if (node.more && style.node !== 'thin') {
    const cx = at.x + 190
    const cy = at.y + height / 2
    ctx.beginPath()
    ctx.arc(cx, cy, 8, 0, Math.PI * 2)
    ctx.fillStyle = theme.surface
    ctx.fill()
    ctx.strokeStyle = theme.border
    ctx.lineWidth = 1
    ctx.stroke()
    ctx.fillStyle = theme.dim
    ctx.font = `600 11px ${FONT_STACK}`
    ctx.textAlign = 'center'
    ctx.textBaseline = 'middle'
    ctx.fillText('›', cx, cy)
    ctx.textAlign = 'left'
  }
}

function drawLabel(ctx, at, node, theme, mode, boxes, highlighted) {
  const text = mode === 'tail' ? tailOf(node.ref) : node.ref
  const maxWidth = mode === 'tail' ? 180 : 188
  const label = clipText(ctx, text, maxWidth)
  ctx.font = `${highlighted ? 600 : 400} 12px ${FONT_STACK}`
  ctx.fillStyle = theme.text
  ctx.textBaseline = 'middle'
  const box = { x: at.x + 10, y: at.y + 8, w: ctx.measureText(label).width + 4, h: 18 }
  if (intersectsAny(box, boxes)) {
    // 与本帧已放置的标签相交 → 这一帧不画（悬停时补画）。不做全局最优：O(n²) 也只多放 5%
    return
  }
  boxes.push(box)
  ctx.fillText(label, box.x, at.y + 17)
  if (node.stale) {
    ctx.fillStyle = theme.dim
    ctx.font = `10px ${FONT_STACK}`
    ctx.fillText('已删除', box.x + box.w + 6, at.y + 17)
  }
}

/** 中间省略：保留首段与末段（`paimon.dws.…order_daily`）。尾部截断看着像「又一张 order_daily」。 */
export function ellipsizeMiddle(name, maxChars = 26) {
  if (!name || name.length <= maxChars) return name || ''
  const tail = name.slice(-Math.ceil((maxChars - 1) * 0.55))
  const head = name.slice(0, maxChars - 1 - tail.length)
  return `${head}…${tail}`
}

export function tailOf(ref) {
  const parts = (ref || '').split('.')
  return parts[parts.length - 1] || ref
}

function clipText(ctx, text, maxWidth) {
  const label = ellipsizeMiddle(text, 32)
  if (ctx.measureText(label).width <= maxWidth) return label
  let trimmed = label
  while (trimmed.length > 4 && ctx.measureText(`${trimmed}…`).width > maxWidth) {
    trimmed = trimmed.slice(0, -1)
  }
  return `${trimmed}…`
}

function intersectsAny(box, boxes) {
  for (const other of boxes) {
    if (box.x < other.x + other.w && box.x + box.w > other.x
      && box.y < other.y + other.h && box.y + box.h > other.y) {
      return true
    }
  }
  return false
}

function roundRect(ctx, x, y, w, h, r) {
  const radius = Math.min(r, h / 2, w / 2)
  ctx.moveTo(x + radius, y)
  ctx.lineTo(x + w - radius, y)
  ctx.quadraticCurveTo(x + w, y, x + w, y + radius)
  ctx.lineTo(x + w, y + h - radius)
  ctx.quadraticCurveTo(x + w, y + h, x + w - radius, y + h)
  ctx.lineTo(x + radius, y + h)
  ctx.quadraticCurveTo(x, y + h, x, y + h - radius)
  ctx.lineTo(x, y + radius)
  ctx.quadraticCurveTo(x, y, x + radius, y)
  ctx.closePath()
}

/** 命中测试：视口内最多几百个节点，线性扫描比维护空间索引更不容易写错。 */
export function hitTest(snapshot, point) {
  const { positions, virtual, view } = snapshot
  const x = point.x / view.k + view.x
  const y = point.y / view.k + view.y
  for (const fold of virtual) {
    if (x >= fold.x && x <= fold.x + 84 && y >= fold.y && y <= fold.y + 28) {
      return { type: 'fold', fold }
    }
  }
  for (const [ref, at] of positions) {
    const height = 44
    if (x >= at.x && x <= at.x + 200 && y >= at.y && y <= at.y + height) {
      return { type: 'node', ref }
    }
  }
  return null
}

/** 边 hover 的命中：取离光标最近的边（采样贝塞尔，误差 2px 内即可）。 */
export function hitEdge(snapshot, point, tolerance = 5) {
  const { edges, positions, view } = snapshot
  const x = point.x / view.k + view.x
  const y = point.y / view.k + view.y
  let best = null
  for (const edge of edges) {
    const a = positions.get(edge.source)
    const b = positions.get(edge.target)
    if (!a || !b) continue
    const geometry = edgePath(a, b, false)
    for (let t = 0; t <= 1; t += 0.1) {
      const px = cubic(geometry.x0, geometry.c1x, geometry.c2x, geometry.x1, t)
      const py = cubic(geometry.y0, geometry.c1y, geometry.c2y, geometry.y1, t)
      const distance = Math.hypot(px - x, py - y)
      if (distance <= tolerance && (!best || distance < best.distance)) {
        best = { edge, distance }
      }
    }
  }
  return best?.edge || null
}

function cubic(p0, p1, p2, p3, t) {
  const s = 1 - t
  return s * s * s * p0 + 3 * s * s * t * p1 + 3 * s * t * t * p2 + t * t * t * p3
}
