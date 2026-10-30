package io.github.melin.lakehouse.rest.engine.auth;

import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一张会自己续期的票：进程级缓存 + single-flight + 退避。
 *
 * <p>所有「什么时候换、失败了怎么办」的规则都在这里，两个消费面（Paimon 的
 * {@code AuthProvider}、Spark 治理通道）只是调用它。
 *
 * <h2>四条规则，每条都对应一类会静默出错的写法</h2>
 *
 * <ol>
 *   <li><b>进窗口才换，不是每次都换。</b>{@code mergeAuthHeader} 每个 HTTP 请求都被调，
 *       每次都换票等于把换票端点变成整条链路最热的接口，也让服务端日志里全是交换记录。
 *       窗口默认 {@code min(ttl × 15%, 5min)}——与服务端签票的 TTL 成比例，
 *       不写死数值：把 TTL 调到 90 秒做验收时，窗口自动变成 13.5 秒。
 *   <li><b>并发只换一次（single-flight）。</b>双检 + 锁。没有这一条的话，一个
 *       Spark 作业的 8 个 task 同时进窗口就会发 8 次换票，服务端那边每次都要
 *       验签 + 查主体 + 签票，而它们换来的是同一张等权的票。
 *   <li><b>换票失败返回旧票，不抛异常。</b>旧票可能还剩几十秒，抛出去会把
 *       「本来能成功的请求」变成 500。失败按 1s / 2s / 4s … 退避（上限 30s）并打 WARN
 *       ——让下一次请求别再撞墙，而不是让这一次请求死掉。
 *   <li><b>不知道什么时候过期就不假装能续。</b>{@code expires_in} 与 JWT 的 {@code exp}
 *       都没有时，判定「不可续」并打一条 WARN。比「每 5 分钟盲换一次」诚实：
 *       后者在票其实很长寿时是纯浪费，在票已失效时是白发请求。
 * </ol>
 *
 * <h2>为什么缓存必须是进程级</h2>
 *
 * Paimon 的 {@code RESTTokenFileIO.refreshToken()} 里有
 * {@code apiInstance = new RESTApi(catalogContext.options(), false)}——**每次刷新数据票
 * 都会重建一个 RESTApi**，于是 {@code createAuthProvider} 被反复执行。
 * 把当前票缓存在 provider 实例字段里，新实例拿到的只有配置里那张**初始票**（早已过期）。
 * 症状是「目录能读、读表 1 小时后 401」：目录那条路（同一个实例）在正常续期，
 * 数据票那条路每换一次都重置成初始票。两条路径表现不一致，极难归因。
 * 所以状态放在 {@link RefreshingTokens} 的静态表里，按「端点 + 主体身份」分组。
 *
 * <h2>时钟可注入</h2>
 *
 * 窗口是「TTL 的 15%」，默认 TTL 一小时 → 窗口 9 分钟。「9 分钟之后该换票」这种事
 * 没法靠等来验证，所以时间从 {@link Clock} 取，测试把窗口调到毫秒级。
 */
public final class RefreshingToken implements TokenSource {

    private static final Logger LOG = LoggerFactory.getLogger(RefreshingToken.class);

    /** 默认窗口的上限。TTL 再长也不需要提前 5 分钟以上去换。 */
    static final long MAX_WINDOW_MILLIS = Duration.ofMinutes(5).toMillis();

    static final long MIN_BACKOFF_MILLIS = 1_000;

    static final long MAX_BACKOFF_MILLIS = 30_000;

    /** 日志与错误消息里标识「这是哪张票」，即缓存键。不含密钥。 */
    private final String label;

    private final TokenClient client;

    /** {@code oauth2.refresh-window}；{@code null} 表示按 TTL 推算。 */
    private final Duration configuredWindow;

    private final Clock clock;

    private final Object lock = new Object();

    private volatile Entry entry;

    /**
     * 服务端是否拒绝 {@code token-exchange}。
     *
     * <p>一旦发现就粘住：这是服务端能力问题，不会在同一个进程生命周期内自愈，
     * 每次刷新都先去撞一次 400 只是白打请求。放在实例上而不是 {@link Entry} 里，
     * 是因为它描述的是「这条路通不通」，与「当前这张票」无关。
     */
    private volatile boolean exchangeUnsupported;

    RefreshingToken(String label, String initialToken, TokenClient client, Duration configuredWindow,
                    Clock clock) {
        this.label = label;
        this.client = client;
        this.configuredWindow = configuredWindow;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.entry = initialEntry(initialToken);
    }

    @Override
    public String token() {
        Entry current = entry;
        if (!due(current)) {
            return current.token();
        }
        synchronized (lock) {
            Entry latest = entry;
            if (!due(latest)) {
                return latest.token();
            }
            if (latest.nextAttemptAtMillis() > clock.millis()) {
                // 退避期内：仍返回旧票。这一步是「换票失败」之后不再打服务端的地方，
                // 也是它让「服务端宕了 10 分钟」不会变成「每个请求都超时 10 秒」
                return latest.token();
            }
            return refresh(latest).token();
        }
    }

    /**
     * 强制换票，只有真的拿到另一张票才返回 {@code true}。
     *
     * <p>调用场景是 401 重放。返回布尔而不是无返回值，是为了让调用方区分
     * 「换到了新票，值得重发」与「没换到（固定票 / 换票失败 / 退避中），重发也是同一个 401」。
     * 把后一种也重发，等于把一次 401 变成两次，并把重试风暴引到服务端。
     */
    @Override
    public boolean invalidate() {
        synchronized (lock) {
            Entry current = entry;
            if (current.nextAttemptAtMillis() > clock.millis()) {
                // 刚失败过，退避没到点。这里不越过退避重试：401 是「票不对」，
                // 连着换票通常是同一个原因（服务端拒了 / 票被撤了），
                // 越过退避只会把「服务端不可达」放大成重试风暴
                return false;
            }
            Entry refreshed = refresh(current);
            String before = current.token();
            String after = refreshed.token();
            return after != null && !after.equals(before);
        }
    }

    /** 当前这张票是否可续（供测试与诊断；不影响取票行为）。 */
    boolean refreshable() {
        Entry current = entry;
        return current.token() != null && current.expiresAtMillis() > 0;
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 一条不可变的状态快照。
     *
     * <p>整条替换而不是就地改字段：{@link #token()} 的无锁快路径读的就是它，
     * 分几个字段读会在换票的瞬间看到「新票 + 旧过期时间」这种半更新的组合。
     *
     * @param token              当前票；{@code null} 表示还没取到（冷启动）
     * @param expiresAtMillis    过期时刻；{@code 0} 表示不可续（静态令牌 / 服务端没给依据）
     * @param windowMillis       提前多久开始换
     * @param failures           连续失败次数，用于退避
     * @param nextAttemptAtMillis 退避到期时刻；{@code 0} 表示不限制
     */
    private record Entry(String token, long expiresAtMillis, long windowMillis, int failures,
                         long nextAttemptAtMillis) {
    }

    private record Expiry(long expiresAtMillis, long ttlMillis) {
    }

    /**
     * 起始状态：配置里那张票。
     *
     * <p>没有票但有凭据（{@code token} 不配、只配了 {@code oauth2.credential}）时
     * 返回一个 {@code token == null} 的状态，第一次 {@link #token()} 就会去
     * {@code client_credentials} 取票——这是冷启动路径。
     */
    private Entry initialEntry(String initialToken) {
        if (initialToken == null || initialToken.isBlank()) {
            return new Entry(null, 0, 0, 0, 0);
        }
        Long exp = JwtClaims.numericClaim(initialToken, "exp");
        if (exp == null) {
            // 不是 JWT（静态令牌）或没有 exp：不可续。这里**不**打 WARN——
            // 静态令牌是合法配置（服务端 auth.tokens 那一档），
            // 「它不会自动续期」由 AuthProvider 的初始化日志统一说一次
            return new Entry(initialToken, 0, 0, 0, 0);
        }
        long now = clock.millis();
        long ttl = ttlMillisOf(initialToken, exp, now);
        long expiresAt = exp * 1000L;
        if (ttl <= 0) {
            // 有 exp 但算不出正寿命：这张票**已经过期**了（exp 在过去，且没有 iat 可作声明寿命）。
            // 有凭据时标成「此刻到期」，第一次取票就会走 client_credentials——否则这里会静默地
            // 返回一张死票，而工厂那边「起始票过期但配了凭据可用」这句承诺就不成立
            // （服务端签发的票都带 iat，踩不到；外部 IdP 的票可能不带）。
            // 没有凭据时没什么可做的，按不可续处理，让服务端的 401 说话
            return client.hasCredential()
                    ? new Entry(initialToken, now, 0, 0, 0)
                    : new Entry(initialToken, 0, 0, 0, 0);
        }
        return new Entry(initialToken, expiresAt, windowMillis(ttl), 0, 0);
    }

    private boolean due(Entry current) {
        if (current.token() == null) {
            return true;
        }
        if (current.expiresAtMillis() <= 0) {
            return false;
        }
        return clock.millis() >= current.expiresAtMillis() - current.windowMillis();
    }

    private Entry refresh(Entry current) {
        try {
            IssuedToken issued = obtain(current);
            Entry next = entryOf(issued);
            entry = next;
            return next;
        } catch (RuntimeException e) {
            int failures = current.failures() + 1;
            long backoff = backoffMillis(failures);
            Entry next = new Entry(current.token(), current.expiresAtMillis(),
                    current.windowMillis(), failures, clock.millis() + backoff);
            entry = next;
            // 累计次数写进同一条 WARN：单条 WARN 分不清「偶发一次」与「每个请求都在失败」，
            // 而后者说明配置错了（端点、凭据、服务端没开令牌端点），处置完全不同
            String cause = TokenRefreshMetrics.causeOf(e);
            TokenRefreshMetrics.failed(cause);
            LOG.warn("{}: could not refresh the access token (attempt {} for this token, {} failures"
                            + " since start), keeping the current token; next attempt in {} ms."
                            + " cause={}: {}",
                    label, failures, TokenRefreshMetrics.totalFailures(), backoff, cause, e.getMessage());
            return next;
        }
    }

    /**
     * 拿到一张新票：优先交换，服务端不认识这个 grant（或手里的票已经死了）时退回客户端凭据。
     *
     * <p>兜底只在<b>配了凭据</b>时可用，这是「只升引擎、没升服务端」那个升级窗口里的
     * 唯一活路（设计文档 §6），也是「起始票已过期但配了凭据」能一次成功的唯一原因。
     * 没有凭据就照原样抛，让上层退避并打 WARN——那时除了升级服务端 / 换一张票没有别的办法，
     * 早点让日志说清楚比反复重试有用。
     */
    private IssuedToken obtain(Entry current) {
        String currentToken = current.token();
        if (currentToken == null || exchangeUnsupported) {
            return client.clientCredentials();
        }
        if (expired(current) && client.hasCredential()) {
            // 手里的票**已经**过期：交换必然被服务端拒（invalid_grant，「subject_token 无效或已过期」），
            // 而这条路上我们有凭据，能直接取新票。不去试那一次交换，是为了让「作业排队一小时
            // 后才启动」这个场景一次成功，而不是先吃一个必定的 400 再退避一秒重来。
            //
            // 没有凭据时**仍然去交换**：那时服务端回的 invalid_grant 是唯一准确的说法
            // （我们无法区分「票过期」与「票是静态令牌」），而本地猜一个「不可续」只会更模糊
            LOG.info("{}: the configured token has already expired; fetching a new one with"
                    + " client_credentials instead of exchanging a dead token", label);
            return client.clientCredentials();
        }
        try {
            return client.exchange(currentToken);
        } catch (TokenRefreshException e) {
            if (!e.isUnsupportedGrant() || !client.hasCredential()) {
                throw e;
            }
            exchangeUnsupported = true;
            LOG.info("{}: the token endpoint does not accept token-exchange ({}); switching to"
                            + " client_credentials for subsequent refreshes",
                    label, e.errorCode());
            return client.clientCredentials();
        }
    }

    /** 这张票是否已经过期。{@code expiresAtMillis() <= 0} 表示不知道（不可续），不当成过期。 */
    private boolean expired(Entry current) {
        return current.expiresAtMillis() > 0 && clock.millis() >= current.expiresAtMillis();
    }

    private Entry entryOf(IssuedToken issued) {
        long now = clock.millis();
        Expiry expiry = expiryOf(issued, now);
        return new Entry(issued.token(), expiry.expiresAtMillis(),
                windowMillis(expiry.ttlMillis()), 0, 0);
    }

    /**
     * 从换票响应推出「什么时候过期」。
     *
     * <p>顺序是 {@code expires_in} → JWT {@code exp} → 不可续。前两个都没有时
     * 打 WARN 并判定不可续：**不要假装能续**。每 5 分钟盲换一次的方案在票很长寿时
     * 是纯浪费，在票已失效时是白发请求，而且两种情况都看不出来（都"成功"了）。
     */
    private Expiry expiryOf(IssuedToken issued, long now) {
        if (issued.expiresInSeconds() != null) {
            long ttl = issued.expiresInSeconds() * 1000L;
            if (ttl <= 0) {
                LOG.warn("{}: the token endpoint reported expires_in={}, so this token is treated as"
                                + " non-refreshable. Refreshing it would loop forever.",
                        label, issued.expiresInSeconds());
                return new Expiry(0, 0);
            }
            return new Expiry(now + ttl, ttl);
        }
        Long exp = JwtClaims.numericClaim(issued.token(), "exp");
        if (exp != null) {
            long ttl = ttlMillisOf(issued.token(), exp, now);
            if (ttl > 0) {
                return new Expiry(exp * 1000L, ttl);
            }
        }
        LOG.warn("{}: the issued token has neither expires_in nor a readable JWT exp, so it will NOT"
                        + " be refreshed automatically. Long-running jobs will fail with 401 once it"
                        + " expires. ({} occurrences since start)",
                label, TokenRefreshMetrics.failedCounting(TokenRefreshMetrics.CAUSE_UNKNOWN_EXPIRY));
        return new Expiry(0, 0);
    }

    /**
     * 由 {@code exp} 推算 TTL：有 {@code iat} 就用 {@code exp - iat}（这是服务端声明的寿命），
     * 没有就用 {@code exp - now}（剩余寿命，只可能比声明值小，因此窗口也更保守）。
     */
    private long ttlMillisOf(String token, long expSeconds, long nowMillis) {
        Long issuedAt = JwtClaims.numericClaim(token, "iat");
        long nowSeconds = nowMillis / 1000;
        long ttlSeconds = issuedAt == null ? expSeconds - nowSeconds : expSeconds - issuedAt;
        return Math.max(0, ttlSeconds * 1000L);
    }

    /**
     * 提前多久开始换。
     *
     * <p>默认 {@code min(ttl × 15%, 5min)}，与 Iceberg 的 {@code min(ttl/10, 5min)} 同一个
     * 数量级——比它稍早，因为这边换票之后还要被下一次请求用上。
     *
     * <p><b>窗口必须显著小于 TTL。</b>配成 {@code refresh-window=300s} 而 TTL 只有 90s 时，
     * 「一进窗口就换」变成「每个请求都换」：新票的窗口同样覆盖当前时刻，于是下一次请求
     * 又在窗口里。这里把窗口压到 TTL 的 90%，让「到期前必然还剩至少 10% 的余量」，
     * 把误配置从「重试风暴」降级成「换得偏早」。
     */
    private long windowMillis(long ttlMillis) {
        if (ttlMillis <= 0) {
            return 0;
        }
        long window = configuredWindow != null
                ? configuredWindow.toMillis()
                : Math.min(ttlMillis * 15 / 100, MAX_WINDOW_MILLIS);
        long ceiling = ttlMillis * 9 / 10;
        return Math.max(0, Math.min(window, ceiling));
    }

    /** 1s / 2s / 4s / 8s / 16s / 30s（封顶），按连续失败次数。 */
    static long backoffMillis(int failures) {
        long shift = Math.min(Math.max(failures - 1, 0), 5);
        return Math.min(MIN_BACKOFF_MILLIS << shift, MAX_BACKOFF_MILLIS);
    }

    @Override
    public String toString() {
        return "RefreshingToken(" + label + ")";
    }
}
