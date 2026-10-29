/**
 * 「凭据从哪来」的**展示层词表**：把服务端算出来的 `mode` 翻成标签、颜色与一句说明。
 *
 * <p><b>判定只由服务端做。</b>判据要读 `lakehouse.rest.storage.*`（具名存储、默认凭据）
 * 与 `lakehouse.rest.credential.providers`（有没有启用签发），这两份东西前端拿不到；
 * 而把优先级抄进 JS 必然与凭据管理器漂移，漂移的表现是「界面说在签发、实际在转发」——
 * 只在出事那天才被发现。因此这里只做**翻译**，不做判定
 * （与 `executionCluster.js` 同一条约定）。
 *
 * <p>三档里后两档（转发 / 不下发）在客户端配置上完全一样，界面上这一处标记
 * 是唯一能区分它们的地方，这也是它必须在**列表页**也出现、而不只藏在详情里的原因
 * （`docs/credential-vending-design.md` §10.1）。
 */

/** 四档：三档来源 + 一种「配置本身有问题」。`tag` 走 Element Plus 的语义色。 */
export const CREDENTIAL_SOURCES = {
  STS: {
    label: '短期签发',
    tag: 'success',
    renewable: true,
    hint: '服务端用 catalog 的 roleArn 现签一份按表收窄的临时凭据，到期失效、客户端可以续期。'
      + '泄漏的影响面是「这一张表 + 这一段时间」。',
  },
  RELAY: {
    label: '转发长期密钥',
    tag: 'warning',
    renewable: false,
    hint: '服务端把存着的长期密钥原样发给引擎。客户端配置不用改，但密钥不会过期——'
      + '泄漏只能靠轮换处理。三档里风险最高的一档，而它与「短期签发」在客户端看起来完全一样。',
  },
  NONE: {
    label: '不下发',
    tag: 'info',
    renewable: false,
    hint: '服务端只发定位信息（endpoint / region / path-style），密钥由引擎自己的身份提供'
      + '（实例角色、环境变量、服务账号）。',
  },
  INVALID: {
    label: '配置有误',
    tag: 'danger',
    renewable: false,
    hint: '这份配置在取票时会失败（引用不存在的具名存储、或用了这条存储不支持的字段）。'
      + '详情见右侧说明。',
  },
}

/** 认不出的 `mode`：原样显示并标灰，不猜它是哪一档。 */
export function credentialSourceInfo(mode) {
  if (!mode) {
    return { label: '—', tag: 'info', renewable: false, hint: '还没有取到判定结果。' }
  }
  return CREDENTIAL_SOURCES[mode] || {
    label: mode,
    tag: 'info',
    renewable: false,
    hint: '服务端返回了这个控制台不认识的取值（可能是服务端更新、控制台未跟上）。'
      + '判定仍以服务端为准，这里不做猜测。',
  }
}

/**
 * 列表/详情共用的一格：`{ mode, label, tag, detail, hint, known }`。
 *
 * @param {{catalog: string, mode: string, detail: string}} [entry] 服务端返回的那一条；
 *   没取到时传 `undefined`——表格里显示「—」，而不是「不下发」：
 *   把「没问到」显示成「不下发」是在替服务端下一个它没下的结论
 */
export function credentialSourceCell(entry) {
  if (!entry) {
    return {
      mode: '',
      label: '—',
      tag: 'info',
      detail: '未取到判定',
      hint: '控制台还没拿到这个 catalog 的凭据来源判定（接口失败或该 catalog 不可见）。',
      known: false,
    }
  }
  const info = credentialSourceInfo(entry.mode)
  return {
    mode: entry.mode,
    label: info.label,
    tag: info.tag,
    detail: entry.detail || '',
    hint: entry.detail ? `${info.hint}\n\n本 catalog：${entry.detail}` : info.hint,
    known: Boolean(CREDENTIAL_SOURCES[entry.mode]),
  }
}

/** 服务端回的那份列表 → `{ catalogName: entry }`，表格按行查。 */
export function indexCredentialSources(payload) {
  const map = {}
  for (const entry of payload?.sources || []) {
    if (entry?.catalog) {
      map[entry.catalog] = entry
    }
  }
  return map
}
