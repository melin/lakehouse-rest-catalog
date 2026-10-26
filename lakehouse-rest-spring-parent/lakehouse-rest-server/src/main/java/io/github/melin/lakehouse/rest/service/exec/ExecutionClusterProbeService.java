package io.github.melin.lakehouse.rest.service.exec;

import io.fabric8.kubernetes.api.model.ServiceAccount;
import io.fabric8.kubernetes.api.model.authorization.v1.SelfSubjectAccessReview;
import io.fabric8.kubernetes.api.model.authorization.v1.SelfSubjectAccessReviewBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.VersionInfo;
import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.domain.entity.CatalogExecutionClusterEntity;
import io.github.melin.lakehouse.rest.domain.repo.CatalogRepository;
import io.github.melin.lakehouse.rest.dto.ExecutionClusterDtos.ProbeResult;
import io.github.melin.lakehouse.rest.dto.ExecutionClusterDtos.ProbeStep;
import io.github.melin.lakehouse.rest.service.submit.OptimizerJob;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.ClusterType;
import io.github.melin.lakehouse.rest.support.ProbeReason;
import io.github.melin.lakehouse.rest.support.ResourceType;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 执行集群的**五问探活**：凭据能不能用、身份是不是它自称的那个、权限够不够、准入会不会拦、
 * 回调地址从服务端能不能到。
 *
 * <p><b>为什么探活要分两档。</b>第 4 步是 dry-run 建 Job：它不落库，但它逐条走准入链路，
 * 在大集群的审计日志里是**可见的动作**。每 30 分钟来一次会把审计日志变成噪音。
 * 因此轻探活（调度扫描时触发，默认 30 分钟）只跑 1–3 步，全探活（用户点「验证连接」）
 * 才跑 4–5 步。「令牌还有效吗、RBAC 还在吗」这两件事恰恰是轻探活能覆盖的。
 *
 * <p><b>第 5 步必须自己声明局限。</b>回调是 <b>Pod → 服务端</b>，探活是 <b>服务端 → 地址</b>，
 * 两个方向可以完全相反：服务端在集群外能访问公网域名，而集群里的 Pod 出不去；
 * 或者 Service 名在服务端所在命名空间解析不了。因此这一步的文案写「服务端可达」，
 * 并把真正的验证命令给出来（{@link #CALLBACK_MANUAL_CHECK}）。自动做这一步要求服务端有
 * {@code create pods} 权限，而优化任务本身只需要 {@code create/delete jobs}——
 * 为了一个探活去扩大权限，是把最小权限原则反过来用。
 *
 * <p><b>失败不抛异常。</b>五步各自独立，任何一步失败都只影响它自己（后续步骤记 SKIP），
 * 最终把「三通过一警告一失败」原样交给界面。抛异常会让用户只看到第一个错，
 * 而那通常是**最不重要**的那个（比如凭据失败掩盖了权限也失败）。
 */
@Service
@RequiredArgsConstructor
public class ExecutionClusterProbeService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionClusterProbeService.class);

    /** 回调探活的超时。5 秒：够一次内网往返，也不会把探活拖成一个长请求。 */
    private static final Duration CALLBACK_TIMEOUT = Duration.ofSeconds(5);

    /**
     * 回调探活打的那个路径。
     *
     * <p>用 {@code /api/console/v1/meta} 而不是一个专门的 {@code /ping}：它是服务端**不需要令牌**
     * 就能回答的端点（登录页要靠它发现认证方式），因此「拿到 200」这件事只说明网络通，
     * 不掺杂「凭据过期」的干扰。要检的是可达性，不是鉴权。
     */
    static final String CALLBACK_PATH = "/api/console/v1/meta";

    /** 手工验证回调的命令（放在第 5 步的处置里，用户可以直接复制到集群里跑）。 */
    static final String CALLBACK_MANUAL_CHECK = """
            kubectl -n %s run cb-probe --rm -it --restart=Never --image=curlimages/curl -- \\
              curl -sS -m 5 -o /dev/null -w '%%{http_code}\\n' %s%s""";

    private final CatalogRepository catalogRepository;
    private final ExecutionClusterService clusterService;
    private final ExecutionClientFactory clientFactory;
    private final Clock clock;

    /**
     * 跑一次探活并回写结果。
     *
     * @param full {@code true} 跑 1–5 步（用户点「验证连接」）；{@code false} 只跑 1–3 步（调度扫描）
     */
    public ProbeResult probe(String catalogName, boolean full) {
        long started = System.nanoTime();
        CatalogEntity catalog = catalogRepository.findByPrefix(catalogName)
                .orElseThrow(() -> ApiException.managementNotExist(ResourceType.CATALOG, catalogName));
        CatalogExecutionClusterEntity entity = clusterService.findEntity(catalog.getId());
        if (entity == null) {
            throw ApiException.managementNotExist(ResourceType.EXECUTION_CLUSTER, catalogName);
        }
        List<ProbeStep> steps = new ArrayList<>();
        ResolvedCluster cluster = null;
        KubernetesClient client = null;
        try {
            cluster = clusterService.resolve(catalog.getId());
            steps.add(credentialStep(cluster));
            client = clientFactory.clientFor(cluster, entity.getConfigHash());
        } catch (ExecutionProbeException failure) {
            steps.add(failed("1", "凭据可用", failure.getMessage(), failure.fix()));
        } catch (RuntimeException failure) {
            ExecutionProbeException classified = ExecutionProbeException.classify(failure, "构建执行客户端");
            steps.add(failed("1", "凭据可用", classified.getMessage(), classified.fix()));
        }
        if (client == null) {
            // 第 1 步就失败：后面的步骤无从谈起。明确写 SKIP 而不是省略，
            // 让用户看到「这一屏应该有五步」
            for (String[] step : new String[][] {
                    {"2", "TLS 与身份"}, {"3", "权限"}, {"4", "准入预演"}, {"5", "回调地址"}}) {
                steps.add(new ProbeStep(step[0], step[1], "SKIP", "凭据不可用，未执行", "先修好第 1 步", null));
            }
            return finish(catalog.getId(), steps, started);
        }
        steps.add(identityStep(client, cluster));
        steps.add(rbacStep(client, cluster));
        if (full) {
            steps.add(admissionStep(client, cluster));
            steps.add(callbackStep(cluster));
        }
        return finish(catalog.getId(), steps, started);
    }

    // ------------------------------------------------------------------ 第 1 步：凭据

    /**
     * 三种身份三种验法，但都真做一次动作：
     * kubeconfig 解析 + 选定 context 的结论、EKS 真调一次 STS、AKS 真换一次 AAD 令牌。
     *
     * <p>「只解析不验证」没有意义：凭据错的时候解析照样成功，而失败会推迟到第一次提交。
     */
    private ProbeStep credentialStep(ResolvedCluster cluster) {
        if (cluster.type() == ClusterType.SELF_HOSTED) {
            KubeconfigParser.Parsed parsed = KubeconfigParser.parse(cluster.kubeconfig());
            var chosen = parsed.find(cluster.contextName());
            if (chosen == null || !KubeconfigParser.isUsable(chosen.verdict())) {
                throw ExecutionProbeException.of(ProbeReason.CREDENTIAL_UNAVAILABLE,
                        "kubeconfig 里没有可用的 context " + cluster.contextName(),
                        "重新粘贴 kubeconfig，选一个结论为「可用」的 context");
            }
            return new ProbeStep("1", "凭据可用", "PASS",
                    "kubeconfig 解析成功：context " + chosen.name() + "（" + chosen.auth() + "）",
                    null, chosen.server());
        }
        if (cluster.type() == ClusterType.EKS) {
            AwsEksTokenProvider provider = new AwsEksTokenProvider(cluster.auth().region(),
                    cluster.auth().clusterName(), cluster.auth().accessKeyId(),
                    cluster.auth().secretAccessKey(), cluster.auth().sessionToken());
            AwsEksTokenProvider.CallerIdentity identity = provider.getCallerIdentity();
            return new ProbeStep("1", "凭据可用", "PASS",
                    "sts:GetCallerIdentity 返回 " + identity.arn() + "（令牌 15 分钟内有效，每次请求现签）",
                    null, "account " + identity.accountId());
        }
        AzureAksTokenProvider provider = new AzureAksTokenProvider(
                AzureAksTokenProvider.Authority.of(cluster.auth().authority()),
                cluster.auth().tenantId(), cluster.auth().clientId(), cluster.auth().clientSecret(),
                cluster.auth().scope());
        AzureAksTokenProvider.Cached token = provider.refresh();
        return new ProbeStep("1", "凭据可用", "PASS",
                "AAD 已签发访问令牌，到期时间 " + token.expiresAt() + "（scope " + token.scope() + "）",
                null, "tenant " + cluster.auth().tenantId());
    }

    // ------------------------------------------------------------------ 第 2 步：TLS 与身份

    /**
     * {@code GET /version} 证明「TLS 通了、身份被 apiserver 认了」；
     * {@code SelfSubjectRulesReview} 把身份能做什么顺带列出来。
     *
     * <p>不用 {@code SelfSubjectReview}（1.28+ 回显用户名/组）：它比 rules review 新，
     * 老集群上会是 404，而「集群版本低」这件事本身不该让探活失败。
     */
    private ProbeStep identityStep(KubernetesClient client, ResolvedCluster cluster) {
        VersionInfo version;
        try {
            version = client.getKubernetesVersion();
        } catch (RuntimeException failure) {
            ExecutionProbeException classified = ExecutionProbeException.classify(failure, "读取集群版本");
            return failed("2", "TLS 与身份", classified.getMessage(), classified.fix(), classified.reason());
        }
        if (version == null) {
            return failed("2", "TLS 与身份", "apiserver 没有返回版本信息", "确认地址指向的是 k8s apiserver 而不是一个代理首页",
                    ProbeReason.NETWORK);
        }
        String rules;
        try {
            var review = client.authorization().v1().selfSubjectRulesReview()
                    .create(new io.fabric8.kubernetes.api.model.authorization.v1.SelfSubjectRulesReviewBuilder()
                            .withNewSpec().withNamespace(cluster.namespace()).endSpec().build());
            int count = review.getStatus() == null || review.getStatus().getResourceRules() == null
                    ? 0 : review.getStatus().getResourceRules().size();
            rules = "namespace " + cluster.namespace() + " 内有 " + count + " 条规则";
        } catch (RuntimeException failure) {
            // 受限 RBAC 下 rules review 也可能被拒——它不是必要条件，降级成警告
            rules = "rules review 不可用（" + shortMessage(failure) + "），身份可用性以第 3 步为准";
        }
        return new ProbeStep("2", "TLS 与身份", "PASS",
                "集群版本 " + version.getMajor() + "." + version.getMinor() + "；" + rules,
                null, "gitVersion " + version.getGitVersion());
    }

    // ------------------------------------------------------------------ 第 3 步：权限

    /** 四个自检：建 Job、删 Job、读 Pod、读 Pod 日志。缺前两个不能跑，缺后两个不能排障。 */
    private ProbeStep rbacStep(KubernetesClient client, ResolvedCluster cluster) {
        StringBuilder summary = new StringBuilder();
        List<String> deniedActions = new ArrayList<>();
        for (String[] check : new String[][] {
                {"create", "jobs", null}, {"delete", "jobs", null},
                {"get", "pods", null}, {"get", "pods", "log"}}) {
            boolean allowed;
            try {
                // Job 在 batch 组、Pod 在核心组（组名为空串，不是 "core"）
                String group = "jobs".equals(check[1]) ? "batch" : "";
                allowed = allowed(client, cluster.namespace(), check[0], group, check[1], check[2]);
            } catch (RuntimeException failure) {
                ExecutionProbeException classified = ExecutionProbeException.classify(failure, "权限自检");
                return failed("3", "权限", classified.getMessage(), classified.fix(), classified.reason());
            }
            String label = check[0] + " " + check[1] + (check[2] == null ? "" : "/" + check[2]);
            if (allowed) {
                summary.append(summary.isEmpty() ? "" : " · ").append(label).append(" ✓");
            } else {
                deniedActions.add(label);
            }
        }
        if (deniedActions.isEmpty()) {
            return new ProbeStep("3", "权限", "PASS", summary.toString(), null,
                    "ns " + cluster.namespace());
        }
        String denied = String.join("、", deniedActions);
        boolean blocking = deniedActions.stream().anyMatch(action -> action.contains("jobs"));
        return new ProbeStep("3", "权限", blocking ? "FAIL" : "WARN",
                "缺少权限：" + denied + (blocking ? "" : "（只影响排障时读日志）"),
                "在 namespace " + cluster.namespace() + " 上绑一个只含 jobs 的 create/get/list/delete "
                        + "与 pods、pods/log 的 get/list 的 Role（模板见 docs/catalog-execution-cluster-design.md §12.1）",
                blocking ? ProbeReason.FORBIDDEN.name() : null);
    }

    private boolean allowed(KubernetesClient client, String namespace, String verb,
                            String group, String resource, String subresource) {
        SelfSubjectAccessReview review = new SelfSubjectAccessReviewBuilder()
                .withNewSpec()
                    .withNewResourceAttributes()
                        .withNamespace(namespace)
                        .withVerb(verb)
                        .withGroup(group)
                        .withResource(resource)
                        .withSubresource(subresource)
                    .endResourceAttributes()
                .endSpec()
                .build();
        SelfSubjectAccessReview result = client.authorization().v1().selfSubjectAccessReview().create(review);
        return result.getStatus() != null && Boolean.TRUE.equals(result.getStatus().getAllowed());
    }

    // ------------------------------------------------------------------ 第 4 步：准入预演

    /**
     * dry-run 建一个 Job（{@code fieldValidation=Strict}）+ 查 serviceAccount 是否存在。
     *
     * <p>准入层能拦的都在这一步暴露：配额、PodSecurity、SA 不存在、镜像策略名字非法。
     * 有一件事它**看不出来**：镜像拉不下来（dry-run 不会真的拉）。因此当镜像是私有仓库
     * （含 {@code .} 的 registry 主机）而没配 {@code imagePullSecrets} 时，主动降级成警告——
     * 这条提示比等用户看到 {@code ErrImagePull} 有价值。
     */
    private ProbeStep admissionStep(KubernetesClient client, ResolvedCluster cluster) {
        String serviceAccount = cluster.serviceAccount();
        if (serviceAccount != null && !serviceAccount.isBlank()) {
            try {
                ServiceAccount found = client.serviceAccounts().inNamespace(cluster.namespace())
                        .withName(serviceAccount).get();
                if (found == null) {
                    return failed("4", "准入预演", "serviceAccount " + serviceAccount + " 在 namespace "
                            + cluster.namespace() + " 不存在",
                            "新建这个 SA（IRSA / Workload Identity 要挂在它上面），或把这一项留空用默认账号",
                            ProbeReason.ADMISSION);
                }
            } catch (RuntimeException failure) {
                ExecutionProbeException classified = ExecutionProbeException.classify(failure, "读取 serviceAccount");
                return failed("4", "准入预演", classified.getMessage(), classified.fix(), classified.reason());
            }
        }
        Job job = OptimizerJob.build("probe", cluster, "probe");
        try {
            client.batch().v1().jobs().inNamespace(cluster.namespace()).resource(job).dryRun().create();
        } catch (RuntimeException failure) {
            ExecutionProbeException classified = ExecutionProbeException.classify(failure, "dry-run 建 Job");
            String detail = classified.getMessage() + "（dry-run 不落库，只走准入链路）";
            return failed("4", "准入预演", detail, classified.fix(), ProbeReason.ADMISSION);
        }
        if (cluster.imagePullSecrets().isEmpty() && looksPrivate(cluster.image())) {
            return new ProbeStep("4", "准入预演", "WARN",
                    "准入链路通过（配额与 PodSecurity 都没问题），但镜像是私有仓库且没配 imagePullSecrets",
                    "补上 imagePullSecrets 的名字，或确认该命名空间已有默认拉取凭据。"
                            + "dry-run 不会真的拉镜像，这一项它看不出来——漏了的表现是 Pod 起来后 ErrImagePull",
                    null);
        }
        return new ProbeStep("4", "准入预演", "PASS", "dry-run 建 Job 通过（准入链路、配额、PodSecurity 都没问题）",
                null, "ns " + cluster.namespace() + " · image " + cluster.image());
    }

    /** 判定「这看起来是个私有仓库」：镜像的第一段含 {@code .} 或 {@code :}（registry 主机），且不是常见公共库。 */
    private static boolean looksPrivate(String image) {
        if (image == null) {
            return false;
        }
        String first = image.split("/")[0];
        if (!first.contains(".") && !first.contains(":")) {
            return false;
        }
        return !(first.contains("docker.io") || first.contains("registry.k8s.io")
                || first.contains("quay.io") || first.contains("ghcr.io"));
    }

    // ------------------------------------------------------------------ 第 5 步：回调

    /**
     * 服务端 → {@code callbackBaseUrl} 的一次 GET。
     *
     * <p>结论写死成「服务端可达」。反向（Pod → 服务端）必须另外验：命令在
     * {@link #CALLBACK_MANUAL_CHECK} 里，处置里原样给出。
     */
    private ProbeStep callbackStep(ResolvedCluster cluster) {
        String url = cluster.callbackBaseUrl() + CALLBACK_PATH;
        HttpClient http = HttpClient.newBuilder().connectTimeout(CALLBACK_TIMEOUT).build();
        try {
            HttpResponse<Void> response = http.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(CALLBACK_TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status >= 200 && status < 500) {
                return new ProbeStep("5", "回调地址", "PASS",
                        "服务端能访问 " + cluster.callbackBaseUrl() + "（HTTP " + status + "）",
                        "这一步只说了一个方向：回调是 Pod → 服务端。反向请在集群里跑一次：\n"
                                + String.format(CALLBACK_MANUAL_CHECK, cluster.namespace(),
                                cluster.callbackBaseUrl(), CALLBACK_PATH),
                        null);
            }
            return new ProbeStep("5", "回调地址", "WARN",
                    "服务端能连上 " + cluster.callbackBaseUrl() + "，但它回了 HTTP " + status,
                    "确认地址指向的是本服务端（而不是同一台机器上的另一个服务）", null);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failed("5", "回调地址", "回调探活被中断", "重试一次", ProbeReason.CALLBACK);
        } catch (Exception failure) {
            return new ProbeStep("5", "回调地址", "FAIL",
                    "从服务端连不上 " + cluster.callbackBaseUrl() + "：" + shortMessage(failure),
                    "注意这一步只说了一个方向。回调是 Pod → 服务端，这里测的是服务端 → 地址，"
                            + "两个方向可以完全相反。真正的验证在集群里跑一次：\n"
                            + String.format(CALLBACK_MANUAL_CHECK, cluster.namespace(),
                            cluster.callbackBaseUrl(), CALLBACK_PATH),
                    ProbeReason.CALLBACK.name());
        }
    }

    // ------------------------------------------------------------------ 汇总与回写

    /** 汇总五步：有 FAIL 即 FAIL（原因取第一个 FAIL），有 WARN 即 WARN，否则 PASS。 */
    private ProbeResult finish(String catalogId, List<ProbeStep> steps, long startedNanos) {
        String status = "PASS";
        ProbeStep firstFailure = null;
        for (ProbeStep step : steps) {
            if ("FAIL".equals(step.status())) {
                status = "FAIL";
                if (firstFailure == null) {
                    firstFailure = step;
                }
            } else if ("WARN".equals(step.status()) && "PASS".equals(status)) {
                status = "WARN";
            }
        }
        String reason = firstFailure == null ? null : reasonOf(firstFailure);
        String message = firstFailure != null ? firstFailure.title() + "：" + firstFailure.summary() : null;
        long elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000;
        Long probedAt = clock.millis();
        // 落库的 probe_status 只有 PASS / FAIL 两态：WARN 意味着「能跑但有隐患」，
        // 它不该阻止调度，因此记 PASS，而把隐患留在 message 里
        ProbeResult result = new ProbeResult("FAIL".equals(status) ? "FAIL" : "PASS",
                "FAIL".equals(status) ? reason : null, message, steps, elapsedMillis, probedAt);
        clusterService.recordProbe(catalogId, result);
        log.info("execution cluster probe of catalog {}: {}（{} ms）", catalogId, status, elapsedMillis);
        // 返回值里的 status 是**这一屏的结论**（含 WARN），落库的是可调度判据
        return new ProbeResult(status, result.reason(), message, steps, elapsedMillis, probedAt);
    }

    private static boolean isReasonCode(String value) {
        for (ProbeReason reason : ProbeReason.values()) {
            if (reason.name().equals(value)) {
                return true;
            }
        }
        return false;
    }

    /** 一步的 detail 有时放的是原因码（第 3 步），有时是补充信息（第 1/2 步），分开取。 */
    private static String reasonOf(ProbeStep step) {
        if (step.detail() != null && isReasonCode(step.detail())) {
            return step.detail();
        }
        return ProbeReason.UNKNOWN.name();
    }

    private static ProbeStep failed(String index, String title, String summary, String fix) {
        return failed(index, title, summary, fix, ProbeReason.UNKNOWN);
    }

    private static ProbeStep failed(String index, String title, String summary, String fix, ProbeReason reason) {
        return new ProbeStep(index, title, "FAIL", summary, fix,
                reason == null ? ProbeReason.UNKNOWN.name() : reason.name());
    }

    private static String shortMessage(Throwable failure) {
        String message = failure.getMessage();
        if (message == null) {
            return failure.getClass().getSimpleName();
        }
        int newline = message.indexOf('\n');
        return newline > 0 ? message.substring(0, newline) : message;
    }
}
