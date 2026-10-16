/**
 * 表格式（`schema.options.type`）的读取与显示名。
 *
 * <p>类型写在 schema 的 `options.type` 里，取值就是 Paimon `CoreOptions.TYPE` 的那几个字符串
 * （`table` / `format-table` / `object-table` / `lance-table`，语义见 `docs/catalog-api-endpoints.md`
 * 的「建表」一节）。Paimon 表**没有**这个键——服务端把缺省当 Paimon 表，
 * 因此这里也把「没有 type」读成 Paimon 表。
 *
 * <p>两个页面共用这一份：目录浏览的列表要显示表格式，详情页要按它伸缩页签。
 *
 * <p>`lance-table` 留在表里，虽然控制台的建表对话框已经不提供这个选项（见 `BrowseView`
 * 的 `TABLE_KINDS`）：Lance 表还能从 HTTP 或 Spark SQL 建出来，认不出它就会被当成 Paimon 表，
 * 于是列表显示错格式、详情页还会去问快照端点再报错。
 */

export const TABLE_KIND_LABELS = {
  'format-table': 'Format 表',
  'object-table': 'Object 表',
  'lance-table': 'Lance 表',
}

/** 取表格式取值；Paimon 表返回 `'table'`（即使 options 里没有这个键）。 */
export function tableKindOf(table) {
  const type = table?.schema?.options?.type
  return typeof type === 'string' && type.trim() ? type.trim().toLowerCase() : 'table'
}

/** 取表格式的显示名。 */
export function tableKindLabel(table) {
  return TABLE_KIND_LABELS[tableKindOf(table)] || 'Paimon 表'
}

/**
 * 是否受快照管理——只有 Paimon 表是。
 *
 * <p>三种非 Paimon 表都没有快照：Format 表读目录里的既有文件，Object 表暴露对象的元数据，
 * Lance 表是 Lance 数据集。因此快照 / 标签 / 分支 / 分区状态 / 提交累计的文件统计对它们都不适用。
 */
export function isSnapshotManaged(table) {
  return !TABLE_KIND_LABELS[tableKindOf(table)]
}
