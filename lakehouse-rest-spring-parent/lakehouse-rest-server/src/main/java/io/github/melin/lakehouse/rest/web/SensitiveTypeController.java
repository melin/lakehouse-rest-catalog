package io.github.melin.lakehouse.rest.web;

import io.github.melin.lakehouse.rest.config.RequestContext;
import io.github.melin.lakehouse.rest.dto.SensitivityDtos.SensitiveType;
import io.github.melin.lakehouse.rest.dto.SensitivityDtos.SensitiveTypeList;
import io.github.melin.lakehouse.rest.dto.SensitivityDtos.SensitiveTypeUpsertRequest;
import io.github.melin.lakehouse.rest.service.AuthorizationService;
import io.github.melin.lakehouse.rest.service.SensitiveTypeService;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * 敏感类型与探测器配置的管理端点（{@code /api/management/v1/sensitive-types}，
 * 设计见 {@code docs/sensitive-data-design.md} §4.1 / §5.1 / §9 / §10）。
 *
 * <p><b>挂管理 API 而不是 catalog API</b>：类型是**服务端自己的识别规则**，
 * 不是 Paimon / Iceberg 元数据的一部分，引擎不需要也不该从协议面看到它——
 * 与存储优化、血缘、数据策略同一选址理由。
 *
 * <p><b>权限是服务管理员，不是某个 catalog 的 ALTER。</b>这是对设计稿 §10 的一处
 * **有意的收窄**，理由在数据模型里：{@code lakehouse_sensitive_type} **没有 catalog 列**
 * ——全服务共用一份类型表。§10 把「管类型」挂在 {@code CATALOG:SENSITIVE_ADMIN} 下，
 * 隐含前提是类型按 catalog 分治，而 P0 不是这样。
 *
 * <p>把一份全局表上的写操作映射到某个 catalog 的权限，会出现这个后果：只要对一个
 * catalog 有 ALTER 的人，就能改掉**所有** catalog 扫描时用的探测器权重。
 * 「我在 A 库把身份证的阈值调到 0.99」会静默改变 B 库的识别结果，
 * 而 B 库的治理员看不到这条改动来自哪里。这比「改类型要找服务管理员」坏得多。
 * 因此 P0 取「全局资源用全局权限」这条与 {@code PrincipalController} /
 * {@code GroupMappingController} / {@code IdentityProviderController} 一致的口径。
 *
 * <p>类型改为按 catalog 分治（加一列或加一张覆盖表）是后续阶段的事；那时这四个端点
 * 会改成 {@code requireCatalog(..., Access.ALTER)}，而**改的只会是这一处判权**——
 * 服务层与存储层不用动，因为它们本来就只按 {@code type_code} 寻址。
 */
@RestController
@RequestMapping("/api/management/v1/sensitive-types")
@RequiredArgsConstructor
public class SensitiveTypeController {

    private final SensitiveTypeService typeService;
    private final AuthorizationService authorizationService;

    /**
     * {@code GET /sensitive-types}：类型清单（内置 + 自定义，含停用的）。
     *
     * <p>含停用的是一个刻意的选择：这一屏是**配置面**，治理员要能看到
     * 「我把 {@code PERSON_NAME} 关了」这件事并把它打开，而只回启用的类型会让
     * 那个开关看上去消失。停用的类型在响应里带着 {@code enabled=false}。
     */
    @GetMapping
    public SensitiveTypeList list() {
        authorizationService.requireServiceAdmin("list sensitive types");
        return typeService.list();
    }

    /** {@code GET /sensitive-types/{typeCode}}：单个类型；不存在抛 404。 */
    @GetMapping("/{typeCode}")
    public SensitiveType get(@PathVariable String typeCode) {
        authorizationService.requireServiceAdmin("read a sensitive type");
        return typeService.get(typeCode);
    }

    /**
     * {@code POST /sensitive-types}：新建自定义类型。
     *
     * <p>{@code typeCode} 由调用方给出（不像别的资源用服务端生成 id）：它会进标签键
     * （{@code PII.CN_ID_CARD}）与事件属性，是跨系统契约的一部分。
     * {@code builtin} 恒为 {@code false}——不接受请求体指定，否则
     * 「先把一个内置类型标成非内置、再删掉它」就是一条绕过 409 保护的路。
     */
    @PostMapping
    public ResponseEntity<SensitiveType> create(@RequestBody SensitiveTypeUpsertRequest request) {
        authorizationService.requireServiceAdmin("create a sensitive type");
        SensitiveType created = typeService.create(request, RequestContext.principal());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * {@code PUT /sensitive-types/{typeCode}}：整份更新（**内置类型也可改**）。
     *
     * <p>验收断言要求「改一个内置类型的探测器权重后，下一轮扫描的判定随之改变且变更进审计」，
     * 而权重就住在 {@code detectors} 里——因此内置类型不能锁死。
     * 改动的责任由 {@code updatedBy} / {@code updatedAt} 与事件承担。
     *
     * <p>没有乐观锁：类型表没有 {@code entity_version} 列（设计稿 §4.1 的字段表里没有它）。
     * 理由与代价见 {@code SensitivityDtos.SensitiveType} 的注释——两个治理员同时改同一个
     * 类型的窗口极小，而冲突的后果是「一个版本的探测器配置被覆盖」，下一轮扫描就能发现。
     */
    @PutMapping("/{typeCode}")
    public SensitiveType update(@PathVariable String typeCode,
                                @RequestBody SensitiveTypeUpsertRequest request) {
        authorizationService.requireServiceAdmin("update a sensitive type");
        return typeService.update(typeCode, request, RequestContext.principal());
    }

    /**
     * {@code DELETE /sensitive-types/{typeCode}}：删除自定义类型。
     *
     * <p><b>内置类型返 409</b>（验收断言之一）：删了它，下次启动 seed 会按
     * {@code type_code} 唯一键把它重新建出来——「删掉了但重启又回来了」是一个比 409
     * 更难理解的状态。停用走 {@code PUT} 且 {@code enabled=false}。
     */
    @DeleteMapping("/{typeCode}")
    public ResponseEntity<Void> delete(@PathVariable String typeCode) {
        authorizationService.requireServiceAdmin("delete a sensitive type");
        typeService.delete(typeCode, RequestContext.principal());
        return ResponseEntity.noContent().build();
    }
}
