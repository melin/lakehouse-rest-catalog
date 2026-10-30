package io.github.melin.lakehouse.rest.engine.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * 走 HTTP 的换票实现：两个 grant 都发到同一个令牌端点。
 *
 * <p><b>HTTP 客户端只用 JDK 自带的 {@link HttpClient}。</b>这个 jar 会被放进 Spark 的
 * {@code jars/} 与 Flink 的 {@code lib/}，多引一个 HTTP 库就是多一处与发行版的版本竞争。
 *
 * <p><b>凭据走请求体，不走 HTTP Basic。</b>本服务端两种都收（RFC 6749 §2.3.1），
 * 但 Basic 要求先把 {@code clientId:clientSecret} 做 form-urlencode 再 base64——
 * 密钥里出现 {@code :} 或非 ASCII 时，写错这一步的表现是 401 {@code invalid_client}，
 * 而它看起来像「密钥不对」。请求体只需普通 form 编码，少一个会写错的步骤。
 *
 * <p><b>不重试。</b>重试策略在 {@link RefreshingToken} 里（按剩余有效期与退避决定
 * 下一次尝试的时机），在这里再套一层会让「并发 8 个请求只发 1 次换票」变成 8 次。
 */
final class HttpTokenClient implements TokenClient {

    private static final String GRANT_TOKEN_EXCHANGE = "urn:ietf:params:oauth:grant-type:token-exchange";

    private static final String TOKEN_TYPE_ACCESS_TOKEN = "urn:ietf:params:oauth:token-type:access_token";

    private static final String GRANT_CLIENT_CREDENTIALS = "client_credentials";

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private final URI endpoint;

    private final String clientId;

    private final String clientSecret;

    /** {@code oauth2.scope}；为空则**不发这个参数**，由服务端按默认值归一。 */
    private final String scope;

    private final HttpClient http;

    private final Duration timeout;

    private final ObjectMapper mapper = new ObjectMapper();

    HttpTokenClient(URI endpoint, String clientId, String clientSecret, String scope, Duration timeout) {
        this.endpoint = endpoint;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.scope = scope;
        this.timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        this.http = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    @Override
    public boolean hasCredential() {
        return clientId != null && !clientId.isBlank();
    }

    @Override
    public IssuedToken exchange(String subjectToken) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", GRANT_TOKEN_EXCHANGE);
        form.put("subject_token", subjectToken);
        form.put("subject_token_type", TOKEN_TYPE_ACCESS_TOKEN);
        // scope 刻意不发：服务端对缺省值归一成 PRINCIPAL_ROLE:ALL，而本工程的 scope
        // 只有这一档。发一个 scope 出去的唯一效果是——写错了就变成 invalid_scope，
        // 且要等到第一次刷新（默认 55 分钟后）才看得见（设计文档 §4.1 规则 4）
        addScope(form);
        return post(form);
    }

    @Override
    public IssuedToken clientCredentials() {
        if (!hasCredential()) {
            throw new IllegalStateException("no client credential is configured for " + endpoint);
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", GRANT_CLIENT_CREDENTIALS);
        form.put("client_id", clientId);
        form.put("client_secret", clientSecret == null ? "" : clientSecret);
        addScope(form);
        return post(form);
    }

    private void addScope(Map<String, String> form) {
        if (scope != null && !scope.isBlank()) {
            form.put("scope", scope);
        }
    }

    private IssuedToken post(Map<String, String> form) {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(encode(form), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new TokenRefreshException(0, null,
                    "cannot reach the token endpoint " + endpoint + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TokenRefreshException(0, null, "interrupted while calling " + endpoint, e);
        }
        return parse(response.statusCode(), response.body());
    }

    /**
     * 解析响应。
     *
     * <p>{@code expires_in} 用 {@code Long} 而不是 {@code long}：缺省与 0 是两件事
     * （前者可以去读 JWT 的 {@code exp}，后者是「这张票当场过期」），
     * 见 {@link IssuedToken}。
     */
    private IssuedToken parse(int status, String body) {
        JsonNode node;
        try {
            node = body == null || body.isBlank() ? mapper.createObjectNode() : mapper.readTree(body);
        } catch (IOException e) {
            throw new TokenRefreshException(status, null,
                    "token endpoint returned a body that is not valid JSON: " + e.getMessage(), e);
        }
        if (status != 200) {
            String error = text(node, "error");
            String description = text(node, "error_description");
            throw new TokenRefreshException(status, error,
                    "token endpoint refused the request: status=" + status
                            + (error == null ? "" : ", error=" + error)
                            + (description == null ? "" : ", description=" + description));
        }
        String token = text(node, "access_token");
        if (token == null || token.isBlank()) {
            throw new TokenRefreshException(status, null,
                    "token endpoint returned 200 without access_token");
        }
        Long expiresIn = node.hasNonNull("expires_in") ? node.get("expires_in").asLong() : null;
        return new IssuedToken(token, expiresIn, text(node, "scope"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String encode(Map<String, String> form) {
        StringJoiner joiner = new StringJoiner("&");
        for (Map.Entry<String, String> entry : form.entrySet()) {
            joiner.add(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                    + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return joiner.toString();
    }

    @Override
    public String toString() {
        return "HttpTokenClient(" + endpoint + ")";
    }
}
