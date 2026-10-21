package io.github.melin.lakehouse.rest.web;

import io.github.melin.lakehouse.rest.config.RequestContext;
import io.github.melin.lakehouse.rest.dto.ManagementDtos;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.Catalog;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.Catalogs;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.CreateCatalogRequest;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.UpdateCatalogRequest;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.Access;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.ResourceType;
import io.github.melin.lakehouse.rest.service.AuthorizationService;
import io.github.melin.lakehouse.rest.service.ManagementCatalogService;
import io.github.melin.lakehouse.rest.support.Paging;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
 * 管理面的 catalog 端点（服务端扩展）。
 *
 * <p>官方 Paimon 管理 API 不含 catalog 生命周期（它把 catalog 看作部署配置），
 * 这里保留路径与形状与 {@code spec/polaris-management-service.yml} 一致的一组端点，
 * 基址为 {@code /api/management/v1}。**权限不在这条链上**：
 * catalog 内「谁能读什么」由 {@code /v1/{prefix}/permissions} 的 access 决定，
 * 本控制器只判读（{@code CATALOG_ALL:DESCRIBE}）与改/删（{@code CATALOG:ALTER|DROP}）。
 */
@RestController
@RequestMapping("/api/management/v1/catalogs")
@RequiredArgsConstructor
public class ManagementCatalogController {

    private final ManagementCatalogService catalogService;
    private final AuthorizationService authorizationService;

    /**
     * {@code GET /catalogs?page=&pageSize=}。
     *
     * <p>开启授权时按调用者的可见范围过滤：服务管理员看到全部 catalog，
     * 其余调用者只看到自己至少持有一条赋权的 catalog。
     * 这样未授权调用方不会通过 catalog 列表探测到服务端有哪些 catalog。
     *
     * <p><b>先过滤、再切页。</b>顺序反过来会让 {@code total} 变成「服务器上有多少个
     * catalog」，于是页面上写着「共 12 个」却只翻得出 3 个——把不可见的东西
     * 通过条数泄露出去，也让人以为分页坏了。因此这里不用 {@code Pageable}：
     * 可见性是逐条判定的，只有整份列表在手才切得准。
     *
     * <p>不传 {@code page}/{@code pageSize} 时与以前完全一样：一次给出全量，
     * {@code page} 为 {@code null}。脚本与 Spark 客户端不受影响。
     */
    @GetMapping
    public Catalogs list(@RequestParam(name = "page", required = false) Integer page,
                         @RequestParam(name = "pageSize", required = false) Integer pageSize,
                         @RequestParam(name = "format", required = false) String format) {
        List<Catalog> visible = formatFiltered(visible(catalogService.list().catalogs()), format);
        Paging.Paged<Catalog> paged = Paging.paged(visible, page, pageSize);
        return paged == null
                ? new Catalogs(visible, null)
                : new Catalogs(paged.items(), paged.meta());
    }

    /**
     * 按 catalog 协议面过滤（{@code ?format=PAIMON|ICEBERG}）。
     *
     * <p>筛在服务端而不是浏览器：控制台的表格是远程分页的，客户端手里只有一页，
     * 在浏览器里筛只能筛出「这一页里的 Paimon」，与「全站的 Paimon」是两回事。
     * 空值与认不出的取值都按「不过滤」处理——筛选器是界面自选的，
     * 不该因为一个手改的 URL 就 400，但也不该静默筛出错的行。
     */
    private static List<Catalog> formatFiltered(List<Catalog> all, String format) {
        if (format == null || format.isBlank()) {
            return all;
        }
        String wanted = format.trim().toUpperCase(java.util.Locale.ROOT);
        List<Catalog> filtered = new ArrayList<>();
        for (Catalog catalog : all) {
            // 与前端 `catalogFormatOf` 同一个兜底：早期数据没有这一列时按 PAIMON 认
            String actual = catalog.catalogFormat() == null || catalog.catalogFormat().isBlank()
                    ? "PAIMON" : catalog.catalogFormat().toUpperCase(java.util.Locale.ROOT);
            if (actual.equals(wanted)) {
                filtered.add(catalog);
            }
        }
        return filtered;
    }

    /** 按调用者的可见范围过滤 catalog 列表（未开授权时原样返回）。 */
    private List<Catalog> visible(List<Catalog> all) {
        if (!authorizationService.enabled() || authorizationService.isServiceAdmin(RequestContext.principal())) {
            return all;
        }
        List<Catalog> visible = new ArrayList<>();
        for (Catalog catalog : all) {
            if (authorizationService.hasAnyGrant(RequestContext.principal(), catalog.name())) {
                visible.add(catalog);
            }
        }
        return visible;
    }

    /** {@code POST /catalogs}。创建 catalog 尚无权限可依，故要求服务管理员身份。 */
    @PostMapping
    public ResponseEntity<Catalog> create(@RequestBody CreateCatalogRequest request) {
        authorizationService.requireServiceAdmin("create a catalog");
        Catalog created = catalogService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * {@code GET /catalogs/{catalogName}}。
     *
     * <p>判 {@code CATALOG_ALL} 上的 {@code DESCRIBE}，而不是 {@code CATALOG} 上的：
     * 官方 Access/Resource 对照表里 {@code CATALOG} 只有 {@code ALL / ALTER / DROP / GRANT /
     * CREATEDATABASE}，**没有**任何读权限；{@code DESCRIBE} 只出现在
     * {@code CATALOG_ALL / DATABASE / TABLE / VIEW / FUNCTION} 上。
     * 而「能读这个 catalog 里的东西」正是本端点的语义，所以落在 {@code CATALOG_ALL}。
     */
    @GetMapping("/{catalogName}")
    public Catalog get(@PathVariable String catalogName) {
        authorizationService.require(catalogName, ResourceType.CATALOG_ALL, List.of(), null,
                Access.DESCRIBE, "read the catalog");
        return catalogService.get(catalogName);
    }

    /**
     * {@code GET /catalogs/{catalogName}/table-stats}：整个 catalog 的存储汇总（SQL 聚合）。
     *
     * <p>为控制台「存储」页服务：KPI、按库、Top 10、直方图四块在服务端一次算完，
     * 替代「一张表一个请求」的逐表扫描，响应大小与表数无关。
     * 授权与 {@link #get} 同一判法——catalog 视角的读，落在 {@code CATALOG_ALL} 的
     * {@code DESCRIBE} 上。
     */
    @GetMapping("/{catalogName}/table-stats")
    public ManagementDtos.TableStatsSummary tableStats(@PathVariable String catalogName,
                                                       @RequestParam(defaultValue = "false") boolean onlyPaimon) {
        authorizationService.require(catalogName, ResourceType.CATALOG_ALL, List.of(), null,
                Access.DESCRIBE, "read the catalog");
        return catalogService.tableStatsSummary(catalogName, onlyPaimon);
    }

    /**
     * {@code GET /catalogs/{catalogName}/table-stats/tables}：逐表明细的一页（远程分页）。
     *
     * <p>分页 / 过滤 / 排序下推到 SQL；浏览器只在选中库之后按页取数，
     * 打开存储页不再把全表行搬过去。授权与汇总端点同判。
     */
    @GetMapping("/{catalogName}/table-stats/tables")
    public ManagementDtos.TableStats tableRows(@PathVariable String catalogName,
                                               @RequestParam(required = false) String database,
                                               @RequestParam(required = false) String keyword,
                                               @RequestParam(defaultValue = "false") boolean onlyPaimon,
                                               @RequestParam(required = false) String sort,
                                               @RequestParam(required = false) String order,
                                               @RequestParam(required = false) Integer maxResults,
                                               @RequestParam(required = false) String pageToken,
                                               @RequestParam(required = false) Integer page,
                                               @RequestParam(required = false) Integer pageSize) {
        authorizationService.require(catalogName, ResourceType.CATALOG_ALL, List.of(), null,
                Access.DESCRIBE, "read the catalog");
        return catalogService.tableRows(catalogName, database, keyword, onlyPaimon,
                sort, order, maxResults, pageToken, page, pageSize);
    }

    /** {@code PUT /catalogs/{catalogName}}。 */
    @PutMapping("/{catalogName}")
    public Catalog update(@PathVariable String catalogName,
                          @RequestBody UpdateCatalogRequest request) {
        authorizationService.requireCatalog(catalogName, Access.ALTER, "update the catalog");
        return catalogService.update(catalogName, request);
    }

    /** {@code DELETE /catalogs/{catalogName}}。 */
    @DeleteMapping("/{catalogName}")
    public ResponseEntity<Void> delete(@PathVariable String catalogName) {
        authorizationService.requireCatalog(catalogName, Access.DROP, "delete the catalog");
        catalogService.delete(catalogName);
        return ResponseEntity.noContent().build();
    }
}
