package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.support.AuthenticatedToken;
import io.github.melin.lakehouse.rest.support.Jwt;
import io.github.melin.lakehouse.rest.support.Paging;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
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
 *
 * <p><b>换钥匙时的新旧并存。</b>只有一把钥匙时，「换钥匙」是一次全量切换：
 * 所有副本必须同时改配置、同时重启，否则滚动发布的中间态会是「一半节点不认
 * 另一半签的票」——症状是随机 401，而按请求随机出现的 401 几乎不可能被归因到
 * 「刚才改了配置」。为此这里支持 {@code signing-key-id} 与 {@code verify-only-keys}：
 * 签发只认一把（写进令牌头部 {@code kid}），验签可以认多把。于是轮换可以拆成
 * 「先让所有副本学会验新钥 → 再让它们开始用新钥签 → 最后删掉旧钥」，
 * 每一步之间都有一段时间新旧票都能通过，全程零 401。
 *
 * <p><b>{@code kid} 不参与任何判定。</b>它只是「用哪把钥匙验」的索引：
 * 命中就用那把验，验不过就是 {@link Verdict#INVALID}；不命中也是
 * {@link Verdict#INVALID}，**不回退去挨个试**。回退看起来更宽容，实际会把
 * 「轮换顺序错了」（新钥还没下发就有人开始签发）变成静默通过，
 * 于是错误被推迟到第三步删旧钥时才爆发——那时更难查。
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

    /**
     * 未知 {@code kid} 的告警去重表最多记多少个。
     *
     * <p>去重表本身也需要一个上限：不设限时，任何人用随机 {@code kid} 发一串请求
     * 就能把这张表撑大——把一个「防日志刷屏」的机制变成内存增长的入口。
     * 记满之后不再告警（而不是不再拒绝）：<b>拒绝是在验签那一步决定的，与告警无关</b>。
     */
    private static final int MAX_TRACKED_UNKNOWN_KIDS = 64;

    private final RestServerProperties properties;

    /** 取当前时间。抽成字段以便测试用固定时钟确定性地验证过期。 */
    private final Clock clock;

    private final TokenIssuanceMetrics metrics;

    /** 当前用来签发的密钥。 */
    private final byte[] signingKey;

    /** 签发时写进头部 {@code kid} 的值；{@code null} 表示头部不写（单密钥部署）。 */
    private final String signingKeyId;

    /** 只验不签的旧密钥：{@code kid → 密钥}。轮换窗口结束后应当为空。 */
    private final Map<String, byte[]> verifyOnlyKeys;

    /** 已经告警过的未知 {@code kid}。见 {@link #MAX_TRACKED_UNKNOWN_KIDS}。 */
    private final Set<String> warnedUnknownKids = ConcurrentHashMap.newKeySet();

    public AccessTokenService(RestServerProperties properties, Clock clock, TokenIssuanceMetrics metrics) {
        this.properties = properties;
        this.clock = clock;
        this.metrics = metrics;
        KeyRing ring = resolveKeyRing(properties);
        this.signingKey = ring.signingKey();
        this.signingKeyId = ring.signingKeyId();
        this.verifyOnlyKeys = ring.verifyOnlyKeys();
        log.info("access token signing key id: {}; also accepting tokens signed with: {}",
                signingKeyId == null ? "(not set: the kid header is omitted)" : signingKeyId,
                verifyOnlyKeys.isEmpty() ? "(none)" : knownKeyIds());
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
        // kid：**只在配了 signing-key-id 时才写进头部**。没配时令牌形状与加这一功能前
        // 逐字一致（与 ses 同一取舍）——单密钥部署不该因为「多了个不用的功能」
        // 而让新旧票在头部就出现差异
        return new Issued(Jwt.signHs256(claims, signingKey, signingKeyId), ttl);
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
        if (!verifySignature(decoded)) {
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
     * 用「令牌头部声明的那把钥匙」验签。
     *
     * <p>三条路径，顺序就是优先级：
     *
     * <ol>
     *   <li><b>头部没有 {@code kid}</b>（升级前签发的票，或没配 {@code signing-key-id}
     *       的部署）→ 逐把试。此时唯一的线索就是签名本身，而钥匙最多三把，
     *       比较又是常量时间的，试几次不构成可利用的差异。
     *   <li><b>有 {@code kid} 且正是当前签发钥</b> → 用它验。绝大多数请求走这条。
     *   <li><b>有 {@code kid} 且命中 {@code verify-only-keys}</b> → 用旧钥验，
     *       记一次「旧钥匙仍在使用」（这是判断能不能删旧钥的唯一依据）。
     * </ol>
     *
     * <p><b>不命中就到此为止，不回退去试别的钥匙。</b>回退的代价是把配置错误洗成
     * 静默通过：新钥还没下发到某个副本，那个副本却因为「不认识的 kid 就挨个试」
     * 而收下了票——看起来一切正常，直到删旧钥的那一刻集群才开始成片 401。
     * 反过来，直接拒绝的表现是「那台机器暂时不认新票」，因果是清楚的。
     */
    private boolean verifySignature(Jwt.Decoded decoded) {
        String kid = decoded.keyId();
        if (kid == null || kid.isBlank()) {
            if (Jwt.verifyHmac(decoded, signingKey)) {
                return true;
            }
            for (Map.Entry<String, byte[]> entry : verifyOnlyKeys.entrySet()) {
                if (Jwt.verifyHmac(decoded, entry.getValue())) {
                    metrics.recordLegacyKey(entry.getKey());
                    return true;
                }
            }
            return false;
        }
        if (kid.equals(signingKeyId)) {
            return Jwt.verifyHmac(decoded, signingKey);
        }
        byte[] key = verifyOnlyKeys.get(kid);
        if (key == null) {
            warnUnknownKid(kid);
            return false;
        }
        if (!Jwt.verifyHmac(decoded, key)) {
            return false;
        }
        metrics.recordLegacyKey(kid);
        return true;
    }

    /**
     * 遇到不认识的 {@code kid} 时告警一次。
     *
     * <p><b>按 kid 去重</b>：这张票可能是伪造的，而伪造者可以换着 {@code kid}
     * 反复发请求。不去重时一次扫描就能把日志刷到看不见别的东西，
     * 而真正的配置错误（某个副本还没学会新钥）也就在噪声里了。
     *
     * <p>反过来，去重表有上限也意味着「有人用随机 kid 打满 64 个」之后
     * 新的未知 kid 不再告警。这个取舍是有意的：<b>拒绝发生在验签那一步，
     * 与告警无关</b>，少一条日志不会让任何请求被放行。
     */
    private void warnUnknownKid(String kid) {
        if (warnedUnknownKids.size() >= MAX_TRACKED_UNKNOWN_KIDS || !warnedUnknownKids.add(kid)) {
            return;
        }
        log.warn("access token carries kid '{}' which this instance does not know; it will be rejected."
                + " This instance signs with {} and accepts {}. If it started right after a key"
                + " rotation, the rolling order is wrong: every instance must learn the new key"
                + " BEFORE any instance starts signing with it (see README.md > 轮换签名密钥)",
                kid, signingKeyId == null ? "(no kid)" : signingKeyId, knownKeyIds());
    }

    /** 本实例认识的全部 kid（**只有标识，没有密钥**）。 */
    private List<String> knownKeyIds() {
        Set<String> kids = new TreeSet<>(verifyOnlyKeys.keySet());
        if (signingKeyId != null) {
            kids.add(signingKeyId);
        }
        return kids.stream().map(kid -> kid.equals(signingKeyId) ? kid + " (signing)" : kid).toList();
    }

    /**
     * 解析整套密钥。
     *
     * <p><b>格式错误时启动失败，而不是退回随机密钥</b>：一个写错的密钥配置
     * 如果被静默忽略，部署者会以为「配好了」，直到重启后发现所有人掉线、
     * 多实例之间互相 401 才回头怀疑。密钥长度不够同样失败——
     * HMAC-SHA256 用短密钥签出来的令牌是可以被暴力还原密钥的。
     *
     * <p><b>只有「会签出没人能验的票」的错误才拒绝启动</b>，其余一律告警：
     *
     * <ul>
     *   <li>拒绝：任一把钥匙 base64 非法或解码后不足 32 字节——它会在验签时被
     *       {@link Jwt#verifyHmac} 直接拒掉，于是「我明明配了旧钥」变成全量 401。
     *   <li>拒绝：{@code verify-only-keys} 里出现空 kid——令牌头部永远不可能带空 kid，
     *       这一项永远命中不了，属于纯粹写错。
     *   <li>拒绝：{@code verify-only-keys} 的 kid 与 {@code signing-key-id} 相同——
     *       同一把标识对应两把钥匙，验签只会用到签发的那个，旧钥被静默架空，
     *       轮换第三步（删旧钥）就变成了「删掉一个从来没生效过的配置」。
     *   <li>告警：配了 {@code verify-only-keys} 却没配 {@code signing-key-id}——
     *       新票头部没有 kid，仍能验过（走逐把试的路径），但「哪把是当前钥」
     *       在令牌里看不出来，第三步只能靠等满一个 TTL。
     * </ul>
     */
    private static KeyRing resolveKeyRing(RestServerProperties properties) {
        RestServerProperties.Auth.AccessToken config = properties.getAuth().getAccessToken();
        byte[] signingKey = resolveSigningKey(config.getSigningKey());
        String signingKeyId = blankToNull(config.getSigningKeyId());

        Map<String, byte[]> verifyOnly = new LinkedHashMap<>();
        Map<String, String> configured = config.getVerifyOnlyKeys();
        if (configured != null) {
            configured.forEach((rawKid, rawKey) -> {
                String kid = blankToNull(rawKid);
                if (kid == null) {
                    throw new IllegalStateException("lakehouse.rest.auth.access-token.verify-only-keys"
                            + " contains an entry with an empty key id; a token header can never carry"
                            + " an empty kid, so such an entry would never be used");
                }
                if (kid.equals(signingKeyId)) {
                    throw new IllegalStateException("lakehouse.rest.auth.access-token.verify-only-keys"
                            + " must not contain the signing key id ('" + kid + "'): the entry would be"
                            + " shadowed by the signing key and never used, so a rotation that relies on"
                            + " it would silently fail");
                }
                verifyOnly.put(kid, decodeKey(rawKey,
                        "lakehouse.rest.auth.access-token.verify-only-keys[" + kid + "]"));
            });
        }

        boolean keyIsRandom = blankToNull(config.getSigningKey()) == null;
        if (verifyOnly.isEmpty()) {
            // 没有旧钥，没什么可提醒的
        } else if (keyIsRandom) {
            log.warn("lakehouse.rest.auth.access-token.verify-only-keys is set but signing-key is empty:"
                    + " every instance signs with its own random key, so tokens issued by one instance"
                    + " will be rejected by the others regardless of the keys listed here. Set"
                    + " signing-key in production");
        } else if (signingKeyId == null) {
            log.warn("lakehouse.rest.auth.access-token.verify-only-keys is set but signing-key-id is not:"
                    + " newly issued tokens carry no kid, so old and new keys can only be told apart by"
                    + " trying them one by one. Set signing-key-id to make the rotation observable");
        }
        return new KeyRing(signingKey, signingKeyId, Map.copyOf(verifyOnly));
    }

    /**
     * 解析签发密钥；未配置时随机生成一把。
     *
     * <p>随机密钥的告警保留在这里而不是上移到 {@link #resolveKeyRing}：
     * 它是「本进程自己造了一把别人不认识的钥匙」，与后面那些「配置写错了」不是一类事。
     */
    private static byte[] resolveSigningKey(String configured) {
        if (blankToNull(configured) == null) {
            byte[] generated = new byte[48];
            new SecureRandom().nextBytes(generated);
            log.warn("lakehouse.rest.auth.access-token.signing-key is not set: a random key was generated"
                    + " for this process. Tokens issued now will stop working after a restart, and"
                    + " multiple instances will not accept each other's tokens. Set it in production"
                    + " (generate one with: openssl rand -base64 48)");
            return generated;
        }
        return decodeKey(configured, "lakehouse.rest.auth.access-token.signing-key");
    }

    /** 把一个 Base64 配置值解成密钥，长度不足直接拒绝启动。 */
    private static byte[] decodeKey(String base64, String path) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(path + " must be base64 (generate one with:"
                    + " openssl rand -base64 48)", e);
        }
        if (decoded.length < Jwt.MIN_HMAC_KEY_BYTES) {
            throw new IllegalStateException(path + " must decode to at least "
                    + Jwt.MIN_HMAC_KEY_BYTES + " bytes, got " + decoded.length);
        }
        return decoded;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 解析好的一套密钥。构造时校验完毕，运行时不再判断配置合法性。 */
    private record KeyRing(byte[] signingKey, String signingKeyId, Map<String, byte[]> verifyOnlyKeys) {
    }
}
