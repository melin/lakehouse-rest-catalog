package io.github.melin.lakehouse.rest.engine.auth;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.paimon.options.Options;
import org.apache.paimon.rest.RESTCatalogOptions;
import org.apache.paimon.rest.auth.AuthProvider;
import org.apache.paimon.rest.auth.AuthProviderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code token.provider=oauth2} 的工厂。
 *
 * <p><b>标识取 {@code oauth2} 而不是 {@code exchange} / {@code refresh}</b>，是为了与
 * Iceberg 的 {@code rest.auth.type=oauth2} 同名：读文档的人只需要记住一个词。
 *
 * <p><b>配置</b>（Spark 与 Flink 同形，只是选项前缀不同）：
 *
 * <pre>
 * token.provider=oauth2
 * token=&lt;主体令牌&gt;                              # 起始票
 * oauth2.credential=&lt;clientId&gt;:&lt;clientSecret&gt;  # 可选：没票或服务端不认 exchange 时的取票凭据
 * oauth2.token-endpoint=&lt;uri&gt;/v1/oauth/tokens   # 可选：默认由 uri 推导
 * oauth2.refresh-window=300s                    # 可选：默认 min(ttl × 15%, 5min)
 * oauth2.scope=PRINCIPAL_ROLE:ALL               # 可选：不发则由服务端归一
 * </pre>
 *
 * <p><b>没有 {@code oauth2.enabled} 这样的开关。</b>要用续期就把 provider 改成
 * {@code oauth2}，要沿用老行为就留 {@code bear}——两档之间不需要第三个状态，
 * 而第三个状态（「配了 provider 但关掉续期」）产出的行为与 {@code bear} 完全一样，
 * 只是多了一个会配错的地方。
 *
 * <p><b>起始票已过期但配了凭据是可用组合</b>：第一次请求会走 {@code client_credentials}
 * 取新票。这条冷启动路径让「作业排队一小时后才启动」不至于直接失败——设计文档 §9
 * 第 3 条悬着的那个问题，答案就是「能取就取」。
 */
public class PaimonOAuth2AuthProviderFactory implements AuthProviderFactory {

    private static final Logger LOG = LoggerFactory.getLogger(PaimonOAuth2AuthProviderFactory.class);

    /** {@code token.provider} 的取值。 */
    public static final String IDENTIFIER = "oauth2";

    public static final String OPTION_CREDENTIAL = "oauth2.credential";

    public static final String OPTION_TOKEN_ENDPOINT = "oauth2.token-endpoint";

    public static final String OPTION_REFRESH_WINDOW = "oauth2.refresh-window";

    public static final String OPTION_SCOPE = "oauth2.scope";

    /**
     * 已经就「这张票不会自动续期」告警过的键。
     *
     * <p>工厂会被反复调用（每次刷新数据票都会重建 RESTApi，见
     * {@link RefreshingTokens}），不去重的话这条 WARN 会在一个作业里刷出成百上千行，
     * 真出事时反而把它淹掉。
     */
    private static final Set<String> WARNED_NOT_REFRESHABLE = ConcurrentHashMap.newKeySet();

    @Override
    public String identifier() {
        return IDENTIFIER;
    }

    @Override
    public AuthProvider create(Options options) {
        Map<String, String> all = options.toMap();
        String endpoint = endpointOf(all.get(RESTCatalogOptions.URI.key()),
                all.get(OPTION_TOKEN_ENDPOINT)).toString();
        String token = EngineTokenSources.trimToNull(all.get(RESTCatalogOptions.TOKEN.key()));
        String credential = all.get(OPTION_CREDENTIAL);
        String[] pair = EngineTokenSources.splitCredential(credential);
        if (token == null && pair == null) {
            throw new IllegalArgumentException("token.provider=" + IDENTIFIER + " needs either"
                    + " token (the current access token) or " + OPTION_CREDENTIAL
                    + " (clientId:clientSecret, used to fetch one); neither is set");
        }

        TokenSource source = EngineTokenSources.create(endpoint, token, credential,
                all.get(OPTION_SCOPE), EngineTokenSources.parseWindow(all.get(OPTION_REFRESH_WINDOW)));
        String key = RefreshingTokens.keyOf(endpoint, pair == null ? null : pair[0], token);

        if (source instanceof RefreshingToken refreshing && !refreshing.refreshable()
                && pair == null && WARNED_NOT_REFRESHABLE.add(key)) {
            // 决策记录：这里**不抛异常**。「起始票过期」与「票是静态令牌（没有 exp）」
            // 在代码里长得一样，而后者是合法配置；抛出去会让「作业排队一小时后再启动」
            // 起不来，而它其实只差一次取票。服务端给的 401 比这里的猜测更准确。
            //
            // 另一层原因：provider 在 RESTTokenFileIO.refreshToken() 里也会被创建，
            // 在那里抛会连带把数据票的刷新路径打断——故障面比它要解决的问题更大。
            LOG.warn("{}: this access token carries no expiry information, so it will NOT be"
                    + " refreshed automatically. Long-running jobs will fail with 401 once it"
                    + " expires; set {} so a new token can be fetched.", key, OPTION_CREDENTIAL);
        }
        return new PaimonOAuth2AuthProvider(source, key);
    }

    /**
     * 由 catalog 的 {@code uri} 推导令牌端点。
     *
     * <p>{@code uri} 在不同部署里可能是 {@code http://host:8080}，也可能是
     * {@code http://host:8080/v1}（「已经带上 API 版本」的写法，Paimon 自己也接受）。
     * 直接拼 {@code /v1/oauth/tokens} 会在第二种情况下得到 {@code /v1/v1/oauth/tokens}
     * ——一个 404，而它看起来像「服务端没开令牌端点」。
     */
    static URI endpointOf(String catalogUri, String configured) {
        String value = EngineTokenSources.trimToNull(configured);
        if (value != null) {
            return URI.create(value);
        }
        String base = EngineTokenSources.trimToNull(catalogUri);
        if (base == null) {
            throw new IllegalArgumentException("cannot derive the token endpoint: neither"
                    + " uri nor " + OPTION_TOKEN_ENDPOINT + " is set");
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base.endsWith("/v1")
                ? base + "/oauth/tokens"
                : base + "/v1/oauth/tokens");
    }
}
