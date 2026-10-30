/**
 * 「这个 catalog 用哪种方式认证」——**方式表与可用性判定，不含档位**。
 *
 * <p><b>这里只回答两个问题</b>：这一种存储有哪几条路（{@link STORAGE_TYPE_METHODS}），
 * 以及某条路在<em>本部署</em>能不能选（{@link availability}）。后者只读服务端下发的
 * provider 名单——那是一个字符串数组，前端抄一遍不会漂移。
 *
 * <p><b>它不回答「凭据来源是哪一档」。</b>那个判定要看 catalog 上的字段与部署配置的
 * 组合顺序，唯一正确的实现在服务端（`CatalogCredentialSource`），控制台通过
 * `GET /api/console/v1/catalog-credential-sources` 与表单预览接口取。这里刻意不出现
 * 任何档位取值——出现了就说明有人开始在前端重算一遍，而两份算法迟早不一致。
 *
 * <p><b>为什么把「本工程没做」的方式也列出来。</b>不列出来的话，用户看到 OSS 只有
 * 两条路，会以为这是设计上的选择而不是缺口；列出来并写明缺什么（DTO 缺 `securityToken`
 * 字段、OBS 委托要账号口令），才知道该去推动什么。但它们的
 * `notImplemented` 卡片不可选——这比「先做个半成品」诚实。
 *
 * <p><b>S3 的「临时安全凭证（Session Token）」已删。</b>它不是「还没做」，而是与本案的
 * 取票模型重复：那条路把一组会过期的 AK/SK 长期存在 catalog 上，到期后监控页与引擎
 * 同时失效，而同样的事由「IAM 角色」现签现发做得更好（作用域还能按表收窄）。
 * 留一张永远不可选的卡片，只会让人以为该去补一个 `sessionToken` 字段。
 *
 * <p><b>永远不存在的方式不出现在表里。</b>「环境身份 / Azure 托管标识 / GCS ADC」
 * 三种在 v3 被删掉，理由与它们为什么不能存在写在一起（catalog 必须能预览，
 * 而进程身份不保证这一点）。它们不在任何 `methods` 数组里，因此渲染不出卡片，
 * DOM 里也没有——这比「灰掉」更硬，灰掉的东西还会被找到。
 */

/** 每种方式「引擎拿到什么」与「轮换时谁要动」。两张表合起来才是差异所在。 */
export const AUTH_METHODS = {
  static: {
    title: '静态访问密钥',
    kind: 'A',
    desc: '把一对长期密钥存在 catalog 上。服务端与引擎用的是同一对，谁都不过期。',
    store: '一对长期 AK/SK',
    engine: '永久密钥',
    rotate: '改 catalog，作业不用重启',
    fields: ['accessKeyId', 'secretAccessKey'],
  },
  role: {
    title: 'IAM 角色',
    kind: 'B',
    provider: 's3-token',
    desc: '存一个 roleArn，和一对只允许 AssumeRole 的签名密钥。每次取票现签一份，'
      + '作用域收窄到这次要读的表。',
    store: 'roleArn + 签名 AK/SK',
    engine: '短期票（≤12h）',
    rotate: '改 catalog，作业不用重启',
    fields: ['roleArn', 'accessKeyId', 'secretAccessKey'],
  },
  ramRole: {
    title: 'RAM 角色',
    kind: 'B',
    provider: 'oss-sts',
    desc: '服务端用 catalog 上的 AK/SK 去 AssumeRole，换一份按表收窄的 STS 凭据。',
    store: 'roleArn + 签名 AK/SK',
    engine: '短期票（900–3600 秒）',
    rotate: '改 catalog，作业不用重启',
    fields: ['roleArn', 'accessKeyId', 'secretAccessKey'],
  },
  ossSession: {
    title: '临时 AccessKey',
    kind: 'B',
    notImplemented: true,
    missing: 'DTO 里还没有 securityToken 字段',
    desc: '直接存临时 AccessKey + SecurityToken。与 S3 那一档同样缺字段，本工程没接入。',
    store: 'AccessKey + SecurityToken',
    engine: '临时凭据',
    rotate: '改 catalog',
    fields: [],
  },
  obsDelegate: {
    title: 'IAM 委托（临时 AK/SK）',
    kind: 'B',
    notImplemented: true,
    missing: '要账号 + 口令换 IAM token，密钥存哪、怎么轮换都没定',
    desc: 'CreateTemporaryAccessKeyByAgency 要先拿 IAM token，而拿 token 需要华为云的'
      + '账号与口令——那是新一类密钥（不是 AK/SK），本工程没接入。',
    store: 'IAM 账号 + 口令',
    engine: '临时凭据',
    rotate: '——',
    fields: [],
  },
  principal: {
    title: '服务主体',
    kind: 'B',
    provider: 'azure-sas',
    desc: '存 tenantId + clientId + clientSecret。服务端换 AAD token，再签目录级 SAS。',
    store: 'tenantId + clientId + clientSecret',
    engine: '目录级 SAS（上限 7 天）',
    rotate: '改 catalog，作业不用重启',
    fields: ['tenantId', 'clientId', 'clientSecret'],
  },
  serviceAccount: {
    title: '服务账号 JSON',
    kind: 'B',
    provider: 'gcs-downscope',
    desc: '存服务账号密钥 JSON。服务端自签 JWT 换 access token，再 downscope 到表前缀。',
    store: '服务账号 JSON',
    engine: 'downscoped token（1 小时）',
    rotate: '改 catalog，作业不用重启',
    fields: ['serviceAccountJson'],
  },
  none: {
    title: '本地文件系统',
    kind: 'F',
    desc: '没有凭据概念：服务端直接用本地 FileIO，引擎拿到一个自签令牌。',
    store: '无',
    engine: '自签令牌（表 id + 过期时刻）',
    rotate: '不需要',
    fields: [],
  },
}

/**
 * 每种存储有哪几条路，顺序即展示顺序（可用的在前、没做的在后）。
 *
 * <p>COS 只有一条「静态」：COSN 的认证包里没有令牌提供者，`appId` 也不是下发键，
 * 所以临时密钥这条根本不成立——不列，而不是列出来再置灰。
 * Azure 也只有一条：账户密钥（Shared Key）那条路本工程没做，同样不列。
 */
export const STORAGE_TYPE_METHODS = {
  S3: ['static', 'role'],
  OSS: ['static', 'ramRole', 'ossSession'],
  OBS: ['static', 'obsDelegate'],
  COS: ['static'],
  AZURE: ['principal'],
  GCS: ['serviceAccount'],
  FILE: ['none'],
}

/** 某种存储的方式列表；不认识的类型给空数组（表单那边会整块不渲染）。 */
export function methodsFor(type) {
  return (STORAGE_TYPE_METHODS[type] || []).map((id) => ({ id, ...AUTH_METHODS[id] }))
}

/**
 * 这条方式在本部署能不能选。
 *
 * @param {object} method {@link AUTH_METHODS} 里的一条
 * @param {string[]} providers 服务端下发的 `credentialProviders`
 * @returns {'ok'|'deployDisabled'|'notImplemented'} 后两种都不可选，只是原因不同
 */
export function availability(method, providers) {
  if (!method) return 'notImplemented'
  if (method.notImplemented) return 'notImplemented'
  if (!method.provider) return 'ok'
  return (providers || []).includes(method.provider) ? 'ok' : 'deployDisabled'
}

/**
 * 从一份已保存的存储配置反推它在用哪条方式。
 *
 * <p>反推只在**编辑态**用得上：新建时方式是用户选的。判据是「哪些字段有值」，
 * 顺序必须与 `StorageConfigs.validate` 的取向一致——`roleArn` 优先于静态密钥，
 * 因为配了角色时那对 AK/SK 是签名身份，不是数据面凭据。
 *
 * @param {string} type `storageType`
 * @param {object} payload `storageConfigInfo`
 * @returns {string} 方式 id；判不出来时回到该类型的第一个可选方式
 */
export function inferMethod(type, payload) {
  const list = STORAGE_TYPE_METHODS[type] || []
  if (list.length <= 1) return list[0] || ''
  const config = payload || {}
  if (config.roleArn) {
    // OSS 的 roleArn 走 RAM 角色，S3 的走 IAM 角色——名字不同，判据相同
    return type === 'OSS' ? 'ramRole' : 'role'
  }
  if (config.accessKeyId) return 'static'
  if (config.clientId) return 'principal'
  if (config.serviceAccountJson) return 'serviceAccount'
  return list[0] || ''
}

/** 某种方式需要用户填哪些字段（凭据部分；位置信息由字段表另行渲染）。 */
export function methodFields(methodId) {
  return AUTH_METHODS[methodId]?.fields || []
}
