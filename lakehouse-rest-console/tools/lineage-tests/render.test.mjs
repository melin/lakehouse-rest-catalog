/**
 * `utils/lineageRender.js` 里能脱离 Canvas 验证的那部分：LOD 滞回、线宽、短名、取色。
 *
 * <p>绘制本身（`drawFrame`）不在这里测：它要一个真实的 2D 上下文，而这类断言的价值
 * 全在像素上——那是看出来的，不是测出来的。但**阈值**必须测：滞回写错的表现是
 * 「缩放停在临界点上会闪」，肉眼要盯很久才看得出来。
 */
import assert from 'node:assert/strict'
import test from 'node:test'

import { colorOf, edgeWidth, ellipsizeMiddle, lodFor, prefixOf, tailOf } from '../../src/utils/lineageRender.js'

test('LOD 三档：进档要「明显够大」，退档要「明显变小」', () => {
  assert.equal(lodFor(1, 0), 0)
  assert.equal(lodFor(0.9, 1), 0)
  assert.equal(lodFor(0.89, 1), 1)
  // 从最低档回到高档的门槛更高（0.93）：缩到最小再拉回来时，迟钝一点才不闪
  assert.equal(lodFor(0.92, 2), 1)
  assert.equal(lodFor(0.93, 2), 0)
  assert.equal(lodFor(0.5, 1), 2)
  // 默认参数按「当前在最高档」处理
  assert.equal(lodFor(0.8), 1)
})

test('滞回是真的：同一个缩放值，不同来路给不同档位', () => {
  // 0.88 处在 0.86（退档）与 0.9（进档）之间：从高档过来仍是高档，从中档过来仍是中档
  assert.equal(lodFor(0.88, 0), 0)
  assert.equal(lodFor(0.88, 1), 1)
  // 0.56 同理（0.52 / 0.58）
  assert.equal(lodFor(0.56, 1), 1)
  assert.equal(lodFor(0.56, 2), 2)
})

test('线宽按 log10(列数 + 1) 增长：1 列 1px、9 列 2px、99 列 3px', () => {
  assert.equal(edgeWidth(0), 1)
  assert.equal(edgeWidth(9), 2)
  assert.ok(Math.abs(edgeWidth(99) - 3) < 1e-9)
  // 单调：列多的线一定不比列少的细
  assert.ok(edgeWidth(30) > edgeWidth(3))
})

test('中间省略保留首末段（尾部截断会让一串 order_daily 长得一样）', () => {
  const name = 'paimon.dw.dws.order_daily_snapshot_2024'
  const short = ellipsizeMiddle(name, 26)
  assert.equal(short.length, 26)
  assert.ok(short.includes('…'))
  assert.ok(short.endsWith(name.slice(-14)))
  // 短名字原样返回，不加省略号
  assert.equal(ellipsizeMiddle('a.b.t', 26), 'a.b.t')
  assert.equal(ellipsizeMiddle('', 26), '')
})

test('短名取末段；外部节点也照末段显示', () => {
  assert.equal(tailOf('paimon.dw.orders'), 'orders')
  assert.equal(tailOf('~file:8c1f'), '~file:8c1f')
  assert.equal(tailOf(''), '')
})

test('prefixOf：ref 去掉末段表名，剩 catalog.namespace；裸表名没有可提示的前缀', () => {
  assert.equal(prefixOf('paimon.dw.orders'), 'paimon.dw')
  // 多级 namespace：中间段全保留（tooltip 要能完整还原 catalog 与库）
  assert.equal(prefixOf('paimon.a.b.orders'), 'paimon.a.b')
  assert.equal(prefixOf('orders'), '')
  assert.equal(prefixOf(''), '')
})

test('取色：外部节点单列一色（「点不开」比「哪种格式」更重要）', () => {
  const theme = { paimon: 'P', iceberg: 'I', external: 'X', border: 'B' }
  assert.equal(colorOf({ kind: 'TABLE', format: 'PAIMON' }, theme), 'P')
  assert.equal(colorOf({ kind: 'TABLE', format: 'iceberg' }, theme), 'I')
  assert.equal(colorOf({ kind: 'EXTERNAL', format: 'PAIMON' }, theme), 'X')
  // 目录里查不到格式（旧边、外部引擎）：中性色，不猜
  assert.equal(colorOf({ kind: 'TABLE', format: null }, theme), 'X')
  assert.equal(colorOf(null, theme), 'X')
})
