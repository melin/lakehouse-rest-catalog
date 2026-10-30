package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.support.AuthenticatedToken;
import io.github.melin.lakehouse.rest.support.Digests;
import io.github.melin.lakehouse.rest.support.Jwt;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 令牌认证链：一个 Bearer 令牌来自哪里、对应哪个主体。
 *
 * <p>三种来源依次尝试，任何一条认出令牌就停下：
 *
 * <ol>
 *   <li><b>静态令牌</b>（{@code lakehouse.rest.auth.tokens}）——运维配置的长期凭据，给机器用。
 *   <li><b>控制台访问令牌</b>（{@link AccessTokenService}）——控制台用主体凭据登录后
 *       拿到的 JWT，本服务端签发。
 *   <li><b>OIDC 令牌</b>（{@link OidcService}）——外部身份提供方签发，本服务端验签。
 * </ol>
 *
 * <p><b>顺序是有意的。</b>静态令牌是一个字符串包含判断（零解析成本），
 * 自签发的 JWT 是一次 HMAC，OIDC 的可能是 RSA 验签（最贵，还可能要刷新 JWKS）。
 * 便宜的放前面，让最常见的调用路径（引擎用静态令牌）不付验签的钱。
 *
 * <p><b>三种来源在这里合流，下游看不到区别。</b>授权判定按主体名查授权链路，
 * 审计字段记主体名——它们都不需要知道令牌是配置里的字符串、控制台签的 JWT，
 * 还是 Keycloak 签的 JWT。加一种认证方式只改这个类，
 * 这正是「认证可替换、授权只有一套」应当呈现的样子。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenAuthenticationService {

    /** 令牌来源标识。写进诊断信息与日志，不参与判定。 */
    public static final String SOURCE_STATIC = "static-token";

    public static final String SOURCE_CONSOLE = "console-access-token";

    public static final String SOURCE_OIDC = "oidc";

    private final RestServerProperties properties;

    private final AccessTokenService accessTokens;

    private final OidcService oidc;

    /**
     * 令牌被拒的原因。<b>只在认证已经失败之后才能问</b>，它不是判定结果。
     *
     * <p>三个取值对应三句不同的下步动作：没带令牌（补上）、过期（去续期或重新登录）、
     * 无效（检查配置或签名密钥）。混成同一个 401 时，一线看到的现象完全一样，
     * 于是「票过期」被当成「权限不对」排查——这正是长作业在跑满一个 TTL 之后
     * 遇到的那种情况，而它本来只需要一次自动续期。
     */
    public enum Rejection {
        /** 请求里没有 Bearer 令牌。 */
        MISSING,
        /** 令牌是本服务端签的、签名也对，只是过了 {@code exp}。 */
        EXPIRED,
        /** 签名不符、签发者不对、结构损坏，或外部 OIDC 校验没过。 */
        INVALID
    }

    /**
     * 这个被拒的令牌属于哪一种情况。
     *
     * <p><b>先验签再判过期</b>：{@code exp} 是令牌里的一个字段，签名不对时它是不可信的
     * 输入。据此回 {@code EXPIRED}，等于让攻击者用一个乱签的令牌探测「这个服务端签的票
     * 原来什么时候过期」——虽然用处不大，但没有理由给。
     *
     * <p>外部 OIDC 令牌一律归为 {@link Rejection#INVALID}：{@link OidcService} 只回
     * 「行 / 不行」，而它的失败原因（JWKS 拉不到、issuer 配错、签名算法不支持）在
     * 一线排查时看的是服务端日志，不是客户端的一个响应头。
     */
    public Rejection rejectionOf(String token) {
        if (token == null || token.isBlank()) {
            return Rejection.MISSING;
        }
        Jwt.Decoded decoded;
        try {
            decoded = Jwt.decode(token);
        } catch (IllegalArgumentException e) {
            return Rejection.INVALID;
        }
        return accessTokens.examine(decoded) == AccessTokenService.Verdict.EXPIRED
                ? Rejection.EXPIRED
                : Rejection.INVALID;
    }

    /**
     * 认证一个令牌。
     *
     * @return 令牌有效时的身份；无效（含格式不对、签名不符、已过期）时为空。
     *         <b>是否放行只看这个返回值</b>——「因为什么被拒」由
     *         {@link #rejectionOf(String)} 单独回答，它不参与判定
     */
    public Optional<AuthenticatedToken> authenticate(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        if (matchesStaticToken(token)) {
            // 静态令牌没有失效时刻：它在配置里，运维删掉它才算失效
            return Optional.of(new AuthenticatedToken(resolveStaticPrincipal(token), null));
        }

        // 走到这里令牌必须是 JWT 形状。解析一次，三个来源共用
        Jwt.Decoded decoded;
        try {
            decoded = Jwt.decode(token);
        } catch (IllegalArgumentException e) {
            // 静态令牌不认识、又不是 JWT——最常见的原因是令牌粘错或截断
            return Optional.empty();
        }

        Optional<AuthenticatedToken> fromConsole = accessTokens.verify(decoded);
        if (fromConsole.isPresent()) {
            return fromConsole;
        }
        return oidc.verify(decoded);
    }

    /** 令牌来源标识，只用于诊断展示。 */
    public String sourceOf(String token) {
        if (matchesStaticToken(token)) {
            return SOURCE_STATIC;
        }
        try {
            Jwt.Decoded decoded = Jwt.decode(token);
            if (accessTokens.verify(decoded).isPresent()) {
                return SOURCE_CONSOLE;
            }
            if (oidc.verify(decoded).isPresent()) {
                return SOURCE_OIDC;
            }
        } catch (IllegalArgumentException ignored) {
            // 解析不了就没有来源可言
        }
        return null;
    }

    /**
     * 令牌是否在静态列表中。
     *
     * <p>逐个比摘要而不是原文：令牌是长期凭据，直接比较会在第一个不同的字符处返回，
     * 理论上可被用来逐字符试探。列表通常只有几条，全部走一遍的开销可以忽略。
     */
    private boolean matchesStaticToken(String token) {
        byte[] candidate = digest(token);
        for (String registered : properties.getAuth().getTokens()) {
            if (registered != null && MessageDigest.isEqual(digest(registered), candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 静态令牌 → 主体名。
     *
     * <p>优先取 {@code token-principals} 的显式映射；未配置时退化为「令牌即主体名」，
     * 这样本地调试可以直接用 {@code -H 'Authorization: Bearer alice'} 以指定主体身份操作。
     */
    private String resolveStaticPrincipal(String token) {
        String mapped = properties.getAuth().getTokenPrincipals().get(token);
        return mapped == null || mapped.isBlank() ? token : mapped;
    }

    private static byte[] digest(String value) {
        return Digests.sha256Hex(value).getBytes(StandardCharsets.UTF_8);
    }
}
