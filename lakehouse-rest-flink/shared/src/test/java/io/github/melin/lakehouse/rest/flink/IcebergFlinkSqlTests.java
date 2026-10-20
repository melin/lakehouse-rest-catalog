package io.github.melin.lakehouse.rest.flink;

import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.types.Row;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 用 Flink SQL 通过 Lakehouse REST catalog 建 Iceberg 表、读写数据。
 *
 * <p><b>为什么没有主键更新。</b>Iceberg 的 Flink 侧没有主键表/upsert 语义
 * （那是 Paimon 的 merge engine 提供的）；要在 Iceberg 上改一行，得走
 * {@code UPDATE}/{@code MERGE} 或重写数据文件，属另一条链路。这里只验
 * 「建表 → 写入 → 读回 → 再写一批 → 读回」这条 REST catalog 的主干。
 *
 * <p>注意两个 catalog 是**两套键名**：Iceberg 用 {@code type=iceberg} +
 * {@code catalog-type=rest}，Paimon 用 {@code metastore=rest}。写混了不会报
 * 「参数不认识」，只会退化成别的实现——{@link LakehouseRestFlinkCatalogs} 里各有一份。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IcebergFlinkSqlTests {

    private static final String CATALOG = "flink_iceberg";
    private static final String DATABASE = "flink_e2e";
    private static final String TABLE = CATALOG + "." + DATABASE + ".orders";

    private static TableEnvironment env;

    @BeforeAll
    static void setUp() {
        assumeTrue(FlinkE2eSupport.available(), "未配置 -De2e.management.url，跳过端到端用例");

        // Iceberg 的 prefix 必须先用管理 API 建成 ICEBERG：让客户端撞 /v1/config
        // 的自动登记会按部署默认值建成 PAIMON，此后每次调用都 404。
        FlinkE2eSupport.ensureCatalog(CATALOG, "ICEBERG");
        env = FlinkE2eSupport.batchEnvironment();
        FlinkE2eSupport.execute(env, LakehouseRestFlinkCatalogs.createCatalogIfNotExists(
                CATALOG,
                LakehouseRestFlinkCatalogs.icebergOptions(
                        FlinkE2eSupport.catalogUrl(), CATALOG, FlinkE2eSupport.token())));

        // 先用 IF NOT EXISTS 把库建出来，再删表。**顺序不能反**：Iceberg 的 Flink
        // catalog 在 `DROP TABLE IF EXISTS` 时会先解析表，库不存在就直接抛
        // NoSuchNamespaceException——`IF EXISTS` 只兜「表不存在」，不兜「库不存在」。
        // 接着把空库删掉重建，保证每次跑都是干净状态（上一轮遗留的表随库一起消失）。
        FlinkE2eSupport.execute(env, "CREATE DATABASE IF NOT EXISTS " + CATALOG + "." + DATABASE);
        FlinkE2eSupport.execute(env, "DROP TABLE IF EXISTS " + TABLE);
        // Iceberg 不接受级联删除：命名空间下还有表时服务端回 409，所以先删表再删库。
        FlinkE2eSupport.execute(env, "DROP DATABASE " + CATALOG + "." + DATABASE);
        FlinkE2eSupport.execute(env, "CREATE DATABASE " + CATALOG + "." + DATABASE);
        // 显式声明 format-version=2：服务端的 Iceberg 面支持 v2，v1 少了 delete file
        // 一类元数据，用例读回来的形状差别很大。
        FlinkE2eSupport.execute(env, "CREATE TABLE " + TABLE + " ("
                + "id INT, name STRING, amount DOUBLE) WITH ('format-version' = '2')");
    }

    @Test
    @Order(1)
    void writesAndReadsRows() {
        FlinkE2eSupport.execute(env, "INSERT INTO " + TABLE + " VALUES "
                + "(1, 'apple', 10.5), (2, 'banana', 20.5), (3, 'cherry', 30.0)");

        assertEquals(3L, count(), "三行写入后行数应为 3");
        assertEquals(61.0, sum(), 1e-9, "amount 合计应为 61.0");
    }

    @Test
    @Order(2)
    void secondBatchIsVisible() {
        // 再写一批：验证「写入 → 提交 → 读回」不是一次性的，
        // 也顺带覆盖客户端 metadata 缓存之外的第二条快照路径。
        FlinkE2eSupport.execute(env, "INSERT INTO " + TABLE + " VALUES (4, 'date', 40.0)");

        assertEquals(4L, count(), "追加一批后行数应为 4");
        List<Row> rows = FlinkE2eSupport.query(env,
                "SELECT id, name FROM " + TABLE + " WHERE id = 4");
        assertEquals(1, rows.size(), "应能读回新写入的行");
        assertEquals("date", rows.get(0).getField(1));
    }

    @Test
    @Order(3)
    void serverStoresIcebergMetadata() {
        // 引擎读得回来只证明了客户端；这里核对服务端存下的是 Iceberg 形状的元数据。
        HttpResponse<String> response = FlinkE2eSupport.send("GET",
                "/v1/" + CATALOG + "/namespaces/" + DATABASE + "/tables/orders", null);
        assertEquals(200, response.statusCode(), "读表元数据失败：" + response.body());
        String compact = response.body().replace(" ", "");
        assertTrue(compact.contains("\"metadata-location\""),
                "Iceberg 响应应带 metadata-location：" + response.body());
        assertTrue(compact.contains("\"format-version\":2"),
                "建表时声明了 format-version=2，服务端应存成 2：" + response.body());
    }

    private static long count() {
        return (Long) FlinkE2eSupport.query(env, "SELECT COUNT(*) FROM " + TABLE).get(0).getField(0);
    }

    private static double sum() {
        return (Double) FlinkE2eSupport.query(env, "SELECT SUM(amount) FROM " + TABLE).get(0).getField(0);
    }
}
