package io.github.melin.lakehouse.rest.engine.auth;

import java.util.HashMap;
import java.util.Map;
import org.apache.paimon.rest.auth.AuthProvider;
import org.apache.paimon.rest.auth.RESTAuthParameter;

/**
 * Paimon 的认证 provider（{@code token.provider=oauth2}）：每个请求都问一次「当前该用哪张票」。
 *
 * <p><b>它只是把 Paimon 已经提供、但内置 {@code bear} 没用上的钩子用起来。</b>
 * {@link AuthProvider#mergeAuthHeader} 每个 HTTP 请求都会被调用一次（参数里带着方法、
 * 路径、body），而内置 {@code BearTokenAuthProvider} 的 {@code token} 是构造期字段，
 * 此后永不变化——于是 TTL 一到，下一次元数据调用（{@code getTable}、{@code commit}、
 * {@code listPartitions}）就是 401。框架支持动态认证，provider 没利用，仅此而已。
 *
 * <p><b>为什么不是每请求换票。</b>换票的时机由 {@link RefreshingToken} 决定（进窗口才换、
 * 并发只换一次）。本类每次只做一次取票调用，绝大多数时候命中缓存直接返回。
 *
 * <p><b>无票时抛 {@link IllegalStateException} 而不是发空头。</b>走到这一步只有两种原因：
 * 配置里既没有 {@code token} 也没有 {@code oauth2.credential}（启动时就该失败，
 * 见 {@code oauth2} 工厂的校验），或者冷启动取票失败且退避未到。发一个
 * {@code Authorization: Bearer } 出去会让服务端把空串当主体名，错误现场离原因更远。
 */
public final class PaimonOAuth2AuthProvider implements AuthProvider {

    public static final String AUTHORIZATION_HEADER_KEY = "Authorization";

    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenSource tokens;

    /** 缓存键，用于错误消息。不含密钥。 */
    private final String label;

    public PaimonOAuth2AuthProvider(TokenSource tokens, String label) {
        this.tokens = tokens;
        this.label = label;
    }

    @Override
    public Map<String, String> mergeAuthHeader(Map<String, String> baseHeader,
                                               RESTAuthParameter restAuthParameter) {
        String token = tokens.token();
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("no access token is available for " + label
                    + ": configure oauth2.credential (clientId:clientSecret) so it can be fetched,"
                    + " or provide a token that has not expired yet");
        }
        Map<String, String> headers = baseHeader == null
                ? new HashMap<>()
                : new HashMap<>(baseHeader);
        headers.put(AUTHORIZATION_HEADER_KEY, BEARER_PREFIX + token);
        return headers;
    }

    @Override
    public String toString() {
        return "PaimonOAuth2AuthProvider(" + label + ")";
    }
}
