package io.github.melin.lakehouse.rest.web;

import io.github.melin.lakehouse.rest.config.RequestContext;
import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.domain.entity.OptimizationJobEntity;
import io.github.melin.lakehouse.rest.domain.repo.CatalogRepository;
import io.github.melin.lakehouse.rest.dto.OptimizationDtos;
import io.github.melin.lakehouse.rest.dto.OptimizationDtos.CatalogSettings;
import io.github.melin.lakehouse.rest.dto.OptimizationDtos.ItemReportRequest;
import io.github.melin.lakehouse.rest.dto.OptimizationDtos.Job;
import io.github.melin.lakehouse.rest.dto.OptimizationDtos.Jobs;
import io.github.melin.lakehouse.rest.dto.OptimizationDtos.RunOptimizationRequest;
import io.github.melin.lakehouse.rest.dto.OptimizationDtos.Submission;
import io.github.melin.lakehouse.rest.dto.OptimizationDtos.TableHistory;
import io.github.melin.lakehouse.rest.dto.OptimizationDtos.TablePolicy;
import io.github.melin.lakehouse.rest.dto.OptimizationDtos.UpdateCatalogSettingsRequest;
import io.github.melin.lakehouse.rest.dto.OptimizationDtos.UpdateTablePolicyRequest;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.Access;
import io.github.melin.lakehouse.rest.service.AuthorizationService;
import io.github.melin.lakehouse.rest.service.OptimizationJobService;
import io.github.melin.lakehouse.rest.service.TableOptimizationService;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.OptimizationOptions;
import io.github.melin.lakehouse.rest.support.OptimizationOptions.Action;
import io.github.melin.lakehouse.rest.support.ResourceType;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 表存储优化的管理端点（{@code /api/management/v1/optimization}）。
 *
 * <p>挂管理 API 而不是 catalog API（{@code /v1/{prefix}}）：优化策略与批次是**服务端自己
 * 的资源**，不是 Paimon 元数据的一部分，引擎不需要也不该看到它们。这也意味着
 * 新增这些端点不需要登记 {@code CatalogAccessRules}——那张表管的是 catalog API。
 *
 * <p>权限沿用 catalog 级属性权限：策略与 catalog 默认值都是「catalog 配置」，
 * 读用 {@code CATALOG_READ_PROPERTIES}，写与立即执行用 {@code CATALOG_WRITE_PROPERTIES}。
 * 授权关闭时这些检查全部放行，与其余管理端点一致。
 *
 * <p>两个例外是优化任务回调用的端点（{@code /jobs/{id}/submission} 与
 * {@code /jobs/{id}/items/{itemId}}）：调用方是 k8s 里的任务，它没有主体身份，
 * 拿的是**批次级回调令牌**（{@code X-Optimization-Token}）。令牌只对这一个批次有效，
 * 因此不参与 RBAC 判定——把批次令牌当成主体去查授权链，只会得到「谁都不认识」的结果。
 */
@RestController
@RequestMapping("/api/management/v1/optimization")
@RequiredArgsConstructor
public class OptimizationController {

    /** 回调令牌的请求头。用自定义头而不是 {@code Authorization}：后者是主体身份的位置。 */
    public static final String TOKEN_HEADER = "X-Optimization-Token";

    private static final int MAX_JOB_LIST = 200;

    private final TableOptimizationService optimizationService;
    private final OptimizationJobService jobService;
    private final AuthorizationService authorizationService;
    private final CatalogRepository catalogRepository;

    // ------------------------------------------------------------------ catalog 默认设置

    /** {@code GET /optimization/catalogs/{catalogName}/settings}。 */
    @GetMapping("/catalogs/{catalogName}/settings")
    public CatalogSettings settings(@PathVariable String catalogName) {
        requireCatalog(catalogName, Access.DESCRIBE, "read optimization settings");
        return optimizationService.settings(catalogName);
    }

    /** {@code PUT /optimization/catalogs/{catalogName}/settings}。 */
    @PutMapping("/catalogs/{catalogName}/settings")
    public CatalogSettings updateSettings(@PathVariable String catalogName,
                                          @RequestBody UpdateCatalogSettingsRequest request) {
        requireCatalog(catalogName, Access.ALTER, "update optimization settings");
        return optimizationService.updateSettings(catalogName, request, RequestContext.principal());
    }

    // ------------------------------------------------------------------ 表策略

    /** {@code GET /optimization/tables/{catalog}/{database}/{table}/policy}。 */
    @GetMapping("/tables/{catalogName}/{databaseName}/{tableName}/policy")
    public TablePolicy policy(@PathVariable String catalogName, @PathVariable String databaseName,
                              @PathVariable String tableName) {
        requireCatalog(catalogName, Access.DESCRIBE, "read the optimization policy");
        return optimizationService.policy(catalogName, databaseName, tableName);
    }

    /** {@code PUT /optimization/tables/{catalog}/{database}/{table}/policy}。 */
    @PutMapping("/tables/{catalogName}/{databaseName}/{tableName}/policy")
    public TablePolicy updatePolicy(@PathVariable String catalogName, @PathVariable String databaseName,
                                    @PathVariable String tableName,
                                    @RequestBody UpdateTablePolicyRequest request) {
        requireCatalog(catalogName, Access.ALTER, "update the optimization policy");
        return optimizationService.updatePolicy(catalogName, databaseName, tableName, request,
                RequestContext.principal());
    }

    /** {@code POST /optimization/tables/{catalog}/{database}/{table}/run}：立即优化。 */
    @PostMapping("/tables/{catalogName}/{databaseName}/{tableName}/run")
    public ResponseEntity<Job> run(@PathVariable String catalogName, @PathVariable String databaseName,
                                   @PathVariable String tableName,
                                   @RequestBody(required = false) RunOptimizationRequest request) {
        requireCatalog(catalogName, Access.ALTER, "run table optimization");
        List<String> actions = request == null ? null : request.actions();
        Job created = optimizationService.run(catalogName, databaseName, tableName, actions,
                RequestContext.principal());
        // 重新按统一规则取一次详情，而不是直接用 run() 的返回值：DTO 里带不带回调令牌
        // 由 revealToken() 一处决定，让 run() 自己再判断一遍等于有两条规则。
        return ResponseEntity.status(HttpStatus.CREATED).body(jobService.detail(created.id(), revealToken()));
    }

    /**
     * {@code GET /optimization/tables/{catalog}/{database}/{table}/items}：这张表的优化历史。
     *
     * <p>挂在表上而不是让控制台从 {@code /jobs} 里筛：批次列表不带表级明细，
     * 前端要拼出「这张表最近几次跑成什么样」得逐个批次拉详情。
     */
    @GetMapping("/tables/{catalogName}/{databaseName}/{tableName}/items")
    public TableHistory history(@PathVariable String catalogName, @PathVariable String databaseName,
                                @PathVariable String tableName) {
        requireCatalog(catalogName, Access.DESCRIBE, "read the table optimization history");
        return optimizationService.history(catalogName, databaseName, tableName);
    }

    // ------------------------------------------------------------------ 批次

    /**
     * {@code GET /optimization/jobs}：批次列表。
     *
     * <p>授权开启时只返回调用者能看到权限的 catalog 的批次——与 catalog 列表同一条规则，
     * 「看不到 catalog」不该通过优化任务列表泄露出来。
     */
    @GetMapping("/jobs")
    public Jobs jobs(@RequestParam(name = "catalog", required = false) String catalogName,
                     @RequestParam(name = "status", required = false) String status,
                     @RequestParam(name = "action", required = false) String action,
                     @RequestParam(name = "limit", required = false) Integer limit) {
        Action actionFilter = null;
        if (action != null && !action.isBlank()) {
            actionFilter = Action.parse(action);
            if (actionFilter == null) {
                throw ApiException.badRequest("Unknown optimization action: " + action
                        + ", supported: " + OptimizationOptions.actionNames());
            }
        }
        int size = limit == null ? MAX_JOB_LIST : limit;
        if (catalogName != null && !catalogName.isBlank()) {
            requireCatalog(catalogName, Access.DESCRIBE, "list optimization jobs");
            return new Jobs(jobService.list(catalogName, status, actionFilter, size));
        }
        List<Job> visible = new ArrayList<>();
        boolean filter = authorizationService.enabled()
                && !authorizationService.isServiceAdmin(RequestContext.principal());
        for (Job job : jobService.list(null, status, actionFilter, MAX_JOB_LIST)) {
            if (!filter || hasCatalogAccess(job.catalog())) {
                visible.add(job);
                if (visible.size() >= size) {
                    break;
                }
            }
        }
        return new Jobs(visible);
    }

    /** {@code GET /optimization/jobs/{jobId}}：批次详情（含表级明细）。 */
    @GetMapping("/jobs/{jobId}")
    public Job job(@PathVariable String jobId) {
        OptimizationJobEntity job = jobService.require(jobId);
        requireCatalog(catalogNameOf(job.getCatalogId()), Access.DESCRIBE,
                "read the optimization job");
        return jobService.detail(jobId, revealToken());
    }

    /** {@code POST /optimization/jobs/{jobId}/retry}：只重跑失败的表。 */
    @PostMapping("/jobs/{jobId}/retry")
    public ResponseEntity<Job> retry(@PathVariable String jobId) {
        OptimizationJobEntity job = jobService.require(jobId);
        requireCatalog(catalogNameOf(job.getCatalogId()), Access.ALTER,
                "retry the optimization job");
        OptimizationJobEntity created = jobService.retry(jobId, RequestContext.principal());
        return ResponseEntity.status(HttpStatus.CREATED).body(jobService.detail(created.getId(), revealToken()));
    }

    /** {@code POST /optimization/jobs/{jobId}/cancel}：取消批次并删掉执行侧资源。 */
    @PostMapping("/jobs/{jobId}/cancel")
    public Job cancel(@PathVariable String jobId) {
        OptimizationJobEntity job = jobService.require(jobId);
        requireCatalog(catalogNameOf(job.getCatalogId()), Access.ALTER,
                "cancel the optimization job");
        jobService.cancel(jobId, RequestContext.principal());
        return jobService.detail(jobId, revealToken());
    }

    // ------------------------------------------------------------------ 优化任务协定

    /** {@code GET /optimization/jobs/{jobId}/submission}：执行清单（优化任务启动时拉）。 */
    @GetMapping("/jobs/{jobId}/submission")
    public Submission submission(@PathVariable String jobId,
                                 @RequestHeader(name = TOKEN_HEADER, required = false) String token) {
        jobService.requireCallbackToken(jobId, token);
        return jobService.submission(jobId);
    }

    /** {@code POST /optimization/jobs/{jobId}/items/{itemId}}：逐表上报结果。 */
    @PostMapping("/jobs/{jobId}/items/{itemId}")
    public ResponseEntity<Void> reportItem(@PathVariable String jobId, @PathVariable String itemId,
                                           @RequestHeader(name = TOKEN_HEADER, required = false) String token,
                                           @RequestBody ItemReportRequest request) {
        jobService.requireCallbackToken(jobId, token);
        jobService.report(jobId, itemId, request);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 批次详情里要不要带上回调令牌。
     *
     * <p>授权关闭时服务端的数据面本来就是全开的（任何调用方都能读表、写数据），
     * 此时对匿名调用方隐藏一个「只对本批次有效」的令牌没有意义，反而挡住本地调试与
     * 端到端测试。授权开启时只对服务管理员可见——运维要靠它手工重跑一个批次。
     */
    private boolean revealToken() {
        return !authorizationService.enabled()
                || authorizationService.isServiceAdmin(RequestContext.principal());
    }

    private boolean hasCatalogAccess(String catalogName) {
        return catalogName != null
                && authorizationService.hasAnyGrant(RequestContext.principal(), catalogName);
    }

    private void requireCatalog(String catalogName, Access access, String action) {
        authorizationService.requireCatalog(catalogName, access, action);
    }

    private String catalogNameOf(String catalogId) {
        return catalogRepository.findById(catalogId)
                .map(CatalogEntity::getPrefix)
                .orElseThrow(() -> ApiException.managementNotExist(ResourceType.CATALOG, catalogId));
    }
}
