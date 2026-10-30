/**
 * `utils/engineSnippet.js` 的用例：五个面的接入片段。
 *
 * <p>为什么值得单独测：片段是**复制出去的东西**，它错了不会让控制台报任何错——
 *
 *   · `token.provider` 写成 `bearer` → Paimon 客户端不认，报的是「认证失败」，
 *     与「令牌过期 / 权限不足」长得一模一样；
 *   · 服务端不下发凭据却还在片段里留着 `data-token.enabled=true` → 引擎一直等一份
 *     不会来的凭据，最终超时或 403，而配置看上去「跟文档一样」；
 *   · Flink 的 DDL 最后一行少了 / 多了一个逗号 → 语法错误指向语句开头，
 *     看不出是哪一行的问题；
 *   · Lance 的 `uri` 没写到 `/lance` 为止、或丢了 `delimiter` → 每个请求都被解析成别的东西。
 *
 * <p>页面上的 warning 一条都不会跟着片段走，因此这里的断言全部对着**片段文本本身**，
 * 而不是「模块里有没有这个常量」。静态校验（`verify-console.py` 第 12 组）罩的是
 * 「格式怎么选、路由在哪」；这一层罩的是「复制出去那十几行对不对」。
 */
import assert from 'node:assert/strict'
import test from 'node:test'

import {
  buildEngineSnippet,
  defaultSnippetFace,
  RENEWAL,
  SNIPPET_FACES,
  snippetFaces,
} from '../../src/utils/engineSnippet.js'

/** 片段里的纯文本行（复制出去正好是这些）。 */
function textOf(snippet) {
  return snippet.lines.map((line) => line.text)
}

const pairOf = (snippet, key) => snippet.lines.find((line) => line.key === key)?.value
const allText = (snippet) => textOf(snippet).join('\n')

test('五个面都能生成片段，且 copyText 与显示的行逐字一致', () => {
  for (const [format, faces] of Object.entries(SNIPPET_FACES)) {
    for (const face of faces) {
      const snippet = buildEngineSnippet({
        format,
        face: face.id,
        catalog: 'paimon',
        location: 's3://bucket/warehouse/',
      })
      assert.ok(snippet.lines.length > 0, `${face.id} 没有生成任何行`)
      // 页面渲染的是 lines，复制按钮发的是 copyText：两处各拼一份时，
      // 症状是「屏幕上看到的和粘贴出来的不一样」，而没人会去逐行比对
      assert.equal(snippet.copyText, textOf(snippet).join('\n'), `${face.id} 的 copyText 与行不一致`)
      assert.ok(snippet.note.length > 0, `${face.id} 应该有一句说明（页面上显示，不随片段走）`)
    }
  }
})

test('认不出的面直接抛错，不悄悄回落到某个面', () => {
  assert.throws(() => buildEngineSnippet({ format: 'PAIMON', face: 'spark-hudi' }), /unknown face/)
})

test('Paimon · Spark：token.provider 是 bear（不是 bearer），且不下发时改用本地密钥', () => {
  const vended = buildEngineSnippet({
    format: 'PAIMON', face: 'spark-paimon', catalog: 'paimon', location: 's3://b/w/',
  })
  assert.equal(pairOf(vended, 'spark.sql.catalog.paimon.token.provider'), 'bear')
  assert.equal(pairOf(vended, 'spark.sql.catalog.paimon.data-token.enabled'), 'true')
  // 「签发」与「转发」在客户端配置上完全一样：片段里不该出现任何密钥
  assert.ok(!allText(vended).includes('access-key'), '下发凭据时不该出现本地密钥')

  const manual = buildEngineSnippet({
    format: 'PAIMON', face: 'spark-paimon', catalog: 'paimon', location: 's3://b/w/', vendored: false,
  })
  assert.equal(pairOf(manual, 'spark.sql.catalog.paimon.data-token.enabled'), 'false')
  assert.equal(pairOf(manual, 'spark.sql.catalog.paimon.s3.access-key'), '<AK>')
  // 不下发时也不能还留着 data-token：引擎会一直等一份不会来的凭据
  assert.ok(!allText(vended).includes('<AK>'))
})

test('三个 Spark 面的首行是 pair（`spark.sql.catalog.<name>=<实现类>`），键值分得开', () => {
  for (const [format, face, impl] of [
    ['PAIMON', 'spark-paimon', 'org.apache.paimon.spark.SparkCatalog'],
    ['ICEBERG', 'spark-iceberg', 'org.apache.iceberg.spark.SparkCatalog'],
    ['LANCE', 'spark-lance', 'org.lance.spark.LanceNamespaceSparkCatalog'],
  ]) {
    const snippet = buildEngineSnippet({ format, face, catalog: 'paimon' })
    const first = snippet.lines[0]
    // 这一行确实是 `key=value`：标成 plain 的话，页面上不会上色，看起来像「这行不用改」，
    // 而它恰恰是必配项（少了它连 catalog 都不存在）
    assert.equal(first.kind, 'pair', `${face} 的首行应该是 pair`)
    assert.equal(first.key, 'spark.sql.catalog.paimon')
    assert.equal(first.value, impl)
    assert.equal(first.text, `spark.sql.catalog.paimon=${impl}`)
  }
})

test('Paimon · Flink：metastore=rest 必填，且 DDL 只有最后一行不带逗号', () => {
  const snippet = buildEngineSnippet({ format: 'PAIMON', face: 'flink-paimon', catalog: 'paimon' })
  assert.equal(pairOf(snippet, 'metastore'), 'rest')
  assert.match(snippet.lines[0].text, /^CREATE CATALOG paimon WITH \($/)

  const body = snippet.lines.filter((line) => line.kind === 'flink')
  assert.ok(body.length >= 5)
  for (const line of body.slice(0, -1)) {
    assert.match(line.text, /,$/, '除最后一行外都要带逗号')
  }
  assert.ok(!body.at(-1).text.endsWith(','), '最后一行不能带逗号')
  // `);` 必须紧跟在最后一行之后。这个位置比「最后一行是 `);`」更准确：片段尾部的注释
  // （仓库位置、COS 说明）都在它后面，按「数组末元素」判会永远不成立
  const lastRow = snippet.lines.indexOf(body.at(-1))
  assert.equal(snippet.lines[lastRow + 1].text, ');')

  // 行 kind 是 'flink' 而不是 'pair'：这一行带着缩进与行尾逗号，
  // 按 key=value 重新拼会把 DDL 的形状丢掉
  assert.ok(!body.some((line) => line.kind === 'pair'))
})

test('Iceberg：delegation 靠 header.* 注入，不是内置开关', () => {
  const snippet = buildEngineSnippet({ format: 'ICEBERG', face: 'spark-iceberg', catalog: 'ice' })
  assert.equal(
    pairOf(snippet, 'spark.sql.catalog.ice.header.X-Iceberg-Access-Delegation'),
    'vended-credentials',
  )
  // Iceberg 没有 token.provider 这一项：把它抄过来，Spark 会当成一个未知的 catalog 属性收下，
  // 然后什么都不做
  assert.ok(!allText(snippet).includes('token.provider'))
})

test('Iceberg · Flink：恒关缓存（凭据换了它也看不见）', () => {
  const vended = buildEngineSnippet({ format: 'ICEBERG', face: 'flink-iceberg', catalog: 'ice' })
  assert.equal(pairOf(vended, 'catalog-type'), 'rest')
  assert.equal(pairOf(vended, 'cache-enabled'), 'false')
  assert.equal(pairOf(vended, 'header.X-Iceberg-Access-Delegation'), 'vended-credentials')

  // 不下发时不能留 delegation 头：留着它等于告诉服务端「给我凭据」，
  // 而这一档恰恰是本地自己配
  const manual = buildEngineSnippet({
    format: 'ICEBERG', face: 'flink-iceberg', catalog: 'ice', vendored: false,
  })
  assert.equal(pairOf(manual, 'header.X-Iceberg-Access-Delegation'), undefined)
  assert.equal(pairOf(manual, 's3.access-key'), '<AK>')
  assert.equal(pairOf(manual, 'cache-enabled'), 'false')
})

test('Lance：基址写到 /lance 为止，delimiter 与服务端一致，凭据键是 aws_*', () => {
  const snippet = buildEngineSnippet({
    format: 'LANCE', face: 'spark-lance', catalog: 'lance', serverUrl: 'http://host:8080',
  })
  assert.equal(pairOf(snippet, 'spark.sql.catalog.lance.uri'), 'http://host:8080/lance')
  assert.equal(pairOf(snippet, 'spark.sql.catalog.lance.delimiter'), '$')
  assert.equal(pairOf(snippet, 'spark.sql.catalog.lance.single_level_ns'), 'false')
  assert.match(snippet.note, /\/lance/)

  // Lance 面的凭据是 describe 里的 storage_options：键族是 aws_*，不是 s3.*。
  // 写错的表现是「连上了、读不到数据」，而不是配置报错
  assert.match(allText(snippet), /aws_access_key_id/)
  assert.ok(!allText(snippet).includes('s3.access-key'))
})

test('Lance 只有一个页签（connector 没有 Flink 版）', () => {
  assert.equal(snippetFaces('LANCE').length, 1)
  assert.equal(defaultSnippetFace('LANCE'), 'spark-lance')
  assert.deepEqual(snippetFaces('PAIMON').map((face) => face.id), ['spark-paimon', 'flink-paimon'])
  assert.deepEqual(snippetFaces('ICEBERG').map((face) => face.id), ['spark-iceberg', 'flink-iceberg'])
})

test('认不出的格式给空页签，而不是退回 Paimon 的那一套', () => {
  // 服务端新加一种格式时，给它 Paimon 的片段会让人按错的键名去连；
  // 空数组至少会说「这一面还没有片段」。
  // 注意这里不能用 catalogFormatOf() 归一化——它把认不出的值按 Paimon 回落
  // （那是徽标与筛选要的口径），用在这里会把新格式伪装成 Paimon
  assert.deepEqual(snippetFaces('DELTA'), [])
  assert.equal(defaultSnippetFace('DELTA'), '')
  // 缺列（空串）与缺省仍按 Paimon 回落，与徽标、筛选保持同一方向
  assert.deepEqual(snippetFaces('').map((face) => face.id), ['spark-paimon', 'flink-paimon'])
})

test('缺名字时用 lakehouse 占位；物理位置只写进注释，不变成配置键', () => {
  const snippet = buildEngineSnippet({ format: 'PAIMON', face: 'spark-paimon' })
  assert.equal(pairOf(snippet, 'spark.sql.catalog.lakehouse.warehouse'), 'lakehouse')

  // REST 下 `warehouse` 是 prefix（就是 catalog 名），物理路径由服务端解析。
  // 把 s3://… 写进某个键会让人以为改这里就能换仓库——那要重建 catalog
  const located = buildEngineSnippet({
    format: 'PAIMON', face: 'spark-paimon', catalog: 'paimon', location: 's3://bucket/warehouse/',
  })
  const where = located.lines.find((line) => line.text.includes('s3://bucket/warehouse/'))
  assert.ok(where, '片段里应该说明它连的是哪个仓库')
  assert.equal(where.kind, 'comment')
  assert.equal(pairOf(located, 'spark.sql.catalog.paimon.warehouse'), 'paimon')

  // 没填位置时按存储类型给占位：给空串等于「这里不用填」，而它其实是有值的
  const placeholder = buildEngineSnippet({ format: 'PAIMON', face: 'spark-paimon', storageType: 'FILE' })
  assert.match(allText(placeholder), /file:\/\/\/data\//)

  const cos = buildEngineSnippet({ format: 'PAIMON', face: 'spark-paimon', storageType: 'COS' })
  assert.match(allText(cos), /cosn:\/\/<bucket>-<appid>\//)
})

test('COS 在三个面上是三种说法（Paimon 走 COSN、Iceberg 只能 HadoopFileIO、Lance 没有 COSN）', () => {
  const paimon = buildEngineSnippet({ format: 'PAIMON', face: 'spark-paimon', storageType: 'COS' })
  assert.match(allText(paimon), /fs\.cosn\./)

  const iceberg = buildEngineSnippet({ format: 'ICEBERG', face: 'spark-iceberg', storageType: 'COS' })
  assert.match(allText(iceberg), /HadoopFileIO/)

  const lance = buildEngineSnippet({ format: 'LANCE', face: 'spark-lance', storageType: 'COS' })
  assert.match(allText(lance), /没有 COSN/)

  // 非 COS 时一条都不该出现：三个面写成一样，等于让两个面静默失败
  const s3 = buildEngineSnippet({ format: 'PAIMON', face: 'spark-paimon', storageType: 'S3' })
  assert.ok(!allText(s3).includes('fs.cosn.'))
})

test('续期能力按格式各一份，且都是「能不能自动续」这句话的回答', () => {
  assert.deepEqual(Object.keys(RENEWAL).sort(), ['ICEBERG', 'LANCE', 'PAIMON'])
  assert.equal(RENEWAL.PAIMON.label, '能')
  assert.equal(RENEWAL.LANCE.label, '不能')
  assert.match(RENEWAL.ICEBERG.label, /看客户端/)
  for (const entry of Object.values(RENEWAL)) {
    assert.ok(entry.hint.length > 10)
  }
})
