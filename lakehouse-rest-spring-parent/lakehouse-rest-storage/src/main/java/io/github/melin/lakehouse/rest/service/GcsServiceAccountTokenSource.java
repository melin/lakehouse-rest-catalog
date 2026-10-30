package io.github.melin.lakehouse.rest.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 把「服务账号 JSON」换成「可用的访问令牌」，并按服务账号缓存。
 *
 * <p><b>缓存是必需的，不是优化。</b>一次 {@code loadTable} 可能触发多次取票
 * （Paimon 的 {@code /token}、Iceberg 的 {@code /credentials}、Lance 的
 * {@code storage_options} 各一次），每次换令牌都是一次 HTTPS 往返 + 一次 RSA 签名。
 * 不缓存时，一个建表脚本能打出几百次令牌请求，而 Google 侧对同一服务账号的
 * 换票频率是有限流的。
 *
 * <p><b>缓存键是 {@code client_email}，不是整份 JSON。</b>同一个服务账号可能被多个
 * catalog 引用，而它们的 JSON 只有 {@code private_key_id} 之后的内容不同；
 * 按 JSON 原文做键会让每个 catalog 各缓存一份，等于没缓存。
 *
 * <p><b>提前 2 分钟换新。</b>缓存到「到期前 2 分钟」而不是「到期」：一张还剩 30 秒的
 * 令牌发给引擎，引擎拿它去读表时刚好过期，错误是一个看不出原因 401。
 */
@Component
@RequiredArgsConstructor
public class GcsServiceAccountTokenSource {

    /** 提前量：剩下不到这么多就换新的。 */
    static final Duration REFRESH_MARGIN = Duration.ofMinutes(2);

    private final GcsAccessTokenClient client;

    private final Map<String, GcsAccessToken> cache = new ConcurrentHashMap<>();

    /**
     * 取一份可用的访问令牌。
     *
     * <p>换票失败**向上抛**（{@code 503}），不返回空：catalog 上贴着服务账号密钥，
     * 说明它就是要服务端签票；此时静默退回「引擎用自己的 ADC」，在 GKE 之外
     * 根本没有 ADC，最终表现是引擎 403 而服务端日志一片正常。
     */
    public String accessToken(String serviceAccountJson) {
        GcsServiceAccount account = GcsServiceAccount.parse(serviceAccountJson);
        GcsAccessToken cached = cache.get(account.clientEmail());
        if (cached != null && usable(cached)) {
            return cached.token();
        }
        GcsAccessToken minted = client.mint(account);
        if (minted.expiresAt() != null) {
            cache.put(account.clientEmail(), minted);
        }
        return minted.token();
    }

    private static boolean usable(GcsAccessToken token) {
        return token.expiresAt() != null
                && token.expiresAt().isAfter(Instant.now().plus(REFRESH_MARGIN));
    }
}
