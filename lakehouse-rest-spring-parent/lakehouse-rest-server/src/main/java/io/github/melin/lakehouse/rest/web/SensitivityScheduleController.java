package io.github.melin.lakehouse.rest.web;

import io.github.melin.lakehouse.rest.domain.entity.SensitivityScheduleEntity;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.Access;
import io.github.melin.lakehouse.rest.dto.SensitivityDtos.SensitivitySchedule;
import io.github.melin.lakehouse.rest.dto.SensitivityDtos.SensitivityScheduleList;
import io.github.melin.lakehouse.rest.dto.SensitivityDtos.SensitivityScheduleUpsertRequest;
import io.github.melin.lakehouse.rest.service.AuthorizationService;
import io.github.melin.lakehouse.rest.service.SensitivityScheduleService;
import io.github.melin.lakehouse.rest.support.ApiException;
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
 * 定时扫描调度项的管理端点（{@code /api/management/v1/sensitivity-schedules}，设计见
 * {@code docs/sensitive-data-design.md} §4.6 / §5.6.3 / §9）。
 *
 * <p><b>这五条端点是「调度自建」这个决定的可交付部分</b>（§9 末段的注）：路线一翻，
 * 它们就从「可选」变成「必有」，而且要进 {@code scripts/verify-console.py} 的
 * {@code EXTENSION_ENDPOINTS}——那份白名单按 {@code (方法, 路径)} 元组计数，
 * 登记成「+3 个路径」会让守卫的差集对不上。数量是 **5 不是 3**。
 *
 * <p><b>判权按 catalog，与发起扫描同一条。</b>调度项决定了「这个 catalog 的哪些表
 * 会在凌晨被读一遍」，与手动发一次扫描是同一级别的动作，因此写操作用
 * {@code CATALOG:ALTER}、读用 {@code CATALOG:DESCRIBE}。不带 {@code catalog} 的列表
 * 判服务管理员：否则它就是一条「列出所有 catalog 的扫描计划」的路（§10 的
 * 「保护动作的存在本身也是信息」）。
 *
 * <p><b>id 用 {@code String} 接再手工转数字。</b>{@code @PathVariable Long} 在
 * {@code /abc} 时会抛 {@code MethodArgumentTypeMismatchException}，而全局处理器没有
 * 针对它的分支——那会变成一个 500。路径参数写错是调用方的问题，答案应该是 400。
 */
@RestController
@RequestMapping("/api/management/v1/sensitivity-schedules")
@RequiredArgsConstructor
public class SensitivityScheduleController {

    private final SensitivityScheduleService scheduleService;
    private final AuthorizationService authorizationService;

    /**
     * {@code GET /sensitivity-schedules?catalog=}：调度项清单（不分页，量级是「几十条」）。
     *
     * <p>{@code catalog} 可省略：省略即「全部 catalog 的调度项」，此时判服务管理员。
     */
    @GetMapping
    public SensitivityScheduleList list(@RequestParam(name = "catalog", required = false) String catalog) {
        if (catalog != null && !catalog.isBlank()) {
            authorizationService.requireCatalog(catalog.trim(), Access.DESCRIBE,
                    "list sensitivity schedules");
        } else {
            authorizationService.requireServiceAdmin("list sensitivity schedules across catalogs");
        }
        return scheduleService.list(catalog);
    }

    /**
     * {@code POST /sensitivity-schedules}：新建。
     *
     * <p>四种失败都是 400 而不是「存下来之后每天失败一次」：cron 不合法、
     * 范围不递进（有表名没库名）、{@code METADATA_ONLY} + {@code SPARK}、
     * 以及目标 catalog 的执行集群不可派发（fail-closed，§5.6.5）。
     * 同名是 409——那是唯一一个「换个名字就能过」的失败。
     */
    @PostMapping
    public ResponseEntity<SensitivitySchedule> create(@RequestBody SensitivityScheduleUpsertRequest request) {
        String catalog = request == null ? null : request.catalog();
        requireCatalog(catalog, "create a sensitivity schedule");
        return ResponseEntity.status(HttpStatus.CREATED).body(scheduleService.create(request));
    }

    /**
     * {@code GET /sensitivity-schedules/{id}}：详情。
     *
     * <p>先查存在性、再判权：反过来的话一个不存在的 id 会回 403（连判权需要的 catalog
     * 都取不到），而正确语义是 404。与 {@code SensitivityScanController#get} 同形。
     */
    @GetMapping("/{id}")
    public SensitivitySchedule get(@PathVariable String id) {
        SensitivityScheduleEntity entity = scheduleService.require(parseId(id));
        requireCatalog(entity.getCatalogName(), "read a sensitivity schedule");
        return scheduleService.get(entity.getId());
    }

    /**
     * {@code PUT /sensitivity-schedules/{id}}：改（含启停）。
     *
     * <p><b>两个 catalog 都要判</b>：请求体允许把调度项挪到另一个 catalog（同一个治理员
     * 可能同时管两个），但那意味着「在新的 catalog 上排扫描」。只判旧的那个，
     * 就等于允许把一条已有的调度项搬进自己没权限的 catalog——判权形同虚设。
     *
     * <p>{@code entityVersion} 不匹配回 409；{@code null} 跳过校验（与
     * {@code PUT /sensitivity-settings} 同口径）。
     */
    @PutMapping("/{id}")
    public SensitivitySchedule update(@PathVariable String id,
                                      @RequestBody SensitivityScheduleUpsertRequest request) {
        SensitivityScheduleEntity entity = scheduleService.require(parseId(id));
        requireCatalog(entity.getCatalogName(), "update a sensitivity schedule");
        // 目标 catalog 也要判一次（同样是 ALTER）：请求体允许把调度项挪到另一个 catalog，
        // 只判旧的那个就等于允许「把一条已有调度项搬进自己没权限的 catalog」
        requireCatalog(request == null ? null : request.catalog(), "update a sensitivity schedule");
        return scheduleService.update(entity.getId(), request);
    }

    /**
     * {@code DELETE /sensitivity-schedules/{id}}：删除（真删，不是停用）。
     *
     * <p>停用是另一个动作（{@code PUT enabled=false}）：它的语义是「这条规则还在、
     * 只是暂时不跑」。把删除实现成停用，清单上就会出现「我删掉了它却还在」。
     * 历史扫描不受影响——它们按 {@code schedule_name} 存字符串，不建外键。
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        SensitivityScheduleEntity entity = scheduleService.require(parseId(id));
        requireCatalog(entity.getCatalogName(), "delete a sensitivity schedule");
        scheduleService.delete(entity.getId());
        return ResponseEntity.noContent().build();
    }

    private void requireCatalog(String catalogName, String action) {
        if (catalogName == null || catalogName.isBlank()) {
            throw ApiException.badRequest("catalog is required");
        }
        authorizationService.requireCatalog(catalogName.trim(), Access.ALTER, action);
    }

    /** 路径参数是数字；不是就给 400（{@code Long.parseLong} 的原话对调用方没有意义）。 */
    private static Long parseId(String id) {
        try {
            return Long.valueOf(id);
        } catch (NumberFormatException exception) {
            throw ApiException.badRequest("scheduleId must be a number, got \"" + id + "\"");
        }
    }
}
