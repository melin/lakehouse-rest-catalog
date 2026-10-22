/**
 * 图视图的列级血缘（§8.7 / §9.5(1)）：把批量列边解码成「卡片的列清单 + 列与列的连线」。
 *
 * <p>这个模块是纯函数：`decode` 只认报文，`buildCards` 只认（下标表, 列边）。渲染与命中
 * 测试都在 `lineageRender` / `LineageGraph`，布局只在 `lineageLayout`（读卡片的行数算高度）。
 *
 * <p><b>为什么节点集合由面板给（§8.7）。</b>图已经被过滤、展开与截断过，服务端按根重算
 * 的集合与它对不上。因此调用链是「面板拿当前展示的节点列表 → 一次请求 → 这里解码」，
 * 任何导致节点集合变化的动作（换根、展开、过滤）都会让列边作废重拉。
 */

/** 单张卡片最多画多少行列清单；超出的收进一行 `+N`（§9.5 的列预算）。 */
export const MAX_CARD_ROWS = 24

/**
 * SQL 类型 → 图标类别的归类（§9.5(1) 的数据类型图标）。
 *
 * <p>只分四类 + 兜底，不做完整类型系统：图标是给「扫一眼」用的，分错一两个
 * 边缘类型（比如把 `bit` 归进数字）的代价远小于维护一张大映射表。
 *
 * @param {string} type schema 字段的类型串（`int` / `varchar(255)` / `array<string>`）
 * @returns {''|'num'|'str'|'bool'|'complex'|'other'} 空串 = 未知（schema 没拉到，不画图标）
 */
export function typeKindOf(type) {
  const raw = String(type || '').toLowerCase()
  if (!raw) return ''
  if (/\b(array|map|struct|row|vector|json|binary|varbinary|blob)\b/.test(raw)) return 'complex'
  if (/\b(boolean|bool)\b/.test(raw)) return 'bool'
  if (/\b(tinyint|smallint|int|integer|bigint|long|short|byte|float|double|real|decimal|numeric|number)\b/.test(raw)) return 'num'
  if (/\b(char|varchar|string|text|clob|uuid)\b/.test(raw)) return 'str'
  return 'other'
}

/**
 * 解码 `GET /lineage/column-edges` 的响应。
 *
 * @param {object} payload `{ refs: string[], edges: [[s, sc, t, tc, kind]] }`
 * @returns {{ edges: Array<{s:number, sCol:string, t:number, tCol:string, kind:string}> }}
 */
export function decodeColumnEdges(payload) {
  const edges = (payload?.edges || []).map((row) => ({
    s: row[0],
    sCol: row[1],
    t: row[2],
    tCol: row[3],
    kind: row[4] || 'UNRESOLVED',
  }))
  return { edges }
}

/**
 * 从列边造出每张卡片的列清单。
 *
 * <p><b>行 = 被写过的列 ∪ 被引用的列。</b>目标侧的列（带 `kind`）是权威清单；
 * 只作为源出现的列（比如 `SELECT a + b AS c` 里的 `a`）也占一行——不然连线没有落点，
 * 用户会以为源表只有一条光秃秃的表级边。源侧独有行的 `kind` 为空串（渲染时不出徽标）。
 *
 * <p>排序是确定性的：先目标侧列（字典序），再源侧独有列（字典序）。同一份输入
 * 两次调用得到同一份清单（§9.2 的老规矩）。
 *
 * @param {Array<string>} names 下标 → ref（`columnEdges` 请求时的同一份节点列表）
 * @param {Array} edges `decodeColumnEdges().edges`
 * @param {object} [options]
 * @param {number} [options.maxRows] 单卡可见行数上限，默认 {@link MAX_CARD_ROWS}
 * @param {Function} [options.typesOf] `(ref, colName) => type`：列类型查询（面板从表
 *        详情的 schema 现拉的）。给出行上带 `type`（原始类型串，渲染层按
 *        `typeKindOf` 分图标类别）；不给或查不到时 `type` 为空串——不画图标。
 * @returns {Map<string, {rows: Array<{name: string, kind: string, type: string}>, overflow: number,
 *                      total: number, rowIndexOf: Map<string, number>}>}
 *          只含出现在列边里的 ref；`rowIndexOf` 只含可见行，被 `+N` 折掉的列查不到——
 *          连线落点由渲染层钳到最后一行。
 */
export function buildCards(names, edges, { maxRows = MAX_CARD_ROWS, typesOf } = {}) {
  const byRef = new Map()
  const cardOf = (ref) => {
    if (!byRef.has(ref)) byRef.set(ref, { targets: new Map(), sources: new Map() })
    return byRef.get(ref)
  }
  for (const edge of edges) {
    const targetRef = names[edge.t]
    if (!targetRef) continue
    if (edge.s >= 0) {
      cardOf(names[edge.s]).sources.set(edge.sCol, edge.sCol)
    }
    // 目标侧的 kind 是权威值：同名列既是目标又是源时，以「它被怎么写出来」为准
    if (!cardOf(targetRef).targets.has(edge.tCol)) {
      cardOf(targetRef).targets.set(edge.tCol, edge.kind)
    }
  }

  const cards = new Map()
  for (const [ref, { targets, sources }] of byRef) {
    const rows = [...targets.entries()]
      .sort((a, b) => a[0].localeCompare(b[0]))
      .map(([name, kind]) => ({
        name,
        kind,
        type: typesOf ? String(typesOf(ref, name) || '') : '',
      }))
    const seen = new Set(rows.map((row) => row.name))
    for (const name of [...sources.keys()].sort((a, b) => a.localeCompare(b))) {
      if (!seen.has(name)) rows.push({ name, kind: '', type: typesOf ? String(typesOf(ref, name) || '') : '' })
    }
    const overflow = Math.max(0, rows.length - maxRows)
    const visible = rows.slice(0, maxRows)
    const rowIndexOf = new Map(visible.map((row, index) => [row.name, index]))
    cards.set(ref, { rows: visible, overflow, total: rows.length, rowIndexOf })
  }
  return cards
}
