package io.github.melin.lakehouse.rest.spark

import io.github.melin.lakehouse.rest.spark.client.ManagementApiClient
import io.github.melin.lakehouse.rest.spark.parser.ManagementSqlBaseVisitor
import io.github.melin.lakehouse.rest.spark.parser.ManagementSqlParser._
import org.antlr.v4.runtime.tree.ParseTree
import org.apache.spark.sql.execution.command.RunnableCommand

import java.util.Locale
import scala.collection.JavaConverters._

/**
 * 把管理语句的语法树转换成 Spark 的逻辑计划。
 *
 * <p>每个 `visit*` 方法返回一个 {@link RunnableCommand}，由 Spark 在分析阶段执行；
 * 命令内部再调用 REST 管理 API。
 *
 * <p><b>解析期只做「形状」校验</b>（例如对象级授权必须写成 `库.对象名`），
 * 外加一处纯换算：有效期的「到哪一天」按本地时区折成 instant，见
 * {@link ManagementCommands#expireInstant}。access 取值是否合法、access 与资源
 * 类型是否匹配，都交给服务端判定：官方已有那份词表，SQL 层再抄一份只会多一处会漂移的副本。
 *
 * <p><b>访问器的两种形态。</b>ANTLR 对带标签的元素（`name=identifier`）生成公共**字段**，
 * 对未加标签的元素生成**方法**。因此 `ctx.name` 不带括号，而 `ctx.propertyList()` 带括号——
 * 写反了会被 Scala 报成「该类型不接受参数」。
 */
private[spark] class ManagementAstBuilder(client: ManagementApiClient)
  extends ManagementSqlBaseVisitor[AnyRef] {

  override def visitSingleStatement(ctx: SingleStatementContext): AnyRef =
    ctx.statement().accept(this)

  /**
   * 分派到具体的语句分支。
   *
   * <p>不能交给 `visitChildren`：其默认聚合规则是「取最后一个子节点的结果」，
   * 而 `singleStatement` 的最后一个子节点是 EOF 终结符，结果会是 null。
   * `statement` 的每个可选分支恰好对应一个子节点，因此直接分派给该子节点。
   */
  override def visitStatement(ctx: StatementContext): AnyRef =
    ctx.getChild(0).asInstanceOf[ParseTree].accept(this)

  // ------------------------------------------------------------------ DDL：主体

  override def visitCreatePrincipal(ctx: CreatePrincipalContext): AnyRef =
    CreatePrincipalCommand(
      identifier(ctx.name),
      properties(ctx.propertyList()),
      ifNotExists = ctx.EXISTS() != null,
      client = client)

  override def visitDropPrincipal(ctx: DropPrincipalContext): AnyRef =
    DropPrincipalCommand(identifier(ctx.name), ifExists = ctx.EXISTS() != null, client = client)

  override def visitAlterPrincipal(ctx: AlterPrincipalContext): AnyRef = {
    val name = identifier(ctx.name)
    ctx.setPropertyList() match {
      case null =>
        AlterPrincipalCommand(name, Map.empty, unsetKeys(ctx.unsetPropertyList()), client)
      case set =>
        AlterPrincipalCommand(name, setProperties(set), Set.empty, client)
    }
  }

  override def visitResetPrincipal(ctx: ResetPrincipalContext): AnyRef =
    ResetPrincipalCommand(identifier(ctx.name), rotate = ctx.ROTATE() != null, client = client)

  // ------------------------------------------------------------------ DDL：角色

  override def visitCreatePrincipalRole(ctx: CreatePrincipalRoleContext): AnyRef =
    CreatePrincipalRoleCommand(
      identifier(ctx.name),
      properties(ctx.propertyList()),
      ifNotExists = ctx.EXISTS() != null,
      client = client)

  override def visitDropPrincipalRole(ctx: DropPrincipalRoleContext): AnyRef =
    DropPrincipalRoleCommand(identifier(ctx.name), ifExists = ctx.EXISTS() != null, client = client)

  override def visitAlterPrincipalRole(ctx: AlterPrincipalRoleContext): AnyRef = {
    val name = identifier(ctx.name)
    ctx.setPropertyList() match {
      case null =>
        AlterPrincipalRoleCommand(name, Map.empty, unsetKeys(ctx.unsetPropertyList()), client)
      case set =>
        AlterPrincipalRoleCommand(name, setProperties(set), Set.empty, client)
    }
  }

  // ------------------------------------------------------------------ DCL：角色装配
  // catalog role 的六条语句（建档 / 装配 / 清点）已从语法中移除，
  // 对应的 visit 分支也不复存在——它们只剩管理 API 一条路。

  override def visitGrantPrincipalRole(ctx: GrantPrincipalRoleContext): AnyRef =
    GrantPrincipalRoleCommand(identifier(ctx.principal), identifier(ctx.role),
      revoke = false, client = client)

  override def visitRevokePrincipalRole(ctx: RevokePrincipalRoleContext): AnyRef =
    GrantPrincipalRoleCommand(identifier(ctx.principal), identifier(ctx.role),
      revoke = true, client = client)

  // ------------------------------------------------------------------ DCL：资源授权

  override def visitGrantPermissions(ctx: GrantPermissionsContext): RunnableCommand =
    permissionCommand(ctx.accessList(), ctx.permissionResource(), ctx.grantee(),
      // 有效期在这里就从「到哪一天」折成 instant，写错格式的语句不会走到发请求那一步
      Option(ctx.expire).map(token => ManagementCommands.expireInstant(stringValue(token.getText))),
      revoke = false)

  override def visitRevokePermissions(ctx: RevokePermissionsContext): RunnableCommand =
    permissionCommand(ctx.accessList(), ctx.permissionResource(), ctx.grantee(),
      None, revoke = true)

  private def permissionCommand(accesses: AccessListContext, resource: PermissionResourceContext,
                                grantee: GranteeContext, expire: Option[String],
                                revoke: Boolean): RunnableCommand = {
    val target = permissionTarget(resource)
    GrantPermissionsCommand(target.catalog, accessTexts(accesses), target.resource,
      granteeText(grantee), expire, revoke, client)
  }

  /**
   * 资源描述解出的目标。
   *
   * <p>{@code catalog} 是请求路径里的 prefix（权限端点长这样：
   * {@code /v1/{prefix}/permissions}），因此每条分支都把它一起取出来——
   * catalog 写在资源描述里，不单独占一个词。
   */
  private case class GrantTarget(catalog: String, resource: ManagementApiClient.PermissionResource)

  private def permissionTarget(ctx: PermissionResourceContext): GrantTarget =
    Option(ctx.catalogResource()).map { r =>
      GrantTarget(identifier(r.catalog), ManagementApiClient.PermissionResource.catalog())
    }.orElse(Option(ctx.catalogAllResource()).map { r =>
      GrantTarget(identifier(r.catalog), ManagementApiClient.PermissionResource.catalogAll())
    }).orElse(Option(ctx.databaseResource()).map { r =>
      GrantTarget(identifier(r.catalog),
        ManagementApiClient.PermissionResource.database(identifier(r.database)))
    }).orElse(Option(ctx.databaseAllResource()).map { r =>
      GrantTarget(identifier(r.catalog),
        ManagementApiClient.PermissionResource.databaseAll(identifier(r.database)))
    }).orElse(Option(ctx.tableResource()).map { r =>
      objectTarget(identifier(r.catalog), r.objectName, "table",
        (database, name) => ManagementApiClient.PermissionResource.table(database, name))
    }).orElse(Option(ctx.viewResource()).map { r =>
      objectTarget(identifier(r.catalog), r.objectName, "view",
        (database, name) => ManagementApiClient.PermissionResource.view(database, name))
    }).orElse(Option(ctx.functionResource()).map { r =>
      objectTarget(identifier(r.catalog), r.objectName, "function",
        (database, name) => ManagementApiClient.PermissionResource.function(database, name))
    }).orElse(Option(ctx.columnResource()).map { r =>
      val names = parts(r.objectName)
      requireQualified(names, "column")
      val scope = columnScope(r.columnScope())
      GrantTarget(identifier(r.catalog), ManagementApiClient.PermissionResource.column(
        names.dropRight(1).mkString("."), names.last, scope.allowed.asJava, scope.excluded.asJava))
    }).getOrElse(throw new ManagementSqlException(
      "unsupported permission resource: " + ctx.getText))

  /**
   * 对象级资源的公共形态：`<库>.<对象名> IN CATALOG <catalog>`。
   *
   * <p>库是授权作用域的一部分（官方定位器要求对象级授权带 `database`），单段名字
   * 无法确定作用域。这里不做「猜默认库」的处理，直接报错——报错比授错地方好。
   *
   * <p>四种对象类型只在定位字段上不同，因此共用一个 helper；差异用一个构造器函数带进来。
   */
  private def objectTarget(catalog: String, multipart: MultipartIdentifierContext,
                           resourceType: String,
                           factory: (String, String) => ManagementApiClient.PermissionResource)
                          : GrantTarget = {
    val names = parts(multipart)
    requireQualified(names, resourceType)
    GrantTarget(catalog, factory(names.dropRight(1).mkString("."), names.last))
  }

  private def requireQualified(names: Seq[String], resourceType: String): Unit =
    if (names.size < 2) {
      throw new ManagementSqlException(
        s"$resourceType grants require a qualified name of the form <database>.<name>, " +
          s"got '${names.mkString(".")}'")
    }

  /** 列范围解出的白 / 黑名单：默认白名单，`EXCEPT` 形式给黑名单。 */
  private case class ColumnScope(allowed: List[String], excluded: List[String])

  private def columnScope(ctx: ColumnScopeContext): ColumnScope = {
    val names = ctx.columnName().asScala.map(name => identifier(name.identifier())).toList
    if (ctx.EXCEPT() != null) ColumnScope(Nil, names) else ColumnScope(names, Nil)
  }

  /**
   * access 的取值逐项取字面文本并折成大写。
   *
   * <p>反引号写法（`` `select` ``）要先剥引号——词法上它们与裸标识符是不同的记号，
   * 直接 `getText` 会把反引号一起带进请求体。
   */
  private def accessTexts(ctx: AccessListContext): Seq[String] =
    ctx.access().asScala.map { access =>
      Option(access.identifier()).map(identifier).getOrElse(access.getText)
        .toUpperCase(Locale.ROOT)
    }.toSeq

  /**
   * 授权对象统一写成一个 principal 不透明字符串。
   *
   * <p>`USER alice` / `ROLE analyst` / `GROUP eng` 折成官方约定的三种前缀写法；
   * `PRINCIPAL 'svc:etl'` 原样透传，给不在这三种约定里的主体留口子。
   */
  private def granteeText(ctx: GranteeContext): String =
    Option(ctx.userName).map(name => "user:" + identifier(name)).orElse(
      Option(ctx.roleName).map(name => "role:" + identifier(name))).orElse(
      Option(ctx.groupName).map(name => "group:" + identifier(name))).orElse(
      Option(ctx.principalName).map(name => stringValue(name.getText))).getOrElse(
      throw new ManagementSqlException("unsupported grantee: " + ctx.getText))

  // ------------------------------------------------------------------ SHOW

  override def visitShowCatalogs(ctx: ShowCatalogsContext): AnyRef = ShowCatalogsCommand(client)

  override def visitShowPrincipals(ctx: ShowPrincipalsContext): AnyRef =
    ShowPrincipalsCommand(Option(ctx.principalRole).map(identifier), client)

  override def visitShowPrincipalRoles(ctx: ShowPrincipalRolesContext): AnyRef =
    ShowPrincipalRolesCommand(Option(ctx.principal).map(identifier), client)

  override def visitShowPermissions(ctx: ShowPermissionsContext): AnyRef =
    ShowPermissionsCommand(identifier(ctx.catalog), Option(ctx.grantee()).map(granteeText), client)

  // ------------------------------------------------------------------ 公共工具

  private def identifier(ctx: IdentifierContext): String = {
    val quoted = ctx.BACKQUOTED_IDENTIFIER()
    if (quoted != null) {
      val raw = quoted.getText
      // 反引号内的 `` 表示一个反引号
      raw.substring(1, raw.length - 1).replace("``", "`")
    } else {
      ctx.IDENTIFIER().getText
    }
  }

  private def parts(ctx: MultipartIdentifierContext): Seq[String] =
    ctx.identifier().asScala.map(identifier).toSeq

  private def properties(ctx: PropertyListContext): Map[String, String] =
    if (ctx == null) Map.empty else toMap(ctx.property().asScala.toSeq)

  private def setProperties(ctx: SetPropertyListContext): Map[String, String] =
    toMap(ctx.property().asScala.toSeq)

  private def unsetKeys(ctx: UnsetPropertyListContext): Set[String] =
    ctx.propertyKey().asScala.map(propertyKey).toSet

  private def toMap(items: Seq[PropertyContext]): Map[String, String] =
    items.map { item =>
      // 只写键不写值等价于设置成空串，属性是否合法由服务端校验
      val value = Option(item.value).map(propertyValue).getOrElse("")
      propertyKey(item.key) -> value
    }.toMap

  private def propertyKey(ctx: PropertyKeyContext): String = {
    val literal = ctx.STRING()
    if (literal != null) stringValue(literal.getText) else identifier(ctx.identifier())
  }

  private def propertyValue(ctx: PropertyValueContext): String = {
    val literal = ctx.STRING()
    if (literal != null) {
      stringValue(literal.getText)
    } else if (ctx.number() != null) {
      ctx.number().getText
    } else {
      identifier(ctx.identifier())
    }
  }

  /** 去掉字符串字面量的引号，并还原成对引号。 */
  private def stringValue(raw: String): String = {
    val quote = raw.charAt(0)
    raw.substring(1, raw.length - 1).replace(s"$quote$quote", quote.toString)
  }
}
