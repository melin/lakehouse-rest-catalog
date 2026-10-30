package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.Json;
import java.util.Map;

/**
 * 服务账号密钥文件里，换令牌真正用到的那三项。
 *
 * <p>只挑三项而不是把整份 JSON 传下去：这份文件里其余字段（{@code type}、
 * {@code project_id}、{@code client_x509_cert_url} 等）对
 * 「签一份 JWT 换 access token」没有作用，带着它们只会在日志与异常里多出一些
 * 看起来敏感的键。
 *
 * <p>{@code tokenUri} 允许被文件改写：私有化 / 代理环境会把
 * {@code oauth2.googleapis.com} 换成内网地址，而那个值是 Google 自己写进文件里的。
 * 缺省时用公有云的令牌端点。
 *
 * <p><b>解析失败在这里就抛 500，而不是让换票去失败。</b>一份贴错的 JSON
 * （多了一层引号、被编辑器转义过、少了 private_key）在交换阶段的表现是
 * {@code invalid_grant}——那句话里没有任何线索指向「你贴错了文件」。
 */
public record GcsServiceAccount(String clientEmail, String privateKeyPem, String tokenUri) {

    /** 公有云的令牌端点；文件里没写时用它。 */
    static final String DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token";

    public static GcsServiceAccount parse(String json) {
        Object parsed;
        try {
            parsed = Json.read(json);
        } catch (RuntimeException malformed) {
            throw new ApiException(500, null, null,
                    "the catalog's serviceAccountJson is not valid JSON: " + malformed.getMessage());
        }
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new ApiException(500, null, null,
                    "the catalog's serviceAccountJson must be a JSON object (the service account"
                            + " key file)");
        }
        String email = text(map.get("client_email"));
        String key = text(map.get("private_key"));
        if (blank(email) || blank(key)) {
            throw new ApiException(500, null, null,
                    "the catalog's serviceAccountJson has no client_email or private_key:"
                            + " no token can be minted from it");
        }
        String tokenUri = text(map.get("token_uri"));
        return new GcsServiceAccount(email, key, blank(tokenUri) ? DEFAULT_TOKEN_URI : tokenUri);
    }

    private static String text(Object value) {
        return value instanceof String string ? string : null;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
