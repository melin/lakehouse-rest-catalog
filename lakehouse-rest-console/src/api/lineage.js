import { get } from './client.js'

/**
 * 血缘的两条**读**端点（`/api/management/v1/lineage/**`，设计见 `docs/lineage-design.md` §8）。
 *
 * <p>为什么单独一个模块而不是塞进 `management.js`：这两条是控制台里唯一「一次请求
 * 回答一整张图」的端点（响应里同时有节点、边、预算与 ETag），形状与其余管理端点的
 * 「一页列表」完全不同；放在一起读者会以为它们也是分页列表，从而去找 `page`/`pageSize`。
 *
 * <p><b>只有读。</b>上报端点 `POST /lineage/runs` 是给引擎侧（Spark 抽取器）用的，
 * 控制台不该有「手工上报血缘」的入口——那等于让人手写一条假的依赖关系。
 *
 * <p><b>没有 `runs` / `runs/{runId}`。</b>服务端有这两条（运行历史与单次明细），但控制台
 * 目前没有展示它们的地方，而 `endpoints.js` 里声明了却没人调用的条目会被
 * `verify-console.py` 第 4 组判成死条目。等「最近写入」抽屉要展示 SQL 原文时再补。
 */

/**
 * 表级上下游。
 *
 * <p>参数全由调用方给（面板里只有一处拼，见 `LineagePanel.loadGraph`）：
 * `ref` 是归一化全名 `catalog.namespace.table`，`direction` 取
 * `upstream` / `downstream` / `both`（服务端默认 `downstream`），`depth` 是跳数，
 * `maxNodes` 是节点预算，`compact=1` 要紧凑形状，`columnCount=1` 让服务端把边的
 * 列数一起聚合出来（图用它算线宽）。
 *
 * <p><b>不发 `If-None-Match`。</b>服务端会回 ETag 并在命中时回 304，但 `client.js`
 * 把 304 当成功、响应体为空，`decode({})` 会得到一张空图——那比多传一次 JSON 更糟。
 * 要接上条件请求得先让客户端认识 304，等真有重复拉同一张图的压力再说。
 *
 * @param {object} query 见上
 */
function tables(query) {
  return get('lineageTables', { params: { query } })
}

/**
 * 列级映射（按目标列分组）。
 *
 * <p>与 {@link tables} 分开是服务端的设计：图只要表级结构，列级会让边数变成
 * `表数 × 列数`。因此这里只在用户切到「列映射」视图时才发一次，切回图不重发。
 *
 * @param {object} query `{ ref, direction, engine }`
 */
function columns(query) {
  return get('lineageColumns', { params: { query } })
}

export const lineageApi = { tables, columns }
