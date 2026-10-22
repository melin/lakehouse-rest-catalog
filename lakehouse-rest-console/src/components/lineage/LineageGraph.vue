<template>
  <div ref="host" class="lg-host" :class="{ 'lg-grabbing': dragging }">
    <!--
      Canvas 对屏幕阅读器是空白，三条兜底缺一不可（§9.6）：role + aria-label 说明
      「这张图有多大」，邻接表是真正的无障碍路径，键盘能换焦点。
      这里不 import 任何 element-plus 组件：渲染层与 UI 库解耦（verify-console 钉着）。
    -->
    <canvas
      ref="canvas"
      class="lg-canvas"
      role="img"
      :aria-label="ariaLabel"
      tabindex="0"
      @pointerdown="onPointerDown"
      @pointermove="onPointerMove"
      @pointerup="onPointerUp"
      @pointercancel="onPointerUp"
      @pointerleave="onPointerLeave"
      @wheel.prevent="onWheel"
      @dblclick="onDoubleClick"
      @keydown="onKeyDown"
    />
    <div class="lg-tools">
      <button type="button" title="放大" @click="zoomBy(1.25)">+</button>
      <button type="button" title="缩小" @click="zoomBy(0.8)">−</button>
      <button type="button" title="适应窗口" @click="fit()">⤢</button>
    </div>
    <div v-if="hoverTip" class="lg-tip" :style="{ left: `${hoverTip.x}px`, top: `${hoverTip.y}px` }">
      {{ hoverTip.text }}
    </div>
    <div class="lg-hint">滚轮缩放 · 拖拽平移 · 双击以该表为根</div>
  </div>
</template>

<script setup>
/**
 * 图的宿主：唯一持有可变交互状态（pan / zoom / hover）的地方（§9.13）。
 *
 * <p>它把 DOM 事件翻译成语义事件（`select` / `expand` / `fold` / `reroot`），
 * 自己不查数据、不认识路由、不 import UI 库——换一整套交互外观时只改这一个文件。
 *
 * <p>绘制全部交给 `utils/lineageRender.drawFrame`：一帧的输入是 `(布局, 视口, 悬停)`，
 * 输出是像素，中间没有任何跨帧状态。这也让「展开后已放置节点坐标锁定」这类规矩
 * 落在布局层，而不是散在事件处理里。
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'

import { theme } from '@/store/theme.js'
import { CARD_ROW_H, cardBodyHeight, fitView } from '@/utils/lineageLayout.js'
import { drawFrame, hitEdge, hitTest, lodFor, prefixOf, readTheme } from '@/utils/lineageRender.js'

const props = defineProps({
  /** 过滤之后的图（`lineageGraph.build` 的形状）。 */
  graph: { type: Object, required: true },
  /** `lineageLayout.layout` 的产物：positions / virtual / ring / width / height。 */
  layout: { type: Object, required: true },
  /** 淡化类过滤命中的边（布局不变，只是画淡，§9.4(4)）。 */
  faded: { type: Set, default: () => new Set() },
  /** 本帧要突出的节点（图内搜索命中、或抽屉打开的那个）。 */
  focusRef: { type: String, default: '' },
  /** 卡片清单（`lineageColumns.buildCards` 的产物）。null = 没有列信息，退回表模式。
   *
   *  <p>与列级开关无关：卡片是图的常态，`columnEdges` 才是开关（§9.5(1)）。 */
  cards: { type: Object, default: null },
  /** 列级连线（ref 已按下标解码回表名）：`{s, sCol, t, tCol, kind}`。空数组 = 不画列线。 */
  columnEdges: { type: Array, default: () => [] },
  /** 点选高亮的表（只给表头上一层淡强调色）。空串 = 没有点选。 */
  selectedRef: { type: String, default: '' },
  /** 「N列」开关已点开的 ref 集合（面板持有：布局高度跟着它算）。 */
  expandedRefs: { type: Set, default: () => new Set() },
  /** `ref → {up, down}`：上下游表计数（画在卡片标题行右侧）。 */
  degrees: { type: Object, default: null },
})

const emit = defineEmits(['select', 'expand', 'fold', 'reroot', 'toggle-rows', 'deselect'])

const host = ref(null)
const canvas = ref(null)

const view = reactive({ x: 0, y: 0, k: 1 })
const size = reactive({ w: 0, h: 0, dpr: 1 })
const hover = reactive({ ref: '', colName: '', edge: null, x: 0, y: 0 })
const dragging = ref(false)
const lod = ref(0)
const palette = ref(null)

// 各卡滚动体的滚动位移（ref → px）。刻意不放进 reactive：它一变就是整帧重画，
// 走 schedule() 的 rAF 合帧；Vue 的深度响应在这里只会白花开销
const scrolls = new Map()

let ctx = null
let observer = null
let frameHandle = 0
let dirty = true
let dragOrigin = null
let userAdjusted = false
let keyboardIndex = -1

const ariaLabel = computed(() => {
  const upstream = props.graph?.budget?.upstream ?? 0
  const downstream = props.graph?.budget?.downstream ?? 0
  return `血缘图：上游 ${upstream} 张表，下游 ${downstream} 张表，当前画了 ${props.layout?.positions?.size ?? 0} 个节点`
})

const hoverTip = computed(() => {
  if (hover.colName) {
    const card = props.cards instanceof Map ? props.cards.get(hover.ref) : null
    const row = card?.rows.find((item) => item.name === hover.colName)
    const kindText = KIND_TEXT[row?.kind] || ''
    return {
      x: hover.x + 12,
      y: hover.y + 12,
      text: `${hover.colName}${kindText ? ` · ${kindText}` : ''}`,
    }
  }
  // 卡片模式的节点悬停：标题只画了表短名，catalog 与库名在这里补全（§9.5(1)）
  if (props.cards instanceof Map && props.cards.has(hover.ref)) {
    const prefix = prefixOf(hover.ref)
    if (prefix) {
      return { x: hover.x + 12, y: hover.y + 12, text: prefix }
    }
  }
  const edge = hover.edge
  if (!edge) return null
  return {
    x: hover.x + 12,
    y: hover.y + 12,
    text: `${edge.engine} · ${edge.writeMode} · 跑过 ${edge.runCount} 次`
      + (edge.columnCount ? ` · ${edge.columnCount} 条列映射` : ''),
  }
})

const KIND_TEXT = {
  DIRECT: '直通',
  DERIVED: '派生',
  AGGREGATE: '聚合',
  CONSTANT: '常量',
  UNRESOLVED: '未解析',
}

/** 一帧：只在脏且尺寸就绪时画。早退条件就是「view 与 data 都没变」。 */
function schedule() {
  dirty = true
  if (frameHandle) return
  frameHandle = requestAnimationFrame(() => {
    frameHandle = 0
    if (!dirty || !ctx || !size.w) return
    dirty = false
    const nextLod = lodFor(view.k, lod.value)
    lod.value = nextLod
    drawFrame(ctx, {
      positions: props.layout.positions,
      virtual: props.layout.virtual,
      ring: props.layout.ring,
      nodes: props.graph.nodes,
      edges: props.graph.edges,
      faded: props.faded,
      view,
      width: size.w,
      height: size.h,
      dpr: size.dpr,
      theme: palette.value,
      lod: nextLod,
      hoverRef: hover.ref || null,
      hoverEdge: hover.edge,
      focusRef: props.focusRef,
      cards: props.cards,
      columnEdges: props.columnEdges,
      hoverCol: hover.colName ? { ref: hover.ref, name: hover.colName } : null,
      selectedRef: props.selectedRef,
      expandedRefs: props.expandedRefs,
      scrolls,
      degrees: props.degrees,
    })
  })
}

function resize() {
  if (!canvas.value || !host.value) return
  const dpr = window.devicePixelRatio || 1
  const rect = host.value.getBoundingClientRect()
  size.w = Math.max(1, Math.round(rect.width))
  size.h = Math.max(1, Math.round(rect.height))
  size.dpr = dpr
  canvas.value.width = Math.round(size.w * dpr)
  canvas.value.height = Math.round(size.h * dpr)
  canvas.value.style.width = `${size.w}px`
  canvas.value.style.height = `${size.h}px`
  schedule()
}

/** 适应窗口：图高超过视口 1.5 倍时缩到一屏（§9.4(3)）。用户手动缩过就不再自动。 */
function fit() {
  userAdjusted = false
  const next = fitView(props.layout, size.w, size.h)
  view.k = next.k
  view.x = next.x
  view.y = next.y
  schedule()
}

function zoomBy(factor) {
  zoomAt(factor, size.w / 2, size.h / 2)
}

/** 以光标为锚点缩放：拖完手就停在同一张表上，而不是被拉回中心。 */
function zoomAt(factor, sx, sy) {
  const next = Math.min(2, Math.max(0.25, view.k * factor))
  if (next === view.k) return
  const wx = sx / view.k + view.x
  const wy = sy / view.k + view.y
  view.k = next
  view.x = wx - sx / next
  view.y = wy - sy / next
  userAdjusted = true
  schedule()
}

/**
 * 滚轮：指针落在展开卡的滚动体上时改为滚卡片内容，不缩放。
 *
 * <p>返回是否消费了这次滚动。位移钳在内容高度内——滚到底再往下滚会把画布
 * 缩放突然接过去，手感像「卡了一下」，所以宁可不动。
 */
function scrollCardAt(point, deltaY) {
  if (!(props.cards instanceof Map) || !props.cards.size) return false
  const hit = hitTest(snapshot(), point)
  const overBody = hit && (hit.type === 'column'
    || (hit.type === 'node' && hit.zone === 'body'))
  if (!overBody) return false
  const card = props.cards.get(hit.ref)
  if (!card) return false
  const contentH = (card.rows.length + (card.overflow ? 1 : 0)) * CARD_ROW_H + 8
  const bodyH = cardBodyHeight(card)
  const maxScroll = Math.max(0, contentH - bodyH)
  const current = scrolls.get(hit.ref) || 0
  const next = Math.min(Math.max(0, current + deltaY), maxScroll)
  if (next === current) return false
  scrolls.set(hit.ref, next)
  schedule()
  return true
}

function onWheel(event) {
  const rect = canvas.value.getBoundingClientRect()
  const point = { x: event.clientX - rect.left, y: event.clientY - rect.top }
  if (scrollCardAt(point, event.deltaY)) return
  zoomAt(event.deltaY < 0 ? 1.1 : 1 / 1.1, point.x, point.y)
}

function onPointerDown(event) {
  canvas.value?.focus()
  dragOrigin = { x: event.clientX, y: event.clientY, vx: view.x, vy: view.y, moved: false }
  dragging.value = true
  canvas.value.setPointerCapture?.(event.pointerId)
}

function onPointerMove(event) {
  const rect = canvas.value.getBoundingClientRect()
  const point = { x: event.clientX - rect.left, y: event.clientY - rect.top }
  if (dragOrigin) {
    const dx = event.clientX - dragOrigin.x
    const dy = event.clientY - dragOrigin.y
    if (Math.abs(dx) + Math.abs(dy) > 3) {
      dragOrigin.moved = true
      view.x = dragOrigin.vx - dx / view.k
      view.y = dragOrigin.vy - dy / view.k
      schedule()
    }
    return
  }
  const hit = hitTest(snapshot(), point)
  const edge = hit ? null : hitEdge(snapshot(), point)
  const nextRef = hit?.ref || ''
  const nextCol = hit?.type === 'column' ? hit.name : ''
  if (nextRef !== hover.ref || nextCol !== hover.colName || edge !== hover.edge) {
    hover.ref = nextRef
    hover.colName = nextCol
    hover.edge = edge
    hover.x = point.x
    hover.y = point.y
    schedule()
  } else if (edge) {
    hover.x = point.x
    hover.y = point.y
  }
}

function onPointerUp(event) {
  const origin = dragOrigin
  dragOrigin = null
  dragging.value = false
  if (!origin || origin.moved) return
  const rect = canvas.value.getBoundingClientRect()
  const point = { x: event.clientX - rect.left, y: event.clientY - rect.top }
  const hit = hitTest(snapshot(), point)
  if (!hit) {
    // 空白处点击 = 取消选中（高亮切回普通卡）
    emit('deselect')
    return
  }
  if (hit.type === 'fold') {
    emit('fold', hit.fold.rank)
    return
  }
  // 「N列」开关：展开/收起该卡的滚动体（§9.5(1)）
  if (hit.type === 'colcount') {
    emit('toggle-rows', hit.ref)
    return
  }
  // 列行点击按节点算（打开抽屉）；不走 MORE 徽标分支——徽标只画在表头上
  if (hit.type === 'column') {
    emit('select', hit.ref)
    return
  }
  const at = props.layout.positions.get(hit.ref)
  const node = props.graph.byRef?.get(hit.ref)
  // 右端的 MORE 徽标是「展开」而不是「选中」：两者在同一个矩形里，靠位置分开。
  // 卡片模式没有这个徽标（标题行右侧是上下游计数），整块让给「选中」
  if (!props.cards && node?.more && at && (point.x / view.k + view.x) > at.x + 176) {
    emit('expand', hit.ref)
    return
  }
  emit('select', hit.ref)
}

function onPointerLeave() {
  hover.ref = ''
  hover.colName = ''
  hover.edge = null
  schedule()
}

function onDoubleClick(event) {
  const rect = canvas.value.getBoundingClientRect()
  const hit = hitTest(snapshot(), { x: event.clientX - rect.left, y: event.clientY - rect.top })
  if (hit?.type === 'node') {
    emit('reroot', hit.ref)
  }
}

/** 键盘：←/→ 在节点间移焦点，Enter 打开抽屉（§9.6 的第三条兜底）。 */
function onKeyDown(event) {
  const refs = [...props.layout.positions.keys()].sort()
  if (!refs.length) return
  if (event.key === 'ArrowRight' || event.key === 'ArrowLeft') {
    const step = event.key === 'ArrowRight' ? 1 : -1
    keyboardIndex = (keyboardIndex + step + refs.length) % refs.length
    const ref = refs[keyboardIndex]
    const at = props.layout.positions.get(ref)
    // 把焦点节点挪到视口中央：键盘用户没有鼠标，只能靠「图在动」感知位置
    view.x = at.x - size.w / view.k / 2 + 100
    view.y = at.y - size.h / view.k / 2 + 22
    hover.ref = ref
    schedule()
    event.preventDefault()
    return
  }
  if (event.key === 'Enter' && hover.ref) {
    emit('select', hover.ref)
    event.preventDefault()
  }
}

function snapshot() {
  return {
    positions: props.layout.positions,
    virtual: props.layout.virtual,
    edges: props.graph.edges,
    view,
    cards: props.cards,
    expandedRefs: props.expandedRefs,
    scrolls,
  }
}

onMounted(() => {
  ctx = canvas.value.getContext('2d')
  palette.value = readTheme()
  observer = new ResizeObserver(resize)
  observer.observe(host.value)
  resize()
  fit()
})

onBeforeUnmount(() => {
  observer?.disconnect()
  if (frameHandle) cancelAnimationFrame(frameHandle)
})

watch(() => props.layout, () => {
  if (!userAdjusted) fit()
  else schedule()
})
watch(() => props.graph, schedule)
watch(() => props.faded, schedule)
watch(() => props.focusRef, schedule)
watch(() => props.cards, () => {
  // 新一份卡片数据（重拉/换根）：旧的滚动位移指向的行已经对不上号，全部归零
  scrolls.clear()
  schedule()
})
watch(() => props.expandedRefs, (next) => {
  // 收起的卡没有滚动体了，位移清掉——下次展开从头看
  for (const ref of [...scrolls.keys()]) {
    if (!next.has(ref)) scrolls.delete(ref)
  }
  schedule()
})
watch(() => props.columnEdges, schedule)
watch(() => props.selectedRef, schedule)
watch(() => props.degrees, schedule)
// 主题切换后重读颜色变量并重画：Canvas 拿不到 CSS 继承（§9.3）
watch(() => theme.state.mode, () => {
  palette.value = readTheme()
  schedule()
})

defineExpose({ fit })
</script>

<style scoped>
.lg-host {
  position: relative;
  height: 460px;
  border: 1px solid var(--pc-border);
  border-radius: var(--pc-radius);
  background: var(--pc-surface-2);
  overflow: hidden;
}

.lg-canvas {
  display: block;
  cursor: grab;
  outline: none;
}

.lg-grabbing .lg-canvas {
  cursor: grabbing;
}

.lg-tools {
  position: absolute;
  top: 8px;
  right: 8px;
  display: flex;
  gap: 4px;
}

.lg-tools button {
  width: 26px;
  height: 26px;
  border: 1px solid var(--pc-border);
  border-radius: var(--pc-radius);
  background: var(--pc-surface);
  color: var(--pc-text);
  cursor: pointer;
  line-height: 1;
}

.lg-tools button:hover {
  border-color: var(--pc-accent);
  color: var(--pc-accent);
}

.lg-tip {
  position: absolute;
  padding: 3px 8px;
  border: 1px solid var(--pc-border);
  border-radius: var(--pc-radius);
  background: var(--pc-surface);
  color: var(--pc-text);
  font-size: 11.5px;
  pointer-events: none;
  white-space: nowrap;
  box-shadow: var(--pc-shadow);
}

.lg-hint {
  position: absolute;
  left: 10px;
  bottom: 8px;
  color: var(--pc-text-dim);
  font-size: 11px;
  pointer-events: none;
}
</style>
