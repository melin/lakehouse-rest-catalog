package io.github.melin.lakehouse.rest.web;

import io.github.melin.lakehouse.rest.dto.ManagementDtos.CreateGroupMappingRequest;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.GroupMapping;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.GroupMappingPreview;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.GroupMappingPreviewRequest;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.GroupMappings;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.UpdateGroupMappingRequest;
import io.github.melin.lakehouse.rest.service.AuthorizationService;
import io.github.melin.lakehouse.rest.service.GroupMappingService;
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
 * 身份接入的管理端点：IdP 组 → 主体角色的映射，以及「保存前看一眼」的预览。
 *
 * <p><b>非 Polaris 规格端点</b>，与 {@code /api/console/**} 同属本服务端的扩展：
 * Polaris 没有「组映射」这个概念（它把身份供给交给上层 portal），
 * 所以这里不假装对齐一个不存在的规范，形状按本工程的习惯来。
 *
 * <p>整组要求服务管理员：身份接入是全局配置，改一条映射等于改一批人的权限，
 * 而现有 14 项 access 词表是资源级的，没有一项能准确表达这件事。
 *
 * <p><b>预览端点为什么要单独存在。</b>映射写错的代价不对称——多给一个角色是
 * 「他看到不该看的数据」，少给一个角色是「他打不开该打开的表」，两者都不会报错。
 * 让管理员在保存前看到「这组人最终会拿到什么」，比事后从审计里反推便宜得多。
 * 它与登录时的同步调用的是<b>同一段计算</b>（{@code GroupRoleSyncService}），
 * 因此不会出现「预览说两个角色、保存后一个都没有」这类分叉问题。
 */
@RestController
@RequestMapping("/api/management/v1/group-mappings")
@RequiredArgsConstructor
public class GroupMappingController {

    private final GroupMappingService groupMappings;

    private final AuthorizationService authorizationService;

    /** {@code GET /group-mappings?provider=&group=&page=&pageSize=}。 */
    @GetMapping
    public GroupMappings list(@RequestParam(name = "provider", required = false) String provider,
                              @RequestParam(name = "group", required = false) String group,
                              @RequestParam(name = "page", required = false) Integer page,
                              @RequestParam(name = "pageSize", required = false) Integer pageSize) {
        authorizationService.requireServiceAdmin("list group mappings");
        return groupMappings.list(provider, group, page, pageSize);
    }

    /** {@code POST /group-mappings}。 */
    @PostMapping
    public ResponseEntity<GroupMapping> create(
            @RequestBody(required = false) CreateGroupMappingRequest request) {
        authorizationService.requireServiceAdmin("create a group mapping");
        return ResponseEntity.status(HttpStatus.CREATED).body(groupMappings.create(request));
    }

    /** {@code PUT /group-mappings/{id}}：改角色与启停。 */
    @PutMapping("/{id}")
    public GroupMapping update(@PathVariable String id,
                               @RequestBody(required = false) UpdateGroupMappingRequest request) {
        authorizationService.requireServiceAdmin("update a group mapping");
        return groupMappings.update(id, request);
    }

    /** {@code DELETE /group-mappings/{id}}：只删规则，不回收已经同步出去的成员关系。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        authorizationService.requireServiceAdmin("delete a group mapping");
        groupMappings.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** {@code POST /group-mappings/preview}：不写库。 */
    @PostMapping("/preview")
    public GroupMappingPreview preview(
            @RequestBody(required = false) GroupMappingPreviewRequest request) {
        // 预览只读配置、不写库，但仍然要求管理员身份：它会把「某个组会拿到哪些角色」
        // 告诉调用方，而那是权限拓扑——同一个理由让映射列表也不对普通用户开放
        authorizationService.requireServiceAdmin("preview group mappings");
        return groupMappings.preview(request);
    }
}
