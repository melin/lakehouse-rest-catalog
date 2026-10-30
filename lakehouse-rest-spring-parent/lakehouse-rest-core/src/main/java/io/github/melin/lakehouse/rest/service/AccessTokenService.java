package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.support.AuthenticatedToken;
import io.github.melin.lakehouse.rest.support.Jwt;
import io.github.melin.lakehouse.rest.support.Paging;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 控制台登录签发的访问令牌。
 *
 * <p>对应 {@code lakehouse.rest.auth.access-token.*}。本服务只做两件事：
 * 签一个令牌出去，验一个令牌回来。凭据校验（clientId / clientSecret 对不对）
 * 发生在调用方，因为那是主体仓库的事，不是签名的事。
 *
 * <p><b>令牌是自包含的。</b>载荷里只有主体名与时间，服务端不存任何东西。
 * 由此得到三个性质，都是上一版（内存会话表）缺的：
 *
 * <ul>
 *   <li><b>多实例互认</b>——任一实例签发的令牌，其余实例凭同一份密钥即可验证。
 *       内存会话表做不到这点，负载均衡下登录会时灵时不灵。
 *   <li><b>重启不掉线</b>——密钥在配置里，重启后仍然能验签。
 *   <li><b>代价是撤销</b>——令牌签发后到过期前始终有效，服务端没有「作废它」
 *       的开关。轮换签名密钥能让全部令牌立刻失效，但那不区分对象。
 *       因此 {@code ttl} 是唯一的暴露窗口上限，默认取 1 小时而非一天。
 * </ul>
 *
 * <p><b>签名密钥未配置时随机生成。</b>这样本地起一个实例就能用，但重启会让
 * 已签发的令牌全部失效、多实例之间互不认账。启动日志会就此告警，
 * 生产必须显式配置（{@code openssl rand -base64 48}）。
 */
@Slf4j
@Service
public class AccessTokenService {

    /**
     * 续期链起点的 claim 名。
     *
     * <p>写在这里而不是各写一份字面量：写的一侧（{@link #issue}）与读的一侧
     * （{@code TokenExchangeService}）必须用同一个键。拼错的表现是「上限设了却不生效」——
     * 没有报错，也没有日志，只是永远算不出链龄。
     */
    public static final String CLAIM_SESSION_START = "ses";

    private final RestServerProperties properties;

    /** 取当前时间。抽成字段以便测试用固定时钟确定性地验证过期。 */
    private final Clock clock;

    private final byte[] signingKey;

    public AccessTokenService(RestServerProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.signingKey = resolveSigningKey(properties);
    }

    /** 签发结果：令牌与其有效期（秒）。两者的有效范围由同一处计算，不会各说各话。 */
    public record Issued(String token, long expiresInSeconds) {
    }

    /**
     * 一个令牌的判定结果。
     *
     * <p>三种取值而不是布尔值，是为了让 401 的响应能区分「过期」与「无效」。
     * 两者的处理方式完全不同：过期该去续期（或重新登录），无效该检查配置或密钥。
     * 报文里不加区分时，一线看到的是同一条 401，于是「票过期」被当成「权限不对」排查。
     *
     * <p>{@link #EXPIRED} 的前提是**签名与签发者都对**——也就是说，能拿到这个答案的人
     * 手里本来就有一张真票的副本。因此它不构成对攻击者的额外信息（无法凭它伪造票）。
     */
    public enum Verdict {
        OK,
        EXPIRED,
        INVALID
    }

    /** 令牌有效期（秒）。控制台登录页据此提示「多久后需要重新登录」。 */
    public long ttlSeconds() {
        return Math.max(1, properties.getAuth().getAccessToken().getTtl().toSeconds());
    }

    /**
     * 本服务看到的当前时刻（秒）。
     *
     * <p>对外暴露是为了让「令牌的年龄」只有一个算法：{@code ses} 由这里写、
     * 由这里判，{@code max-session} 的比较也走这里。若换票那边自己再持一个
     * {@link Clock}，测试用固定时钟时两边会各说各话——而这类不一致的表现是
     * 「刚签的票被判超时」，很难联想到是时钟源不同。
     */
    public long nowSeconds() {
        return clock.millis() / 1000;
    }

    /** 签发者标识，即 JWT 的 {@code iss}。 */
    public String issuer() {
        return properties.getAuth().getAccessToken().getIssuer();
    }

    /**
     * 为指定主体签发一个访问令牌。
     *
     * @param principal 主体名。授权判定按它查授权链路，因此它必须来自可信来源
     *                  （已通过 clientSecret 校验的主体记录），不能是请求里直接给的字符串
     * @param scope     记录在令牌里的授权范围；为空则不带该 claim
     */
    public Issued issue(String principal, String scope) {
        return issue(principal, scope, null);
    }

    /**
     * 为指定主体签发令牌，并沿用一条续期链的起点。
     *
     * @param sessionStartSeconds 这条链的起点（秒）；{@code null} 表示这是一张新票
     *                            （登录 / 客户端凭据 / 冷启动），起点就是现在。
     *                            令牌交换会把父票的起点传进来，于是整条链共享同一个
     *                            {@code ses}，{@code max-session} 才能对「链」而不是
     *                            「票」设上限。**{@code max-session} 关闭时它被忽略**
     */
    public Issued issue(String principal, String scope, Long sessionStartSeconds) {
        long now = nowSeconds();
        long ttl = ttlSeconds();

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", issuer());
        claims.put("sub", principal);
        claims.put("iat", now);
        claims.put("exp", now + ttl);
        // jti 目前只用于日志与排查：没有撤销机制，它不参与判定。
        // 留着是因为将来若加黑名单，这是现成的键——事后补一个 claim 会让
        // 已签发的令牌与新签发的令牌形状不一致
        claims.put("jti", Paging.newId());
        // ses：续期链的起点。**只在 max-session 打开时才放进去**——关掉时令牌形状与
        // 改造前完全一致，这是最省事的兼容：没有 ses 的部署里，「这张票是哪条链上的」
        // 这个问题不存在，也就没人会去读它。
        // 反过来（上限打开）时，父票没有 ses 也能算出链龄：换票那边退回父票的 iat，
        // 所以「先关着跑一段、再打开上限」的链不会被误判成刚开头
        if (properties.getAuth().getAccessToken().maxSessionEnabled()) {
            claims.put(CLAIM_SESSION_START, sessionStartSeconds == null ? now : sessionStartSeconds);
        }
        if (scope != null && !scope.isBlank()) {
            claims.put("scope", scope);
        }
        return new Issued(Jwt.signHs256(claims, signingKey), ttl);
    }

    /**
     * 判定一个已经解析过的令牌，区分「过期」与「无效」。
     *
     * <p>顺序与 {@link #verify(Jwt.Decoded)} 完全一致，只是把失败的那一步记下来：
     * 签名 → 签发者 → 过期 → not-before → 主体名。**先判签名再判过期**，
     * 因为「签名不对」时 {@code exp} 是不可信输入，据此回 EXPIRED 会让攻击者
     * 用一个乱签的令牌探测「这张票原来什么时候过期」。
     */
    public Verdict examine(Jwt.Decoded decoded) {
        if (!Jwt.verifyHmac(decoded, signingKey)) {
            return Verdict.INVALID;
        }
        if (!issuer().equals(decoded.claim("iss"))) {
            return Verdict.INVALID;
        }
        long now = nowSeconds();
        Long expiresAt = decoded.numericClaim("exp");
        if (expiresAt == null) {
            return Verdict.INVALID;
        }
        if (now >= expiresAt) {
            return Verdict.EXPIRED;
        }
        Long notBefore = decoded.numericClaim("nbf");
        if (notBefore != null && now < notBefore) {
            return Verdict.INVALID;
        }
        String principal = decoded.claim("sub");
        return principal == null || principal.isBlank() ? Verdict.INVALID : Verdict.OK;
    }

    /**
     * 验证令牌并取出主体名。
     *
     * <p>任何一步不通过都返回空——调用方只需要知道「这个令牌能不能用」，
     * 把失败原因分成七八种只会让鉴权失败时的处理分叉。
     * 需要区分原因的场合（排查 OIDC 配置）由 {@code OidcService} 自己写日志。
     */
    public Optional<AuthenticatedToken> verify(String token) {
        Jwt.Decoded decoded;
        try {
            decoded = Jwt.decode(token);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return verify(decoded);
    }

    /**
     * 验证一个已经解析过的令牌。
     *
     * <p>认证链要依次问三个来源「这是你的令牌吗」，各自都从同一个令牌串出发。
     * 提供这个重载是为了让一次请求只解析一遍 JWT——解析本身很便宜，
     * 但三个来源各解析一遍会让「令牌只被解析一次」这个不变量变得需要靠注释维持。
     * <b>调用前必须已经确认这不是静态令牌</b>：静态令牌不是 JWT，解析会失败。
     */
    public Optional<AuthenticatedToken> verify(Jwt.Decoded decoded) {
        // 判定只有一份（examine），这里只负责在通过之后取出身份。
        // 两处各写一遍的后果是「verify 通过、examine 说过期」这种自相矛盾，
        // 而它出现的地方恰好是 401 的归因
        if (examine(decoded) != Verdict.OK) {
            return Optional.empty();
        }
        Long expiresAt = decoded.numericClaim("exp");
        return Optional.of(new AuthenticatedToken(decoded.claim("sub"), expiresAt * 1000L));
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 解析签名密钥。
     *
     * <p>格式错误时**启动失败**而不是退回随机密钥：一个写错的密钥配置
     * 如果被静默忽略，部署者会以为「配好了」，直到重启后发现所有人掉线、
     * 多实例之间互相 401 才回头怀疑。密钥长度不够同样失败——
     * HMAC-SHA256 用短密钥签出来的令牌是可以被暴力还原密钥的。
     */
    private static byte[] resolveSigningKey(RestServerProperties properties) {
        String configured = properties.getAuth().getAccessToken().getSigningKey();
        if (configured == null || configured.isBlank()) {
            byte[] generated = new byte[48];
            new SecureRandom().nextBytes(generated);
            log.warn("lakehouse.rest.auth.access-token.signing-key is not set: a random key was generated"
                    + " for this process. Tokens issued now will stop working after a restart, and"
                    + " multiple instances will not accept each other's tokens. Set it in production"
                    + " (generate one with: openssl rand -base64 48)");
            return generated;
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(configured.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "lakehouse.rest.auth.access-token.signing-key must be base64 (generate one with:"
                            + " openssl rand -base64 48)", e);
        }
        if (decoded.length < Jwt.MIN_HMAC_KEY_BYTES) {
            throw new IllegalStateException("lakehouse.rest.auth.access-token.signing-key must decode to at"
                    + " least " + Jwt.MIN_HMAC_KEY_BYTES + " bytes, got " + decoded.length);
        }
        return decoded;
    }
}
