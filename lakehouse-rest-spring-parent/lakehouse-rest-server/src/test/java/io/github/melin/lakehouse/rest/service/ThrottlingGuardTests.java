package io.github.melin.lakehouse.rest.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.ThrottledException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 限流的两级处置：先重试，重试耗尽后进入冷却，冷却期间直接失败。
 *
 * <p>时钟是注入的（{@link ThrottlingGuard} 的包级构造器），所以「冷却 10 秒」
 * 在用例里是一次 {@code clock.add(10_000)} 而不是真的睡十秒——这类用例
 * 一旦真的 sleep，就会因为机器慢而随机变红，然后被人加上 {@code @Disabled}。
 */
class ThrottlingGuardTests {

    private final RestServerProperties properties = new RestServerProperties();

    private final AtomicLong now = new AtomicLong(1_000_000L);

    private ThrottlingGuard guard;

    @BeforeEach
    void setUp() {
        // 退避改成 1 毫秒：用例要验的是「重试了几次」，不是「等了多久」
        properties.getCredential().setRetryBackoffMillis(1);
        properties.getCredential().setRetryAttempts(3);
        properties.getCredential().setThrottleCooldownSeconds(10);
        guard = new ThrottlingGuard(properties, now::get);
    }

    /** 偶发的一两次限流由重试消化掉，调用方拿到结果、也没进冷却。 */
    @Test
    void transientThrottlingIsAbsorbedByRetries() {
        AtomicInteger attempts = new AtomicInteger();
        String result = guard.call("s3-token", () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new ThrottledException("Throttling: rate exceeded");
            }
            return "ticket";
        });

        assertEquals("ticket", result);
        assertEquals(3, attempts.get());
        assertEquals(0L, guard.cooldownRemainingMillis("s3-token"), "成功之后不该留着冷却");
    }

    /**
     * 重试耗尽 → 进入冷却；冷却期内的调用**不发请求**直接失败。
     *
     * <p>「不发请求」是这条的要点：它证明冷却真的把压力从云侧拿走了，
     * 而不只是把错误换了句话。
     */
    @Test
    void exhaustedRetriesEnterCooldownThatStopsCallingTheCloud() {
        AtomicInteger attempts = new AtomicInteger();
        assertThrows(ThrottledException.class, () -> guard.call("oss-sts", () -> {
            attempts.incrementAndGet();
            throw new ThrottledException("Throttling.User: request was denied due to user flow control");
        }));
        assertEquals(3, attempts.get(), "三个 attempt 都该真的打出去");
        assertEquals(10_000L, guard.cooldownRemainingMillis("oss-sts"));
        assertEquals(1, guard.consecutiveThrottles("oss-sts"));

        ApiException rejected = assertThrows(ApiException.class,
                () -> guard.call("oss-sts", () -> {
                    attempts.incrementAndGet();
                    return "ticket";
                }));
        assertEquals(503, rejected.getStatus());
        assertTrue(rejected.getMessage().contains("cooling down"), rejected.getMessage());
        assertEquals(3, attempts.get(), "冷却期间一次也不该打出去");
    }

    /** 冷却结束之后照常放行；再次被限流则冷却翻倍。 */
    @Test
    void cooldownExpiresAndDoublesOnRepeatedThrottling() {
        alwaysThrottled("azure-sas");
        assertEquals(10_000L, guard.cooldownRemainingMillis("azure-sas"));

        now.addAndGet(10_000L);
        assertEquals(0L, guard.cooldownRemainingMillis("azure-sas"), "到点即解除");

        alwaysThrottled("azure-sas");
        assertEquals(20_000L, guard.cooldownRemainingMillis("azure-sas"), "连续第二次翻倍");
        assertEquals(2, guard.consecutiveThrottles("azure-sas"));
    }

    /** 一次成功把 streak 清零，冷却时间回到基准。 */
    @Test
    void aSuccessResetsTheStreak() {
        alwaysThrottled("gcs-downscope");
        now.addAndGet(10_000L);

        assertEquals("ok", guard.call("gcs-downscope", () -> "ok"));
        assertEquals(0, guard.consecutiveThrottles("gcs-downscope"));

        alwaysThrottled("gcs-downscope");
        assertEquals(10_000L, guard.cooldownRemainingMillis("gcs-downscope"),
                "清零之后应回到基准，而不是继续翻倍");
    }

    /** 冷却键是按 provider 分的：一家被限流不影响另一家。 */
    @Test
    void cooldownIsScopedToOneProvider() {
        alwaysThrottled("s3-token");

        assertEquals("ok", guard.call("oss-sts", () -> "ok"));
        assertEquals(0L, guard.cooldownRemainingMillis("oss-sts"));
    }

    /** 非限流的失败原样抛出，不重试也不进冷却（权限错了重试一百次也一样）。 */
    @Test
    void otherFailuresAreNotRetried() {
        AtomicInteger attempts = new AtomicInteger();
        assertThrows(ApiException.class, () -> guard.call("s3-token", () -> {
            attempts.incrementAndGet();
            throw new ApiException(503, null, null, "AccessDenied: not authorized to perform sts:AssumeRole");
        }));

        assertEquals(1, attempts.get(), "只有 ThrottledException 才重试");
        assertEquals(0L, guard.cooldownRemainingMillis("s3-token"));
    }

    /** 重试次数配上 1 时没有重试，但冷却仍然生效。 */
    @Test
    void asingleAttemptStillCoolsDown() {
        properties.getCredential().setRetryAttempts(1);
        AtomicInteger attempts = new AtomicInteger();
        assertThrows(ThrottledException.class, () -> guard.call("s3-token", () -> {
            attempts.incrementAndGet();
            throw new ThrottledException("SlowDown");
        }));

        assertEquals(1, attempts.get());
        assertTrue(guard.cooldownRemainingMillis("s3-token") > 0L);
    }

    private void alwaysThrottled(String key) {
        assertThrows(ThrottledException.class,
                () -> guard.call(key, () -> {
                    throw new ThrottledException(key + " throttled");
                }));
    }
}
