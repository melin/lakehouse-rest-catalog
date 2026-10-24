package io.github.melin.lakehouse.rest.spark.client;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 引擎侧的策略缓存：键 {@code (catalog, database, table, principal)}，TTL 到期即失效
 * （{@code docs/row-filter-column-mask-design.md} §5.4）。
 *
 * <p><b>为什么按主体分片。</b>服务端下发前把 {@code $principal} 折成调用者名、按豁免名单
 * 判定——不同主体拿到的表达式不同，同一把键空间里混放必然串数据。主体用调用者令牌指代：
 * 一个会话一个令牌、令牌换主体必换，粒度等价（{@code principal} 回显要等第一次请求才有，
 * 而缓存键在请求之前就要算出来）。
 *
 * <p><b>刻意不做过期清扫线程。</b>过期条目只在被再次读到时失效——策略表的规模是
 * 「被查询的表数」，惰性清理足够；后台线程反而要在 driver 上常驻一个定时器。
 * 条目数有上限（够一个大作业扫描的表数），超过即整体清空：策略缓存 miss 的代价是
 * 一次 HTTP 往返，不是正确性问题；反方向（该失效的没失效）才是。
 *
 * <p><b>TTL = 0 表示关闭缓存。</b>每次读取都 miss——用例表（§10.2 第 9 条）与
 * 「改完策略立刻想看到」的场景靠它。
 *
 * <p>零依赖（只用 JDK 并发容器）：本类在 {@code spark-common} 里，与
 * {@link ManagementApiClient} 同一约束——最终进 Spark 的 classpath，不引入新库。
 */
public final class PolicyCache {

    /** 条目上限。超过按「全部失效」处理，见类注释——宁可多几次 HTTP，不可错留旧策略。 */
    private static final int MAX_ENTRIES = 10_000;

    private final long ttlMillis;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    private static final class Entry {
        final Object value;
        final long expireAtMillis;

        Entry(Object value, long expireAtMillis) {
            this.value = value;
            this.expireAtMillis = expireAtMillis;
        }
    }

    /**
     * @param ttlMillis 缓存时长；&le;0 表示关闭缓存（永远 miss）
     */
    public PolicyCache(long ttlMillis) {
        this.ttlMillis = ttlMillis;
    }

    /** 命中且未过期返回缓存值，否则 {@code null}。过期条目顺手移除（惰性清理）。 */
    public Object get(String key) {
        if (ttlMillis <= 0) {
            return null;
        }
        Entry entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() >= entry.expireAtMillis) {
            entries.remove(key);
            return null;
        }
        return entry.value;
    }

    /** 写入；TTL 关闭时是 no-op（缓存关着，写了也读不到，不如省一次哈希）。 */
    public void put(String key, Object value) {
        if (ttlMillis <= 0) {
            return;
        }
        if (entries.size() >= MAX_ENTRIES && !entries.containsKey(key)) {
            entries.clear();
        }
        entries.put(key, new Entry(value, System.currentTimeMillis() + ttlMillis));
    }

    /** 条目数，供测试与诊断。 */
    public int size() {
        return entries.size();
    }

    /** 全部失效（{@code REFRESH TABLE} 类语义预留；当前没有挂接点，先给入口）。 */
    public void invalidateAll() {
        entries.clear();
    }

    /** 缓存是否关闭，供测试断言。 */
    public boolean isDisabled() {
        return ttlMillis <= 0;
    }

    /** 供诊断输出：只报条目数，不报键（键里有令牌）。 */
    @Override
    public String toString() {
        return "PolicyCache[ttlMillis=" + ttlMillis + ", entries=" + entries.size() + "]";
    }

    /** 组缓存键；各段不转义——段间用 '\u0000' 分隔，段内容里不可能出现 NUL。 */
    public static String key(String catalog, String database, String table, String principalShard) {
        return catalog + "\u0000" + database + "\u0000" + table + "\u0000" + principalShard;
    }
}
