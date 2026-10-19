package io.github.melin.lakehouse.rest.dto;

import io.github.melin.lakehouse.rest.dto.ManagementEnums.CatalogType;
import io.github.melin.lakehouse.rest.dto.StorageDtos.StorageConfigInfo;
import java.util.List;
import java.util.Map;

/**
 * 管理面的 catalog / 主体 / principal role 模型，字段形状沿用
 * {@code spec/polaris-management-service.yml}（该规格只作**形状来源**，不再是契约）。
 *
 * <p>权限与授权不在这份文件里：官方 Paimon REST Management API 只定义
 * labels / permissions / policies 三族端点，主体与 catalog 的生命周期归服务端，
 * 因此 catalog 与主体留在 {@code /api/management/v1} 作为服务端扩展，
 * 而「谁能对什么做什么」统一由 {@link PermissionDtos} 的
 * {@code (resource, access, principal)} 表达。
 *
 * <p>用 record 而非可变类：这些对象只在控制器与服务层之间传递，没有原地修改的需求，
 * record 自动获得 {@code equals} / {@code hashCode}，便于测试断言。
 *
 * <p>可变长度与自由结构的部分（{@code properties}、{@code credentials}）保留为 Map，
 * 与外层契约的 {@code object} 类型一致。
 */
public final class ManagementDtos {

    private ManagementDtos() {
    }

    // ------------------------------------------------------------------ catalog

    /** {@code Catalogs}。 */
    public record Catalogs(List<Catalog> catalogs) {
    }

    /**
     * {@code Catalog}。
     *
     * <p>{@code name} 即 catalog API 中的 prefix：管理面用它标识 catalog，
     * catalog 面用它拼路径，两侧指向同一份数据。
     *
     * <p>{@code catalogFormat} 是**表格式**（{@code PAIMON} / {@code ICEBERG}），
     * 与 {@code type}（托管模式 {@code INTERNAL} / {@code EXTERNAL}）正交：
     * {@code EXTERNAL + ICEBERG} 这类组合只有两个字段都在才能表达。
     * 它是这个模型里**唯一不可变**的字段——要换格式只能删除重建，
     * 因为同一个 prefix 下两种格式的元数据代数、仓库物化产物、客户端可见的端点
     * 都不同，改一个字段等于让所有已建对象的语义整体漂移。
     * 因此 {@code PUT} 只在取值与库中相同时接受它（见
     * {@code ManagementCatalogService#update}）。
     */
    public record Catalog(String type,
                          String catalogFormat,
                          String name,
                          Map<String, String> properties,
                          Long createTimestamp,
                          Long lastUpdateTimestamp,
                          Integer entityVersion,
                          StorageConfigInfo storageConfigInfo) {
    }

    /** {@code CreateCatalogRequest}。 */
    public record CreateCatalogRequest(Catalog catalog) {
    }

    /**
     * {@code UpdateCatalogRequest}。
     *
     * <p>{@code catalogFormat} 在这里是**只读确认字段**，不是可改字段：给了就必须与
     * 库中当前值一致，否则 400（消息里点明「格式不可变，要换请删除重建」）。
     * 之所以还收它，是因为管理面常见用法是「GET 一个 catalog、改一个字段、PUT 回去」，
     * 那种往返会把整个 {@code Catalog} 原样带回来。断言相等而不是静默忽略，
     * 是为了让「以为能改格式」的调用方立刻知道不行，而不是以为改成功了。
     */
    public record UpdateCatalogRequest(Integer currentEntityVersion,
                                       String catalogFormat,
                                       Map<String, String> properties,
                                       StorageConfigInfo storageConfigInfo) {
    }

    // ------------------------------------------------------------------ principal

    /** {@code Principals}。 */
    public record Principals(List<Principal> principals) {
    }

    /** {@code Principal}。{@code clientId} 可读，密钥永不出现在该模型里。 */
    public record Principal(String name,
                            String clientId,
                            Map<String, String> properties,
                            Long createTimestamp,
                            Long lastUpdateTimestamp,
                            Integer entityVersion) {
    }

    /** {@code PrincipalWithCredentials}：创建、轮换、重置主体时返回，明文密钥仅此一次。 */
    public record PrincipalWithCredentials(Principal principal, Map<String, Object> credentials) {
    }

    /** {@code CreatePrincipalRequest}。 */
    public record CreatePrincipalRequest(Principal principal, Boolean credentialRotationRequired) {
    }

    /** {@code UpdatePrincipalRequest}。 */
    public record UpdatePrincipalRequest(Integer currentEntityVersion, Map<String, String> properties) {
    }

    /** {@code ResetPrincipalRequest}：两个字段都为空时由服务端生成新凭据。 */
    public record ResetPrincipalRequest(String clientId, String clientSecret) {
    }

    // ------------------------------------------------------------------ principal role

    /** {@code PrincipalRoles}。 */
    public record PrincipalRoles(List<PrincipalRole> roles) {
    }

    /** {@code PrincipalRole}。 */
    public record PrincipalRole(String name,
                                Boolean federated,
                                Map<String, String> properties,
                                Long createTimestamp,
                                Long lastUpdateTimestamp,
                                Integer entityVersion) {
    }

    /** {@code CreatePrincipalRoleRequest}。 */
    public record CreatePrincipalRoleRequest(PrincipalRole principalRole) {
    }

    /** {@code UpdatePrincipalRoleRequest}。 */
    public record UpdatePrincipalRoleRequest(Integer currentEntityVersion, Map<String, String> properties) {
    }

    /** {@code GrantPrincipalRoleRequest}：把 principal role 授予某个主体。 */
    public record GrantPrincipalRoleRequest(PrincipalRole principalRole) {
    }

    // ------------------------------------------------------------------ 权限
    //
    // 权限不在这里建模：官方 management API 把「资源 + access + principal」定义在
    // `PermissionDtos` 里，端点挂在 catalog 前缀下（`/v1/{prefix}/permissions`）。
    // 这里曾有一套 Polaris 形状的 catalog role / grant 模型，已随 catalog role 一并移除。

    /** catalog 类型的判别值，供校验错误信息复用。 */
    public static CatalogType parseCatalogType(String value) {
        return CatalogType.parse(value).orElse(CatalogType.INTERNAL);
    }
}
