package io.github.melin.lakehouse.rest.spark

import io.github.melin.lakehouse.rest.spark.LiveSparkSession.{catalog, icebergCatalog}
import org.apache.spark.sql.SparkSession

class IcebergS3Test {

  def baseUrl: String = s"http://127.0.0.1:8080/api/management/v1"

  /*
    获取 token:
    curl -s -X POST http://127.0.0.1:8080/api/catalog/v1/oauth/tokens \
      -d grant_type=client_credentials \
      -d client_id="230698be-b91b-4262-b208-179cc530e54c" -d client_secret="nUmM_Y2nvcCLNxqBP7U-TM9uA3bENYFW1_Io2Cjumss" \
      -d scope=PRINCIPAL_ROLE:ALL | jq -r .access_token
   */
  val token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJwYWltb24tcmVzdCIsInN1YiI6InJvb3QiLCJpYXQiOjE3OTMzNDc4MTksImV4cCI6MTc5MzM1MTQxOSwianRpIjoiM2E2YWNiMzItODZmZC00OWNkLTk3NDktZjViZDY3OGNkYzJhIiwic2NvcGUiOiJQUklOQ0lQQUxfUk9MRTpBTEwifQ.IMjC4JfMtARHfSzrDi4XbY9_qZJCG5sLCxWbyjYnUfI"

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
      .config("spark.sql.extensions", LiveSparkSession.extensions)
      // Paimon 自己的扩展：建表/读写等 Paimon SQL 由它落地
      .config("spark.sql.catalog." + icebergCatalog, "org.apache.iceberg.spark.SparkCatalog")
      .config("spark.sql.catalog." + icebergCatalog + ".type", "rest")
      .config("spark.sql.catalog." + icebergCatalog + ".uri", "http://127.0.0.1:8080")
      // warehouse 传的是 catalog 的 prefix：服务端 GET /v1/config 会用仓库名换回 prefix，
      // 客户端后续路径都以它开头。这里两侧同名
      .config("spark.sql.catalog." + icebergCatalog + ".warehouse", icebergCatalog)
      .config("spark.sql.catalog." + icebergCatalog + ".rest.auth.type", "oauth2")
      .config("spark.sql.catalog." + icebergCatalog + ".token", token)
      // catalog 的仓库在 `s3://`（MinIO）上，本进程没有任何对象存储密钥，
      // **端点和密钥都只能由服务端下发**。这一行就是「请把凭据给我」：
      // 服务端认这个头之后才会在 loadTable 里带 `storage-credentials`，
      // 而那一条里的 config 除 AK/SK 外还带 `s3.endpoint` / `s3.path-style-access`
      // / `s3.region`（见 DefaultStorageCredentialManager 的 s3()，定位配置与
      // 密钥有无无关）。属性名必须带 `header.` 前缀——Iceberg 的 REST 客户端
      // 按这个前缀把属性筛成请求头（RESTUtil.configHeaders），不是内置开关。
      //
      // 不加这一行的表现值得记下来：客户端拿本机凭据链直接连 AWS 的默认端点，
      // 桶 `test` 不在那里，S3 回 `301 Moved Permanently` + 一串 AWS 的
      // Request ID / Extended Request ID——报错长得像权限问题，实际是「没人告诉它连哪」。
      .config("spark.sql.catalog." + icebergCatalog + ".header.X-Iceberg-Access-Delegation",
        "vended-credentials")

      // 另一条路：自己带密钥，那样就不需要上面的 delegation 头（服务端「不问不给」）。
      // 两条路不要同时开——同时给的时候客户端用自己那份，服务端下发的那份只覆盖配置，
      // 排查时分不清是谁在生效。
      // .config("spark.sql.catalog." + icebergCatalog + ".s3.endpoint", "http://172.18.6.181:9330")
      // .config("spark.sql.catalog." + icebergCatalog + ".s3.access-key-id", "McvVnpOziVsWv7Qlyut7")
      // .config("spark.sql.catalog." + icebergCatalog + ".s3.secret-access-key", "PbICbD6H7iyq0PuefHa383YoqJn3JCjedQHSYmbp")
      // .config("spark.sql.catalog." + icebergCatalog + ".s3.path.style.access", "true")

      .config("spark.lakehouse.rest.lineage.enabled", "true")
      .config("spark.lakehouse.rest.policy.enforced", "true")
      .config(PaimonRestManagement.MANAGEMENT_URL, "http://127.0.0.1:8080/api/management/v1")
      .config(PaimonRestManagement.TOKEN, token)
      .getOrCreate()

    spark.catalog.setCurrentCatalog(icebergCatalog)

    val insertSql = "insert into table default.iceberg_demo values(1, 'zhangsan')"
    // spark.sql(insertSql)

    val insertSql2 = "insert into table default.iceberg_users select * from default.iceberg_demo"
    // spark.sql(insertSql2)

    spark.sql("select * from default.iceberg_users").show()
  }
}
