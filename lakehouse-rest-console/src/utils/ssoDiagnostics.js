/**
 * SSO 登录失败的**分类**：把服务端 / IdP 抛回来的那句话翻成「为什么 + 下一步做什么」。
 *
 * <p>为什么需要它：OIDC 失败在浏览器里几乎只有一张脸——「登录没成」。而实际原因至少十二种，
 * 且分属三方（IdP 的配置、服务端的配置、这个人的身份本身）。没有分类时，用户只能把
 * 原文截图发给人看，而原文常常是 `invalid_grant` 这种对谁都无意义的字符串。
 *
 * <p><b>只做分类，不改写原文。</b>原文一律保留在 `raw` 里并显示在折叠区：
 * 分类是为了给出下一步，不是为了好看——把原文删掉等于把唯一的证据丢了。
 *
 * <p>判据是**服务端与 IdP 的实际错误串**（见 `OidcLoginService`、`OidcService`、
 * `api/oidc.js`），因此这里是纯函数、可以单测（`tools/sso-tests/`）。新增一种失败时
 * 只要在这里加一条规则；没有命中任何规则就走 `unknown` 并原样展示。
 */

/**
 * 失败分类。`tone` 只有两个取值：`blocked`（这个人进不来，需要改配置或找管理员）
 * 与 `retry`（多半是时序 / 网络问题，再试一次可能就好了）。
 */
export const SSO_DIAGNOSIS = {
  principal_conflict: {
    title: '这个身份不能绑定到已有主体',
    why: '同名的主体已经存在，且不是由 IdP 建立的，或者已绑定到另一个外部身份。',
    action: '找服务管理员确认：是要把该主体显式绑定到这个 IdP 身份（allow-adopt-local），还是给这个人在 IdP 里换一个不与本地主体重名的账号。',
    tone: 'blocked',
  },
  email_domain: {
    title: '邮箱域不在允许范围内',
    why: '服务端配了 allowed-email-domains，而这个账号的邮箱域不在名单里（或令牌里没有 email）。',
    action: '确认账号的邮箱域，或让管理员把该域加入 allowed-email-domains；若 IdP 不下发 email，需要先补 scope。',
    tone: 'blocked',
  },
  not_provisioned: {
    title: '服务端没有为这个身份建立主体',
    why: 'auto-provision 关着，且库里没有同名主体，于是认证成功但没有任何授权。',
    action: '让管理员在「主体」页预建同名主体并授予角色，或打开 auto-provision。',
    tone: 'blocked',
  },
  audience: {
    title: '令牌的受众对不上',
    why: '服务端配的 audience 与签发方写进令牌的 aud 不一致。',
    action: '确认 IdP 把 aud 填成了什么（Cognito 的 access token 根本没有 aud），据此改 audience 或留空。',
    tone: 'blocked',
  },
  issuer: {
    title: '签发方对不上',
    why: '令牌的 iss 与服务端配置的 issuer-uri 不一致，或发现文档自称的 issuer 与配置不同。',
    action: '核对 issuer-uri 是否与 IdP 的 realm / tenant 地址逐字一致（尾斜杠也算）。',
    tone: 'blocked',
  },
  state: {
    title: '这次回调已经失效',
    why: 'state 校验没通过：登录中途换了标签页、回调链接被重复使用，或浏览器禁用了会话存储。',
    action: '回到登录页重新点一次 SSO 登录；不要刷新回调地址或从历史记录里重新打开它。',
    tone: 'retry',
  },
  callback: {
    title: '这个回调地址没有授权码',
    why: '直接从地址栏打开回调页，或从历史记录里重开它——它只在 IdP 跳回来的那一次有效。',
    action: '从登录页重新发起一次 SSO 登录；不要刷新或重开回调地址。',
    tone: 'retry',
  },
  oidc_disabled: {
    title: '服务端没有启用 SSO 登录',
    why: 'oidc.enabled 是 false，或 issuer-uri / client-id 没配全，因此 IdP 把浏览器送回来也换不到令牌。',
    action: '让管理员按「身份接入」页的提示补齐配置并重启；本地登录方式在此期间仍然可用。',
    tone: 'blocked',
  },
  client_auth: {
    title: '服务端在 IdP 那边没通过认证',
    why: 'client-id / client-secret 与 IdP 应用里登记的不一致，或这个 client 没被允许使用授权码流程。',
    action: '核对「身份接入」页的 clientId 与 IdP 应用配置；密钥错了只能改部署配置后重启，控制台改不了。',
    tone: 'blocked',
  },
  redirect: {
    title: '回调地址没有登记',
    why: 'IdP 侧登记的回调地址与这次请求带的 redirect_uri 不完全一致。',
    action: '在 IdP 的应用配置里逐字加上控制台的回调地址（含 http/https、端口与路径）。',
    tone: 'blocked',
  },
  access_denied: {
    title: '身份提供方拒绝了这次授权',
    why: '通常是点了取消，或该账号未被允许使用这个应用。',
    action: '重新登录并确认授权；若是账号策略限制，需要在 IdP 侧放行。',
    tone: 'retry',
  },
  unreachable: {
    title: '联系不上服务端或身份提供方',
    why: '请求没有到达：网络不通、服务端没起来，或发现文档拉取超时。',
    action: '先确认服务端可用（「连接设置」页能读到登录要求），再确认服务端能访问 IdP 的发现地址。',
    tone: 'retry',
  },
  unknown: {
    title: 'SSO 登录未完成',
    why: '没有命中已知的失败分类，下面保留原始错误。',
    action: '把原始错误发给服务管理员；同时看「身份接入」页的连通性测试与 claim 样例。',
    tone: 'retry',
  },
}

/**
 * 规则表。**顺序有意义**：越具体的串越靠前（`email_domain_not_allowed` 同时含 domain 与
 * allowed，放在通用的 domain 规则之前；`invalid_grant` 太泛，放最后）。
 */
const RULES = [
  { code: 'principal_conflict', pattern: /principal_conflict|already exists as a local principal|bound to a different external identity/i },
  { code: 'email_domain', pattern: /email_domain_not_allowed|email domain/i },
  { code: 'not_provisioned', pattern: /not-provisioned|auto-provision|not provisioned/i },
  // 放在 `state` 之前：`invalid_client` 的官方说明是「Client authentication failed」，
  // 不含 state，但把具体的串排在前面总是更安全
  { code: 'client_auth', pattern: /invalid_client|unauthorized_client|client authentication/i },
  { code: 'oidc_disabled', pattern: /oidc\.enabled|not enabled|oidc is disabled/i },
  // `\bstate\b` 而不是裸 `state`：否则 `statement` 之类的词也会被算成 state 失败
  { code: 'state', pattern: /\bstate\b|csrf/i },
  { code: 'redirect', pattern: /redirect_uri|redirect uri/i },
  { code: 'audience', pattern: /\baud\b|audience/i },
  { code: 'issuer', pattern: /issuer|\biss\b/i },
  { code: 'access_denied', pattern: /access_denied|cancel/i },
  { code: 'unreachable', pattern: /failed to fetch|networkerror|timeout|timed out|econnrefused|unreachable/i },
]

/**
 * 把一次 SSO 失败翻译成结论。
 *
 * @param {object} input
 * @param {string} [input.code] 已知的分类（调用方已经判过时直接给，例如回调页自己识别出的
 *   `access_denied`）。给了就跳过规则匹配，但仍保留原文
 * @param {string} [input.detail] 原始错误（异常消息 / IdP 的 error_description）
 * @param {string} [input.message] 补充说明，与 detail 一起参与匹配与展示
 * @returns {{code: string, title: string, why: string, action: string, tone: string, raw: string}}
 */
export function diagnoseSsoError({ code = '', detail = '', message = '' } = {}) {
  const raw = [detail, message].filter((value) => typeof value === 'string' && value.trim()).join(' ')
  let resolved = code && SSO_DIAGNOSIS[code] ? code : ''
  if (!resolved) {
    const haystack = raw || ''
    const hit = RULES.find((rule) => rule.pattern.test(haystack))
    resolved = hit ? hit.code : 'unknown'
  }
  const entry = SSO_DIAGNOSIS[resolved]
  return { code: resolved, raw, ...entry }
}

/** 分类码 → 短标签，给表格 / 徽标用。未识别时回「未分类」。 */
export function ssoErrorLabel(code) {
  return SSO_DIAGNOSIS[code]?.title || '未分类'
}
