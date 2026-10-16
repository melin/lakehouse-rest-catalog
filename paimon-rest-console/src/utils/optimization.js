/**
 * 表存储优化的展示知识与参数表单定义。
 *
 * <p>放在 utils 而不是各自视图里：表详情页的「存储优化」页签与全局「优化任务」页
 * 展示同一批动作、同一套状态取值与同一份指标口径，两处各写一份必然漂移——
 * 而漂移的表现是「同一张表在两个页面显示的参数不一样」，最难查的那类问题。
 *
 * <p><b>参数键名不是随便起的。</b>每一项动作允许出现的键由服务端的
 * `OptimizationOptions.KNOWN_KEYS` 定义，键名写错的表现是保存时吃一个
 * 400「Unknown compact option」；而这类错字编译、类型检查都不会报。
 * `scripts/verify-console.py` 会拿本文件里的 `fields` 与服务端那张表逐字比对，
 * 因此**加参数只改这一处**（服务端那边同步改，校验脚本会指出不一致）。
 *
 * <p>取值口径也要跟服务端的 {@code OptimizationOptions.validate} 对齐
 * （数字区间的上下限写在字段的 `min` / `max` 上，由输入控件挡住大部分非法值）：
 * 校验失败发生在**保存时**，而在表单上先拦住比让用户提交后看报错便宜。
 */

/** 三项动作的展示元数据与参数表单定义。顺序与执行顺序一致（compact → expire → orphan）。 */
export const ACTIONS = [
  {
    action: 'compact',
    label: 'Compaction',
    summary: '把小文件合并成更大的文件，提升查询性能',
    fields: [
      {
        key: 'strategy',
        label: '合并策略',
        type: 'select',
        choices: [
          { value: 'binpack', label: '小文件合并（Binpack）' },
          { value: 'sort', label: '排序合并（Sort）' },
        ],
        tip: 'Binpack 把小文件合并到目标大小；排序合并按指定列重排后再合并，'
          + '适合经常按某几列过滤的表——代价是合并本身更贵。',
      },
      {
        key: 'sort-columns',
        label: '排序列',
        type: 'text',
        placeholder: '如 dt,id',
        showWhen: (values) => values.strategy === 'sort',
        tip: '排序合并必填，逗号分隔。一般填查询里最常出现在过滤条件中的列。',
      },
      {
        key: 'min-input-files',
        label: '触发阈值',
        type: 'number',
        unit: '个文件',
        min: 1,
        max: 1000,
        tip: '待合并的小文件数达到这个值才启动合并。给 compaction 一个阈值是必要的：'
          + '没有阈值，每次扫描都会变成一次作业。',
      },
      {
        key: 'target-file-size-mb',
        label: '目标文件大小',
        type: 'number',
        unit: 'MB',
        min: 1,
        max: 4096,
        tip: '合并后单个文件的目标大小。对象存储上小文件意味着读放大，'
          + '但文件太大又会让单次读的并行度上不去，128 MB 左右是常见的折中。',
      },
      {
        key: 'interval-hours',
        label: '执行间隔',
        type: 'number',
        unit: '小时',
        min: 1,
        max: 8760,
        tip: '两次 compaction 之间至少隔这么久。它决定的是这张表的动作频率，'
          + '与表的调度间隔（多久扫一次）是两件事。',
      },
    ],
  },
  {
    action: 'expire',
    label: 'Snapshot Expire',
    summary: '清理过期快照及其关联的数据文件，回收存储空间',
    fields: [
      {
        key: 'retention-hours',
        label: '快照保留时长',
        type: 'number',
        unit: '小时',
        min: 1,
        max: 8760,
        tip: '早于这个时长的快照会被清掉。它同时是「能回滚到多久之前」的上限，'
          + '调小要慎重：快照没了，基于它的时间旅行查询也就没了。',
      },
      {
        key: 'retain-min',
        label: '最少保留快照数',
        type: 'number',
        unit: '个',
        min: 0,
        max: 10000,
        tip: '无论多旧，至少留下这么多个快照。防止「写入停了很久」的表一开清理就被清成只剩一个。',
      },
      {
        key: 'interval-hours',
        label: '执行间隔',
        type: 'number',
        unit: '小时',
        min: 1,
        max: 8760,
        tip: '快照清理每天一次足够，比 compaction 迟钝是合理的：它要遍历快照历史，'
          + '而收益（回收空间）不随时间线性增长。',
      },
    ],
  },
  {
    action: 'orphan',
    label: 'Orphan File Remove',
    summary: '清理未被任何快照引用的废弃文件',
    fields: [
      {
        key: 'older-than-hours',
        label: '清理早于',
        type: 'number',
        unit: '小时前写入的文件',
        min: 1,
        max: 8760,
        tip: '清理窗口必须大于这张表的最大提交间隔，否则会把正在写入、'
          + '还没被任何快照引用的数据文件当成孤儿删掉。下限 1 小时是服务端强制的。',
      },
      {
        key: 'interval-days',
        label: '执行间隔',
        type: 'number',
        unit: '天',
        min: 1,
        max: 365,
        tip: '三类动作里最贵的一项（要遍历目录），按周执行足够。',
      },
      {
        key: 'dry-run',
        label: '干跑预览',
        type: 'switch',
        on: '只列出将删除的文件，不真删',
        off: '直接删除文件',
        tip: '三项动作里只有它会删数据文件，因此默认开着。'
          + '确认无误后再关掉——关掉的那一刻起，扫描到的东西就被真删了。',
      },
    ],
  },
]

/** 动作 wire 名 → 中文显示名。批次里存的是 wire 名，页面显示的是这个。 */
const ACTION_LABELS = Object.fromEntries(ACTIONS.map((item) => [item.action, item.label]))

/** 动作 wire 名 → 中文显示名；未知取值原样返回（不隐藏数据）。 */
export function actionLabel(action) {
  return ACTION_LABELS[action] || action
}

/**
 * 把 `"compact,expire"` 这样的存储形态拆成数组。
 * 顺序保留存储时的顺序（服务端固定为枚举顺序），页面据此显示「先做什么后做什么」。
 */
export function actionsOf(wire) {
  if (!wire) return []
  return String(wire).split(',').map((item) => item.trim()).filter(Boolean)
}

/**
 * 动作的展示文本，用 ` · ` 连接。
 *
 * <p>认两种形状：批次给的是一个数组（`Job.actions`），明细给的是逗号分隔的字符串
 * （`JobItem.actions`，因为一个明细可能同时跑了几项动作）。让调用方自己判断形状，
 * 迟早会有一处把数组当字符串拼成 `compact,expire`。
 */
export function actionsLabel(value) {
  const list = Array.isArray(value) ? value : actionsOf(value)
  return list.map(actionLabel).join(' · ')
}

// ------------------------------------------------------------------ 状态

/**
 * 批次状态的展示（颜色 + 文案）。
 *
 * <p>`PARTIAL_SUCCESS` 必须与 `FAILED` 分开显示：它的可操作动作是「重试失败的表」，
 * 而全失败时该看的是错误原因。两者合并成一个红色标签就分不出来了。
 */
const JOB_STATUS = {
  PENDING: { label: '排队中', tag: 'info' },
  SUBMITTING: { label: '提交中', tag: 'warning' },
  RUNNING: { label: '运行中', tag: 'primary' },
  SUCCESS: { label: '成功', tag: 'success' },
  PARTIAL_SUCCESS: { label: '部分成功', tag: 'warning' },
  FAILED: { label: '失败', tag: 'danger' },
  CANCELED: { label: '已取消', tag: 'info' },
}

const ITEM_STATUS = {
  PENDING: { label: '等待中', tag: 'info' },
  RUNNING: { label: '运行中', tag: 'primary' },
  SUCCESS: { label: '成功', tag: 'success' },
  FAILED: { label: '失败', tag: 'danger' },
  // 「跑过但无事可做」：未达阈值、没有过期快照、没有孤儿文件。它不是失败，
  // 计成失败会让「批次成功率」失去意义，因此单独一档灰色。
  SKIPPED: { label: '跳过', tag: 'info' },
}

export function jobStatus(status) {
  return JOB_STATUS[status] || { label: status || '未知', tag: 'info' }
}

export function itemStatus(status) {
  return ITEM_STATUS[status] || { label: status || '未知', tag: 'info' }
}

/** 批次是不是已经结束（决定能不能取消、能不能重试）。 */
export function isTerminalJob(status) {
  return status === 'SUCCESS' || status === 'PARTIAL_SUCCESS'
    || status === 'FAILED' || status === 'CANCELED'
}

/** 批次是不是还占着执行资源（决定要不要显示「取消」）。 */
export function isActiveJob(status) {
  return status === 'PENDING' || status === 'SUBMITTING' || status === 'RUNNING'
}

export function triggerText(triggerType) {
  return triggerType === 'MANUAL' ? '手动' : '定时调度'
}

/**
 * 批次号/明细号的短形式：完整 id 是 36 字符的 UUID，列表里放不下也不好扫读。
 * 取第一段（8 位十六进制）——同一屏里重名的概率足够低，完整值放进 tooltip。
 */
export function shortId(id) {
  if (!id) return '—'
  return String(id).split('-')[0]
}

// ------------------------------------------------------------------ 指标

/**
 * 一个明细在某项动作下的指标。
 *
 * <p>指标在服务端是**单张 JSON**，键带动作前缀（`compact.files-before`、
 * `expire.deleted-snapshots`）——因为一个明细可能同时跑了几项动作，
 * 而各项的指标集合不同。前缀在这一层剥掉，页面拿到的就是自己那一项动作的指标。
 */
export function actionMetrics(item, action) {
  const prefix = `${action}.`
  const result = {}
  for (const [key, value] of Object.entries(item?.metrics || {})) {
    if (key.startsWith(prefix)) {
      result[key.slice(prefix.length)] = value
    }
  }
  return result
}

/** 一项动作耗时（毫秒）→ `41 s` / `1m 04s`。 */
export function formatDurationMs(value) {
  const millis = Number(value)
  if (!Number.isFinite(millis) || millis < 0) return '—'
  if (millis < 1000) return `${millis} ms`
  const seconds = Math.round(millis / 1000)
  if (seconds < 60) return `${seconds} s`
  const minutes = Math.floor(seconds / 60)
  return `${minutes}m ${String(seconds % 60).padStart(2, '0')}s`
}

/**
 * 一个明细在某项动作下的指标摘要（一行文字）。
 *
 * <p>不认识的键不显示：指标口径归执行侧（`TableOptimizerJob`）所有，
 * 那边加了一个新指标时，这里没更新的表现应该是「少显示一项」，
 * 而不是把 `some-new-key=42` 原样糊到页面上。
 */
export function metricSummary(item, action) {
  const values = actionMetrics(item, action)
  const parts = []
  if (action === 'compact') {
    if (values['files-before'] !== undefined || values['files-after'] !== undefined) {
      parts.push(`${values['files-before'] ?? '?'} → ${values['files-after'] ?? '?'} 个文件`)
    }
    if (values['size-before'] || values['size-after']) {
      parts.push(`${values['size-before'] ?? '?'} → ${values['size-after'] ?? '?'}`)
    }
  } else if (action === 'expire') {
    if (values['deleted-snapshots'] !== undefined) {
      parts.push(`删除 ${values['deleted-snapshots']} 个快照`)
    }
  } else if (action === 'orphan') {
    if (values['deleted-files'] !== undefined) {
      parts.push(`${values['deleted-files']} 个文件`)
    }
    if (values['released-size']) {
      parts.push(`释放 ${values['released-size']}`)
    }
  }
  // 执行侧的「什么都没做」原因（未达阈值、没有过期快照…）也在这里给一行
  if (values.reason) {
    parts.push(values.reason)
  }
  return parts.join(' · ')
}

/** 明细在某项动作下的耗时文字。 */
export function metricDuration(item, action) {
  return formatDurationMs(actionMetrics(item, action)['duration-ms'])
}

/**
 * 一个明细的指标摘要：它跑的每一项动作各给一段，用 ` · ` 连起来。
 * 批次明细表只有一行放得下，因此各项动作的读数在这里合并。
 */
export function itemSummary(item) {
  return actionsOf(item?.actions)
    .map((action) => metricSummary(item, action))
    .filter(Boolean)
    .join(' · ')
}

/** 一个明细的总耗时：各项动作的耗时之和（明细本身没有跨越动作的计时）。 */
export function itemDuration(item) {
  let total = 0
  let seen = false
  for (const action of actionsOf(item?.actions)) {
    const value = Number(actionMetrics(item, action)['duration-ms'])
    if (Number.isFinite(value)) {
      total += value
      seen = true
    }
  }
  return seen ? formatDurationMs(total) : '—'
}

/** 一个时间区间（批次耗时）：缺一端时按 `null` 处理，由调用方决定要不要补「至今」。 */
export function elapsedText(from, to) {
  const start = Number(from)
  const end = Number(to)
  if (!Number.isFinite(start) || start <= 0) return '—'
  if (!Number.isFinite(end) || end <= 0) return '—'
  return formatDurationMs(Math.max(0, end - start))
}

// ------------------------------------------------------------------ 「说明」列的溢出显示

/**
 * 「说明」列的溢出配置，`show-overflow-tooltip` 的取值，两处表格共用：
 * 表详情页的执行历史与「优化任务」页的批次明细。
 *
 * <p>这一列装的是错误信息，Spark 抛出来的动辄一两百字；任其换行，
 * 一条记录会被撑成四五行的区块，列表就失去「扫一眼看全部」的能力。
 * 因此固定单行、截断省略，完整内容靠悬浮气泡——`show-overflow-tooltip`
 * 只在内容真正溢出时才弹气泡，短错误与「—」占位不会被打扰。
 *
 * <p>用对象而不是裸 `true`：气泡默认不限宽，一条长错误会把气泡拉成
 * 横穿整屏的一条。`popperClass` 挂上 `.pc-cell-tip`（见 `styles/index.css`）
 * 限住宽度。气泡渲染在组件外，那条例必须放全局样式，组件内写不进去。
 */
export const OVERFLOW_TIP = { popperClass: 'pc-cell-tip' }
