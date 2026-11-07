package io.github.melin.lakehouse.rest.optimizer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.melin.lakehouse.rest.scan.ScanProtocol.ScanItemReport;
import io.github.melin.lakehouse.rest.scan.ScanProtocol.ScanJobSpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 扫描任务与服务端之间的通道：拉执行清单（分页）+ 逐表回报。
 *
 * <p>与 {@link OptimizationApiClient} 同构（只用 JDK 的 {@code HttpClient} + Jackson 2），
 * 但**协议模型来自内核模块**（{@link ScanJobSpec} / {@link ScanItemReport}）而不是在这里
 * 再写一份 record：两端按同一份定义读写，字段名改了一边的可能性就不存在了。
 *
 * <p><b>凭据只有扫描令牌</b>（请求头 {@code X-Sensitivity-Scan-Token}）：它在**提交那一刻**
 * 由服务端生成、作为容器参数传入，库里只存它的 SHA-256。作业没有主体身份，两个端点也
 * 因此在 RBAC 之外——这里就不该（也不能）携带任何用户令牌。
 *
 * <p><b>清单要分页拉，回报不用。</b>一次 catalog 级扫描可能有几千张表，清单是「每张表
 * 几十列」的量级；回报是一条几十字节的小 JSON。因此这里只给清单加了「更长的超时」——
 * 服务端要先把范围内所有表从 {@code lakehouse_table} 里解析出来再切片，那是全表走一遍，
 * 用回报那个 30 秒超时会让作业在第一个请求上就失败。
 */
final class ScanApiClient implements AutoCloseable {

    /** 回调令牌的请求头名，与服务端 {@code SensitivityScanController.TOKEN_HEADER} 一致。 */
    private static final String TOKEN_HEADER = "X-Sensitivity-Scan-Token";

    /** 回报最多尝试几次。三次覆盖「一台服务端关闭 + 一次网络抖动」（与优化任务同一个数）。 */
    private static final int REPORT_ATTEMPTS = 3;

    /** 退避基数（毫秒）：第 n 次失败后等 {@code n × 基数}。 */
    private static final long REPORT_BACKOFF_MILLIS = 300L;

    /**
     * 清单请求的超时是回报的几倍。
     *
     * <p>四个不是随手取的：这是一个「用户点了扫描、作业刚开始跑」的位置，等三十秒然后
     * 整轮失败，比多等两分钟要糟得多——失败之后要重来一遍，而重来仍然要走同一个慢查询。
     */
    private static final int PAGE_TIMEOUT_MULTIPLIER = 4;

    private final ObjectMapper mapper = new ObjectMapper()
            // 服务端将来加字段时不该让在跑的作业解析失败（与优化任务同一条）
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final HttpClient http;
    private final String baseUrl;
    private final String token;
    private final Duration timeout;

    ScanApiClient(String baseUrl, String token, Duration timeout) {
        this.baseUrl = JobArgs.trimSlash(baseUrl);
        this.token = token;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /**
     * 拉一页执行清单。
     *
     * @param offset 起始下标；从 0 开始，每次按 {@code spec.limit()} 前进
     * @param limit  本页上限
     */
    ScanJobSpec page(String scanId, int offset, int limit) {
        HttpRequest request = HttpRequest.newBuilder(
                        uri("/sensitivity-scans/" + scanId + "/submission?offset=" + offset
                                + "&limit=" + limit))
                .timeout(timeout.multipliedBy(PAGE_TIMEOUT_MULTIPLIER))
                .header(TOKEN_HEADER, token)
                .GET()
                .build();
        HttpResponse<String> response = send(request, "fetch page " + offset + " of scan " + scanId);
        try {
            return mapper.readValue(response.body(), ScanJobSpec.class);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot parse page " + offset + " of scan " + scanId
                    + ": " + response.body(), exception);
        }
    }

    /**
     * 回报一张表的结果。
     *
     * <p>**「扫了但没命中」也要调这里**（{@code findings} 为空、状态 {@code SCANNED}）：
     * 服务端的覆盖率靠这个区分「扫完没发现」与「漏扫」，而两者在控制台上长得一模一样。
     *
     * <p>内部自带有限次**退避**重试：网络抖动、服务端滚动重启都会让一次回报失败，而
     * 「表已经扫完但结果没记上」比「没扫」更难排查——用户看到部分失败，却不知道
     * 那几张表其实扫过。退避而不是立刻重发：多副本部署下最常见的一次失败是「那台服务端
     * 刚好被滚动掉」，立刻重发只会打到同一个正在关闭的连接上。
     *
     * <p><b>不重试 4xx。</b>令牌错、扫描已取消、扫描不是 {@code execution=SPARK} 都是
     * 确定性失败，重试三次只会让作业多花一秒、日志里多两条同样的报错。区分方式是让
     * {@link #send} 把状态码带进异常，这里按状态码判断。
     */
    void report(String scanId, ScanItemReport report) {
        String payload;
        try {
            payload = mapper.writeValueAsString(report);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot serialize the report of "
                    + report.database() + "." + report.table(), exception);
        }
        HttpRequest request = HttpRequest.newBuilder(uri("/sensitivity-scans/" + scanId + "/items"))
                .timeout(timeout)
                .header(TOKEN_HEADER, token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        RuntimeException last = null;
        for (int attempt = 1; attempt <= REPORT_ATTEMPTS; attempt++) {
            try {
                send(request, "report " + report.database() + "." + report.table());
                return;
            } catch (RuntimeException exception) {
                last = exception;
                if (isPermanent(exception) || attempt == REPORT_ATTEMPTS) {
                    break;
                }
                backoff(attempt, report, exception);
            }
        }
        throw last == null ? new IllegalStateException("cannot report "
                + report.database() + "." + report.table()) : last;
    }

    /**
     * 4xx 是确定性失败，重试没有意义；5xx 与 IO 异常才值得再试。
     *
     * <p>判据落在消息里的状态码上而不是单开一个异常类型：这条链路只有本类用，
     * 为一个布尔判断引入一个异常子类反而让 {@link #send} 更难读。
     */
    private static boolean isPermanent(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || !message.contains("HTTP ")) {
            return false;
        }
        int index = message.indexOf("HTTP ") + "HTTP ".length();
        try {
            int status = Integer.parseInt(message.substring(index, index + 3));
            return status >= 400 && status < 500;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static void backoff(int attempt, ScanItemReport report, RuntimeException cause) {
        try {
            Thread.sleep(REPORT_BACKOFF_MILLIS * attempt);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while retrying the report of "
                    + report.database() + "." + report.table(), cause);
        }
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

    @Override
    public void close() {
        // JDK 的 HttpClient 在 Java 17 上不可关闭（close 是 21 之后加的），这里只留空实现。
    }
}
