package io.github.melin.lakehouse.rest.web;

import io.github.melin.lakehouse.rest.config.RequestContext;
import io.github.melin.lakehouse.rest.dto.DataPolicyDtos;
import io.github.melin.lakehouse.rest.dto.DataPolicyDtos.PolicyDetail;
import io.github.melin.lakehouse.rest.dto.DataPolicyDtos.PolicyList;
import io.github.melin.lakehouse.rest.dto.DataPolicyDtos.PolicyUpsertRequest;
import io.github.melin.lakehouse.rest.dto.DataPolicyDtos.ResolveResult;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.Access;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.ResourceType;
import io.github.melin.lakehouse.rest.dto.PreviewDtos;
import io.github.melin.lakehouse.rest.preview.PreviewTarget;
import io.github.melin.lakehouse.rest.service.AuthorizationService;
import io.github.melin.lakehouse.rest.service.DataPolicyService;
import io.github.melin.lakehouse.rest.service.TablePreviewService;
import io.github.melin.lakehouse.rest.support.ApiException;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 行过滤 / 列脱敏策略的管理端点（{@code /api/management/v1/policies}，设计见
 * {@code docs/row-filter-column-mask-design.md} §5.4 / §8.1）。
 *
 * <p><b>挂管理 API 而不是 catalog API</b>：策略是本服务自己的资源，不是 Paimon /
 * Iceberg 元数据的一部分，引擎不需要也不该从协议面看到它——与存储优化、血缘同一选址
 * 理由（见 {@code OptimizationController} 的类注释）。
 *
 * <p><b>权限映射（§8.1，不新增权限词）</b>：
 * <ul>
 *   <li>读 / 写 / 删策略：<b>{@code TABLE:ALTER}</b>——策略是表的属性（虽然不存进
 *       表属性），改它等于改表的可见性；豁免名单也在这里看（它需要同权）；</li>
 *   <li>解析下发（{@code /resolve}）：<b>{@code TABLE:SELECT}</b>，逐表判权——策略内容
 *       （谓词字面量、列清单）本身就是敏感信息，不能变成无权者的读口；</li>
 *   <li>总览列表：给了 catalog 过滤按 {@code CATALOG:DESCRIBE}，全 catalog 的总览
 *       要服务管理员。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/management/v1/policies")
@RequiredArgsConstructor
public class DataPolicyController {

    private final DataPolicyService policyService;
    private final AuthorizationService authorizationService;
    private final TablePreviewService previewService;

    /** {@code GET /policies}：总览列表（{@code catalog} 过滤 + {@code refTable} 反查 + 分页）。 */
    @GetMapping
    public PolicyList list(@RequestParam(value = "catalog", required = false) String catalog,
                           @RequestParam(value = "refTable", required = false) String refTable,
                           @RequestParam(value = "page", required = false) Integer page,
                           @RequestParam(value = "pageSize", required = false) Integer pageSize) {
        if (catalog != null && !catalog.isBlank()) {
            authorizationService.requireCatalog(catalog, Access.DESCRIBE, "list data policies");
        } else {
            authorizationService.requireServiceAdmin("list data policies across catalogs");
        }
        return policyService.list(catalog, refTable, page, pageSize);
    }

    /**
     * {@code GET /policies/resolve?catalog=&tables=db.t1,db.t2}：引擎的策略解析通道。
     *
     * <p>响应按调用者个性化（{@code $principal} 已折成字面量、豁免已判定）。
     * 对没策略的表回 {@code status=NONE}——「没策略」是正常响应；
     * 服务端错误走 5xx，由引擎侧 fail-closed 拒读（§5.4 的失败模型）。
     */
    @GetMapping("/resolve")
    public ResolveResult resolve(@RequestParam("catalog") String catalog,
                                 @RequestParam("tables") String tables) {
        List<String> names = tables == null || tables.isBlank()
                ? List.of() : Arrays.stream(tables.split(",")).map(String::trim).toList();
        return policyService.resolve(catalog, names);
    }

    /** {@code GET /policies/tables/{catalog}/{database}/{table}}：一张表的策略详情。 */
    @GetMapping("/tables/{catalogName}/{databaseName}/{tableName}")
    public ResponseEntity<PolicyDetail> detail(@PathVariable String catalogName,
                                               @PathVariable String databaseName,
                                               @PathVariable String tableName) {
        requireTableAccess(catalogName, databaseName, tableName, Access.ALTER, "read the data policy");
        PolicyDetail detail = policyService.detail(catalogName, databaseName, tableName);
        // 没配过策略不是错误（控制台的空态卡要用它区分「没策略」和「没权限」）
        return detail == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(detail);
    }

    /** {@code PUT /policies/tables/{catalog}/{database}/{table}}：创建或整份更新。 */
    @PutMapping("/tables/{catalogName}/{databaseName}/{tableName}")
    public PolicyDetail upsert(@PathVariable String catalogName,
                               @PathVariable String databaseName,
                               @PathVariable String tableName,
                               @RequestBody PolicyUpsertRequest request) {
        requireTableAccess(catalogName, databaseName, tableName, Access.ALTER, "write the data policy");
        return policyService.upsert(catalogName, databaseName, tableName, request,
                RequestContext.principal());
    }

    /** {@code DELETE /policies/tables/{catalog}/{database}/{table}}：物理删除整条策略。 */
    @DeleteMapping("/tables/{catalogName}/{databaseName}/{tableName}")
    public void delete(@PathVariable String catalogName,
                       @PathVariable String databaseName,
                       @PathVariable String tableName) {
        requireTableAccess(catalogName, databaseName, tableName, Access.ALTER, "drop the data policy");
        policyService.delete(catalogName, databaseName, tableName, RequestContext.principal());
    }

    /**
     * {@code POST /policies/tables/{catalog}/{database}/{table}/dry-run}：主体视角试算
     * （控制台 mockup 画面 4）。按请求体里的 {@code principal}（不是当前调用者）判定
     * 策略命中状态，命中时用同一套预览求值链读一页数据返回——行被过滤、敏感列被 mask。
     *
     * <p><b>判权是 {@code TABLE:ALTER} 而不是 SELECT</b>：试算回答的是「策略对他生效成
     * 什么样」，这本身就是策略内容（谓词、列清单、豁免效果），与 GET 详情同一敏感级；
     * 放宽到 SELECT 等于任何能读表的人都能看策略文本。被模拟的主体**不需要存在**——
     * {@code $principal} 折的是名字字面量，角色名单查不到就折成空（行过滤 fail-closed），
     * 这正是「新建主体还没挂角色」场景要试算的原因。
     *
     * <p>只读、不落库：唯一副作用是预览自身的 {@code TABLE_PREVIEWED} 审计（调用者是
     * 操作者本人，不是被模拟的主体）。
     */
    @PostMapping("/tables/{catalogName}/{databaseName}/{tableName}/dry-run")
    public DataPolicyDtos.DryRunResult dryRun(@PathVariable String catalogName,
                                              @PathVariable String databaseName,
                                              @PathVariable String tableName,
                                              @RequestBody DataPolicyDtos.DryRunRequest request) {
        requireTableAccess(catalogName, databaseName, tableName, Access.ALTER,
                "dry-run the data policy as another principal");
        String principal = request.principal() == null ? null : request.principal().trim();
        if (principal == null || principal.isBlank()) {
            throw ApiException.badRequest("principal must not be empty");
        }
        DataPolicyService.PrincipalPolicy policy =
                policyService.policyForPrincipal(catalogName, databaseName, tableName, principal);
        PreviewTarget target = previewService.resolve(catalogName, databaseName, tableName);
        PreviewDtos.PreviewResponse preview =
                previewService.preview(target, request.preview(), policy.policy());
        return new DataPolicyDtos.DryRunResult(tableName, policy.status(), policy.version(),
                policy.rowFilterSql(), principal, preview);
    }

    private void requireTableAccess(String catalogName, String databaseName, String tableName,
                                    Access access, String action) {
        authorizationService.require(catalogName, ResourceType.TABLE, List.of(databaseName),
                tableName, access, action);
    }
}
