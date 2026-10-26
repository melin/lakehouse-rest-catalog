package io.github.melin.lakehouse.rest.service.exec;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.domain.entity.CatalogExecutionClusterEntity;
import io.github.melin.lakehouse.rest.domain.repo.CatalogExecutionClusterRepository;
import io.github.melin.lakehouse.rest.domain.repo.CatalogRepository;
import io.github.melin.lakehouse.rest.dto.ExecutionClusterDtos;
import io.github.melin.lakehouse.rest.dto.ExecutionClusterDtos.AksAuth;
import io.github.melin.lakehouse.rest.dto.ExecutionClusterDtos.Context;
import io.github.melin.lakehouse.rest.dto.ExecutionClusterDtos.CopyFromRequest;
import io.github.melin.lakehouse.rest.dto.ExecutionClusterDtos.EksAuth;
import io.github.melin.lakehouse.rest.dto.ExecutionClusterDtos.ExecutionCluster;
import io.github.melin.lakehouse.rest.dto.ExecutionClusterDtos.Kubeconfig;
import io.github.melin.lakehouse.rest.dto.ExecutionClusterDtos.KubeconfigParseResult;
import io.github.melin.lakehouse.rest.dto.ExecutionClusterDtos.ProbeResult;
import io.github.melin.lakehouse.rest.dto.ExecutionClusterDtos.Summary;
import io.github.melin.lakehouse.rest.dto.ExecutionClusterDtos.UpdateExecutionClusterRequest;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.ClusterType;
import io.github.melin.lakehouse.rest.support.CredentialCipher;
import io.github.melin.lakehouse.rest.support.ExecutionClusterStatus;
import io.github.melin.lakehouse.rest.support.Json;
import io.github.melin.lakehouse.rest.support.Paging;
import io.github.melin.lakehouse.rest.support.ProbeReason;
import io.github.melin.lakehouse.rest.support.ResourceType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * catalog 执行集群配置的读写、校验与状态判定。
 *
 * <p><b>凭据只进不出。</b>写进来的 kubeconfig / secretAccessKey / clientSecret / catalogToken
 * 立刻经 {@link CredentialCipher} 密封（AES-GCM），读接口只回 {@code has*} 与可公开的标识。
 * 这里有一条与静态凭据相同的铁律：**本部署没配加密密钥时拒绝保存**，
 * 而不是「先明文存下来、以后再说」——那等于给一个安全承诺开了一道后门。
 *
 * <p><b>留空 = 保持原样。</b>密文字段（kubeconfig / 三个 secret / catalogToken）传空表示
 * 「不改」；唯一有清空开关的是可选的 {@code catalogToken}（{@code clearCatalogToken}）。
 * 这条约定不是洁癖：控制台的抽屉保存时会把整份表单发回来，没有它，「打开抽屉、什么都不改、
 * 保存」就会把凭据清成空串——静态凭据那块踩过一次一模一样的事故。
 *
 * <p><b>为什么 hash 用密文算。</b>同一份明文每次加密的 IV 不同，用明文算需要一个「解密再比」
 * 的额外路径；用密文算则要求「明文没变就别重新加密」。因此写入时先比较解密后的明文，
 * 相同就沿用原有的密文——这一步同时解决了「什么都没改却掉成待验证」的问题。
 */
@Service
@RequiredArgsConstructor
public class ExecutionClusterService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionClusterService.class);

    /** RFC 1123 label：namespace / serviceAccount / imagePullSecrets 的名字都受这个约束。 */
    private static final Pattern RFC1123 = Pattern.compile("[a-z0-9]([-a-z0-9]*[a-z0-9])?");

    private static final int MAX_SECRET_BYTES = 64 * 1024;

    private final CatalogExecutionClusterRepository repository;
    private final CatalogRepository catalogRepository;
    private final CredentialCipher cipher;
    private final ExecutionClientFactory clientFactory;
    private final Clock clock;

    // ------------------------------------------------------------------ 读

    /**
     * {@code GET .../execution-cluster}。
     *
     * @throws ApiException 404：这个 catalog 还没配（与「catalog 不存在」是两件事，
     *                      错误体里的资源类型因此是 {@code EXECUTION_CLUSTER}）
     */
    @Transactional(readOnly = true)
    public ExecutionCluster get(String catalogName) {
        CatalogEntity catalog = requireCatalog(catalogName);
        CatalogExecutionClusterEntity entity = repository.findByCatalogId(catalog.getId())
                .orElseThrow(() -> ApiException.managementNotExist(ResourceType.EXECUTION_CLUSTER, catalogName));
        return toDto(catalog.getPrefix(), entity);
    }

    /** 库里那一行（没配时为 {@code null}）。调度与策略校验都走它。 */
    @Transactional(readOnly = true)
    public CatalogExecutionClusterEntity findEntity(String catalogId) {
        return repository.findByCatalogId(catalogId).orElse(null);
    }

    /** 派生状态（见 {@link ExecutionClusterStatus}）。 */
    @Transactional(readOnly = true)
    public ExecutionClusterStatus statusOf(String catalogId) {
        return ExecutionClusterStatus.of(findEntity(catalogId));
    }

    /** catalog 列表每行那一小块。未配置时返回静态常量，不为每个未配置的 catalog 造对象。 */
    @Transactional(readOnly = true)
    public Summary summaryOf(String catalogId) {
        CatalogExecutionClusterEntity entity = findEntity(catalogId);
        return entity == null ? Summary.NOT_CONFIGURED : summary(entity);
    }

    /** 一次取一页 catalog 的执行集群摘要（避免 N+1）。 */
    @Transactional(readOnly = true)
    public Map<String, Summary> summaries(Collection<String> catalogIds) {
        if (catalogIds == null || catalogIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Summary> result = new HashMap<>();
        for (CatalogExecutionClusterEntity entity : repository.findAllByCatalogIdIn(catalogIds)) {
            result.put(entity.getCatalogId(), summary(entity));
        }
        return result;
    }

    public Summary summary(CatalogExecutionClusterEntity entity) {
        ExecutionClusterStatus status = ExecutionClusterStatus.of(entity);
        String reason = status == ExecutionClusterStatus.FAILING ? entity.getProbeReason() : null;
        return new Summary(true, entity.isEnabled(), status.name(), reason,
                entity.getClusterType(), entity.getNamespace(), entity.getApiServer(),
                entity.getProbedAt(), entity.getProbeMessage());
    }

    /**
     * 解开一份配置，得到能用来建客户端/提交的形状。
     *
     * @throws ExecutionProbeException 没配、停用、或密文解不开
     */
    @Transactional(readOnly = true)
    public ResolvedCluster resolve(String catalogId) {
        CatalogExecutionClusterEntity entity = findEntity(catalogId);
        if (entity == null) {
            throw ExecutionProbeException.of(ProbeReason.CREDENTIAL_UNAVAILABLE,
                    "catalog 没有配置执行集群", "在 catalog 列表点「执行集群」完成配置");
        }
        ClusterType type = ClusterType.of(entity.getClusterType());
        return new ResolvedCluster(type, entity.getCatalogId(), entity.getContextName(), entity.getApiServer(),
                entity.getCaCertPem(), revealQuietly(entity.getKubeconfigSealed()), auth(entity),
                revealQuietly(entity.getCatalogTokenSealed()), entity.getNamespace(), entity.getImage(),
                entity.getImagePullPolicy(), splitSecrets(entity.getImagePullSecrets()),
                entity.getServiceAccount(), entity.getCallbackBaseUrl(),
                entity.getMaxTablesPerJob(), entity.getMaxConcurrentJobs(), entity.getJobTimeoutSeconds());
    }

    /**
     * 取该 catalog 的 fabric8 客户端（按 {@code configHash} 缓存，配置改了自动换）。
     *
     * <p>把「查实体 → 解配置 → 拿客户端」这三步收在一处，是因为提交器与探活都要它，
     * 而三处各写一遍就会出现「某一处忘了传 {@code configHash}」这种安静的错误：
     * 表现是改完配置后那一处还在用旧客户端。
     *
     * @throws ExecutionProbeException 未配置、密文解不开、或客户端构建失败（带结论与处置）
     */
    @Transactional(readOnly = true)
    public KubernetesClient clientFor(String catalogId) {
        CatalogExecutionClusterEntity entity = findEntity(catalogId);
        if (entity == null) {
            throw ExecutionProbeException.of(ProbeReason.CREDENTIAL_UNAVAILABLE,
                    "catalog 没有配置执行集群", "在 catalog 列表点「执行集群」完成配置");
        }
        return clientFactory.clientFor(resolve(catalogId), entity.getConfigHash());
    }

    /**
     * 该 catalog 的批次超时（秒）。没配置时回落到 {@link #DEFAULT_JOB_TIMEOUT_SECONDS}。
     *
     * <p>为什么要有回落而不是抛：这个方法在「给批次定 deadline」与「判它超时」两处被调用，
     * 而 catalog 可能在建完批次之后被删（执行集群行随之级联删除）。此时批次仍是历史事实，
     * 用一个默认值把它收尾，比让它永远停在 {@code RUNNING} 好。
     */
    @Transactional(readOnly = true)
    public int jobTimeoutSeconds(String catalogId) {
        CatalogExecutionClusterEntity entity = findEntity(catalogId);
        int configured = entity == null ? 0 : entity.getJobTimeoutSeconds();
        return configured > 0 ? configured : DEFAULT_JOB_TIMEOUT_SECONDS;
    }

    /**
     * 该 catalog 同时允许几个批次在跑。没配置时回落到 1——不是为了「能跑」，
     * 而是为了让调用方的算式有个有限值（不可派发这件事本身由 {@link #dispatchable} 判）。
     */
    @Transactional(readOnly = true)
    public int maxConcurrentJobs(String catalogId) {
        CatalogExecutionClusterEntity entity = findEntity(catalogId);
        int configured = entity == null ? 0 : entity.getMaxConcurrentJobs();
        return configured > 0 ? configured : 1;
    }

    /** 单批最多几张表。没配置时回落到实体列的默认值（20）。 */
    @Transactional(readOnly = true)
    public int maxTablesPerJob(String catalogId) {
        CatalogExecutionClusterEntity entity = findEntity(catalogId);
        int configured = entity == null ? 0 : entity.getMaxTablesPerJob();
        return configured > 0 ? configured : 20;
    }

    /** 与实体列的默认值同一个数（{@code CatalogExecutionClusterEntity#jobTimeoutSeconds}）。 */
    public static final int DEFAULT_JOB_TIMEOUT_SECONDS = 7200;

    /** 当前这份配置能不能提交（未配置 / 停用 / 异常都不行）。 */
    @Transactional(readOnly = true)
    public boolean dispatchable(String catalogId) {
        return ExecutionClusterStatus.of(findEntity(catalogId)).dispatchable();
    }

    /**
     * 保存优化策略 / 立即执行前的**关卡**（fail-closed）。
     *
     * <p>允许给一个没有执行集群的 catalog 保存策略，等于允许「配了但永远不会跑」——
     * 这类静默失效最难发现。停用（{@code enabled=false}）是例外：那是刻意的关闭。
     */
    @Transactional(readOnly = true)
    public void assertDispatchable(String catalogName) {
        CatalogEntity catalog = requireCatalog(catalogName);
        CatalogExecutionClusterEntity entity = findEntity(catalog.getId());
        ExecutionClusterStatus status = ExecutionClusterStatus.of(entity);
        if (status.dispatchable()) {
            return;
        }
        String detail = switch (status) {
            case NOT_CONFIGURED -> "未配置执行集群";
            case DISABLED -> "执行集群已停用（enabled=false）";
            case FAILING -> "执行集群最近一次验证失败"
                    + (entity == null || entity.getProbeMessage() == null ? "" : "：" + entity.getProbeMessage());
            default -> status.label();
        };
        throw ApiException.badRequest("catalog " + catalogName + " 的" + detail
                + "，优化任务不会提交；请先在 catalog 列表点「执行集群」");
    }

    // ------------------------------------------------------------------ 写

    /**
     * {@code PUT .../execution-cluster}：整份替换（含 {@code enabled}）。
     *
     * <p>乐观锁：更新时必须带 {@code entityVersion}，不匹配回 409 并带上当前值。
     * 与 catalog 的更新同一条约定——两块配置是两份独立的并发域。
     */
    @Transactional
    public ExecutionCluster update(String catalogName, UpdateExecutionClusterRequest request, String principal) {
        CatalogEntity catalog = requireCatalog(catalogName);
        if (request == null) {
            throw ApiException.badRequest("request body must not be empty");
        }
        if (!cipher.available()) {
            // fail-closed：没有密钥就不保存，而不是明文落库
            throw ApiException.badRequest("this deployment cannot store execution cluster credentials:"
                    + " lakehouse.rest.storage.credential-secret-key is not configured"
                    + " (generate one with `openssl rand -base64 32`)");
        }
        CatalogExecutionClusterEntity entity = repository.findByCatalogId(catalog.getId()).orElse(null);
        boolean create = entity == null;
        if (create) {
            entity = new CatalogExecutionClusterEntity();
            entity.setId(Paging.newId());
            entity.setCatalogId(catalog.getId());
            entity.markCreated(principal, clock.millis());
            entity.setEntityVersion(0);
        } else {
            requireVersion(request.entityVersion(), entity);
        }
        apply(entity, request);
        entity.touch(principal, clock.millis());
        entity.setEntityVersion(entity.getEntityVersion() + 1);
        CatalogExecutionClusterEntity saved = repository.save(entity);
        // 配置改了：立刻丢掉旧客户端，别等下一次提交才发现 hash 变了
        clientFactory.invalidate(catalog.getId());
        log.info("catalog {} execution cluster saved: type={} ns={} apiServer={} status={}",
                catalogName, saved.getClusterType(), saved.getNamespace(), saved.getApiServer(),
                ExecutionClusterStatus.of(saved));
        return toDto(catalogName, saved);
    }

    /** {@code DELETE .../execution-cluster}：连同凭据一起丢掉（不可恢复）。 */
    @Transactional
    public void delete(String catalogName) {
        CatalogEntity catalog = requireCatalog(catalogName);
        CatalogExecutionClusterEntity entity = repository.findByCatalogId(catalog.getId()).orElse(null);
        if (entity == null) {
            throw ApiException.managementNotExist(ResourceType.EXECUTION_CLUSTER, catalogName);
        }
        repository.delete(entity);
        clientFactory.invalidate(catalog.getId());
        log.info("catalog {} execution cluster deleted", catalogName);
    }

    /**
     * {@code POST .../copy-from}：从另一个 catalog 复制**连接与认证**。
     *
     * <p>复制在服务端做（不是客户端把另一份凭据读出来再写回去）：客户端手里不该出现
     * 任何密文对应的明文。复制范围与设计稿 §4.3 一致——集群类型与整段认证、{@code apiServer}、
     * {@code caCertPem}、运行位置（namespace / image / pullPolicy / pullSecrets / serviceAccount）、
     * {@code catalogToken}。
     *
     * <p><b>不复制三样，每样各有理由：</b>
     * <ul>
     *   <li>{@code callbackBaseUrl}——方向性字段，由请求显式提供（见 {@link CopyFromRequest}）；</li>
     *   <li>批次策略（表数上限 / 并发数 / 超时）——它跟着**目标集群的算力**走，
     *       同一台集群给两个 catalog 分配不同份额是正常用法；</li>
     *   <li>探活结论——它验的是源那份配置的通道，复制来的是别人的验证结果。</li>
     * </ul>
     *
     * <p>复制完状态是「待验证」而不是「正常」：{@code configHash} 重算了（{@code callbackBaseUrl}
     * 本来就不同），而 {@code probeConfigHash} 被清空。
     */
    @Transactional
    public ExecutionCluster copyFrom(String catalogName, CopyFromRequest request, String principal) {
        if (request == null || request.sourceCatalog() == null || request.sourceCatalog().isBlank()) {
            throw ApiException.badRequest("sourceCatalog is required");
        }
        if (request.sourceCatalog().trim().equals(catalogName)) {
            throw ApiException.badRequest("cannot copy the execution cluster from the catalog itself");
        }
        // 先校验这一项：它是唯一不从源带过来的连接参数，缺了就不该写任何东西
        String callbackBaseUrl = requireCallback(request.callbackBaseUrl());
        CatalogEntity target = requireCatalog(catalogName);
        CatalogEntity source = requireCatalog(request.sourceCatalog().trim());
        CatalogExecutionClusterEntity sourceEntity = repository.findByCatalogId(source.getId())
                .orElseThrow(() -> ApiException.badRequest("catalog " + request.sourceCatalog()
                        + " 还没有配置执行集群，没有可复制的内容"));
        if (!cipher.available()) {
            throw ApiException.badRequest("this deployment cannot store execution cluster credentials:"
                    + " lakehouse.rest.storage.credential-secret-key is not configured");
        }

        CatalogExecutionClusterEntity entity = repository.findByCatalogId(target.getId()).orElse(null);
        boolean create = entity == null;
        if (create) {
            entity = new CatalogExecutionClusterEntity();
            entity.setId(Paging.newId());
            entity.setCatalogId(target.getId());
            entity.markCreated(principal, clock.millis());
            entity.setEntityVersion(0);
            // 新建一份配置＝要用它，因此默认启用；已存在的配置保持它自己的开关状态
            entity.setEnabled(true);
        }
        entity.setClusterType(sourceEntity.getClusterType());
        entity.setContextName(sourceEntity.getContextName());
        entity.setApiServer(sourceEntity.getApiServer());
        entity.setCaCertPem(sourceEntity.getCaCertPem());
        entity.setKubeconfigSealed(sourceEntity.getKubeconfigSealed());
        entity.setAuthConfigSealed(sourceEntity.getAuthConfigSealed());
        entity.setCatalogTokenSealed(sourceEntity.getCatalogTokenSealed());
        entity.setNamespace(sourceEntity.getNamespace());
        entity.setImage(sourceEntity.getImage());
        entity.setImagePullPolicy(sourceEntity.getImagePullPolicy());
        entity.setImagePullSecrets(sourceEntity.getImagePullSecrets());
        entity.setServiceAccount(sourceEntity.getServiceAccount());
        entity.setCallbackBaseUrl(callbackBaseUrl);
        // 探活结论不复制：它验的是源 catalog 那条通道
        entity.setProbeStatus(null);
        entity.setProbeReason(null);
        entity.setProbeMessage(null);
        entity.setProbeConfigHash(null);
        entity.setProbedAt(null);
        entity.setConfigHash(configHash(entity));
        entity.touch(principal, clock.millis());
        entity.setEntityVersion(entity.getEntityVersion() + 1);
        CatalogExecutionClusterEntity saved = repository.save(entity);
        clientFactory.invalidate(target.getId());
        log.info("catalog {} execution cluster copied from {} (status becomes UNVERIFIED)",
                catalogName, request.sourceCatalog());
        return toDto(catalogName, saved);
    }

    /**
     * {@code POST .../kubeconfig/parse}：只解析、不落库。
     *
     * <p>为什么挂在 catalog 下而不是做成无状态的全局端点：它要判权（改执行集群的权限），
     * 而权限是 per-catalog 的。路径里的 catalog 名因此是「判权的主体」，不是「被读的对象」。
     */
    public KubeconfigParseResult parse(String catalogName, String kubeconfig) {
        requireCatalog(catalogName);
        KubeconfigParser.Parsed parsed = KubeconfigParser.parse(kubeconfig);
        return new KubeconfigParseResult(parsed.contexts(), parsed.currentContext(), parsed.warnings());
    }

    /** 探活结果回写（只记结论与原因码，不记原始响应体）。 */
    @Transactional
    public void recordProbe(String catalogId, ProbeResult result) {
        CatalogExecutionClusterEntity entity = repository.findByCatalogId(catalogId).orElse(null);
        if (entity == null) {
            return;
        }
        entity.setProbeStatus(result.status());
        entity.setProbeReason(result.reason());
        entity.setProbeMessage(truncate(result.message(), 500));
        entity.setProbeConfigHash(entity.getConfigHash());
        entity.setProbedAt(result.probedAt());
        repository.save(entity);
    }

    // ------------------------------------------------------------------ 校验与落值

    /**
     * 把请求落到实体上。**所有校验都在这里**——服务层是唯一入口，
     * 控制器只负责判权，因此「能不能存」只有一处判断。
     */
    private void apply(CatalogExecutionClusterEntity entity, UpdateExecutionClusterRequest request) {
        ClusterType type = ClusterType.parse(request.clusterType())
                .orElseThrow(() -> ApiException.badRequest("clusterType must be one of SELF_HOSTED / EKS / AKS, got "
                        + request.clusterType()));
        String previousType = entity.getClusterType();

        entity.setClusterType(type.wireName());
        // 未传 enabled 时保持原样：PUT 是整份替换，但「没提这一项」不该把一个停用的集群
        // 悄悄打开——那会让「我明明停用了」变成一条查不出来的行为
        if (request.enabled() != null) {
            entity.setEnabled(request.enabled());
        }

        switch (type) {
            case SELF_HOSTED -> applySelfHosted(entity, request, type.wireName().equals(previousType));
            case EKS -> applyEks(entity, request, type.wireName().equals(previousType));
            case AKS -> applyAks(entity, request, type.wireName().equals(previousType));
        }

        // 集群 CA：null = 不改；空串 = 清空（与密文字段的「留空 = 不改」不同——它不是秘密，
        // 用户要能把它删掉）
        if (request.caCertPem() != null) {
            entity.setCaCertPem(request.caCertPem().isBlank() ? null : request.caCertPem().trim());
        }

        entity.setNamespace(requireName(request.namespace(), "namespace", 63));
        entity.setImage(requireImage(request.image()));
        entity.setImagePullPolicy(imagePullPolicy(request.imagePullPolicy()));
        if (request.imagePullSecrets() != null) {
            entity.setImagePullSecrets(joinSecrets(request.imagePullSecrets()));
        }
        if (request.serviceAccount() != null) {
            String serviceAccount = request.serviceAccount().trim();
            entity.setServiceAccount(serviceAccount.isEmpty() ? null
                    : requireName(serviceAccount, "serviceAccount", 63));
        }
        entity.setCallbackBaseUrl(requireCallback(request.callbackBaseUrl()));

        if (request.maxTablesPerJob() != null) {
            entity.setMaxTablesPerJob(range(request.maxTablesPerJob(), "maxTablesPerJob", 1, 500));
        }
        if (request.maxConcurrentJobs() != null) {
            entity.setMaxConcurrentJobs(range(request.maxConcurrentJobs(), "maxConcurrentJobs", 1, 50));
        }
        if (request.jobTimeoutSeconds() != null) {
            entity.setJobTimeoutSeconds(range(request.jobTimeoutSeconds(), "jobTimeoutSeconds", 300, 86400));
        }

        applyCatalogToken(entity, request);
        entity.setConfigHash(configHash(entity));
    }

    /** 自建：kubeconfig 是唯一入口，apiServer 由解析结果投影出来（不接受手填）。 */
    private void applySelfHosted(CatalogExecutionClusterEntity entity, UpdateExecutionClusterRequest request,
                                 boolean sameType) {
        String existing = sameType ? revealQuietly(entity.getKubeconfigSealed()) : null;
        String text = request.kubeconfig() == null || request.kubeconfig().isBlank()
                ? existing : request.kubeconfig().trim();
        if (text == null || text.isBlank()) {
            throw ApiException.badRequest("clusterType=SELF_HOSTED 时必须提供 kubeconfig");
        }
        KubeconfigParser.Parsed parsed = KubeconfigParser.parse(text);
        String contextName = request.contextName() == null || request.contextName().isBlank()
                ? parsed.currentContext() : request.contextName().trim();
        if (contextName == null || contextName.isBlank()) {
            throw ApiException.badRequest("kubeconfig 里没有 current-context，请显式指定 contextName");
        }
        Context chosen = parsed.find(contextName);
        if (chosen == null) {
            throw ApiException.badRequest("kubeconfig 里没有 context " + contextName
                    + "；可用的是 " + parsed.contexts().stream().map(Context::name).toList());
        }
        if (!KubeconfigParser.isUsable(chosen.verdict())) {
            throw ApiException.badRequest("context " + contextName + " 不能用：" + chosen.note());
        }
        if (chosen.server() == null || chosen.server().isBlank()) {
            throw ApiException.badRequest("context " + contextName + " 指向的 cluster 没有 server 地址");
        }
        entity.setContextName(contextName);
        entity.setApiServer(chosen.server().trim());
        // 明文没变就别重新加密：密文一变 configHash 就变，列表会掉成「待验证」
        entity.setKubeconfigSealed(text.equals(existing) ? entity.getKubeconfigSealed() : cipher.seal(text));
        entity.setAuthConfigSealed(null);
    }

    /** EKS：STS 预签名令牌。集群名大小写敏感——这里只 trim，不做大小写归一。 */
    private void applyEks(CatalogExecutionClusterEntity entity, UpdateExecutionClusterRequest request,
                          boolean sameType) {
        ResolvedCluster.Auth existing = sameType ? auth(entity) : ResolvedCluster.Auth.EMPTY;
        String region = keepOr(request.region(), existing.region());
        String clusterName = keepOr(request.clusterName(), existing.clusterName());
        String accessKeyId = keepOr(request.accessKeyId(), existing.accessKeyId());
        String secretAccessKey = keepOr(request.secretAccessKey(), existing.secretAccessKey());
        String sessionToken = request.sessionToken() == null ? existing.sessionToken() : blankToNull(request.sessionToken());
        requireText(region, "region");
        requireText(clusterName, "clusterName");
        requireText(accessKeyId, "accessKeyId");
        requireText(secretAccessKey, "secretAccessKey");
        entity.setApiServer(requireApiServer(request.apiServer(), existingApiServer(entity, sameType)));
        entity.setContextName(null);
        entity.setKubeconfigSealed(null);
        ResolvedCluster.Auth merged = new ResolvedCluster.Auth(region.trim(), clusterName.trim(),
                accessKeyId.trim(), secretAccessKey, sessionToken, null, null, null, null, null);
        entity.setAuthConfigSealed(sealAuth(merged, sameType ? entity.getAuthConfigSealed() : null, existing));
    }

    /** AKS：AAD 客户端凭据。scope 可填，默认 AKS 自己的 audience。 */
    private void applyAks(CatalogExecutionClusterEntity entity, UpdateExecutionClusterRequest request,
                          boolean sameType) {
        ResolvedCluster.Auth existing = sameType ? auth(entity) : ResolvedCluster.Auth.EMPTY;
        String authority = request.authority() == null || request.authority().isBlank()
                ? (existing.authority() == null ? "PUBLIC" : existing.authority()) : request.authority().trim().toUpperCase();
        String tenantId = keepOr(request.tenantId(), existing.tenantId());
        String clientId = keepOr(request.clientId(), existing.clientId());
        String clientSecret = keepOr(request.clientSecret(), existing.clientSecret());
        String scope = request.scope() == null || request.scope().isBlank() ? existing.scope() : request.scope().trim();
        requireText(tenantId, "tenantId");
        requireText(clientId, "clientId");
        requireText(clientSecret, "clientSecret");
        if (!"PUBLIC".equals(authority) && !"CHINA".equals(authority)) {
            throw ApiException.badRequest("authority must be PUBLIC or CHINA, got " + authority);
        }
        if (scope != null && !scope.endsWith("/.default")) {
            // 这条错误很值钱：写错 scope 的表现是 401 Invalid audience，看起来像「应用没授权」
            throw ApiException.badRequest("scope 必须以 /.default 结尾（AKS 的 audience 是 "
                    + AzureAksTokenProvider.AKS_AUDIENCE + "）");
        }
        entity.setApiServer(requireApiServer(request.apiServer(), existingApiServer(entity, sameType)));
        entity.setContextName(null);
        entity.setKubeconfigSealed(null);
        ResolvedCluster.Auth merged = new ResolvedCluster.Auth(null, null, null, null, null,
                authority, tenantId.trim(), clientId.trim(), clientSecret, scope);
        entity.setAuthConfigSealed(sealAuth(merged, sameType ? entity.getAuthConfigSealed() : null, existing));
    }

    /**
     * 重新密封认证 JSON，但**明文没变时沿用旧密文**。
     *
     * <p>没有这一步，「打开抽屉 → 保存」会让每个 catalog 都从「正常」掉成「待验证」，
     * 那条提示很快就没人看了。比较的是解密后的 JSON 字符串：字段顺序由 record 的
     * 序列化顺序固定，因此它是稳定的。
     */
    private String sealAuth(ResolvedCluster.Auth merged, String previousSealed, ResolvedCluster.Auth previous) {
        String json = Json.write(merged);
        if (previousSealed != null && previous != null && Json.write(previous).equals(json)) {
            return previousSealed;
        }
        return cipher.seal(json);
    }

    private void applyCatalogToken(CatalogExecutionClusterEntity entity, UpdateExecutionClusterRequest request) {
        if (Boolean.TRUE.equals(request.clearCatalogToken())) {
            entity.setCatalogTokenSealed(null);
            return;
        }
        if (request.catalogToken() == null || request.catalogToken().isBlank()) {
            return;
        }
        requireBytes(request.catalogToken(), "catalogToken");
        entity.setCatalogTokenSealed(cipher.seal(request.catalogToken().trim()));
    }

    // ------------------------------------------------------------------ 校验工具

    private static int requireVersion(Integer requestVersion, CatalogExecutionClusterEntity entity) {
        if (requestVersion != null && requestVersion != entity.getEntityVersion()) {
            throw ApiException.entityVersionMismatch(ResourceType.EXECUTION_CLUSTER, entity.getCatalogId(),
                    requestVersion, entity.getEntityVersion());
        }
        return entity.getEntityVersion();
    }

    private static int range(Integer value, String field, int min, int max) {
        if (value < min || value > max) {
            throw ApiException.badRequest(field + " must be between " + min + " and " + max + ", got " + value);
        }
        return value;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(field + " is required for this cluster type");
        }
    }

    private static void requireBytes(String value, String field) {
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_SECRET_BYTES) {
            throw ApiException.badRequest(field + " 超过 64 KiB 上限");
        }
    }

    private static String requireName(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(field + " is required");
        }
        String trimmed = value.trim();
        if (trimmed.length() > max || !RFC1123.matcher(trimmed).matches()) {
            throw ApiException.badRequest(field + " 必须是 " + max + " 个字符以内的 RFC 1123 名字（小写字母、数字、-），got "
                    + trimmed);
        }
        return trimmed;
    }

    /** 镜像必须带 tag 或 digest：只写仓库名会解析成 latest，而 latest + IfNotPresent 是「改了不生效」。 */
    private static String requireImage(String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("image is required（集群可以有多个，镜像只可能有一个对的那个）");
        }
        String image = value.trim();
        if (!image.contains(":") && !image.contains("@")) {
            throw ApiException.badRequest("image 必须带 tag 或 digest（含 ':' 或 '@'），got " + image);
        }
        return image;
    }

    private static String imagePullPolicy(String value) {
        if (value == null || value.isBlank()) {
            return "IfNotPresent";
        }
        String policy = value.trim();
        if (!List.of("IfNotPresent", "Always", "Never").contains(policy)) {
            throw ApiException.badRequest("imagePullPolicy must be IfNotPresent / Always / Never, got " + policy);
        }
        return policy;
    }

    /** 回调地址：**Pod 能 curl 通**的那个地址。结尾的斜杠吞掉，避免拼出 {@code //api/...}。 */
    private static String requireCallback(String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("callbackBaseUrl is required（填集群里的 Pod 能访问到的服务端地址）");
        }
        String url = value.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw ApiException.badRequest("callbackBaseUrl must start with http:// or https://, got " + url);
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private static String requireApiServer(String value, String fallback) {
        String server = value == null || value.isBlank() ? fallback : value.trim();
        if (server == null || server.isBlank()) {
            throw ApiException.badRequest("apiServer is required for EKS / AKS");
        }
        if (!server.startsWith("https://")) {
            throw ApiException.badRequest("apiServer must start with https://, got " + server);
        }
        return server;
    }

    private static String joinSecrets(String raw) {
        List<String> names = new ArrayList<>();
        for (String part : raw.split(",")) {
            String name = part.trim();
            if (name.isEmpty()) {
                continue;
            }
            if (!RFC1123.matcher(name).matches()) {
                throw ApiException.badRequest("imagePullSecrets 里的名字不是 RFC 1123：" + name);
            }
            names.add(name);
        }
        if (names.size() > 8) {
            throw ApiException.badRequest("imagePullSecrets 最多 8 个");
        }
        return names.isEmpty() ? null : String.join(",", names);
    }

    private static List<String> splitSecrets(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (String part : raw.split(",")) {
            if (!part.isBlank()) {
                names.add(part.trim());
            }
        }
        return names;
    }

    private static String keepOr(String incoming, String existing) {
        return incoming == null || incoming.isBlank() ? existing : incoming.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String existingApiServer(CatalogExecutionClusterEntity entity, boolean sameType) {
        return sameType ? entity.getApiServer() : null;
    }

    // ------------------------------------------------------------------ 密文与哈希

    /** 解不开时返回 null 而不是抛：读路径要能显示「密文不可读」，而不是整个 500。 */
    private String revealQuietly(String sealed) {
        if (sealed == null || sealed.isBlank()) {
            return null;
        }
        try {
            return cipher.reveal(sealed);
        } catch (RuntimeException failure) {
            log.warn("cannot decrypt an execution cluster credential: {}", failure.getMessage());
            return null;
        }
    }

    /** 解密出 EKS / AKS 的认证参数；解不开时返回空（后续校验会给出 400 「凭据不可用」）。 */
    private ResolvedCluster.Auth auth(CatalogExecutionClusterEntity entity) {
        return parseAuth(revealQuietly(entity.getAuthConfigSealed()));
    }

    private ResolvedCluster.Auth parseAuth(String json) {
        if (json == null) {
            return ResolvedCluster.Auth.EMPTY;
        }
        try {
            ResolvedCluster.Auth parsed = Json.read(json, ResolvedCluster.Auth.class);
            return parsed == null ? ResolvedCluster.Auth.EMPTY : parsed;
        } catch (RuntimeException malformed) {
            log.warn("cannot parse an execution cluster auth config: {}", malformed.getMessage());
            return ResolvedCluster.Auth.EMPTY;
        }
    }

    /**
     * 「参与连接与运行的全部字段」的 SHA-256。
     *
     * <p>不含批次策略（改上限不该要求重新验证连接）、不含探活时间戳、不含 {@code enabled}
     * （停用再启用不该把「正常」变成「待验证」）。每段后面跟一个 0x1f 分隔符，
     * 避免「(a=ab,b=c) 与 (a=a,b=bc) 撞同一个串」这种拼接歧义。
     */
    static String configHash(CatalogExecutionClusterEntity entity) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : new String[] {
                    entity.getClusterType(), entity.getContextName(), entity.getApiServer(),
                    entity.getCaCertPem(), entity.getKubeconfigSealed(), entity.getAuthConfigSealed(),
                    entity.getNamespace(), entity.getImage(), entity.getImagePullPolicy(),
                    entity.getImagePullSecrets(), entity.getServiceAccount(), entity.getCallbackBaseUrl(),
                    entity.getCatalogTokenSealed()}) {
                digest.update((part == null ? "<null>" : part).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0x1f);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    // ------------------------------------------------------------------ 组装 DTO

    private ExecutionCluster toDto(String catalogName, CatalogExecutionClusterEntity entity) {
        ClusterType type = ClusterType.of(entity.getClusterType());
        ExecutionClusterStatus status = ExecutionClusterStatus.of(entity);
        String kubeconfigText = revealQuietly(entity.getKubeconfigSealed());
        String authJson = revealQuietly(entity.getAuthConfigSealed());
        ResolvedCluster.Auth authConfig = parseAuth(authJson);
        Kubeconfig kubeconfig = kubeconfigView(entity, kubeconfigText);
        // 「密文解不开」要能与「没配」区分开：前者是运维问题（换了密钥），后者是用户还没填
        boolean readable = (entity.getKubeconfigSealed() == null || kubeconfigText != null)
                && (entity.getAuthConfigSealed() == null || authJson != null);
        return new ExecutionCluster(
                catalogName,
                true,
                entity.isEnabled(),
                status.name(),
                status == ExecutionClusterStatus.FAILING ? entity.getProbeReason() : null,
                type.wireName(),
                entity.getContextName(),
                entity.getApiServer(),
                entity.getCaCertPem(),
                kubeconfig,
                type == ClusterType.EKS
                        ? new EksAuth(authConfig.region(), authConfig.clusterName(), authConfig.accessKeyId(),
                        notBlank(authConfig.secretAccessKey()), notBlank(authConfig.sessionToken()))
                        : null,
                type == ClusterType.AKS
                        ? new AksAuth(authConfig.authority() == null ? "PUBLIC" : authConfig.authority(),
                        authConfig.tenantId(), authConfig.clientId(), notBlank(authConfig.clientSecret()),
                        authConfig.scope())
                        : null,
                entity.getNamespace(),
                entity.getImage(),
                entity.getImagePullPolicy(),
                splitSecrets(entity.getImagePullSecrets()),
                entity.getServiceAccount(),
                entity.getCallbackBaseUrl(),
                entity.getCatalogTokenSealed() != null,
                entity.getMaxTablesPerJob(),
                entity.getMaxConcurrentJobs(),
                entity.getJobTimeoutSeconds(),
                readable,
                entity.getProbeStatus(),
                entity.getProbeReason(),
                entity.getProbeMessage(),
                entity.getProbedAt(),
                entity.getEntityVersion(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    /** kubeconfig 只回摘要与 context 列表（context 列表由服务端重新解析得到，不含密钥材料）。 */
    private Kubeconfig kubeconfigView(CatalogExecutionClusterEntity entity, String text) {
        if (entity.getKubeconfigSealed() == null) {
            return new Kubeconfig("NONE", 0, List.of(), List.of());
        }
        if (text == null) {
            return new Kubeconfig("UNREADABLE", 0, List.of(), List.of(
                    "kubeconfig 无法解密：本部署的 credential-secret-key 与保存时不一致，请重新粘贴"));
        }
        try {
            KubeconfigParser.Parsed parsed = KubeconfigParser.parse(text);
            return new Kubeconfig("CONFIGURED", parsed.contexts().size(), parsed.contexts(), parsed.warnings());
        } catch (RuntimeException malformed) {
            return new Kubeconfig("UNREADABLE", 0, List.of(), List.of("已保存的 kubeconfig 无法解析："
                    + malformed.getMessage()));
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private CatalogEntity requireCatalog(String catalogName) {
        return catalogRepository.findByPrefix(catalogName)
                .orElseThrow(() -> ApiException.managementNotExist(ResourceType.CATALOG, catalogName));
    }

}
