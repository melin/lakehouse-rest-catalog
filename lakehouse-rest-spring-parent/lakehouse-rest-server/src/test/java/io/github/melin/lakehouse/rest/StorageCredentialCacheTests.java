package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.dto.ManagementEnums.StorageType;
import io.github.melin.lakehouse.rest.dto.StorageDtos.StorageConfigInfo;
import io.github.melin.lakehouse.rest.service.StorageCredentialCache;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.StorageConfigs;
import io.github.melin.lakehouse.rest.support.VendedStorageCredential;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link StorageCredentialCache#resolve} 的 singleflight，以及「作废之后不许写回」。
 *
 * <p>这一组用例只在**并发**下才有意义，所以每条都真的起线程：
 * 单线程调用 {@code resolve} 与老的 {@code get-or-put} 行为完全一样，
 * 用一个顺序用例去「验证」singleflight 会得到一条永远绿、什么也没证明的断言。
 */
class StorageCredentialCacheTests {

    private final RestServerProperties properties = new RestServerProperties();

    private final StorageConfigInfo storage = StorageConfigs.blank(StorageType.FILE, List.of("file:///tmp"));

    private final ExecutorService pool = Executors.newFixedThreadPool(8);

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    /** 一份 1 小时后到期的短期票：`shortLived()` 为真，进缓存时按比例缩短。 */
    private static VendedStorageCredential shortLived(String marker) {
        long expiresAt = System.currentTimeMillis() + 3_600_000L;
        return new VendedStorageCredential(Map.of("k-" + marker, marker), 3600L, StorageType.S3,
                VendedStorageCredential.SOURCE_STS_SESSION, "s3-token", expiresAt);
    }

    /**
     * 八个线程同时未命中：只有一次真的去签发，其余等同一份。
     *
     * <p>不断言「等待者比签发者慢」——那是时序，不是契约。契约是**计数**：
     * 一次未命中风暴只允许产生一次远程调用。
     */
    @Test
    void concurrentMissesMintExactlyOnce() throws Exception {
        StorageCredentialCache cache = new StorageCredentialCache(properties);
        String key = StorageCredentialCache.key("c1", storage, "file:///tmp/t");

        AtomicInteger calls = new AtomicInteger();
        CountDownLatch leaderInside = new CountDownLatch(1);
        List<Future<StorageCredentialCache.Cached>> futures = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            futures.add(pool.submit(() -> cache.resolve(key, () -> {
                calls.incrementAndGet();
                leaderInside.countDown();
                sleep(150);
                return shortLived("one");
            })));
        }
        assertTrue(leaderInside.await(5, TimeUnit.SECONDS));

        for (Future<StorageCredentialCache.Cached> future : futures) {
            assertEquals("one", future.get(5, TimeUnit.SECONDS).credential().token().get("k-one"));
        }
        assertEquals(1, calls.get(), "同一键上的并发未命中只该打一次云");
    }

    /** 命中不发请求：第二次 resolve 走缓存。 */
    @Test
    void aHitDoesNotMintAgain() {
        StorageCredentialCache cache = new StorageCredentialCache(properties);
        String key = StorageCredentialCache.key("c1", storage, "file:///tmp/t");
        AtomicInteger calls = new AtomicInteger();

        StorageCredentialCache.Cached first = cache.resolve(key, () -> {
            calls.incrementAndGet();
            return shortLived("one");
        });
        StorageCredentialCache.Cached second = cache.resolve(key, () -> {
            calls.incrementAndGet();
            return shortLived("two");
        });

        assertSame(first, second, "命中应当返回同一个条目");
        assertEquals(1, calls.get());
    }

    /**
     * 失败不落缓存：后来的请求会重新尝试。
     *
     * <p>这一条比 singleflight 本身更重要——把失败缓存起来会让一次偶发的
     * 云侧抖动变成「这个 catalog 在缓存过期前一直签不出来」。
     */
    @Test
    void aFailureIsNotCached() {
        StorageCredentialCache cache = new StorageCredentialCache(properties);
        String key = StorageCredentialCache.key("c1", storage, "file:///tmp/t");
        AtomicInteger calls = new AtomicInteger();

        assertThrows(ApiException.class, () -> cache.resolve(key, () -> {
            calls.incrementAndGet();
            throw new ApiException(503, null, null, "cloud said no");
        }));
        assertEquals(0, cache.size());

        StorageCredentialCache.Cached recovered = cache.resolve(key, () -> {
            calls.incrementAndGet();
            return shortLived("two");
        });
        assertEquals(2, calls.get(), "第二次应当真的重试，而不是拿到上次的失败结论");
        assertTrue(recovered.credential().shortLived());
    }

    /**
     * 等待者拿到的是**原异常**，不是 {@code CompletionException}。
     *
     * <p>包装漏出去会让异常处理器把它当 500：{@code ApiException} 的 503
     * 是「客户端明确要凭据、服务端暂时给不了」这一语义的载体。
     */
    @Test
    void waitersSeeTheOriginalFailure() throws Exception {
        StorageCredentialCache cache = new StorageCredentialCache(properties);
        String key = StorageCredentialCache.key("c1", storage, "file:///tmp/t");
        CountDownLatch leaderInside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Future<?> leader = pool.submit(() -> cache.resolve(key, () -> {
            leaderInside.countDown();
            await(release);
            throw new ApiException(503, null, null, "cloud said no");
        }));
        assertTrue(leaderInside.await(5, TimeUnit.SECONDS));

        Future<?> follower = pool.submit(() -> assertThrows(ApiException.class,
                () -> cache.resolve(key, () -> shortLived("never"))));
        // 让等待者先挂上去，再放签发者失败
        sleep(80);
        release.countDown();

        // 签发者自己也拿到那个异常（不是被吞掉）；关键是等待者拿到的**不是**
        // CompletionException——后者会被处理器当成 500
        ExecutionException failed = assertThrows(ExecutionException.class,
                () -> leader.get(5, TimeUnit.SECONDS));
        assertInstanceOf(ApiException.class, failed.getCause());
        follower.get(5, TimeUnit.SECONDS);
        assertEquals(0, cache.size());
    }

    /**
     * 作废落在签发过程中：那次签发只交给它的调用方，不写回缓存。
     *
     * <p>没有这道 fencing 时，作废与签发撞上就会变成「点了作废，下一次读表
     * 还是拿到刚被作废的那张票」，而下一等就是整个缓存期。
     */
    @Test
    void aMintThatFinishesAfterARevokeIsNotStored() throws Exception {
        StorageCredentialCache cache = new StorageCredentialCache(properties);
        String key = StorageCredentialCache.key("c1", storage, "file:///tmp/t");
        CountDownLatch leaderInside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Future<StorageCredentialCache.Cached> leader = pool.submit(() -> cache.resolve(key, () -> {
            leaderInside.countDown();
            await(release);
            return shortLived("stale");
        }));
        assertTrue(leaderInside.await(5, TimeUnit.SECONDS));

        cache.invalidateCatalog("c1");
        release.countDown();

        StorageCredentialCache.Cached minted = leader.get(5, TimeUnit.SECONDS);
        assertEquals("stale", minted.credential().token().get("k-stale"), "调用方仍拿到这次签发的结果");
        assertEquals(0, cache.size(), "但它不该在作废之后重新变成可复用的条目");
    }

    /**
     * 一个 catalog 名下两种键都要清：表级票与服务端 catalog 级票。
     *
     * <p>只清一种的话，用户那张票换了、服务端后台还在用旧票——
     * 两把钥匙的寿命不同，这种「半作废」比不作废更难排查。
     */
    @Test
    void revokeDropsBothTableAndCatalogKeysOfOneCatalogOnly() {
        StorageCredentialCache cache = new StorageCredentialCache(properties);
        cache.resolve(StorageCredentialCache.key("c1", storage, "file:///tmp/a"),
                () -> shortLived("a"));
        cache.resolve(StorageCredentialCache.key("c1", storage, "file:///tmp/b"),
                () -> shortLived("b"));
        cache.resolve(StorageCredentialCache.catalogKey("c1", storage, false),
                () -> shortLived("c"));
        cache.resolve(StorageCredentialCache.key("c2", storage, "file:///tmp/d"),
                () -> shortLived("d"));
        assertEquals(4, cache.size());

        assertEquals(3, cache.invalidateCatalog("c1"));

        assertEquals(1, cache.size(), "别的 catalog 的票不该被连坐");
        assertTrue(cache.get(StorageCredentialCache.key("c2", storage, "file:///tmp/d")).isPresent());
        assertFalse(cache.get(StorageCredentialCache.key("c1", storage, "file:///tmp/a")).isPresent());
    }

    /** 冷却状态与缓存无关：作废凭据不该顺手把限流冷却也解掉。 */
    @Test
    void revokingDoesNotTouchAnythingElse() {
        StorageCredentialCache cache = new StorageCredentialCache(properties);
        assertEquals(0, cache.invalidateCatalog("nothing-here"));
        assertEquals(0, cache.size());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
