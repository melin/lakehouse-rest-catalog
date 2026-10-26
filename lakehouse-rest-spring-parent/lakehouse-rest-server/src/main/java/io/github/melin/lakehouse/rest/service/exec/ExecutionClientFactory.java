package io.github.melin.lakehouse.rest.service.exec;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.github.melin.lakehouse.rest.support.ClusterType;
import io.github.melin.lakehouse.rest.support.ProbeReason;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 按 catalog 取一个 fabric8 客户端：**缓存的、可关闭的、绝不回落到默认配置的**。
 *
 * <p><b>为什么缓存。</b>{@code KubernetesClient} 是重对象：内置 OkHttp 的连接池与 dispatcher
 * 线程、Watch 的线程池。每批新建一个，跑一夜之后线程数与 fd 都会涨上去，
 * 而现象是「服务端越来越慢」，很难联想到提交器。缓存键是 catalogId，
 * 值是「客户端 + 构造它时的 configHash」——配置变了自然换一个（不用等下一个人报 bug）。
 *
 * <p><b>为什么必须 close。</b>淘汰与配置变更时都要关掉旧客户端。不关的泄漏是**安静的**：
 * 每个旧客户端留下几个空闲的 OkHttp 线程，只有在 jstack 里才看得见。
 * {@link #evictOverflow()} 与 {@link #invalidate(String)} 是唯一两处移除点，都调 close。
 *
 * <p><b>绝不允许「配置不全就回落到默认」。</b>fabric8 的老习惯是
 * {@code Config.autoConfigure()}：配置缺了就去找 {@code ~/.kube/config}，再不行用集群内
 * service account。多集群场景下这是**最危险的行为**——一个填错 {@code apiServer} 的 catalog
 * 会静默地把 Job 提交到服务端自己所在的集群，而界面上一切正常。因此这里只走显式路径，
 * 并在构建后用 {@link #verifyMasterUrl} 再断言一次：宁可抛异常，也不提交到别的地方。
 */
@Component
public class ExecutionClientFactory {

    private static final Logger log = LoggerFactory.getLogger(ExecutionClientFactory.class);

    /** 同时缓存几个客户端。一个 catalog 一个，32 个已经覆盖绝大多数部署。 */
    private static final int MAX_CLIENTS = 32;

    /**
     * 请求与连接超时。
     *
     * <p>调度循环是单线程的（{@code docs/table-optimization.md} §6），而 fabric8 的调用是同步阻塞的：
     * 一个卡住的集群会把**所有** catalog 的分派都拖住。10 秒是「够慢查询返回」与
     * 「不会拖垮整轮」之间的取舍；派发侧另有逐 catalog 的 try/catch 兜底。
     */
    private static final int TIMEOUT_MILLIS = 10_000;

    /** LRU：{@code accessOrder=true}，最久没用的在最前。 */
    private final LinkedHashMap<String, Cached> cache = new LinkedHashMap<>(16, 0.75f, true);

    /**
     * 取（或建）一个客户端。
     *
     * @param configHash 当前配置的哈希；与缓存里的不一致时重建，重建前关掉旧的
     */
    public KubernetesClient clientFor(ResolvedCluster cluster, String configHash) {
        synchronized (cache) {
            Cached existing = cache.get(cluster.catalogId());
            if (existing != null && existing.configHash().equals(configHash)) {
                return existing.client();
            }
            if (existing != null) {
                // 配置变了：先关旧的。不关就是「改一次配置漏一组线程」
                cache.remove(cluster.catalogId());
                closeQuietly(existing.client(), cluster.catalogId());
            }
            KubernetesClient client = build(cluster);
            cache.put(cluster.catalogId(), new Cached(client, configHash));
            evictOverflow();
            return client;
        }
    }

    /**
     * 配置被改/被删/被停用时调用。
     *
     * <p>{@code PUT} 时就地失效，而不是等下一次提交时才发现 hash 变了：
     * 否则「改完配置立刻点验证连接」用的还是旧客户端，看起来像「改了没生效」。
     */
    public void invalidate(String catalogId) {
        synchronized (cache) {
            Cached existing = cache.remove(catalogId);
            if (existing != null) {
                closeQuietly(existing.client(), catalogId);
                log.debug("closed the execution client of catalog {} after a config change", catalogId);
            }
        }
    }

    /** 当前缓存的客户端数（测试用：断言淘汰与失效真的关掉了）。 */
    public int cachedClients() {
        synchronized (cache) {
            return cache.size();
        }
    }

    @PreDestroy
    public void closeAll() {
        synchronized (cache) {
            cache.values().forEach(cached -> closeQuietly(cached.client(), "shutdown"));
            cache.clear();
        }
    }

    // ------------------------------------------------------------------ 构建

    /**
     * 按类型构造客户端。三种身份各自只做一件事：把「怎么证明身份」交给一个
     * {@code OAuthTokenProvider}（EKS / AKS）或交给 kubeconfig（SELF_HOSTED）。
     */
    KubernetesClient build(ResolvedCluster cluster) {
        Config config = switch (cluster.type()) {
            case SELF_HOSTED -> fromKubeconfig(cluster);
            case EKS, AKS -> fromToken(cluster);
        };
        verifyMasterUrl(config, cluster);
        return new KubernetesClientBuilder().withConfig(config).build();
    }

    /** 自建集群：kubeconfig 是唯一入口（不提供手填 apiServer + token + CA 三个框）。 */
    private Config fromKubeconfig(ResolvedCluster cluster) {
        if (cluster.kubeconfig() == null || cluster.kubeconfig().isBlank()) {
            throw ExecutionProbeException.of(ProbeReason.CREDENTIAL_UNAVAILABLE,
                    "SELF_HOSTED 的执行集群没有 kubeconfig", "在「连接」一段粘贴 kubeconfig 并选一个 context");
        }
        Config base;
        try {
            base = Config.fromKubeconfig(cluster.contextName(), cluster.kubeconfig(), null);
        } catch (RuntimeException malformed) {
            throw new ExecutionProbeException(ProbeReason.CREDENTIAL_UNAVAILABLE,
                    "kubeconfig 无法解析出 context " + cluster.contextName() + "：" + malformed.getMessage(),
                    "重新粘贴 kubeconfig 并选一个「可用」的 context", malformed);
        }
        ConfigBuilder builder = new ConfigBuilder(base)
                .withNamespace(cluster.namespace())
                .withRequestTimeout(TIMEOUT_MILLIS)
                .withConnectionTimeout(TIMEOUT_MILLIS)
                // 显式关掉回落：缺什么就报错，不去找 ~/.kube/config 或集群内 service account
                .withAutoConfigure(false);
        applyCa(builder, cluster);
        return builder.build();
    }

    /** EKS / AKS：显式造一份 Config，认证用一个现取令牌的回调。 */
    private Config fromToken(ResolvedCluster cluster) {
        ResolvedCluster.Auth auth = cluster.auth();
        ConfigBuilder builder = new ConfigBuilder()
                .withMasterUrl(cluster.apiServer())
                .withNamespace(cluster.namespace())
                .withRequestTimeout(TIMEOUT_MILLIS)
                .withConnectionTimeout(TIMEOUT_MILLIS)
                .withAutoConfigure(false)
                .withTrustCerts(false);
        applyCa(builder, cluster);
        if (cluster.type() == ClusterType.EKS) {
            builder.withOauthTokenProvider(new AwsEksTokenProvider(auth.region(), auth.clusterName(),
                    auth.accessKeyId(), auth.secretAccessKey(), auth.sessionToken()));
        } else {
            builder.withOauthTokenProvider(new AzureAksTokenProvider(
                    AzureAksTokenProvider.Authority.of(auth.authority()), auth.tenantId(), auth.clientId(),
                    auth.clientSecret(), auth.scope()));
        }
        return builder.build();
    }

    /**
     * 填 CA。空的含义是「用 JVM 信任库」，而不是「不校验」——{@code trustCerts=true}
     * 只应该出现在显式勾了「跳过证书校验」的部署里，本设计不提供这个开关。
     */
    private void applyCa(ConfigBuilder builder, ResolvedCluster cluster) {
        String pem = cluster.caCertPem();
        if (pem != null && !pem.isBlank()) {
            // fabric8 的 caCertData 要的是 base64 后的 PEM（与 kubeconfig 的 *-data 字段同形）
            builder.withCaCertData(Base64.getEncoder()
                    .encodeToString(pem.getBytes(StandardCharsets.UTF_8)));
        }
    }

    /**
     * 断言「客户端真的指向你配的那个 apiserver」。
     *
     * <p>这是 §11.2 那条「绝不回落」的最后一道闸：{@code autoConfigure} 已经被关掉，
     * 但那是一个库内行为，升级 fabric8 时可能变。真正要守的事实只有一句——
     * 提交出去的请求不能落在另一个集群上。它值得一次字符串比较。
     */
    private void verifyMasterUrl(Config config, ResolvedCluster cluster) {
        String actual = normalize(config.getMasterUrl());
        String expected = normalize(cluster.apiServer());
        if (!expected.equals(actual)) {
            throw new IllegalStateException("refusing to use an execution client whose master URL is "
                    + config.getMasterUrl() + " while the catalog is configured for " + cluster.apiServer()
                    + " (fabric8 auto-configuration fallback?)");
        }
    }

    private static String normalize(String url) {
        if (url == null) {
            return "";
        }
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private void evictOverflow() {
        while (cache.size() > MAX_CLIENTS) {
            Map.Entry<String, Cached> oldest = cache.entrySet().iterator().next();
            cache.remove(oldest.getKey());
            closeQuietly(oldest.getValue().client(), oldest.getKey());
            log.debug("evicted the least recently used execution client of catalog {}", oldest.getKey());
        }
    }

    private void closeQuietly(KubernetesClient client, String catalogId) {
        try {
            client.close();
        } catch (RuntimeException failure) {
            log.warn("cannot close the execution client of catalog {}: {}", catalogId, failure.getMessage());
        }
    }

    /** 缓存项。{@code configHash} 一起存：配置改过就必须重建，不能只看 catalogId。 */
    private record Cached(KubernetesClient client, String configHash) {
    }

    /** 供测试检查「有没有残留」：名字带出来方便断言失败时看。 */
    List<String> cachedCatalogIds() {
        synchronized (cache) {
            return new ArrayList<>(cache.keySet());
        }
    }
}
