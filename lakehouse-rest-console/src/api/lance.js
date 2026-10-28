import { get, post } from './client.js'

/**
 * Lance Namespace REST（`/lance/v1/**`）的调用面——三条读、一条写。
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
 * <p><b>写只有一条：建命名空间。</b>命名空间是纯元数据登记（服务端
 * `LanceNamespaceService.create` 只落一行 database 记录），与「建表」不是一回事：
 * 表那一侧的 declare / register / deregister / rename / drop 一个都不登记——这台服务端
 * 对 Lance 没有建立数据的路径（`docs/lance-rest-catalog-design.md` §3.2），
 * 控制台给那些按钮等于承诺「这里能建表」。
 */

/** 默认分隔符。与服务端 `LanceIds` 的缺省一致；控制台不允许改（客户端才需要）。 */
export const LANCE_DELIMITER = '$'

/**
 * 命名空间名允许的字符。**与「id 段白名单」是同一个集合，且不含 `$`**。
 *
 * 服务端 `LanceIds.LEVEL` 是 `[A-Za-z0-9_.-]+`，超集会被 400 挡掉（`code 13`）；
 * `$` 尤其不能放过去——它是 id 的分隔符，放进去等于在名字里开了一个层级。
 */
export const LANCE_NAME_PATTERN = /^[A-Za-z0-9_.-]+$/

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
   * <p>响应是 `{namespaces: ['demo', ...], page_token}`——**裸名字**，相对父级
   * （`id=当前 catalog`）的子级名。规范原话是「child namespaces relative to the parent」，
   * 真客户端也按字符串数组读，因此这里不能再假设元素是 `{id, properties}`：
   * 完整 id 要自己用 {@link lanceId} 拼回去，属性要另外调 describe。
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

  /**
   * 建命名空间（`POST /lance/v1/namespace/{id}/create`）。
   *
   * <p>`namespaceId` 是**新命名空间的完整 id**（`catalog$schema`），路径与 body 里
   * 都要有，且必须一致——只给路径不给 body 上的 `id` 服务端也收（`id` 可空表示以路径
   * 为准），但两边都给能让「路径与 body 不一致就 400」这条规范要求自然成立。
   *
   * <p>**只能建两级。**`LanceIds` 把 namespace 类 operation 收窄到 1–2 段，
   * 只给 catalog（一段）时服务端回「catalog 级不支持」，所以这里永远是 `catalog$名字`。
   *
   * <p>不带 `mode`：缺省是 `create`，已存在时 409（页面把那句话原样显示）。
   * `exist_ok` / `overwrite` 是给脚本用的幂等模式，控制台不提供——「点一下创建却
   * 悄悄改了别人的属性」比报一个错更难查。
   */
  createNamespace: (namespaceId, properties = {}) => post('lanceCreateNamespace', {
    params: { id: namespaceId },
    body: { id: namespaceId, properties },
  }),
}
