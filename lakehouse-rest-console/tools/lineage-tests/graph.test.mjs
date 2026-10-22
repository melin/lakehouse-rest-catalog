/**
 * `utils/lineageGraph.js` 的用例：解码、索引、过滤、合并。
 *
 * <p>跑法：`npm run test:lineage`（`node --test`，无第三方测试框架）。
 * 这一层是纯函数、不碰 DOM，所以能直接测——这也是它被单独拆出来的原因：
 * 如果解码与过滤留在组件里，验证「淡化类过滤不改布局」就得挂一个 Vue 实例。
 */
import assert from 'node:assert/strict'
import test from 'node:test'

import {
  STALE_FADE_MS,
  applyFilters,
  decode,
  defaultFilters,
  edgeKey,
  engineOptions,
  hasColumnInfo,
  isFaded,
  mergeGraph,
} from '../../src/utils/lineageGraph.js'

/** 服务端 `compact=1` 的真实形状：`[ref, format, kind, more, stale]` 与八位边。 */
function compactPayload() {
  return {
    v: 1,
    root: 0,
    n: [
      ['paimon.dw.orders', 'PAIMON', 'T', 0, 0],
      ['paimon.dw.order_daily', 'PAIMON', 'T', 1, 0],
      ['paimon.ods.raw_orders', 'PAIMON', 'T', 0, 1],
      ['~file:8c1f', null, 'E', 0, 0],
    ],
    e: [
      [2, 0, 'T', 'A', 'SPARK', 1_000, 7, 3],
      [0, 1, 'T', 'A', 'SPARK', 2_000, 5, 12],
      [3, 0, 'E', 'O', 'SPARK', 500, 2, 0],
    ],
    budget: { upstream: 2, downstream: 1, returned: 4, truncated: false },
  }
}

test('解码紧凑形状：位置固定、枚举压成单字母', () => {
  const graph = decode(compactPayload())
  assert.equal(graph.root, 'paimon.dw.orders')
  assert.deepEqual(graph.nodes.map((node) => node.ref), [
    'paimon.dw.orders',
    'paimon.dw.order_daily',
    'paimon.ods.raw_orders',
    '~file:8c1f',
  ])
  // 第 3 位 E 是外部节点（`~` 前缀那类），第 5 位 1 是「目录里已经没有了」
  assert.equal(graph.byRef.get('~file:8c1f').kind, 'EXTERNAL')
  assert.equal(graph.byRef.get('paimon.ods.raw_orders').stale, true)
  assert.equal(graph.byRef.get('paimon.dw.order_daily').more, true)
  // 下标 → ref：边里存的是整数，解码后必须已经换成名字
  assert.deepEqual(graph.edges.map((edge) => [edge.source, edge.target]), [
    ['paimon.ods.raw_orders', 'paimon.dw.orders'],
    ['paimon.dw.orders', 'paimon.dw.order_daily'],
    ['~file:8c1f', 'paimon.dw.orders'],
  ])
  // A / O 是写模式，界面要读得懂
  assert.equal(graph.edges[0].writeMode, 'APPEND')
  assert.equal(graph.edges[2].writeMode, 'OVERWRITE')
  assert.deepEqual(graph.budget, { upstream: 2, downstream: 1, returned: 4, truncated: false })
})

test('解码认两种形状：紧凑与默认（节点是对象）', () => {
  const full = decode({
    root: 'a.b.t1',
    nodes: [
      { ref: 'a.b.t1', objectType: 'TABLE', format: 'ICEBERG', stale: false, more: false },
      { ref: 'a.b.t2', objectType: 'EXTERNAL', format: null, stale: true, more: true },
    ],
    edges: [{ source: 'a.b.t1', target: 'a.b.t2', kind: 'EXTERNAL', writeMode: 'UPSERT', engine: 'SPARK', lastSeen: 9, runCount: 2, columnCount: 4 }],
    budget: { upstream: 0, downstream: 1, returned: 2, truncated: false },
  })
  assert.equal(full.nodes.length, 2)
  assert.equal(full.byRef.get('a.b.t2').kind, 'EXTERNAL')
  assert.equal(full.byRef.get('a.b.t2').stale, true)
  assert.equal(full.edges[0].writeMode, 'UPSERT')
  assert.equal(full.edges[0].columnCount, 4)
})

test('空响应与 null 都退化成空图，而不是抛错', () => {
  for (const payload of [null, {}, { root: 'a.b.t1', n: [], e: [] }]) {
    const graph = decode(payload)
    assert.equal(graph.nodes.length, 0)
    assert.equal(graph.edges.length, 0)
    assert.equal(graph.budget.upstream, 0)
  }
})

test('索引：outgoing / incoming 都按 ref 取，同一个节点可以在两张表里', () => {
  const graph = decode(compactPayload())
  assert.equal(graph.outgoing.get('paimon.dw.orders').length, 1)
  assert.equal(graph.incoming.get('paimon.dw.orders').length, 2)
})

test('隐藏类过滤：外部节点关掉之后，连着它的边一起消失', () => {
  const graph = decode(compactPayload())
  const result = applyFilters(graph, { ...defaultFilters(), showExternal: false })
  assert.deepEqual(result.graph.nodes.map((node) => node.ref), [
    'paimon.dw.orders',
    'paimon.dw.order_daily',
    'paimon.ods.raw_orders',
  ])
  assert.equal(result.graph.edges.length, 2)
  // 1 个节点 + 1 条边：空态文案要靠它区分「本来就没有」与「被过滤没了」
  assert.equal(result.removed, 2)
})

test('淡化类过滤只标记、不删边（布局不能因它改变）', () => {
  const graph = decode(compactPayload())
  const now = 1_000 + STALE_FADE_MS + 1
  const result = applyFilters(graph, defaultFilters(), now)
  assert.equal(result.graph.edges.length, 3)
  assert.deepEqual([...result.faded].sort(), [
    edgeKey(graph.edges[0]),
    edgeKey(graph.edges[2]),
  ])
})

test('lastSeen 为 0 的边不淡化：那是「不知道」而不是「很久以前」', () => {
  assert.equal(isFaded({ lastSeen: 0 }, Date.now()), false)
  assert.equal(isFaded({ lastSeen: 1 }, 1 + STALE_FADE_MS + 1), true)
})

test('引擎候选从图里现算，不写死 SPARK', () => {
  const graph = decode(compactPayload())
  assert.deepEqual(engineOptions(graph), ['SPARK'])
  const flink = decode({
    v: 1,
    root: 0,
    n: [['a.b.t1', 'PAIMON', 'T', 0, 0], ['a.b.t2', 'PAIMON', 'T', 0, 0]],
    e: [[0, 1, 'T', 'A', 'FLINK', 1, 1, 0]],
    budget: {},
  })
  assert.deepEqual(engineOptions(flink), ['FLINK'])
})

test('有没有列级映射看边的 columnCount，不看节点数', () => {
  const graph = decode(compactPayload())
  assert.equal(hasColumnInfo(graph), true)
  const without = decode({
    v: 1,
    root: 0,
    n: [['a.b.t1', 'PAIMON', 'T', 0, 0], ['a.b.t2', 'PAIMON', 'T', 0, 0]],
    e: [[0, 1, 'T', 'A', 'SPARK', 1, 1, 0]],
    budget: {},
  })
  assert.equal(hasColumnInfo(without), false)
})

test('展开是一次合并：新节点进图，旧节点的 more 只会被推进、不会被抹掉', () => {
  const base = decode(compactPayload())
  const addition = decode({
    v: 1,
    root: 0,
    n: [
      ['paimon.dw.order_daily', 'PAIMON', 'T', 0, 0],
      ['paimon.dw.order_agg', 'PAIMON', 'T', 1, 0],
    ],
    e: [[0, 1, 'T', 'O', 'SPARK', 3_000, 1, 5]],
    budget: { upstream: 0, downstream: 2, returned: 2, truncated: false },
  })
  const merged = mergeGraph(base, addition)
  assert.equal(merged.nodes.length, 5)
  // 旧值是 true、新值是 false：取或，否则「还有一跳」的信息会被一次展开抹掉
  assert.equal(merged.byRef.get('paimon.dw.order_daily').more, true)
  assert.equal(merged.byRef.get('paimon.dw.orders').more, false)
  assert.equal(merged.edges.length, 4)
  // 根不变：合并不换根
  assert.equal(merged.root, base.root)
  // 预算取两侧的较大值（root 的直接邻居数是真实总数，不该因为合并变小）
  assert.equal(merged.budget.upstream, 2)
  assert.equal(merged.budget.downstream, 2)
})

test('同一条边合并两次只有一条（键含 engine：不同引擎是两条边）', () => {
  const graph = decode(compactPayload())
  const merged = mergeGraph(graph, graph)
  assert.equal(merged.edges.length, 3)
  assert.equal(edgeKey(graph.edges[0]) === edgeKey(graph.edges[1]), false)
})
