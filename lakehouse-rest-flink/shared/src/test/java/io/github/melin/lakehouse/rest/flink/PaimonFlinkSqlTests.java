package io.github.melin.lakehouse.rest.flink;

import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.types.Row;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 用 Flink SQL 通过 Lakehouse REST catalog 建 Paimon 表、读写数据，并验证主键更新。
 *
 * <p><b>为什么要跑真实服务端。</b>Paimon 的 Flink 连接器自己不会造元数据：建表时它把
 * schema 交给服务端、由服务端生成并落库；写入时它按主键做 LSM 合并，读取时再按
 * 主键去重。用桩替代服务端只能证明「我们与自己的假设自洽」。
 *
 * <p><b>主键更新怎么算验证过。</b>两次写同一个主键、值不同，然后：行数仍是 2（不是 3）、
 * 该主键读到的是后写的值。只查「写进去了」不够——追加成功但合并没生效时，
 * 行数会变 3，或者两条都在。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PaimonFlinkSqlTests {

    private static final String CATALOG = "flink_paimon";
    private static final String DATABASE = "flink_e2e";
    private static final String TABLE = CATALOG + "." + DATABASE + ".pk_orders";

    private static TableEnvironment env;

    @BeforeAll
    static void setUp() {
        // 没配目标地址就跳过：`mvn test` 在没有服务端时仍应是绿的，
        // 不能把「没起服务端」报成回归。
        assumeTrue(FlinkE2eSupport.available(), "未配置 -De2e.management.url，跳过端到端用例");

        FlinkE2eSupport.ensureCatalog(CATALOG, "PAIMON");
        env = FlinkE2eSupport.batchEnvironment();
        FlinkE2eSupport.execute(env, LakehouseRestFlinkCatalogs.createCatalogIfNotExists(
                CATALOG,
                LakehouseRestFlinkCatalogs.paimonOptions(
                        FlinkE2eSupport.catalogUrl(), CATALOG, FlinkE2eSupport.token())));

        // 用例要能反复跑：先按「表 → 库」的顺序清干净，再建。
        // 与 Iceberg 那个类同样的顺序（见那边的说明）：先建库，再删表，最后重建。
        FlinkE2eSupport.execute(env, "CREATE DATABASE IF NOT EXISTS " + CATALOG + "." + DATABASE);
        FlinkE2eSupport.execute(env, "DROP TABLE IF EXISTS " + TABLE);
        FlinkE2eSupport.execute(env, "DROP DATABASE " + CATALOG + "." + DATABASE);
        FlinkE2eSupport.execute(env, "CREATE DATABASE " + CATALOG + "." + DATABASE);
        // 主键由 Flink 的 DDL 声明、Paimon 落成 primary key 表；bucket=1 让两次写入
        // 落在同一个桶里，读的时候才走合并那条路径（多桶时同一主键也只会落在同一桶，
        // 但单桶能把「合并」与「分桶路由」两件事解耦，排查时少一个变量）。
        //
        // **sequence.field 不能省**：Paimon 主键表按 sequence 判「谁更新」，批量 INSERT
        // 不指定它时两条记录的 sequence 相同，先后就是**未定义**的——同一份用例在
        // Flink 1.20 上碰巧读到新值、在 2.3 上读到旧值（实测踩过）。用 updated_at 当
        // sequence，语义变成「时间晚的胜出」，两个版本都确定。
        FlinkE2eSupport.execute(env, "CREATE TABLE " + TABLE + " ("
                + "id INT, name STRING, amount DECIMAL(10, 2), updated_at TIMESTAMP(3), "
                + "PRIMARY KEY (id) NOT ENFORCED) "
                + "WITH ('bucket' = '1', 'sequence.field' = 'updated_at')");
    }

    @Test
    @Order(1)
    void writesAndReadsRows() {
        FlinkE2eSupport.execute(env, "INSERT INTO " + TABLE + " VALUES "
                + "(1, 'apple', CAST(10.00 AS DECIMAL(10, 2)), TIMESTAMP '2026-10-20 10:00:00'), "
                + "(2, 'banana', CAST(20.00 AS DECIMAL(10, 2)), TIMESTAMP '2026-10-20 10:00:00')");

        assertEquals(2L, count(), "两行写入后行数应为 2");
        assertEquals(0, new BigDecimal("30.00").compareTo(sum()), "amount 合计应为 30.00");
    }

    @Test
    @Order(2)
    void primaryKeyUpdateReplacesExistingRow() {
        // 同一个主键、新的名称与金额，且 updated_at 更晚：Paimon 的主键表按
        // (主键, sequence) 合并，sequence 大的那条胜出。
        FlinkE2eSupport.execute(env, "INSERT INTO " + TABLE + " VALUES "
                + "(1, 'apple-2', CAST(99.00 AS DECIMAL(10, 2)), TIMESTAMP '2026-10-20 11:00:00')");

        assertEquals(2L, count(), "主键更新不应新增行：仍是 2 行");
        List<Row> rows = FlinkE2eSupport.query(env,
                "SELECT id, name, amount FROM " + TABLE + " WHERE id = 1");
        assertEquals(1, rows.size(), "主键 1 只应有一行");
        Row row = rows.get(0);
        assertEquals("apple-2", row.getField(1), "名称应被后写的那条覆盖");
        assertEquals(0, new BigDecimal("99.00").compareTo((BigDecimal) row.getField(2)),
                "金额应被后写的那条覆盖");
        assertEquals(0, new BigDecimal("119.00").compareTo(sum()),
                "合计应为 20.00 + 99.00：既没丢行，也没重复计旧值");
    }

    @Test
    @Order(3)
    void serverStoresPrimaryKeyMetadata() {
        // 引擎侧读到的行为已经验过；这里再看一眼服务端**存下来的**元数据，
        // 确认主键不是只在客户端里存在（那样换个引擎读就没了）。
        HttpResponse<String> response = FlinkE2eSupport.send("GET",
                "/v1/" + CATALOG + "/databases/" + DATABASE + "/tables/pk_orders", null);
        assertEquals(200, response.statusCode(), "读表元数据失败：" + response.body());
        String compact = response.body().replace(" ", "");
        assertTrue(compact.contains("\"primaryKeys\""), "服务端元数据里应带 primaryKeys：" + response.body());
        assertTrue(compact.contains("\"id\""), "主键应为 id：" + response.body());
    }

    private static long count() {
        return (Long) FlinkE2eSupport.query(env, "SELECT COUNT(*) FROM " + TABLE).get(0).getField(0);
    }

    private static BigDecimal sum() {
        return (BigDecimal) FlinkE2eSupport.query(env, "SELECT SUM(amount) FROM " + TABLE).get(0).getField(0);
    }
}
