package io.github.melin.lakehouse.rest.engine.auth;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 给引擎侧的两条链路（Paimon {@code AuthProvider}、Spark 治理通道）装配 {@link TokenSource}。
 *
 * <p>两个消费面的配置项名字不同（{@code oauth2.*} 与
 * {@code spark.lakehouse.rest.management.*}），但「把这段配置变成一只能续期的票」这件事
 * 只有一份实现：拆凭据、解析窗口、算缓存键、复用进程级状态。两处各写一遍的代价不是重复
 * 二十行代码，而是**漂移**——一边把裸数字当秒、另一边当毫秒，两边的续期行为就不一样了，
 * 而它们看起来都「配好了」。
 */
public final class EngineTokenSources {

    private static final Logger LOG = LoggerFactory.getLogger(EngineTokenSources.class);

    private EngineTokenSources() {
    }

    /**
     * 装配一个会续期的票来源。
     *
     * <p>三种组合：
     *
     * <ul>
     *   <li>有票、有端点 → 到期前用 {@code token-exchange} 换新（首选：作业配置里只有短票）；
     *   <li>有票、<b>没有</b>端点 → 退化成分固定票并打 WARN。没有端点就无从换票，
     *       而静默退化成「不续期」会让长作业跑到一小时后 401，且日志里没有任何线索；
     *   <li>没有票、有凭据 → 冷启动时用 {@code client_credentials} 取一张
     *       （兜底路线，代价是长期密钥进了作业配置，见设计文档 §7）。
     * </ul>
     *
     * @param endpoint   令牌端点；空白表示只用手里的票
     * @param token      当前票；可为空
     * @param credential {@code clientId:clientSecret}；可为空
     * @param scope      可选，空白则不发送
     * @param window     提前多久换；{@code null} 表示按 TTL 推算
     */
    public static TokenSource create(String endpoint, String token, String credential, String scope,
                                     Duration window) {
        String ticket = trimToNull(token);
        String[] pair = splitCredential(credential);
        URI uri = endpointUri(endpoint);
        if (uri == null) {
            if (ticket == null && pair == null) {
                return TokenSource.fixed(null);
            }
            LOG.warn("no token endpoint is configured, so the access token will NOT be refreshed;"
                    + " long-running sessions will fail with 401 once it expires");
            return TokenSource.fixed(ticket);
        }
        if (ticket == null && pair == null) {
            // 没有票也没有凭据：保持「不发 Authorization 头」的老行为
            return TokenSource.fixed(null);
        }

        TokenClient client = new HttpTokenClient(uri, pair == null ? null : pair[0],
                pair == null ? null : pair[1], trimToNull(scope), null);
        String key = RefreshingTokens.keyOf(uri.toString(), pair == null ? null : pair[0], ticket);
        return RefreshingTokens.obtain(key,
                () -> new RefreshingToken(key, ticket, client, window, Clock.systemUTC()));
    }

    /** 令牌端点；空白或不是合法 URI 时返回 {@code null}（后者打 WARN，不抛）。 */
    private static URI endpointUri(String endpoint) {
        String value = trimToNull(endpoint);
        if (value == null) {
            return null;
        }
        try {
            return URI.create(value);
        } catch (IllegalArgumentException e) {
            LOG.warn("the configured token endpoint is not a valid URI ({}), so the access token will"
                    + " NOT be refreshed", value);
            return null;
        }
    }

    /**
     * 拆 {@code clientId:clientSecret}。
     *
     * <p>按<b>第一个</b>冒号拆：密钥里允许出现冒号（base64 常带），clientId 里不允许
     * （本工程的主体名是标识符）。反过来拆会把密钥截断成后半段，表现是
     * {@code invalid_client}——看起来像「密钥不对」。
     *
     * @return {@code [clientId, clientSecret]}；未配置时返回 {@code null}
     */
    public static String[] splitCredential(String credential) {
        String value = trimToNull(credential);
        if (value == null) {
            return null;
        }
        int separator = value.indexOf(':');
        if (separator <= 0) {
            throw new IllegalArgumentException(
                    "credential must be <clientId>:<clientSecret>, got a value without a clientId part");
        }
        return new String[]{value.substring(0, separator), value.substring(separator + 1)};
    }

    /**
     * 解析刷新窗口：{@code 300s} / {@code 5min} / {@code 500ms} / 裸数字（按秒）。
     *
     * <p>裸数字按<b>秒</b>而不是毫秒：配置项名字是「提前多久换票」，写 {@code 300} 的人
     * 想说的是 5 分钟。按毫秒解释会让窗口变成 0.3 秒，表现是「每个请求都换票」
     * ——一个不会报错、只会把换票端点打满的误配置。
     *
     * @return {@code null} 表示未配置，由 {@link RefreshingToken} 按 TTL 推算
     */
    public static Duration parseWindow(String value) {
        String text = trimToNull(value);
        if (text == null) {
            return null;
        }
        String normalized = text.toLowerCase(Locale.ROOT).replace(" ", "");
        long multiplier = 1_000L;
        String number = normalized;
        for (String[] unit : new String[][]{{"ms", "1"}, {"min", "60000"}, {"s", "1000"},
                {"m", "60000"}, {"h", "3600000"}, {"d", "86400000"}}) {
            if (normalized.endsWith(unit[0]) && normalized.length() > unit[0].length()) {
                multiplier = Long.parseLong(unit[1]);
                number = normalized.substring(0, normalized.length() - unit[0].length());
                break;
            }
        }
        try {
            long amount = Long.parseLong(number);
            if (amount < 0) {
                throw new IllegalArgumentException("refresh window must not be negative: " + value);
            }
            return Duration.ofMillis(amount * multiplier);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("refresh window is not a duration: " + value
                    + " (expected forms like 300s, 5min, 500ms, or a bare number of seconds)", e);
        }
    }

    static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
