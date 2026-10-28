/**
 * 表存储优化的展示知识与参数表单定义。
 *
 * <p>放在 utils 而不是各自视图里：表详情页的「存储优化」页签与全局「优化任务」页
 * 展示同一批动作、同一套状态取值与同一份指标口径，两处各写一份必然漂移——
 * 而漂移的表现是「同一张表在两个页面显示的参数不一样」，最难查的那类问题。
 *
 * <p><b>三种格式各一套参数。</b>动作名与执行顺序是格式无关的契约，参数不是：
 * Paimon 的 `retain-min`、Iceberg 的 `retain-last`、Lance 的 `target-rows-per-fragment`
 * 是口径完全不同的三套键，强行共用一套字段的后果不是报错，而是把 A 格式的参数写进
 * B 格式的策略里、保存时吃 400。因此这里按格式各列一份（{@link ACTIONS} /
 * {@link ICEBERG_ACTIONS} / {@link LANCE_ACTIONS}），取用一律走 {@link actionsFor}，
 * 页面不自己判断格式。
 *
 * <p><b>参数键名不是随便起的。</b>每一项动作允许出现的键由服务端的
 * `OptimizationOptions`（Paimon）、`IcebergOptimizationOptions`（Iceberg）与
 * `LanceOptimizationOptions`（Lance）定义，键名写错的表现是保存时吃一个 400
 * 「Unknown compact option」；而这类错字编译、类型检查都不会报。
 * `scripts/verify-console.py` 会拿这三份 `fields` 与服务端那三张表逐字比对，
 * 因此**加参数只改这一处**（服务端那边同步改，校验脚本会指出不一致）。
 *
 * <p>取值口径也要跟服务端的 `validate` 对齐（数字区间的上下限写在字段的
 * `min` / `max` 上，由输入控件挡住大部分非法值）：校验失败发生在**保存时**，
 * 而在表单上先拦住比让用户提交后看报错便宜。
 */

/**
 * 三项动作的展示元数据：名字、一句话说明、与执行顺序。
 *
 * <p>格式无关：三种表做的三件事一一对应（小文件合并 / 过期快照清理 / 孤儿文件清理），
 * 名字也一样，差别只在参数与默认值。把名字抄三份，迟早出现「同一张表在两个页面
 * 叫两个名字」。
 */
const ACTION_META = [
  { action: 'compact', label: 'Compaction', summary: '把小文件合并成更大的文件，提升查询性能' },
  { action: 'expire', label: 'Snapshot Expire', summary: '清理过期快照及其关联的数据文件，回收存储空间' },
  { action: 'orphan', label: 'Orphan File Remove', summary: '清理未被任何快照引用的废弃文件' },
]

/**
 * Paimon 表的参数表单。
 *
 * <p>键名由服务端 `OptimizationOptions.KNOWN_KEYS` 定义，写错的表现是保存时吃一个
 * 400「Unknown compact option」；`scripts/verify-console.py` 会拿这份定义与服务端那张表
 * 逐字比对，因此**加参数只改这一处**（服务端那边同步改，校验脚本会指出不一致）。
 *
 * <p>取值口径与 `OptimizationOptions.validate` 对齐：数字区间的上下限写在字段的
 * `min` / `max` 上，由输入控件挡住大部分非法值——校验失败发生在**保存时**，
 * 在表单上先拦住比让用户提交后看报错便宜。
 */
const PAIMON_FIELDS = {
  compact: [
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
  expire: [
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
  orphan: [
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
}

/**
 * Iceberg 表的参数表单。
 *
 * <p>键名由服务端的 `IcebergOptimizationOptions.KNOWN_KEYS` 定义（与 Paimon 那份是
 * **两套**：`retain-min` 换成 `retain-last`、`target-file-size-mb` 默认 512、
 * 多出 `max-concurrent-file-group-rewrites`）。两套键混用的后果不是报错而是
 * 保存时 400，因此这里按格式分开列，`actionsFor(format)` 是唯一的取用入口。
 *
 * <p>与 Paimon 侧的三处实质差异（都在文案里说清，因为用户会拿两张表对比）：
 *
 * - **zorder 是第三种策略**：Iceberg 没有独立的 zorder，它是 `strategy=sort` +
 *   `sort_order=zorder(...)`；控制台按「策略」这一档呈现，翻译在执行侧。
 * - **`retain-last` 数的是「最后几个快照」**，不是「最少保留几个」：语义相反的一对
 *   参数，取值 0 表示不强制保留（清空所有过期快照）。
 * - **孤儿清理默认干跑**，与 Paimon 侧一致，理由相同。
 */
const ICEBERG_FIELDS = {
  compact: [
    {
      key: 'strategy',
      label: '合并策略',
      type: 'select',
      choices: [
        { value: 'binpack', label: '小文件合并（Binpack）' },
        { value: 'sort', label: '排序合并（Sort）' },
        { value: 'zorder', label: '多维排序（Z-Order）' },
      ],
      tip: 'Binpack 只按大小合并；Sort 按指定列重排，适合单列/前缀过滤；'
        + 'Z-Order 让多个列在同一份数据里都保持局部有序，适合多列组合过滤——'
        + '代价是重写更慢，且列数越多收益越摊薄（一般 2～4 列）。',
    },
    {
      key: 'sort-columns',
      label: '排序列',
      type: 'text',
      placeholder: '如 dt,user_id',
      showWhen: (values) => values.strategy === 'sort' || values.strategy === 'zorder',
      tip: '排序与 Z-Order 都必填，逗号分隔。Z-Order 建议 2～4 列：'
        + '再多，每列的局部性都会被摊薄到接近没有。',
    },
    {
      key: 'min-input-files',
      label: '触发阈值',
      type: 'number',
      unit: '个文件',
      min: 1,
      max: 1000,
      tip: '一个文件组里的小文件数达到这个值才重写。Iceberg 按分区/文件组分别判断，'
        + '因此它是「每个组」的门槛，不是整张表的。',
    },
    {
      key: 'target-file-size-mb',
      label: '目标文件大小',
      type: 'number',
      unit: 'MB',
      min: 1,
      max: 8192,
      tip: '重写后单个文件的目标大小，默认 512 MB（Iceberg 自己的默认）。'
        + 'Iceberg 的扫描规划把一个文件当一个 split，文件太大反而会让并行度上不去；'
        + '小文件多则意味着读放大与元数据膨胀。',
    },
    {
      key: 'max-concurrent-file-group-rewrites',
      label: '并发重写组数',
      type: 'number',
      unit: '组',
      min: 1,
      max: 100,
      tip: '同时提交多少个文件组的重写。调大能缩短大表的整体耗时，代价是并发写'
        + '更容易撞上提交冲突（冲突会重试，不会丢数据，但会更慢）。',
    },
    {
      key: 'interval-hours',
      label: '执行间隔',
      type: 'number',
      unit: '小时',
      min: 1,
      max: 8760,
      tip: '两次 compaction 之间至少隔这么久。与「多久扫一次」是两件事：'
        + '扫描只是看看该不该做，执行间隔决定真的做多频繁。',
    },
  ],
  expire: [
    {
      key: 'retention-hours',
      label: '快照保留时长',
      type: 'number',
      unit: '小时',
      min: 1,
      max: 8760,
      tip: '早于这个时长的快照会被清掉（默认 5 天，与 Iceberg 的'
        + ' history.expire.max-snapshot-age-ms 一致）。它同时是「能回滚到多久之前」'
        + '的上限：快照没了，时间旅行查询也就没了。',
    },
    {
      key: 'retain-last',
      label: '保留最后快照数',
      type: 'number',
      unit: '个',
      min: 0,
      max: 10000,
      tip: '无论多旧，最近这么多个快照一定留下。注意它数的是「最后几个」，'
        + '与 Paimon 的「最少保留几个」不是同一个口径；填 0 表示不额外保留。',
    },
    {
      key: 'interval-hours',
      label: '执行间隔',
      type: 'number',
      unit: '小时',
      min: 1,
      max: 8760,
      tip: '快照清理每天一次足够：它要遍历快照历史与清单文件，'
        + '而收益（回收空间）不随时间线性增长。',
    },
  ],
  orphan: [
    {
      key: 'older-than-hours',
      label: '清理早于',
      type: 'number',
      unit: '小时前写入的文件',
      min: 1,
      max: 8760,
      tip: '清理窗口必须大于这张表的最大提交间隔，否则会把正在写入、'
        + '还没被任何快照引用的数据文件当成孤儿删掉。默认 72 小时是留够余量的取值。',
    },
    {
      key: 'interval-days',
      label: '执行间隔',
      type: 'number',
      unit: '天',
      min: 1,
      max: 365,
      tip: '三类动作里最贵的一项（要列举整个表目录并逐个比对元数据），按周执行足够。',
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
}

/**
 * Lance 表的参数表单。
 *
 * <p>键名由服务端的 `LanceOptimizationOptions.KNOWN_KEYS` 定义，与前两份**没有一处重合**：
 * Lance 的「目标大小」是**行数**不是字节（`target-rows-per-fragment`）、没有「至少几个
 * 小文件才合并」这个门槛、也没有「最少保留几个版本」。把 Paimon 那份直接复用的后果比
 * 另两种格式的混用更糟：`min-input-files`、`retain-min`、`dry-run` 都会在保存时被
 * Lance 的键表判成未知键，一次 400 之后表单还是原地不动——用户看到的是一张
 * 「怎么填都存不下」的表。
 *
 * <p>两处与另两种格式**结构上不同**，都在这里说清（用户会拿三张表对比）：
 *
 * - **只有两项动作。** `orphan` 在这张格式上是空数组：`VACUUM` 一步就把过期版本与
 *   孤儿文件一起做了。空数组而不是不给这一项——动作名是格式无关的契约，缺一项会让
 *   「同一张表在两个页面不一样」。那张卡由 `supported=false` 置灰（见
 *   {@link withFields} 的第二个参数），原因文案来自服务端，不在前端写死。
 * - **没有干跑。** `VACUUM` 删掉的版本无法恢复，因此没有 `dry-run` 这个档位；
 *   补偿是「保留窗口默认 72 小时」与保存前的一句不可逆确认（见 `TableOptimizationPanel`
 *   的 `confirmIrreversible`），不是在表单里给一个看起来能反悔的开关。
 *
 * <p>`delete-unverified` 是一个**只接受一个取值**的键：`true` 的语义是「删掉验证不了
 * 引用关系的文件」，那是「反正我要腾空间」的场景，而我们是定期维护。因此它照常出现在
 * 表单里（服务端的键表要求两处一致），但开关锁在 `false` 上（{@link #LOCKED_NOTE}），
 * 不给人一个点了就 400 的按钮。
 */
const LANCE_FIELDS = {
  compact: [
    {
      key: 'target-rows-per-fragment',
      label: '目标碎片行数',
      type: 'number',
      unit: '行',
      min: 1000,
      max: 100000000,
      tip: 'Lance 的「目标大小」按**行数**算，不是字节数——它的碎片（fragment）是行组'
        + '之上的逻辑划分，文件大小由编码方式与列宽共同决定。默认 1048576 就是 Lance '
        + '自己的默认值，改动它要同时想清楚「碎片太小 → 文件多、读放大」与'
        + '「碎片太大 → 合并一次太久、删除标记摊不薄」两头。',
    },
    {
      key: 'max-rows-per-group',
      label: '每组行数',
      type: 'number',
      unit: '行',
      min: 64,
      max: 1000000,
      tip: '碎片内部 Row Group 的行数，决定随机读与谓词下推的粒度。默认 1024 与 Lance '
        + '自身一致；给另一个数字会让「Spark 合并出来的」与「Lance 自己写出来的」'
        + '产出不同形状的文件。',
    },
    {
      key: 'materialize-deletions',
      label: '物化删除',
      type: 'switch',
      on: '把软删除的行真正重写掉',
      off: '保留删除标记，只合并碎片',
      tip: '关闭时只合并碎片、不动删除标记：查询仍要读删除文件来过滤，但合并更快、'
        + '不额外占空间峰值。开启后删除的行在物理上消失，读变快、空间回收，代价是重写量更大。',
    },
    {
      key: 'materialize-deletions-threshold',
      label: '物化阈值',
      type: 'number',
      unit: '（删除比例）',
      min: 0,
      max: 1,
      step: 0.01,
      precision: 2,
      tip: '删除行的比例达到这个值才物化，0.1 表示 10%。填 1 等于关闭——'
        + '分区里几乎没有活行时也不重写，避免为一点数据付整块重写的代价。',
    },
    {
      key: 'defer-index-remap',
      label: '延迟索引重映射',
      type: 'switch',
      on: '提交时只记一层映射，稍后再重映射',
      off: '提交时立即重映射索引',
      tip: '开启能缩短提交时间（大表上索引重映射比合并本身还久），代价是索引在一段时间里'
        + '指向旧位置，期间按索引读会慢一点。频繁合并的表值得打开。',
    },
    {
      key: 'batch-size',
      label: '扫描批次大小',
      type: 'number',
      unit: '行',
      min: 1,
      max: 1000000,
      tip: '默认**不传**（执行侧用写入侧的默认值）。只在合并大表时 OOM 才需要调小：'
        + '给一个值会让输出按字节截断，与「目标碎片行数」一起用容易产出'
        + '「行数没到但文件满了」的碎片，下一轮又成为候选。',
    },
    {
      key: 'interval-hours',
      label: '执行间隔',
      type: 'number',
      unit: '小时',
      min: 1,
      max: 8760,
      tip: '两次合并之间至少隔这么久。与「多久扫一次」是两件事：扫描只是看看该不该做，'
        + '执行间隔决定真的做多频繁。',
    },
  ],
  expire: [
    {
      key: 'retention-hours',
      label: '版本保留时长',
      type: 'number',
      unit: '小时',
      min: 1,
      max: 8760,
      tip: '早于这个时长的版本会被删掉——**不可撤销**，时间旅行的窗口随之变短。'
        + '默认 72 小时不是随手取的：它必须远大于这张表的最大提交间隔，否则会把'
        + '正在写入、还没被新版本引用的文件当成过期删掉。catalog 侧有一个'
        + '「最大提交间隔」（默认 24 小时）的下限，填得比它小会被服务端抬高。',
    },
    {
      key: 'delete-unverified',
      label: '删除无法校验的文件',
      type: 'switch',
      on: '删除引用关系验证不了的文件',
      off: '只删能验证引用的文件',
      disabled: true,
      tip: '只接受「否」，服务端会拒掉 true。它的语义是「删掉验证不了引用关系的文件」——'
        + '那是「反正我要腾空间」的场景，而定期维护没有理由承担这个风险。'
        + '因此这一项在表单里锁死，只作为「执行时用的是哪个值」的说明。',
    },
    {
      key: 'interval-days',
      label: '执行间隔',
      type: 'number',
      unit: '天',
      min: 1,
      max: 365,
      tip: '清理版本要读 manifest 判断引用关系，是两项动作里更贵的那一项，'
        + '而回收空间不随时间线性增长；每周一次足够。',
    },
  ],
  // 空集：这一项在这张格式上不存在（VACUUM 已包含），见上面的类注释
  orphan: [],
}

/**
 * 把元数据与某一套字段拼成页面直接消费的列表（顺序 = 执行顺序）。
 *
 * <p>第二个参数是「这套格式上不存在的动作」。它只是**首帧的静态兜底**——卡片真正
 * 置灰与否以服务端响应里的 `ActionPolicy.supported` 为准（原因文案也在那边，
 * 前端不抄第二份）。但 Lance 的 `orphan` 是定义上的不存在，静态知道它没有坏处：
 * 响应回来之前那一帧、或接口降级时，卡片也不会先闪一下「可以勾选」。
 */
function withFields(fields, absent = []) {
  return ACTION_META.map((meta) => ({
    ...meta,
    fields: fields[meta.action] || [],
    supported: !absent.includes(meta.action),
  }))
}

/** Paimon 表的动作与参数定义。保持原名：跨格式的页面（批次页）只读它的 `label`。 */
export const ACTIONS = withFields(PAIMON_FIELDS)

/** Iceberg 表的动作与参数定义。 */
export const ICEBERG_ACTIONS = withFields(ICEBERG_FIELDS)

/** Lance 表的动作与参数定义。 */
export const LANCE_ACTIONS = withFields(LANCE_FIELDS, ['orphan'])

/**
 * 按 catalog 格式取动作定义。`format` 是服务端 `TablePolicy.format` 的原值
 * （`PAIMON` / `ICEBERG` / `LANCE`）；缺省与认不出的取值都按 Paimon——与服务端
 * `CatalogFormat.of` 的回落方向一致。
 *
 * <p><b>必须写成三个显式的分支。</b>这里原来是
 * `format === 'ICEBERG' ? ICEBERG_ACTIONS : ACTIONS`——加了第三种格式之后，Lance
 * 会安静地落到 Paimon 那一支：表单里多出 `min-input-files` / `retain-min` /
 * `dry-run`，少了 `target-rows-per-fragment`，而用户填完保存时被服务端用 Lance 的
 * 键表校验、吃一个 400。这是**前端版的 `isIceberg()` 陷阱**，与执行侧那个布尔
 * 是同一类错：二元的默认分支在第三种取值出现时不再是「另一侧」，而是「错误答案」。
 */
export function actionsFor(format) {
  if (format === 'ICEBERG') return ICEBERG_ACTIONS
  if (format === 'LANCE') return LANCE_ACTIONS
  return ACTIONS
}


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
 * <p><b>三套指标键，一个函数。</b>执行侧按格式报的是**不同**的列（Iceberg 的
 * `rewrite_data_files` 不给压缩前后的文件数、`expire_snapshots` 不给「删了几个快照」；
 * Lance 的 `OPTIMIZE` / `VACUUM` 是增量而非快照，连「压缩前后」都没有），
 * 因此这段不判格式、只判键存在：三套键在同一动作下互不重叠，谁在就说谁。
 * 这样批次页（一批里可能混着三种格式的表）也不需要知道每张表的格式。
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
    // Iceberg：重写了几个、新增了几个（重写前后总量由这两个数与目标大小共同决定，
    // procedure 本身不报「合并后一共几个文件」）
    if (values['rewritten-files'] !== undefined || values['added-files'] !== undefined) {
      parts.push(`重写 ${values['rewritten-files'] ?? '?'} → 新增 ${values['added-files'] ?? '?'} 个文件`)
    }
    if (values['rewritten-size']) {
      parts.push(`写入 ${values['rewritten-size']}`)
    }
    // Lance：OPTIMIZE 只报增量（减了几个碎片、加了几个），因此这里是「−8 / +2」
    // 而不是另两种格式的「12 → 4」——不是忘了对齐，是这个语句没有给压缩后的总量
    if (values['fragments-removed'] !== undefined || values['fragments-added'] !== undefined) {
      parts.push(`碎片 −${values['fragments-removed'] ?? '?'} / +${values['fragments-added'] ?? '?'}`)
    }
    if (values['files-removed'] !== undefined || values['files-added'] !== undefined) {
      parts.push(`文件 −${values['files-removed'] ?? '?'} / +${values['files-added'] ?? '?'}`)
    }
  } else if (action === 'expire') {
    if (values['deleted-snapshots'] !== undefined) {
      parts.push(`删除 ${values['deleted-snapshots']} 个快照`)
    }
    // Iceberg：删的是数据文件与清单文件，不是「快照」这个对象
    if (values['deleted-data-files'] !== undefined) {
      parts.push(`删除 ${values['deleted-data-files']} 个数据文件`)
    }
    const metadata = Number(values['deleted-manifest-files'] || 0)
      + Number(values['deleted-manifest-lists'] || 0)
    if (metadata > 0) {
      parts.push(`${metadata} 个清单文件`)
    }
    if (values['deleted-statistics-files'] !== undefined) {
      parts.push(`${values['deleted-statistics-files']} 个统计文件`)
    }
    // Lance：VACUUM 报的是「删了几个旧版本」与「释放了多少字节」——
    // 版本（version）是 Lance 的一等对象，与 Paimon 的快照不是同一个词
    if (values['old-versions'] !== undefined) {
      parts.push(`清理 ${values['old-versions']} 个版本`)
    }
    if (values['bytes-removed']) {
      parts.push(`释放 ${values['bytes-removed']}`)
    }
  } else if (action === 'orphan') {
    if (values['deleted-files'] !== undefined) {
      // 干跑时这个数就是「候选清单条数」，执行侧不区分——所以文案不写「已删除」
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
