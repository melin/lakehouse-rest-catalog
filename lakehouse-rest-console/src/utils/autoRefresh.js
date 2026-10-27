/**
 * 定时刷新的偏好与「这一拍要不要发请求」的判定。
 *
 * <p>只有纯函数与常量，不 import Vue、不读 window。这么做的原因不是洁癖：
 * 节拍判定是最容易写错的一小块，而它错起来**一句报错都没有**——
 * 后台标签页每 5 秒打一次接口（用户看不见）、或整页停在旧数据上却像是实时的
 * （更糟：决策依据是过期的）。因此判定单独成函数，`tools/auto-refresh-tests`
 * 能在没有浏览器的情况下把每种组合逐条验掉。
 *
 * <p><b>默认开启、5 秒一次</b>：这两个列表回答的都是「现在什么状态」，
 * 打开就在那里等着变的页面（catalog 的执行集群状态、优化批次的运行状态）
 * 让人反复点刷新，比每 5 秒一次查询贵得多。
 */

/** 默认偏好：开启、5 秒。 */
export const AUTO_REFRESH_DEFAULT = Object.freeze({ enabled: true, seconds: 5 })

/**
 * 可选的节拍（秒）。
 *
 * <p>**不给自由输入**：低于 3 秒只是把同一个查询连打几遍（列表端点是全表扫描 +
 * 分页，不是增量游标），高于 60 秒与手动刷新没有区别。给一组档位还顺带挡住了
 * 「1 秒」这种能把自己服务端打满的取值。
 */
export const AUTO_REFRESH_INTERVALS = Object.freeze([3, 5, 10, 30, 60])

/** 偏好的存储键前缀；每个列表页各存一份，互不影响。 */
export const AUTO_REFRESH_KEY_PREFIX = 'lakehouse.console.autoRefresh.'

/** 某个页面的存储键。 */
export function autoRefreshKey(page) {
  return `${AUTO_REFRESH_KEY_PREFIX}${page}`
}

/**
 * 把任意来源的值收敛成一份合法偏好。
 *
 * <p>来源不止「用户刚点的开关」：还有上一版控制台写下的 localStorage、
 * 手改过的值、以及 JSON 解析失败后的 undefined。合法值以外一律回默认——
 * 宁可回到「开启 5 秒」这个安全值，也不要让一个字符串 `"5s"` 变成
 * `setInterval(fn, NaN)`（等于 0 毫秒，把浏览器打满）。
 */
export function sanitizePreference(raw) {
  const source = raw && typeof raw === 'object' ? raw : {}
  return {
    // 只有明确的 false 才算关：读到一个不认识的字段时保持默认的「开启」，
    // 而不是把「没读到」当成「用户关掉了」
    enabled: source.enabled === false ? false : AUTO_REFRESH_DEFAULT.enabled,
    seconds: AUTO_REFRESH_INTERVALS.includes(Number(source.seconds))
      ? Number(source.seconds)
      : AUTO_REFRESH_DEFAULT.seconds,
  }
}

/** 读偏好；读不到、读坏了都回默认（存储不可用时也不抛）。 */
export function readPreference(storage, key) {
  try {
    return sanitizePreference(JSON.parse(storage?.getItem(key) ?? 'null'))
  } catch {
    return { ...AUTO_REFRESH_DEFAULT }
  }
}

/** 写偏好；写不进去（隐私模式、配额满）就只影响「下次还记得」，不影响本次。 */
export function writePreference(storage, key, preference) {
  try {
    storage?.setItem(key, JSON.stringify(sanitizePreference(preference)))
    return true
  } catch {
    return false
  }
}

/**
 * 这一拍该不该发请求。
 *
 * <p>四条跳过理由，各自对应一种真实存在的错法：
 * <ul>
 *   <li>{@code enabled=false}：关掉开关后仍在跑（定时器没清）——请求照发，用户以为停了；</li>
 *   <li>{@code hidden}：后台标签页照常轮询。页签开着过夜，第二天早上多出几千次查询，
 *       而且这些请求的响应没人看；</li>
 *   <li>{@code inFlight}：上一拍还没回来就发下一拍。慢网络下请求会越堆越多，
 *       最后一起返回、一起覆盖，页面开始闪；</li>
 *   <li>{@code blocked}：页面正处在「刷新会打断人」的状态（例如正在编辑的表单、
 *       正在读的详情抽屉把行数据换掉）。这是页面自己的判断，由调用方给。</li>
 * </ul>
 */
export function shouldTick({ enabled = true, hidden = false, inFlight = false, blocked = false } = {}) {
  return Boolean(enabled) && !hidden && !inFlight && !blocked
}
