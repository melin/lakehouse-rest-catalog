package io.github.melin.lakehouse.rest.web;

import io.github.melin.lakehouse.rest.domain.entity.SensitivityScanEntity;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.Access;
import io.github.melin.lakehouse.rest.dto.SensitivityDtos.FindingList;
import io.github.melin.lakehouse.rest.dto.SensitivityDtos.ScanAccepted;
import io.github.melin.lakehouse.rest.dto.SensitivityDtos.ScanList;
import io.github.melin.lakehouse.rest.dto.SensitivityDtos.ScanRequest;
import io.github.melin.lakehouse.rest.dto.SensitivityDtos.ScanSummary;
import io.github.melin.lakehouse.rest.service.AuthorizationService;
import io.github.melin.lakehouse.rest.service.SensitivityScanService;
import io.github.melin.lakehouse.rest.support.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 敏感数据扫描的管理端点（{@code /api/management/v1/sensitivity-scans}，
 * 设计见 {@code docs/sensitive-data-design.md} §5.6 / §9 / §10）。
 *
 * <p><b>判权是 {@code CATALOG:ALTER}，不是新增的 {@code SENSITIVE_SCAN} 权限点。</b>
 * 设计稿 §10 单列了一个权限点，理由写得很实：「采样是合法高权限读，不能挂在
 * {@code TABLE:SELECT} 上——否则任何能查表的人都能让服务端替他读明文并给出打码样本」。
 * 这条理由指向的是**不让它落在 SELECT 上**，而不是「必须是一个新词」。
 * 而 {@code CATALOG:ALTER} 表达的正是「改这个 catalog 的配置」——发起一次扫描会读
 * 这个 catalog 里所有表的元数据（P1 起还会读明文样本），与改它的执行集群同级。
 *
 * <p>不新增权限词还有一处机械约束：{@code scripts/verify-spark-sql-doc.py} 把
 * {@code allowedFor} 与 {@code docs/spark-sql-reference.md} §7.4 的 access 词表逐项比对，
 * {@code ConsoleApiTests} 也断言权限矩阵——加一个词要同时改这两处与引擎侧文档。
 *
 * <p><b>发起与读取用不同的权。</b>{@code POST} 判 {@code ALTER}（它触发一次真实读取，
 * P1 起读明文），{@code GET} 判 {@code DESCRIBE}（与读 catalog 本身同判法——
 * 「这个 catalog 最近被扫过」不是秘密，而「读了什么明文」在 finding 那边另判）。
 */
@RestController
@RequestMapping("/api/management/v1/sensitivity-scans")
@RequiredArgsConstructor
public class SensitivityScanController {

    private final SensitivityScanService scanService;
    private final AuthorizationService authorizationService;

    /**
     * {@code POST /sensitivity-scans}：发起一次扫描。
     *
     * <p><b>回 202 而不是 200</b>：即使 P0 是同步执行（跑完才返回），语义上它发起的是
     * 一个可能长跑的动作，而 P4 的 B 路线会让这个响应在作业还没提交时就返回
     * （那时 {@code status} 是 {@code PENDING}）。客户端的处置方式不该随实现分期而变
     * ——按 {@code scanId} 轮询详情。
     *
     * <p>失败的四种前置在此**没有**特殊处理，都由服务层抛：
     * {@code execution=SPARK} 且 {@code mode=METADATA_ONLY} → 400（下推没有收益，
     * 且不允许静默改走进程内）；{@code execution=SPARK} 且 catalog 不可派发 → 400
     * （fail-closed，§5.6.5）；{@code execution=SPARK} 且 {@code mode=SAMPLED} → 501
     * （下推链路属 P4，还没接）；同表已有在跑的扫描 → 409；超出 A 路线并发 → 429。
     * <b>{@code mode=SAMPLED} 本身不再是错误</b>：P1 起它真的会去读样本（走预览那条只读通道）。
     */
    @PostMapping
    public ResponseEntity<ScanAccepted> start(@RequestBody ScanRequest request) {
        String catalog = request == null ? null : request.catalog();
        requireCatalog(catalog, Access.ALTER, "start a sensitivity scan");
        ScanAccepted accepted = scanService.start(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(accepted);
    }

    /**
     * {@code GET /sensitivity-scans}：扫描历史（含 {@code execution} 与 {@code triggerType} 两列）。
     *
     * <p>不带 {@code catalog} 时判服务管理员：否则这个端点就是一条「列出所有 catalog
     * 被扫过哪些表」的路，而那些表名的存在感本身就是信息（§10 的「保护动作的存在本身
     * 也是信息」）。与 {@code OptimizationController#jobs} 同一口径。
     */
    @GetMapping
    public ScanList list(@RequestParam(name = "catalog", required = false) String catalog,
                         @RequestParam(name = "status", required = false) String status,
                         @RequestParam(name = "scope", required = false) String scope,
                         @RequestParam(name = "triggerType", required = false) String triggerType,
                         @RequestParam(name = "page", required = false) Integer page,
                         @RequestParam(name = "pageSize", required = false) Integer pageSize) {
        if (catalog != null && !catalog.isBlank()) {
            requireCatalog(catalog, Access.DESCRIBE, "list sensitivity scans");
        } else {
            authorizationService.requireServiceAdmin("list sensitivity scans across catalogs");
        }
        return scanService.list(catalog, status, scope, triggerType, page, pageSize);
    }

    /**
     * {@code GET /sensitivity-scans/{id}}：单次扫描详情与计数。
     *
     * <p><b>判权用的是扫描自己记着的 catalog，不是请求参数。</b>这不是多余的绕路：
     * 若按查询参数判，调用方可以给一个自己有权限的 catalog 名来读另一个 catalog 的扫描
     * ——{@code scanId} 是不透明的，而服务端从它取出的 catalog 才是真相源。
     * 这条与 {@code OptimizationController#job} 完全同形（那边也是先取批次再按它的
     * catalog 判权）。
     *
     * <p>顺带的后果是一条有意为之的顺序：**先查存在性、再判权**。
     * 反过来的话，一个不存在的 {@code scanId} 会回 403（因为它连判权所需的 catalog
     * 都取不到），而正确语义是 404。
     */
    @GetMapping("/{scanId}")
    public ScanSummary get(@PathVariable String scanId) {
        SensitivityScanEntity scan = scanService.require(scanId);
        requireCatalog(scan.getCatalogName(), Access.DESCRIBE, "read a sensitivity scan");
        return scanService.get(scanId);
    }

    private void requireCatalog(String catalogName, Access access, String action) {
        if (catalogName == null || catalogName.isBlank()) {
            throw ApiException.badRequest("catalog is required");
        }
        authorizationService.requireCatalog(catalogName.trim(), access, action);
    }
}
