package io.github.melin.lakehouse.rest.spark

import java.util.{ArrayList, List => JavaList, Map => JavaMap}

import io.github.melin.lakehouse.rest.spark.client.ManagementApiClient
import org.apache.spark.sql.Row
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.expressions.AttributeReference

import java.time.Instant

/**
 * 数据策略管理语句的命令（§13.2）。与主体 / 授权命令同一形态：
 * {@code RunnableCommand}，{@code run} 在分析阶段调用 REST 端点，会话里不存任何策略状态。
 *
 * <p><b>每条写语句都是「GET → 修改 → PUT」</b>：PUT 的语义是整份替换，所以没被
 * 这条语句触碰的部分（另一侧的表达式、豁免名单、主体白名单）必须从上一次读到的
 * 详情里原样带回。{@code baseVersion} 用读到的版本做乐观锁——两个会话同时 ALTER，
 * 后提交者 409，SQL 不自动重试（§13.6：静默重试可能把别人刚删的条件加回来）。
 *
 * <p><b>报错而不是 no-op</b>（§13.3）：DROP ROW FILTER / DROP MASK / SET POLICY FOR
 * 在表上没有策略、或列没有 mask 时直接失败——「以为删过」比「明确失败」危险。
 */
private[spark] object PolicySqlCommands {

  /** DROP POLICY 的防呆开关（§13.3）：物理删除、不可恢复，缺省拒绝。 */
  val CONFIRM_DESTRUCTIVE = "spark.lakehouse.rest.sql.confirm-destructive"

  private[spark] def confirmDestructive(sparkSession: SparkSession): Boolean =
    "true".equalsIgnoreCase(sparkSession.conf.get(CONFIRM_DESTRUCTIVE, "false"))

  private[spark] def requirePolicy(detail: ManagementApiClient.PolicyDetailInfo, catalog: String, database: String,
                            table: String, statement: String): ManagementApiClient.PolicyDetailInfo =
    if (detail == null) {
      throw new ManagementSqlException(
        s"no data policy on table $catalog.$database.$table — $statement is an error" +
          " rather than a no-op, so that 'I thought I dropped it' never happens (design §13.3)")
    } else {
      detail
    }

  private[spark] def baseVersion(detail: ManagementApiClient.PolicyDetailInfo): Integer =
    if (detail == null) null else Integer.valueOf(detail.version())

  private[spark] def enabled(detail: ManagementApiClient.PolicyDetailInfo): java.lang.Boolean =
    if (detail == null) null else java.lang.Boolean.valueOf(detail.enabled())

  private[spark] def maskUpserts(detail: ManagementApiClient.PolicyDetailInfo): JavaList[ManagementApiClient.MaskUpsertInfo] = {
    val out = new ArrayList[ManagementApiClient.MaskUpsertInfo]()
    if (detail != null) {
      detail.masks().forEach { mask =>
        out.add(new ManagementApiClient.MaskUpsertInfo(mask.column(), mask.mask(),
          java.lang.Boolean.valueOf(mask.allowNullOnRequired())))
      }
    }
    out
  }

  private[spark] def exempt(detail: ManagementApiClient.PolicyDetailInfo): JavaList[String] =
    if (detail == null) new ArrayList[String]() else detail.exemptPrincipals()

  private[spark] def subjects(detail: ManagementApiClient.PolicyDetailInfo): JavaList[String] =
    if (detail == null) new ArrayList[String]() else detail.subjects()

  private[spark] def nowIso(millis: Long): String =
    if (millis <= 0) "" else Instant.ofEpochMilli(millis).toString
}

private[spark] case class SetRowFilterCommand(
    catalog: String,
    database: String,
    table: String,
    rowFilter: JavaMap[String, Object],
    subjects: JavaList[String],
    client: ManagementApiClient) extends ManagementCommand {

  override val output: Seq[AttributeReference] = Seq.empty

  override def run(sparkSession: SparkSession): Seq[Row] = {
    val detail = client.getPolicy(catalog, database, table)
    client.putPolicy(catalog, database, table, PolicySqlCommands.baseVersion(detail),
      PolicySqlCommands.enabled(detail), rowFilter, PolicySqlCommands.maskUpserts(detail),
      PolicySqlCommands.exempt(detail), subjects)
    Seq.empty
  }
}

private[spark] case class DropRowFilterCommand(
    catalog: String,
    database: String,
    table: String,
    client: ManagementApiClient) extends ManagementCommand {

  override val output: Seq[AttributeReference] = Seq.empty

  override def run(sparkSession: SparkSession): Seq[Row] = {
    val detail = PolicySqlCommands.requirePolicy(
      client.getPolicy(catalog, database, table), catalog, database, table, "DROP ROW FILTER")
    client.putPolicy(catalog, database, table, PolicySqlCommands.baseVersion(detail),
      PolicySqlCommands.enabled(detail), null, PolicySqlCommands.maskUpserts(detail),
      PolicySqlCommands.exempt(detail), PolicySqlCommands.subjects(detail))
    Seq.empty
  }
}

private[spark] case class SetColumnMaskCommand(
    catalog: String,
    database: String,
    table: String,
    column: String,
    mask: JavaMap[String, Object],
    allowNullOnRequired: java.lang.Boolean,
    subjects: JavaList[String],
    client: ManagementApiClient) extends ManagementCommand {

  override val output: Seq[AttributeReference] = Seq.empty

  override def run(sparkSession: SparkSession): Seq[Row] = {
    val detail = client.getPolicy(catalog, database, table)
    val masks = PolicySqlCommands.maskUpserts(detail)
    // 跨语句重复 SET = 覆盖（§13.3）；同一语句内两次 SET 在语法上就装不下
    masks.removeIf(existing => column == existing.column())
    masks.add(new ManagementApiClient.MaskUpsertInfo(column, mask, allowNullOnRequired))
    client.putPolicy(catalog, database, table, PolicySqlCommands.baseVersion(detail),
      PolicySqlCommands.enabled(detail), if (detail == null) null else detail.rowFilter(),
      masks, PolicySqlCommands.exempt(detail), subjects)
    Seq.empty
  }
}

private[spark] case class DropColumnMaskCommand(
    catalog: String,
    database: String,
    table: String,
    column: String,
    client: ManagementApiClient) extends ManagementCommand {

  override val output: Seq[AttributeReference] = Seq.empty

  override def run(sparkSession: SparkSession): Seq[Row] = {
    val detail = PolicySqlCommands.requirePolicy(
      client.getPolicy(catalog, database, table), catalog, database, table, "DROP MASK")
    val masks = PolicySqlCommands.maskUpserts(detail)
    val removed = masks.removeIf(existing => column == existing.column())
    if (!removed) {
      throw new ManagementSqlException(
        s"column $column has no mask on table $catalog.$database.$table —" +
          " DROP MASK is an error rather than a no-op (design §13.3)")
    }
    client.putPolicy(catalog, database, table, PolicySqlCommands.baseVersion(detail),
      PolicySqlCommands.enabled(detail), detail.rowFilter(), masks,
      PolicySqlCommands.exempt(detail), PolicySqlCommands.subjects(detail))
    Seq.empty
  }
}

private[spark] case class SetPolicySubjectsCommand(
    catalog: String,
    database: String,
    table: String,
    subjects: JavaList[String],
    client: ManagementApiClient) extends ManagementCommand {

  override val output: Seq[AttributeReference] = Seq.empty

  override def run(sparkSession: SparkSession): Seq[Row] = {
    // 「只有主体范围」不构成策略（§13.3）：没有策略时这条语句没有意义
    val detail = PolicySqlCommands.requirePolicy(
      client.getPolicy(catalog, database, table), catalog, database, table, "SET POLICY FOR")
    client.putPolicy(catalog, database, table, PolicySqlCommands.baseVersion(detail),
      PolicySqlCommands.enabled(detail), detail.rowFilter(), PolicySqlCommands.maskUpserts(detail),
      PolicySqlCommands.exempt(detail), subjects)
    Seq.empty
  }
}

private[spark] case class DropPolicyCommand(
    catalog: String,
    database: String,
    table: String,
    client: ManagementApiClient) extends ManagementCommand {

  override val output: Seq[AttributeReference] = Seq.empty

  override def run(sparkSession: SparkSession): Seq[Row] = {
    // 会话级防呆（§13.3）：物理删除、不可恢复，而 SQL 里没法优雅地二次确认——
    // 把确认写进会话变量，比隐藏开关诚实。开关在 run 时读，两条语句之间可以改。
    if (!PolicySqlCommands.confirmDestructive(sparkSession)) {
      throw new ManagementSqlException(
        s"DROP POLICY requires the session property ${PolicySqlCommands.CONFIRM_DESTRUCTIVE}" +
          "=true (design §13.3) — set it explicitly, or drop the policy from the console")
    }
    val detail = PolicySqlCommands.requirePolicy(
      client.getPolicy(catalog, database, table), catalog, database, table, "DROP POLICY")
    client.deletePolicy(catalog, database, table)
    Seq.empty
  }
}

private[spark] case class ShowPolicyCommand(
    catalog: String,
    database: String,
    table: String,
    client: ManagementApiClient) extends ManagementCommand {

  private[spark] val attrs: Seq[(String, Boolean)] = Seq(
    ("version", false), ("subjects", true), ("row_filter", true), ("masked_columns", true),
    ("allow_null_confirmed", true), ("updated_by", true), ("updated_at", true))

  override val output: Seq[AttributeReference] =
    attrs.map { case (name, nullable) =>
      ManagementCommands.attr(name, nullable)
    }

  override def run(sparkSession: SparkSession): Seq[Row] = {
    // 表上没有策略时返回 0 行，不报错（§13.3）
    val detail = client.getPolicy(catalog, database, table)
    if (detail == null) {
      Seq.empty
    } else {
      val subjects =
        if (detail.subjects().isEmpty) "" else String.join(", ", detail.subjects())
      val maskedNames = new ArrayList[String]()
      var allowNullConfirmed = false
      detail.masks().forEach { mask =>
        maskedNames.add(mask.column())
        allowNullConfirmed = allowNullConfirmed || mask.allowNullOnRequired()
      }
      Seq(Row(String.valueOf(detail.version()), subjects,
        if (detail.rowFilterSql() == null) "" else detail.rowFilterSql(),
        String.join(", ", maskedNames), String.valueOf(allowNullConfirmed),
        detail.updatedBy(), PolicySqlCommands.nowIso(detail.updatedAt())))
    }
  }
}
