package io.github.melin.lakehouse.rest.service.exec;

import io.fabric8.kubernetes.client.OAuthTokenProvider;
import io.github.melin.lakehouse.rest.support.Json;
import io.github.melin.lakehouse.rest.support.ProbeReason;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * AKS 的令牌：AAD 客户端凭据换一个 access token。
 *
 * <p><b>与参考实现（{@code bee} 里的 {@code AzureAksOAuthTokenProvider}）有两处必须改：</b>
 * <ol>
 *   <li><b>scope 不能是管理面。</b>参考实现取的是
 *       {@code https://management.core.windows.net//.default}，那是 Azure **管理面**的 audience；
 *       AKS 的 apiserver 校验的是它自己的 AAD server application id
 *       （{@code 6dae42f8-4368-4678-94ff-3960e28e3630}）。用管理面 scope 会得到
 *       {@code 401 Unauthorized: Invalid audience}——这个错误看起来像「服务主体没授权」，
 *       实际是 audience 不对，非常容易误诊。因此 scope 是**可填字段**，默认 AKS audience。</li>
 *   <li><b>不设 subscriptionId。</b>参考实现里它构造后从未被读取：一个填了也不影响结果的必填框，
 *       只会在被人问起时解释不清。集群在哪个订阅是「查文档」的事，不是服务端要知道的事。</li>
 * </ol>
 *
 * <p><b>不走 azure-identity SDK。</b>服务端只需要一件事——一次 {@code client_credentials}
 * 换令牌的 POST。为此引入 MSAL4J 那一整套（约 20 MB、带 JNA 与交互式登录的实现）不划算；
 * 令牌请求本身就是三个表单字段。
 *
 * <p><b>令牌按到期时间缓存。</b>与 EKS 不同：AAD 令牌 1 小时有效，而调度节拍是 10 秒一次，
 * 不缓存等于每 10 秒换一次令牌（既慢又可能被 AAD 限流）。提前 2 分钟刷新，
 * 留出的余量够一次请求往返。
 */
public class AzureAksTokenProvider implements OAuthTokenProvider {

    /** AKS 的 apiserver 自己的 AAD audience（公共云与中国云 app id 相同，authority 不同）。 */
    public static final String AKS_AUDIENCE = "6dae42f8-4368-4678-94ff-3960e28e3630/.default";

    /** 云环境。只留两个：其余主权云没有环境可验，写进来就是「编译得过、跑不了」。 */
    public enum Authority {
        PUBLIC("https://login.microsoftonline.com", "公共云"),
        CHINA("https://login.chinacloudapi.cn", "中国云");

        private final String host;

        private final String label;

        Authority(String host, String label) {
            this.host = host;
            this.label = label;
        }

        public String host() {
            return host;
        }

        public String label() {
            return label;
        }

        public static Authority of(String value) {
            if (value == null || value.isBlank()) {
                return PUBLIC;
            }
            return "CHINA".equalsIgnoreCase(value.trim()) ? CHINA : PUBLIC;
        }
    }

    private final Authority authority;

    private final String tenantId;

    private final String clientId;

    private final String clientSecret;

    private final String scope;

    /** 缓存的令牌与到期时刻；{@code null} 表示还没换过。 */
    private volatile Cached cached;

    public AzureAksTokenProvider(Authority authority, String tenantId, String clientId,
                                 String clientSecret, String scope) {
        this.authority = authority == null ? Authority.PUBLIC : authority;
        this.tenantId = tenantId;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.scope = scope == null || scope.isBlank() ? AKS_AUDIENCE : scope.trim();
    }

    @Override
    public String getToken() {
        Cached current = cached;
        // 提前 2 分钟视为过期：一次请求可能因为集群过载跑上几十秒，卡在边界上会拿到刚过期的令牌
        if (current != null && Instant.now().isBefore(current.expiresAt().minus(Duration.ofMinutes(2)))) {
            return current.token();
        }
        return refresh().token();
    }

    /**
     * 换一次令牌（探活第 1 步用它，顺带拿到到期时间回显）。
     *
     * @throws ExecutionProbeException 凭据不对 / 出不了网 / scope 不认，三者分开说
     */
    public Cached refresh() {
        String form = "grant_type=client_credentials"
                + "&client_id=" + encode(clientId)
                + "&client_secret=" + encode(clientSecret)
                + "&scope=" + encode(scope);
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(authority.host() + "/" + tenantId + "/oauth2/v2.0/token"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response;
        try {
            response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
                    .send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw ExecutionProbeException.of(ProbeReason.NETWORK, "换取 AAD 令牌被中断", "重试一次");
        } catch (Exception failure) {
            throw new ExecutionProbeException(ProbeReason.NETWORK,
                    "连不上 " + authority.host() + "（" + failure.getClass().getSimpleName() + "）",
                    "服务端要能访问 " + authority.host() + "；内网无出口时用中国云对应域名，或走出口代理",
                    failure);
        }
        Map<?, ?> body = parse(response.body());
        String token = text(body.get("access_token"));
        if (response.statusCode() == 200 && token != null) {
            long expiresIn = body.get("expires_in") instanceof Number number ? number.longValue() : 3600L;
            Cached fresh = new Cached(token, Instant.now().plusSeconds(expiresIn), scope);
            cached = fresh;
            return fresh;
        }
        String error = text(body.get("error"));
        String description = text(body.get("error_description"));
        throw new ExecutionProbeException(ProbeReason.CREDENTIAL_INVALID,
                "AAD 拒绝签发令牌：" + (error == null ? "HTTP " + response.statusCode() : error)
                        + (description == null ? "" : "（" + firstLine(description) + "）"),
                conclude(error), null);
    }

    private static String conclude(String error) {
        if (error == null) {
            return "检查 tenantId / clientId / clientSecret（三者都属于同一个 AAD 应用）";
        }
        return switch (error) {
            case "invalid_client" -> "AAD 不认这个应用或密钥：确认 clientId 与 clientSecret 是一对，"
                    + "且密钥没过期（AAD 的密钥最长 2 年）";
            case "unauthorized_client" -> "这个应用不允许客户端凭据流：让管理员授予应用权限，或用证书凭据";
            case "invalid_scope" -> "scope 不认：要 AKS 的 audience（" + AKS_AUDIENCE + "），不是管理面的";
            default -> "按 AAD 返回的 error 检查应用注册";
        };
    }

    private static Map<?, ?> parse(String body) {
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

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** AAD 的 error_description 很长（带跟踪 id 与时间戳），列表上一行放不下。 */
    private static String firstLine(String description) {
        int index = description.indexOf('\n');
        String line = index > 0 ? description.substring(0, index) : description;
        return line.length() > 160 ? line.substring(0, 160) + "…" : line;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /** 一次换到的令牌。{@code scope} 一起存：换了 scope 之后的缓存不该继续用。 */
    public record Cached(String token, Instant expiresAt, String scope) {
    }
}
