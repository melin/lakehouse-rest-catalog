package io.github.melin.lakehouse.rest.dto;

import io.github.melin.lakehouse.rest.dto.ManagementEnums.CatalogType;
import io.github.melin.lakehouse.rest.dto.StorageDtos.StorageConfigInfo;
import io.github.melin.lakehouse.rest.support.PageMeta;
import java.util.List;
import java.util.Map;

/**
 * 管理面的 catalog / 主体 / principal role 模型，字段形状沿用 Polaris 的管理规格
 * （该规格已**从仓库移除**，只作形状来源，不再是契约；快照见
 * {@code docs/management-api-contract.md}）。
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

    /**
     * {@code Catalogs}。{@code page} 是远程分页元数据：不传分页参数时为
     * {@code null}（一次拿全量），传了 {@code page}/{@code pageSize} 时
     * 带 {@code total}/{@code page}/{@code pageSize}/{@code hasNext}。
     */
    public record Catalogs(List<Catalog> catalogs, PageMeta page) {
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
     *
     * <p>{@code executionCluster} 是**列表每行直接带的执行集群摘要**（一次 join，不是
     * 每行再发一个请求）：控制台要在 catalog 列表上显示「优化会不会跑」，
     * 而这件事取决于这个 catalog 有没有配执行集群、是否停用、最近一次验证的结论。
     * 三个判据的先后顺序有语义（停用优先于「待验证」），因此状态由服务端算、
     * 前端只显示——两边各算一份迟早会漂，漂的表现是「列表说正常、点进去说待验证」。
     */
    public record Catalog(String type,
                          String catalogFormat,
                          String name,
                          Map<String, String> properties,
                          Long createTimestamp,
                          Long lastUpdateTimestamp,
                          Integer entityVersion,
                          StorageConfigInfo storageConfigInfo,
                          ExecutionClusterDtos.Summary executionCluster) {
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

    /**
     * {@code POST /catalogs/{catalogName}/credentials/revoke} 的回执（服务端扩展）。
     *
     * <p><b>名字里带 {@code cached} 是刻意的。</b>云侧没有「按 token 吊销」的 API
     * （设计稿 G10 / P2-3）：已经发出去的那张票会一直有效到它自己的到期时刻，
     * 任何声称「立即失效」的接口都是在骗人。这个端点能做的只有两件：
     * 把服务端缓存里这个 catalog 的条目清掉（下一次读表会重新签发，
     * 而旧票仍在寿命内可用），以及让运维知道「从现在起新签的票已经是新的」。
     *
     * @param catalog             catalog 的 prefix
     * @param invalidatedEntries  清掉的缓存条目数（表级票 + 服务端 catalog 级票）
     * @param alreadyIssuedStillWork 固定 {@code true}，一句话说清边界，别让调用方误读
     * @param note               人读的处置建议（缩短 TTL / 轮换 catalog 上的钥匙 / 云侧拒条件）
     */
    public record CachedCredentialRevocation(String catalog,
                                             int invalidatedEntries,
                                             boolean alreadyIssuedStillWork,
                                             String note) {
    }

    // ------------------------------------------------------------------ principal

    /** {@code Principals}。 */
    /**
     * {@code Principals}。{@code page} 是远程分页元数据，语义与 {@link Catalogs#page}
     * 完全一致：不传 {@code page}/{@code pageSize} 时为 {@code null}（一次拿全量）。
     */
    public record Principals(List<Principal> principals, PageMeta page) {
    }

    /** {@code Principal}。{@code clientId} 可读，密钥永不出现在该模型里。 */
    public record Principal(String name,
                            String clientId,
                            Map<String, String> properties,
                            Long createTimestamp,
                            Long lastUpdateTimestamp,
                            Integer entityVersion,
                            // ---- 身份接入（docs/sso-catalog-identity-design.md §4）----
                            // 这四项只读：控制台据此把「IdP 来的」与「本地建的」分开显示。
                            // 它们不出现在任何请求体里——来源是服务端在 JIT 时写的事实，
                            // 不是调用方能声明的属性（能声明就等于能自己挑一个 `source=LOCAL`
                            // 来躲开同名接管判定）。
                            String source,
                            String externalId,
                            String displayName,
                            Long lastLoginAt) {
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
    /** {@code PrincipalRoles}。{@code page} 同上。 */
    public record PrincipalRoles(List<PrincipalRole> roles, PageMeta page) {
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

    // ------------------------------------------------------------------ 表统计

    /**
     * {@code GET /catalogs/{catalogName}/table-stats}：catalog 下全部表的**当前快照**统计。
     *
     * <p>为控制台「存储」页而生：一次请求替代逐表的 snapshot / loadTable 循环
     * （表多时那是一条请求队列，Iceberg 侧还要解析整份 metadata JSON）。
     * 行是 {@code lakehouse_table} 与 {@code lakehouse_snapshot} 的投影 join，
     * 空表（还没有快照）也有一行、统计列为 {@code null}，与逐表端点的「无快照」语义一致。
     */
    public record TableStats(List<TableStat> tables, long total, String nextPageToken,
                             PageMeta page) {
    }

    /**
     * {@code GET /catalogs/{catalogName}/table-stats}：整个 catalog 的存储汇总。
     *
     * <p>四块都在服务端算完（SQL 聚合），浏览器不做任何逐行计算——表再多，
     * 响应大小也是常数。`totals` 是 KPI 五张卡；`databases` 是「按库的存储」
     * 条形图（全量库、存储降序）；`topTables` 是 Top 10；`histogram` 是
     * 平均文件大小的五档分布。
     */
    public record TableStatsSummary(Totals totals,
                                    List<DatabaseStat> databases,
                                    List<TableStat> topTables,
                                    Histogram histogram) {
    }

    /** 汇总读数。口径：无快照（`empty`）与缺统计（`unknown`）分开；小文件是表级平均判据。 */
    public record Totals(long tables,
                         long empty,
                         long unknown,
                         long files,
                         long size,
                         long records,
                         long deleteFiles,
                         long filesWithSize,
                         Double avgFileSize,
                         long smallTables,
                         long smallFiles,
                         long smallSize,
                         Double smallShare) {
    }

    /** 一个库的聚合（「按库的存储」条形图的一行）。 */
    public record DatabaseStat(String database,
                               long tables,
                               long files,
                               long size,
                               long small,
                               long unknown) {
    }

    /** 平均文件大小的五档分布：`buckets` 依次是 &lt;1MB / 1–10MB / 10–100MB / 100MB–1GB / ≥1GB。 */
    public record Histogram(List<Long> buckets, long counted, long unknown) {
    }

    /**
     * 一张表的当前快照统计。
     *
     * <p>{@code tableType} 是实体的原始取值（{@code PAIMON} / {@code ICEBERG} /
     * {@code format-table} / {@code object-table} / {@code lance-table}），
     * 判「是不是快照管理的表」由调用方做——映射规则属于前端（{@code utils/tableKind.js}），
     * 服务端不替它解释。
     */
    public record TableStat(String database,
                            String table,
                            String tableType,
                            Long snapshotId,
                            Long fileCount,
                            Long fileSizeInBytes,
                            Long recordCount,
                            Long deleteFileCount,
                            Long timeMillis) {
    }

    // ------------------------------------------------------------------ 权限
    //
    // 权限不在这里建模：官方 management API 把「资源 + access + principal」定义在
    // `PermissionDtos` 里，端点挂在 catalog 前缀下（`/v1/{prefix}/permissions`）。
    // 这里曾有一套 Polaris 形状的 catalog role / grant 模型，已随 catalog role 一并移除。

    // ------------------------------------------------------------------ 身份接入
    //
    // 「IdP 组 → 主体角色」的映射。它不是 Polaris 的一部分（Polaris 没有这个概念），
    // 是本服务端自己的扩展，因此形状按本工程的习惯来，不去凑一个不存在的规范。

    /**
     * 一条组映射。
     *
     * <p>{@code group} 是 IdP 里的组名，<b>精确匹配</b>（区分大小写、不 trim）。
     * {@code provider} 在单 IdP 部署下恒为 {@code default}，字段先留着，
     * 免得将来接第二个 IdP 时要改一次对外形状。
     *
     * <p>{@code enabled} 停用而不删：映射配错时的第一反应是删，但删掉之后
     * 「当时是怎么配的」就没了，回滚只能凭记忆。
     */
    public record GroupMapping(String id,
                               String provider,
                               String group,
                               String role,
                               Boolean enabled,
                               Long createTimestamp,
                               Long lastUpdateTimestamp,
                               Integer entityVersion) {
    }

    /** {@code GET /group-mappings}。 */
    public record GroupMappings(List<GroupMapping> mappings, PageMeta page) {
    }

    /**
     * {@code POST /group-mappings}。
     *
     * <p>{@code confirmAdminMapping} 是「危险映射」的二次确认：目标角色如果正被
     * 服务管理员持有，保存会要求显式置真。这不是防误操作的花招——把一组人映射到
     * 管理员角色，等于把「谁能进 IdP 的这个组」变成「谁能管理整个服务」。
     */
    public record CreateGroupMappingRequest(String provider,
                                            String group,
                                            String role,
                                            Boolean enabled,
                                            Boolean confirmAdminMapping) {
    }

    /** {@code PUT /group-mappings/{id}}：只改角色与启停，组名与 provider 不可变。 */
    public record UpdateGroupMappingRequest(Integer currentEntityVersion,
                                            String role,
                                            Boolean enabled,
                                            Boolean confirmAdminMapping) {
    }

    /** {@code POST /group-mappings/preview}：给定一组成员，回会得到哪些角色。 */
    public record GroupMappingPreviewRequest(String provider, List<String> groups) {
    }

    /**
     * 预览结果。**不写库**，只是把登录时会做的计算跑一遍。
     *
     * <p>{@code unmappedGroups} 与 {@code warnings} 分开：前者是「这些组没有配映射」，
     * 后者是「配了但有别的问题」（角色不存在、映射被停用）。
     * 合并成一句会让管理员分不清该新建还是该启用。
     */
    public record GroupMappingPreview(List<String> desiredRoles,
                                      List<String> unmappedGroups,
                                      List<String> unknownRoles,
                                      List<String> warnings) {
    }

    /** {@code POST /principals/{name}/sync} 的结果：这次动了哪些角色。 */
    public record PrincipalSyncResult(String principal,
                                      List<String> added,
                                      List<String> removed,
                                      Integer groupsCount,
                                      String skippedReason) {
    }

    /**
     * {@code POST /principals/{name}/sync} 的请求体。
     *
     * <p><b>为什么要显式传组名。</b>服务端不保存「这个人上次登录时在哪些组」——
     * 那是一次快照，存下来就多一份会过期的状态，而它唯一的用途是省掉这一次参数。
     * 因此手动重算要求调用方把组名带上：管理员的场景通常是「刚加完一条映射，
     * 想立刻生效」，而那条映射的组名就在他手上。
     */
    public record PrincipalSyncRequest(List<String> groups) {
    }

    /**
     * {@code GET /identity-provider}：把生效中的身份接入配置如实回给管理员。
     *
     * <p><b>没有写端点。</b>这些是部署配置（`application.yml` / ConfigMap / 环境变量），
     * 把它们做成可在线修改意味着「一个登录页的开关可以被另一个登录进来的人改掉」，
     * 而配置错误的表现是所有人都登不上——那需要重启与回滚能力，不是浏览器能承担的操作。
     */
    public record IdentityProvider(boolean enabled,
                                   boolean consoleRequired,
                                   String issuer,
                                   String clientId,
                                   String audience,
                                   String redirectUri,
                                   String scope,
                                   String principalClaim,
                                   String groupClaim,
                                   String displayNameClaim,
                                   String syncMode,
                                   boolean autoProvision,
                                   boolean allowAdoptLocal,
                                   List<String> allowedEmailDomains,
                                   List<String> defaultRoles) {
    }

    /**
     * 连通性测试的一步。
     *
     * @param step   {@code discovery} / {@code endpoints} / {@code jwks} / {@code mapping} /
     *               {@code provisioning}
     * @param status {@code OK} / {@code WARN} / {@code FAIL} / {@code SKIPPED}
     * @param detail 人话结论。<b>只含配置与错误分类，不含任何令牌或 claim 值</b>
     */
    public record IdentityProviderProbeStep(String step, String status, String detail) {
    }

    /**
     * {@code POST /identity-provider/probe} 的结果。
     *
     * @param verdict 总判：{@code OK} / {@code WARN} / {@code FAIL}（任一步 FAIL 即 FAIL）
     */
    public record IdentityProviderProbe(List<IdentityProviderProbeStep> steps, String verdict) {
    }

    /**
     * 一次登录的 claim 形状（**打码后的**，见 `OidcClaimSampleStore`）。
     *
     * @param groups             最多三个打码后的组名；总数看 {@code groupCount}
     * @param groupsClaimPresent 令牌里到底有没有那个 claim（空数组算「有」）
     * @param outcome            {@code success} / {@code not-provisioned} / {@code partial} /
     *                           {@code refused}
     */
    public record IdentityClaimSample(String principal,
                                      String issuer,
                                      String subject,
                                      String email,
                                      List<String> groups,
                                      int groupCount,
                                      boolean groupsClaimPresent,
                                      String outcome,
                                      long at) {
    }

    /**
     * {@code GET /identity-provider/claim-samples}。
     *
     * @param processLocal 恒为 true：样本存在**本进程内存**里。多副本部署时每个副本
     *                     只记得自己处理过的那几次登录，看到的人少不等于别人没登录过
     */
    public record IdentityClaimSamples(List<IdentityClaimSample> samples, boolean processLocal) {
    }

    /** catalog 类型的判别值，供校验错误信息复用。 */
    public static CatalogType parseCatalogType(String value) {
        return CatalogType.parse(value).orElse(CatalogType.INTERNAL);
    }
}
