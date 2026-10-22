/**
 * `utils/lineageLayout.js` 的用例：分层、折叠、确定性、坐标锁定。
 *
 * <p>这些性质**没法靠看一眼界面验证**：位置对了没有、打乱输入之后是不是同一张图、
 * 展开时旧节点有没有动——都要把两次布局的结果逐点比对。因此这一层的用例是
 * 「同一份输入算出两份布局，再比坐标」这种形状。
 */
import assert from 'node:assert/strict'
import test from 'node:test'

import { decode } from '../../src/utils/lineageGraph.js'
import {
  CARD_BODY_MAX_H,
  CARD_HEADER_H,
  CARD_PAD,
  CARD_ROW_H,
  FOLD_THRESHOLD,
  GAP_Y,
  NODE_H,
  NODE_W,
  cardBodyHeight,
  cardHeight,
  fitScale,
  fitView,
  layout,
  shiftPositions,
} from '../../src/utils/lineageLayout.js'

const STEP = NODE_H + GAP_Y

/** 造一张图：`edges` 是 `[source, target, runCount?]`。 */
function graphOf(refs, edges) {
  const index = new Map(refs.map((ref, i) => [ref, i]))
  return decode({
    v: 1,
    root: 0,
    n: refs.map((ref) => [ref, 'PAIMON', 'T', 0, 0]),
    e: edges.map(([source, target, runCount = 1]) => [
      index.get(source), index.get(target), 'T', 'A', 'SPARK', 1_000, runCount, 0,
    ]),
    budget: {},
  })
}

/** 坐标集合：与 Map 的插入顺序无关，便于比较两次布局。 */
function coords(result) {
  return [...result.positions.entries()]
    .map(([ref, at]) => `${ref}@${at.x},${at.y}`)
    .sort()
}

test('跳数即层号：下游在右、上游在左，根在 0 层', () => {
  const graph = graphOf(['c.s.root', 'c.s.down1', 'c.s.down2', 'c.s.up1'], [
    ['c.s.root', 'c.s.down1'],
    ['c.s.down1', 'c.s.down2'],
    ['c.s.up1', 'c.s.root'],
  ])
  const result = layout(graph)
  assert.equal(result.positions.get('c.s.root').rank, 0)
  assert.equal(result.positions.get('c.s.down1').rank, 1)
  assert.equal(result.positions.get('c.s.down2').rank, 2)
  assert.equal(result.positions.get('c.s.up1').rank, -1)
  // 上游的 x 一定小于根的 x，下游的一定大于——这条错了整张图就左右颠倒了
  assert.ok(result.positions.get('c.s.up1').x < result.positions.get('c.s.root').x)
  assert.ok(result.positions.get('c.s.down2').x > result.positions.get('c.s.root').x)
  assert.equal(result.minRank, -1)
  assert.equal(result.maxRank, 2)
})

test('同一节点两条路径时取绝对值小的层号（长链不该把图横向拉长）', () => {
  // root → a → b → t，同时 root → t：t 是 1 跳，不是 3 跳
  const graph = graphOf(['c.s.root', 'c.s.a', 'c.s.b', 'c.s.t'], [
    ['c.s.root', 'c.s.a'],
    ['c.s.a', 'c.s.b'],
    ['c.s.b', 'c.s.t'],
    ['c.s.root', 'c.s.t'],
  ])
  assert.equal(layout(graph).positions.get('c.s.t').rank, 1)
})

test('确定性：同一份输入两次布局，坐标逐个相同', () => {
  const graph = graphOf(['c.s.root', 'c.s.a', 'c.s.b', 'c.s.c'], [
    ['c.s.root', 'c.s.a'],
    ['c.s.root', 'c.s.b'],
    ['c.s.b', 'c.s.c'],
    ['c.s.a', 'c.s.c'],
  ])
  assert.deepEqual(coords(layout(graph)), coords(layout(graph)))
})

test('打乱输入顺序不影响坐标（布局只依赖图，不依赖数组顺序）', () => {
  const refs = ['c.s.root', 'c.s.a', 'c.s.b', 'c.s.c', 'c.s.d']
  const edges = [
    ['c.s.root', 'c.s.a'],
    ['c.s.root', 'c.s.b'],
    ['c.s.a', 'c.s.c'],
    ['c.s.b', 'c.s.c'],
    ['c.s.c', 'c.s.d'],
  ]
  const forward = layout(graphOf(refs, edges))
  const reversed = layout(graphOf([...refs].reverse(), [...edges].reverse()))
  assert.deepEqual(coords(forward), coords(reversed))
})

test('环不会让布局递归：回边进 ring，节点各就各位', () => {
  const graph = graphOf(['c.s.a', 'c.s.b'], [
    ['c.s.a', 'c.s.b'],
    ['c.s.b', 'c.s.a'],
  ])
  const result = layout(graph)
  assert.equal(result.positions.size, 2)
  assert.equal(result.ring.size, 1)
  // A → B 是正向边（B 在下一层），B → A 是回边
  const ringKey = [...result.ring][0]
  assert.ok(ringKey.startsWith('c.s.b\u0000c.s.a'))
})

test(`单层超过 ${FOLD_THRESHOLD} 个节点时折叠成一个 +N 占位`, () => {
  const refs = ['c.s.root']
  const edges = []
  for (let i = 0; i < 37; i += 1) {
    const ref = `c.s.leaf_${String(i).padStart(2, '0')}`
    refs.push(ref)
    edges.push(['c.s.root', ref])
  }
  const result = layout(graphOf(refs, edges))
  assert.equal(result.positions.size, 1 + FOLD_THRESHOLD)
  assert.equal(result.virtual.length, 1)
  assert.equal(result.virtual[0].count, 37 - FOLD_THRESHOLD)
  assert.equal(result.hidden.size, 37 - FOLD_THRESHOLD)
  // 折叠节点是虚线框，落在本层最后一行
  const lastY = Math.max(...[...result.positions.values()].map((at) => at.y))
  assert.equal(result.virtual[0].y, lastY + STEP)
})

test('点开某一层的 +N 之后该层不再折叠', () => {
  const refs = ['c.s.root']
  const edges = []
  for (let i = 0; i < 20; i += 1) {
    const ref = `c.s.leaf_${String(i).padStart(2, '0')}`
    refs.push(ref)
    edges.push(['c.s.root', ref])
  }
  const result = layout(graphOf(refs, edges), { expandedLayers: new Set([1]) })
  assert.equal(result.positions.size, 21)
  assert.equal(result.virtual.length, 0)
  assert.equal(result.hidden.size, 0)
})

test('根所在层不折叠：根永远在图上', () => {
  const refs = ['c.s.root']
  const edges = []
  for (let i = 0; i < 20; i += 1) {
    const ref = `c.s.up_${String(i).padStart(2, '0')}`
    refs.push(ref)
    edges.push([ref, 'c.s.root'])
  }
  const result = layout(graphOf(refs, edges))
  assert.ok(result.positions.has('c.s.root'))
  // 上游那一层被折叠了（-1 层），根所在的 0 层没有
  assert.equal(result.virtual[0].rank, -1)
})

test('与根不连通的节点不画（图上不该出现孤岛）', () => {
  const graph = decode({
    v: 1,
    root: 0,
    n: [['c.s.root', 'PAIMON', 'T', 0, 0], ['c.s.island', 'PAIMON', 'T', 0, 0]],
    e: [],
    budget: {},
  })
  const result = layout(graph)
  assert.equal(result.positions.size, 1)
  assert.equal(result.positions.has('c.s.island'), false)
})

test('坐标锁定：已放置节点的相对位置不变，新节点接在下面', () => {
  // a / b / c 都在同一层（都是根的下一跳）：只有同层才谈得上「排在锁定之后」
  const graph = graphOf(['c.s.root', 'c.s.a', 'c.s.b', 'c.s.c'], [
    ['c.s.root', 'c.s.a'],
    ['c.s.root', 'c.s.b'],
    ['c.s.root', 'c.s.c'],
  ])
  const locked = new Map([
    ['c.s.a', { x: 0, y: 400 }],
    ['c.s.b', { x: 0, y: 800 }],
  ])
  const result = layout(graph, { locked })
  const a = result.positions.get('c.s.a')
  const b = result.positions.get('c.s.b')
  const c = result.positions.get('c.s.c')
  // 整体归一化会平移，但两个锁定节点之间的间距必须一分不差
  assert.equal(b.y - a.y, 400)
  // 未锁定的 c 在本层已锁定节点之后，不会压在 b 上（整体平移是共同项）
  assert.ok(c.y - b.y >= STEP, `c.y-b.y=${c.y - b.y}，至少要有 ${STEP}`)
})

test('fitScale：图比视口高很多时缩到一屏，但有 0.25 的下限', () => {
  assert.equal(fitScale({ height: 300 }, 460), 1)
  assert.equal(fitScale({ height: 0 }, 460), 1)
  const scaled = fitScale({ height: 2_000 }, 460)
  assert.ok(scaled < 1 && scaled >= 0.25)
  assert.equal(fitScale({ height: 100_000 }, 460), 0.25)
})

test('fitView：小图在视口里垂直居中（view.y 必须是负的）', () => {
  // 两节点图：高 44，视口 460。world 0 应画在屏幕 (460-44)/2 = 208 处。
  // 回归：第一版把符号写反，view.y=+208 把整张图平移出画布顶部，只能拖回来看。
  const { k, y } = fitView({ width: 480, height: 44 }, 1_200, 460)
  assert.equal(k, 1)
  assert.equal(y, (44 - 460) / 2)
  // 验证语义：world 0 的屏幕 y = (0 - view.y) * k = 208，正好居中
  assert.equal((0 - y) * k, (460 - 44) / 2)
})

test('fitView：小图同样水平居中；窄出视口的量直接由 view.x 承担', () => {
  // 图宽 480 < 视口 1200 → world 0 应画在屏幕 (1200-480)/2 = 360 处
  const { x } = fitView({ width: 480, height: 44 }, 1_200, 460)
  assert.equal(x, (480 - 1_200) / 2)
  assert.equal((0 - x) * 1, (1_200 - 480) / 2)
})

test('fitView：大图不居中，顶行贴上沿向右下看；缩放后的小图同理', () => {
  // 图高超过 1.5 倍视口 → 缩到一屏，缩完的高度是 viewport-24，仍比视口矮 → 居中留 12px 边
  const tall = fitView({ width: 300, height: 1_000 }, 1_200, 460)
  assert.equal(tall.k, (460 - 24) / 1_000)
  assert.equal(tall.y, (1_000 - 460 / tall.k) / 2)
  assert.ok(tall.y < 0)
  assert.ok(Math.abs((0 - tall.y) * tall.k - 12) < 1e-9, 'world 0 应落在屏幕 12px 处')
  // 图缩到 0.25 下限仍然比视口高 → 顶行贴上沿（y 截到 0），向下滚
  const huge = fitView({ width: 300, height: 100_000 }, 1_200, 460)
  assert.equal(huge.k, 0.25)
  assert.equal(huge.y, 0)
  // 宽图不居中：world 0 贴左沿，向右滚
  const wide = fitView({ width: 5_000, height: 100 }, 1_200, 460)
  assert.equal(wide.x, 0)
})

test('cardOf：收起的卡只有头部两行，展开的卡多一块固定高度的滚动体', () => {
  const graph = graphOf(
    ['c.s.src', 'c.s.a', 'c.s.b', 'c.s.dst'],
    [
      ['c.s.src', 'c.s.a'],
      ['c.s.src', 'c.s.b'],
      ['c.s.a', 'c.s.dst'],
      ['c.s.b', 'c.s.dst'],
    ],
  )
  const cards = new Map([
    ['c.s.src', { rows: [], overflow: 0 }],
    // a 有 5 行（展开后内容不足一屏，滚动体按内容收）
    ['c.s.a', { rows: Array.from({ length: 5 }, (_, i) => ({ name: `col${i}`, kind: 'DIRECT' })), overflow: 0 }],
    ['c.s.b', { rows: [{ name: 'x', kind: 'DIRECT' }], overflow: 0 }],
    ['c.s.dst', { rows: [], overflow: 0 }],
  ])
  // a 展开，其余收起（面板默认展开根卡，其余收起）
  const result = layout(graph, { cardOf: cards, expandedRefs: new Set(['c.s.a']) })

  // 收起的卡：高度只含头部（标题 + 元信息）+ 底部留白，与行数无关
  const collapsedH = CARD_HEADER_H + CARD_PAD
  assert.equal(cardHeight(cards.get('c.s.b'), false), collapsedH)
  assert.equal(result.positions.get('c.s.b').h, collapsedH)
  // 展开的卡：多一块滚动体（5 行 × 行高 + 内边距，不足一屏按内容收）
  const bodyA = 5 * CARD_ROW_H + 8
  const expandedH = CARD_HEADER_H + bodyA + CARD_PAD
  assert.equal(cardHeight(cards.get('c.s.a'), true), expandedH)
  assert.equal(result.positions.get('c.s.a').h, expandedH)
  // 不折叠：就算层里超过阈值也不收（每张卡都要露出列清单）
  assert.equal(result.hidden.size, 0)

  // 中间层两张卡按实际高度堆叠：b 紧跟 a，间距正好 GAP_Y
  const a = result.positions.get('c.s.a')
  const b = result.positions.get('c.s.b')
  assert.equal(b.y - a.y, expandedH + GAP_Y)

  // height 是各层跨度的最大值（层是横排的），中间层的跨度 = 两卡高 + 间距
  assert.equal(result.height, Math.max(expandedH + GAP_Y + collapsedH, collapsedH))
})

test('cardOf：列太多时滚动体高度封顶（固定高度 + 滚动，而不是撑成长卡）', () => {
  // 30 行：内容远超一屏，展开后的滚动体固定为 CARD_BODY_MAX_H
  const card = {
    rows: Array.from({ length: 30 }, (_, i) => ({ name: `col${i}`, kind: 'DIRECT' })),
    overflow: 0,
  }
  assert.equal(cardBodyHeight(card), CARD_BODY_MAX_H)
  assert.equal(cardHeight(card, true), CARD_HEADER_H + CARD_BODY_MAX_H + CARD_PAD)
  // 同一张卡收起时只有头部——展开与否的高度差就是一块滚动体
  assert.equal(cardHeight(card, false), CARD_HEADER_H + CARD_PAD)
})

test('cardOf：锁定坐标被忽略——卡片高度一变，旧 y 只会把新卡钉在错位上', () => {
  const graph = graphOf(['c.s.a', 'c.s.b'], [['c.s.a', 'c.s.b']])
  const locked = new Map([['c.s.a', { x: 0, y: 400 }]])
  const cards = new Map([['c.s.a', { rows: [], overflow: 0 }], ['c.s.b', { rows: [], overflow: 0 }]])
  const result = layout(graph, { cardOf: cards, locked })
  // 表模式下 a 会被钉在 y=400；卡片模式下两层各只有一张卡，各层独立居中，
  // 归一化后 y 都是 0——**层是横排的（x = rank 步进）**，竖直方向只有一张卡
  const a = result.positions.get('c.s.a')
  const b = result.positions.get('c.s.b')
  assert.equal(a.y, 0)
  assert.equal(b.y, 0)
  assert.equal(b.x, NODE_W + 80, 'b 在 a 右边一层')
})

test('shiftPositions：没有位移时返回原 Map（每帧不拷一份坐标）', () => {
  const graph = graphOf(['c.s.a', 'c.s.b'], [['c.s.a', 'c.s.b']])
  const result = layout(graph)
  assert.equal(shiftPositions(result.positions, new Map()), result.positions)
  assert.equal(shiftPositions(result.positions, null), result.positions)
})

test('shiftPositions：只动被拖的那张，其余节点原样（不重排邻居）', () => {
  const graph = graphOf(['c.s.a', 'c.s.b', 'c.s.c'], [
    ['c.s.a', 'c.s.b'], ['c.s.b', 'c.s.c'],
  ])
  const before = layout(graph)
  const moved = shiftPositions(before.positions, new Map([['c.s.b', { dx: 37, dy: -12 }]]))
  const b0 = before.positions.get('c.s.b')
  const b1 = moved.get('c.s.b')
  assert.equal(b1.x, b0.x + 37)
  assert.equal(b1.y, b0.y - 12)
  // 其余节点是同一个对象——连线的另一头不该因为拖了别人而跳位
  assert.equal(moved.get('c.s.a'), before.positions.get('c.s.a'))
  assert.equal(moved.get('c.s.c'), before.positions.get('c.s.c'))
})

test('shiftPositions：不修改布局产物（布局仍是纯函数）', () => {
  const graph = graphOf(['c.s.a', 'c.s.b'], [['c.s.a', 'c.s.b']])
  const before = layout(graph)
  const snapshot = coords(before)
  shiftPositions(before.positions, new Map([['c.s.a', { dx: 100, dy: 100 }]]))
  // 第一次布局的结果没被就地改写：第二次算出来的坐标仍与第一次一致
  assert.deepEqual(coords(layout(graph)), snapshot)
  assert.deepEqual(coords(before), snapshot)
})

test('shiftPositions：指向不在图里的 ref 被忽略（换根后残留的位移）', () => {
  const graph = graphOf(['c.s.a'], [])
  const result = layout(graph)
  const moved = shiftPositions(result.positions, new Map([['c.s.ghost', { dx: 9, dy: 9 }]]))
  assert.deepEqual([...moved.keys()], [...result.positions.keys()])
  assert.equal(moved.get('c.s.a'), result.positions.get('c.s.a'))
})
