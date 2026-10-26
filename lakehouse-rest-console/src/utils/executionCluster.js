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
