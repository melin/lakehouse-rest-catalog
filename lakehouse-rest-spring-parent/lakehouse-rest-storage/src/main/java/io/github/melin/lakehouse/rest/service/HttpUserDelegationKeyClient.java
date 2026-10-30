package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.Json;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * {@link UserDelegationKeyClient} 的真实实现：两步 REST + 一次 XML 解析。
 *
 * <p><b>为什么不用 azure SDK。</b>整个动作是「换 AAD 令牌 → 调一个固定端点 → 读六个字段」，
 * 为此引入 {@code azure-storage-blob} 或 MSAL4J（几十 MB，还带交互式登录的实现）
 * 没有收益；本工程已有的 AKS 令牌就是同一个判断（服务端只做一次
 * {@code client_credentials} 的 POST）。代价是签名与 XML 解析要自己写，
 * 但那部分在 {@link AzureSasToken} 里、可以脱离网络测。
 *
 * <p><b>两步的失败要分开报。</b>第一步失败几乎总是应用注册的问题
 * （密钥过期、没授权限、scope 写错），第二步失败才是「这个身份能登录但不能签 SAS」。
 * 合成一条 {@code 503} 之后运维只会去查账户权限，而真正的原因在应用注册那一侧。
 *
 * <p><b>AAD 令牌按「租户 + 应用 + scope」缓存。</b>令牌 1 小时有效，而每次读表都可能
 * 触发一次签发；不缓存等于每张表两次远程调用，还会被 AAD 限流。提前 2 分钟刷新，
 * 与 AKS 那个 provider 的余量一致——一次请求可能跑上几十秒，卡在边界上会拿到刚过期的令牌。
 *
 * <p>委托密钥**不缓存**：它本身是个短命的签名密钥，而 SAS 每次都要按不同的
 * 目录路径与权限重签；缓存密钥只能省掉一次调用，却要把「密钥的窗口」与
 * 「SAS 的窗口」两个时间轴对齐（见 {@link AzureDelegationRequest} 的注释），
 * 换来的复杂度大于收益。
 */
@Component
public class HttpUserDelegationKeyClient implements UserDelegationKeyClient {

    private static final Logger log = LoggerFactory.getLogger(HttpUserDelegationKeyClient.class);

    /** 令牌到期前多久视为失效。 */
    private static final Duration TOKEN_MARGIN = Duration.ofMinutes(2);

    private final Map<String, CachedToken> tokens = new ConcurrentHashMap<>();

    @Override
    public UserDelegationKey requestKey(AzureDelegationRequest request) {
        String token = accessToken(request);
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<KeyInfo>"
                + "<Start>" + AzureSasToken.TIME.format(request.start()) + "</Start>"
                + "<Expiry>" + AzureSasToken.TIME.format(request.expiry()) + "</Expiry>"
                + "</KeyInfo>";
        HttpRequest http = HttpRequest.newBuilder(URI.create(
                        "https://" + request.account() + "." + request.blobHost()
                                + "/?restype=service&comp=userdelegationkey"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + token)
                .header("x-ms-version", AzureSasToken.VERSION)
                .header("Content-Type", "application/xml")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = send(http, "generateUserDelegationKey");
        if (response.statusCode() / 100 != 2) {
            // x-ms-error-code 是唯一能把「身份没权限」与「账户名写错」分开的东西：
            // 前者是 AuthorizationPermissionMismatch，后者是 InvalidAuthenticationInfo 或 404
            String code = response.headers().firstValue("x-ms-error-code").orElse("");
            log.warn("azure user delegation key rejected for account {} (HTTP {} {}): {}",
                    request.account(), response.statusCode(), code, abbreviate(response.body()));
            throw new ApiException(503, null, null,
                    "Azure refused to issue a user delegation key for account "
                            + request.account() + ": HTTP " + response.statusCode()
                            + (code.isEmpty() ? "" : " " + code)
                            + "; the service identity needs"
                            + " Microsoft.Storage/storageAccounts/blobServices/"
                            + "generateUserDelegationKey/action on that account");
        }
        return parseKey(response.body(), request);
    }

    // ------------------------------------------------------------------ AAD 令牌

    /**
     * 换一个 AAD 访问令牌（带缓存）。
     *
     * <p>scope 由配置给出而不是按云环境推导：公有云是 {@code https://storage.azure.com/.default}、
     * 中国云是 {@code https://storage.azure.cn/.default}，主权云的域名各不相同。
     * 从 {@code blobHost()} 去猜一个 scope 会在猜错时得到一个「能登录、调接口 401」的令牌，
     * 而那正是最难归因的一种失败。
     */
    private String accessToken(AzureDelegationRequest request) {
        String key = request.tenantId() + "|" + request.clientId() + "|" + request.scope()
                + "|" + request.authority();
        CachedToken cached = tokens.get(key);
        if (cached != null && Instant.now().isBefore(cached.expiresAt().minus(TOKEN_MARGIN))) {
            return cached.token();
        }
        String form = "grant_type=client_credentials"
                + "&client_id=" + encode(request.clientId())
                + "&client_secret=" + encode(request.clientSecret())
                + "&scope=" + encode(request.scope());
        HttpRequest http = HttpRequest.newBuilder(URI.create(
                        request.authority() + "/" + request.tenantId() + "/oauth2/v2.0/token"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = send(http, "AAD token");
        Map<?, ?> body = parseJson(response.body());
        Object token = body.get("access_token");
        if (response.statusCode() == 200 && token != null) {
            long expiresIn = body.get("expires_in") instanceof Number number ? number.longValue() : 3600L;
            CachedToken fresh = new CachedToken(String.valueOf(token),
                    Instant.now().plusSeconds(expiresIn));
            tokens.put(key, fresh);
            return fresh.token();
        }
        Object error = body.get("error");
        throw new ApiException(503, null, null,
                "could not get an AAD token for tenant " + request.tenantId() + ": "
                        + (error == null ? "HTTP " + response.statusCode() : error)
                        // 点名到 catalog 的字段：那对服务主体跟着某个 catalog 走，
                        // 不是部署级配置，报错里指错地方会让人去翻一份不存在的配置
                        + "; check this catalog's storageConfigInfo."
                        + "{tenantId,clientId,clientSecret,authority,scope}");
    }

    // ------------------------------------------------------------------ 解析

    /**
     * 读 {@code <UserDelegationKey>} 的六个字段。
     *
     * <p>任何一个缺失都直接失败：少一个字段的密钥拿去签名只会得到
     * 「签名不对」，而这里的报错能指出是响应里少了什么。形参 {@code request} 只用于
     * 报错信息里的账户名——排障时「哪个账户没签出来」是第一个要回答的问题。
     */
    private static UserDelegationKey parseKey(String xml, AzureDelegationRequest request) {
        if (xml == null || xml.isBlank()) {
            throw new ApiException(503, null, null,
                    "Azure returned an empty user delegation key for account " + request.account());
        }
        Element root;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // 响应来自云，但「不解析外部实体」是解析任何 XML 的默认姿势：
            // 关掉 DTD 与外部实体，XXE 就不再是一个需要单独评估的风险
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            root = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
                    .getDocumentElement();
        } catch (Exception malformed) {
            throw new ApiException(503, null, null,
                    "could not parse the user delegation key response: "
                            + malformed.getClass().getSimpleName());
        }
        return new UserDelegationKey(
                child(root, "Signed"),
                child(root, "SignedTid"),
                child(root, "SignedStart"),
                child(root, "SignedExpiry"),
                child(root, "SignedService"),
                child(root, "SignedVersion"),
                child(root, "Value"));
    }

    /**
     * 取一个子元素的文本。
     *
     * <p><b>元素名是 {@code Signed} / {@code SignedTid} 这一类，不是 {@code SignedOid}。</b>
     * 响应里的这几个名字与 SAS 查询串（{@code skoid} / {@code sktid}）并不一一对应，
     * 照查询串的名字去找会全部落空——所以下面用「找不到就报错」的写法，
     * 而不是给一个空串让签名在别处失败。
     */
    private static String child(Element root, String name) {
        NodeList nodes = root.getElementsByTagName(name);
        String value = nodes.getLength() == 0 ? null : nodes.item(0).getTextContent();
        if (value == null || value.isBlank()) {
            throw new ApiException(503, null, null,
                    "Azure returned a user delegation key without <" + name + ">");
        }
        return value;
    }

    // ------------------------------------------------------------------ 工具

    private static HttpResponse<String> send(HttpRequest request, String what) {
        try {
            return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
                    .send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, null, null, what + " was interrupted");
        } catch (Exception failure) {
            throw new ApiException(503, null, null,
                    what + " failed: " + failure.getClass().getSimpleName()
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

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        String single = value.replace('\n', ' ');
        return single.length() > 200 ? single.substring(0, 200) + "…" : single;
    }

    /** 一次换到的 AAD 令牌。 */
    private record CachedToken(String token, Instant expiresAt) {
    }
}
