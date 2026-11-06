package io.github.melin.lakehouse.rest.web;

import io.github.melin.lakehouse.rest.config.RequestContext;
import io.github.melin.lakehouse.rest.domain.entity.ColumnTagEntity;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.Access;
import io.github.melin.lakehouse.rest.dto.SensitivityDtos.ColumnTag;
import io.github.melin.lakehouse.rest.dto.SensitivityDtos.ColumnTagList;
import io.github.melin.lakehouse.rest.dto.SensitivityDtos.ColumnTagUpsertRequest;
import io.github.melin.lakehouse.rest.dto.SensitivityDtos.FindingList;
import io.github.melin.lakehouse.rest.service.AuthorizationService;
import io.github.melin.lakehouse.rest.service.ColumnTagService;
import io.github.melin.lakehouse.rest.service.SensitivityScanService;
import io.github.melin.lakehouse.rest.support.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 列级标签与扫描命中的管理端点（设计见 {@code docs/sensitive-data-design.md}
 * §4.4 / §8.4 / §9 / §10）。
 *
 * <p><b>一个控制器承载两组资源</b>（{@code /column-tags} 与 {@code /sensitivity-findings}），
 * 因为它们共享同一条判权规则、同一套错误语义，且在人审工作流里是同一个界面的两侧
 * （左栏是「扫描判过什么」，右栏是「现在是什么」）。给一个资源类型各开一个类，
 * 会给同一个功能带来两份判权代码，而两份判权代码漂移的表现是「一边松一边紧」。
 *
 * <p><b>权限分两档（设计稿 §10 的「标签分级可见」）：</b>
 * <ul>
 *   <li>看完整标签（类型 {@code PII.CN_ID_CARD} + 级别 + 置信度）→ {@code CATALOG:DESCRIBE}。
 *       设计稿为此单列了 {@code CATALOG:SENSITIVE_READ}，但它的判据与
 *       「能不能读这个 catalog」完全相同——而 §10 那条规则要挡住的是
 *       **看整张表**的人（{@code TABLE:SELECT}），不是看 catalog 的人。
 *       {@code DESCRIBE} 就是「能读 catalog」；只有 TABLE 级权限的人拿不到它，
 *       因此这条规则在现有词表上已经完整表达。</li>
 *   <li>打标 / 撤标 → {@code CATALOG:ALTER}。标签是**脱敏行为的输入**
 *       （{@code tag → mask} 的绑定在 P3 展开），改它与 {@code TABLE:ALTER} 同级；
 *       落在 catalog 上漏 TABLE 上，是因为标签表（{@code lakehouse_column_tag}）
 *       只按 {@code catalog_name} 建了索引、没有按表的索引，且引用坐标是列级
 *       SHA-256——「这张表的这个标签」不是一个可单独寻址的资源。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/management/v1")
@RequiredArgsConstructor
public class ColumnTagController {

    private final ColumnTagService tagService;
    private final SensitivityScanService scanService;
    private final AuthorizationService authorizationService;

    // ------------------------------------------------------------------ 列级标签

    /**
     * {@code GET /column-tags?catalog=&table=&tagKey=&level=}：标签清单 + 覆盖率。
     *
     * <p>这是设计稿 §8.4 说的「对外主接口」（数据地图、审批流、上线检查都消费它）。
     * 响应里带 {@code coverage} 而不是分成两个端点：调用方取标签时几乎总要同时知道
     * 「这个范围覆盖了多少」——分两个端点会让「看到 12 条标签」与「12 条占多少」
     * 之间出现一次可以做别的写入的窗口，而那个窗口里的数字是不一致的。
     */
    @GetMapping("/column-tags")
    public ColumnTagList list(@RequestParam("catalog") String catalog,
                              @RequestParam(name = "table", required = false) String table,
                              @RequestParam(name = "tagKey", required = false) String tagKey,
                              @RequestParam(name = "level", required = false) String level,
                              @RequestParam(name = "page", required = false) Integer page,
                              @RequestParam(name = "pageSize", required = false) Integer pageSize) {
        requireCatalog(catalog, Access.DESCRIBE, "list column tags");
        return tagService.list(catalog, table, tagKey, level, page, pageSize);
    }

    /**
     * {@code PUT /column-tags}：手工打标（upsert）。
     *
     * <p><b>判权用的是请求体里的 catalog，因此必须在服务层之前。</b>
     * 顺序是「先判权、再校验三段是否存在」——反过来的话，一个无权的主体可以通过
     * 「库/表不存在」与「表存在」两种 404 的差别，枚举出这个 catalog 里有哪些表。
     * 这条与 {@code DataPolicyController} 的 {@code requireTableAccess} 完全同形。
     *
     * <p>回 200 而不是 201：upsert 既可能是新建也可能是更新，而调用方区分不了
     * （它要的是「这条标签现在是什么」）。{@code entityVersion} 在响应体里，
     * 下次更新用它。
     */
    @PutMapping("/column-tags")
    public ColumnTag upsert(@RequestBody ColumnTagUpsertRequest request) {
        String catalog = request == null ? null : request.catalog();
        requireCatalog(catalog, Access.ALTER, "write a column tag");
        return tagService.upsert(request, RequestContext.principal());
    }

    /**
     * {@code DELETE /column-tags/{id}}：删标签（**同时写抑制项**，§7.2）。
     *
     * <p>判权按标签自己记着的 catalog——与 {@code GET /sensitivity-scans/{id}} 同一理由：
     * {@code id} 是不透明的，从库里取出的 catalog 才是真相源。
     *
     * <p><b>P0 只删标签、不写抑制项。</b>原因是抑制项依赖的两个前提在 P0 都不成立：
     * 没有任何路径能产生 {@code REJECTED}（人审端点属 P1），且元数据档扫描
     * 置信度上限 0.45 **永远不产 tag**（够不到 0.85）——因此「删了会复活」在 P0
     * 不成立。抑制项必须与 {@code decide} 端点一起落地（P1）。
     * 完整推理见 {@code ColumnTagService} 的类注释。
     */
    @DeleteMapping("/column-tags/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        ColumnTagEntity entity = tagService.require(id);
        requireCatalog(entity.getCatalogName(), Access.ALTER, "delete a column tag");
        tagService.delete(id, RequestContext.principal());
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ 扫描命中

    /**
     * {@code GET /sensitivity-findings?state=PENDING&table=&type=}：命中清单。
     *
     * <p>P0 只提供**读**（人审队列 UI 与 {@code POST /sensitivity-findings:decide}
     * 属 P1）。分在同一个控制器里、判同一个权，是因为人审界面同时用这两组读端点。
     */
    @GetMapping("/sensitivity-findings")
    public FindingList findings(@RequestParam("catalog") String catalog,
                                @RequestParam(name = "state", required = false) String state,
                                @RequestParam(name = "table", required = false) String table,
                                @RequestParam(name = "type", required = false) String type,
                                @RequestParam(name = "page", required = false) Integer page,
                                @RequestParam(name = "pageSize", required = false) Integer pageSize) {
        requireCatalog(catalog, Access.DESCRIBE, "list sensitivity findings");
        return scanService.findings(catalog, state, table, type, page, pageSize);
    }

    private void requireCatalog(String catalogName, Access access, String action) {
        if (catalogName == null || catalogName.isBlank()) {
            throw ApiException.badRequest("catalog is required");
        }
        authorizationService.requireCatalog(catalogName.trim(), access, action);
    }
}
