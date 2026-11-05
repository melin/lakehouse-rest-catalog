/**
 * 执行集群的**展示层词表**：把服务端算出来的状态码翻成标签、颜色与一句话说明。
 *
 * <p><b>为什么状态只由服务端算。</b>状态的判据是「有没有记录 / 停用 / `configHash` 与
 * `probeConfigHash` 是否相等 / 最近一次探活结果」，四条里有两条在库里。前端手里只有
 * 请求那一刻的响应，一旦自己重算，就会出现「列表说正常、点进去说待验证」——
 * 那种不一致没有任何人能解释。因此这里只做**翻译**（码 → 文案 / 颜色），不做判定。
 *
 * <p><b>三种集群类型与三种认证方式是一一对应的</b>（自建 → kubeconfig、
 * EKS → SigV4 预签名令牌、AKS → AAD 客户端凭据），不存在「EKS + kubeconfig」这种组合。
 * 因此类型既是「用哪种认证」的开关，也是表单的分支条件——不需要第二个下拉。
 */

/** 三种集群类型。`label` 是界面上的短名，`desc` 说明「凭什么能连上」。 */
export const CLUSTER_TYPES = [
  {
    value: 'SELF_HOSTED',
    label: '自建 / 通用集群',
    short: '自建',
    auth: 'kubeconfig',
    desc: '粘贴 kubeconfig，选一个 context。服务端用其中的证书或令牌连过去。',
    path: 'kubeconfig → 选 context',
  },
  {
    value: 'EKS',
    label: 'AWS EKS',
    short: 'EKS',
    auth: 'EKS_TOKEN',
    desc: '给区域、集群名与一对访问密钥。服务端每次请求现签一个 15 分钟的 STS 令牌。',
    path: 'AK/SK → SigV4 预签名 STS URL',
  },
  {
    value: 'AKS',
    label: 'Azure AKS',
    short: 'AKS',
    auth: 'AKS_TOKEN',
    desc: '给租户、应用（客户端）id 与机密。服务端向 AAD 换令牌，再用它连 apiserver。',
    path: 'client secret → AAD 令牌',
  },
]

const CLUSTER_TYPE_BY_VALUE = new Map(CLUSTER_TYPES.map((item) => [item.value, item]))

/** 类型的短名；认不出的取值原样显示（不套用某个已知类型的说明）。 */
export function clusterTypeLabel(value) {
  if (!value) return '—'
  return CLUSTER_TYPE_BY_VALUE.get(value)?.short || value
}

/** 类型的完整描述（抽屉里的三张卡片用）。 */
export function clusterTypeOption(value) {
  return CLUSTER_TYPE_BY_VALUE.get(value) || {
    value,
    label: value,
    short: value,
    auth: value,
    desc: '服务端未声明这种类型的认证方式。',
    path: '—',
  }
}

/**
 * 状态词表。`dispatchable` 与 `ExecutionClusterStatus` 一一对应——
 * **它只用于文案**（比如「优化会运行 / 不会运行」），判定仍然在服务端。
 */
export const CLUSTER_STATUSES = {
  NOT_CONFIGURED: {
    label: '未配置',
    tag: 'info',
    dispatchable: false,
    hint: '优化任务不会运行。配好执行集群之后，到期策略会在下一拍立刻出批。',
  },
  DISABLED: {
    label: '已停用',
    tag: 'info',
    dispatchable: false,
    hint: '配置保留但不提交。重新启用只要一次点击，删除则要把凭据全填一遍。',
  },
  UNVERIFIED: {
    label: '待验证',
    tag: 'warning',
    dispatchable: true,
    hint: '当前这份配置还没有验证过。优化会照常提交，但建议先点一次「验证连接」。',
  },
  HEALTHY: {
    label: '正常',
    tag: 'success',
    dispatchable: true,
    hint: '最近一次验证通过。',
  },
  FAILING: {
    label: '异常',
    tag: 'danger',
    dispatchable: false,
    hint: '最近一次验证失败，已暂停这个 catalog 的派发：排队中的批次保持原状、不会判失败，'
      + '修好之后从原位置继续（错过的调度不补）。',
  },
}

/** 状态的展示信息；认不出的状态按「待验证」处理（保守：不声称「正常」）。 */
export function clusterStatusInfo(status) {
  return CLUSTER_STATUSES[status] || CLUSTER_STATUSES.UNVERIFIED
}

/**
 * 列表那一行要显示的东西：`{ configured, status, label, tag, detail, hint }`。
 *
 * <p>`detail` 是第二行（类型 · namespace），`hint` 是 tooltip 与横幅共用的一句话。
 * 未配置时 `detail` 写成「优化不会运行」——这是那一列存在的意义：
 * 一个 catalog 有没有配执行集群，决定了它的优化策略是不是永远不会跑，
 * 而这件事在此之前只能靠「去点一下按钮才知道」。
 */
export function clusterCell(summary) {
  const status = summary?.status || (summary?.configured ? 'UNVERIFIED' : 'NOT_CONFIGURED')
  const info = clusterStatusInfo(status)
  if (!summary?.configured && status === 'NOT_CONFIGURED') {
    return { configured: false, status, label: info.label, tag: info.tag, detail: '优化不会运行', hint: info.hint }
  }
  const parts = [clusterTypeLabel(summary.clusterType)]
  if (summary.namespace) parts.push(`ns ${summary.namespace}`)
  return {
    configured: true,
    status,
    label: info.label,
    tag: info.tag,
    detail: parts.filter(Boolean).join(' · ') || '—',
    hint: summary.lastProbeMessage || info.hint,
  }
}

/** 探活五步的标题（结果里带 title，这里只是兜底与顺序说明）。 */
export const PROBE_STEP_TITLES = [
  { step: '1', title: '凭据', ask: '这份认证材料还能用吗' },
  { step: '2', title: 'TLS 与身份', ask: '对面真的是那个 apiserver 吗' },
  { step: '3', title: '权限', ask: '能建 Job、能删 Job、能读日志吗' },
  { step: '4', title: '准入预演', ask: 'dry-run 建一个 Job 会不会被 Admission 拦' },
  { step: '5', title: '回调', ask: '服务端能访问到那个回调地址吗（只验一个方向）' },
]

/** 一步探活的展示：`SKIP` 也是合法的，要显示成灰而不是红。 */
export function probeStepTag(status) {
  switch (status) {
    case 'PASS':
      return 'success'
    case 'WARN':
      return 'warning'
    case 'FAIL':
      return 'danger'
    default:
      return 'info'
  }
}

/** 探活原因码的人话（服务端也有 label，这里只在前端展示时兜底）。 */
export const PROBE_REASONS = {
  CREDENTIAL_INVALID: '凭据无效',
  CREDENTIAL_UNAVAILABLE: '凭据不可读',
  FORBIDDEN: '权限不足',
  TLS: 'TLS 校验失败',
  NETWORK: '网络不可达',
  ADMISSION: '准入被拒',
  CALLBACK: '回调不可达',
  UNKNOWN: '未知原因',
}

export function probeReasonLabel(reason) {
  if (!reason) return ''
  return PROBE_REASONS[reason] || reason
}

/** kubeconfig 里一个 context 的结论。`UNSUPPORTED` 与 `UNAVAILABLE` 的处置完全不同。 */
export const CONTEXT_VERDICTS = {
  OK: { label: '可用', tag: 'success' },
  WARN: { label: '可用（有警告）', tag: 'warning' },
  UNSUPPORTED: { label: '不支持', tag: 'danger' },
  UNAVAILABLE: { label: '不可用', tag: 'danger' },
}

export function contextVerdict(verdict) {
  return CONTEXT_VERDICTS[verdict] || { label: verdict || '—', tag: 'info' }
}

/** 能不能被选中（只有 OK / WARN 能，与服务端 `KubeconfigParser.isUsable` 一致）。 */
export function contextSelectable(verdict) {
  return verdict === 'OK' || verdict === 'WARN'
}

// ---------------------------------------------------------------- 两组批次策略

/**
 * 两组批次策略的默认值。**必须与实体字段的默认值逐字一致**
 * （`CatalogExecutionClusterEntity` 里 `columnDefinition = "integer default X"`）。
 *
 * <p>前端给一个「看起来更合理」的数，就会造出「打开抽屉看到 50、保存一提交变成 20」这种
 * 只在第一次保存时才暴露的偏差——而用户看到的现象是「我什么都没改，值自己变了」。
 *
 * <p>扫描那组**没有并发项**：扫描作业与优化批次共用 `maxConcurrentJobs` 一把闸门
 * （两者跑在同一台集群上、争同一份算力）。同一个字段名出现在两个位置，
 * 早晚会有人按「两层配置」去读，所以干脆只保留一个。
 */
export const BATCH_DEFAULTS = {
  maxTablesPerJob: 20,
  maxConcurrentJobs: 2,
  jobTimeoutSeconds: 7200,
  maxScanTablesPerJob: 200,
  scanJobTimeoutSeconds: 3600,
}

/**
 * 取值范围，与服务端 `ExecutionClusterService.apply()` 里的 `range(...)` 一一对应。
 *
 * <p>前端放宽了会在保存时吃 400（错误信息还不一定指到那一格），前端收紧了会让一个
 * **合法**配置根本填不进去——两种都比「范围提示写错一个字」严重。
 */
export const BATCH_RANGES = {
  maxTablesPerJob: [1, 500],
  maxConcurrentJobs: [1, 50],
  jobTimeoutSeconds: [300, 86400],
  maxScanTablesPerJob: [1, 500],
  scanJobTimeoutSeconds: [300, 86400],
}

// ---------------------------------------------------------------- Spark 参数

/**
 * 前缀白名单，与服务端 `SPARK_CONF_ALLOWED_PREFIXES` 一致。
 *
 * <p>前端这份**不承载安全语义**——真正把关的是服务端（它会把白名单外的键丢弃并记审计）。
 * 这里只用来说清「什么能填」并在保存前给一次提示，免得用户以为
 * `spark.sql.catalog.xxx.uri` 生效了，实际上从来没到过 Spark 命令行。
 */
export const SPARK_CONF_ALLOWED_PREFIXES = [
  'spark.driver.',
  'spark.executor.',
  'spark.sql.shuffle.',
  'spark.sql.files.',
  'spark.memory.',
]

/** 唯一的精确键（前缀匹配覆盖不到它）。 */
export const SPARK_CONF_ALLOWED_KEYS = ['spark.default.parallelism']

/** 单行值长度上限，与服务端 `MAX_SPARK_CONF_VALUE` 一致。 */
export const SPARK_CONF_MAX_VALUE = 1024

/** 一个键是否落在白名单内（前缀匹配要求比前缀更长，避免一个裸前缀被当成合法键）。 */
export function sparkConfKeyAllowed(key) {
  if (!key) return false
  if (SPARK_CONF_ALLOWED_KEYS.includes(key)) return true
  return SPARK_CONF_ALLOWED_PREFIXES.some((prefix) => key.startsWith(prefix) && key.length > prefix.length)
}

/**
 * 一段 `k=v` 文本里**会被服务端丢弃**的键（保序去重）。
 *
 * <p>三种行不算：空行、`#` 注释行、格式不合法（`=` 缺失）的——最后一种服务端会直接 400，
 * 由校验错误提示，不在这里重复报一遍。
 */
export function sparkConfRejectedKeys(raw) {
  const rejected = []
  for (const line of String(raw || '').split(/\r?\n/)) {
    const entry = line.trim()
    if (!entry || entry.startsWith('#')) continue
    const eq = entry.indexOf('=')
    if (eq <= 0) continue
    const key = entry.slice(0, eq).trim()
    if (!sparkConfKeyAllowed(key) && !rejected.includes(key)) {
      rejected.push(key)
    }
  }
  return rejected
}

/**
 * 「留空时会生效的那份参数」——它是**镜像级**默认值，不是库里的默认值。
 *
 * <p>所以它只出现在 placeholder 里，**不回填进输入框**：回填会让「留空」这个状态消失，
 * 之后镜像升级默认值时，所有 catalog 都停在旧参数上。这也解释了字段表里
 * 「默认值 = 见下、留空语义 = 空 = 只有镜像级 `--conf`」这两句话并不矛盾。
 */
export const SPARK_CONF_IMAGE_DEFAULTS = {
  optimization: [
    'spark.driver.memory=4g',
    'spark.driver.cores=4',
    'spark.sql.shuffle.partitions=200',
  ],
  scan: [
    'spark.driver.memory=4g',
    'spark.driver.cores=4',
    'spark.sql.shuffle.partitions=200',
    'spark.sql.files.maxPartitionBytes=134217728',
  ],
}

/** placeholder 的文本形态：多行默认值 + 一句「留空即用它」。 */
export function sparkConfPlaceholder(kind) {
  const lines = SPARK_CONF_IMAGE_DEFAULTS[kind] || []
  return `留空 = 用镜像级默认：\n${lines.join('\n')}`
}
