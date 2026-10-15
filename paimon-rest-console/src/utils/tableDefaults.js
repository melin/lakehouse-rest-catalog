/**
 * catalog 级的表属性默认值：{@code table-default.<Paimon table property name>}。
 *
 * <p>语义与服务端的 `TableDefaults` 一一对应（Paimon 自己也这么叫：
 * `Catalog.TABLE_DEFAULT_OPTION_PREFIX`）：catalog 属性里带这个前缀的项，
 * 是「此后新建的 Paimon 表默认带上的表属性」，前缀在写进表属性时去掉。
 *
 * <p>控制台有两处要用它，规则必须一致，所以收在这里：
 *
 * <ul>
 *   <li>catalog 详情页的「配置」页签——编辑这份默认值（读写带前缀的形态）；</li>
 *   <li>「新建表」对话框——把它们预填进表属性（写不带前缀的形态，与服务端合并后的结果一致）。</li>
 * </ul>
 *
 * <p>两个函数刻意做成纯函数、输入输出都是普通对象：默认值来自服务端返回的
 * `properties`，任何一处缓存了它都会让人看到过期的默认值。
 */

/** 前缀，与服务端 `TableDefaults.PREFIX` 一致。 */
export const TABLE_DEFAULT_PREFIX = 'table-default.'

/** 表格式由建表请求决定，不接受默认值；服务端同样忽略它。 */
const TYPE_OPTION = 'type'

export function isTableDefaultKey(key) {
  return typeof key === 'string' && key.startsWith(TABLE_DEFAULT_PREFIX)
}

/**
 * 取出 catalog 属性里的默认表属性，键**去掉前缀**。
 *
 * <p>两种写法在这里被挡掉，与服务端同款：`table-default.` 后面为空（没有属性名），
 * 以及 `table-default.type`（表格式不接受默认）。
 *
 * @param {object} properties catalog 的 `properties`
 * @returns {object} `{ 属性名: 取值 }`，按原顺序；没有默认值时为空对象
 */
export function tableDefaultsOf(properties) {
  const result = {}
  for (const [key, value] of Object.entries(properties || {})) {
    if (!isTableDefaultKey(key)) continue
    const name = key.slice(TABLE_DEFAULT_PREFIX.length).trim()
    if (!name || name === TYPE_OPTION) continue
    result[name] = value == null ? '' : String(value)
  }
  return result
}

/** catalog 属性里非默认参数的那些键，供「其它属性」只读展示与保存时回填。 */
export function otherPropertiesOf(properties) {
  const result = {}
  for (const [key, value] of Object.entries(properties || {})) {
    if (!isTableDefaultKey(key)) result[key] = value
  }
  return result
}

/**
 * 把一份默认表属性合并进 catalog 属性，返回新的 properties（不修改入参）。
 *
 * <p>管理 API 的更新是**全量替换**，所以这里把原有的普通属性原样带上；
 * 原有的 `table-default.*` 先全部丢弃，再按传入的这份写进去——
 * 这样「界面上删掉一项」才会真的删掉，而不是留在请求体里。
 *
 * @param {object} properties 服务端当前的 `properties`
 * @param {object} defaults   `{ 属性名: 取值 }`，不带前缀
 */
export function composeProperties(properties, defaults) {
  const result = { ...otherPropertiesOf(properties) }
  for (const [name, value] of Object.entries(defaults || {})) {
    const key = name.trim()
    if (!key) continue
    result[TABLE_DEFAULT_PREFIX + key] = value == null ? '' : String(value)
  }
  return result
}
