package io.github.melin.lakehouse.rest.flink;

import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.types.Row;
import org.apache.flink.util.CloseableIterator;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 需要真实服务端的 Flink 用例共用的支撑代码。
 *
 * <p><b>与 Spark 那份的区别。</b>Spark 侧一个 JVM 只能有一个 SparkContext，
 * 所以 {@code LiveSparkSession} 是「唯一会话入口」；Flink 的
 * {@code TableEnvironment} 可以并存（每个自带一个 mini cluster），因此这里只提供
 * 「怎么建、怎么跑 SQL」，不强制单例——但两个测试类仍各自只建一个，避免不必要的集群启动。
 *
 * <p><b>目标地址取自系统属性</b>，未配置时 {@link #available()} 为 false，各用例
 * 据此 skip（见 {@code scripts/e2e-flink-sql.sh}）。这样 {@code mvn test} 在没有服务端时
 * 仍然是绿的，不会把「没起服务端」误报成回归。
 */
final class FlinkE2eSupport {

    /** 管理 API 在服务端上的固定前缀，用来反推 catalog API 的基址。 */
    private static final String MANAGEMENT_PATH = "/api/management/v1";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private FlinkE2eSupport() {
    }

    /** 管理 API 基址，例如 {@code http://127.0.0.1:18081/api/management/v1}。 */
    static String managementUrl() {
        return System.getProperty("e2e.management.url", "");
    }

    /** 调用者令牌；服务端以它认证出主体名，RBAC 再判定能做什么。 */
    static String token() {
        return System.getProperty("e2e.management.token", "root");
    }

    /** 是否给了目标地址。没给就说明没打算跑端到端用例。 */
    static boolean available() {
        return !managementUrl().isEmpty();
    }

    /** LakeHouse Catalog 的基址：管理 API 去掉固定前缀，两者本来就挂在同一个服务端上。 */
    static String catalogUrl() {
        String managementUrl = managementUrl();
        return managementUrl.endsWith(MANAGEMENT_PATH)
                ? managementUrl.substring(0, managementUrl.length() - MANAGEMENT_PATH.length())
                : managementUrl;
    }

    /**
     * 确保服务端上有一个指定格式的 catalog。
     *
     * <p>为什么不在 SQL 里让客户端自己登记：{@code GET /v1/config?warehouse=<prefix>}
     * 是**连接阶段**，那时客户端还没说过自己要哪种格式，服务端的自动登记按部署默认值走
     * （默认 PAIMON）。一旦以 Paimon 登记，这个 prefix 上每一次 Iceberg 调用都会以
     * {@code is a PAIMON catalog} 失败，而且错是第一次请求自己留下的。
     *
     * <p>幂等：已存在（409 / 200）时不再建，但要核对格式——同名 prefix 若是另一种格式，
     * 继续跑只会得到一串与真正原因无关的 404，不如在这里明确失败。
     */
    static void ensureCatalog(String catalog, String format) {
        HttpResponse<String> existing = send("GET", MANAGEMENT_PATH + "/catalogs/" + catalog, null);
        if (existing.statusCode() == 200) {
            requireFormat(catalog, format, existing.body());
            return;
        }
        String location = "file://" + System.getProperty("java.io.tmpdir") + "/lakehouse-rest-flink-e2e/" + catalog;
        String body = "{\"catalog\":{\"type\":\"INTERNAL\",\"name\":\"" + catalog + "\","
                + "\"catalogFormat\":\"" + format + "\",\"properties\":{},"
                + "\"storageConfigInfo\":{\"storageType\":\"FILE\",\"allowedLocations\":[\"" + location + "\"]}}}";
        HttpResponse<String> created = send("POST", MANAGEMENT_PATH + "/catalogs", body);
        int status = created.statusCode();
        if (status != 201 && status != 409) {
            throw new IllegalStateException("创建 " + format + " catalog 「" + catalog + "」失败：POST /catalogs -> "
                    + status + " " + created.body());
        }
        HttpResponse<String> after = send("GET", MANAGEMENT_PATH + "/catalogs/" + catalog, null);
        if (after.statusCode() != 200) {
            throw new IllegalStateException("catalog 「" + catalog + "」建完之后读不到："
                    + after.statusCode() + " " + after.body());
        }
        requireFormat(catalog, format, after.body());
    }

    private static void requireFormat(String catalog, String format, String body) {
        String expected = "\"catalogFormat\":\"" + format + "\"";
        String compact = body.replace(" ", "");
        if (!compact.contains(expected)) {
            int at = compact.indexOf("\"catalogFormat\"");
            String actual = at < 0 ? "(未返回 catalogFormat)" : compact.substring(at, Math.min(compact.length(), at + 40));
            throw new IllegalStateException("prefix 「" + catalog + "」已存在且不是 " + format
                    + " 格式（" + actual + "）。同一个 prefix 不能同时承载两种格式，请换一个名字。");
        }
    }

    /** 批模式表环境。两个连接器在流模式下要 checkpoint 才会真正落数据，批模式省掉这一层。 */
    static TableEnvironment batchEnvironment() {
        return TableEnvironment.create(EnvironmentSettings.newInstance().inBatchMode().build());
    }

    /** 执行一条 DDL / DML 并等它结束。 */
    static void execute(TableEnvironment env, String sql) {
        try {
            env.executeSql(sql).await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("执行 SQL 时被中断：" + sql, e);
        } catch (Exception e) {
            // 批模式下 await() 会把作业失败包成 ExecutionException；把原句带上，
            // 否则日志里只剩一个「作业失败」，看不出是哪一句。
            throw new IllegalStateException("执行 SQL 失败：" + sql, e);
        }
    }

    /** 执行查询并取出全部行（调用方在 SQL 里自己 ORDER BY，别依赖返回顺序）。 */
    static List<Row> query(TableEnvironment env, String sql) {
        List<Row> rows = new ArrayList<>();
        TableResult result = env.executeSql(sql);
        try (CloseableIterator<Row> iterator = result.collect()) {
            while (iterator.hasNext()) {
                rows.add(iterator.next());
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取查询结果失败：" + sql, e);
        }
        return rows;
    }

    /** 读服务端原始响应，用来核对「引擎写下的东西服务端真的存成了这样」。 */
    static HttpResponse<String> send(String method, String path, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(catalogUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token())
                .header("Accept", "application/json");
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        try {
            return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(method + " " + path + " 失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(method + " " + path + " 被中断", e);
        }
    }
}
