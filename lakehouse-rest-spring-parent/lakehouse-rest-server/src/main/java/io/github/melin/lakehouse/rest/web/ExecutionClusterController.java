package io.github.melin.lakehouse.rest.web;

import io.github.melin.lakehouse.rest.config.RequestContext;
import io.github.melin.lakehouse.rest.dto.ExecutionClusterDtos.ExecutionCluster;
import io.github.melin.lakehouse.rest.dto.ExecutionClusterDtos.KubeconfigParseResult;
import io.github.melin.lakehouse.rest.dto.ExecutionClusterDtos.ParseKubeconfigRequest;
import io.github.melin.lakehouse.rest.dto.ExecutionClusterDtos.ProbeResult;
import io.github.melin.lakehouse.rest.dto.ExecutionClusterDtos.UpdateExecutionClusterRequest;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.Access;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.ResourceType;
import io.github.melin.lakehouse.rest.service.AuthorizationService;
import io.github.melin.lakehouse.rest.service.exec.ExecutionClusterProbeService;
import io.github.melin.lakehouse.rest.service.exec.ExecutionClusterService;
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
 * catalog 的执行集群配置（优化任务提交到哪个 k8s、用什么身份）——设计见
 * {@code docs/catalog-execution-cluster-design.md} §8。
 *
 * <p><b>路径挂在 catalog 下，不引入新的一级资源。</b>「这个 catalog 的执行集群」是 catalog 的
 * 一个属性，不是独立可枚举的实体：没有「列出全部执行集群」的需求（列表每行已带摘要），
 * 也没有「不属于任何 catalog 的执行集群」。挂在这里同时省掉了控制台的一个菜单项。
 *
 * <p><b>三种权限，一条规则：</b>读用 {@code CATALOG_ALL:DESCRIBE}（与读 catalog 本身同判法——
 * 官方 Access/Resource 表里 {@code CATALOG} 没有读权限）；改、探活、解析 kubeconfig 用
 * {@code CATALOG:ALTER}；删除用 {@code CATALOG:DROP}。
 *
 * <p><b>探活为什么也算 ALTER。</b>它能触发一次真实的 {@code sts:GetCallerIdentity} 调用，
 * 全探活还会在目标集群里 dry-run 建一个 Job（不落库，但**在审计日志里可见**）。
 * 让只有读权限的人反复触发这类动作，等于把「最小权限」反过来用。
 */
@RestController
@RequestMapping("/api/management/v1/catalogs/{catalogName}/execution-cluster")
@RequiredArgsConstructor
public class ExecutionClusterController {

    private final ExecutionClusterService clusterService;
    private final ExecutionClusterProbeService probeService;
    private final AuthorizationService authorizationService;

    /**
     * {@code GET .../execution-cluster}：读配置。
     *
     * <p>没配时回 <b>404</b>（错误体的资源类型是 {@code EXECUTION_CLUSTER}），而不是回一个
     * {@code configured=false} 的 200：对调用方来说「这个 catalog 没有执行集群」与
     * 「这个 catalog 不存在」是两件要分别处理的事，前者要引导去配置，后者要引导去建 catalog。
     */
    @GetMapping
    public ExecutionCluster get(@PathVariable String catalogName) {
        authorizationService.require(catalogName, ResourceType.CATALOG_ALL, List.of(), null,
                Access.DESCRIBE, "read the execution cluster");
        return clusterService.get(catalogName);
    }

    /**
     * {@code PUT .../execution-cluster}：整份替换。
     *
     * <p>密文字段留空 = 保持原样（见 {@link UpdateExecutionClusterRequest}）；
     * 必须带 {@code entityVersion}（新建时除外），不匹配回 409。
     */
    @PutMapping
    public ExecutionCluster update(@PathVariable String catalogName,
                                  @RequestBody UpdateExecutionClusterRequest request) {
        authorizationService.requireCatalog(catalogName, Access.ALTER, "update the execution cluster");
        return clusterService.update(catalogName, request, RequestContext.principal());
    }

    /**
     * {@code DELETE .../execution-cluster}：连同凭据一起丢掉。
     *
     * <p>REST 侧不做二次确认：确认由控制台的弹窗承担（{@code confirm-destructive} 是
     * Spark SQL 会话变量，只约束 SQL 通道，这里没有 SQL 通道）。判 {@code DROP}——
     * 删掉配置等于删掉一份凭据，成本与删 catalog 同级。
     */
    @DeleteMapping
    public ResponseEntity<Void> delete(@PathVariable String catalogName) {
        authorizationService.requireCatalog(catalogName, Access.DROP, "delete the execution cluster");
        clusterService.delete(catalogName);
        return ResponseEntity.noContent().build();
    }

    /**
     * {@code POST .../kubeconfig/parse}：只解析、不落库，供表单即时反馈。
     *
     * <p>每个 context 给一行结论（{@code OK} / {@code WARN} / {@code UNSUPPORTED} /
     * {@code UNAVAILABLE}）。{@code exec:} 与 {@code auth-provider:} 是硬拒：
     * 前者要求服务端执行 kubeconfig 里写的本地命令，那是一条 RCE 边界
     * （见 {@code KubeconfigParser} 的注释）。
     */
    @PostMapping("/kubeconfig/parse")
    public KubeconfigParseResult parse(@PathVariable String catalogName,
                                       @RequestBody ParseKubeconfigRequest request) {
        authorizationService.requireCatalog(catalogName, Access.ALTER, "parse a kubeconfig");
        return clusterService.parse(catalogName, request == null ? null : request.kubeconfig());
    }

    /**
     * {@code POST .../probe}：五问探活。
     *
     * <p>{@code ?full=false} 只跑第 1–3 步（凭据、身份、权限），不 dry-run 建 Job——
     * 调度扫描用的就是这一档。界面上的「验证连接」走默认的全档。
     *
     * <p>**失败不回 4xx/5xx**：五步各自独立，结果里带着「三通过一警告一失败」的完整结论，
     * 抛异常会让用户只看到第一个错，而那通常是最不重要的那个。
     */
    @PostMapping("/probe")
    public ProbeResult probe(@PathVariable String catalogName,
                             @RequestParam(name = "full", defaultValue = "true") boolean full) {
        authorizationService.requireCatalog(catalogName, Access.ALTER, "probe the execution cluster");
        return probeService.probe(catalogName, full);
    }
}
