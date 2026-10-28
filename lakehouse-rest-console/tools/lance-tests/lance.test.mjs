/**
 * Lance 面的 id 拼接与路径拼装。
 *
 * <p>这三条错了都不会报错，只会「查不到」：
 *
 *   · 分隔符写错（`.` / `::` 而不是 `$`）→ 服务端把整串当成**一个**层级，
 *     回 404 或列出空集，而页面上看不出是拼错的；
 *   · `$` 没有编码就塞进路径 → 大多数情况仍能工作，但名字里带空格 / 斜杠时
 *     路径会被切断，症状是「有的表点得开、有的点不开」；
 *   · `check_declared` 忘了带 → `is_only_declared` 恒为 `null`，界面上显示
 *     「未探测」，等于把「表到底建出来没有」这个问题原样还给用户。
 *
 * <p>因此这里从**拼出来的字符串**反推，而不是断言「函数返回了某个对象」。
 */
import assert from 'node:assert/strict'
import test from 'node:test'

import { buildPath } from '../../src/api/client.js'
import { LANCE_DELIMITER, LANCE_NAME_PATTERN, lanceId, lanceLeaf } from '../../src/api/lance.js'

test('id 用 $ 拼接，且丢掉空段', () => {
  assert.equal(LANCE_DELIMITER, '$')
  assert.equal(lanceId('catalog', 'demo'), 'catalog$demo')
  assert.equal(lanceId('catalog', 'demo', 'events'), 'catalog$demo$events')
  // 缺少中间那段时不能拼出 `catalog$$events`——服务端按 `$` 切会多出一段空层级
  assert.equal(lanceId('catalog', '', 'events'), 'catalog$events')
  assert.equal(lanceId(undefined, 'demo'), 'demo')
})

test('界面显示末段，完整 id 留给 title', () => {
  assert.equal(lanceLeaf('catalog$demo'), 'demo')
  assert.equal(lanceLeaf('catalog$demo$events'), 'events')
  assert.equal(lanceLeaf('catalog'), 'catalog')
  assert.equal(lanceLeaf(''), '')
})

test('表清单路径把 $ 编码成 %24', () => {
  assert.equal(
    buildPath('lanceListTables', { id: 'catalog$demo' }),
    '/lance/v1/namespace/catalog%24demo/table/list',
  )
})

test('命名空间列举与 describe 的路径落在 /lance/v1 下', () => {
  assert.equal(
    buildPath('lanceListNamespaces', { id: 'catalog' }),
    '/lance/v1/namespace/catalog/list',
  )
  assert.equal(
    buildPath('lanceDescribeTable', { id: 'catalog$demo$events' }),
    '/lance/v1/table/catalog%24demo%24events/describe',
  )
})

test('建命名空间的路径是 namespace/{id}/create，且 id 编码成 %24', () => {
  assert.equal(
    buildPath('lanceCreateNamespace', { id: lanceId('catalog', 'ods') }),
    '/lance/v1/namespace/catalog%24ods/create',
  )
})

test('名称白名单与 id 段一致：允许 _ - .，但不许 $（那是分隔符）', () => {
  // 服务端 LanceIds.LEVEL 是 [A-Za-z0-9_.-]+，两边必须是同一个集合：
  // 松了会在提交时才吃 400，紧了会挡掉服务端本来接受的名字。
  for (const ok of ['ods', 'sales_eu', 'sales-eu', 'v1.2', 'A1']) {
    assert.equal(LANCE_NAME_PATTERN.test(ok), true, ok)
  }
  for (const bad of ['', 'sales eu', 'sales/eu', 'cat$db', '账务']) {
    assert.equal(LANCE_NAME_PATTERN.test(bad), false, bad)
  }
})
