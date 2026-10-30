/**
 * 「凭据从哪来」的**展示层词表**：把服务端算出来的 `mode` 翻成标签、颜色与一句说明。
 *
 * <p><b>判定只由服务端做。</b>判据要读 `lakehouse.rest.credential.providers`
 * （有没有启用签发），前端拿不到；而把优先级抄进 JS 必然与凭据管理器漂移，
 * 漂移的表现是「界面说在签发、实际在转发」——只在出事那天才被发现。
 * 因此这里只做**翻译**，不做判定（与 `executionCluster.js` 同一条约定）。
 *
 * <p><b>五档是一维，不是两维。</b>v2 把「服务端能不能读」与「引擎拿不拿得到」拆成两个
 * 维度，组合出来的四种状态里有两种（「服务端能读、引擎拿不到」「服务端读不了、引擎正常」）
 * 不该被建出来。v3 只留一档标签，那些半可用状态在保存前就会被拦下。
 *
 * <p>后三档（`LOCAL` / `LEGACY_ENV` / `INVALID`）都不是「凭据来源」而是状态，
 * 但它们和 `VENDED` / `STATIC` 共用一个格子：用户的问题是「这个 catalog 的认证
 * 现在是什么状况」，答案本来就可能不是某一种方式。
 */

/** 五档。`tag` 走 Element Plus 的语义色；`renewable` 只管「有没有换票这回事」。 */
export const CREDENTIAL_SOURCES = {
  VENDED: {
    label: '短期签发',
    tag: 'success',
    renewable: true,
    hint: '服务端用 catalog 上的身份现签一份按表收窄的短期凭据，到期失效。'
      + '泄漏的影响面是「这一张表 + 这一段时间」。能不能续期看具体方式：'
      + 'S3 可以续，OSS / Azure / GCS 没有标准续期端点。',
  },
  STATIC: {
    label: '转发长期密钥',
    tag: 'warning',
    renewable: false,
    hint: '服务端把存着的长期密钥原样发给引擎。客户端配置不用改，但密钥不会过期——'
      + '泄漏只能靠轮换处理。它也是四格全通（服务端读 / 服务端写 / 引擎读 / 引擎写）'
      + '的档位，代价是发给引擎的凭据是永久的。',
  },
  LOCAL: {
    label: '本地文件系统',
    tag: 'info',
    renewable: false,
    hint: '本地仓库没有凭据这回事：服务端返回一个自包含令牌（内容是表 id 与过期时刻），'
      + '文件系统本身不校验它。它不是「缺配置」，不需要补任何东西。',
  },
  LEGACY_ENV: {
    label: '无可用凭据',
    tag: 'danger',
    renewable: false,
    hint: 'catalog 上没有服务端可用的秘密：服务端读这个位置用的是进程自己的身份'
      + '（预览、浏览、下载大概率 500），引擎用它自己的。'
      + '这是 v3 之前的存量形态，不会被自动迁移——编辑时选一种认证方式并跑一次自检即可。',
  },
  INVALID: {
    label: '配置有误',
    tag: 'danger',
    renewable: false,
    hint: '这份配置取票时会失败或静默退回别的档：常见的是填了 roleArn 却没给签名密钥、'
      + '启用了签发但 catalog 上没配身份。详情见右侧说明。',
  },
}

/**
 * 认不出的 `mode`：原样显示并标灰，不猜它是哪一档。
 *
 * <p>服务端与控制台版本不同步时会出现这种情况（控制台是构建产物，可能落后一个版本）。
 * 猜一个档位比显示原值更糟：用户会照着错的标签去排查。
 */
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
 *   没取到时传 `undefined`——表格里显示「—」，而不是「无可用凭据」：
 *   把「没问到」显示成「没有凭据」是在替服务端下一个它没下的结论
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
    renewable: info.renewable,
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
