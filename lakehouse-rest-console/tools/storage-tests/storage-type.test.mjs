/**
 * `utils/storageType.js` 的用例：存储类型的中文名、排列顺序与「能不能选」。
 *
 * <p>为什么这三件事值得单独测——它们的错误都不会让控制台报错，只会让人按错的
 * 前提去操作：
 *
 *   · 显示裸枚举值（`AZURE` / `FILE`）→ 不认识 ADLS 的人以为那是另一种东西，
 *     `FILE` 也不像「本地文件」，而这两个恰好是「允许位置」写法的分水岭
 *     （`abfss://` vs `file://`）；
 *   · 顺序跟着 meta 的枚举定义序走 → `GCS` 夹在 `S3` 与 `AZURE` 中间，
 *     七种存储按「用户会不会挑它」读起来是乱的；
 *   · 「本部署未接入」的类型变成可点 → 这一条最贵：填完配置、点保存、
 *     才被服务端以「本部署未接入该存储实现」拒绝，而界面上它一直是个正常按钮。
 *
 * <p>最后一条在**界面上验不到**：本仓库默认部署支持全部七种，永远不会出现灰态。
 * 所以判据抽成了纯函数放在这里，用「假设的部署」去测它。
 */
import assert from 'node:assert/strict'
import test from 'node:test'

import {
  isUnsupportedStorageType,
  STORAGE_TYPE_LABELS,
  sortStorageTypes,
  storageTypeLabel,
} from '../../src/utils/storageType.js'

/** 服务端 meta 真正下发的枚举顺序（`ConsoleDtos.Enums#storageTypes`）。 */
const META_ORDER = ['S3', 'GCS', 'AZURE', 'OBS', 'OSS', 'COS', 'FILE']

test('七种存储都有显示名；除 GCS 外都不是裸枚举值', () => {
  assert.deepEqual(Object.keys(STORAGE_TYPE_LABELS).sort(), [...META_ORDER].sort())
  for (const [type, label] of Object.entries(STORAGE_TYPE_LABELS)) {
    assert.ok(label.length > 0)
    // GCS 是唯一的例外：它就是 Google Cloud Storage 的通用叫法，
    // 硬翻成「谷歌云存储」反而与所有人日常说的那个词对不上（原型也写 GCS）
    if (type !== 'GCS') {
      assert.notEqual(label, type, `${type} 的显示名不该是它自己`)
    }
  }
  // 这四处是「看名字猜不到」的，逐条钉住
  assert.equal(storageTypeLabel('AZURE'), 'Azure ADLS')
  assert.equal(storageTypeLabel('FILE'), '本地文件')
  assert.equal(storageTypeLabel('OSS'), '阿里云 OSS')
  assert.equal(storageTypeLabel('COS'), '腾讯云 COS')
})

test('认不出的类型原样返回，空值给空串', () => {
  // 原样返回而不是「未知」：服务端新加一种存储时，NOPE 能直接拿去搜
  assert.equal(storageTypeLabel('NOPE'), 'NOPE')
  assert.equal(storageTypeLabel(''), '')
  assert.equal(storageTypeLabel(undefined), '')
})

test('排列按原型的顺序，不跟 meta 的枚举定义序走', () => {
  assert.deepEqual(sortStorageTypes(META_ORDER), ['S3', 'OSS', 'OBS', 'AZURE', 'GCS', 'COS', 'FILE'])
  // 与 meta 给的原数组解耦：函数不能就地改调用方那份
  const input = [...META_ORDER]
  sortStorageTypes(input)
  assert.deepEqual(input, META_ORDER)
})

test('不认识的类型排在最后，而不是被丢掉', () => {
  const sorted = sortStorageTypes([...META_ORDER, 'NOPE'])
  assert.equal(sorted.at(-1), 'NOPE')
  assert.equal(sorted.length, META_ORDER.length + 1)
  // 两个未知值之间保持原顺序（sort 自 ES2019 起稳定）
  assert.deepEqual(sortStorageTypes(['ZED', 'NOPE']), ['ZED', 'NOPE'])
})

test('本部署支持全部七种时，一个都不灰', () => {
  for (const type of META_ORDER) {
    assert.equal(isUnsupportedStorageType(type, META_ORDER), false)
  }
})

test('只接了 S3 的部署：其余六种都灰，S3 不灰', () => {
  assert.equal(isUnsupportedStorageType('S3', ['S3']), false)
  for (const type of ['OSS', 'OBS', 'AZURE', 'GCS', 'COS', 'FILE']) {
    assert.equal(isUnsupportedStorageType(type, ['S3']), true, `${type} 应当灰掉`)
  }
})

test('supportedTypes 为空时不灰任何一项（那是「meta 没读到」，不是「一种都不支持」）', () => {
  // 全灰会让表单在这一刻完全没法填，而它的默认值恰恰是能用的
  assert.equal(isUnsupportedStorageType('S3', []), false)
  assert.equal(isUnsupportedStorageType('FILE', []), false)
  assert.equal(isUnsupportedStorageType('S3', undefined), false)
})
