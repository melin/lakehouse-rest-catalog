package io.github.melin.lakehouse.rest.spark.lineage

import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.catalog.{CatalogTable, HiveTableRelation}
import org.apache.spark.sql.catalyst.expressions.Attribute
import org.apache.spark.sql.catalyst.plans.logical.{LocalRelation, LogicalPlan, OneRowRelation}
import org.apache.spark.sql.execution.datasources.LogicalRelation
import org.apache.spark.sql.connector.catalog.Identifier
import org.apache.spark.sql.execution.datasources.v2.{DataSourceV2Relation, DataSourceV2ScanRelation}

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * 从 Spark 的引擎对象取「表名」，归一化成 `{catalog}.{namespace...}.{table}`（§5.6）。
 *
 * <p><b>为什么单独一个对象。</b>这段逻辑被四处用到（源表收集、目标表、`~file` 兜底、
 * 服务端同一套规则的对照），而它的每条规则都对应一次「不这么写就错」的踩坑：
 * 少补 catalog 会把两个 catalog 下的同名表焊成一条边；把小写化了会替引擎做决定。
 *
 * <p><b>三条规则</b>：
 *
 * <ol>
 *   <li><b>catalog 必填。</b>V2 表取 `catalog.name()`（就是用户在 `spark.sql.catalog.<name>`
 *       里写的那个名字，与服务端的 prefix 同名）；V1 表没有 catalog 概念，取会话默认
 *       catalog（`spark.sql.defaultCatalog`）。</li>
 *   <li><b>大小写原样保留。</b>`Attribute#name` 与 `Identifier#name` 已经是按引擎规则
 *       折叠过的真实名字，再折一次等于替引擎做决定。</li>
 *   <li><b>`~` 是保留前缀。</b>`~values` / `~file:<hash>` / `~external:<provider>`
 *       都点不开，服务端对它们不判权、建表时也拒绝同名。</li>
 * </ol>
 */
private[lineage] object LineageNames {

  /** 纯常量源（`VALUES` / 常量 `SELECT`）共用一个节点。 */
  val VALUES: String = "~values"

  /** 外部连接器表：`spark.read.format(...)` 读出来、没有 `identifier` 的关系。 */
  def external(provider: String): String = "~external:" + provider

  /** 裸路径写入：`save("/tmp/x")` 这类没有 catalog 表的目标。 */
  def file(path: String): String = "~file:" + sha256(path).take(12)

  /** `~` 开头的节点不判权，因为它在目录里点不开（§6.5）。 */
  def isExternal(ref: String): Boolean = ref != null && ref.startsWith("~")

  /** 三段名拼接：namespace 可能是多级（Paimon 的多级 namespace 用点号拼成一段）。 */
  def qualify(catalog: String, namespace: Seq[String], table: String): String =
    (namespace.filter(_.nonEmpty) :+ table).mkString(catalog + ".", ".", "")

  /** V2 标识符 → ref。 */
  def refOf(identifier: Identifier, catalog: String): String =
    qualify(catalog, identifier.namespace().toSeq, identifier.name())

  /**
   * V1 的 `TableIdentifier` → ref。
   *
   * <p>`catalog` 为 `None` 是常态（`spark_catalog` 的表都是 V1）：那时用会话默认 catalog，
   * 与服务端 `NamespacePaths` 的口径一致。
   */
  def refOf(identifier: TableIdentifier, defaultCatalog: String, currentDatabase: String): String = {
    val catalog = identifier.catalog.getOrElse(defaultCatalog)
    val database = identifier.database.getOrElse(currentDatabase)
    qualify(catalog, Seq(database), identifier.table)
  }

  /** V1 关系上的 `CatalogTable`。 */
  def refOfCatalogTable(table: CatalogTable, defaultCatalog: String, currentDatabase: String): String =
    refOf(table.identifier, defaultCatalog, currentDatabase)

  /**
   * 一个逻辑计划节点如果是「可以点开的表」，返回它的 ref。
   *
   * <p>返回 `None` 不等于「不收集任何东西」：`LocalRelation` 给 `~values`，
   * `OneRowRelation` 刻意不给（它只影响行数，不构成依赖，见 §5.6）。
   */
  def refOfRelation(plan: LogicalPlan, defaultCatalog: String, currentDatabase: String): Option[String] =
    plan match {
      case r: DataSourceV2Relation =>
        r.identifier match {
          case Some(id) => Some(refOf(id, r.catalog.map(_.name()).getOrElse(defaultCatalog)))
          // 没有 identifier 的 V2 关系是 `spark.read.format(...)` 读出来的：
          // 点不开，但「依赖了一个外部源」必须留下，否则这条链在这里断掉（§5.6）
          case None => Some(external(tableNameOf(r)))
        }
      case r: DataSourceV2ScanRelation =>
        refOfRelation(r.relation, defaultCatalog, currentDatabase)
      case r: LogicalRelation =>
        r.catalogTable match {
          case Some(table) => Some(refOfCatalogTable(table, defaultCatalog, currentDatabase))
          // V1 的裸路径读取（`spark.read.parquet("/x")`）：同样点不开
          case None => Some("~external:hadoopFs")
        }
      case r: HiveTableRelation =>
        Some(refOfCatalogTable(r.tableMeta, defaultCatalog, currentDatabase))
      case _: LocalRelation => Some(VALUES)
      case _: OneRowRelation => None
      case _ => None
    }

  /** V2 表的数据源名。取不到时给 `unknown`——宁可粗一点，也不要因为一个名字把整条 run 丢掉。 */
  private def tableNameOf(relation: DataSourceV2Relation): String =
    try {
      Option(relation.table.name()).filter(_.nonEmpty).getOrElse("unknown")
    } catch {
      case _: Throwable => "unknown"
    }

  /** 表级边/节点的 kind：`~` 开头是 `EXTERNAL`，其余是 `TABLE`。 */
  def kindOf(ref: String): String = if (isExternal(ref)) "EXTERNAL" else "TABLE"

  private def sha256(value: String): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8))
    val sb = new StringBuilder(bytes.length * 2)
    bytes.foreach(b => sb.append(f"${b & 0xff}%02x"))
    sb.toString()
  }

  /** 列的占位名（`__count__` / `__subquery__` / `__local__`）不落库，一律判成 `UNRESOLVED`（§3.5）。 */
  def isPlaceholderColumn(attribute: Attribute): Boolean = attribute.name.startsWith("__")
}
