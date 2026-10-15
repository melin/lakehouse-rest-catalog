package io.github.melin.paimonrest.spark

import io.github.melin.paimonrest.spark.client.{ManagementApiClient, ManagementApiException}
import org.apache.spark.sql.Row
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.expressions.AttributeReference
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.execution.command.RunnableCommand
import org.apache.spark.sql.types.StringType

import java.time.format.DateTimeParseException
import java.time.{Instant, LocalDate, LocalTime, ZoneId}
import java.util.LinkedHashMap
import scala.collection.JavaConverters._

/**
 * 全部管理命令的公共基类。
 *
 * <p><b>存在的理由。</b>Spark 3.5 的 `RunnableCommand` 只是一个 trait，它继承了
 * `LogicalPlan`，但没有实现 `TreeNode` 的抽象方法 `withNewChildrenInternal`；
 * 因此直接 `extends RunnableCommand` 的类会被编译器判定为抽象类（报错形如
 * 「class XxxCommand needs to be abstract, since method withNewChildrenInternal
 * in class TreeNode ... is not defined」）。
 *
 * <p>管理命令全部是叶子节点——它们不持有任何子计划，只把语句参数和 REST 客户端
 * 带在身上。这里的实现直接返回自身：即使 Spark 在优化阶段用「新旧子节点」的
 * 方式重建节点（`TreeNode` 的通用遍历机制），也不会有子节点被替换掉。
 *
 * <p>之所以不在每个命令里重复这段代码，是因为 13 个命令的行为完全一致，
 * 分散实现只会给后续新增语句留下漏写的口子。
 */
private[spark] trait ManagementCommand extends RunnableCommand {

  override protected def withNewChildrenInternal(
      newChildren: IndexedSeq[LogicalPlan]): LogicalPlan = this
}

/**
 * 管理语句对应的可执行命令。
 *
 * <p>每个命令实现成 {@link RunnableCommand}：`run` 在分析阶段被调用一次，
 * 返回的 `Seq[Row]` 成为查询结果，因此 `spark.sql("SHOW PRINCIPALS").show()` 能直接看到表格。
 *
 * <p><b>结果集的两种形态。</b>
 *   - 读类语句（SHOW）与 `CREATE PRINCIPAL` 返回数据行，其中 `CREATE PRINCIPAL` 的
 *     明文密钥只在这一次结果里出现，必须用 `show()` / `collect()` 取走，否则只能靠
 *     `ROTATE PRINCIPAL` 重新签发——这一点与服务端「不保存明文」的约定一致；
 *   - 写类语句（CREATE / DROP / ALTER / GRANT / REVOKE）返回 0 行，
 *     与 Spark 原生 `CREATE TABLE` 这类命令的行为保持一致，便于脚本编排。
 *
 * <p>命令不持有 SparkSession 状态，客户端在构造时注入，因此同一个解析器实例可以
 * 服务同一会话内的多条语句。
 */
private[spark] object ManagementCommands {

  /** 结果集的一列：列名 + 字符串类型。所有列都是可空字符串，唯一例外见各命令的 output。 */
  def attr(name: String, nullable: Boolean = true): AttributeReference =
    AttributeReference(name, StringType, nullable)()

  /** Scala Map → Java Map，避免依赖 Scala 版本的隐式转换。 */
  def toJavaMap(properties: Map[String, String]): java.util.Map[String, String] = {
    val result = new LinkedHashMap[String, String]()
    properties.foreach { case (key, value) => result.put(key, value) }
    result
  }

  /**
   * 授权有效期的最后一个瞬间：`23:59:59.999`。
   *
   * <p>用毫秒而不是 `LocalTime.MAX`（纳秒级）：权限管理页写的就是毫秒，
   * 两侧落库的值应当逐字相同，否则同一条授权经页面授与经 SQL 授在清单里看起来是两个值。
   */
  private val LAST_MOMENT_OF_DAY: LocalTime = LocalTime.of(23, 59, 59, 999000000)

  /**
   * 把脚本里的有效期写成服务端要的 ISO-8601 instant。
   *
   * <p>两种写法都收：`'2026-11-30'` 按**当天 23:59:59.999 本地时区**换算——
   * 「到 11 月 30 日」应当是 30 日整日有效，而不是 30 日零点失效；完整 instant
   * （`'2026-11-30T15:59:59.999Z'`）原样使用。
   *
   * <p>换算放在解析期：写错日期格式的语句在发出任何请求之前就失败，
   * 与「形状非法不发请求」的既有约定一致。
   */
  def expireInstant(raw: String): String = {
    val text = if (raw == null) "" else raw.trim
    parseInstant(text)
      .orElse(parseDate(text))
      .getOrElse(throw new ManagementSqlException(
        s"expire time must be a date (2026-11-30) or an ISO-8601 instant " +
          s"(2026-11-30T15:59:59.999Z), got '$text'"))
  }

  private def parseInstant(text: String): Option[String] =
    try {
      Some(Instant.parse(text).toString)
    } catch {
      case _: DateTimeParseException => None
    }

  private def parseDate(text: String): Option[String] =
    try {
      Some(LocalDate.parse(text).atTime(LAST_MOMENT_OF_DAY)
        .atZone(ZoneId.systemDefault()).toInstant.toString)
    } catch {
      case _: DateTimeParseException => None
    }
}

// ---------------------------------------------------------------------- DDL：主体

private[spark] case class CreatePrincipalCommand(
    name: String,
    properties: Map[String, String],
    ifNotExists: Boolean,
    client: ManagementApiClient) extends ManagementCommand {

  override val output: Seq[AttributeReference] = Seq(
    ManagementCommands.attr("principal", nullable = false),
    ManagementCommands.attr("client_id"),
    ManagementCommands.attr("client_secret"))

  override def run(sparkSession: SparkSession): Seq[Row] = {
    try {
      val credentials = client.createPrincipal(name, ManagementCommands.toJavaMap(properties))
      Seq(Row(credentials.name(), credentials.clientId(), credentials.clientSecret()))
    } catch {
      case e: ManagementApiException if ifNotExists && e.alreadyExists() => Seq.empty
    }
  }
}

private[spark] case class DropPrincipalCommand(
    name: String,
    ifExists: Boolean,
    client: ManagementApiClient) extends ManagementCommand {

  override val output: Seq[AttributeReference] = Seq.empty

  override def run(sparkSession: SparkSession): Seq[Row] = {
    try {
      client.dropPrincipal(name)
      Seq.empty
    } catch {
      case e: ManagementApiException if ifExists && e.notFound() => Seq.empty
    }
  }
}

private[spark] case class AlterPrincipalCommand(
    name: String,
    toSet: Map[String, String],
    toUnset: Set[String],
    client: ManagementApiClient) extends ManagementCommand {

  override val output: Seq[AttributeReference] = Seq.empty

  override def run(sparkSession: SparkSession): Seq[Row] = {
    client.alterPrincipalProperties(
      name, ManagementCommands.toJavaMap(toSet), toUnset.toSet.asJava)
    Seq.empty
  }
}

private[spark] case class ResetPrincipalCommand(
    name: String,
    rotate: Boolean,
    client: ManagementApiClient) extends ManagementCommand {

  override val output: Seq[AttributeReference] = Seq(
    ManagementCommands.attr("principal", nullable = false),
    ManagementCommands.attr("client_id"),
    ManagementCommands.attr("client_secret"))

  override def run(sparkSession: SparkSession): Seq[Row] = {
    val credentials = client.resetPrincipal(name, rotate)
    Seq(Row(credentials.name(), credentials.clientId(), credentials.clientSecret()))
  }
}

// ---------------------------------------------------------------------- DDL：角色

private[spark] case class CreatePrincipalRoleCommand(
    name: String,
    properties: Map[String, String],
    ifNotExists: Boolean,
    client: ManagementApiClient) extends ManagementCommand {

  override val output: Seq[AttributeReference] = Seq.empty

  override def run(sparkSession: SparkSession): Seq[Row] = {
    try {
      client.createPrincipalRole(name, ManagementCommands.toJavaMap(properties))
      Seq.empty
    } catch {
      case e: ManagementApiException if ifNotExists && e.alreadyExists() => Seq.empty
    }
  }
}

private[spark] case class DropPrincipalRoleCommand(
    name: String,
    ifExists: Boolean,
    client: ManagementApiClient) extends ManagementCommand {

  override val output: Seq[AttributeReference] = Seq.empty

  override def run(sparkSession: SparkSession): Seq[Row] = {
    try {
      client.dropPrincipalRole(name)
      Seq.empty
    } catch {
      case e: ManagementApiException if ifExists && e.notFound() => Seq.empty
    }
  }
}

private[spark] case class AlterPrincipalRoleCommand(
    name: String,
    toSet: Map[String, String],
    toUnset: Set[String],
    client: ManagementApiClient) extends ManagementCommand {

  override val output: Seq[AttributeReference] = Seq.empty

  override def run(sparkSession: SparkSession): Seq[Row] = {
    client.alterPrincipalRoleProperties(
      name, ManagementCommands.toJavaMap(toSet), toUnset.toSet.asJava)
    Seq.empty
  }
}

// ---------------------------------------------------------------------- DCL

private[spark] case class GrantPrincipalRoleCommand(
    principal: String,
    principalRole: String,
    revoke: Boolean,
    client: ManagementApiClient) extends ManagementCommand {

  override val output: Seq[AttributeReference] = Seq.empty

  override def run(sparkSession: SparkSession): Seq[Row] = {
    if (revoke) {
      client.revokePrincipalRole(principal, principalRole)
    } else {
      client.grantPrincipalRole(principal, principalRole)
    }
    Seq.empty
  }
}

/**
 * 资源授权：一次语句可以带多项 access，展开成多条授权请求。
 *
 * <p>多条 access 时按顺序逐条调用；如果中途失败，前面的已经生效。服务端没有为
 * 「批量授权」提供原子端点（官方规格里每条授权是一个请求），因此这里不做伪原子化，
 * 而是让失败直接抛出，由调用方按需回滚——SQL 层面保持与规格一致的行为。
 *
 * <p>目标与「权限管理」页同一套语义：授权直接面向 principal，不经过 catalog role。
 */
private[spark] case class GrantPermissionsCommand(
    catalog: String,
    accesses: Seq[String],
    resource: ManagementApiClient.PermissionResource,
    principal: String,
    expireTime: Option[String],
    revoke: Boolean,
    client: ManagementApiClient) extends ManagementCommand {

  override val output: Seq[AttributeReference] = Seq.empty

  override def run(sparkSession: SparkSession): Seq[Row] = {
    accesses.foreach { access =>
      if (revoke) {
        client.revokePermission(catalog, resource, access, principal)
      } else {
        client.grantPermission(catalog, resource, access, principal, expireTime.orNull)
      }
    }
    Seq.empty
  }
}

// ---------------------------------------------------------------------- SHOW

private[spark] case class ShowCatalogsCommand(client: ManagementApiClient)
  extends ManagementCommand {

  override val output: Seq[AttributeReference] = Seq(
    ManagementCommands.attr("name", nullable = false),
    ManagementCommands.attr("type"))

  override def run(sparkSession: SparkSession): Seq[Row] = {
    client.listCatalogs().asScala.map { catalog =>
      Row(catalog.name(), catalog.`type`())
    }.toSeq
  }
}

private[spark] case class ShowPrincipalsCommand(
    forPrincipalRole: Option[String],
    client: ManagementApiClient) extends ManagementCommand {

  override val output: Seq[AttributeReference] = forPrincipalRole match {
    case Some(_) => Seq(ManagementCommands.attr("principal", nullable = false))
    case None => Seq(
      ManagementCommands.attr("name", nullable = false),
      ManagementCommands.attr("client_id"))
  }

  override def run(sparkSession: SparkSession): Seq[Row] = forPrincipalRole match {
    case Some(role) =>
      client.listPrincipalsOf(role).asScala.map(name => Row(name)).toSeq
    case None =>
      client.listPrincipals().asScala.map { principal =>
        Row(principal.name(), principal.clientId())
      }.toSeq
  }
}

private[spark] case class ShowPrincipalRolesCommand(
    forPrincipal: Option[String],
    client: ManagementApiClient) extends ManagementCommand {

  override val output: Seq[AttributeReference] = forPrincipal match {
    case Some(_) => Seq(ManagementCommands.attr("principal_role", nullable = false))
    case None => Seq(
      ManagementCommands.attr("name", nullable = false),
      ManagementCommands.attr("federated"))
  }

  override def run(sparkSession: SparkSession): Seq[Row] = forPrincipal match {
    case Some(principal) =>
      client.listPrincipalRolesOf(principal).asScala.map(name => Row(name)).toSeq
    case None =>
      client.listPrincipalRoles().asScala.map { role =>
        Row(role.name(), Option(role.federated()).map(_.toString).orNull)
      }.toSeq
  }
}

/**
 * 列出当前 catalog 的直授（官方权限模型）——与「权限管理」页同一份数据。
 *
 * <p>行承载「谁，被允许对什么做什么」：主体、资源定位器、access、有效期。
 * catalog 是全语句常量（写在 `IN CATALOG` 里），因此每行都回显它而不是留空——
 * 与 {@link ShowPrincipalRolesCommand} 每行回显主体名同一个约定。
 *
 * <p>{@code resource} 列把定位符拼成人能读的名字（`catalog` / `lib` / `lib.orders`），
 * 同时保留 {@code database} 与 {@code object_name} 两列以便筛选；
 * {@code columns} 列只对 COLUMN 授权有值，白名单写成 `amount, id`、
 * 黑名单写成 `EXCEPT card_no`，与本模块的语句写法一致。
 */
private[spark] case class ShowPermissionsCommand(
    catalog: String,
    principal: Option[String],
    client: ManagementApiClient) extends ManagementCommand {

  override val output: Seq[AttributeReference] = Seq(
    ManagementCommands.attr("catalog", nullable = false),
    ManagementCommands.attr("principal", nullable = false),
    ManagementCommands.attr("type", nullable = false),
    ManagementCommands.attr("resource"),
    ManagementCommands.attr("database"),
    ManagementCommands.attr("object_name"),
    ManagementCommands.attr("columns"),
    ManagementCommands.attr("access", nullable = false),
    ManagementCommands.attr("expire_time"))

  override def run(sparkSession: SparkSession): Seq[Row] = {
    client.listPermissions(catalog, principal.orNull).asScala.map { entry =>
      val resource = entry.resource()
      val database = resource.database()
      val objectName = resource.objectName()
      val label = (Option(database), Option(objectName)) match {
        // CATALOG / CATALOG_ALL 不定位（catalog 由请求路径的 prefix 决定）：写 catalog 名
        case (None, _) => catalog
        case (Some(db), None) => db
        case (Some(db), Some(name)) => db + "." + name
      }
      Row(catalog, entry.principal(), resource.`type`(), label, database, objectName,
        columnScope(resource), entry.access(), entry.expireTime())
    }.toSeq
  }

  /** 列范围的可读写法；不是列级授权时为 null（结果集里显示成 null 而不是空串）。 */
  private def columnScope(resource: ManagementApiClient.PermissionResource): String = {
    val allowed = resource.columns().asScala
    val excluded = resource.excludedColumns().asScala
    if (allowed.nonEmpty) allowed.mkString(", ")
    else if (excluded.nonEmpty) "EXCEPT " + excluded.mkString(", ")
    else null
  }
}
