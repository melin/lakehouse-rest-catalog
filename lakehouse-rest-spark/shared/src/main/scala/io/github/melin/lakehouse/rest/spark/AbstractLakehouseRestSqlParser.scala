package io.github.melin.lakehouse.rest.spark

import io.github.melin.lakehouse.rest.spark.client.ManagementApiClient
import io.github.melin.lakehouse.rest.spark.parser.ManagementSqlLexer
import io.github.melin.lakehouse.rest.spark.parser.ManagementSqlParser
import org.antlr.v4.runtime.misc.ParseCancellationException
import org.antlr.v4.runtime.{BailErrorStrategy, CharStreams, CommonTokenStream}
import org.apache.spark.sql.catalyst.FunctionIdentifier
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.expressions.Expression
import org.apache.spark.sql.catalyst.parser.{ParseException, ParserInterface}
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.types.{DataType, StructType}

/**
 * 在 Spark 原生解析器前面加一层「管理语句解析」。
 *
 * <p>这是 Spark 官方提供的 {@code SparkSessionExtensions.injectParser} 扩展点的实现：
 * 包装（而不是替换）会话原有的 {@link ParserInterface}，只拦截本扩展认识的语句，
 * 其余原样交回，因此既不会影响普通 SQL，也不会丢掉会话已有的解析行为
 * （例如其它扩展注入的解析逻辑）。
 *
 * <p><b>拦截策略：先试自己的语法，失败即交回。</b>
 * `singleStatement` 要求整条语句匹配到 EOF，而每个可选分支都以固定的关键字序列开头，
 * 因此只有真正是管理语句的输入才会解析成功；解析失败（`ParseCancellationException`）
 * 一律视为「不是管理语句」，交给原生解析器给出它自己的错误信息。
 * 这样判断依据是语法本身，而不是靠字符串前缀猜测。
 *
 * <p><b>为什么这里是抽象类。</b>{@code ParserInterface} 的抽象成员在两个受支持的
 * Spark 版本之间并不相同：4.x 多一个 {@code parseRoutineParam}，3.5 没有。写在这个
 * 共享类里会让 3.5 编译失败（「方法不是父类型的成员」），而完整复制一份到两个版本
 * 模块又会让这 100 行出现两份。因此这里只实现**两个版本都有**的那部分，具体子类
 * {@link LakehouseRestSqlParser} 由各版本模块提供：3.5 侧不补任何东西，4.x 侧补一个方法。
 * 子类与共享源码在同一个模块里编译，所以调用方照常 `new LakehouseRestSqlParser(...)`，
 * 不需要工厂或反射。
 */
abstract class AbstractLakehouseRestSqlParser(
    protected val delegate: ParserInterface,
    clientFactory: () => ManagementApiClient) extends ParserInterface {

  /**
   * 客户端延迟创建：构造解析器时配置可能还没设，等第一条管理语句到来时再读。
   *
   * <p>之所以把「怎么拿到客户端」抽成工厂而不是直接接收 `SparkSession`，是为了让解析
   * 逻辑可以脱离真实会话单独测试——解析只依赖客户端实例，不依赖会话本身。
   */
  private lazy val client: ManagementApiClient = clientFactory()

  override def parsePlan(sqlText: String): LogicalPlan =
    parseManagementStatement(sqlText).getOrElse(delegate.parsePlan(sqlText))

  // ------------------------------------------------------------------ 其余方法一律委托

  override def parseQuery(sqlText: String): LogicalPlan = delegate.parseQuery(sqlText)

  override def parseExpression(sqlText: String): Expression = delegate.parseExpression(sqlText)

  override def parseTableIdentifier(sqlText: String): TableIdentifier =
    delegate.parseTableIdentifier(sqlText)

  override def parseFunctionIdentifier(sqlText: String): FunctionIdentifier =
    delegate.parseFunctionIdentifier(sqlText)

  override def parseMultipartIdentifier(sqlText: String): Seq[String] =
    delegate.parseMultipartIdentifier(sqlText)

  override def parseTableSchema(sqlText: String): StructType = delegate.parseTableSchema(sqlText)

  override def parseDataType(sqlText: String): DataType = delegate.parseDataType(sqlText)

  // ------------------------------------------------------------------ 内部

  /**
   * 尝试按管理语法解析。
   *
   * @return 解析成功时为对应的命令；不是管理语句时为 {@code None}
   * @throws ManagementSqlException 确实是管理语句但形状非法
   * @throws ParseException         语句以管理关键字开头、但语法不完整（例如 `CREATE PRINCIPAL`）
   */
  private[spark] def parseManagementStatement(sqlText: String): Option[LogicalPlan] = {
    val lexer = new ManagementSqlLexer(CharStreams.fromString(sqlText))
    // 去掉默认错误监听器：这里只关心「能不能解析」，
    // 具体的报错信息由原生解析器给出，避免同一句 SQL 打印两遍错误。
    lexer.removeErrorListeners()
    val parser = new ManagementSqlParser(new CommonTokenStream(lexer))
    parser.removeErrorListeners()
    // 首个语法错误立即抛出，不做错误恢复：半成品语法树会得到不可预期的计划
    parser.setErrorHandler(new BailErrorStrategy())
    try {
      val tree = parser.singleStatement()
      val plan = new ManagementAstBuilder(client).visit(tree)
      Option(plan).map(_.asInstanceOf[LogicalPlan])
    } catch {
      case _: ParseCancellationException =>
        // 语法不匹配：交给原生解析器。若确实是管理语句的残篇，
        // 原生解析器会给出「Unsupported operation」之类的错误信息。
        // 其它异常（如 ManagementSqlException）不在此捕获，直接向上抛出。
        None
    }
  }
}
