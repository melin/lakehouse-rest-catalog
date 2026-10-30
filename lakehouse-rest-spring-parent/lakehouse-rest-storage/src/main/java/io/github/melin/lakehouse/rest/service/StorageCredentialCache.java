package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.dto.StorageDtos.StorageConfigInfo;
import io.github.melin.lakehouse.rest.support.Json;
import io.github.melin.lakehouse.rest.support.VendedStorageCredential;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 已下发凭据的短期复用缓存，容量由
 * {@code lakehouse.rest.storage-credential-cache.max-entries} 决定。
 *
 * <p><b>为什么要缓存。</b>读一张表是高频动作，而凭据解析要读配置、算过期时间。
 * 接上 STS 之后还多一层理由：每次未命中都是一次远程调用，而云侧 STS 的配额是
 * 单账号数百 TPS 量级——一批引擎同时启动就会把配额打满。
 * 更实际的原因是语义：同一份未过期的凭据重复下发，调用方看到的是同一个凭据，
 * 而不是每次读表都换一把——后者会让「凭据泄漏了，作废它」这件事变得没有着力点。
 *
 * <p><b>键里放什么。</b>catalog id、整份存储配置的 JSON、表路径。放整份配置而不是
 * 只放存储类型，是因为区域、端点、{@code stsUnavailable}、具名存储都会改变下发结果；
 * 只放类型会让「改了 S3 端点」这类修改在缓存过期前不生效。
 *
 * <p><b>两个过期时刻必须分开，这是本类最容易写错的地方。</b>
 * <ul>
 *   <li>{@link Cached#expiresAtMillis()} 是**凭据自己的寿命**，会原样进协议响应
 *       （Paimon 的 {@code expiresAt}、Iceberg 的 {@code s3.session-token-expires-at-ms}、
 *       Lance 的 {@code expires_at_millis}）。它必须是云给的事实。</li>
 *   <li>{@link Cached#cacheUntilMillis()} 是**这份凭据还能从缓存里被复用的截止时刻**。
 *       短期凭据按 {@code ttl × cache-expire-ratio}（默认 0.15）缩短——
 *       1 小时的票只留 9 分钟。理由很实在：缓存到快过期才给出去，引擎拿到手
 *       用几秒就 403，而它没有任何理由预期这件事。</li>
 * </ul>
 * 把两者合成一个字段的后果是「响应里宣称 9 分钟有效」，客户端于是在第 10 分钟
 * 就去续期——用一次多余的远程调用换一个本来不必解决的问题。反过来（用寿命当缓存期）
 * 就是把快过期的票发给引擎。
 *
 * <p><b>静态凭据不按比例缩短。</b>它没有「快过期」这回事，缩短只会让转发档每次读表
 * 都重新解析一遍配置，换不到任何安全收益。
 *
 * <p><b>上限的作用。</b>条目在缓存到期时自然失效，上限只兜住
 * 「大量不同的表在同一时刻各自持有一份未过期凭据」的内存占用。
 * 淘汰用访问序（LRU）而不是插入序：持续读同一批表的场景下，
 * 插入序会把还在用的条目淘汰掉。
 */
@Component
@RequiredArgsConstructor
public class StorageCredentialCache {

    private final RestServerProperties properties;

    /** 访问序映射，容量由 {@code maxEntries} 约束。 */
    private final Map<String, Cached> entries = new LinkedHashMap<>(16, 0.75f, true);

    /**
     * 正在签发中的键。
     *
     * <p>{@link #resolve} 的 singleflight 靠它实现：同一个键上只允许一个线程
     * 真正去调用云侧，其余线程等这一个结果。不加这一层时，一批引擎同时启动
     * 会对同一张表并发打出 N 次 AssumeRole——缓存只在**签发完成之后**才有值，
     * 而 N 次并发都在那之前读到了空。
     */
    private final Map<String, CompletableFuture<Cached>> inFlight = new ConcurrentHashMap<>();

    /**
     * 作废代数。每次 {@link #invalidateCatalog} / {@link #clear} 自增一次。
     *
     * <p><b>它解决的是「作废之后又被写回」这一秒级的窗口</b>：一次签发要几十到几百毫秒，
     * 如果作废正好落在这段时间里，签发线程醒来后会把刚被作废的那份凭据原样塞回缓存，
     * 表现是「点了作废，下一次读表还是拿到那张票」。带代数写入让这次签发只对
     * **它自己的调用方**有效，不再污染缓存。这就是本仓库别处用的 fencing 思路，
     * 只是这里 fence 的是缓存而不是租约。
     */
    private final AtomicLong generation = new AtomicLong();

    /**
     * 取一份仍在缓存期内的凭据。
     *
     * <p>过期条目在读取时顺手删除，而不是等定时清理：
     * 定时任务在没有流量的部署里纯属空转，而读路径本来就要判断过期。
     */
    public synchronized Optional<Cached> get(String key) {
        Cached entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.cacheUntilMillis() <= System.currentTimeMillis()) {
            entries.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry);
    }

    /**
     * 放入一份刚签发的凭据。
     *
     * <p>{@code ttlSeconds} 非正时不入缓存：存进去立刻过期，
     * 只会白占一个可能被其它条目挤掉的位置。
     *
     * @param issuedAtMillis 签发时刻，来自调用方，便于测试注入固定时间
     * @return 该凭据连同它的两个过期时刻
     */
    public synchronized Cached put(String key, VendedStorageCredential credential, long issuedAtMillis) {
        return withExpiry(key, credential, issuedAtMillis, true);
    }

    /**
     * 取一份凭据：命中就返回，未命中就**由第一个到的线程**去签发，其余线程等它。
     *
     * <p>这是 P2-1 的 singleflight。三条约定：
     *
     * <ol>
     *   <li><b>同键只发一次请求。</b>等待者拿到的是同一个 {@link Cached} 对象，
     *       因此也拿到同一份票——审计里只出现一条 session，而不是 N 条。</li>
     *   <li><b>失败不落到缓存，也不留给后来者。</b>签发线程抛异常时，等待者拿到
     *       同一个异常（{@link CompletionException} 里那一层原样抛出），
     *       键随后被移除：下一个请求会重新尝试，而不是被一个陈旧结论挡住。</li>
     *   <li><b>作废优先。</b>签发期间发生过 {@link #invalidateCatalog}，这次签发
     *       只交给它的调用方，不写回缓存（见 {@link #generation}）。</li>
     * </ol>
     *
     * <p><b>为什么不加超时。</b>等待者与签发者等的是同一次云调用，而那次调用的
     * 连接/读取超时由 HTTP 客户端自己兜着（{@code lakehouse.rest.storage.*-timeout}）。
     * 在这里再套一层超时只会制造「签发成功了但等待者先放弃」的错配，
     * 而等待者放弃之后签发线程仍会把值放进缓存——白等一次。
     *
     * @param key    缓存键（{@link #key} 或 {@link #catalogKey}）
     * @param loader 真正去签发 / 解析凭据的动作；**必须幂等**，因为它可能被不同线程依次执行
     */
    public Cached resolve(String key, Supplier<VendedStorageCredential> loader) {
        Optional<Cached> hit = get(key);
        if (hit.isPresent()) {
            return hit.get();
        }
        CompletableFuture<Cached> mine = new CompletableFuture<>();
        CompletableFuture<Cached> running = inFlight.putIfAbsent(key, mine);
        if (running != null) {
            return await(key, running);
        }
        long startedAt = generation.get();
        try {
            Cached cached = putIfCurrent(key, loader.get(), System.currentTimeMillis(), startedAt);
            mine.complete(cached);
            return cached;
        } catch (RuntimeException failure) {
            mine.completeExceptionally(failure);
            throw failure;
        } catch (Error failure) {
            // 错误也要唤醒等待者，否则它们会一直等在它上面
            mine.completeExceptionally(failure);
            throw failure;
        } finally {
            inFlight.remove(key, mine);
        }
    }

    /**
     * 让某个键失效（下一次读取会重新签发）。
     *
     * @return 这次是否真的删掉了一条（缓存里没有也是成功，只是无事可做）
     */
    public synchronized boolean invalidate(String key) {
        return entries.remove(key) != null;
    }

    /**
     * 作废一个 catalog 名下的全部凭据——表级票与服务端 catalog 级票都要清。
     *
     * <p><b>为什么按前缀而不是按精确键。</b>缓存的键里带着整份存储配置与表路径，
     * 作废入口手里只有一个 catalog 名。两种键的前缀分别是 {@code <catalogId>|} 与
     * {@code catalog|<catalogId>|}，都靠 {@code catalogId} 定位——所以两条前缀都要扫，
     * 漏掉后一条的表现是「用户那张票换了，服务端后台还在用旧票」。
     *
     * <p>同时推进 {@link #generation}：正在签发中的那次不会把结果写回缓存。
     *
     * @return 被清掉的条数
     */
    public synchronized int invalidateCatalog(String catalogId) {
        String tablePrefix = catalogId + "|";
        String serverPrefix = "catalog|" + catalogId + "|";
        List<String> doomed = new ArrayList<>();
        for (String key : entries.keySet()) {
            if (key.startsWith(tablePrefix) || key.startsWith(serverPrefix)) {
                doomed.add(key);
            }
        }
        doomed.forEach(entries::remove);
        generation.incrementAndGet();
        return doomed.size();
    }


    /**
     * 带代数校验地放入：签发期间发生过作废就不写回。
     *
     * <p>返回值仍然交给**发起这次签发的那个调用方**——它等的是这次结果，
     * 拿到一张比缓存更新鲜的票没有坏处；不该发生的事情只有一个，就是把
     * 刚被作废的那份凭据重新变成「所有人都能复用」。
     */
    private synchronized Cached putIfCurrent(String key, VendedStorageCredential credential,
                                             long issuedAtMillis, long startedAtGeneration) {
        if (generation.get() != startedAtGeneration) {
            // 不落缓存，但仍要给出两个过期时刻供调用方应答
            return withExpiry(key, credential, issuedAtMillis, false);
        }
        return put(key, credential, issuedAtMillis);
    }

    private Cached withExpiry(String key, VendedStorageCredential credential, long issuedAtMillis,
                              boolean store) {
        long ttlMillis = credential.ttlSeconds() * 1000L;
        long expiresAt = credential.shortLived() ? credential.expireTimeInMs() : issuedAtMillis + ttlMillis;
        long cacheUntil = credential.shortLived()
                ? Math.min(expiresAt, issuedAtMillis + Math.max(1L, (long) (ttlMillis * expireRatio())))
                : expiresAt;
        Cached cached = new Cached(credential, expiresAt, cacheUntil);
        if (store && credential.ttlSeconds() > 0) {
            entries.put(key, cached);
            evictOverflow();
        }
        return cached;
    }

    /**
     * 等同一个键上正在进行的签发。
     *
     * <p>把 {@link CompletionException} 拆开再抛：等待者看到的应该是签发者看到的
     * 那个异常（{@code ApiException} 的 503、{@code ThrottledException}），
     * 而不是一层「异步任务失败」的包装——否则异常处理器会把它当成 500。
     */
    private static Cached await(String key, CompletableFuture<Cached> running) {
        try {
            return running.join();
        } catch (CompletionException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("credential vending for " + key + " failed", cause);
        }
    }

    /** 当前条目数，供测试与诊断使用。 */
    public synchronized int size() {
        return entries.size();
    }


    /** 容量上限。 */
    public long maxEntries() {
        return properties.getStorageCredentialCache().getMaxEntries();
    }

    /** 短期凭据的缓存保留比例。 */
    public double expireRatio() {
        double ratio = properties.getCredential().getCacheExpireRatio();
        // 越界时夹住而不是报错：0 会让缓存彻底失效（每读一次表换一次票），
        // 1 等于不缩短。两个极端都是可用的配置，只是不该把整个服务打挂。
        if (ratio <= 0d) {
            return 0.15d;
        }
        return Math.min(1d, ratio);
    }

    /**
     * 清空缓存，供测试隔离与「全部作废」使用。
     *
     * <p>也推进 {@link #generation}：清空的语义是「此前签发的都不算数」，
     * 而正在签发中的那次如果照常写回，就等于清空没发生。**不动
     * {@link #inFlight}**：里面的等待者还在等同一个结果，把它们摘掉只会让
     * 那些线程永远醒不过来。
     */
    public synchronized void clear() {
        entries.clear();
        generation.incrementAndGet();
    }

    /**
     * 组装缓存键（表级票）。
     *
     * <p>存储配置用 JSON 文本参与键，而不是重写一遍各字段：新增字段时
     * 这里不用跟着改，漏改的代价是缓存命中率下降而非结果错误——
     * 这个失效方向是可以接受的。
     *
     * <p>读写也要进键：同一张表的只读票与可写票是两份不同的授权，
     * 共用一条缓存会把「刚才只读」的票发给要写的那次调用，表现是写元数据 403。
     */
    public static String key(String catalogId, StorageConfigInfo storage, String tablePath) {
        return key(catalogId, storage, tablePath, false);
    }

    public static String key(String catalogId, StorageConfigInfo storage, String tablePath,
                             boolean allowWrite) {
        return catalogId + "|" + Json.write(storage) + "|"
                + (tablePath == null ? "" : tablePath) + "|" + (allowWrite ? "rw" : "r");
    }

    /**
     * 组装缓存键（服务端自己的 catalog 级票）。
     *
     * <p><b>必须与 {@link #key} 不同名。</b>两类票的作用域不同（一张表 vs 整份
     * {@code allowedLocations}），若共用一个键，先发生的那次会决定后一次拿到什么：
     * 用户先读表、服务端随后删目录，服务端就拿到一张只覆盖表的票，
     * 删到一半 403；反过来则是服务端把一张能跨表写的票留在缓存里等用户来取。
     * 前缀 {@code catalog|} 让两者不可能撞上——表路径是 URI，不会以它开头。
     */
    public static String catalogKey(String catalogId, StorageConfigInfo storage, boolean allowWrite) {
        return "catalog|" + catalogId + "|" + Json.write(storage) + "|" + (allowWrite ? "rw" : "r");
    }

    /**
     * 淘汰超出容量的最久未使用条目。
     *
     * <p>不用 {@code LinkedHashMap.removeEldestEntry}：那个钩子在 {@code put} 内部调用，
     * 想让它读 {@code properties} 就得把上限塞进匿名子类，
     * 与其让上限藏在一个构造表达式里，不如在调用点显式写明。
     */
    private void evictOverflow() {
        long max = maxEntries();
        if (max <= 0) {
            entries.clear();
            return;
        }
        Iterator<String> oldest = entries.keySet().iterator();
        while (entries.size() > max && oldest.hasNext()) {
            oldest.next();
            oldest.remove();
        }
    }

    /**
     * 缓存条目。
     *
     * @param expiresAtMillis 凭据自己的到期时刻，用于协议响应；静态凭据为
     *                        {@code 签发时刻 + ttl}（那时它其实不会过期，但响应里需要有个数）
     * @param cacheUntilMillis 还能从缓存复用的截止时刻，按比例缩短后的值
     */
    public record Cached(VendedStorageCredential credential, long expiresAtMillis, long cacheUntilMillis) {

        /** 兼容构造器：两个时刻相同（静态凭据的常态）。 */
        public Cached(VendedStorageCredential credential, long expiresAtMillis) {
            this(credential, expiresAtMillis, expiresAtMillis);
        }
    }
}
