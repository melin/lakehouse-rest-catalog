import { get, post } from './client.js'

/**
 * Lance Namespace REST（`/lance/v1/**`）的调用面——只读三件事。
 *
 * <p>与另外两个协议面不是同一套东西，三处差别都在这里吸收，不要上浮到视图：
 *
 * 1. **没有 `/v1/config` 握手**：Lance 的客户端按 id 直接打 `/lance/v1/namespace/{id}`，
 *    服务端也没有一个「声明能力」的位置（见 `docs/lance-rest-catalog-design.md` §12.9）。
 * 2. **id 是拼接串**：`catalog$schema` 与 `catalog$schema$table` 各是**一段**路径，
 *    不是三段层级。分隔符默认 `$`（URL 里是 `%24`），客户端可改，控制台按默认值拼。
 * 3. **列举只回 id**：`ListTables` 的 `tables` 是字符串数组，元素是**完整 id**
 *    （`catalog$schema$table`，服务端 `LanceTableService.list` 的写法），没有位置、
 *    没有版本、也没有「存储里有没有数据集」。这些只在单表的 `DescribeTable` 里，
 *    因此列表页不伪造这些列，把它们放进按需打开的详情抽屉（Paimon 面的
 *    `table-details` 一次给全，Lance 面没有对应端点——这是两面的真实差距，不是没写）。
 *
 * <p><b>只登记只读端点。</b>declare / register / deregister / create / drop 都是客户端
 * （Spark、Lance SDK）的活；控制台出现这些按钮，等于承诺「这里能建表」，而这台服务端
 * 对 Lance 没有写入路径（`docs/lance-rest-catalog-design.md` §3.2）。
 */

/** 默认分隔符。与服务端 `LanceIds` 的缺省一致；控制台不允许改（客户端才需要）。 */
export const LANCE_DELIMITER = '$'

/** 拼接 id：丢掉空段，避免拼出 `cat$$tbl` 这种服务端解不出来的形状。 */
export function lanceId(...parts) {
  return parts.filter((part) => part !== undefined && part !== null && part !== '').join(LANCE_DELIMITER)
}

/**
 * id 的末段（界面上显示用）。
 *
 * <p>`catalog$schema` 显示成 `schema`，`catalog$schema$table` 显示成 `table`。
 * 完整 id 仍然放在 `title` 里——**显示名是有损的**（名字本身可以含分隔符），
 * 复制给客户端时要用完整那个。
 */
export function lanceLeaf(id) {
  const text = (id || '').trim()
  return text ? text.split(LANCE_DELIMITER).pop() : ''
}

export const lanceApi = {
  /**
   * 列 catalog 下的命名空间（Lance 只有两级，这里就是全部）。
   *
   * <p>服务端在 `id` 只有一段（即 catalog）时回它的全部 schema；`properties` 是该 schema
   * 的 options，控制台只用来显示个数，不解析内容。
   */
  listNamespaces: (catalog, query = {}) => get('lanceListNamespaces', {
    params: { id: catalog, query },
  }),

  /**
   * 列命名空间下的表。响应是 `{tables: ['a', 'b'], page_token}`——**只有名字**。
   */
  listTables: (namespaceId, query = {}) => get('lanceListTables', {
    params: { id: namespaceId, query },
  }),

  /**
   * 单表描述。
   *
   * <p>`detailed: true` 才去读最新 manifest（版本 / schema / 统计）；默认那一次只回
   * 位置与属性，便宜。**只有登记、没有数据集**的表在 detailed 下会失败（规范允许），
   * 因此列表上不调它，抽屉里单独给一个按钮，失败原因原样显示而不是当成「页面出错」。
   */
  describeTable: (tableId, { detailed = false } = {}) => post('lanceDescribeTable', {
    params: { id: tableId, query: { check_declared: true, load_detailed_metadata: detailed } },
  }),
}
