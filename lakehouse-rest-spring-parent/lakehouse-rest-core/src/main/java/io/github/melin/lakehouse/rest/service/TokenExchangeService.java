package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.AuthenticatedToken;
import io.github.melin.lakehouse.rest.support.Jwt;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * OAuth 2.0 令牌交换（RFC 8693）：拿一张仍然有效的访问令牌换一张新的。
 *
 * <p><b>它为什么必须存在。</b>本服务端签的令牌是自包含 JWT，有效期默认 1 小时，
 * 且服务端不存令牌、没有撤销机制——因此「续期」只能由客户端发起（见
 * {@code docs/engine-token-refresh-design.md} §1.3）。Iceberg 的 REST 客户端
 * 已经写好了这条刷新链：解析 JWT 的 {@code exp}、到期前调度刷新，刷新时**默认**发
 * 的正是这个 grant。所以服务端认这个 grant，等于让 Iceberg 侧零改动就能续期；
 * 不认的话，它每次刷新都吃 400 且失败后不再重排，作业跑到一小时后必然 401。
 *
 * <p><b>交换不放大任何权限。</b>四条确保这一点：
 *
 * <ul>
 *   <li>新票的 {@code sub} 取自<b>认证结果</b>，不取自请求里的任何字段——请求里
 *       根本没有「换成谁」这个参数；
 *   <li>新票的 TTL 就是 {@code access-token.ttl}，不是「在原票基础上延长」；
 *   <li>{@code scope} 与 {@link ClientCredentialsService} 共用同一套归一化，
 *       只认 {@code PRINCIPAL_ROLE:ALL}，其余一律拒绝；
 *   <li>{@code subject_token} 必须<b>仍然有效</b>（{@link TokenAuthenticationService}
 *       会判 {@code exp}）。这是自带的寿命上界：作业挂起超过一个 TTL 就续不上，
 *       必须重新拿票——「一张泄露的票可以被无限续期」因此不成立。
 * </ul>
 *
 * <p><b>没有开关。</b>不提供 {@code exchange.enabled} 这样的配置项：这个端点唯一能
 * 产出的东西，是与调用方手里的票<b>等权</b>的另一张票，关掉它不增加任何安全性，
 * 却会让长作业在跑了一小时后失败——正是本功能要消除的那种故障。需要「作业不能
 * 永久持有访问权」的场合属于另一个问题（总时长上限），由
 * {@code lakehouse.rest.auth.access-token.max-session} 回答，不靠关掉续期来解决。
 *
 * <p><b>总时长上限（默认关）。</b>打开后每张票都带一个 {@code ses} claim（这条链的起点），
 * 换票时沿用父票的 {@code ses}（没有就取父票的 {@code iat}），整个链共享同一个起点。
 * 链龄超过上限就拒绝交换，但<b>已经签发的票仍然可用到期</b>——否则正在跑的作业会在
 * 没有任何预兆的情况下 401，而那时它既没有新票也没有告警，只能重启。
 *
 * <p><b>不计数、不审计「换了几次」本身</b>：审计日志记的是「谁、从哪张票换来的」
 * （父票的 {@code jti}）。没有撤销机制时，这是唯一能追到链条起点的线索。
 *
 * @see ClientCredentialsService 客户端凭据流程——两种 grant 产出的票形状相同
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenExchangeService {

    /**
     * RFC 8693 §2.1 的 grant_type 取值。
     *
     * <p>刻意拼全而不是引常量：这个字符串是**跨实现的对端契约**（Iceberg 按同样的
     * 字面量发请求），写错一个字符的后果是「刷新永远 400，且报的是
     * unsupported_grant_type」——而那条错误看起来像是服务端不支持这个功能。
     */
    public static final String GRANT_TOKEN_EXCHANGE = "urn:ietf:params:oauth:grant-type:token-exchange";

    /** RFC 8693 §3 的令牌类型标识。本服务端只认这一种，也只签发这一种。 */
    public static final String TOKEN_TYPE_ACCESS_TOKEN = "urn:ietf:params:oauth:token-type:access_token";

    /**
     * 限速桶的客户端标识。
     *
     * <p>与 {@code client_credentials} 的桶刻意分开：那条路按主体 clientId 分桶，
     * 而这条路在认证成功之前<b>不知道</b>调用方是谁——失败时能用的只有来源地址。
     * 于是实际分桶是「来源 IP + 这个固定串」，即按 IP 限。
     *
     * <p>用常量而不是 {@code null}：{@code null} 会与「来源地址也拿不到」的桶撞在一起，
     * 排查时看不出是哪种调用被限了。
     */
    private static final String RATE_LIMIT_IDENTIFIER = "token-exchange";

    private final RestServerProperties properties;

    private final AccessTokenService accessTokens;

    private final TokenAuthenticationService authentication;

    private final LoginAttemptLimiter limiter;

    private final TokenIssuanceMetrics metrics;

    /**
     * 用一张访问令牌换一张新的访问令牌。
     *
     * @param subjectToken      当前有效的访问令牌（必填）
     * @param subjectTokenType  RFC 8693 要求的类型标识；空视为 access_token，
     *                          其余取值一律拒绝（见方法内注释）
     * @param requestedTokenType 期望的返回类型；空或 access_token 之外一律拒绝
     * @param actorToken        「代他人操作」的令牌；本工程没有这个模型，出现即拒
     * @param scope             请求的 scope；空则取 {@code PRINCIPAL_ROLE:ALL}
     * @param clientAddress     来源地址，用于失败限速
     * @throws ApiException 400 参数/scope 不合法、401 主体令牌无效、429 失败次数超限
     */
    public ClientCredentialsService.IssuedToken exchange(String subjectToken, String subjectTokenType,
            String requestedTokenType, String actorToken, String scope, String clientAddress) {
        if (subjectToken == null || subjectToken.isBlank()) {
            throw fail("invalid_request", "subject_token is required");
        }
        // actor_token 与 requested_token_type / subject_token_type 一样，都是「声明」。
        // 声明了却不支持的东西必须拒绝：静默忽略一个 actor_token，等于让调用方以为
        // 「这次操作记在别人名下」，而实际上记的是他自己——这类误解在审计时才会暴露
        if (actorToken != null && !actorToken.isBlank()) {
            throw fail("invalid_request",
                    "actor_token is not supported: this server has no \"act on behalf of another"
                    + " principal\" model, and ignoring it would silently attribute the request to"
                    + " the subject instead");
        }
        requireAccessTokenType("requested_token_type", requestedTokenType);
        requireAccessTokenType("subject_token_type", subjectTokenType);

        String effectiveScope = scopeOrAll(scope);

        if (!limiter.allow(rateLimit(), clientAddress, RATE_LIMIT_IDENTIFIER)) {
            log.warn("refusing a token exchange from {}: too many recent failures", clientAddress);
            metrics.record(TokenIssuanceMetrics.GRANT_TOKEN_EXCHANGE,
                    TokenIssuanceMetrics.RESULT_RATE_LIMITED);
            throw ApiException.protocolError(429, "temporarily_unavailable",
                    "too many failed token exchanges; retry after "
                    + rateLimit().getWindow().toSeconds() + " seconds");
        }

        Optional<AuthenticatedToken> subject = authentication.authenticate(subjectToken);
        if (subject.isEmpty()) {
            limiter.recordFailure(rateLimit(), clientAddress, RATE_LIMIT_IDENTIFIER);
            log.warn("token exchange rejected from {}: the subject token is not valid or has expired",
                    clientAddress);
            // invalid_grant 而不是 invalid_token：RFC 6749 §5.2 把它定义为「授权许可
            // （这里就是那张 subject token）无效」，而调用方该做的是「重新拿一张票再来」。
            // invalid_token（RFC 6750）指的是「访问受保护资源时令牌不管用」，
            // 那个错误由 BearerAuthInterceptor 那条路产生，两者不该混用同一个码
            throw fail("invalid_grant", "subject_token is not valid or has expired");
        }

        AuthenticatedToken authenticated = subject.get();
        // 链的起点：父票自己记的 ses，没有就退回它的签发时刻。
        // 两者都没有（静态令牌 / 外部票）时为 null，issue() 会以「现在」为起点
        Long sessionStart = sessionStartOf(subjectToken);
        requireSessionWithinLimit(sessionStart);
        AccessTokenService.Issued issued =
                accessTokens.issue(authenticated.principal(), effectiveScope, sessionStart);
        metrics.record(TokenIssuanceMetrics.GRANT_TOKEN_EXCHANGE, TokenIssuanceMetrics.RESULT_SUCCESS);
        log.info("exchanged an access token for principal {} (grant=token-exchange, parent jti={},"
                        + " expires in {}s)",
                authenticated.principal(), jtiOf(subjectToken), issued.expiresInSeconds());
        return new ClientCredentialsService.IssuedToken(
                issued.token(), "bearer", issued.expiresInSeconds(), effectiveScope);
    }

    /**
     * 一条续期链的起点（秒）。
     *
     * <p>{@code ses} 优先于 {@code iat}：上限打开之前签出的票没有 {@code ses}，
     * 用 {@code iat} 兜底，于是「先关着跑、后打开上限」时，正在跑的链也能被算出真实年龄。
     *
     * <p>解析失败返回 {@code null} 而不是抛：静态令牌不是 JWT，它照样可以参与交换
     * （它本来就代表一次长期授权）；这种情况下链的起点只能算作「现在」——
     * 也就是说，静态令牌换来的是新的一条链，而不是无限延长的那一条。
     */
    private static Long sessionStartOf(String subjectToken) {
        try {
            Jwt.Decoded decoded = Jwt.decode(subjectToken);
            Long session = decoded.numericClaim(AccessTokenService.CLAIM_SESSION_START);
            return session != null ? session : decoded.numericClaim("iat");
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 链龄超过 {@code max-session} 时拒绝交换。
     *
     * <p>拒绝的是「再换一张」，<b>不是</b>「手里的票作废」：已签发的票仍然可用到期。
     * 这一条是有意的——反向做法（到点即失效）会让跑满 N 小时的作业在某个请求上
     * 毫无预兆地 401，而它既没有新票也没有告警；按这里的规定，它至少能把当前这张
     * 票用完，运维在日志里看到的是「续期被拒」而不是「随机 401」。
     *
     * <p>错误码仍是 {@code invalid_grant}：RFC 6749 里它涵盖「授权许可不能再用」，
     * 而调用方能做的动作与「主体令牌过期」完全一样（重新登录 / 重新拿票）。
     * 另起一个码会让通用 OAuth 客户端把它当成未知错误丢掉原因。
     */
    private void requireSessionWithinLimit(Long sessionStart) {
        if (sessionStart == null || !properties.getAuth().getAccessToken().maxSessionEnabled()) {
            return;
        }
        long limitSeconds = properties.getAuth().getAccessToken().getMaxSession().toSeconds();
        long age = accessTokens.nowSeconds() - sessionStart;
        if (age <= limitSeconds) {
            return;
        }
        log.warn("token exchange rejected: the renewal chain started {}s ago, which exceeds"
                        + " lakehouse.rest.auth.access-token.max-session ({}s). The current token keeps"
                        + " working until it expires; a new login or client_credentials request starts a"
                        + " new chain.",
                age, limitSeconds);
        throw fail("invalid_grant", "this session is older than lakehouse.rest.auth.access-token"
                + ".max-session (" + limitSeconds + "s); obtain a fresh token with a new login or a"
                + " client_credentials request");
    }

    /**
     * 记一次失败并造出异常。
     *
     * <p>两件事绑在一起做，是为了让「某个分支忘了计数」不可能发生：这些 throw 点都在
     * 本类内，且每个都从这里出。请求参数类的错误也计数——它们同样是「有人在打这个端点
     * 却没拿到票」，而这一点正是指标要回答的。
     */
    private ApiException fail(String errorCode, String message) {
        metrics.record(TokenIssuanceMetrics.GRANT_TOKEN_EXCHANGE, errorCode);
        return ApiException.protocolError(400, errorCode, message);
    }

    /**
     * 归一化 scope，与客户端凭据流程<b>共用一份规则</b>。
     *
     * <p>缺省时取 {@code PRINCIPAL_ROLE:ALL}。文档里说「缺省沿用 subject 的 scope」，
     * 而在本工程里这两者是同一个值：{@link ClientCredentialsService} 对空 scope 也
     * 归一成它，所以本服务端签出的每一张票都带着它。外部 OIDC 票的 {@code scope}
     * claim 是 IdP 的语义（通常是 {@code openid profile}），把它原样搬进我们签的票，
     * 只会在下一次换票时变成 {@code invalid_scope}——那时距离真正的故障已经过了
     * 一个 TTL，排查成本极高。
     */
    private String scopeOrAll(String scope) {
        String normalized = ClientCredentialsService.normalizeScope(scope);
        if (normalized == null) {
            throw fail("invalid_scope",
                    "unsupported scope \"" + scope + "\": a token exchange cannot widen what the"
                    + " subject token carries, and this server grants the principal's full set of"
                    + " roles, so it only accepts " + ClientCredentialsService.SCOPE_ALL);
        }
        return normalized;
    }

    /**
     * 校验一个 RFC 8693 的类型标识。
     *
     * <p>缺省放行、取值不对拒绝：缺省只说明调用方没声明，而声明了另一种类型
     * （例如 {@code jwt} 或 {@code id_token}）说明它期望的行为我们没有实现。
     * 两者的区别是「没说」与「说了但不支持」，后者必须让它看见拒绝。
     */
    private void requireAccessTokenType(String parameter, String value) {
        if (value == null || value.isBlank() || TOKEN_TYPE_ACCESS_TOKEN.equals(value)) {
            return;
        }
        throw fail("invalid_request",
                parameter + " must be " + TOKEN_TYPE_ACCESS_TOKEN + ", got \"" + value + "\"");
    }

    /**
     * 取父票的 {@code jti} 供审计日志使用。
     *
     * <p>只用于日志，因此解析失败一律返回 {@code null} 而不抛：静态令牌不是 JWT，
     * 它同样可以参与交换（它本来就代表一次长期授权），不该因为没有 {@code jti}
     * 而无法记录这次交换。
     */
    private static String jtiOf(String token) {
        try {
            return Jwt.decode(token).claim("jti");
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 与客户端凭据流程共用同一个限速策略配置——两者都是匿名可调的换票入口。 */
    private RestServerProperties.Auth.RateLimit rateLimit() {
        return properties.getAuth().getConsole().getClientCredentials().getRateLimit();
    }
}
