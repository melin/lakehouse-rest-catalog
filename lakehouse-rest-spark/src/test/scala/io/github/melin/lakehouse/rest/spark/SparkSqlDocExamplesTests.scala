package io.github.melin.lakehouse.rest.spark

import org.apache.spark.sql.{Row, SparkSession}
import org.junit.jupiter.api.Assertions._
import org.junit.jupiter.api.{Assumptions, BeforeEach, Test}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.time.{Instant, LocalDate, LocalTime, ZoneId}

/**
 * 把参考文档里的示例当作验收用例执行。
 *
 * <p><b>为什么值得单独一层。</b>`docs/spark-sql-reference.md` 第 10 节给出的是一段
 * 「按顺序执行即可跑通全部 16 条语句」的脚本。文档里的 SQL 有两种坏法：
 * 写错语法（解析期就会失败），以及顺序不成立（例如先 `DROP` 再 `REVOKE`，
 * 或者少了 `GRANT` 却断言 `SHOW` 有结果）。前者靠读稿难以发现，
 * 后者只有真跑一遍才暴露。因此这里把那段示例从 Markdown 里**抽出来执行**，
 * 让文档与代码同生共死：改坏了示例，构建会失败。
 *
 * <p>执行对象是**真实服务端**，与 {@link ManagementSqlLiveServerTests} 同一层：
 * 桩服务端不会校验「撤销后授权是否真的少了」，也就验不出示例的顺序是否正确。
 * 默认跳过，需要显式指向服务端（`scripts/e2e-spark-sql.sh` 会自动带上）。
 *
 * <p><b>断言挑的是「语义」而不是「行数整齐」。</b>资源授权那一段改成了官方权限模型
 * （与「权限管理」页同一条链路），因此这里除了数行数，还钉住三件事：
 * 作用范围怎么落到 `type`/`database`/`object_name` 三列上、列范围与有效期怎么回读、
 * 以及「撤销一项 access 不影响同资源上的其它 access」。
 *
 * <p>会话复用 {@link ManagementSqlLiveServerTests} 的那一个：一个 JVM 只能有一个
 * `SparkContext`，同一个会话对象既保证配置一致，也避免建会话的重复开销。
 */
class SparkSqlDocExamplesTests {

  private val url = Option(System.getProperty("e2e.management.url")).filter(_.nonEmpty)

  @BeforeEach
  def requireLiveServer(): Unit =
    Assumptions.assumeTrue(url.isDefined,
      "跳过：需要 -De2e.management.url=<管理 API 基址>，见 scripts/e2e-spark-sql.sh")

  private def session: SparkSession = ManagementSqlLiveServerTests.session

  /** 文档路径：相对模块目录解析，因此从仓库根或模块目录运行都能找到。 */
  private def docPath: Path = {
    val candidates = Seq(
      Paths.get("docs/spark-sql-reference.md"),
      Paths.get("../docs/spark-sql-reference.md"))
    candidates.find(Files.exists(_)).getOrElse(
      throw new IllegalStateException(
        "找不到 docs/spark-sql-reference.md，当前目录: " + Paths.get("").toAbsolutePath))
  }

  /**
   * 抽出示例块并切成单条语句。
   *
   * <p>切分依据是「每行一条语句、以分号结尾」这一约定，而不是通用 SQL 分词：
   * 示例本身就是按这个约定写的，而通用分词会引入字符串字面量里分号的歧义，
   * 反而更难保证正确。
   */
  private def statements(): Seq[String] = {
    val markdown = new String(Files.readAllBytes(docPath), StandardCharsets.UTF_8)
    val begin = markdown.indexOf("<!-- doc-example:begin -->")
    val end = markdown.indexOf("<!-- doc-example:end -->")
    assertTrue(begin >= 0 && end > begin,
      "文档里应保留 doc-example:begin / doc-example:end 标记，校验脚本依赖它们")
    val block = markdown.substring(begin, end)
    val sql = block.linesIterator
      .map(_.trim)
      .filterNot(_.isEmpty)
      .filterNot(_.startsWith("```"))
      .filterNot(_.startsWith("<!--"))
      .filterNot(_.startsWith("--")) // 示例块里的分节注释
      .mkString(" ")
    sql.split(';').map(_.trim).filter(_.nonEmpty).toSeq
  }

  @Test
  def everyStatementInTheReferenceExampleExecutesAgainstTheRealServer(): Unit = {
    val all = statements()
    assertTrue(all.length >= 16,
      s"示例块应至少覆盖 16 条语句，实际 ${all.length} 条")

    // 逐条执行；记下每条的结果，供下面按语义断言
    val executed = all.map { sql =>
      val rows = session.sql(sql).collect()
      (sql, rows)
    }

    // ① 主体：RESET 与 ROTATE 各自签发一份新的明文密钥
    val reset = executed.collectFirst { case (sql, rows) if sql.startsWith("RESET PRINCIPAL") => rows }
    val rotate = executed.collectFirst { case (sql, rows) if sql.startsWith("ROTATE PRINCIPAL") => rows }
    assertEquals(1, reset.get.length, "RESET PRINCIPAL 应返回一行凭据")
    assertEquals(1, rotate.get.length, "ROTATE PRINCIPAL 应返回一行凭据")
    assertNotNull(reset.get(0).getString(2), "RESET 应返回明文密钥")
    assertNotEquals(reset.get(0).getString(2), rotate.get(0).getString(2),
      "轮换后密钥应与重置时不同")

    // ④ 资源授权：示例里三次「查该主体的直授」，按出现顺序取
    //
    // 列：0 catalog、1 principal、2 type、3 resource、4 database、5 object_name、
    //     6 columns、7 access、8 expire_time
    def picks(sqlPart: String): Seq[Array[Row]] = executed.collect {
      case (sql, rows) if sql.startsWith("SHOW PERMISSIONS") && sql.contains(sqlPart) => rows
    }

    // 示例把四条资源授权都写给同一个主体。挑 `USER` 而不是 `ROLE`，是因为 `USER` 不依赖
    // 服务端状态——`role:<名>` 要生效得先有同名 catalog role，而 catalog role 已经没有
    // SQL 语句了（只剩管理 API），示例块不该隐含这种脚手架依赖。
    val views = picks("FOR USER doc_demo_etl")
    assertEquals(3, views.length,
      "示例里应有三次按主体查直授：撤销前、撤销后、清理后各一次")

    def pairs(rows: Array[Row]): Set[(String, String)] =
      rows.map(row => (row.getString(2), row.getString(7))).toSet

    // 撤销前：CREATEDATABASE(catalog) + LIST/DESCRIBE/SELECT(catalog 全部后代)
    //         + UPDATE(一张表) + SELECT(两列)
    assertEquals(6, views.head.length, "撤销前应有 6 条直授")
    assertEquals(
      Set(("CATALOG", "CREATEDATABASE"), ("CATALOG_ALL", "LIST"),
        ("CATALOG_ALL", "DESCRIBE"), ("CATALOG_ALL", "SELECT"), ("TABLE", "UPDATE"),
        ("COLUMN", "SELECT")),
      pairs(views.head))

    // principal 列是授权对象折成的串：`USER x` → `user:x`
    assertTrue(views.head.forall(_.getString(1) == "user:doc_demo_etl"),
      "principal 列应回显折好的 user: 前缀串")

    // resource 列的拼法：catalog 级写 catalog 名（不带库），对象级写 库.对象名
    val byType = views.head.map(row => (row.getString(2), row.getString(7)) -> row).toMap
    val catalogRow = byType(("CATALOG", "CREATEDATABASE"))
    assertEquals("paimon", catalogRow.getString(3))
    assertNull(catalogRow.getString(4), "catalog 级授权不带库")
    assertNull(catalogRow.getString(5), "catalog 级授权不带对象名")
    assertNull(catalogRow.getString(8), "没写 UNTIL 的授权没有有效期")

    val tableRow = byType(("TABLE", "UPDATE"))
    assertEquals("default.orders", tableRow.getString(3))
    assertEquals("default", tableRow.getString(4))
    assertEquals("orders", tableRow.getString(5))

    // 撤销一项 access：同资源上的其它 access 与别的资源都不受影响
    assertEquals(5, views(1).length, "撤销 DESCRIBE 后应剩 5 条")
    assertFalse(pairs(views(1)).contains(("CATALOG_ALL", "DESCRIBE")),
      "被撤销的 access 不应还在")
    assertTrue(pairs(views(1)).contains(("CATALOG_ALL", "LIST")),
      "撤销一项 access 不应影响同一资源上的其它 access")
    assertTrue(pairs(views(1)).contains(("TABLE", "UPDATE")), "别的资源不应受影响")

    // 列范围与有效期：白名单直写列名；`UNTIL '2030-01-01'` 落在当天最后一毫秒。
    // 两条都从同一次查询里读——示例授权的 6 条直授属于同一个主体。
    val columnRow = byType(("COLUMN", "SELECT"))
    assertEquals("default.orders", columnRow.getString(3))
    assertEquals("default", columnRow.getString(4))
    assertEquals("orders", columnRow.getString(5))
    assertEquals("id, amount", columnRow.getString(6), "白名单直写列名")

    // 值的形态依赖运行机器的时区，因此按本地时区判定，而不是直接比字符串。
    val expires = Instant.parse(columnRow.getString(8))
    val zone = ZoneId.systemDefault()
    assertEquals(LocalDate.of(2030, 1, 1), LocalDate.ofInstant(expires, zone),
      "有效期应落在 2030-01-01 当天")
    assertEquals(LocalTime.of(23, 59, 59, 999000000), LocalTime.ofInstant(expires, zone),
      "有效期应是当天最后一毫秒，而不是当天零点")

    // 清理之后：该主体名下一条不剩。这一步同时验证「撤授权」与「装配」是两件事——
    // 主体 → principal role 的装配此时仍在
    assertTrue(views.last.isEmpty,
      "把四条直授都撤掉后，该主体名下不应再有直授")

    // ⑤ 收尾：示例最后一条 SHOW CATALOGS 应仍能看到预置的 catalog
    val catalogs = executed.last
    assertTrue(catalogs._1.startsWith("SHOW CATALOGS"), "示例最后一条应为 SHOW CATALOGS")
    assertTrue(catalogs._2.exists(_.getString(0) == "paimon"),
      "SHOW CATALOGS 应包含预置的 paimon")
  }
}
