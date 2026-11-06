package io.github.melin.lakehouse.rest.web;

import io.github.melin.lakehouse.rest.dto.PermissionDtos.Access;
import io.github.melin.lakehouse.rest.dto.SensitivityDtos.SensitivitySettings;
import io.github.melin.lakehouse.rest.dto.SensitivityDtos.SensitivitySettingsUpdateRequest;
import io.github.melin.lakehouse.rest.service.AuthorizationService;
import io.github.melin.lakehouse.rest.service.SensitivitySettingsService;
import io.github.melin.lakehouse.rest.support.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * catalog 级敏感数据治理设置（{@code /api/management/v1/sensitivity-settings}，
 * 设计见 {@code docs/sensitive-data-design.md} §5.6.1 / §5.6.4 / §9 / §10）。
 *
 * <p><b>为什么它挂在一级路径上、catalog 走查询参数，而不是挂在 catalog 下。</b>
 * {@code /catalogs/{c}/execution-cluster} 那一组挂 catalog 下是因为它是 catalog 的
 * 一个从属对象（1:1、随 catalog 级联删除、有独立的错误资源类型）。而这三个设置是
 * **catalog 属性 JSON 里的一段键**（{@code sensitivity.*}，见
 * {@code SensitivityCatalogSettings}）——它们不是一个可独立寻址的资源，{@code GET}
 * 也不该回 404（「这个 catalog 什么都没覆盖」是正常状态，返回的是空的覆盖值 + 生效值）。
 * 挂在 catalog 下会给「不存在的子资源」赋予语义，而这里没有子资源。
 *
 * <p><b>读写都判 {@code CATALOG:ALTER}，读不判 DESCRIBE。</b>与
 * {@code ExecutionClusterController}（读用 DESCRIBE）刻意不同，理由是这三个字段里有
 * {@code maxConcurrentScans} 与 {@code defaultExecution}：它们描述的是**这个 catalog
 * 的扫描会怎么跑**，而「这个 catalog 被纳入敏感数据治理了」本身是治理状态
 * （§10 的「保护动作的存在也是信息」）。读它的人应当是能改它的人。
 * 代价是控制台上这一屏对只读用户不可见——这是正确的，它只在对它做决定时才有用。
 */
@RestController
@RequestMapping("/api/management/v1/sensitivity-settings")
@RequiredArgsConstructor
public class SensitivitySettingsController {

    private final SensitivitySettingsService settingsService;
    private final AuthorizationService authorizationService;

    /**
     * {@code GET /sensitivity-settings?catalog=}：覆盖值 + 三个生效值。
     *
     * <p>同时回生效值是刻意的：界面要回答「这个 catalog 现在实际会怎么跑」，
     * 而只给覆盖值的话，答案是「未设置」——那要前端自己再取一次全局配置，
     * 于是同一件事有两个答案源。
     */
    @GetMapping
    public SensitivitySettings get(@RequestParam("catalog") String catalog) {
        requireCatalog(catalog, "read sensitivity settings");
        return settingsService.get(catalog);
    }

    /**
     * {@code PUT /sensitivity-settings?catalog=}：整份替换三个覆盖值。
     *
     * <p>走乐观锁（{@code entityVersion} 对的是 **catalog 自己的版本号**，
     * 不是这里的三个字段）：它改的是「这个 catalog 的扫描怎么跑」，与改绑定同级
     * ——两个治理员同时改必须由人来裁决。版本号借用 catalog 的，意味着
     * **改这个设置会让 catalog 的 etag 一起变**，因此并发改 catalog 属性的
     * 另一个请求也会拿到 409。这是有意的：这两个写操作动的是同一行。
     */
    @PutMapping
    public SensitivitySettings update(@RequestParam("catalog") String catalog,
                                      @RequestBody SensitivitySettingsUpdateRequest request) {
        requireCatalog(catalog, "update sensitivity settings");
        return settingsService.update(catalog, request);
    }

    private void requireCatalog(String catalogName, String action) {
        if (catalogName == null || catalogName.isBlank()) {
            throw ApiException.badRequest("catalog is required");
        }
        authorizationService.requireCatalog(catalogName.trim(), Access.ALTER, action);
    }
}
