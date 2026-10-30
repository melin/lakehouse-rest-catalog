/**
 * 「引擎怎么连这个 catalog」的**可复制片段**生成器。
 *
 * <p>为什么要有这个模块：三个协议面的键名族完全不同（`token.provider=bear` 不是
 * `bearer`、Iceberg 的 delegation 头靠 `header.*` 注入、Flink 用 `CREATE CATALOG`
 * 而 Spark 用 `spark.sql.catalog.*`、Lance 要多一个 `delimiter`），抽象成一份
 * 「通用配置」的结果是每一面都跑不起来。因此这里**不做抽象**，按面各写一份，
 * 只共享「怎么拼 key=value」与「Flink 的最后一行不加逗号」这两件机械的事。
 *
 * <p>三条硬约束，改这个模块时不要破：
 *
 * <ol>
 *   <li><b>纯函数，不碰 Vue / window / DOM。</b>片段的内容是服务端契约的投影，
 *       要能被 `tools/snippet-tests` 直接钉住；混进组件状态之后，它只能在浏览器里被验证。</li>
 *   <li><b>不判凭据档位。</b>「发不发凭据」由调用方给（`vendored`），档位本身是服务端
 *       算的（见 `utils/credentialSource.js`）。这里只决定两种形态各自长什么样，
 *       而这两种形态的区别恰好是最想让人看见的事：**签发与转发在客户端一模一样**，
 *       只有「不下发」才需要本地写密钥。</li>
 *   <li><b>注释写进片段里，而不是只写在页面上。</b>复制出去的是这十几行，
 *       页面上的提醒不会跟着走；而最容易踩的那几条（`bear` 不是 `bearer`、
 *       Lance 的凭据只读一次、COS 只有 Paimon 面认）都藏在复制之后才发作。</li>
 * </ol>
 *
 * <p>行是结构化的（`{kind, key, value, text}`）而不是裸字符串：页面要按
 * `key=value` 上色，而这个拆分只该发生一次——在页面里 `split('=')` 会把
 * `s3.access-key` 这种带点的键和注释里的等号一起切坏。
 */

// 相对路径而不是 `@/utils/catalogFormat.js`：`@` 是 vite 的别名，`node --test` 不认它，
// 而这个模块的第一条约束就是「能被 tools/snippet-tests 直接钉住」。utils 之间只此一处
// 互相引用，改成相对路径的代价是零，换来的是这一层不依赖构建器就能跑。
import { ICEBERG, LANCE, PAIMON } from './catalogFormat.js'

/**
 * 每个协议面有哪几个页签。
 *
 * <p>**按格式给，不把五个页签全列出来**：一个 Paimon catalog 的页面上摆着
 * 「Spark · Lance」的片段，读者第一反应是「那我是不是也能这么连」，而答案是
 * 不能——换面就是换 catalog。Lance 只有 Spark 一个页签是事实：connector
 * （`org.lance.spark`）没有 Flink 版，摆一个出来只会让人去找它。
 */
export const SNIPPET_FACES = {
  [PAIMON]: [
    { id: 'spark-paimon', label: 'Spark · Paimon' },
    { id: 'flink-paimon', label: 'Flink · Paimon' },
  ],
  [ICEBERG]: [
    { id: 'spark-iceberg', label: 'Spark · Iceberg' },
    { id: 'flink-iceberg', label: 'Flink · Iceberg' },
  ],
  [LANCE]: [
    { id: 'spark-lance', label: 'Spark · Lance' },
  ],
}

/** 各面的续期能力。`vendored=false` 时引擎自管凭据，这张表不适用。 */
export const RENEWAL = {
  [PAIMON]: {
    label: '能',
    hint: '每次读表都会重打 /v1/{prefix}/.../token，RESTTokenFileIO 在到期前重取。',
  },
  [ICEBERG]: {
    label: '要看客户端',
    hint: 'Iceberg 客户端在到期前 5 分钟预取，但要有 client.refresh-credentials-endpoint'
      + '（服务端下发 config 时才给）；没有它的话 delegation 只生效一次。',
  },
  [LANCE]: {
    label: '不能',
    hint: 'connector 0.8 只在 describe 时读一次 storage_options，作业时长必须小于有效期。',
  },
}

/** 每种存储的 URI 前缀，用来在没填位置时给一个不会误导的占位。 */
const LOCATION_PLACEHOLDER = {
  S3: 's3://<bucket>/',
  OSS: 'oss://<bucket>/',
  OBS: 'obs://<bucket>/',
  AZURE: 'abfss://<container>@<account>.dfs.core.windows.net/',
  GCS: 'gs://<bucket>/',
  COS: 'cosn://<bucket>-<appid>/',
  FILE: 'file:///data/<warehouse>/',
}

const comment = (text) => ({ kind: 'comment', text: `# ${text}` })

const pair = (key, value) => ({ kind: 'pair', key, value, text: `${key}=${value}` })

const plain = (text) => ({ kind: 'plain', text })

/** 治理扩展的类名。片段里要出现两次（Lance 那一行、说明文字），只写一处。 */
const MGMT_EXTENSION = 'io.github.melin.lakehouse.rest.spark.ManagementSparkExtensions'

/** Lance 面要的两个扩展，逗号拼在同一个 `spark.sql.extensions` 里（这个键不能写两行）。 */
const LANCE_EXTENSIONS = ['org.lance.spark.extensions.LanceSparkSessionExtensions', MGMT_EXTENSION].join(',')

/**
 * 收尾：补一行仓库位置说明，再把行拼成可复制的文本。
 *
 * <p>位置写成**注释**而不是某个配置键：REST 客户端的 `warehouse` 是 prefix（就是 catalog 名），
 * 物理路径由服务端解析。把 `s3://…` 塞进 `warehouse` 会让读者以为改这里就能换仓库——
 * 那要重建一个 catalog 才做得到。写进注释则是回答另一个问题：「我连的是哪个仓库」。
 */
function finish(lines, note, warehouse) {
  lines.push(comment(`仓库位置 ${warehouse}（服务端解析；客户端只写 prefix）`))
  return { lines, note, copyText: lines.map((line) => line.text).join('\n') }
}

/**
 * Flink 的 DDL 形态：`'key' = 'value',`，最后一行不带逗号。
 *
 * <p>单独一个函数是因为「最后一行不加逗号」在拼接时最容易错，而错了的表现是
 * 一段语法错误、报的列号还常指向语句开头，看不出是哪一行的问题。
 */
function flinkBlock(header, entries, footer) {
  const rows = entries.map(({ key, value }, index) => ({
    // kind 单独给 'flink' 而不是 'pair'：这一行带着缩进与行尾逗号（`  'k' = 'v',`），
    // 按 key/value 重新拼会把这个形状丢掉，而 Flink 的 DDL 少一个逗号就不成立
    kind: 'flink',
    key,
    value,
    text: `  '${key}' = '${value}'${index === entries.length - 1 ? '' : ','}`,
  }))
  return [plain(header), ...rows, plain(footer)]
}

/** 「不下发」时手动补的那几行。键名族与 S3 兼容存储一致。 */
function manualSecrets({ spark, name }) {
  const prefix = spark ? `spark.sql.catalog.${name}.` : ''
  return [
    { key: `${prefix}s3.endpoint`, value: 'http://minio:9000' },
    { key: `${prefix}s3.access-key`, value: '<AK>' },
    { key: `${prefix}s3.secret-key`, value: '<SK>' },
    { key: `${prefix}s3.path-style-access`, value: 'true' },
  ]
}

/**
 * 治理四行：血缘采集 + 行过滤 / 列脱敏。**三个 Spark 面都默认给**（与凭据档位无关）。
 *
 * <p>为什么是四行而不是一句「见文档」：这两件能力默认不开，而它们是否生效**只由客户端
 * 决定**——服务端开着也没用，作业不带上这四行就什么都采不到，症状是「血缘图一直空、
 * 脱敏看着像没生效」，而治理页面上没有任何一处会说明原因。
 *
 * <p>为什么键上**没有** catalog 前缀：它们是 session 级的，描述的是「这批作业往哪报、
 * 按不按策略改写」，与连哪个 catalog 无关。挂上前缀的症状是**不报错也不生效**——
 * Spark 把它当成一个没人读的 catalog 属性收下，配置看着完全正当。
 *
 * <p>`management.url` 沿用片段自己的服务端地址：两处填成不同机器时 catalog 能连、
 * 血缘却全丢，而报错只是一条 404。
 *
 * <p><b>这里不自己补 `spark.sql.extensions`</b>（除 Lance 面，它本来就有一行）：这个键是
 * **逗号分隔的整串**，多写一行等于覆盖用户已有的扩展，症状是「Paimon 的过程调用突然都不见了」。
 * 因此只作为注释提醒，由人合进自己那一串里。
 *
 * <p>只给 Spark 面：`spark.lakehouse.rest.*` 由 `ManagementSparkExtensions` 读，
 * Flink 那一侧没有对应实现，写进 `CREATE CATALOG` 里只会是一条没人认的选项。
 *
 * @param {string} serverUrl      与服务端同一个基址（管理 API 前缀由这里补）
 * @param {boolean} [withExtensionsLine] 片段里已经有 `spark.sql.extensions` 那一行（Lance 面）
 */
function governance(serverUrl, withExtensionsLine = false) {
  const managementUrl = `${String(serverUrl).replace(/\/+$/, '')}/api/management/v1`
  return [
    comment('治理（血缘 / 行过滤脱敏）：session 级配置，不挂在 catalog 前缀下；'),
    ...(withExtensionsLine
      ? []
      : [comment('要 spark.sql.extensions 里有 ManagementSparkExtensions 才生效（已有扩展用逗号拼，别覆盖）')]),
    pair('spark.lakehouse.rest.lineage.enabled', 'true'),
    pair('spark.lakehouse.rest.policy.enforced', 'true'),
    pair('spark.lakehouse.rest.management.url', managementUrl),
    pair('spark.lakehouse.rest.token', '<主体令牌>'),
  ]
}

/**
 * COS 在三个面上的三种写法。
 *
 * <p>这不是风格差异，是架构差异（设计文档 §6.3.2）：Paimon 走 COSN 的
 * `fs.cosn.*`、Iceberg 没有 COS FileIO 只能 HadoopFileIO、Lance 是 Rust
 * 根本没有 COSN。三面写成一样，等于让两个面静默失败。
 */
function cosNotes(face) {
  if (face === 'spark-lance') {
    return [
      comment('Lance 是 Rust（object_store），没有 COSN：cosn:// 会被当成不认识的 scheme'),
      comment('要用 Lance 读 COS 只能走 S3 兼容端点，且建议单独建一个 storageType=S3 的 catalog'),
    ]
  }
  if (face === 'spark-iceberg' || face === 'flink-iceberg') {
    return [
      comment('Iceberg 没有 COS FileIO：要显式指定 io-impl=org.apache.iceberg.hadoop.HadoopFileIO'),
      comment('HadoopFileIO 不吃下发的 storage-credentials，fs.cosn.* 必须自己配'),
    ]
  }
  return [comment('COSN 只认 fs.cosn.* 这一族（键名大小写敏感）；类路径需要 paimon-cosn')]
}

/**
 * 生成一个面的片段。
 *
 * @param {object} options
 * @param {string} options.format        `PAIMON` / `ICEBERG` / `LANCE`
 * @param {string} options.face          来自 {@link SNIPPET_FACES} 的 id
 * @param {string} [options.catalog]     catalog 名（客户端里的 `warehouse`）
 * @param {string} [options.location]    首个允许位置（决定片段里的路径形态）
 * @param {string} [options.storageType] `S3` / `OSS` / …，只用来选占位与特别说明
 * @param {boolean} [options.vendored]   服务端会不会下发凭据（`NONE` 档为 false）
 * @param {string} [options.serverUrl]   服务端地址；默认给一个显眼的占位
 * @returns {{lines: Array<{kind: string, text: string, key?: string, value?: string}>, note: string, copyText: string}}
 */
export function buildEngineSnippet({
  format,
  face,
  catalog = '',
  location = '',
  storageType = 'S3',
  vendored = true,
  serverUrl = 'http://<catalog-host>:8080',
}) {
  const name = catalog && catalog.trim() ? catalog.trim() : 'lakehouse'
  const warehouse = location && location.trim() ? location.trim() : (LOCATION_PLACEHOLDER[storageType] || '')
  const lines = []
  const cos = () => (storageType === 'COS' ? cosNotes(face) : [])

  if (face === 'spark-paimon') {
    // 首行也是 `key=value`（键是 `spark.sql.catalog.<name>`、值是实现类），因此按 pair 给：
    // 标成 plain 的话，页面上这一行不会上色，看起来像「这行不用改」，而它恰恰是必配项
    lines.push(pair(`spark.sql.catalog.${name}`, 'org.apache.paimon.spark.SparkCatalog'))
    const entries = [
      { key: 'type', value: 'rest' },
      { key: 'uri', value: serverUrl },
      { key: 'warehouse', value: name },
      { key: 'token.provider', value: 'bear' },
      { key: 'token', value: '<主体令牌>' },
      { key: 'data-token.enabled', value: vendored ? 'true' : 'false' },
    ]
    lines.push(...entries.map(({ key, value }) => pair(`spark.sql.catalog.${name}.${key}`, value)))
    if (vendored) {
      lines.push(comment('到此为止：存储凭据由 /token 按表下发，配置里一行密钥都没有'))
    } else {
      lines.push(comment('服务端不下发凭据：关了 data-token，密钥自己写'))
      lines.push(...manualSecrets({ spark: true, name }).map(({ key, value }) => pair(key, value)))
    }
    lines.push(...governance(serverUrl))
    lines.push(...cos())
    return finish(lines, vendored
      ? 'token.provider 的取值是 bear，不是 bearer；写错不报参数错误，只是认证不上。'
        + ' 这一面上「转发」与「签发」长得完全一样——差别只在服务端发的是哪把钥匙。'
      : '关掉 data-token 之后凭据完全由本地配置决定，这是「不下发」唯一诚实的写法。',
    warehouse)
  }

  if (face === 'flink-paimon') {
    const entries = [
      { key: 'type', value: 'paimon' },
      // metastore=rest 是必填：少了它 Paimon 会去读本地 warehouse，报错却像连不上
      { key: 'metastore', value: 'rest' },
      { key: 'uri', value: serverUrl },
      { key: 'warehouse', value: name },
      { key: 'token.provider', value: 'bear' },
      { key: 'token', value: '<主体令牌>' },
    ]
    if (vendored) {
      entries.push({ key: 'data-token.enabled', value: 'true' })
    } else {
      entries.push({ key: 'data-token.enabled', value: 'false' })
      entries.push(...manualSecrets({ spark: false, name }))
    }
    lines.push(...flinkBlock(`CREATE CATALOG ${name} WITH (`, entries, ');'))
    if (vendored) {
      lines.push({ kind: 'plain', text: '-- 存储凭据按表下发（data-token.enabled 默认就是 true）' })
    }
    lines.push(...cos())
    return finish(lines, vendored
      ? 'Flink 的 Paimon 面与 Spark 用同一套 data-token 机制（同一个 RESTTokenFileIO），长作业能续；'
        + ' 但「凭据那条路」在本仓库没有跑过 e2e（Flink 用例全落在 file://）。'
      : '服务端不下发时，存储键名要自己写在这一句里——写错了 Flink 只报「读不到路径」。',
    warehouse)
  }

  if (face === 'spark-iceberg') {
    lines.push(pair(`spark.sql.catalog.${name}`, 'org.apache.iceberg.spark.SparkCatalog'))
    lines.push(pair(`spark.sql.catalog.${name}.type`, 'rest'))
    // 基址写到端口为止：客户端自己拼 `v1/config`、`v1/{prefix}/namespaces`。
    // 写成 `/v1` 会得到 `/v1/v1/config`，报的是 404——看不出是自己多写了一段
    lines.push(pair(`spark.sql.catalog.${name}.uri`, serverUrl))
    // 填 catalog 名（prefix），不是文件路径：客户端先 `GET /v1/config?warehouse=<prefix>`
    // 换回真正的仓库位置，物理路径服务端知道就够了
    lines.push(pair(`spark.sql.catalog.${name}.warehouse`, name))
    // 认证三行：属性全名是 `rest.auth.type`，不是 `auth.type`（后者会被当成一个
    // 没人看的 catalog 属性原样发给服务端，功能上看着能用，日志里一直有推断警告）
    lines.push(pair(`spark.sql.catalog.${name}.rest.auth.type`, 'oauth2'))
    lines.push(pair(`spark.sql.catalog.${name}.token`, '<主体令牌>'))
    if (vendored) {
      lines.push(pair(`spark.sql.catalog.${name}.header.X-Iceberg-Access-Delegation`, 'vended-credentials'))
    } else {
      lines.push(...manualSecrets({ spark: true, name }).map(({ key, value }) => pair(key, value)))
    }
    // 关元数据缓存：凭据换了它也可能继续拿旧表对象，表现是「改了配置没生效」
    lines.push(pair(`spark.sql.catalog.${name}.cache-enabled`, 'false'))
    lines.push(...governance(serverUrl))
    lines.push(...cos())
    return finish(lines, (vendored
      ? 'delegation 头靠 header. 前缀注入，不是内置开关；它只在服务端下发了'
        + ' client.refresh-credentials-endpoint 时才会被升级成「可刷新」。'
      : '没有 delegation 头，服务端就不会在 loadTable 里带凭据，必须自己配 s3.*。')
      + ' rest.auth.type 与 token 这两行只在本部署开了 lakehouse.rest.auth.enabled 时才需要；'
      + ' 令牌是主体令牌，不是存储密钥。',
    warehouse)
  }

  if (face === 'flink-iceberg') {
    const entries = [
      { key: 'type', value: 'iceberg' },
      { key: 'catalog-type', value: 'rest' },
      { key: 'uri', value: serverUrl },
      { key: 'warehouse', value: name },
      { key: 'rest.auth.type', value: 'oauth2' },
      { key: 'token', value: '<主体令牌>' },
    ]
    if (vendored) {
      entries.push({ key: 'header.X-Iceberg-Access-Delegation', value: 'vended-credentials' })
    }
    // 关缓存：Flink 的 Iceberg catalog 默认缓存表对象，凭据换了它也看不见
    entries.push({ key: 'cache-enabled', value: 'false' })
    if (!vendored) {
      entries.push(...manualSecrets({ spark: false, name }))
    }
    lines.push(...flinkBlock(`CREATE CATALOG ${name} WITH (`, entries, ');'))
    lines.push(...cos())
    return finish(lines, vendored
      ? '与 Spark · Iceberg 同一条 REST 路径，delegation 头同样靠 header.* 注入；'
        + ' 这条凭据路径在本仓库没有跑过 e2e。'
      : `服务端不下发：按 ${storageType} 对应 FileIO 的键名自行配置。`,
    warehouse)
  }

  if (face === 'spark-lance') {
    lines.push(pair(`spark.sql.catalog.${name}`, 'org.lance.spark.LanceNamespaceSparkCatalog'))
    lines.push(pair(`spark.sql.catalog.${name}.impl`, 'rest'))
    // 基址写到 /lance 为止：这一面没有 /v1/{prefix} 的握手，多写一段就对不上
    lines.push(pair(`spark.sql.catalog.${name}.uri`, `${serverUrl}/lance`))
    lines.push(pair(`spark.sql.catalog.${name}.delimiter`, '$'))
    lines.push(pair(`spark.sql.catalog.${name}.parent`, name))
    lines.push(pair(`spark.sql.catalog.${name}.single_level_ns`, 'false'))
    lines.push(pair(`spark.sql.catalog.${name}.header.Authorization`, 'Bearer <主体令牌>'))
    // `spark.sql.extensions` 是 **session 级**配置，不是 catalog 属性，因此没有前缀。
    // Lance 的 SQL 能力（含本页那些优化语句）来自这个扩展；缺了它，catalog 能连上，
    // 但每次 DDL 都报「不认识这个语法」，而错误信息不会提扩展。
    // 这里**一次给全**（Lance 的 + 治理的）：这个键是逗号分隔的整串，分两行写等于后一行
    // 覆盖前一行，而症状是「Lance 语句又能用了 / 血缘又空了」这种此消彼长
    lines.push(pair('spark.sql.extensions', LANCE_EXTENSIONS))
    if (vendored) {
      lines.push(comment('存储凭据来自 describe 的 storage_options：aws_access_key_id / aws_secret_access_key /'))
      lines.push(comment('  aws_session_token / expires_at_millis（是 aws_* 键，不是 s3.*）；connector 0.8 只读一次'))
      lines.push(comment('作业时长必须小于有效期，否则中途开始 403'))
    } else {
      lines.push(...manualSecrets({ spark: true, name }).map(({ key, value }) => pair(key, value)))
    }
    lines.push(...governance(serverUrl, true))
    lines.push(...cos())
    return finish(lines, 'uri 要写到 /lance 为止（这一面没有 /v1/config 握手，也不带 prefix）；delimiter 必须与服务端一致，'
      + '否则每个请求都会被解析成别的东西。spark.sql.extensions 那一行已经把 Lance 与治理两个扩展都列上了'
      + '（这个键是逗号分隔的整串，再加自己的扩展要往里拼，别另起一行）；'
      + '另外 lance-spark-bundle（3.5 / 4.1 对应包）必须已经在 Spark 的 classpath 上。'
      + (vendored ? ' 它今天拿到的仍是「一次性」凭据。' : ''), warehouse)
  }

  throw new Error(`unknown face: ${face}`)
}

/**
 * 这个格式有哪几个页签；认不出的格式返回空数组。
 *
 * <p>不退回「Paimon 的页签」：认不出的格式多半是服务端新加的，给它 Paimon 的
 * 片段会让读者按错的键名去连——空数组至少会说「这一面还没有片段」。
 *
 * <p>因此这里**不能用 `catalogFormatOf()` 归一化**：它把认不出的值一律按 Paimon 回落
 * （那是徽标与筛选要的口径，缺列时不能显示成空），用在这里会把「新格式」伪装成
 * 「Paimon」，于是上面那条判断永远不会成立。判据分成两件事——**缺列**（空串）按
 * Paimon 回落，**认得出字面值但没有这几个面**则如实返回空。
 */
export function snippetFaces(format) {
  const raw = typeof format === 'string' ? format.trim().toUpperCase() : ''
  if (!raw) {
    return SNIPPET_FACES[PAIMON] || []
  }
  return SNIPPET_FACES[raw] || []
}

/** 默认选中的页签：每个格式的第一个。 */
export function defaultSnippetFace(format) {
  return snippetFaces(format)[0]?.id || ''
}
