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
  FOLD_THRESHOLD,
  GAP_Y,
  NODE_H,
  fitScale,
  layout,
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
