package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.dto.StorageDtos.StorageConfigInfo;
import io.github.melin.lakehouse.rest.support.Json;
import io.github.melin.lakehouse.rest.support.VendedStorageCredential;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
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
        long ttlMillis = credential.ttlSeconds() * 1000L;
        // 短期凭据的寿命以云给的事实为准；静态凭据没有事实，用 ttl 当默认。
        long expiresAt = credential.shortLived() ? credential.expireTimeInMs() : issuedAtMillis + ttlMillis;
        long cacheUntil = credential.shortLived()
                ? Math.min(expiresAt, issuedAtMillis + Math.max(1L, (long) (ttlMillis * expireRatio())))
                : expiresAt;
        Cached cached = new Cached(credential, expiresAt, cacheUntil);
        if (credential.ttlSeconds() > 0) {
            entries.put(key, cached);
            evictOverflow();
        }
        return cached;
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

    /** 清空缓存，供测试隔离使用。 */
    public synchronized void clear() {
        entries.clear();
    }

    /**
     * 组装缓存键。
     *
     * <p>存储配置用 JSON 文本参与键，而不是重写一遍各字段：新增字段时
     * 这里不用跟着改，漏改的代价是缓存命中率下降而非结果错误——
     * 这个失效方向是可以接受的。
     */
    public static String key(String catalogId, StorageConfigInfo storage, String tablePath) {
        return catalogId + "|" + Json.write(storage) + "|" + (tablePath == null ? "" : tablePath);
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
