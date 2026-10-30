package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link GcsDownscopeClient} 的真实实现：一次 {@code token-exchange}。
 *
 * <p><b>请求体的三个反直觉之处，都在这里显式写出来：</b>
 *
 * <ol>
 *   <li><b>{@code options} 是字符串，不是对象。</b>它的内容是
 *       {@code {"accessBoundary":{...}}} 这串 JSON 的**文本**。传对象会被回
 *       {@code Invalid JSON payload}，而错误信息不指向嵌套层数。</li>
 *   <li><b>{@code grantType} 用的是 {@code urn:ietf:params:oauth:grant-type:token-exchange}，
 *       不是 OAuth2 常见的 {@code authorization_code}。</b>写错时是一个与凭据无关的
 *       {@code invalid_grant}。</li>
 *   <li><b>{@code subjectTokenType} 与 {@code requestedTokenType} 都要写</b>，
 *       前者是「我拿什么来换」（{@code .../access_token}），后者是「我要换出什么」
 *       （同一个值）。少一个都会被拒。</li>
 * </ol>
 *
 * <p><b>为什么不缓存。</b>交换结果的作用面是**一次调用**的表前缀，与 S3 的票一样
 * 没有跨表复用的意义；而上层的 {@code StorageCredentialCache} 已经按
 * 「剩余寿命 × 比例」缓存了整份凭据。在这里再缓存一层，只会让「换了配置但票没变」
 * 变成一个新的现象。
 */
@Component
public class HttpGcsDownscopeClient implements GcsDownscopeClient {

    private static final Logger log = LoggerFactory.getLogger(HttpGcsDownscopeClient.class);

    private static final String TOKEN_EXCHANGE =
            "urn:ietf:params:oauth:grant-type:token-exchange";

    private static final String ACCESS_TOKEN_TYPE =
            "urn:ietf:params:oauth:token-type:access_token";

    @Override
    public GcsDownscopedToken downscope(GcsDownscopeRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("grantType", TOKEN_EXCHANGE);
        body.put("requestedTokenType", ACCESS_TOKEN_TYPE);
        body.put("subjectToken", request.subjectToken());
        body.put("subjectTokenType", ACCESS_TOKEN_TYPE);
        // 这一行的值是别的 JSON 的文本，不是对象——见类注释第一条
        body.put("options", request.options());

        HttpRequest http = HttpRequest.newBuilder(URI.create(
                        endpoint(request.endpoint()) + "/v1/token"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = send(http);
        Map<?, ?> parsed = parseJson(response.body());
        Object token = parsed.get("access_token");
        if (response.statusCode() / 100 != 2 || token == null) {
            Object error = parsed.get("error");
            Object description = parsed.get("error_description");
            log.warn("gcs token exchange rejected (HTTP {}): {} {}", response.statusCode(),
                    error, description);
            throw new ApiException(503, null, null,
                    "GCS refused to downscope the access token: HTTP " + response.statusCode()
                            + (error == null ? "" : " " + error)
                            + (description == null ? "" : " (" + description + ")")
                            + "; the source credential needs"
                            + " roles/iam.serviceAccountTokenCreator to exchange tokens");
        }
        Instant expiresAt = null;
        Object expiresIn = parsed.get("expires_in");
        if (expiresIn instanceof Number number) {
            expiresAt = Instant.now().plusSeconds(number.longValue());
        }
        return new GcsDownscopedToken(String.valueOf(token), expiresAt);
    }

    /** 端点补默认值并去掉结尾斜杠：配置写了 {@code https://sts.googleapis.com/} 时别拼出两个。 */
    private static String endpoint(String configured) {
        String value = configured == null || configured.isBlank()
                ? "https://sts.googleapis.com" : configured.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static HttpResponse<String> send(HttpRequest request) {
        try {
            return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
                    .send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, null, null, "the GCS token exchange was interrupted");
        } catch (Exception failure) {
            throw new ApiException(503, null, null,
                    "the GCS token exchange failed: " + failure.getClass().getSimpleName()
                            + (failure.getMessage() == null ? "" : " " + failure.getMessage()));
        }
    }

    private static Map<?, ?> parseJson(String body) {
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        try {
            Object parsed = Json.read(body);
            return parsed instanceof Map<?, ?> map ? map : Map.of();
        } catch (RuntimeException malformed) {
            return Map.of();
        }
    }
}
