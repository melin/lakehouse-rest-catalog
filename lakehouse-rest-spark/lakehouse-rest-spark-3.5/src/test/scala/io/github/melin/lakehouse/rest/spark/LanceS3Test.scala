package io.github.melin.lakehouse.rest.spark

import io.github.melin.lakehouse.rest.spark.LiveSparkSession.lanceCatalog
import org.apache.spark.sql.SparkSession

class LanceS3Test {

  def baseUrl: String = s"http://127.0.0.1:8080/api/management/v1"

  /*
    获取 token:
    curl -s -X POST http://127.0.0.1:8080/api/catalog/v1/oauth/tokens \
      -d grant_type=client_credentials \
      -d client_id="230698be-b91b-4262-b208-179cc530e54c" -d client_secret="nUmM_Y2nvcCLNxqBP7U-TM9uA3bENYFW1_Io2Cjumss" \
      -d scope=PRINCIPAL_ROLE:ALL | jq -r .access_token
   */
  val token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJwYWltb24tcmVzdCIsInN1YiI6InJvb3QiLCJpYXQiOjE3OTMyNDMwMzQsImV4cCI6MTc5MzI0NjYzNCwianRpIjoiNjRiNzQzMjAtNzdiOC00YWMzLTg3OTMtNTI2ZDVmMWQ2YzQ1Iiwic2NvcGUiOiJQUklOQ0lQQUxfUk9MRTpBTEwifQ.fKlD3szoCJewD6iODyukxbvsp4RuNSrx7xS4l6spyeQ"

  @org.junit.jupiter.api.Test
  def test(): Unit = {
    val warehouse = java.nio.file.Files.createTempDirectory("paimon-rest-warehouse")
    val spark = SparkSession.builder()
      .master("local[1]")
      .appName("lakehouse-rest-spark")
      .config("spark.ui.enabled", "false")
      .config("spark.driver.host", "127.0.0.1")
      .config("spark.driver.bindAddress", "127.0.0.1")
      .config("spark.sql.shuffle.partitions", "1")
      .config("spark.sql.shuffle.partitions", "1")
      .config("spark.sql.warehouse.dir", warehouse.toString)
      .config("spark.sql.extensions", "org.lance.spark.extensions.LanceSparkSessionExtensions")
      // Paimon 自己的扩展：建表/读写等 Paimon SQL 由它落地
      .config("spark.sql.catalog." + lanceCatalog, "org.lance.spark.LanceNamespaceSparkCatalog")
      .config("spark.sql.catalog." + lanceCatalog + ".impl", "rest")
      .config("spark.sql.catalog." + lanceCatalog + ".uri", "http://127.0.0.1:8080/lance")
      // warehouse 传的是 catalog 的 prefix：服务端 GET /v1/config 会用仓库名换回 prefix，
      // 客户端后续路径都以它开头。这里两侧同名
      .config("spark.sql.catalog." + lanceCatalog + ".delimiter", "$")
      .config("spark.sql.catalog." + lanceCatalog + ".data-token.enabled", "true")
      .config("spark.sql.catalog." + lanceCatalog + ".parent", "lance_catalog")
      .config("spark.sql.catalog." + lanceCatalog + ".single_level_ns", false)

      .config("spark.sql.catalog." + lanceCatalog + ".aws_region", "us-east-1")
      .config("spark.sql.catalog." + lanceCatalog + ".aws_endpoint", "http://172.18.6.181:9330")
      .config("spark.sql.catalog." + lanceCatalog + ".aws_access_key_id", "McvVnpOziVsWv7Qlyut7")
      .config("spark.sql.catalog." + lanceCatalog + ".aws_secret_access_key", "PbICbD6H7iyq0PuefHa383YoqJn3JCjedQHSYmbp")
      .config("spark.sql.catalog." + lanceCatalog + ".allow_http", "true")

      .config("spark.sql.catalog." + lanceCatalog + ".header.Authorization", "Bearer " + token)


      .config("spark.sql.catalog." + lanceCatalog + ".s3.endpoint", "http://172.18.6.181:9330")
      .config("spark.sql.catalog." + lanceCatalog + ".s3.access-key", "McvVnpOziVsWv7Qlyut7")
      .config("spark.sql.catalog." + lanceCatalog + ".s3.secret-key", "PbICbD6H7iyq0PuefHa383YoqJn3JCjedQHSYmbp")
      .config("spark.sql.catalog." + lanceCatalog + ".s3.path.style.access", "true")
      .config("spark.lakehouse.rest.lineage.enabled", "true")
      .config("spark.lakehouse.rest.policy.enforced", "true")

      .config(PaimonRestManagement.MANAGEMENT_URL, "http://127.0.0.1:8080/api/management/v1")
      .config(PaimonRestManagement.TOKEN, token)
      .getOrCreate()

    spark.catalog.setCurrentCatalog(lanceCatalog)

    spark.sql("drop table if exists demo.lance_users")

    // https://lance.org/integrations/spark/operations/ddl/create-table/
    val lanceCreateTableDdl =
      """
        |CREATE TABLE IF NOT EXISTS demo.lance_users (
        |    id BIGINT NOT NULL,
        |    name STRING,
        |    email STRING,
        |    created_at STRING
        |)
        |""".stripMargin
    // query lance table
    spark.sql(lanceCreateTableDdl)


    val insertSql =
      """
        |INSERT INTO demo.lance_users VALUES
        |    (4, 'David', 'david@example.com', '2024-01-15 10:30:00'),
        |    (5, 'Eva', 'eva@example.com', '2024-01-15 11:45:00');
        |""".stripMargin
    spark.sql(insertSql)

    spark.sql("explain select * from demo.lance_users").show(false)
    spark.sql("select * from demo.lance_users").show(false)
  }
}
