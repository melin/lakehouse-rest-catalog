import { get, post } from './client.js'

/**
 * Lance Namespace REST（`/lance/v1/**`）的调用面——五条读、一条写。
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
 *    因此**列表页只有表名与 id 两列**，位置 / 版本 / 是否只有登记都在详情页按需读
 *    （Paimon 面的 `table-details` 一次给全，Lance 面没有对应端点——这是两面的真实差距）。
 *
 * <p><b>写只有一条：建命名空间。</b>命名空间是纯元数据登记（服务端
 * `LanceNamespaceService.create` 只落一行 database 记录），与「建表」不是一回事：
 * 表那一侧的 declare / register / deregister / rename / drop 一个都不登记——这台服务端
 * 对 Lance 没有建立数据的路径（`docs/lance-rest-catalog-design.md` §3.2），
 * 控制台给那些按钮等于承诺「这里能建表」。
 *
 * <p>版本与标签都**只读**：`version/create`、`version/delete`、`tags` 的写路径
 * 都没接。写版本要理解 manifest 的盘上布局，而控制台不该成为第二个写入方——
 * 那条路径属于 Lance 客户端（Spark connector / lance-java），见设计稿 §7。
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

/**
 * 把「列举结果里的一个元素」归一到**完整 id 字符串**。
 *
 * <p>规范说 `ListNamespaces` / `ListTables` 的元素是字符串，我们按这个实现。但把这条
 * 假设直接写进视图会很难受：**一旦对端是别的实现（或本服务端是早于这次修正的版本），
 * 元素就是 `{id: ...}`**，而 Vue 把对象插进模板会渲染成 `[object Object]`——接着这个
 * 字符串被拼进 id、发给服务端，得到的是
 * `namespace id level "[object Object]" contains characters outside [A-Za-z0-9_.-]`。
 * 那条报文说的是「id 里有非法字符」，而真正的问题是**上游把对象当字符串用了**，
 * 两者隔着好几层，很难一眼看出来。
 *
 * <p>所以这里统一吸收三种形状，视图只认字符串：
 *
 * <ul>
 *   <li>字符串：原样返回（`demo` / `cat$demo` / `cat$demo$orders`）；</li>
 *   <li>数组：`['cat', 'orders']`——规范里 body 的 `id` 是层级数组，列举结果被某个
 *       实现写成数组也不奇怪，按分隔符拼回来；</li>
 *   <li>对象：`{id}` / `{name}` / `{namespace}`——取那个字段再递归。</li>
 * </ul>
 */
export function lanceFullIdOf(item) {
  if (item === undefined || item === null) return ''
  if (Array.isArray(item)) {
    return item
      .filter((part) => part !== undefined && part !== null && part !== '')
      .map((part) => String(part).trim())
      .join(LANCE_DELIMITER)
  }
  if (typeof item === 'object') {
    return lanceFullIdOf(item.id ?? item.name ?? item.namespace)
  }
  return String(item).trim()
}

/**
 * 列举元素的**名字**（末段）。取末段是安全的：名字本身不允许含 `$`
 * （见 {@link LANCE_NAME_PATTERN}），所以末段就是相对父级的那个名字。
 */
export function lanceNameOf(item) {
  return lanceLeaf(lanceFullIdOf(item))
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
   * 因此列表上不调它，详情页里单独给一个按钮，失败原因原样显示而不是当成「页面出错」。
   */
  describeTable: (tableId, { detailed = false } = {}) => post('lanceDescribeTable', {
    params: { id: tableId, query: { check_declared: true, load_detailed_metadata: detailed } },
  }),

  /**
   * 列举版本（`POST /lance/v1/table/{id}/version/list`）。
   *
   * <p>响应是 `{versions: [{version, manifest_path, manifest_size, e_tag,
   * timestamp_millis, metadata}], page_token}`。**默认倒序**：进这一页的人几乎总在找
   * 最近一次提交，正序要把列表翻到底才能看到它。
   *
   * <p>`limit` 默认 100：一张高频写入的表版本数可以上千，一次拉全量对这个页面没有
   * 意义（这里没有按版本号的过滤器，翻到第 200 个版本的人其实是在看盘上的
   * `_versions/` 目录，那是「文件列表」页签的事）。
   */
  listVersions: (tableId, { descending = true, limit = 100 } = {}) => post('lanceListVersions', {
    params: { id: tableId, query: { descending, limit } },
  }),

  /**
   * 列举标签（`POST /lance/v1/table/{id}/tags/list`）。
   *
   * <p>响应里的 `tags` 是 **map**（标签名 → `{branch, version, manifestSize}`），
   * 不是数组——`manifestSize` 还是唯一一处驼峰（服务端 DTO 照盘上的 JSON 字段名）。
   * 标签目录不存在时回空 map 而**不是 404**：没有标签是正常状态，不是这张表坏了。
   */
  listTags: (tableId, { limit = 100 } = {}) => post('lanceListTags', {
    params: { id: tableId, query: { limit } },
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
