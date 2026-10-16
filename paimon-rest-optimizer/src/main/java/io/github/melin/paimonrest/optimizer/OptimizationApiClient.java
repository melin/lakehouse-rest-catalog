package io.github.melin.paimonrest.optimizer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 优化任务与服务端之间的回调通道。
 *
 * <p>只用 {@link java.net.http.HttpClient} + Jackson，不引 HTTP 客户端库：调用面就两个端点、
 * 两种方法，而本模块跑在集群上，类路径越接近「Spark 发行版自带的东西」越不容易出意外。
 *
 * <p><b>凭据只有批次令牌</b>（请求头 {@code X-Optimization-Token}）：它由服务端在
 * 提交 k8s Job 时作为容器参数传入，作用域是「这一个批次的表级结果上报」。
 * 任务没有主体身份，因此这两个端点也不参与 RBAC——服务端那边刻意如此设计，
 * 这里就不需要（也不该）携带任何用户令牌。
 *
 * <p><b>回调必须能被重试</b>：网络抖动、服务端滚动重启都会让一次上报失败，而
 * 「表已经优化完但结果没记上」比「没优化」更难排查——用户看到批次部分失败，
 * 却不知道数据其实已经合过了。因此 {@link #report} 内部自带有限次重试，
 * 且成功判据只看 HTTP 状态码（服务端上是幂等写入，同一 item 重复上报以最后一次为准）。
 */
public final class OptimizationApiClient implements AutoCloseable {

    /** 回调令牌的请求头名，与服务端 {@code OptimizationController.TOKEN_HEADER} 一致。 */
    private static final String TOKEN_HEADER = "X-Optimization-Token";

    private static final int REPORT_ATTEMPTS = 3;

    private final ObjectMapper mapper = new ObjectMapper()
            // 服务端将来加字段时不该让在跑的任务解析失败
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final HttpClient http;
    private final String baseUrl;
    private final String token;
    private final Duration timeout;

    public OptimizationApiClient(String baseUrl, String token, Duration timeout) {
        this.baseUrl = trimSlash(baseUrl);
        this.token = token;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /** 执行清单：本批次要处理的表与每张表生效的参数。 */
    public Plan fetchPlan(String jobId) {
        HttpRequest request = HttpRequest.newBuilder(uri("/optimization/jobs/" + jobId + "/submission"))
                .timeout(timeout)
                .header(TOKEN_HEADER, token)
                .GET()
                .build();
        HttpResponse<String> response = send(request, "fetch the execution plan of batch " + jobId);
        try {
            return mapper.readValue(response.body(), Plan.class);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot parse the execution plan of batch " + jobId
                    + ": " + response.body(), exception);
        }
    }

    /**
     * 上报一张表的结果。
     *
     * @param status {@code SUCCESS} / {@code FAILED} / {@code SKIPPED}
     */
    public void report(String jobId, String itemId, String status,
                       Map<String, String> metrics, String errorMessage) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("metrics", metrics == null ? Map.of() : metrics);
        body.put("errorMessage", errorMessage);
        String payload;
        try {
            payload = mapper.writeValueAsString(body);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot serialize the report of item " + itemId, exception);
        }
        RuntimeException last = null;
        for (int attempt = 1; attempt <= REPORT_ATTEMPTS; attempt++) {
            try {
                send(HttpRequest.newBuilder(uri("/optimization/jobs/" + jobId + "/items/" + itemId))
                        .timeout(timeout)
                        .header(TOKEN_HEADER, token)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .build(), "report the result of item " + itemId);
                return;
            } catch (RuntimeException exception) {
                last = exception;
            }
        }
        throw last == null ? new IllegalStateException("cannot report item " + itemId) : last;
    }

    private HttpResponse<String> send(HttpRequest request, String what) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("cannot " + what + ": HTTP " + status
                        + " from " + request.uri() + ", body=" + response.body());
            }
            return response;
        } catch (IOException exception) {
            throw new IllegalStateException("cannot " + what + ": " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while trying to " + what, exception);
        }
    }

    private URI uri(String path) {
        return URI.create(baseUrl + path);
    }

    private static String trimSlash(String value) {
        String trimmed = value == null ? "" : value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    @Override
    public void close() {
        // JDK 的 HttpClient 在 Java 17 上不可关闭（close 是 21 之后加的），这里只留空实现。
    }

    // ------------------------------------------------------------------ 清单模型

    /** 执行清单（对应服务端 {@code OptimizationDtos.Submission}）。 */
    public record Plan(String jobId, String catalog, String triggerType,
                       List<String> actions, List<Table> tables) {
    }

    /**
     * 清单里的一张表。
     *
     * <p>{@code status} 一并给出，是为了让任务的重启幂等：已经上报过结果的表
     * （终态）不必再跑一遍，重拉清单时跳过即可。{@code options} 是服务端合并好的
     * 全部参数（内置默认 → catalog 默认 → 表级覆盖），任务侧不做任何合并。
     */
    public record Table(String itemId, String table, String status,
                        Map<String, Map<String, String>> options) {

        public boolean settled() {
            return "SUCCESS".equals(status) || "FAILED".equals(status) || "SKIPPED".equals(status);
        }
    }
}
