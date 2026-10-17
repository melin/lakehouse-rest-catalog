/**
 * catalog 的**表格式**（`catalogFormat`）的读取、显示名与影响面说明。
 *
 * <p>它与 `catalog_type`（`INTERNAL` / `EXTERNAL`，托管模式）是两条正交的属性：
 * `EXTERNAL + ICEBERG`（外部 Iceberg 仓库的入口）是托管模式那个枚举表达不了的状态，
 * 因此服务端是两个列、两个字段（见 `docs/iceberg-rest-catalog-design.md` §3.1）。
 * 界面上也就要分两处显示：列表里「类型」列照旧是 INTERNAL/EXTERNAL，
 * 「表格式」是新增的一列。
 *
 * <p>**创建后不可变**（改格式 = 删除重建）：同一个 prefix 下两种格式的元数据代数、
 * 仓库里的物化产物、客户端可见的端点集合都不同。因此这个模块只提供「读」——
 * 没有 `set`、没有 `update`，编辑表单也不给入口。这里连一个可写的界面都用不上，
 * 少一个让人误以为能改的地方。
 *
 * <p>`format` 的取值只有两个，服务端的 `CatalogFormat` 是唯一权威：
 * `GET /api/console/v1/meta` 的 `enums.catalogFormats` 会把它下发过来，
 * 前端不另抄一份名单（抄了就会漂移）。本模块的 `CATALOG_FORMATS` 只是
 * **读不到元数据时**的兜底，与服务端枚举逐字一致，由
 * `scripts/verify-console.py` 双向比对。
 */

/** 兜底用的取值集合；正常路径上取 `meta.enums.catalogFormats`。 */
export const CATALOG_FORMATS = ['PAIMON', 'ICEBERG']

/** 两个取值各有一个名字：判断分支时用它，避免全文散落裸字符串。 */
export const PAIMON = 'PAIMON'
export const ICEBERG = 'ICEBERG'

/** 徽标上的显示名。首字母大写与官方写法一致（Paimon / Iceberg），取值本身仍是大写。 */
export const CATALOG_FORMAT_LABELS = {
  [PAIMON]: 'Paimon',
  [ICEBERG]: 'Iceberg',
}

/** 徽标颜色（el-tag 的 type）。两个值必须不同——这一列的作用就是一眼分得开。 */
export const CATALOG_FORMAT_TAG_TYPES = {
  [PAIMON]: 'info',
  [ICEBERG]: 'primary',
}

/**
 * 一句话影响面：选了这个格式，什么会不一样。
 *
 * <p>用在两处：新建对话框里选项下方那行说明、详情页的格式说明。
 * 写的是**客户端能看到什么**而不是内部实现（`/v1/{prefix}` 之后的路径形状），
 * 因为那句话要回答的是使用者真正会踩到的那个问题——「我该拿哪种客户端连它」。
 */
export const CATALOG_FORMAT_NOTES = {
  [PAIMON]:
    'Paimon REST 协议面：客户端走 /v1/{prefix}/databases/**，表有快照、标签、分支、'
    + '分区状态与数据访问；控制台的目录浏览与表详情就是按这一面做的。',
  [ICEBERG]:
    'Iceberg REST 协议面：客户端走 /v1/{prefix}/namespaces/**，表元数据以仓库里的 '
    + 'metadata.json 为准；没有 Paimon 的分区、消费者与语义视图。'
    + '控制台一期不浏览 Iceberg 表，库里与仓库里的文件仍可在详情页看到。',
}

/**
 * 取格式取值。
 *
 * <p>参数可以是 catalog 对象、也可以是取值字符串本身（两种情况在页面里都会出现：
 * 列表行有整个对象，而按 prefix 取格式时拿到的可能只是一个字符串）。
 *
 * <p>**认不出来时回落到 `PAIMON`**，与服务端 `CatalogFormat.of()` 同一个方向：
 * 这个值可能来自一行旧数据，而「按 Paimon 解释」是安全的那一侧——默认格式下的行为
 * 与本特性引入前完全一致。反过来把 Paimon 认成 Iceberg，会让这个 catalog 的界面
 * 藏掉一半能用的东西。
 */
export function catalogFormatOf(value) {
  const raw = typeof value === 'string' ? value : value?.catalogFormat
  const text = typeof raw === 'string' ? raw.trim().toUpperCase() : ''
  return CATALOG_FORMATS.includes(text) ? text : PAIMON
}

/** 取显示名；未知取值原样回显，不假装它是 Paimon。 */
export function catalogFormatLabel(value) {
  const format = catalogFormatOf(value)
  return CATALOG_FORMAT_LABELS[format] || format
}

/** 取徽标颜色类型。 */
export function catalogFormatTagType(value) {
  const format = catalogFormatOf(value)
  return CATALOG_FORMAT_TAG_TYPES[format] || 'info'
}

/** 取影响面说明。 */
export function catalogFormatNote(value) {
  const format = catalogFormatOf(value)
  return CATALOG_FORMAT_NOTES[format] || ''
}

/**
 * 新建表单里每个格式的选项卡片内容（名称、一句话说明、客户端路径形状）。
 *
 * <p>与 `CATALOG_FORMAT_NOTES` 分开：那份是**选中之后**「会有什么不一样」的长说明
 * （放在选项下方），这份是**选择之前**用来分辨两个选项的短信息。
 * 合成一份会让两个位置互相将就——要么卡片里塞进长段落，要么下面的说明只剩半句。
 *
 * <p>路径那一行是有用的：它回答的是「我该拿哪种客户端连它」，
 * 而这个问题的答案就是 prefix 后面那段路径的形状。
 */
export const CATALOG_FORMAT_OPTIONS = {
  [PAIMON]: {
    label: 'Paimon',
    desc: 'Apache Paimon REST Catalog：主键表、分支与标签、消费者位点、表存储优化。',
    path: '/v1/{prefix}/databases/**',
  },
  [ICEBERG]: {
    label: 'Iceberg',
    desc: 'Apache Iceberg REST Catalog（规格 35 个端点）：多级 namespace、视图与更新代数。',
    path: '/v1/{prefix}/namespaces/**',
  },
}

/**
 * 取选项卡片内容；取值不在表里时给一个不撒谎的兜底。
 *
 * <p>服务端以后多加一种格式、而前端还没跟上时，界面应当**照实把那个取值列出来**
 * （名称就是取值本身、说明为空），而不是把它渲染成 Paimon——后者建出来的 catalog
 * 与表单上看到的不一致。
 */
export function catalogFormatOption(value) {
  const raw = typeof value === 'string' ? value : value?.catalogFormat
  const text = typeof raw === 'string' && raw.trim() ? raw.trim().toUpperCase() : PAIMON
  return CATALOG_FORMAT_OPTIONS[text] || { label: text, desc: '', path: '' }
}

/** 是不是 Iceberg 协议面。页面里按它决定「哪些是 Paimon 专有的东西」。 */
export function isIcebergCatalog(value) {
  return catalogFormatOf(value) === ICEBERG
}
