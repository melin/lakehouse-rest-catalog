package io.github.melin.lakehouse.rest.web;

import io.github.melin.lakehouse.rest.domain.entity.SensitivityScanEntity;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.Access;
import io.github.melin.lakehouse.rest.dto.SensitivityDtos.FindingList;
import io.github.melin.lakehouse.rest.dto.SensitivityDtos.ScanAccepted;
import io.github.melin.lakehouse.rest.dto.SensitivityDtos.ScanList;
import io.github.melin.lakehouse.rest.dto.SensitivityDtos.ScanRequest;
import io.github.melin.lakehouse.rest.dto.SensitivityDtos.ScanSummary;
import io.github.melin.lakehouse.rest.scan.ScanProtocol.ScanItemReport;
import io.github.melin.lakehouse.rest.scan.ScanProtocol.ScanJobSpec;
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
import org.springframework.web.bind.annotation.RequestHeader;
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

    /**
     * 扫描回调令牌的请求头。与 {@code X-Optimization-Token} 分开：两个令牌的作用域不同，
     * 共用一个头会让「拿优化批次令牌去调扫描回调」看起来像一次普通的认证失败。
     */
    public static final String TOKEN_HEADER = "X-Sensitivity-Scan-Token";

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
 * （fail-closed，§5.6.5）；{@code execution=SPARK} 且表数超过该 catalog 的
 * {@code maxScanTablesPerJob} → 400（一个扫描 = 一个 Job，切不开）；同表已有在跑的扫描 → 409；
 * 超出 A 路线并发 → 429。<b>{@code mode=SAMPLED} 本身不再是错误</b>：P1 起它真的会去读样本
 * （走预览那条只读通道），P4 起 {@code execution=SPARK} 也真的会提交成 k8s 作业。
 *
 * <p><b>两个作业回调端点（{@code /submission} 与 {@code /items}）不在 RBAC 之内。</b>
 * 调用方是 k8s 里的扫描作业，它没有主体身份，凭据是 {@code X-Sensitivity-Scan-Token}
 * （作用域 = 这一次扫描，库里只存 SHA-256）。把作业令牌当成主体去查授权链，只会得到
 * 「谁都不认识」——与优化批次的两个回调端点同一口径。
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
     *
     * <p>{@code scheduleName}（P4）：按调度项名筛历史。它不参与判权——可见性仍由
     * {@code catalog} 那一条决定，否则「传一个我有权限的调度项名」就能绕过 catalog 判权。
     */
    @GetMapping
    public ScanList list(@RequestParam(name = "catalog", required = false) String catalog,
                         @RequestParam(name = "status", required = false) String status,
                         @RequestParam(name = "scope", required = false) String scope,
                         @RequestParam(name = "triggerType", required = false) String triggerType,
                         @RequestParam(name = "scheduleName", required = false) String scheduleName,
                         @RequestParam(name = "page", required = false) Integer page,
                         @RequestParam(name = "pageSize", required = false) Integer pageSize) {
        if (catalog != null && !catalog.isBlank()) {
            requireCatalog(catalog, Access.DESCRIBE, "list sensitivity scans");
        } else {
            authorizationService.requireServiceAdmin("list sensitivity scans across catalogs");
        }
        return scanService.list(catalog, status, scope, triggerType, scheduleName, page, pageSize);
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

    // ------------------------------------------------------------------ B 路线：作业协定

    /**
     * {@code GET /sensitivity-scans/{scanId}/submission}：扫描作业拉取执行清单（分页）。
     *
     * <p>令牌走 {@code X-Sensitivity-Scan-Token} 而不是 {@code Authorization}：
     * 后者是**主体身份**的位置，而这个令牌只对这一次扫描有效、不代表任何主体
     * （与 {@code OptimizationController#TOKEN_HEADER} 同一条理由）。
     *
     * <p>分页由作业驱动（{@code offset} / {@code limit}）：它逐表跑，跑完一页再拉下一页，
     * 内存里永远只有一页的列清单。
     */
    @GetMapping("/{scanId}/submission")
    public ScanJobSpec submission(@PathVariable String scanId,
                                  @RequestHeader(name = TOKEN_HEADER, required = false) String token,
                                  @RequestParam(name = "offset", required = false) Integer offset,
                                  @RequestParam(name = "limit", required = false) Integer limit) {
        scanService.requireCallbackToken(scanId, token);
        return scanService.submission(scanId, offset, limit);
    }

    /**
     * {@code POST /sensitivity-scans/{scanId}/items}：作业逐表回报。
     *
     * <p><b>回 204 而不是回计数。</b>作业不关心服务端累加到了多少——它只关心「这一条收到没」。
     * 回一个读模型会诱使作业去判断「数字对不对」，而那是服务端的职责。
     */
    @PostMapping("/{scanId}/items")
    public ResponseEntity<Void> reportItem(@PathVariable String scanId,
                                           @RequestHeader(name = TOKEN_HEADER, required = false) String token,
                                           @RequestBody ScanItemReport report) {
        scanService.requireCallbackToken(scanId, token);
        scanService.reportItem(scanId, report);
        return ResponseEntity.noContent().build();
    }

    /**
     * {@code POST /sensitivity-scans/{scanId}:cancel}：取消扫描（B 路线同时停作业）。
     *
     * <p>判权按扫描自己记着的 catalog（{@code ALTER}），与 {@link #get} 同一条理由：
     * 调用方不能靠「声明一个有权限的 catalog」来取消别的 catalog 的扫描。
     */
    @PostMapping("/{scanId}:cancel")
    public ScanSummary cancel(@PathVariable String scanId) {
        SensitivityScanEntity scan = scanService.require(scanId);
        requireCatalog(scan.getCatalogName(), Access.ALTER, "cancel a sensitivity scan");
        return scanService.cancel(scanId);
    }

    private void requireCatalog(String catalogName, Access access, String action) {
        if (catalogName == null || catalogName.isBlank()) {
            throw ApiException.badRequest("catalog is required");
        }
        authorizationService.requireCatalog(catalogName.trim(), access, action);
    }
}
