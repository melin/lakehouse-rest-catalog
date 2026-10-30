package io.github.melin.lakehouse.rest.engine.auth;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 换票失败的计数（进程级）。
 *
 * <p>对应的观测点是设计稿里的 {@code lakehouse_auth_token_refresh_failure_total{cause}}：
 * <b>跨 TTL 持续为 0 才算健康</b>。引擎这一侧没有指标注册表（它跑在 Spark / Flink 的
 * 进程里，没有 Actuator 也没有 Prometheus 端点），所以这里只提供可在进程内读到的计数，
 * 外加一条「把累计次数写进 WARN」的用法——日志是这一侧唯一能被运维看到的通道。
 *
 * <p><b>为什么计数必须有，即使已经打了 WARN。</b>单条 WARN 分不清「偶发一次」与
 * 「每个请求都在失败」：前者是服务端抖动，后者是配置错了（端点写错、凭据过期、
 * 服务端没开令牌端点），而它们的处置完全不同。把这个数字放进同一条 WARN 里，
 * 一眼就能看出是哪一种。
 *
 * <p>键是<b>失败原因</b>而不是令牌或主体：{@link #snapshot()} 的值要保持有界，
 * 否则一个有几千个作业的集群会攒出一张没法读的表。按主体拆解的需求在服务端日志里
 * （那里有 {@code sub}）。
 */
public final class TokenRefreshMetrics {

    /** 设计稿里给这条线定的名字。没有注册表时它是「名字的占位」，接上后直接用。 */
    public static final String REFRESH_FAILURE = "lakehouse.auth.token.refresh.failure";

    /** 服务端拒绝了这个 grant（{@code unsupported_grant_type}）。升级窗口里最常见。 */
    public static final String CAUSE_UNSUPPORTED_GRANT = "unsupported_grant";

    /** 换来的票没有 {@code expires_in} 也没有可读的 {@code exp}，判定为不可续。 */
    public static final String CAUSE_UNKNOWN_EXPIRY = "unknown_expiry";

    /**
     * 换票请求没能完成（连不上、超时、5xx）。
     *
     * <p>与「服务端明确拒绝」分开：这一条通常是网络或服务端整个挂了，
     * 而拒绝是配置问题。混在一起时，排查会从「检查配置」开始，方向就错了。
     */
    public static final String CAUSE_IO = "io_error";

    private static final Map<String, AtomicLong> COUNTERS = new ConcurrentHashMap<>();

    private TokenRefreshMetrics() {
    }

    /** 记一次失败。{@code cause} 由 {@link #causeOf} 归一，取值有界。 */
    static void failed(String cause) {
        COUNTERS.computeIfAbsent(cause == null ? CAUSE_IO : cause, key -> new AtomicLong())
                .incrementAndGet();
    }

    /**
     * 记一次并返回该原因的累计次数。
     *
     * <p>日志语句里需要一个数字，「先记再读」两步在这里没法写成一条表达式；
     * 拆成两句又容易出现「记了但读的是另一个键」。合成一个方法就不会错。
     */
    static long failedCounting(String cause) {
        failed(cause);
        return failures(cause);
    }

    /**
     * 某个原因累计失败了多少次。
     *
     * <p>包内可见：它是「写进 WARN 的那个数字」，不打算做成对外 API。
     */
    static long failures(String cause) {
        AtomicLong counter = COUNTERS.get(cause);
        return counter == null ? 0 : counter.get();
    }

    /** 全部原因的总失败次数，用来判断「这次是偶发还是连续」。 */
    static long totalFailures() {
        return COUNTERS.values().stream().mapToLong(AtomicLong::get).sum();
    }

    /**
     * 当前计数快照（原因 → 次数）。
     *
     * <p>给测试与将来的指标导出用。返回副本，调用方遍历时不会看到并发自增的中间状态。
     */
    public static Map<String, Long> snapshot() {
        Map<String, Long> copy = new LinkedHashMap<>();
        COUNTERS.forEach((key, value) -> copy.put(key, value.get()));
        return copy;
    }

    /** 清空计数，仅供测试：静态状态跨用例累积会让断言互相干扰。 */
    static void clear() {
        COUNTERS.clear();
    }

    /**
     * 把一个异常归一成有限的失败原因。
     *
     * <p>优先用服务端给的 OAuth 错误码（{@code invalid_grant} / {@code unsupported_grant_type}
     * / {@code invalid_scope}）——那几个值本身就是有限集合，而且直接指向配置问题。
     *
     * <p>没有错误码时按 HTTP 状态分开：{@code 0} 与 5xx 是「服务端或网络的问题」，
     * 合到 {@link #CAUSE_IO}；其余 4xx 记成 {@code http_4xx} 这种带状态的短名。
     * 刻意不用异常类名：{@code tokenrefreshexception} 这种标签在日志里既长又什么都没说，
     * 而真正有用的区分（连不上 / 被拒）本来就藏在状态码里。
     */
    static String causeOf(RuntimeException e) {
        if (e instanceof TokenRefreshException refresh) {
            if (refresh.errorCode() != null && !refresh.errorCode().isBlank()) {
                return refresh.errorCode();
            }
            return refresh.status() == 0 || refresh.status() >= 500
                    ? CAUSE_IO
                    : "http_" + refresh.status();
        }
        return e instanceof java.io.UncheckedIOException ? CAUSE_IO
                : e.getClass().getSimpleName().toLowerCase(Locale.ROOT);
    }
}
