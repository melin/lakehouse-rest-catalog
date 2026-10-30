package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.ThrottledException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 云侧限流的两级处置：**先重试，再冷却**。
 *
 * <p><b>为什么要分成两级。</b>「限流」有轻重两种：偶发的一两次（同一秒里几个引擎
 * 一起启动）重试一次就过去了；配额真被打满时，重试只是在别人的修复动作之前
 * 把线程一起拖住，还会让云侧更难恢复。前者用重试解决，后者必须**停下来**——
 * 停下来的代价是这段时间里所有签发都失败，收益是把请求量从「持续打满」
 * 降到「零」，云侧才有机会把令牌桶补回来。
 *
 * <p><b>冷却键是 provider 名，不是 catalog。</b>配额是按云账号算的：
 * 一个 catalog 打满之后，同一个 provider 下的别的 catalog 一样签不出来。
 * 按 catalog 分开冷却会造成「每个 catalog 各打一轮，总请求量不变，但错误信息
 * 散落在 N 个地方」。
 *
 * <p><b>这里不抛新的错误语义。</b>冷却中的调用直接拿到 {@code 503} 与剩余秒数，
 * 与限流本身的状态码一样——对调用方来说「现在拿不到票」是同一件事，
 * 差别只在消息里说清了「多久之后可以再试」。这一点很重要：把冷却做成
 * {@code 429} 会让引擎按 HTTP 语义去重试，而它重试的正是我们想让它别做的事。
 *
 * <p><b>失败不缓存结论。</b>冷却到期后第一次调用照常放行；如果云侧仍在限流，
 * 它会重新走进重试与冷却，冷却时间翻倍。没有「永久熔断」这一档：配额是
 * 分钟级的现象，一个需要人工重置的开关只会变成新的故障点。
 */
@Component
public class ThrottlingGuard {

    private static final Logger log = LoggerFactory.getLogger(ThrottlingGuard.class);

    /** 冷却时长最多翻到基础值的这么多倍，防止一次长故障冷却成半小时。 */
    private static final long MAX_COOLDOWN_FACTOR = 6L;

    /** 退避的上限：单次重试最多等这么久，再长就不如让调用方自己重来。 */
    private static final long MAX_BACKOFF_MILLIS = 2_000L;

    private final RestServerProperties properties;

    private final LongSupplier clock;

    /** 各 provider 的冷却状态。{@code null} 表示当前不在冷却中。 */
    private final Map<String, Cooldown> cooldowns = new ConcurrentHashMap<>();

    /**
     * 装配用的构造器。
     *
     * <p>标 {@code @Autowired} 是因为类里还有第二个（包级、注入时钟的）构造器：
     * 两个都不是默认构造器时，Spring 不会去猜，直接报「No default constructor found」。
     */
    @Autowired
    public ThrottlingGuard(RestServerProperties properties) {
        this(properties, System::currentTimeMillis);
    }

    /** 测试用：注入可控时钟，免得真去睡 {@code 10s}。 */
    ThrottlingGuard(RestServerProperties properties, LongSupplier clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 执行一次可能被限流的调用。
     *
     * @param key    冷却键（provider 名）
     * @param action 真正发请求的动作
     * @return 动作的返回值
     * @throws ApiException 冷却中（{@code 503} + 剩余秒数），或重试耗尽的限流
     */
    public <T> T call(String key, Supplier<T> action) {
        requireNotCoolingDown(key);
        long backoff = Math.max(1L, properties.getCredential().getRetryBackoffMillis());
        int attempts = Math.max(1, properties.getCredential().getRetryAttempts());
        for (int attempt = 1; ; attempt++) {
            try {
                T result = action.get();
                // 成功即清零：这样「偶发限流」不会累积成一个越来越长的冷却
                cooldowns.remove(key);
                return result;
            } catch (ThrottledException throttled) {
                if (attempt >= attempts) {
                    enterCooldown(key, throttled);
                    throw throttled;
                }
                long wait = Math.min(MAX_BACKOFF_MILLIS, backoff << (attempt - 1));
                // 云侧给的 Retry-After 只用来「延长」，不用来「缩短」：
                // 它可能只是 0 或 1 秒，而我们的退避曲线是按并发规模算的
                wait = Math.max(wait, throttled.retryAfterMillis());
                log.warn("{} throttled (attempt {}/{}), retrying in {}ms: {}",
                        key, attempt, attempts, wait, throttled.getMessage());
                sleep(key, wait);
            }
        }
    }

    /**
     * 当前是否在冷却中，以及还剩多久（毫秒）。供诊断端点与测试使用。
     *
     * <p><b>到期不删条目。</b>条目里带着「连续第几次」这个计数，删掉就等于
     * 把翻倍曲线重置——那样「冷却 10 秒 → 又被限流」永远是 10 秒，
     * 而云侧的限流恰恰是持续越久越难抢回配额。计数只在**成功**时清零
     * （见 {@link #call}），这里只回答「现在还能不能调」。
     *
     * @return 不在冷却中返回 {@code 0}
     */
    public long cooldownRemainingMillis(String key) {
        Cooldown cooldown = cooldowns.get(key);
        if (cooldown == null) {
            return 0L;
        }
        return Math.max(0L, cooldown.untilMillis() - clock.getAsLong());
    }

    /** 连续被限流的次数（0 = 没在冷却）。冷却时长按它翻倍。 */
    public int consecutiveThrottles(String key) {
        Cooldown cooldown = cooldowns.get(key);
        return cooldown == null ? 0 : cooldown.streak();
    }

    /** 清空冷却状态，供测试与「作废凭据」这类人工介入使用。 */
    public void reset() {
        cooldowns.clear();
    }

    private void requireNotCoolingDown(String key) {
        long remaining = cooldownRemainingMillis(key);
        if (remaining <= 0L) {
            return;
        }
        throw new ApiException(503, null, null,
                key + " is cooling down after repeated throttling; retry in "
                        + ((remaining + 999L) / 1000L) + "s"
                        + " (lakehouse.rest.credential.throttle-cooldown-seconds="
                        + properties.getCredential().getThrottleCooldownSeconds() + ")");
    }

    private void enterCooldown(String key, ThrottledException last) {
        int streak = consecutiveThrottles(key) + 1;
        long base = Math.max(1L, properties.getCredential().getThrottleCooldownSeconds()) * 1000L;
        long factor = Math.min(streak, MAX_COOLDOWN_FACTOR);
        long until = clock.getAsLong() + base * factor;
        cooldowns.put(key, new Cooldown(until, streak));
        log.warn("{} keeps throttling; cooling down for {}s (streak {}) last error: {}",
                key, (base * factor) / 1000L, streak, last.getMessage());
    }

    private static void sleep(String key, long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            // 线程被中断（停机 / 请求取消）时把中断标志还回去，
            // 让它按限流失败向上走，而不是假装睡醒了继续重试
            Thread.currentThread().interrupt();
            throw new ThrottledException(key + " was interrupted while backing off after throttling");
        }
    }

    /** 一次冷却：到什么时候为止，以及这是连续第几次。 */
    private record Cooldown(long untilMillis, int streak) {
    }
}
