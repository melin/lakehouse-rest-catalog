/**
 * `utils/executionCluster.js` 里 M5 新增部分的用例：Spark 参数白名单与两组策略的默认值。
 *
 * <p>为什么单独测这一层：这里的错法**全都不报错**——
 * 白名单少一个前缀 → 用户以为填了的键生效了，实际上从来没到过 Spark 命令行；
 * 白名单多一个前缀（比如写成 `spark.sql.catalog.`）→ 那栏就成了「改认证目标」的旁路，
 * 而它看起来和别的键一模一样；
 * 前端默认值与列 `DEFAULT` 不一致 → 打开抽屉看到 200、保存一提交变成别的数，
 * 现象是「我什么都没改，值自己变了」。
 *
 * <p>还要挡住一类**看起来更严、其实更糟**的写法：把「格式不合法」的行也算进
 * 「会被丢弃」的清单。服务端对这类行直接 400（不是丢弃），前端把它说成「丢弃并记审计」
 * 会让用户以为「填错了也没关系」。
 */
import assert from 'node:assert/strict'
import test from 'node:test'

import {
  BATCH_DEFAULTS,
  BATCH_RANGES,
  SPARK_CONF_ALLOWED_KEYS,
  SPARK_CONF_ALLOWED_PREFIXES,
  SPARK_CONF_IMAGE_DEFAULTS,
  SPARK_CONF_MAX_VALUE,
  sparkConfKeyAllowed,
  sparkConfPlaceholder,
  sparkConfRejectedKeys,
} from '../../src/utils/executionCluster.js'

test('五个白名单前缀就是文档里那一份，顺序固定', () => {
  assert.deepEqual([...SPARK_CONF_ALLOWED_PREFIXES].sort(), [
    'spark.driver.',
    'spark.executor.',
    'spark.memory.',
    'spark.sql.files.',
    'spark.sql.shuffle.',
  ])
  // 精确键只有一个：给它单列是因为它没有可用的公共前缀，
  // 放开 spark.* 会把连接与认证键一起放进来。
  assert.deepEqual([...SPARK_CONF_ALLOWED_KEYS], ['spark.default.parallelism'])
})

test('前缀匹配要求比前缀更长（裸前缀不是合法键）', () => {
  assert.equal(sparkConfKeyAllowed('spark.driver.memory'), true)
  assert.equal(sparkConfKeyAllowed('spark.sql.shuffle.partitions'), true)
  assert.equal(sparkConfKeyAllowed('spark.default.parallelism'), true)
  // 只写前缀本身：它是个「命名空间」，不是一个 setter
  assert.equal(sparkConfKeyAllowed('spark.driver.'), false)
  assert.equal(sparkConfKeyAllowed('spark.driver'), false)
})

test('连接与认证键一律不在白名单内（那一栏不该承载安全语义）', () => {
  for (const key of [
    'spark.sql.catalog.paimon.token',
    'spark.sql.catalog.paimon.uri',
    'spark.sql.catalog.paimon.rest.auth.type',
    'spark.hadoop.fs.s3a.access.key',
    'spark.sql.session.timeZone',
    'spark.kubernetes.authenticate.driver.serviceAccountName',
  ]) {
    assert.equal(sparkConfKeyAllowed(key), false, `${key} 不该被放行`)
  }
})

test('rejectedKeys 只报白名单外的键，保序且去重', () => {
  const text = [
    'spark.driver.memory=4g',
    'spark.sql.catalog.x.token=secret',
    'spark.sql.shuffle.partitions=200',
    'spark.sql.catalog.x.token=another',
  ].join('\n')
  assert.deepEqual(sparkConfRejectedKeys(text), ['spark.sql.catalog.x.token'])
})

test('空行与 # 注释不算「被丢弃的键」（注释是给 textarea 留的备注位）', () => {
  const text = ['', '  ', '# 容器即 driver（--master local[*]）', 'spark.driver.cores=4', ''].join('\n')
  assert.deepEqual(sparkConfRejectedKeys(text), [])
})

test('格式不合法的行不算「被丢弃」——服务端对它是 400，不是静默丢掉', () => {
  // 把这个说成「丢弃并记审计」会让用户以为「填错了也没关系」
  assert.deepEqual(sparkConfRejectedKeys('spark.driver.memory'), [])
  assert.deepEqual(sparkConfRejectedKeys('=4g'), [])
  assert.deepEqual(sparkConfRejectedKeys(''), [])
  assert.deepEqual(sparkConfRejectedKeys(null), [])
})

test('值里带等号不影响键的判定（只在第一个 = 处切）', () => {
  assert.deepEqual(sparkConfRejectedKeys('spark.driver.extraJavaOptions=-Da=b'), [])
  assert.deepEqual(sparkConfRejectedKeys('spark.sql.catalog.x.uri=s3a://a=b'), ['spark.sql.catalog.x.uri'])
})

test('默认值是「镜像级」那份，只出现在 placeholder 里', () => {
  // 回填进输入框会让「留空」这个状态消失：之后镜像升级默认值时，
  // 所有 catalog 都停在旧参数上
  assert.deepEqual(SPARK_CONF_IMAGE_DEFAULTS.optimization, [
    'spark.driver.memory=4g',
    'spark.driver.cores=4',
    'spark.sql.shuffle.partitions=200',
  ])
  // 扫描那组与优化那组**刻意不同**（§5.6.7）：扫描是「拉一批列的样本回来打分」，
  // 峰值取决于列数 × 采样行上限而不是表多大，容器又就是 driver。因此 1g / 单核、
  // 且**不预置 shuffle.partitions**（小数据量下 200 分区是纯开销）
  assert.deepEqual(SPARK_CONF_IMAGE_DEFAULTS.scan, [
    'spark.driver.memory=1g',
    'spark.driver.cores=1',
  ])
  assert.ok(!SPARK_CONF_IMAGE_DEFAULTS.scan.some((line) => line.startsWith('spark.sql.shuffle.')))
  // executor.* 故意不配（容器即 driver，local 模式下是死配置）
  assert.ok(!SPARK_CONF_IMAGE_DEFAULTS.optimization.some((line) => line.startsWith('spark.executor.')))
})

test('placeholder 文本里既有默认值也有一句「留空即用它」', () => {
  const text = sparkConfPlaceholder('optimization')
  assert.ok(text.startsWith('留空 = 用镜像级默认'))
  for (const line of SPARK_CONF_IMAGE_DEFAULTS.optimization) {
    assert.ok(text.includes(line), `placeholder 少了 ${line}`)
  }
  // 认不出的类型给空列表而不是抛错：placeholder 是展示层，炸掉整屏不划算
  assert.equal(sparkConfPlaceholder('nope'), '留空 = 用镜像级默认：\n')
})

test('两组策略的默认值：扫描 200/3600、优化 20/2/7200', () => {
  assert.deepEqual(BATCH_DEFAULTS, {
    maxTablesPerJob: 20,
    maxConcurrentJobs: 2,
    jobTimeoutSeconds: 7200,
    maxScanTablesPerJob: 200,
    scanJobTimeoutSeconds: 3600,
  })
})

test('取值范围两对对称（优化的上限就是扫描的上限）', () => {
  assert.deepEqual(BATCH_RANGES.maxTablesPerJob, BATCH_RANGES.maxScanTablesPerJob)
  assert.deepEqual(BATCH_RANGES.jobTimeoutSeconds, BATCH_RANGES.scanJobTimeoutSeconds)
  // 并发只有一把闸门：扫描那组没有独立的 maxConcurrentScans，
  // 也自然没有对应的区间——它用的是 maxConcurrentJobs 那一个。
  assert.equal(BATCH_DEFAULTS.maxConcurrentScans, undefined)
  assert.equal(BATCH_RANGES.maxConcurrentScans, undefined)
  // 每个默认值都必须落在自己的区间里：区间收紧而默认值没跟上时，
  // 表单会一打开就报「不在范围内」
  for (const [name, value] of Object.entries(BATCH_DEFAULTS)) {
    const [min, max] = BATCH_RANGES[name]
    assert.ok(value >= min && value <= max, `${name} 的默认值 ${value} 不在 ${min}–${max} 内`)
  }
})

test('单行值的上限与服务端同一量级（列是 TEXT，但没必要放开）', () => {
  assert.equal(SPARK_CONF_MAX_VALUE, 1024)
})
