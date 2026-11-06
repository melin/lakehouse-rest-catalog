/**
 * 表详情「敏感数据」页签（mockup `#table`；设计稿 §11）的纯函数层。
 *
 * <p><b>为什么判据要单拎出来。</b>这一屏要回答的「这张表能对外吗」不是一个数字，而是
 * 四条判据的结论：表级最高级别、机密列数、这些列**是否都已被脱敏**、以及**还有多少列
 * 根本没被分类**。这四条各自都能算错，而算错的表现是**一句看起来很确定的结论**——
 * 页面不会报错，读的人也不会怀疑。放进纯函数并配用例，是把「结论怎么来的」钉住的
 * 唯一低成本办法（与 `utils/lineageLayout.js`、`utils/policyAst.js` 同一取舍）。
 *
 * <p><b>级别不按字符串比大小。</b>服务端 `SensitivityLevel` 的 `ordinal()` 是声明顺序
 * （`L1..L4, IMPORTANT, CORE`），而上层那两档是人工作出的「重要 / 核心数据」认定，
 * 严重度高于 L4。这里用同一份 rank 表（与服务端 `rank()` 逐字对齐），不依赖
 * `Object.keys` 顺序，也不依赖任何 locale 上的字符串排序。
 */

/**
 * 级别的严重度序数，与服务端 `SensitivityEnums.SensitivityLevel.rank()` 逐字对应。
 *
 * <p>抄在 JS 里有漂移风险，`verify-console.py` 有断言盯着这六个值与 Java 常量一致。
 * 之所以不改成「从接口读」：它是**判定口径**而不是数据——接口给的是「这一列的级别是
 * 什么」，而「L4 比 L3 严重」是本方案的分级定义（§5.5 对齐《网络数据分类分级指引》），
 * 让服务端多回一份序数，只是把同一份定义挪个地方。
 */
export const LEVEL_RANK = {
  L1: 1,
  L2: 2,
  L3: 3,
  L4: 4,
  IMPORTANT: 5,
  CORE: 6,
}

/** 只看级别名、不看序数：序数为 0 表示「不是分级值」（如 `owner.team`）。 */
export function levelRank(level) {
  return LEVEL_RANK[level] || 0
}

/** 人工认定的两档。它们**不参与自动识别**，只可能来自手工打标（设计稿 §5.5）。 */
export const MANUAL_ONLY_LEVELS = ['IMPORTANT', 'CORE']

/** 级别 → el-tag 语义色。与 `SensitivityTagsView` 的同名映射保持一致（同一套词表）。 */
export function levelTagType(level) {
  return {
    L4: 'danger',
    L3: 'warning',
    L2: 'primary',
    L1: 'info',
    IMPORTANT: 'danger',
    CORE: 'danger',
  }[level] || 'info'
}

/** 来源 → 中文标签。人工与自动必须一眼可分：前者可追责，后者可复现。 */
export function sourceLabel(source) {
  return { AUTO: '自动', MANUAL: '人工', INHERITED: '继承' }[source] || source || '—'
}

/** 来源 → el-tag 语义色。与 `SensitivityTagsView` 一致。 */
export function sourceTagType(source) {
  return { AUTO: 'primary', MANUAL: 'success', INHERITED: 'warning' }[source] || 'info'
}

/**
 * 一列归属来源时的优先级：人工 &gt; 自动 &gt; 继承。
 *
 * <p>与服务端 `ColumnTagService.SOURCE_PRIORITY` 同序，理由也一样：它表达的是**结论的
 * 权威性**，不是敏感度。一列同时有人工标签与自动标签时，它应该被读成「这列靠人管着」——
 * 算成自动会把「人工覆盖」这件事从统计里抹掉。
 */
const SOURCE_PRIORITY = ['MANUAL', 'AUTO', 'INHERITED']

/**
 * 把「全量列 + 标签 + 这张表的策略」拼成表格行。
 *
 * <p><b>行来自 `columns` 而不是来自 `tags`。</b>这是这一屏与「标签清单」屏最本质的差别：
 * 标签清单答的是「有哪些治理结论」，它天然只有命中的列；而这一屏要答「**每一列**现在
 * 是什么状态」，因此必须以 schema 为骨架左外连接标签——反过来的话，**没被识别出来的列
 * 会从页面上消失**，而「哪些列没被分类」正是覆盖率缺口（设计稿 §13 的「未分类列显式
 * 标灰，不能靠沉默掩盖」）。
 *
 * @param {Array}  columns       全量列 `[{name, type}]`（来自表详情已取回的 schema）
 * @param {Array}  tags          `GET /column-tags` 的 `items`（**表级标签会被跳过**）
 * @param {Array}  masks         策略里的列脱敏 `[{column, sql}]`
 * @param {Array}  hiddenColumns 策略里的整列不可见列名
 */
export function buildRows(columns, tags, masks, hiddenColumns) {
  const byColumn = new Map()
  for (const tag of tags || []) {
    // 表级标签（`column` 为空串 / `tableLevel=true`）描述的是「这张表里最敏感的一列有多敏感」，
    // 它不是某一列的结论。挂到列行上会凭空多出一列带标签——那一列并不存在
    if (tag.tableLevel) continue
    const key = tag.column
    if (!byColumn.has(key)) byColumn.set(key, [])
    byColumn.get(key).push(tag)
  }
  const maskByColumn = new Map((masks || []).map((mask) => [mask.column, mask.sql]))
  const hidden = new Set(hiddenColumns || [])

  return (columns || []).map((column) => {
    const columnTags = byColumn.get(column.name) || []
    let level = ''
    for (const tag of columnTags) {
      if (levelRank(tag.level) > levelRank(level)) level = tag.level
    }
    const sources = new Set(columnTags.map((tag) => tag.source))
    const source = SOURCE_PRIORITY.find((candidate) => sources.has(candidate)) || ''
    const maskSql = maskByColumn.get(column.name) || ''
    const isHidden = hidden.has(column.name)
    return {
      name: column.name,
      type: column.type,
      tags: columnTags,
      level,
      source,
      /** 有**任何**标签就算已分类——与服务端 `coverage.classifiedColumns` 同一口径：
       *  它数的是「有标签的列」，不是「有级别的列」（`owner.team` 这类也占一格）。 */
      classified: columnTags.length > 0,
      maskSql,
      hidden: isHidden,
      /** 当前是否已被保护。P0 只认策略里**显式**写的脱敏与不可见；
       *  「标签绑定」要等 P3 的绑定对照（§8.1），今天在界面上把它算成已保护是假的。 */
      protected: Boolean(maskSql) || isHidden,
    }
  })
}

/**
 * 「这张表能对外吗」的四条判据。
 *
 * <p>返回的是判据本身 + 一句结论，**不是一个是 / 否**。设计稿 §11 的原型里那一栏就是
 * 一张「判据 → 结果」的表，理由很实在：对外能不能给是业务判断，平台能给的是**它缺什么**。
 * 直接回「不能」会让人去找一个不存在的开关；把「还有 1 列没分类」摆出来，处置才是清楚的。
 *
 * @param {Array} rows  {@link buildRows} 的结果
 */
export function exposureVerdict(rows) {
  const list = rows || []
  const secretLevels = ['L4', ...MANUAL_ONLY_LEVELS]
  const secret = list.filter((row) => secretLevels.includes(row.level))
  const secretProtected = secret.filter((row) => row.protected)
  const unclassified = list.filter((row) => !row.classified)
  // 有级别但没被保护的列（L2 及以上）：它们不是「机密」，但也不是可以随便给出去的
  const exposed = list.filter((row) => levelRank(row.level) >= 2 && !row.protected)

  let topLevel = ''
  for (const row of list) {
    if (levelRank(row.level) > levelRank(topLevel)) topLevel = row.level
  }

  // 三档**逐级加重、只升不降**：写成 `cond ? a : (cond ? b : c)` 的嵌套时会漏档——
  // 「有条件列仍是明文」（risk）与「有条件列没脱敏」（warn）同时成立时，
  // 后面的分支会把已经判定的 risk 覆盖成 warn，界面从红转黄。
  const plaintextSecrets = secret.length - secretProtected.length
  let tone = 'ok'
  if (unclassified.length > 0 || exposed.length > 0) tone = 'warn'
  if (plaintextSecrets > 0) tone = 'risk'

  // 结论永远是「还缺什么」，而不是「行 / 不行」——平台能给的只有缺口清单，
  // 「能不能对外」是业务判断（设计稿 §11）。空清单才说「未发现缺口」。
  const gaps = []
  if (plaintextSecrets > 0) {
    gaps.push(`${plaintextSecrets} 个机密列（L4 / 重要 / 核心）仍是明文`)
  }
  if (exposed.length > 0) {
    gaps.push(`${exposed.length} 个 L2 及以上的列没有脱敏动作`)
  }
  if (unclassified.length > 0) {
    // 这一条必须与「扫过、没敏感」分开说：覆盖率的缺口是**分母**问题
    gaps.push(`${unclassified.length} 列还没被分类，它们「不敏感」这个结论不成立`)
  }

  let verdict
  if (!gaps.length) {
    verdict = list.length
      ? '未发现缺口：列已全部分类，机密列均已受保护'
      : '这张表没有可判定的列'
  } else if (tone === 'risk') {
    verdict = `不可对外：${gaps.join('；')}`
  } else {
    // 原型对「L4 已全部受保护、还剩未分类列」给的就是这一句（`sensitive-data-mockup.html`
    // `#table` 的结论栏）——「可内部共享」是平台敢说的那一半，另一半是缺口清单
    verdict = `可内部共享，对外前需补齐：${gaps.join('；')}`
  }

  return {
    topLevel,
    secretTotal: secret.length,
    secretProtected: secretProtected.length,
    exposedTotal: exposed.length,
    unclassifiedTotal: unclassified.length,
    totalColumns: list.length,
    classifiedTotal: list.length - unclassified.length,
    tone,
    /** 一句话结论：永远是「缺什么」，而不是「行 / 不行」。 */
    verdict,
  }
}
