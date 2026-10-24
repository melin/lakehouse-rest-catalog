/**
 * 行过滤 / 列脱敏的**结构化编辑器共享逻辑**（设计稿 row-filter-column-mask-design.md §5.3）。
 *
 * <p>两处 UI 用同一份：表详情的「数据策略」页签（`TablePolicyPanel.vue`）与
 * 数据保护总览的新建向导（`PolicyWizardView.vue`）。抽成纯函数模块的原因与
 * `PolicyExpressionEvaluator`（服务端唯一双实现处）相同——编辑器逻辑抄两份
 * 迟早漂移，而且漂移的表现是「同一个策略两个入口建出来的形状不一样」。
 *
 * <p>AST 键名与服务端 `PolicyExpressions.validateOp` / `parseStandardMask` 逐字对齐：
 * 写错的表现在是保存时吃一个 400，而服务端的报错会指名写清期望什么。
 */

/** 行过滤算子。键名与服务端 `validateOp` 的 op 值逐字一致。 */
export const ROW_OPS = [
  { value: 'eq', label: '=（等于）' },
  { value: 'neq', label: '≠（不等于）' },
  { value: 'lt', label: '<（小于）' },
  { value: 'le', label: '≤（小于等于）' },
  { value: 'gt', label: '>（大于）' },
  { value: 'gte', label: '≥（大于等于）' },
  { value: 'like', label: 'LIKE（模式匹配）' },
  { value: 'in', label: 'IN（在列表中）' },
  { value: 'is_null', label: 'IS NULL（为空）' },
  { value: 'is_not_null', label: 'IS NOT NULL（不为空）' },
]

/** 标准脱敏集（§5.3.2.1，名字取自 SPIP 的 ColumnMasks）。参数形状与 `parseStandardMask` 一致。 */
export const MASK_KINDS = [
  { fn: 'mask_alphanum', label: '字母数字打码（mask_alphanum）' },
  { fn: 'show_first_n', label: '保留前 N 位（show_first_n）' },
  { fn: 'show_last_n', label: '保留后 N 位（show_last_n）' },
  { fn: 'replace_with_null', label: '置空（replace_with_null）' },
  { fn: 'mask_to_fixed_value', label: '替换为固定值（mask_to_fixed_value）' },
  { fn: 'truncate_to_year', label: '截断到年（truncate_to_year）' },
  { fn: 'truncate_to_month', label: '截断到月（truncate_to_month）' },
  { fn: 'hash', label: '哈希（hash）' },
]

/** hash 的算法清单：必须写明，不允许「读集群配置」（设计 §3.4）。 */
export const HASH_ALGORITHMS = ['MD5', 'SHA-256', 'SHA-512']

/** 各方式要求的参数形状：none / int / string / select。 */
export function argKindOf(fn) {
  if (fn === 'show_first_n' || fn === 'show_last_n') return 'int'
  if (fn === 'mask_to_fixed_value') return 'string'
  if (fn === 'hash') return 'select'
  return 'none'
}

export function defaultArg(fn) {
  const kind = argKindOf(fn)
  if (kind === 'int') return 1
  if (kind === 'select') return 'SHA-256'
  return ''
}

/** 新条件行的初始形状（列名由调用方按可用列给）。 */
export function emptyCondition(column = '') {
  return { column, op: 'eq', valueKind: 'lit', literal: '' }
}

/** 新脱敏行的初始形状。 */
export function emptyMask(column = '') {
  return { column, fn: 'mask_alphanum', arg: '', allowNullOnRequired: false, raw: null }
}

/**
 * 编辑器不支持的表达式形状。判定按语义不按运气：从 AST 递归解析，认不出的键就放弃。
 * 返回条件数组（拍平的 AND/OR 同层）或 `null`（超出编辑器范围，原样保留）。
 */
export function parseConditionAst(node) {
  if (!node || typeof node !== 'object' || Array.isArray(node)) return null
  if (node.col !== undefined) return null // 列引用不能单独当条件
  const op = node.op
  if (op === 'and' || op === 'or') {
    const children = node.children || []
    const parsed = children.map(parseConditionAst)
    if (parsed.some((item) => item === null)) return null
    // 混合 AND/OR 的嵌套层拍不平，只在「与顶层同一算子」时接受（拍平）
    if (children.length > 1 && children.every((child) => child?.op === op)) {
      return parsed.flat()
    }
    return null
  }
  if (op === 'is_null' || op === 'is_not_null') {
    const column = node.operand?.col
    if (!column || Object.keys(node.operand).length !== 1) return null
    return [{ column, op, valueKind: 'lit', literal: '' }]
  }
  if (op === 'in') {
    const column = node.operand?.col
    if (!column || Object.keys(node.operand).length !== 1) return null
    if (node.values && typeof node.values === 'object' && !Array.isArray(node.values)) {
      if (!node.values.principal_roles) return null
      return [{ column, op, valueKind: 'roles', literal: '' }]
    }
    const literals = (node.values || []).map((item) => item?.lit)
    if (literals.some((value) => typeof value === 'object')) return null
    return [{
      column,
      op,
      valueKind: 'lit',
      literal: literals.map((value) => (value === null ? 'NULL' : String(value))).join(','),
    }]
  }
  if (['eq', 'neq', 'lt', 'le', 'gt', 'gte', 'like'].includes(op)) {
    const left = node.left
    const right = node.right
    const column = left?.col
    if (!column || Object.keys(left).length !== 1 || !right || typeof right !== 'object') return null
    if (right.lit !== undefined && Object.keys(right).length === 1) {
      const value = right.lit
      return [{
        column,
        op,
        valueKind: 'lit',
        literal: value === null ? 'NULL' : String(value),
      }]
    }
    if (right.principal === true && Object.keys(right).length === 1) {
      return [{ column, op, valueKind: 'principal', literal: '' }]
    }
    return null
  }
  return null
}

/** 条件草稿 → 服务端 AST。与 `validateOp` 的键名逐字对齐。 */
export function buildRowFilterAst(conditions, combine) {
  const usable = conditions.filter((cond) => cond.column)
  if (usable.length === 0) return null
  const nodes = usable.map((cond) => {
    if (cond.op === 'is_null' || cond.op === 'is_not_null') {
      return { op: cond.op, operand: { col: cond.column } }
    }
    if (cond.op === 'in') {
      if (cond.valueKind === 'roles') {
        return { op: 'in', operand: { col: cond.column }, values: { principal_roles: true } }
      }
      return { op: 'in', operand: { col: cond.column }, values: splitLiterals(cond.literal) }
    }
    const right = cond.valueKind === 'principal' ? { principal: true } : { lit: toLiteral(cond.literal) }
    return { op: cond.op, left: { col: cond.column }, right }
  })
  if (nodes.length === 1) return nodes[0]
  return { op: combine, children: nodes }
}

/** 「CN,US」→ [{lit:'CN'},{lit:'US'}]；空段丢弃，全空视为一个空串（服务端会给 400 指名）。 */
export function splitLiterals(text) {
  const parts = String(text ?? '')
    .split(',')
    .map((part) => part.trim())
    .filter((part) => part !== '')
  return (parts.length ? parts : ['']).map((part) => ({ lit: toLiteral(part) }))
}

/**
 * 表单里的文本 → 字面量：纯数字按数值，`true` / `false` 按布尔，其余按字符串。
 * 日期等类型敏感的值直接写 ISO 文本（`2026-01-01`）——服务端校验不过会 400 指名，
 * 比前端猜列类型更诚实。
 */
export function toLiteral(text) {
  const trimmed = String(text ?? '').trim()
  if (/^-?\d+(\.\d+)?$/.test(trimmed)) return Number(trimmed)
  if (trimmed === 'true') return true
  if (trimmed === 'false') return false
  return trimmed
}

/** mask 草稿 → 服务端 AST（标准集形状）。 */
export function buildMaskAst(mask) {
  const ast = { fn: mask.fn }
  const kind = argKindOf(mask.fn)
  if (kind === 'int') ast.args = [Number(mask.arg) || 0]
  if (kind === 'string') ast.args = [String(mask.arg ?? '')]
  if (kind === 'select') ast.args = [String(mask.arg || 'SHA-256')]
  return ast
}

/** 脱敏草稿列表 → PUT 请求里的 masks 数组（标准集取表单、逃生口原样带回）。 */
export function buildMasksPayload(masks) {
  return masks
    .filter((mask) => mask.column && (mask.fn || mask.raw))
    .map((mask) => ({
      column: mask.column,
      mask: mask.raw || buildMaskAst(mask),
      allowNullOnRequired: !!mask.allowNullOnRequired,
    }))
}

/** 同一列配置多次的告警文本（空串 = 没有重复）。视图里用 computed 包一层即可。 */
export function duplicateMaskColumnText(masks) {
  const seen = new Set()
  const duplicated = new Set()
  for (const mask of masks) {
    if (!mask.column) continue
    if (seen.has(mask.column)) duplicated.add(mask.column)
    seen.add(mask.column)
  }
  return [...duplicated].join('、')
}
