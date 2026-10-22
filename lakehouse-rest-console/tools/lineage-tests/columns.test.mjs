/**
 * `utils/lineageColumns.js` 的用例：报文解码、卡片列清单的构成与确定性。
 *
 * <p>列级图最容易错的是「行从哪来」：目标侧列是权威清单、源侧独有列也要占行
 * （不然连线没有落点）、被 `+N` 折掉的列连线要钳回最后一行——这些性质
 * 没法靠看一眼界面验证，得把输入输出逐项比对。
 */
import assert from 'node:assert/strict'
import test from 'node:test'

import { MAX_CARD_ROWS, buildCards, decodeColumnEdges, typeKindOf } from '../../src/utils/lineageColumns.js'

const NAMES = ['paimon.dw.src', 'paimon.dw.mid', 'paimon.dw.dst']

test('decode：报文行原样转对象，kind 缺省为 UNRESOLVED', () => {
  const { edges } = decodeColumnEdges({
    refs: NAMES,
    edges: [
      [0, 'id', 2, 'id', 'DIRECT'],
      [1, 'amount', 2, 'gmv', 'AGGREGATE'],
      [-1, '', 2, 'dt', 'CONSTANT'],
    ],
  })
  assert.deepEqual(edges, [
    { s: 0, sCol: 'id', t: 2, tCol: 'id', kind: 'DIRECT' },
    { s: 1, sCol: 'amount', t: 2, tCol: 'gmv', kind: 'AGGREGATE' },
    { s: -1, sCol: '', t: 2, tCol: 'dt', kind: 'CONSTANT' },
  ])
  assert.deepEqual(decodeColumnEdges(null).edges, [])
  assert.deepEqual(decodeColumnEdges({ edges: [[0, 'a', 1, 'a', null]] }).edges[0].kind, 'UNRESOLVED')
})

test('buildCards：行 = 被写过的列 ∪ 被引用的列；目标侧 kind 是权威值', () => {
  const { edges } = decodeColumnEdges({
    refs: NAMES,
    edges: [
      [0, 'id', 2, 'id', 'DIRECT'],
      [1, 'amount', 2, 'gmv', 'AGGREGATE'],
      // 源侧独有列：mid 的 a、b 只被引用，没有被写进 mid 的任何目标列
      [1, 'a', 2, 'total', 'DERIVED'],
      [1, 'b', 2, 'total', 'DERIVED'],
      // 同名列先作为源、后作为目标：以目标的 kind 为准
      [0, 'flag', 1, 'flag', 'DERIVED'],
    ],
  })
  const cards = buildCards(NAMES, edges)

  const dst = cards.get('paimon.dw.dst')
  // dst 只收目标侧的行（gmv/id/total）；a、b 是 mid 的源侧独有列，挂在 mid 的卡上
  assert.deepEqual(dst.rows.map((row) => row.name), ['gmv', 'id', 'total'])
  assert.equal(dst.rows.find((row) => row.name === 'gmv').kind, 'AGGREGATE')

  const mid = cards.get('paimon.dw.mid')
  // mid 的源侧独有列：a、amount（喂给 dst.gmv）、b——按字典序排在 flag 之后
  assert.deepEqual(mid.rows.map((row) => [row.name, row.kind]),
    [['flag', 'DERIVED'], ['a', ''], ['amount', ''], ['b', '']])
})

test('buildCards：常量行（s=-1）只贡献目标侧的行，不产生连线数据', () => {
  const { edges } = decodeColumnEdges({
    refs: NAMES,
    edges: [[-1, '', 1, 'dt', 'CONSTANT']],
  })
  const cards = buildCards(NAMES, edges)
  const mid = cards.get('paimon.dw.mid')
  assert.deepEqual(mid.rows.map((row) => row.name), ['dt'])
  assert.equal(mid.rows[0].kind, 'CONSTANT')
})

test('buildCards：超过 maxRows 收进 +N，rowIndexOf 查不到被折掉的列', () => {
  const edges = []
  for (let i = 0; i < MAX_CARD_ROWS + 5; i += 1) {
    edges.push([0, `col${String(i).padStart(2, '0')}`, 1, `col${String(i).padStart(2, '0')}`, 'DIRECT'])
  }
  const cards = buildCards(['s', 't'], edges.map((row) => ({ s: row[0], sCol: row[1], t: row[2], tCol: row[3], kind: row[4] })))
  const card = cards.get('t')
  assert.equal(card.rows.length, MAX_CARD_ROWS)
  assert.equal(card.overflow, 5)
  assert.equal(card.total, MAX_CARD_ROWS + 5)
  assert.ok(card.rowIndexOf.has('col00'))
  assert.ok(!card.rowIndexOf.has(`col${String(MAX_CARD_ROWS + 4).padStart(2, '0')}`),
    '被折掉的列不能出现在 rowIndexOf——渲染层靠它钳回最后一行')
})

test('buildCards：确定性——同一份输入两次调用得到同一份清单', () => {
  const edges = [
    { s: 0, sCol: 'b', t: 1, tCol: 'y', kind: 'DIRECT' },
    { s: 0, sCol: 'a', t: 1, tCol: 'x', kind: 'DERIVED' },
  ]
  const first = buildCards(NAMES, edges)
  const second = buildCards(NAMES, [...edges].reverse())
  assert.deepEqual(first.get('paimon.dw.mid'), second.get('paimon.dw.mid'))
})

test('typeKindOf：四类 + 兜底，未知类型返回空串（不画图标）', () => {
  // 数字
  assert.equal(typeKindOf('int'), 'num')
  assert.equal(typeKindOf('BIGINT'), 'num')
  assert.equal(typeKindOf('decimal(10,2)'), 'num')
  assert.equal(typeKindOf('double'), 'num')
  // 字符
  assert.equal(typeKindOf('varchar(255)'), 'str')
  assert.equal(typeKindOf('STRING'), 'str')
  assert.equal(typeKindOf('char(2)'), 'str')
  // boolean
  assert.equal(typeKindOf('boolean'), 'bool')
  assert.equal(typeKindOf('BOOL'), 'bool')
  // 复杂类型
  assert.equal(typeKindOf('array<string>'), 'complex')
  assert.equal(typeKindOf('map<int,string>'), 'complex')
  assert.equal(typeKindOf('struct<a:int,b:string>'), 'complex')
  assert.equal(typeKindOf('binary'), 'complex')
  // 时间等其余类型给兜底类（图标是「·」），空 / 缺失不画
  assert.equal(typeKindOf('timestamp'), 'other')
  assert.equal(typeKindOf('date'), 'other')
  assert.equal(typeKindOf(''), '')
  assert.equal(typeKindOf(null), '')
  // complex 的判断优先于数字（array<int> 不是数字）
  assert.equal(typeKindOf('array<int>'), 'complex')
})

test('buildCards：typesOf 把列类型并进行上；查不到给空串', () => {
  // NAMES 的下标：src=0、mid=1、dst=2；这条边是 src.id → dst.id
  const edges = [{ s: 0, sCol: 'id', t: 2, tCol: 'id', kind: 'DIRECT' }]
  const typesOf = (ref, colName) => (ref === 'paimon.dw.dst' && colName === 'id'
    ? 'bigint'
    : (ref === 'paimon.dw.src' ? 'varchar(10)' : ''))
  const cards = buildCards(NAMES, edges, { typesOf })
  assert.equal(cards.get('paimon.dw.dst').rows[0].type, 'bigint')
  assert.equal(cards.get('paimon.dw.src').rows[0].type, 'varchar(10)')
  // 不给 typesOf：行为与旧版一致，type 恒空串
  const plain = buildCards(NAMES, edges)
  assert.equal(plain.get('paimon.dw.dst').rows[0].type, '')
})
