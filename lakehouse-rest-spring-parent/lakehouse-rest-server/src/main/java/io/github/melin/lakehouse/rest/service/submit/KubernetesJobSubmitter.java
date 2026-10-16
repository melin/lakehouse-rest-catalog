package io.github.melin.lakehouse.rest.service.submit;

import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.support.Json;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 用 k8s 原生 Job 跑优化任务（默认提交方式）。
 *
 * <p><b>为什么直接打 k8s REST API，而不是引一个 k8s 客户端。</b>本服务端只需要三个动作：
 * 建 Job、查 Job、删 Job。为此引入一个客户端库，代价是它的依赖树（HTTP 客户端、模型类、
 * 认证插件）与本工程已有的 Jackson 版本产生冲突的可能——而本模块的父 POM 是
 * Spring Boot，多一层版本仲裁就多一处「升级 Boot 时才发现」的坑。用
 * {@code RestClient} 直连三份报文，代码量与一个适配层相当，依赖为零。
 *
 * <p>集群内运行时的认证与信任链：令牌读自 service account 挂载文件
 * （{@code /var/run/secrets/kubernetes.io/serviceaccount/token}，每次提交时现读——
 * 该文件会被 kubelet 轮转），CA 读自同目录的 {@code ca.crt} 并用于构建信任链。
 * 两者都不配时退化为「不带令牌、用 JVM 默认信任库」，只对本地反代到 apiserver 的调试环境成立。
 *
 * <p><b>回调凭据写在容器参数里</b>（{@code --token}）。同一命名空间里能读 pod 定义的人
 * 就能读到它——它的作用域是「一个批次的表级结果上报」，不是数据面凭据，这个暴露面可以接受；
 * 要收掉它需要服务端为每批建一个 Secret 并在批次结束后删除，属于后续优化项，
 * 与 {@code docs/table-optimization.md} 的分期一致。
 */
@Component
@ConditionalOnProperty(prefix = "paimon.rest.optimization", name = "submitter",
        havingValue = "k8s", matchIfMissing = true)
@RequiredArgsConstructor
public class KubernetesJobSubmitter implements OptimizationJobSubmitter {

    private static final Logger log = LoggerFactory.getLogger(KubernetesJobSubmitter.class);

    /** 批次结束后由 k8s 自动清理资源，避免 Job 对象越积越多。 */
    private static final int TTL_SECONDS_AFTER_FINISHED = 3600;

    private final RestServerProperties properties;

    private volatile RestClient client;

    @Override
    public String name() {
        return "k8s";
    }

    @Override
    public Submitted submit(SubmissionRequest request) {
        RestServerProperties.Optimization optimization = properties.getOptimization();
        String namespace = optimization.getNamespace();
        String resource = resourceName(request.jobId());
        post("/apis/batch/v1/namespaces/" + namespace + "/jobs",
                manifest(request, namespace, resource));
        return new Submitted(namespace, resource);
    }

    @Override
    public JobState state(String namespace, String resource) {
        if (namespace == null || resource == null) {
            return JobState.UNKNOWN;
        }
        String body;
        try {
            body = get("/apis/batch/v1/namespaces/" + namespace + "/jobs/" + resource);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                return JobState.UNKNOWN;
            }
            throw new IllegalStateException("query k8s job " + resource + " failed: "
                    + exception.getStatusCode() + " " + exception.getResponseBodyAsString(), exception);
        }
        return readState(body);
    }

    @Override
    public void cancel(String namespace, String resource) {
        if (namespace == null || resource == null) {
            return;
        }
        try {
            delete("/apis/batch/v1/namespaces/" + namespace + "/jobs/" + resource);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() != 404) {
                throw new IllegalStateException("cancel k8s job " + resource + " failed: "
                        + exception.getStatusCode() + " " + exception.getResponseBodyAsString(), exception);
            }
        }
    }

    // ------------------------------------------------------------------ 报文

    /** Job 资源名。k8s 名称上限 63 字符，批次 id 是 36 字符的 UUID，加前缀仍在限内。 */
    static String resourceName(String jobId) {
        return "paimon-optimize-" + jobId;
    }

    private Map<String, Object> manifest(SubmissionRequest request, String namespace, String resource) {
        RestServerProperties.Optimization optimization = properties.getOptimization();
        List<String> args = new ArrayList<>();
        args.add("--job-id");
        args.add(request.jobId());
        args.add("--management-url");
        args.add(optimization.getManagementUrl());
        args.add("--token");
        args.add(request.callbackToken());
        // catalog 数据面令牌是可选的：没开认证的部署（本地、测试）不需要它，
        // 传一个空值会让任务以为「有令牌但为空」，因此干脆不传这个参数。
        if (optimization.getCatalogToken() != null && !optimization.getCatalogToken().isBlank()) {
            args.add("--catalog-token");
            args.add(optimization.getCatalogToken());
        }

        Map<String, Object> container = new LinkedHashMap<>();
        container.put("name", "optimizer");
        container.put("image", optimization.getImage());
        container.put("imagePullPolicy", optimization.getImagePullPolicy());
        container.put("args", args);

        Map<String, Object> podSpec = new LinkedHashMap<>();
        podSpec.put("restartPolicy", "Never");
        podSpec.put("containers", List.of(container));
        if (optimization.getServiceAccount() != null && !optimization.getServiceAccount().isBlank()) {
            podSpec.put("serviceAccountName", optimization.getServiceAccount());
        }

        Map<String, Object> labels = new LinkedHashMap<>();
        labels.put("app", "paimon-optimizer");
        labels.put("paimon-rest-job", request.jobId());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", resource);
        metadata.put("namespace", namespace);
        metadata.put("labels", labels);

        Map<String, Object> spec = new LinkedHashMap<>();
        // 失败不重试：重试是批次级语义（只重跑失败表），由服务端发起新批次完成。
        // 让 k8s 自己重试会把「哪张表失败」的事实冲掉，也会重复删一遍孤儿文件。
        spec.put("backoffLimit", 0);
        spec.put("ttlSecondsAfterFinished", TTL_SECONDS_AFTER_FINISHED);
        spec.put("template", Map.of("metadata", Map.of("labels", labels), "spec", podSpec));

        Map<String, Object> job = new LinkedHashMap<>();
        job.put("apiVersion", "batch/v1");
        job.put("kind", "Job");
        job.put("metadata", metadata);
        job.put("spec", spec);
        return job;
    }

    /**
     * 从 Job 状态里读终态。
     *
     * <p>只认 {@code status.succeeded} / {@code status.failed} 两个计数器：它们是
     * k8s 对「容器跑完了吗、跑成功了吗」的最终判定，比逐条读 {@code conditions}
     * 或容器退出码稳——一个 Job 可能因为驱逐、节点故障重排多次，计数器的语义仍然成立
     * （{@code backoffLimit=0} 时失败计数就是 1）。
     */
    JobState readState(String body) {
        Object tree = Json.read(body);
        if (!(tree instanceof Map<?, ?> job)) {
            return JobState.UNKNOWN;
        }
        Object status = job.get("status");
        if (!(status instanceof Map<?, ?> statusMap)) {
            // 刚创建、还没被控制器处理的 Job 没有 status 字段
            return JobState.RUNNING;
        }
        if (number(statusMap.get("succeeded")) > 0) {
            return JobState.SUCCEEDED;
        }
        if (number(statusMap.get("failed")) > 0) {
            return JobState.FAILED;
        }
        return JobState.RUNNING;
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    // ------------------------------------------------------------------ HTTP

    private String post(String path, Object body) {
        return client().post()
                .uri(path)
                .header("Authorization", "Bearer " + token())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Json.write(body))
                .retrieve()
                .body(String.class);
    }

    private String get(String path) {
        return client().get()
                .uri(path)
                .header("Authorization", "Bearer " + token())
                .retrieve()
                .body(String.class);
    }

    private void delete(String path) {
        client().delete()
                .uri(path)
                .header("Authorization", "Bearer " + token())
                .retrieve()
                .toBodilessEntity();
    }

    private RestClient client() {
        RestClient existing = client;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (client == null) {
                client = buildClient();
            }
            return client;
        }
    }

    private RestClient buildClient() {
        RestServerProperties.Optimization optimization = properties.getOptimization();
        HttpClient.Builder http = HttpClient.newBuilder();
        Path ca = optimization.getCaFile() == null || optimization.getCaFile().isBlank()
                ? null : Path.of(optimization.getCaFile());
        if (ca != null && Files.isReadable(ca)) {
            http.sslContext(sslContext(ca));
        } else {
            log.warn("Kubernetes CA file {} is not readable, falling back to the JVM trust store;"
                    + " this only works when the api server certificate is publicly trusted",
                    optimization.getCaFile());
        }
        return RestClient.builder()
                .baseUrl(optimization.getApiServer())
                .requestFactory(new JdkClientHttpRequestFactory(http.build()))
                .build();
    }

    /** 用集群 CA 构建信任链：apiserver 用的是集群自签 CA，JVM 默认信任库不认它。 */
    private SSLContext sslContext(Path caFile) {
        try (InputStream in = Files.newInputStream(caFile)) {
            X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(in);
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            trustStore.setCertificateEntry("kubernetes-ca", certificate);
            TrustManagerFactory managerFactory = TrustManagerFactory
                    .getInstance(TrustManagerFactory.getDefaultAlgorithm());
            managerFactory.init(trustStore);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, managerFactory.getTrustManagers(), new SecureRandom());
            return context;
        } catch (Exception exception) {
            throw new IllegalStateException("cannot build SSL context from " + caFile, exception);
        }
    }

    /** 每次现读：service account 令牌文件由 kubelet 轮转，缓存会拿到过期的那一份。 */
    private String token() {
        String file = properties.getOptimization().getTokenFile();
        if (file == null || file.isBlank()) {
            return "";
        }
        try {
            return Files.readString(Path.of(file), StandardCharsets.UTF_8).trim();
        } catch (Exception exception) {
            log.debug("cannot read kubernetes token file {}, continue anonymously", file);
            return "";
        }
    }
}
