package io.github.melin.lakehouse.rest.engine.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;

/**
 * 只读地取 JWT 的载荷字段，**不验签**。
 *
 * <p>用途只有一个：手里这张票什么时候过期（{@code exp}）。服务端在响应里给了
 * {@code expires_in} 就用那个，没给才来读这里——本服务端两个都会给，但
 * 「换票端点换成别家」或「有人手工把一张 JWT 填进 {@code token}」时只剩这条路。
 *
 * <p><b>不验签是有意的，不是遗漏。</b>这些值只用于「安排什么时候刷新」与
 * 「选缓存键」，用一个不可信的值最多导致「刷新得太晚」（下次请求 401，服务端会拒）
 * 或「刷新得太勤」（多打几次换票）。真正的信任判定在服务端：它验签、判 {@code exp}、
 * 按 {@code sub} 查授权。在这里验签需要签名密钥，而那正是本模块**刻意不持有**的东西
 * ——引擎侧只该有短期票。
 *
 * <p>任何解析失败都返回 {@code null} 而不是抛：静态令牌（配置里的一个字符串）
 * 同样可以进来，它不是 JWT，此时「读不出过期时间」是正常结论，不该变成异常。
 */
final class JwtClaims {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JwtClaims() {
    }

    /** 取字符串 claim（{@code sub} / {@code jti}）；没有或不是 JWT 时返回 {@code null}。 */
    static String claim(String token, String name) {
        JsonNode payload = payload(token);
        if (payload == null) {
            return null;
        }
        JsonNode value = payload.get(name);
        return value == null || value.isNull() ? null : value.asText();
    }

    /** 取数值 claim（{@code exp} / {@code iat}）；没有或不是 JWT 时返回 {@code null}。 */
    static Long numericClaim(String token, String name) {
        JsonNode payload = payload(token);
        if (payload == null) {
            return null;
        }
        JsonNode value = payload.get(name);
        return value != null && value.isNumber() ? value.asLong() : null;
    }

    /**
     * 载荷 JSON。
     *
     * <p>JWT 的格式是 {@code header.payload.signature}，三段的 base64url（无填充）。
     * 段数不是 3、或中间段解不出来，都当作「不是 JWT」。
     */
    private static JsonNode payload(String token) {
        if (token == null) {
            return null;
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode node = MAPPER.readTree(decoded);
            return node != null && node.isObject() ? node : null;
        } catch (IllegalArgumentException | java.io.IOException e) {
            return null;
        }
    }
}
