package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.Json;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link GcsAccessTokenClient} 的真实实现：一次 JWT-bearer 授权。
 *
 * <p><b>请求体是表单，不是 JSON。</b>Google 的 OAuth 端点只收
 * {@code application/x-www-form-urlencoded}；发 JSON 回的是
 * {@code Invalid JSON payload received. Unknown name "@type"} 之类，与「密钥对不对」
 * 完全无关。两个参数：{@code grant_type} 固定为
 * {@code urn:ietf:params:oauth:grant-type:jwt-bearer}，{@code assertion} 是上一步签的 JWT。
 *
 * <p><b>错误信息原样带出来。</b>这一档的失败通常是「服务账号没有权限」或
 * 「私钥与服务账号不匹配」，两者都只有 {@code invalid_grant} 这一个 code——
 * 把 {@code error_description} 丢掉，报障时就只剩「换不到令牌」这一句。
 */
@Component
public class HttpGcsAccessTokenClient implements GcsAccessTokenClient {

    private static final Logger log = LoggerFactory.getLogger(HttpGcsAccessTokenClient.class);

    private static final String JWT_BEARER =
            "urn:ietf:params:oauth:grant-type:jwt-bearer";

    @Override
    public GcsAccessToken mint(GcsServiceAccount account) {
        String assertion = GcsJwtAssertion.build(account, Instant.now());
        String form = "grant_type=" + encode(JWT_BEARER) + "&assertion=" + encode(assertion);

        HttpRequest request = HttpRequest.newBuilder(URI.create(account.tokenUri()))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = send(request);
        Map<?, ?> parsed = parseJson(response.body());
        Object token = parsed.get("access_token");
        if (response.statusCode() / 100 != 2 || token == null) {
            Object error = parsed.get("error");
            Object description = parsed.get("error_description");
            log.warn("gcs jwt-bearer exchange rejected for {} (HTTP {}): {} {}",
                    account.clientEmail(), response.statusCode(), error, description);
            throw new ApiException(503, null, null,
                    "GCS refused to mint an access token for " + account.clientEmail()
                            + ": HTTP " + response.statusCode()
                            + (error == null ? "" : " " + error)
                            + (description == null ? "" : " (" + description + ")")
                            + "; check that the service account key is still valid and the"
                            + " account still exists");
        }
        Instant expiresAt = null;
        Object expiresIn = parsed.get("expires_in");
        if (expiresIn instanceof Number number) {
            expiresAt = Instant.now().plusSeconds(number.longValue());
        }
        return new GcsAccessToken(String.valueOf(token), expiresAt);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static HttpResponse<String> send(HttpRequest request) {
        try {
            return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
                    .send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, null, null, "the GCS token request was interrupted");
        } catch (Exception failure) {
            throw new ApiException(503, null, null,
                    "the GCS token request failed: " + failure.getClass().getSimpleName()
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
