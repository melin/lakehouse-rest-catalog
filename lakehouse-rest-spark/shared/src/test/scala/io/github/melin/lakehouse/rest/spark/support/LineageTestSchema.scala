package io.github.melin.lakehouse.rest.spark.support

/**
 * 血缘测试用的库与表。
 *
 * <p>与用例分开放：抽取器测试与反射测试都要用到这些表，而 JUnit 不保证测试类的执行顺序，
 * 「谁先跑谁建」会让结果与顺序有关——那种失败看起来像实现坏了，其实是夹具没就位。
 *
 * <p>全部是 Parquet 表（V1）。V2 表（Paimon / Iceberg）需要服务端，端到端用例才用得上；
 * 这里要验的是「计划长什么样」，与表格式无关。
 */
object LineageTestSchema {

  private lazy val ready: Boolean = {
    val session = SharedTestSession.session
    statements.foreach(sql => session.sql(sql).collect())
    true
  }

  /** 建库建表只做一次。 */
  def ensure(): Unit = {
    val _ = ready
  }

  private val statements: Seq[String] = Seq(
    "CREATE DATABASE IF NOT EXISTS lineage_db",
    "CREATE TABLE IF NOT EXISTS lineage_db.s (a INT, b INT, c STRING) USING parquet",
    "CREATE TABLE IF NOT EXISTS lineage_db.u (a INT, b INT) USING parquet",
    "CREATE TABLE IF NOT EXISTS lineage_db.t (x INT, y INT, z STRING) USING parquet",
    "CREATE TABLE IF NOT EXISTS lineage_db.t2 (x INT, y INT) USING parquet",
    "CREATE TABLE IF NOT EXISTS lineage_db.bn (x INT, y INT) USING parquet",
    "CREATE TABLE IF NOT EXISTS lineage_db.one (x INT) USING parquet",
    "CREATE TABLE IF NOT EXISTS lineage_db.tp (x INT, y INT, dt STRING) USING parquet PARTITIONED BY (dt)",
    "CREATE TABLE IF NOT EXISTS lineage_db.arr (id INT, xs ARRAY<INT>) USING parquet"
  )
}
