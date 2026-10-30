package io.github.melin.lakehouse.rest.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 令牌端点（OAuth 的两种 grant）的计数器。
 *
 * <p><b>为什么现在不是 Micrometer。</b>{@code docs/metrics-design.md} 把指标这套东西
 * 定成了「P0：依赖 + 名字常量 + Actuator + 切面」，而那份设计还没落地（仓库里
 * 一个 micrometer 依赖都没有）。本类先按那份设计里<b>已经定好的名字与标签</b>计数，
 * 由 {@link #snapshot()} 与定时摘要暴露；等 Micrometer 接上时，这里的自增点直接换成
 * {@code registry.counter(...).increment()}，名字不用再改一次——指标名是契约，
 * 「先起个临时名字以后再换」等于让看板跟着改两遍。
 *
 * <p><b>它回答的问题。</b>「还在续吗」。换票量突然归零意味着某处的续期停了：
 * 要么服务端换了地址、要么引擎侧退回了固定票、要么作业根本没起来。这三种在
 * 其它指标里长得一样（请求量正常、错误率为零），只有这条线能分开。
 *
 * <p><b>标签值取自有限集合</b>（{@code grant} 两个取值、{@code result} 五个取值），
 * 与 {@code metrics-design.md} §5 的基数规则一致：不带主体名、不带来源 IP。
 * 按主体拆的统计在审计日志里（有 {@code sub} 与父票 {@code jti}），不要搬进指标。
 */
@Slf4j
@Component
public class TokenIssuanceMetrics {

    /**
     * 换票次数。名字里的点对应 Prometheus 导出后的 {@code lakehouse_auth_token_exchanged_total}。
     *
     * <p>{@code grant} 标签把两条路放进同一个指标：它们的和是「这个服务端签了多少张票」，
     * 分开看是「谁在续期」。拆成两个指标会让「总量」要用 {@code sum()} 拼出来。
     */
    public static final String EXCHANGED = "lakehouse.auth.token.exchanged";

    /** {@code grant} 标签取值。与 OAuth 端点的 {@code grant_type} 同一份字面量。 */
    public static final String GRANT_TOKEN_EXCHANGE = "token-exchange";

    public static final String GRANT_CLIENT_CREDENTIALS = "client_credentials";

    public static final String RESULT_SUCCESS = "success";

    /** 主体令牌无效或过期。调用方该重新拿票，而不是重试同一个请求。 */
    public static final String RESULT_INVALID_GRANT = "invalid_grant";

    /** 请求参数不合规（缺 subject_token、声明了 actor_token、类型不对）。 */
    public static final String RESULT_INVALID_REQUEST = "invalid_request";

    public static final String RESULT_INVALID_SCOPE = "invalid_scope";

    /** 失败次数超限，短时间内不再受理。 */
    public static final String RESULT_RATE_LIMITED = "rate_limited";

    /** 凭据不对。与 {@link #RESULT_INVALID_GRANT} 分开：这条要改配置，不是重新拿票。 */
    public static final String RESULT_INVALID_CLIENT = "invalid_client";

    /** 键是 {@code grant|result}；值单调递增。 */
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    /** 上一次摘要时的总量，用来跳过「一条都没变」的那一轮。 */
    private long reportedTotal;

    /**
     * 记一次。
     *
     * @param grant  {@link #GRANT_TOKEN_EXCHANGE} 或 {@link #GRANT_CLIENT_CREDENTIALS}
     * @param result {@link #RESULT_SUCCESS} 等
     */
    public void record(String grant, String result) {
        counters.computeIfAbsent(grant + "|" + result, key -> new AtomicLong()).incrementAndGet();
    }

    /**
     * 当前计数快照（键 {@code grant|result}）。
     *
     * <p>返回副本：调用方遍历时不能看到并发自增造成的中间状态，也不能顺手改内部表。
     */
    public Map<String, Long> snapshot() {
        Map<String, Long> copy = new LinkedHashMap<>();
        counters.forEach((key, value) -> copy.put(key, value.get()));
        return copy;
    }

    /**
     * 每 10 分钟把增量打进日志。
     *
     * <p><b>为什么是日志而不是端点。</b>这个仓库还没有指标端点（见类注释），而新增一个
     * 控制台端点要连带登记权限点、改校验脚本、增文档——为两个计数器做这些不划算。
     * 日志的代价是只能看不能画图，但它能在「换票量归零」时给出第一手证据：
     * 上一轮有数、这一轮没有，就是那一刻停的。
     *
     * <p>没有变化的那一轮什么都不打：一个每 10 分钟说一句「还是 0」的日志会很快被忽略，
     * 而它恰恰是「确实停了」的唯一信号，必须留着当异常来看。
     */
    @Scheduled(fixedDelay = 600_000L, initialDelay = 600_000L)
    public void report() {
        Map<String, Long> current = snapshot();
        long total = current.values().stream().mapToLong(Long::longValue).sum();
        if (total == reportedTotal) {
            return;
        }
        reportedTotal = total;
        log.info("token endpoint: {} (cumulative; granted tokens keep working until they expire)",
                current);
    }
}
