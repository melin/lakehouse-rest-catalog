package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.Json;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用服务账号私钥签一份 JWT assertion，向 Google 换 access token。
 *
 * <p>这是整个 GCS 签发链里唯一需要密码学的部分，因此单独成类、不碰网络：
 * 单测可以直接验签（用私钥对应的公钥），而不必真的去换票。签名对不对、
 * claim 齐不齐，在这里就能分成两件事来看。
 *
 * <p><b>三个 claim 不能省，各有一个失败现象：</b>
 *
 * <ul>
 *   <li>{@code iss} 是服务账号邮箱（{@code client_email}），不是人；写成人名时
 *       回 {@code invalid_grant}。</li>
 *   <li>{@code scope} 必须是 {@code cloud-platform}。少写时换出来的令牌**能用**，
 *       但访问存储时权限不足——错误现场在 GCS 的 403 上，与「scope 写窄了」隔着一步。</li>
 *   <li>{@code aud} 是令牌端点本身，不是服务名。写错时 {@code invalid_grant}。</li>
 * </ul>
 *
 * <p>{@code exp} 取 1 小时，这是 Google 对 assertion 的上限；实际下发时长由
 * downscope 那一步再收窄（最长也是 1 小时）。
 */
public final class GcsJwtAssertion {

    /** 换令牌用的 scope：要访问 Cloud Storage 就得是这一个。 */
    static final String SCOPE = "https://www.googleapis.com/auth/cloud-platform";

    /** assertion 自身的寿命上限，Google 不接受更长的。 */
    static final long LIFETIME_SECONDS = 3600L;

    private GcsJwtAssertion() {
    }

    /** 签一份 assertion；{@code issuedAt} 由调用方给，便于单测固定时间。 */
    public static String build(GcsServiceAccount account, Instant issuedAt) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "RS256");
        header.put("typ", "JWT");

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", account.clientEmail());
        claims.put("scope", SCOPE);
        claims.put("aud", account.tokenUri());
        claims.put("iat", issuedAt.getEpochSecond());
        claims.put("exp", issuedAt.plusSeconds(LIFETIME_SECONDS).getEpochSecond());

        String signingInput = base64Url(Json.write(header)) + "." + base64Url(Json.write(claims));
        byte[] signature = sign(signingInput, privateKey(account.privateKeyPem()));
        return signingInput + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
    }

    /**
     * 解析 PKCS#8 的 PEM。
     *
     * <p>Google 发的 {@code private_key} 就是这种格式，换行以 {@code \n} 的形式
     * 存在于 JSON 字符串里。这里对首尾空白与换行都不做假设——手工粘贴时常带上尾部空格，
     * 而 Base64 解码器对空白是宽容的，所以只需要去掉那两行分隔符。
     */
    static PrivateKey privateKey(String pem) {
        String body = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        try {
            byte[] encoded = Base64.getDecoder().decode(body);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded));
        } catch (IllegalArgumentException | GeneralSecurityException broken) {
            throw new ApiException(500, null, null,
                    "the catalog's serviceAccountJson has an unusable private_key: "
                            + broken.getClass().getSimpleName()
                            + "; it must be the PKCS#8 PEM from the service account key file");
        }
    }

    private static byte[] sign(String signingInput, PrivateKey key) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(key);
            signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return signature.sign();
        } catch (GeneralSecurityException failure) {
            throw new ApiException(500, null, null,
                    "could not sign the GCS token assertion: " + failure.getMessage());
        }
    }

    private static String base64Url(String text) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
