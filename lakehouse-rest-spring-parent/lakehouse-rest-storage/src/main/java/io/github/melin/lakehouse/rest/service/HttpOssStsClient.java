package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link OssStsClient} 的真实实现：一次 GET + 一个 JSON。
 *
 * <p><b>为什么不用阿里云 SDK。</b>与 Azure 那个客户端的判断一致：整个动作是
 * 「拼参数 → 签一次名 → 读四个字段」，签名部分已经抽到 {@link AliyunRpcSignature}
 * 里可以脱网验证，SDK 能多给的只有重试与区域发现，而这两件在这里都不需要
 * （STS 是全局端点，重试由上层缓存与调用方决定）。
 *
 * <p><b>参数表必须一次性给全，不能分两次拼。</b>签名是对**整张**参数表算的，
 * 少一个参数时会得到一个能算出来、但服务端判定不匹配的签名——报错是
 * {@code SignatureDoesNotMatch}，而这个错误对「漏参数」与「密钥错了」完全一样。
 * 所以下面把公共参数与业务参数放进同一个 {@link LinkedHashMap}，
 * 再交给 {@link AliyunRpcSignature#canonicalQuery}。
 *
 * <p><b>{@code Signature} 不进待签串，但进 URL。</b>它由其余参数算出来，
 * 算完再拼到查询串前面。把它放进参数表会得到一个「自己签自己」的死循环。
 */
@Component
public class HttpOssStsClient implements OssStsClient {

    private static final Logger log = LoggerFactory.getLogger(HttpOssStsClient.class);

    /** 阿里云的时间戳格式：秒精度、UTC、以 {@code Z} 结尾。 */
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    /** STS 的 API 版本。写错版本时返回的是 {@code InvalidVersion}，比报错更像配置问题。 */
    private static final String API_VERSION = "2015-04-01";

    @Override
    public OssStsSession assumeRole(OssStsRequest request) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("Action", "AssumeRole");
        parameters.put("Format", "JSON");
        parameters.put("Version", API_VERSION);
        parameters.put("AccessKeyId", request.accessKeyId());
        parameters.put("SignatureMethod", "HMAC-SHA1");
        parameters.put("SignatureVersion", "1.0");
        // nonce 防重放：阿里云要求每次请求不同，重复的 nonce 会被拒（InvalidNonce）
        parameters.put("SignatureNonce", UUID.randomUUID().toString());
        parameters.put("Timestamp", TIMESTAMP.format(Instant.now()));
        parameters.put("RoleArn", request.roleArn());
        parameters.put("RoleSessionName", request.sessionName());
        parameters.put("DurationSeconds", Long.toString(request.durationSeconds()));
        if (request.policy() != null && !request.policy().isBlank()) {
            // policy 里全是引号与花括号，编码由 canonicalQuery 统一做；
            // 在这里先编码会让它被编两次（%7B 变成 %257B），而签名仍然算得出来
            parameters.put("Policy", request.policy());
        }
        String canonical = AliyunRpcSignature.canonicalQuery(parameters);
        String signature = AliyunRpcSignature.sign(request.secretAccessKey(), canonical);
        String url = endpoint(request.endpoint()) + "/?Signature="
                + AliyunRpcSignature.percentEncode(signature) + "&" + canonical;

        HttpRequest http = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        HttpResponse<String> response = send(http);
        Map<?, ?> body = parseJson(response.body());
        if (response.statusCode() / 100 != 2) {
            Object code = body.get("Code");
            Object message = body.get("Message");
            log.warn("aliyun AssumeRole rejected for role {} (HTTP {}): {} {}", request.roleArn(),
                    response.statusCode(), code, message);
            throw new ApiException(503, null, null,
                    "Aliyun refused to assume " + request.roleArn() + ": HTTP "
                            + response.statusCode()
                            + (code == null ? "" : " " + code)
                            + (message == null ? "" : " (" + message + ")")
                            + "; the calling identity needs sts:AssumeRole on that role");
        }
        return parseSession(body, request);
    }

    /**
     * 读 {@code Credentials} 下的四项。
     *
     * <p>三个值缺任何一个都直接失败：少一个的后果不是「少发一个键」，而是引擎拿着
     * 半套凭据去签名，报回来的错误与「密钥过期」长得一样。到期时刻是例外——
     * 解析不出来时给 {@code null}，由调用方按请求的 TTL 兜底（那仍然是一个诚实的上限）。
     */
    private static OssStsSession parseSession(Map<?, ?> body, OssStsRequest request) {
        Object credentials = body.get("Credentials");
        if (!(credentials instanceof Map<?, ?> map)) {
            throw new ApiException(503, null, null,
                    "Aliyun returned no Credentials for role " + request.roleArn());
        }
        String accessKeyId = text(map, "AccessKeyId", request);
        String secretAccessKey = text(map, "AccessKeySecret", request);
        String securityToken = text(map, "SecurityToken", request);
        Instant expiration = null;
        Object raw = map.get("Expiration");
        if (raw != null && !String.valueOf(raw).isBlank()) {
            try {
                expiration = Instant.parse(String.valueOf(raw));
            } catch (RuntimeException malformed) {
                // 时区偏移写法（+08:00）也能被 Instant.parse 接受，走到这里说明格式真的不认识；
                // 不猜，只留一条日志并把到期时刻留给调用方按 TTL 兜底
                log.warn("cannot parse the Aliyun credential expiration '{}' for role {}",
                        raw, request.roleArn());
            }
        }
        return new OssStsSession(accessKeyId, secretAccessKey, securityToken, expiration);
    }

    private static String text(Map<?, ?> map, String name, OssStsRequest request) {
        Object value = map.get(name);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new ApiException(503, null, null,
                    "Aliyun returned a temporary credential without " + name + " for role "
                            + request.roleArn());
        }
        return String.valueOf(value);
    }

    /** 端点补默认值并去掉结尾的斜杠：写 {@code https://sts.aliyuncs.com/} 时别拼出两个斜杠。 */
    private static String endpoint(String configured) {
        String value = configured == null || configured.isBlank()
                ? "https://sts.aliyuncs.com" : configured.trim();
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
            throw new ApiException(503, null, null, "the Aliyun STS request was interrupted");
        } catch (Exception failure) {
            throw new ApiException(503, null, null,
                    "the Aliyun STS request failed: " + failure.getClass().getSimpleName()
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
