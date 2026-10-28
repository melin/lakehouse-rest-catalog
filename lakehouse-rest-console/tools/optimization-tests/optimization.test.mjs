/**
 * `utils/optimization.js` 的用例：按格式取参数集、动作级「不可用」、以及三套指标键。
 *
 * <p>为什么单独测这一层：这三种错法**都不报错**——
 *
 *   · `actionsFor('LANCE')` 落到 Paimon 那一支 → 表单里出现 `min-input-files` /
 *     `retain-min` / `dry-run`，少了 `target-rows-per-fragment`。用户填完保存时被服务端
 *     用 Lance 的键表拒掉（400），而页面看上去一切正常；
 *   · `orphan` 在 Lance 上被渲染成可勾选 → 点开就 400，或者更糟：批次里带上一个
 *     不会被翻译成任何语句的动作；
 *   · `metricSummary` 不认识 Lance 的指标键 → 历史表里那一列永远是「—」，
 *     谁也不会注意到「这次 VACUUM 到底清了几个版本」。
 *
 * <p>这三条都能被静态校验（`scripts/verify-console.py` 第 11 组）部分覆盖，但那边比的是
 * **键集合**，判不出「键集合对了、却挂到了另一种格式上」。因此这里从行为面钉一遍。
 */
import assert from 'node:assert/strict'
import test from 'node:test'

import {
  ACTIONS,
  actionsFor,
  ICEBERG_ACTIONS,
  LANCE_ACTIONS,
  metricSummary,
} from '../../src/utils/optimization.js'

/** 某一套动作定义里，某个动作的字段键（保持定义顺序）。 */
function fieldKeys(actions, action) {
  const spec = actions.find((item) => item.action === action)
  return spec ? spec.fields.map((field) => field.key) : null
}

test('三种格式各取自己那一套：LANCE 不再落到 Paimon', () => {
  assert.equal(actionsFor('PAIMON'), ACTIONS)
  assert.equal(actionsFor('ICEBERG'), ICEBERG_ACTIONS)
  assert.equal(actionsFor('LANCE'), LANCE_ACTIONS)
})

test('认不出的格式与缺省都按 Paimon（与服务端 CatalogFormat.of 同向）', () => {
  // 回落目标仍是「默认格式」，这是兼容旧数据的取舍；但 LANCE 必须是显式的一支，
  // 否则它就从「另一种格式」变成了「错误答案」——这条与上面那条合起来才完整
  assert.equal(actionsFor(''), ACTIONS)
  assert.equal(actionsFor(undefined), ACTIONS)
  assert.equal(actionsFor('HUDI'), ACTIONS)
})

test('Lance 的 compact 是 7 个键：口径整块换了，没有一个键与 Paimon 重合', () => {
  const lance = fieldKeys(LANCE_ACTIONS, 'compact')
  assert.deepEqual(lance, [
    'target-rows-per-fragment',
    'max-rows-per-group',
    'materialize-deletions',
    'materialize-deletions-threshold',
    'defer-index-remap',
    'batch-size',
    'interval-hours',
  ])
  // Paimon 那三个概念在 Lance 上不存在：目标大小按**行**算、没有「至少几个小文件」
  // 的门槛、没有「最多扫多少碎片」的旋钮（connector 没接出来，见服务端类注释）
  for (const absent of ['min-input-files', 'target-file-size-mb', 'max-source-fragments', 'strategy']) {
    assert.ok(!lance.includes(absent), `Lance 的 compact 不该有 ${absent}`)
  }
  // 也没有 `retain-*`：Lance 的版本保留是 expire 的事，且只有 retention-hours 一个键
  assert.ok(!fieldKeys(LANCE_ACTIONS, 'expire').some((key) => key.startsWith('retain-')))
})

test('Lance 的 orphan 是空集，且这张卡静态就是「不可用」', () => {
  assert.deepEqual(fieldKeys(LANCE_ACTIONS, 'orphan'), [])
  const orphan = LANCE_ACTIONS.find((item) => item.action === 'orphan')
  assert.equal(orphan.supported, false)
  // 另两项照常可用——「这张格式少了孤儿清理」不等于「整块不可用」
  assert.equal(LANCE_ACTIONS.find((item) => item.action === 'compact').supported, true)
  assert.equal(LANCE_ACTIONS.find((item) => item.action === 'expire').supported, true)
  // 另两种格式三项都可用（它们的第三张卡是「默认关」而不是「不存在」）
  for (const action of ['compact', 'expire', 'orphan']) {
    assert.equal(ACTIONS.find((item) => item.action === action).supported, true)
    assert.equal(ICEBERG_ACTIONS.find((item) => item.action === action).supported, true)
  }
})

test('delete-unverified 在表单里锁死（只接受 false，不给人一个点了就 400 的开关）', () => {
  const spec = LANCE_ACTIONS.find((item) => item.action === 'expire')
  const field = spec.fields.find((item) => item.key === 'delete-unverified')
  assert.equal(field.type, 'switch')
  assert.equal(field.disabled, true)
})

test('指标：Lance 的增量化读数（碎片 −8 / +2，清理 3 个版本）', () => {
  const compact = metricSummary({
    metrics: {
      'compact.fragments-removed': '8',
      'compact.fragments-added': '2',
      'compact.files-removed': '12',
      'compact.files-added': '3',
    },
  }, 'compact')
  assert.equal(compact, '碎片 −8 / +2 · 文件 −12 / +3')

  // bytes-removed 由执行侧转成人类可读（与 Paimon 的 released-size 同一口径），
  // 控制台不再实现一遍「字节怎么显示」
  const expire = metricSummary({
    metrics: { 'expire.old-versions': '3', 'expire.bytes-removed': '1.2 GB' },
  }, 'expire')
  assert.equal(expire, '清理 3 个版本 · 释放 1.2 GB')
})

test('指标：三套键互不串台，认不出的键不显示', () => {
  // 只带 Lance 键的明细，不该被 Paimon / Iceberg 的口径解释成「? → ?」
  const onlyLance = metricSummary({ metrics: { 'compact.fragments-added': '2' } }, 'compact')
  assert.equal(onlyLance, '碎片 −? / +2')
  // 不认识的键原样糊到页面上是最糟的表现：指标口径归执行侧所有
  assert.equal(metricSummary({ metrics: { 'compact.some-new-key': '42' } }, 'compact'), '')
  // 前缀剥错动作（Paimon 的键挂在 expire 下）时不显示
  assert.equal(metricSummary({ metrics: { 'expire.files-before': '9' } }, 'compact'), '')
})
