/**
 * `utils/autoRefresh.js` 的用例：偏好收敛与「这一拍要不要发请求」。
 *
 * <p>为什么单独测这一层：定时刷新的错法**全是静默的**——
 * 后台标签页照常轮询（页签开一夜，第二天账单上多几千次查询）、
 * 关掉开关后定时器没停（用户以为停了）、上一拍没回来就发下一拍（慢网络下请求堆叠）、
 * 读到一份坏的 localStorage 导致 `setInterval(fn, NaN)`（等于 0 毫秒，把浏览器打满）。
 * 这些在界面上都看不出来，只能靠把每种输入与每种组合逐条钉住。
 */
import assert from 'node:assert/strict'
import test from 'node:test'

import {
  AUTO_REFRESH_DEFAULT,
  AUTO_REFRESH_INTERVALS,
  autoRefreshKey,
  readPreference,
  sanitizePreference,
  shouldTick,
  writePreference,
} from '../../src/utils/autoRefresh.js'

/** 一个最小的内存版 Storage，`throwOn` 用来模拟隐私模式 / 配额满。 */
function memoryStorage(initial = {}, { throwOn } = {}) {
  const map = new Map(Object.entries(initial))
  return {
    getItem: (key) => {
      if (throwOn === 'get') throw new Error('storage 不可用')
      return map.has(key) ? map.get(key) : null
    },
    setItem: (key, value) => {
      if (throwOn === 'set') throw new Error('storage 不可用')
      map.set(key, value)
    },
    raw: map,
  }
}

test('默认值：开启、5 秒（需求写死的两个数只在这里出现一次）', () => {
  assert.equal(AUTO_REFRESH_DEFAULT.enabled, true)
  assert.equal(AUTO_REFRESH_DEFAULT.seconds, 5)
  // 默认节拍必须是档位里的一项，否则控件打开时显示不出当前值
  assert.ok(AUTO_REFRESH_INTERVALS.includes(AUTO_REFRESH_DEFAULT.seconds))
})

test('偏好收敛：不认识的输入一律回默认，而不是把「没读到」当成「关掉了」', () => {
  for (const garbage of [null, undefined, 0, '', 'x', [], { enabled: 'yes' }]) {
    assert.deepEqual(sanitizePreference(garbage), { enabled: true, seconds: 5 })
  }
  assert.deepEqual(sanitizePreference({ enabled: false }), { enabled: false, seconds: 5 })
  assert.deepEqual(sanitizePreference({ enabled: true, seconds: 30 }), { enabled: true, seconds: 30 })
  // 字符串数字（localStorage 里手改过的值）要能收下
  assert.deepEqual(sanitizePreference({ seconds: '10' }), { enabled: true, seconds: 10 })
})

test('偏好收敛：档位之外的值回默认——NaN 秒会让 setInterval 变成每 0 毫秒一次', () => {
  for (const seconds of [0, 1, 2, 7, -5, NaN, Infinity, '5s']) {
    assert.equal(sanitizePreference({ seconds }).seconds, 5, `seconds=${seconds}`)
  }
  for (const seconds of AUTO_REFRESH_INTERVALS) {
    assert.equal(sanitizePreference({ seconds }).seconds, seconds)
  }
})

test('读偏好：没有记录、坏 JSON、storage 抛异常都回默认（都不抛）', () => {
  const empty = memoryStorage()
  assert.deepEqual(readPreference(empty, autoRefreshKey('catalogs')), { enabled: true, seconds: 5 })

  const broken = memoryStorage({ 'lakehouse.console.autoRefresh.catalogs': '{not json' })
  assert.deepEqual(readPreference(broken, autoRefreshKey('catalogs')), { enabled: true, seconds: 5 })

  const throwing = memoryStorage({}, { throwOn: 'get' })
  assert.deepEqual(readPreference(throwing, autoRefreshKey('catalogs')), { enabled: true, seconds: 5 })
})

test('读偏好：两个列表页各存一份，互不影响', () => {
  const storage = memoryStorage({
    [autoRefreshKey('catalogs')]: JSON.stringify({ enabled: false, seconds: 30 }),
    [autoRefreshKey('optimization-jobs')]: JSON.stringify({ enabled: true, seconds: 3 }),
  })
  assert.deepEqual(readPreference(storage, autoRefreshKey('catalogs')), { enabled: false, seconds: 30 })
  assert.deepEqual(readPreference(storage, autoRefreshKey('optimization-jobs')), { enabled: true, seconds: 3 })
})

test('写偏好：落盘前先收敛（坏值不落进去），写不进去只返回 false 不抛', () => {
  const storage = memoryStorage()
  assert.equal(writePreference(storage, autoRefreshKey('catalogs'), { enabled: false, seconds: 999 }), true)
  assert.deepEqual(
    JSON.parse(storage.raw.get(autoRefreshKey('catalogs'))),
    { enabled: false, seconds: 5 },
    '越界的节拍不该被原样写进 localStorage，否则下次读出来是个永远不生效的值',
  )

  const throwing = memoryStorage({}, { throwOn: 'set' })
  assert.equal(writePreference(throwing, autoRefreshKey('catalogs'), { enabled: true, seconds: 5 }), false)
})

test('节拍判定：四个跳过理由各自成立，且缺一个都不行', () => {
  assert.equal(shouldTick({}), true, '默认开启、在前台、没有在途请求、页面不拦——就该刷')
  assert.equal(shouldTick(), true)

  assert.equal(shouldTick({ enabled: false }), false, '关掉开关后还在发请求')
  assert.equal(shouldTick({ hidden: true }), false, '后台标签页照常轮询')
  assert.equal(shouldTick({ inFlight: true }), false, '上一拍没回来又发下一拍')
  assert.equal(shouldTick({ blocked: true }), false, '页面说「现在别刷」')

  // 只差一个条件就要刷：判据之间是 AND，不是「任一命中就刷」
  assert.equal(shouldTick({ enabled: true, hidden: false, inFlight: false, blocked: false }), true)
  assert.equal(shouldTick({ enabled: true, hidden: false, inFlight: false, blocked: true }), false)
})
