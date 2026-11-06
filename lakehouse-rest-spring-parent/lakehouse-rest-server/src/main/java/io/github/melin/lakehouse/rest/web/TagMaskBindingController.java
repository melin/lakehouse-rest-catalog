package io.github.melin.lakehouse.rest.web;

import io.github.melin.lakehouse.rest.config.RequestContext;
import io.github.melin.lakehouse.rest.domain.entity.TagMaskBindingEntity;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.Access;
import io.github.melin.lakehouse.rest.dto.SensitivityDtos.TagMaskBinding;
import io.github.melin.lakehouse.rest.dto.SensitivityDtos.TagMaskBindingList;
import io.github.melin.lakehouse.rest.dto.SensitivityDtos.TagMaskBindingUpsertRequest;
import io.github.melin.lakehouse.rest.service.AuthorizationService;
import io.github.melin.lakehouse.rest.service.TagMaskBindingService;
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
 * 标签 → 脱敏绑定的管理端点（设计见 {@code docs/sensitive-data-design.md}
 * §4.5 / §8.1 / §9）。
 *
 * <p><b>权限分两档，与 {@code /column-tags} 完全同形。</b>看绑定 → {@code CATALOG:DESCRIBE}；
 * 改绑定 → {@code CATALOG:ALTER}。改绑定比改标签更高危：标签只说明「这列是什么」，
 * 绑定决定「这列怎么被遮」——把它调成 {@code replace_with_null} 或换一个更弱的
 * mask，等于把已经生效的保护降级，而接口层面看不出「降级」这个语义。因此写入判
 * {@code ALTER}（与「改这个 catalog 的配置」同级）。
 *
 * <p><b>为什么没有 {@code GET /tag-mask-bindings/{id}}。</b>列表是全量、量级「几条到几十条」，
 * 详情端点等于把同一份数据再开一个入口，而两个入口的判权如果不一致（比如详情漏判
 * {@code DESCRIBE}）就是一个直读后门。{@code DELETE} 用 {@code id}，catalog 从库里取出
 * ——与 {@code DELETE /column-tags/{id}} 同一理由：{@code id} 是不透明的，
 * 库里的 catalog 才是判权的真相源。
 */
@RestController
@RequestMapping("/api/management/v1")
@RequiredArgsConstructor
public class TagMaskBindingController {

    private final TagMaskBindingService bindingService;
    private final AuthorizationService authorizationService;

    /** {@code GET /tag-mask-bindings?catalog=}：绑定清单（含停用项）。 */
    @GetMapping("/tag-mask-bindings")
    public TagMaskBindingList list(@RequestParam("catalog") String catalog) {
        requireCatalog(catalog, Access.DESCRIBE, "list tag mask bindings");
        return bindingService.list(catalog);
    }

    /**
     * {@code PUT /tag-mask-bindings}：按唯一键 {@code (catalog, tagKey, tagValue)} upsert。
     *
     * <p><b>判权用请求体里的 catalog，必须在服务层之前</b>——与
     * {@code ColumnTagController.upsert} 同一理由：先判权再校验 catalog 是否存在，
     * 否则无权主体能靠「404 与 200 的差别」枚举出存在哪些 catalog。
     *
     * <p>回 200 而不是 201：upsert 既可能新建也可能更新，调用方区分不了也不需要区分
     * （它要的是「这条绑定现在是什么」）。
     */
    @PutMapping("/tag-mask-bindings")
    public TagMaskBinding upsert(@RequestBody TagMaskBindingUpsertRequest request) {
        String catalog = request == null ? null : request.catalog();
        requireCatalog(catalog, Access.ALTER, "write a tag mask binding");
        return bindingService.upsert(request, RequestContext.principal());
    }

    /**
     * {@code DELETE /tag-mask-bindings/{id}}：物理删除一条绑定。
     *
     * <p>删除会让之前靠它脱敏的列回到明文（引擎侧最多晚 60 秒缓存）——这是一个
     * 不能静默发生的动作，服务层会发 {@code sensitivity.binding.changed}
     * （{@code kind=BINDING}、{@code action=DELETED}）。要「先关掉观察一下」请用
     * {@code PUT} 带 {@code enabled=false}，那条路保留绑定、不发删除事件。
     */
    @DeleteMapping("/tag-mask-bindings/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        TagMaskBindingEntity entity = bindingService.require(id);
        requireCatalog(entity.getCatalogName(), Access.ALTER, "delete a tag mask binding");
        bindingService.delete(id, RequestContext.principal());
        return ResponseEntity.noContent().build();
    }

    private void requireCatalog(String catalogName, Access access, String action) {
        if (catalogName == null || catalogName.isBlank()) {
            throw ApiException.badRequest("catalog is required");
        }
        authorizationService.requireCatalog(catalogName.trim(), access, action);
    }
}
