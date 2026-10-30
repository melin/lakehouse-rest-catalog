/**
 * 存储类型的显示名。
 *
 * <p>取值（`S3` / `OSS` / `OBS` / `AZURE` / `GCS` / `COS` / `FILE`）是服务端的枚举，
 * 界面上直接显示 `AZURE` 时没人知道那是 ADLS，`FILE` 也没说清「本地文件」是文件系统
 * 而不是某个云端盘。这张表只做翻译，**不改值**——提交体里永远是枚举值。
 *
 * <p>认不出的值原样返回，不折叠成「未知」：服务端新加一种存储时，`NOPE` 可以直接拿去
 * 搜，而「未知」只能让人猜是哪一个。
 */
export const STORAGE_TYPE_LABELS = {
  S3: 'AWS S3',
  OSS: '阿里云 OSS',
  OBS: '华为云 OBS',
  AZURE: 'Azure ADLS',
  GCS: 'GCS',
  COS: '腾讯云 COS',
  FILE: '本地文件',
}

/** 把存储类型枚举翻成界面上的名字；空值给空串，认不出的给原值。 */
export function storageTypeLabel(type) {
  if (!type) {
    return ''
  }
  return STORAGE_TYPE_LABELS[type] || type
}

/**
 * 按显示顺序排列存储类型。
 *
 * <p>顺序取 `STORAGE_TYPE_LABELS` 的键序（即原型里的排法：先三家云的 S3 / OSS / OBS，
 * 再 Azure / GCS / 腾讯 / 本地），**不用 meta 返回的顺序**——那是枚举的定义顺序
 * （`S3, GCS, AZURE, OBS, OSS, COS, FILE`），与「用户实际会在哪几家里挑」无关，
 * 照着排会让 GCS 夹在 S3 与 Azure 中间，读起来像随手摆的。
 *
 * <p>不认识的类型排在最后而不是丢掉：服务端新加一种存储时，它至少还能被选到
 * （排在哪不重要，「能选」才重要）。同类之间保持原顺序——`sort` 在 ES2019 起是稳定的。
 */
export function sortStorageTypes(types) {
  const order = Object.keys(STORAGE_TYPE_LABELS)
  const rank = (type) => {
    const index = order.indexOf(type)
    return index === -1 ? order.length : index
  }
  return [...types].sort((left, right) => rank(left) - rank(right))
}

/**
 * 这个类型在本部署里用不了。
 *
 * <p>`supportedTypes` 为空时**一律返回 false**（即「不灰任何一项」）：那是
 * 「meta 还没读到」，不是「一种都不支持」。全灰会让表单在这一刻完全没法填，
 * 而它的默认值（`supportedTypes[0]` 或 `FILE`）恰恰是能用的——把可用项显示成
 * 不可用比放开一项更糟。
 *
 * <p>抽成纯函数是为了能被用例钉住：这个分支在界面上的表现依赖部署配置
 * （本仓库默认的 h2 实例支持全部七种，永远看不到灰态），只靠截图验不到。
 */
export function isUnsupportedStorageType(type, supportedTypes) {
  if (!supportedTypes || supportedTypes.length === 0) {
    return false
  }
  return !supportedTypes.includes(type)
}
