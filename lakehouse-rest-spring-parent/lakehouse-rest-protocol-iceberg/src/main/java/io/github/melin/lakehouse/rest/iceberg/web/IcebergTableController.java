package io.github.melin.lakehouse.rest.iceberg.web;

import io.github.melin.lakehouse.rest.iceberg.dto.IcebergDtos;
import io.github.melin.lakehouse.rest.iceberg.service.IcebergTableService;
import io.github.melin.lakehouse.rest.support.NamespacePaths;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Iceberg 的表端点（规格 §5.1「直接映射 table ↔ table」+「新的小端点」两类）。
 *
 * <p><b>状态码逐个照规格写。</b>这份规格对同一个动作给的状态码与 Paimon 那套并不一致，
 * 而客户端会据此判断结果，因此这里没有「统一成 200」的余地：
 *
 * <table border="1">
 *   <caption>这些端点的成功状态码</caption>
 *   <tr><th>端点</th><th>成功</th></tr>
 *   <tr><td>{@code GET/POST .../tables}、{@code GET/POST .../tables/{t}}、
 *       {@code POST .../register}、{@code POST .../unregister}、
 *       {@code GET .../credentials}</td><td>200（带响应体）</td></tr>
 *   <tr><td>{@code HEAD .../tables/{t}}</td><td>204</td></tr>
 *   <tr><td>{@code DELETE .../tables/{t}}、{@code POST .../metrics}、
 *       {@code POST /v1/{prefix}/transactions/commit}</td><td>204</td></tr>
 * </table>
 *
 * <p><b>{@code X-Iceberg-Access-Delegation} 是一个「要不要把凭据给我」的请求头</b>
 * （规格的参数名就叫 {@code data-access}）。它在 {@code load} / {@code create} /
 * {@code register} 三条上有定义——建表与登记之后客户端立刻要写数据，因此那两条也带它。
 * 本服务端的行为是**不问不给**（见 {@code IcebergCredentialService}）。
 *
 * <p><b>{@code POST /v1/{prefix}/tables/rename} 不在这里。</b>它与 Paimon 规格逐字同路径、
 * 请求体不同，两个面各写一个映射会让 Spring 以 {@code Ambiguous mapping} 启动失败，
 * 因此它上移到了 core 的 {@code CatalogRenameController}，
 * 由那里按 catalog 格式分派回 {@code IcebergProtocol}。
 */
@RestController
@RequestMapping("/v1/{prefix}")
@RequiredArgsConstructor
public class IcebergTableController {

    private final IcebergTableService tableService;

    // ------------------------------------------------------------------ 列举与创建

    @GetMapping("/namespaces/{namespace}/tables")
    public IcebergDtos.ListTablesResponse listTables(@PathVariable String prefix,
                                                     @PathVariable String namespace,
                                                     @RequestParam(required = false) Integer pageSize,
                                                     @RequestParam(required = false) String pageToken,
        @RequestParam(name = "page", required = false) Integer page) {
        return tableService.list(prefix, levels(namespace), pageSize, pageToken, page);
    }

    @PostMapping("/namespaces/{namespace}/tables")
    public IcebergDtos.LoadTableResponse createTable(
            @PathVariable String prefix,
            @PathVariable String namespace,
            @RequestBody(required = false) IcebergDtos.CreateTableRequest request,
            @RequestHeader(value = "X-Iceberg-Access-Delegation", required = false) String delegation) {
        return tableService.create(prefix, levels(namespace), request, delegation);
    }

    @PostMapping("/namespaces/{namespace}/register")
    public IcebergDtos.LoadTableResponse registerTable(
            @PathVariable String prefix,
            @PathVariable String namespace,
            @RequestBody(required = false) IcebergDtos.RegisterTableRequest request,
            @RequestHeader(value = "X-Iceberg-Access-Delegation", required = false) String delegation) {
        return tableService.register(prefix, levels(namespace), request, delegation);
    }

    // ------------------------------------------------------------------ 单表

    /**
     * {@code GET .../tables/{table}}。
     *
     * <p>{@code snapshots} 参数（{@code all} / {@code refs}，缺省等价于 {@code all}）
     * 决定快照列表要不要裁到「被 ref 引用」的那一批；见
     * {@code IcebergTableService#load}。
     *
     * <p>{@code referenced-by} 参数本服务端不认（它用于 {@code plan} 系列的
     * plan-id 追踪，本服务端不做 plan），因此**不接收、也不报错**——
     * 规格里它是可选参数，忽略未知的可选参数与「静默吞掉一个 update」不是一回事：
     * 后者会让服务端返回一份与客户端预期不符的元数据。
     */
    @GetMapping("/namespaces/{namespace}/tables/{table}")
    public IcebergDtos.LoadTableResponse loadTable(
            @PathVariable String prefix,
            @PathVariable String namespace,
            @PathVariable String table,
            @RequestParam(required = false) String snapshots,
            @RequestHeader(value = "X-Iceberg-Access-Delegation", required = false) String delegation) {
        return tableService.load(prefix, levels(namespace), table, snapshots, delegation);
    }

    @RequestMapping(value = "/namespaces/{namespace}/tables/{table}", method = RequestMethod.HEAD)
    public ResponseEntity<Void> tableExists(@PathVariable String prefix,
                                            @PathVariable String namespace,
                                            @PathVariable String table) {
        tableService.requireExists(prefix, levels(namespace), table);
        return ResponseEntity.noContent().build();
    }

    /** 一轮 update 作用在当前元数据上；响应体是提交之后的完整 {@code LoadTableResult}。 */
    @PostMapping("/namespaces/{namespace}/tables/{table}")
    public IcebergDtos.LoadTableResponse updateTable(
            @PathVariable String prefix,
            @PathVariable String namespace,
            @PathVariable String table,
            @RequestBody(required = false) IcebergDtos.UpdateTableRequest request) {
        return tableService.commit(prefix, levels(namespace), table, request);
    }

    /**
     * {@code DELETE .../tables/{table}}。
     *
     * <p>{@code purgeRequested} 是规格定义的可选参数（默认 {@code false}），
     * 与部署开关 {@code purge-on-drop} 取或，外部登记表永不清——见
     * {@code IcebergTableService#drop}。
     */
    @DeleteMapping("/namespaces/{namespace}/tables/{table}")
    public ResponseEntity<Void> dropTable(@PathVariable String prefix,
                                          @PathVariable String namespace,
                                          @PathVariable String table,
                                          @RequestParam(required = false) Boolean purgeRequested) {
        tableService.drop(prefix, levels(namespace), table, purgeRequested);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ 新的小端点

    /**
     * {@code POST .../tables/{table}/unregister}：只摘目录条目，不动仓库。
     *
     * <p>回 200（规格里这条的成功码不是 204，与同族的 {@code delete} 不同）。
     * 上游没有为它定义 {@code Endpoint} 常量，路径字符串取自规格原文——
     * 详见 {@code IcebergEndpoints}。
     */
    @PostMapping("/namespaces/{namespace}/tables/{table}/unregister")
    public ResponseEntity<Void> unregisterTable(@PathVariable String prefix,
                                                @PathVariable String namespace,
                                                @PathVariable String table) {
        tableService.unregister(prefix, levels(namespace), table);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/namespaces/{namespace}/tables/{table}/credentials")
    public IcebergDtos.LoadCredentialsResponse loadCredentials(@PathVariable String prefix,
                                                               @PathVariable String namespace,
                                                               @PathVariable String table) {
        return tableService.credentials(prefix, levels(namespace), table);
    }

    /**
     * {@code POST .../tables/{table}/metrics}：客户端上报提交报告。
     *
     * <p>收成 {@code JsonNode} 而不是建 DTO：报告的形状有四种
     * （{@code scan-report} / {@code commit-report} / …），字段随上游增删，
     * 而本服务端只用得上其中一两个（见 {@code IcebergTableService#reportMetrics}）。
     * 建一套 DTO 等于替上游承诺它不变。
     */
    @PostMapping("/namespaces/{namespace}/tables/{table}/metrics")
    public ResponseEntity<Void> reportMetrics(@PathVariable String prefix,
                                              @PathVariable String namespace,
                                              @PathVariable String table,
                                              @RequestBody(required = false) JsonNode report) {
        tableService.reportMetrics(prefix, levels(namespace), table, report);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ 多表事务

    /**
     * {@code POST /v1/{prefix}/transactions/commit}：多张表的一轮提交，成功码 204。
     *
     * <p>目标表全在请求体里，授权由服务层对每张表逐个判（deferred），
     * 见 {@code IcebergTableService#commitTransaction}。
     */
    @PostMapping("/transactions/commit")
    public ResponseEntity<Void> commitTransaction(
            @PathVariable String prefix,
            @RequestBody(required = false) IcebergDtos.CommitTransactionRequest request) {
        tableService.commitTransaction(prefix, request);
        return ResponseEntity.noContent().build();
    }

    private static List<String> levels(String namespace) {
        return NamespacePaths.parse(namespace);
    }
}
