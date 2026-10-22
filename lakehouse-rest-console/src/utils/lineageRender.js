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

const MONO_STACK = 'ui-monospace, SFMono-Regular, Menlo, monospace'

// 卡片模式的尺寸常量与布局层同源（单一出处）；NODE_W 也供列连线的起点用
import { CARD_HEADER_H, CARD_META_H, CARD_ROW_H, CARD_TITLE_H, NODE_W, cardBodyHeight } from './lineageLayout.js'
import { typeKindOf } from './lineageColumns.js'

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
 *                   hoverRef, hoverEdge, faded, ring, focusRef, neighbours,
 *                   cards, columnEdges, hoverCol, selectedRef, expandedRefs, scrolls, degrees}`
 *
 *                   后五项是列级视图：`cards` 是 `lineageColumns.buildCards` 的产物
 *                   （卡片列清单），给出时节点画成卡片；`columnEdges` 非空时再叠一层
 *                   列-列贝塞尔，表级边同时淡化成引导线（没有列边就按正常强度画）；
 *                   `hoverCol` 是悬停的列（`{ref, name}`），它的连线用强调色补画一次；
 *                   `selectedRef` 是点选的表（只高亮表头）；`expandedRefs` 是「N列」开关
 *                   点开的 ref 集合（决定卡片有没有滚动体）；`scrolls` 是各卡滚动体的
 *                   滚动位移；`degrees` 是 `ref → {up, down}`（上下游表计数，画在标题行）。
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
    cards = null,
    columnEdges = [],
    hoverCol = null,
    selectedRef = '',
    expandedRefs = new Set(),
    scrolls = new Map(),
    degrees = null,
  } = snapshot

  const cardMode = cards instanceof Map && cards.size > 0
  // 「画卡片」与「画列边」是两件事：卡片是常态，列-列连线是开关（§9.5(1)）。
  // 没有列边时表级边按正常强度画——淡成 0.15 的引导线在没有列线的图里等于看不见关系
  const columnFlow = cardMode && columnEdges.length > 0

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
    // 悬停边不再换色——它的颜色已经是目标表的格式色，再加深就与「选中」混淆了。
    // 卡片模式下表级边退化为引导线，箭头一并省掉（落点不再是表中心）
    // 卡片模式下表级边只是「这两张表之间有关系」的引导线，列连线才是主角
    if (style.arrow && !isFaded && !columnFlow) {
      arrows.push({ ...geometry, color })
    }
  }

  for (const group of groups.values()) {
    ctx.globalAlpha = group.faded ? 0.22 : 1
    // 有列连线时表级边退成引导线；没有列连线时它就是主角，不淡化
    ctx.globalAlpha = (group.faded ? 0.22 : 1) * (columnFlow ? 0.15 : 1)
    ctx.strokeStyle = group.color
    ctx.lineWidth = group.width
    ctx.stroke(group.path)
  }
  ctx.globalAlpha = 1

  // 悬停的那条边补画一次：批量描边之后它的颜色与其余同格式边完全一样，不补就看不出
  // 「这个 tooltip 说的是哪一条」。只多一次 draw call，而且只在悬停时发生
  if (hoverEdge && !columnFlow) {
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

  // ---- 列级连线（卡片 + 开关打开） ---------------------------------------------
  // 常量行（无源）在面板侧已被滤掉：它们只有卡片上的行，没有画出来的线
  if (columnFlow) {
    const batch = new Path2D()
    const hot = new Path2D()
    let hotCount = 0
    for (const edge of columnEdges) {
      const a = place(edge.s)
      const b = place(edge.t)
      if (!a || !b) continue
      const ia = rowIndexAt(cards.get(edge.s), edge.sCol)
      const ib = rowIndexAt(cards.get(edge.t), edge.tCol)
      if (ia < 0 || ib < 0) continue
      const isHot = !!hoverCol
        && ((hoverCol.ref === edge.s && hoverCol.name === edge.sCol)
          || (hoverCol.ref === edge.t && hoverCol.name === edge.tCol))
      const path = isHot ? hot : batch
      const x0 = a.x + NODE_W
      const y0 = rowAnchorY(a, cards.get(edge.s), ia, expandedRefs.has(edge.s), scrolls.get(edge.s) || 0)
      const x1 = b.x
      const y1 = rowAnchorY(b, cards.get(edge.t), ib, expandedRefs.has(edge.t), scrolls.get(edge.t) || 0)
      const mid = (x0 + x1) / 2
      path.moveTo(x0, y0)
      path.bezierCurveTo(mid, y0, mid, y1, x1, y1)
      if (isHot) hotCount += 1
    }
    ctx.strokeStyle = theme.border
    ctx.lineWidth = 1
    ctx.stroke(batch)
    if (hotCount) {
      ctx.strokeStyle = theme.accent
      ctx.lineWidth = 1.6
      ctx.stroke(hot)
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
    if (!visible(at, { x: at.x + NODE_W, y: at.y + (at.h || 44) }, bounds)) continue
    const color = colorOf(node, theme)
    const dimmed = hoverRef && neighbours && !neighbours.has(node.ref)
    ctx.globalAlpha = dimmed ? 0.18 : 1
    if (cardMode) {
      drawCard(ctx, at, node, cards.get(node.ref) || EMPTY_CARD, color, theme,
        node.ref === focusRef, hoverCol,
        expandedRefs.has(node.ref), scrolls.get(node.ref) || 0,
        node.ref === selectedRef, degrees)
    } else {
      drawNode(ctx, at, node, color, theme, style, node.ref === focusRef)
      if (style.label !== 'none') {
        drawLabel(ctx, at, node, theme, style.label, labelBoxes, node.ref === hoverRef)
      }
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

const EMPTY_CARD = { rows: [], overflow: 0, rowIndexOf: new Map() }

/** 列在卡片上的行号；查不到（被 `+N` 折掉）钳到最后一行，连线落在 `+N` 上。 */
function rowIndexAt(card, name) {
  if (!card) return -1
  const index = card.rowIndexOf.get(name)
  if (index !== undefined) return index
  return card.rows.length ? card.rows.length - 1 : -1
}

/**
 * 列连线的落点纵坐标。
 *
 * <p>展开的卡：随行走、随滚动走，滚出滚动体的部分钳回体沿——连线永远指向卡片，
 * 不会悬在卡片外面的空白上。收起的卡：落在卡片竖直中点（此时没有列行可指，
 * 这条线只是「两张表之间有关系」的提示）。
 */
function rowAnchorY(at, card, index, expanded, scrollTop) {
  if (!expanded) return at.y + (at.h || 44) / 2
  const top = at.y + CARD_HEADER_H
  const bodyH = cardBodyHeight(card)
  const raw = top + index * CARD_ROW_H + CARD_ROW_H / 2 - scrollTop
  const min = top + CARD_ROW_H / 2
  const max = top + bodyH - CARD_ROW_H / 2
  return Math.min(Math.max(raw, Math.min(min, max)), Math.max(min, max))
}

/** 列 kind 的点色：直通用中性色，变换/聚合强调，常量与未解析用外部色（同目录页语义）。 */
function kindColor(kind, theme) {
  if (kind === 'DERIVED' || kind === 'AGGREGATE') return theme.accent
  if (kind === 'CONSTANT' || kind === 'UNRESOLVED') return theme.external
  return theme.dim
}

/** 数据类型图标：一个字母/符号，类别不同字形与颜色都不同（§9.5(1)）。 */
const TYPE_GLYPHS = { num: '#', str: 'T', bool: 'B', complex: '{}', other: '·' }

function typeGlyphOf(type) {
  return TYPE_GLYPHS[typeKindOf(type)] || ''
}

function typeColorOf(type, theme) {
  const kind = typeKindOf(type)
  if (kind === 'num') return theme.paimon
  if (kind === 'str') return theme.iceberg
  if (kind === 'bool') return theme.accent
  if (kind === 'complex') return theme.external
  return theme.dim
}

/**
 * ref 去掉末段的表名，剩下的 `catalog.namespace`。
 *
 * <p>卡片标题只显示表短名（§9.5(1)），catalog 与库名放在这里：元信息行的库徽标与
 * 悬停 tooltip 都用它。没有中间段（裸表名）返回空串——那种 ref 没什么可提示的。
 */
export function prefixOf(ref) {
  const parts = (ref || '').split('.')
  return parts.length > 1 ? parts.slice(0, -1).join('.') : ''
}

/** 滚动体内容高度：可见行（含 `+N` 行）× 行高 + 底部留白，与 cardBodyHeight 的 8 对齐。 */
function cardContentHeight(card) {
  const count = Array.isArray(card?.rows) ? card.rows.length : (card?.rows || 0)
  return (count + (card?.overflow ? 1 : 0)) * CARD_ROW_H + 8
}

/**
 * 列级卡片：标题行（表短名 + 上下游计数）+ 元信息行（库徽标 + 「N列」开关）
 * + 展开时的固定高度滚动体（类型图标 + 列名 + kind 点，滚轮上下滚）。
 *
 * <p>标题只画表短名（`tailOf`）——catalog 与库名在元信息行与 tooltip 里；
 * 点选的高亮**只覆盖表头**（标题行 + 元信息行），且是一层很淡的强调色：整卡填充强调色
 * 太刺眼，而列清单正是点选后要读的东西——它比表头更需要对比度（§9.5(1)）。
 */
function drawCard(ctx, at, node, card, color, theme, isFocus, hoverCol, expanded, scrollTop, selected, degrees) {
  const h = at.h || NODE_H
  const radius = 6
  // 卡体恒为普通背景：高亮不铺满整卡
  ctx.fillStyle = theme.surface
  ctx.beginPath()
  roundRect(ctx, at.x, at.y, NODE_W, h, radius)
  ctx.fill()

  // 选中：只在表头那一块叠一层淡强调色。clip 到卡片圆角里，左上的圆角不被方角切掉
  if (selected) {
    ctx.save()
    ctx.beginPath()
    roundRect(ctx, at.x, at.y, NODE_W, h, radius)
    ctx.clip()
    ctx.globalAlpha *= 0.16
    ctx.fillStyle = theme.accent
    ctx.fillRect(at.x, at.y, NODE_W, CARD_HEADER_H)
    ctx.restore()
  }

  ctx.strokeStyle = (selected || isFocus) ? theme.accent : theme.border
  ctx.lineWidth = (selected || isFocus) ? 2 : 1
  ctx.beginPath()
  roundRect(ctx, at.x, at.y, NODE_W, h, radius)
  ctx.stroke()
  // 左侧格式色条：与表模式同源，是「这是什么表」的唯一视觉线索
  ctx.fillStyle = color
  ctx.fillRect(at.x, at.y, 4, h)
  const textColor = theme.text
  const dimColor = theme.dim

  // ---- 标题行：表短名 + 上下游计数 ----
  const titleY = at.y + CARD_TITLE_H / 2
  ctx.font = `600 12px ${FONT_STACK}`
  ctx.fillStyle = textColor
  ctx.textBaseline = 'middle'
  ctx.fillText(clipText(ctx, tailOf(node.ref), NODE_W - 76), at.x + 12, titleY)
  const degree = degrees instanceof Map ? degrees.get(node.ref) : null
  if (degree) {
    ctx.font = `10px ${FONT_STACK}`
    ctx.fillStyle = dimColor
    ctx.textAlign = 'right'
    ctx.fillText(`↑${degree.up} ↓${degree.down}`, at.x + NODE_W - 10, titleY)
    ctx.textAlign = 'left'
  }

  // ---- 元信息行：库徽标 + 「N列」开关 ----
  const metaY = at.y + CARD_TITLE_H + CARD_META_H / 2
  const prefix = prefixOf(node.ref)
  if (prefix) {
    ctx.font = `10px ${FONT_STACK}`
    ctx.fillStyle = dimColor
    ctx.fillText(ellipsizeMiddle(prefix, 16), at.x + 12, metaY)
  }
  if (card.total) {
    ctx.font = `600 10.5px ${FONT_STACK}`
    ctx.fillStyle = theme.accent
    ctx.textAlign = 'right'
    ctx.fillText(`${card.total}列 ${expanded ? '⌃' : '⌄'}`, at.x + NODE_W - 10, metaY)
    ctx.textAlign = 'left'
  }

  // ---- 滚动体：固定高度，列多时滚（§9.5(1)）----
  const bodyH = expanded ? cardBodyHeight(card) : 0
  if (!bodyH) return
  ctx.strokeStyle = theme.border
  ctx.lineWidth = 1
  ctx.beginPath()
  ctx.moveTo(at.x + 0.5, at.y + CARD_HEADER_H + 0.5)
  ctx.lineTo(at.x + NODE_W - 0.5, at.y + CARD_HEADER_H + 0.5)
  ctx.stroke()

  ctx.save()
  ctx.beginPath()
  ctx.rect(at.x + 1, at.y + CARD_HEADER_H + 1, NODE_W - 2, bodyH - 2)
  ctx.clip()
  const drawRow = (index, name, kind, type) => {
    const y = at.y + CARD_HEADER_H + index * CARD_ROW_H + CARD_ROW_H / 2 - scrollTop
    if (y < at.y + CARD_HEADER_H || y > at.y + CARD_HEADER_H + bodyH) return
    const isHot = !!hoverCol && hoverCol.ref === node.ref && hoverCol.name === name
    const glyph = typeGlyphOf(type)
    if (glyph) {
      ctx.font = `700 10px ${MONO_STACK}`
      ctx.fillStyle = typeColorOf(type, theme)
      ctx.textAlign = 'center'
      ctx.fillText(glyph, at.x + 18, y)
      ctx.textAlign = 'left'
    }
    ctx.fillStyle = isHot ? theme.accent : (kind ? textColor : dimColor)
    ctx.font = `${isHot ? 600 : 400} 11px ${MONO_STACK}`
    ctx.fillText(ellipsizeMiddle(name, 20), at.x + 28, y)
    if (kind) {
      ctx.fillStyle = kindColor(kind, theme)
      ctx.beginPath()
      ctx.arc(at.x + NODE_W - 14, y, 2.5, 0, Math.PI * 2)
      ctx.fill()
    }
  }
  card.rows.forEach((row, index) => drawRow(index, row.name, row.kind, row.type))
  if (card.overflow) {
    ctx.fillStyle = dimColor
    ctx.font = `400 11px ${FONT_STACK}`
    ctx.fillText(`+${card.overflow}`, at.x + 28,
      at.y + CARD_HEADER_H + card.rows.length * CARD_ROW_H + CARD_ROW_H / 2 - scrollTop)
  }
  ctx.restore()

  // 滚动条：内容超出一屏才画，细条贴右缘
  const contentH = cardContentHeight(card)
  if (contentH > bodyH) {
    const trackTop = at.y + CARD_HEADER_H + 2
    const trackH = bodyH - 4
    const thumbH = Math.max(14, trackH * bodyH / contentH)
    const thumbY = trackTop + (trackH - thumbH) * (scrollTop / (contentH - bodyH))
    ctx.fillStyle = theme.border
    ctx.fillRect(at.x + NODE_W - 4, thumbY, 2.5, thumbH)
  }
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

/**
 * 命中测试：视口内最多几百个节点，线性扫描比维护空间索引更不容易写错。
 *
 * <p>卡片模式（`snapshot.cards` 给出时）命中分四类：
 * <ul>
 *   <li>`{type:'colcount', ref}`：元信息行右侧的「N列」开关（点它展开/收起滚动体）；</li>
 *   <li>`{type:'column', ref, name}`：滚动体里的一列（滚动位移算进行号）；</li>
 *   <li>`{type:'node', ref, zone}`：其余卡片区域，`zone` 是 `title` / `meta` / `body`
 *       （滚轮滚动要靠 `zone === 'body'` 判断指针在不在滚动体上）；</li>
 *   <li>折叠占位与表模式命中不变。</li>
 * </ul>
 */
export function hitTest(snapshot, point) {
  const { positions, virtual, view, cards, expandedRefs, scrolls } = snapshot
  const x = point.x / view.k + view.x
  const y = point.y / view.k + view.y
  for (const fold of virtual) {
    if (x >= fold.x && x <= fold.x + 84 && y >= fold.y && y <= fold.y + 28) {
      return { type: 'fold', fold }
    }
  }
  const cardMode = cards instanceof Map && cards.size > 0
  const expandedSet = expandedRefs instanceof Set ? expandedRefs : new Set()
  for (const [ref, at] of positions) {
    const height = at.h || 44
    if (x >= at.x && x <= at.x + NODE_W && y >= at.y && y <= at.y + height) {
      if (!cardMode || !cards.has(ref)) return { type: 'node', ref }
      const card = cards.get(ref)
      const expanded = expandedSet.has(ref)
      const bodyTop = at.y + CARD_HEADER_H
      if (y < at.y + CARD_TITLE_H) return { type: 'node', ref, zone: 'title' }
      if (y < bodyTop) {
        // 元信息行右侧 ~72px 是「N列」开关；其余按节点算（tooltip / 选中）
        if (card.total && x > at.x + NODE_W - 72) return { type: 'colcount', ref }
        return { type: 'node', ref, zone: 'meta' }
      }
      if (!expanded || !cardBodyHeight(card)) return { type: 'node', ref, zone: 'body' }
      const index = Math.floor((y - bodyTop + (scrolls instanceof Map ? (scrolls.get(ref) || 0) : 0)) / CARD_ROW_H)
      if (index >= 0 && index < card.rows.length) {
        return { type: 'column', ref, name: card.rows[index].name }
      }
      return { type: 'node', ref, zone: 'body' }
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
