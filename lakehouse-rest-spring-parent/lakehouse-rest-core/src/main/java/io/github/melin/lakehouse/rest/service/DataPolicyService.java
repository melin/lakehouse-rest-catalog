package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.config.RequestContext;
import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.domain.entity.ColumnMaskEntity;
import io.github.melin.lakehouse.rest.domain.entity.DataPolicyEntity;
import io.github.melin.lakehouse.rest.domain.entity.DatabaseEntity;
import io.github.melin.lakehouse.rest.domain.entity.TableEntity;
import io.github.melin.lakehouse.rest.domain.entity.TagMaskBindingEntity;
import io.github.melin.lakehouse.rest.domain.repo.CatalogRepository;
import io.github.melin.lakehouse.rest.domain.repo.ColumnMaskRepository;
import io.github.melin.lakehouse.rest.domain.repo.DataPolicyRepository;
import io.github.melin.lakehouse.rest.domain.repo.DatabaseRepository;
import io.github.melin.lakehouse.rest.domain.repo.TableRepository;
import io.github.melin.lakehouse.rest.dto.DataPolicyDtos.MaskBinding;
import io.github.melin.lakehouse.rest.dto.DataPolicyDtos.MaskConflict;
import io.github.melin.lakehouse.rest.dto.DataPolicyDtos.MaskEntry;
import io.github.melin.lakehouse.rest.dto.DataPolicyDtos.MaskUpsert;
import io.github.melin.lakehouse.rest.dto.DataPolicyDtos.PolicyDetail;
import io.github.melin.lakehouse.rest.dto.DataPolicyDtos.PolicyList;
import io.github.melin.lakehouse.rest.dto.DataPolicyDtos.PolicySummary;
import io.github.melin.lakehouse.rest.dto.DataPolicyDtos.PolicyUpsertRequest;
import io.github.melin.lakehouse.rest.dto.DataPolicyDtos.ResolveResult;
import io.github.melin.lakehouse.rest.dto.DataPolicyDtos.ResolvedPolicy;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.Access;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.ResourceType;
import io.github.melin.lakehouse.rest.dto.TypeDtos;
import io.github.melin.lakehouse.rest.event.CatalogEvent;
import io.github.melin.lakehouse.rest.event.CatalogEventPublisher;
import io.github.melin.lakehouse.rest.event.CatalogEventType;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.Json;
import io.github.melin.lakehouse.rest.support.Paging;
import io.github.melin.lakehouse.rest.support.PolicyExpressions;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 行过滤 / 列脱敏策略的服务（设计见 {@code docs/row-filter-column-mask-design.md} §5）。
 *
 * <p>职责三分，与「引擎是唯一执行点」的分层（§5.1）一一对应：
 * <ul>
 *   <li><b>存与版本</b>：两张表 + 乐观锁（{@code entityVersion} 一列两用：改策略时比对、
 *       下发时作 etag）；</li>
 *   <li><b>校验</b>：建策略时 fail-closed（白名单、类型规则、列存在性，§5.3.3）——
 *       引擎侧的 fail-closed 兜的是「校验之后表又变了」，两道闸各管一段；</li>
 *   <li><b>下发</b>：{@link #resolve} 按调用者个性化（{@code $principal} 折叠成字面量、
 *       豁免名单内返回空策略），引擎侧不需要知道豁免逻辑的存在（§1.3 第 6 行）。</li>
 * </ul>
 *
 * <p><b>「没策略」与「拿不到」必须分开</b>（§5.4）：resolve 对没策略（或已停用）的表返回
 * {@code status=NONE}——这是正常响应，引擎放行；服务内部错误是 5xx，引擎侧 fail-closed
 * 拒读。把两者混成一个响应，等于把「本来就没保护」和「保护失联」说成一件事，
 * 而两者的处置完全相反。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataPolicyService {

    /** 强制范围声明的唯一取值（P0）；{@code advisory} 保留给将来的「仅提示」模式（§7.1）。 */
    public static final String SCOPE_ENGINE = "engine";

    /** 单次 resolve 最多问多少张表：一条 SQL 扫几张表是常态，几百张是异常用法。 */
    public static final int MAX_RESOLVE_TABLES = 64;

    /** 单张表的豁免名单上限。名单太长说明建模错了——该用组，而不是枚举人。 */
    public static final int MAX_EXEMPT_PRINCIPALS = 100;

    /** resolve / 管理端点里表名的形状：{@code database.table}（database 可含点号），catalog 在路径上。 */
    private static final int MIN_TABLE_NAME_SEGMENTS = 2;

    private final DataPolicyRepository policyRepository;
    private final ColumnMaskRepository maskRepository;
    private final CatalogRepository catalogRepository;
    private final DatabaseRepository databaseRepository;
    private final TableRepository tableRepository;
    private final AuthorizationService authorizationService;
    private final CatalogEventPublisher events;
    private final RestServerProperties properties;
    private final Clock clock;
    /**
     * 标签 → mask 的展开（§8.1）。它只做纯计算，读路径上被四条路径共用：
     * 引擎 {@link #resolve}、控制台预览 {@link #previewPolicyOf}、主体试算
     * {@link #policyForPrincipal}，以及文件下载 / 分区列举的收口判据。
     */
    private final TagMaskExpander tagExpander;

    // ------------------------------------------------------------------ 管理面：读

    /** 一张表的策略详情（含豁免名单——它需要 TABLE:ALTER，判权在控制器）。没配过返回 {@code null}。 */
    @Transactional(readOnly = true)
    public PolicyDetail detail(String catalogName, String databaseName, String tableName) {
        requireTableExists(catalogName, databaseName, tableName);
        return toDetail(findPolicy(catalogName, databaseName, tableName).orElse(null));
    }

    /**
     * 策略总览的分页列表（控制台「数据保护」页）。按 catalog 过滤、可按表名反查。
     *
     * <p>全量载入后内存切页（{@link Paging#paged}）：策略是治理对象，量级是「几十」，
     * 为它建数据库级分页是把分页复杂度花在永远不会大的集合上——与优化策略列表同一取舍。
     */
    @Transactional(readOnly = true)
    public PolicyList list(String catalogName, String refTable, Integer page, Integer pageSize) {
        List<DataPolicyEntity> all = catalogName == null || catalogName.isBlank()
                ? policyRepository.findAll()
                : policyRepository.findByCatalogName(catalogName);
        List<PolicySummary> items = new ArrayList<>();
        for (DataPolicyEntity policy : all) {
            if (refTable != null && !refTable.isBlank() && !matchesRefTable(policy, refTable)) {
                continue;
            }
            // 条件文本与脱敏列名一起取：总览页要把「条件是什么、哪些列被改了」
            // 平铺成 chip（判权在控制器已收到 TABLE:ALTER / 服务管理员）
            List<ColumnMaskEntity> masks = maskRepository.findByPolicyId(policy.getId());
            List<String> maskColumns = new ArrayList<>(masks.size());
            for (ColumnMaskEntity mask : masks) {
                maskColumns.add(mask.getColumnName());
            }
            items.add(new PolicySummary(fullName(policy), policy.isEnabled(),
                    policy.getRowFilterJson() != null,
                    policy.getRowFilterSql(), maskColumns, hiddenOf(policy),
                    masks.size(),
                    exemptOf(policy).size(), policy.getEntityVersion(),
                    policy.getUpdatedBy(), policy.getUpdatedAt()));
        }
        Paging.Paged<PolicySummary> paged = Paging.paged(items, page, pageSize);
        return paged == null
                ? new PolicyList(items, null)
                : new PolicyList(paged.items(), paged.meta());
    }

    // ------------------------------------------------------------------ 管理面：写

    /**
     * 创建或整份更新一张表的策略（PUT 语义：提交一套完整策略，不是逐列打补丁）。
     *
     * <p>校验 fail-closed（§5.3.3）：行过滤必须是布尔表达式（外层自动包
     * {@code coalesce(expr, false)}）；每条 mask 的结果类型必须与列同类；
     * 引用不存在的列、嵌套字段、白名单外的函数全部拒绝。校验失败的请求
     * <b>什么都不落</b>——半个策略比没有策略更危险：它会让使用者以为某列已受保护。
     *
     * <p>并发：更新必须带 {@code baseVersion}，与当前版本不符回 409。两个治理员
     * 同时改同一份口径必须人来裁决，SQL 侧同样不自动重试（§13.6：静默重试可能把
     * 别人刚删的条件加回来）。
     */
    @Transactional
    public PolicyDetail upsert(String catalogName, String databaseName, String tableName,
                               PolicyUpsertRequest request, String principal) {
        if (!properties.getPolicy().isEnabled()) {
            throw ApiException.forbidden("data policy management is disabled"
                    + " (lakehouse.rest.policy.enabled=false) — reads are still allowed");
        }
        if (request == null) {
            throw ApiException.badRequest("request body must not be empty");
        }
        TableEntity table = requireTableExists(catalogName, databaseName, tableName);
        PolicyExpressions.SchemaView schema = PolicyExpressions.SchemaView.of(
                Json.read(table.getSchemaDoc() == null ? "{}" : table.getSchemaDoc(),
                        TypeDtos.Schema.class));

        // ---- 先解析校验，全部通过才碰存储
        PolicyExpressions.ValidatedRowFilter rowFilter = request.rowFilter() == null
                ? null : PolicyExpressions.parseRowFilter(request.rowFilter(), schema);
        List<ParsedMask> masks = parseMasks(request, schema);
        List<String> hidden = hiddenOf(request, schema, masks);
        List<String> exempt = exemptOf(request);
        List<String> subjects = subjectsOf(request);
        String scope = SCOPE_ENGINE;

        // ---- 落库
        long now = clock.millis();
        DataPolicyEntity policy = findPolicy(catalogName, databaseName, tableName).orElse(null);
        boolean created = policy == null;
        if (created) {
            if (request.baseVersion() != null) {
                throw versionMismatch(table.getName(), request.baseVersion(), 0);
            }
            policy = new DataPolicyEntity();
            policy.setCatalogName(catalogName);
            policy.setDatabaseName(databaseName);
            policy.setTableName(tableName);
            policy.setFullNameHash(sha256(fullName(catalogName, databaseName, tableName)));
            policy.setEntityVersion(0);
            policy.setEnforcedScope(scope);
            policy.markCreated(principal, now);
        } else {
            if (request.baseVersion() == null || request.baseVersion() != policy.getEntityVersion()) {
                throw versionMismatch(table.getName(),
                        request.baseVersion() == null ? -1 : request.baseVersion(),
                        policy.getEntityVersion());
            }
        }
        if (request.enabled() != null) {
            policy.setEnabled(request.enabled());
        }
        policy.setRowFilterJson(rowFilter == null ? null : Json.write(rowFilter.ast()));
        policy.setRowFilterSql(rowFilter == null ? null : rowFilter.sql());
        policy.setHiddenJson(hidden.isEmpty() ? null : Json.write(hidden));
        policy.setExemptJson(exempt.isEmpty() ? null : Json.write(exempt));
        policy.setSubjectsJson(subjects.isEmpty() ? null : Json.write(subjects));
        policy.setEntityVersion(policy.getEntityVersion() + 1);
        policy.touch(principal, now);
        policyRepository.save(policy);

        // mask 整批替换：PUT 的语义就是「提交完整策略」（包括「这次少了一列」= 删掉那条 mask）
        maskRepository.deleteByPolicyId(policy.getId());
        for (ParsedMask parsed : masks) {
            ColumnMaskEntity entity = new ColumnMaskEntity();
            entity.setPolicyId(policy.getId());
            entity.setColumnName(parsed.column());
            entity.setMaskJson(Json.write(parsed.parsed().ast()));
            entity.setMaskSql(parsed.parsed().sql());
            entity.setAllowNullOnRequired(parsed.allowNullOnRequired());
            entity.markCreated(principal, now);
            maskRepository.save(entity);
        }

        events.publish(CatalogEventType.POLICY_CHANGED, catalogName, CatalogEvent.TARGET_TABLE,
                databaseName + "." + tableName,
                Map.of("action", created ? "created" : "updated",
                        "version", policy.getEntityVersion(),
                        "rowFilter", rowFilter != null,
                        "maskedColumns", masks.size(),
                        "hiddenColumns", hidden.size(),
                        "exemptCount", exempt.size(),
                        "enabled", policy.isEnabled()));
        return toDetail(policy);
    }

    /** 删除整条策略（物理删除，记审计）。「停用」是 enabled=false，只在控制台提供（§5.2）。 */
    @Transactional
    public void delete(String catalogName, String databaseName, String tableName, String principal) {
        if (!properties.getPolicy().isEnabled()) {
            throw ApiException.forbidden("data policy management is disabled"
                    + " (lakehouse.rest.policy.enabled=false) — reads are still allowed");
        }
        requireTableExists(catalogName, databaseName, tableName);
        DataPolicyEntity policy = findPolicy(catalogName, databaseName, tableName).orElse(null);
        if (policy == null) {
            throw ApiException.managementNotExist(
                    io.github.melin.lakehouse.rest.support.ResourceType.TABLE, tableName);
        }
        maskRepository.deleteByPolicyId(policy.getId());
        policyRepository.delete(policy);
        events.publish(CatalogEventType.POLICY_CHANGED, catalogName, CatalogEvent.TARGET_TABLE,
                databaseName + "." + tableName,
                Map.of("action", "dropped", "version", policy.getEntityVersion()));
    }

    // ------------------------------------------------------------------ 引擎侧：解析下发

    /**
     * 批量解析策略（引擎的拉取通道，§5.4）。逐表判权：调用者对某张表没有
     * {@code SELECT} 时，那张表直接从结果里缺席——策略内容（谓词字面量、列清单）
     * 本身就是敏感信息，不能变成无权者的读口。真正的门禁仍在执行路径上（§6.4 三不），
     * 这里挡的是泄露面。
     *
     * <p>按调用者个性化：{@code $principal} 在这里折成字面量（引擎缓存因此必须按主体
     * 分片）；豁免名单内的调用者拿到 {@code exempted=true} + 空表达式。
     *
     * <p><b>P3 起这里也展开标签绑定</b>（§8.1）：每张表的 mask 是「显式 mask + 标签
     * 展开」的合并结果，被放弃的候选随 {@code conflicts} 一起下发。因此
     * {@code FOUND} 不再等价于「有 {@code DataPolicyEntity}」——一张只有标签保护的表
     * 也回 {@code FOUND}（{@code version=null}）。标签展开的读次数：绑定按 catalog 一次、
     * 标签按表一次；catalog 没配绑定时整段短路，开销与 P3 之前一致。
     */
    @Transactional(readOnly = true)
    public ResolveResult resolve(String catalogName, List<String> tables) {
        if (tables == null || tables.isEmpty()) {
            throw ApiException.badRequest("tables must not be empty");
        }
        if (tables.size() > MAX_RESOLVE_TABLES) {
            throw ApiException.badRequest("too many tables in one resolve request (max "
                    + MAX_RESOLVE_TABLES + ")");
        }
        String principal = RequestContext.auditPrincipal();
        // $principal_roles 折叠输入（§5.3.4.1）：一批表共用一份名单，算一次
        List<String> principalRoles = authorizationService.principalRoleNames(principal);
        List<String> names = new ArrayList<>();
        Map<String, String> hashByName = new LinkedHashMap<>();
        for (String tableRef : tables) {
            if (tableRef == null || tableRef.isBlank()) {
                throw ApiException.badRequest("table names must not be empty");
            }
            String name = tableRef.trim();
            names.add(name);
            hashByName.put(name, sha256(catalogName + "." + name));
        }
        // 一次批量把这一批表的策略全部拿回来：一条 SQL 常扫多张表，逐表查询会把
        // 往返次数乘上去（引擎侧还有 60s TTL 缓存兜着，这里不追求极致）
        Map<String, String> nameByHash = new HashMap<>();
        hashByName.forEach((name, hash) -> nameByHash.put(hash, name));
        Map<String, DataPolicyEntity> byName = new HashMap<>();
        for (DataPolicyEntity policy : policyRepository.findByFullNameHashIn(hashByName.values())) {
            byName.put(nameByHash.get(policy.getFullNameHash()), policy);
        }
        // 本 catalog 的启用绑定（catalog 级规则，一次取全量）。空列表时下面每一处都直接
        // 短路——没配绑定的部署，读路径的查询次数与 P3 之前逐字一致。
        List<TagMaskBindingEntity> tagBindings = tagExpander.bindingsOf(catalogName);
        List<ResolvedPolicy> resolved = new ArrayList<>();
        for (String name : names) {
            DataPolicyEntity policy = byName.get(name);
            if (policy == null || !policy.isEnabled()) {
                // 「没策略」与「已停用」对**策略**等价，但对标签绑定不等价：停用的是那条策略，
                // 不是标签。所以两条路都要再问一次展开结果——一张从没配过策略的表，
                // 只要列被打了标、catalog 又配了绑定，照样要下发 mask（这就是 §8.1 说的
                // 「新增列被识别打标后自动纳入覆盖，不用改策略」）。
                MergedMasks merged = tagBindings.isEmpty()
                        ? MergedMasks.empty()
                        : mergedMasks(catalogName, TableRef.of(name), null, tagBindings);
                if (merged.masks().isEmpty()) {
                    // 没策略也没标签保护：正常响应，引擎放行
                    resolved.add(new ResolvedPolicy(name,
                            policy == null ? "NONE" : "DISABLED", null, null, null,
                            List.of(), List.of(), false, null, merged.conflicts()));
                    continue;
                }
                // 标签展开出的 mask 也要判 SELECT：否则无权者能从「这张表回了 FOUND」
                // 反推出「它里面有敏感列」——策略内容（列清单）本身就是敏感信息
                requireTableSelect(catalogName, name);
                resolved.add(new ResolvedPolicy(name, "FOUND", null, null, null,
                        merged.masks(), List.of(), false, null, merged.conflicts()));
                continue;
            }
            requireTableSelect(catalogName, name);
            if (exemptOf(policy).contains(principal)) {
                // 豁免是「这张表对这个人不设防」——对**表**的判定，不是对某条规则的判定，
                // 因此**连标签绑定一起豁免**。刻意写明：这里看起来像漏了展开，其实是不展开。
                // 反过来，若豁免只豁免显式 mask，「临时给某人开明文」这个最常见的运维动作
                // 就只能靠删绑定实现，而删绑定会影响所有人。
                resolved.add(new ResolvedPolicy(name, "EXEMPT", policy.getEntityVersion(),
                        null, null, List.of(), List.of(), true, policy.getEnforcedScope(),
                        List.of()));
                continue;
            }
            // 主体白名单（§13.2 的 FOR 子句）：名单非空且调用者不在名单里 → 这条策略不生效。
            // 但它限定的是**这条策略**的适用主体，不是「这张表对这个人不设防」，
            // 所以标签绑定仍然下发。若混为一谈，一条只给某部门用的行过滤策略
            // 会顺带关掉全局的 PII 脱敏——这个副作用没人预期得到。
            if (!subjectsOf(policy).isEmpty() && !subjectsOf(policy).contains(principal)) {
                MergedMasks tagOnly = tagBindings.isEmpty()
                        ? MergedMasks.empty()
                        : mergedMasks(catalogName, TableRef.of(name), null, tagBindings);
                resolved.add(tagOnly.masks().isEmpty()
                        ? new ResolvedPolicy(name, "NONE", null, null, null,
                                List.of(), List.of(), false, null, tagOnly.conflicts())
                        : new ResolvedPolicy(name, "FOUND", null, null, null,
                                tagOnly.masks(), List.of(), false, null, tagOnly.conflicts()));
                continue;
            }
            Map<String, Object> folded = policy.getRowFilterJson() == null ? null
                    : PolicyExpressions.foldPrincipal(
                            Json.read(policy.getRowFilterJson(), Map.class), principal, principalRoles);
            MergedMasks merged = mergedMasks(catalogName, TableRef.of(name), policy, tagBindings);
            resolved.add(new ResolvedPolicy(name, "FOUND", policy.getEntityVersion(),
                    folded, policy.getRowFilterSql(), merged.masks(), hiddenOf(policy),
                    false, policy.getEnforcedScope(), merged.conflicts()));
        }
        return new ResolveResult(principal, resolved);
    }

    // ------------------------------------------------------------------ 读路径收口

    /**
     * 预览路径的策略解析（设计 §6 P1 / §8.1）。返回 {@code null} 表示「按原样预览」——
     * 什么保护都没有，或调用者在豁免名单里；否则携带折叠好 {@code $principal} 的
     * 行过滤 AST 与 mask 集，由预览服务逐行求值。
     *
     * <p>与引擎的 {@link #resolve} 共用同一份判定链（启用 / 豁免 / 白名单 / 折叠 / 标签展开），
     * 双端语义天然一致；差别只在执行点：引擎把表达式编译进 Catalyst，预览逐行解释
     * （{@code PolicyExpressionEvaluator}）。
     *
     * <p><b>没有策略不等于没有保护。</b>标签绑定是 catalog 级的，一张从没配过策略的表
     * 只要列被打了标、catalog 配了绑定，预览照样要按 mask 求值——否则「打标即脱敏」
     * 只在配过策略的表上成立，而这两件事本来是独立的。
     */
    @Transactional(readOnly = true)
    public PreviewPolicy previewPolicyOf(String catalogName, String databaseName,
                                         String tableName) {
        DataPolicyEntity policy = findPolicy(catalogName, databaseName, tableName).orElse(null);
        if (policy == null || !policy.isEnabled()) {
            // 停用的只是那条策略，标签绑定仍然生效（与 resolve 的 DISABLED 分支同一口径）
            return previewPolicyOfTags(catalogName, databaseName, tableName);
        }
        return previewPolicyOfEntity(policy, RequestContext.auditPrincipal());
    }

    /**
     * 主体试算（控制台 mockup 画面 4）：按<b>指定的</b>主体（不是当前调用者）判定策略
     * 命中状态并折叠 {@code $principal}。判定链与 {@link #previewPolicyOf} 逐条一致，
     * 只是主体来自请求——这是「用他的眼睛看一次」的治理工具，调用者必须是能看到
     * 策略内容的人（判权在控制器，{@code TABLE:ALTER}）。
     *
     * <p>{@code status} 四态与 {@link ResolvedPolicy} 同语义：NONE（没策略或白名单外）、
     * DISABLED、EXEMPT、FOUND。与 resolve 一样，**没有策略但有标签保护**时也回 FOUND
     * （{@code version=null}）——试算的结果必须和这个人真去查询时看到的一样，
     * 否则这个工具就是在骗人。
     */
    @Transactional(readOnly = true)
    public PrincipalPolicy policyForPrincipal(String catalogName, String databaseName,
                                              String tableName, String principal) {
        requireTableExists(catalogName, databaseName, tableName);
        DataPolicyEntity policy = findPolicy(catalogName, databaseName, tableName).orElse(null);
        if (policy == null || !policy.isEnabled()) {
            PreviewPolicy tags = previewPolicyOfTags(catalogName, databaseName, tableName);
            return tags == null
                    ? new PrincipalPolicy(policy == null ? "NONE" : "DISABLED",
                            policy == null ? null : policy.getEntityVersion(),
                            policy == null ? null : policy.getRowFilterSql(), null)
                    : new PrincipalPolicy("FOUND", null, null, tags);
        }
        if (exemptOf(policy).contains(principal)) {
            // 豁免连标签绑定一起豁免（与 resolve 同一口径，理由见那里的注释）
            return new PrincipalPolicy("EXEMPT", policy.getEntityVersion(),
                    policy.getRowFilterSql(), null);
        }
        List<String> subjects = subjectsOf(policy);
        if (!subjects.isEmpty() && !subjects.contains(principal)) {
            // 白名单外：这条策略对该主体不生效，但标签绑定仍然生效（§13.2 FOR 子句只限定策略）
            PreviewPolicy tags = previewPolicyOfTags(catalogName, databaseName, tableName);
            return tags == null
                    ? new PrincipalPolicy("NONE", null, null, null)
                    : new PrincipalPolicy("FOUND", null, null, tags);
        }
        return new PrincipalPolicy("FOUND", policy.getEntityVersion(), policy.getRowFilterSql(),
                previewPolicyOfEntity(policy, principal));
    }

    /** 主体试算的结果：命中状态 + 命中时按主体折叠好的预览策略（{@code policy} 可为 {@code null}）。 */
    public record PrincipalPolicy(String status, Integer version, String rowFilterSql,
                                  PreviewPolicy policy) {
    }

    /**
     * 判定链的公共实现：豁免 / 白名单判定后折叠 {@code $principal} 与 {@code $principal_roles}，
     * 再把显式 mask 与标签展开的 mask 合并。
     */
    private PreviewPolicy previewPolicyOfEntity(DataPolicyEntity policy, String principal) {
        if (exemptOf(policy).contains(principal)) {
            return null;
        }
        // 与 resolve 同一条判定链：白名单非空且调用者不在名单里 → 策略对本调用者不生效
        List<String> subjects = subjectsOf(policy);
        if (!subjects.isEmpty() && !subjects.contains(principal)) {
            return null;
        }
        Map<String, Object> rowFilter = policy.getRowFilterJson() == null ? null
                : PolicyExpressions.foldPrincipal(
                        Json.read(policy.getRowFilterJson(), Map.class), principal,
                        authorizationService.principalRoleNames(principal));
        TableRef ref = new TableRef(policy.getDatabaseName(), policy.getTableName());
        MergedMasks merged = mergedMasks(policy.getCatalogName(), ref, policy, null);
        return new PreviewPolicy(policy.getFullNameHash(), policy.getEntityVersion(),
                rowFilter, indexByColumn(merged.masks()), hiddenOf(policy), merged.conflicts());
    }

    /**
     * 只有标签保护（没有启用中的策略）时的预览策略；一条 mask 都没有则返回 {@code null}。
     *
     * <p>{@code version} 用 {@code 0}：没有策略就没有版本号，而调用方（预览服务）
     * 只把它当缓存标识用。写 {@code -1} 之类的哨兵更糟——它会看起来像一个真实的版本值。
     */
    private PreviewPolicy previewPolicyOfTags(String catalogName, String databaseName,
                                              String tableName) {
        TableRef ref = new TableRef(databaseName, tableName);
        MergedMasks merged = mergedMasks(catalogName, ref, null, null);
        if (merged.masks().isEmpty()) {
            return null;
        }
        return new PreviewPolicy(sha256(fullName(catalogName, databaseName, tableName)), 0,
                null, indexByColumn(merged.masks()), List.of(), merged.conflicts());
    }

    private static Map<String, Map<String, Object>> indexByColumn(List<MaskBinding> masks) {
        Map<String, Map<String, Object>> byColumn = new LinkedHashMap<>();
        for (MaskBinding mask : masks) {
            byColumn.put(mask.column(), mask.mask());
        }
        return Map.copyOf(byColumn);
    }

    /**
     * 显式 mask 与标签展开的 mask 合成一份（§8.1 / §8.2）。
     *
     * <p><b>顺序是「显式在前、标签在后」</b>：同一列不会同时出现在两处（显式赢，
     * 标签那侧记冲突），所以这个顺序只影响未命中显式 mask 的列，但固定下来
     * 让下发报文可逐字比对。
     *
     * @param policy      表策略；{@code null} = 这张表没有策略，只有标签可展开
     * @param tagBindings 该 catalog 的启用绑定；{@code null} 时由 expander 自己取
     */
    private MergedMasks mergedMasks(String catalogName, TableRef ref, DataPolicyEntity policy,
                                    List<TagMaskBindingEntity> tagBindings) {
        List<MaskBinding> masks = new ArrayList<>();
        Set<String> explicitColumns = new LinkedHashSet<>();
        List<String> hidden = List.of();
        if (policy != null) {
            for (ColumnMaskEntity mask : maskRepository.findByPolicyId(policy.getId())) {
                explicitColumns.add(mask.getColumnName());
                masks.add(new MaskBinding(mask.getColumnName(),
                        Json.read(mask.getMaskJson(), Map.class), mask.isAllowNullOnRequired(),
                        MaskBinding.ORIGIN_EXPLICIT));
            }
            hidden = hiddenOf(policy);
        }
        TagMaskExpander.Expansion expansion = tagExpander.expand(catalogName, ref.database(),
                ref.table(), tagBindings, explicitColumns, hidden);
        for (TagMaskExpander.TagMask tagMask : expansion.masks().values()) {
            // allowNullOnRequired 恒 false：绑定表没有这一列（§4.5），而它表达的是
            // 「我知道这个 mask 可能置空、仍要对非空列应用」。绑定要在未知的列上生效，
            // 无法预先确认；真落在非空列上时引擎编译期会拒绝——那是 fail-closed，不是明文。
            masks.add(new MaskBinding(tagMask.column(), tagMask.mask(), false,
                    MaskBinding.ORIGIN_TAG));
        }
        return new MergedMasks(masks, expansion.conflicts());
    }

    /** 一次合并的结果：下发的 mask 列表（显式在前）+ 被放弃的候选。 */
    private record MergedMasks(List<MaskBinding> masks, List<MaskConflict> conflicts) {

        static MergedMasks empty() {
            return new MergedMasks(List.of(), List.of());
        }
    }

    /** {@code database.table} 的拆分结果（表名可含点号，取最后一个点，与 {@link #requireTableSelect} 同口径）。 */
    private record TableRef(String database, String table) {

        static TableRef of(String tableRef) {
            int lastDot = tableRef.lastIndexOf('.');
            if (lastDot <= 0 || lastDot == tableRef.length() - 1) {
                throw ApiException.badRequest("table must be written as database.table, got: " + tableRef);
            }
            return new TableRef(tableRef.substring(0, lastDot), tableRef.substring(lastDot + 1));
        }
    }

    /**
     * 预览求值所需的全部输入（§6 P1）。{@code rowFilter} 已按调用者折叠
     * {@code $principal}；masks 键是列名、值是 mask AST（标准集或逃生口表达式）。
     *
     * <p>{@code conflicts} 让控制台能回答「这列我配了绑定，为什么脱敏成另一个样子」——
     * 与 resolve 的响应同一份口径，两个读取面不能对同一件事给出不同答案。
     */
    public record PreviewPolicy(String fullNameHash, int version,
                                Map<String, Object> rowFilter,
                                Map<String, Map<String, Object>> masks,
                                List<String> hiddenColumns,
                                List<MaskConflict> conflicts) {
    }

    /** 这张表有没有启用中的**策略**（不含标签绑定；组合判据见 {@link #hasActiveProtection}）。 */
    @Transactional(readOnly = true)
    public boolean hasActivePolicy(String catalogName, String databaseName, String tableName) {
        return findPolicy(catalogName, databaseName, tableName)
                .map(DataPolicyEntity::isEnabled).orElse(false);
    }

    /**
     * 这张表有没有任何生效中的**保护**：策略，或标签绑定展开出的 mask。
     *
     * <p><b>分区列举与文件下载必须用这个而不是 {@link #hasActivePolicy}。</b>
     * 只判策略的话，一张「没配过策略、但列被打了标、catalog 配了绑定」的表会把
     * parquet 原文件直接递出去——而同一张表的预览明明已经脱敏了。两个入口对
     * 「这张表有没有受保护」给出不同答案，等于把标签绑定当成不存在。
     */
    @Transactional(readOnly = true)
    public boolean hasActiveProtection(String catalogName, String databaseName, String tableName) {
        return hasActivePolicy(catalogName, databaseName, tableName)
                || tagExpander.hasAnyMask(catalogName, databaseName, tableName);
    }

    /**
     * 这张表有没有任何会改变列值的保护——**快照统计打码的判据**（§7.3）。
     *
     * <p>快照的 {@code statistics} 字段是服务端唯一可能携带<b>列级</b> min/max/nullCount
     * 的载体（Paimon 优化器算完统计后会随提交上报，序列化串里就是逐字段的边界值）。
     * 它是不透明的序列化串，没法按列拆开挑着下发，所以只有「有 mask」才整体置空：
     * 纯行过滤策略不改列值，统计口径不受影响，照常下发（§6 的分工）。统计的行数 /
     * 文件数 / 字节数聚合值不在打码范围——它们不反推任何列的原值。
     *
     * <p><b>标签绑定也算「有 mask」</b>：一张只靠标签脱敏的表，如果统计照常下发，
     * 那么被 mask 的列的最大值 / 最小值会从 {@code statistics} 里漏回去——
     * 而它看起来只是一串不透明的序列化数据，没人会想到那里有原值的边界。
     *
     * <p>签名收 {@link TableEntity} 而不是三段名：调用方（快照读路径）手里就是
     * 表实体，三段名要从 id 反查 catalog / database 两层，收口点不该让每个调用方
     * 自己再走一遍。
     */
    @Transactional(readOnly = true)
    public boolean hasActiveMask(TableEntity table) {
        String catalogName = catalogRepository.findById(table.getCatalogId())
                .map(CatalogEntity::getPrefix).orElse(null);
        String databaseName = databaseRepository.findById(table.getDatabaseId())
                .map(DatabaseEntity::getName).orElse(null);
        if (catalogName == null || databaseName == null) {
            return false;
        }
        boolean explicit = findPolicy(catalogName, databaseName, table.getName())
                .filter(DataPolicyEntity::isEnabled)
                .map(policy -> !maskRepository.findByPolicyId(policy.getId()).isEmpty())
                .orElse(false);
        return explicit
                || tagExpander.hasAnyMask(catalogName, databaseName, table.getName());
    }

    /**
     * 拒绝带保护的表做分区列举与文件下载（403 + 说明）。
     *
     * <p>P1 起预览改为按策略求值（见 {@link #previewPolicyOf}），但**分区列举与
     * 文件下载维持拒绝**：分区值是 transform 后的值（{@code day(ts)}），与 mask 的
     * 「同类型结果」规则对不上；文件下载更直接——一个 parquet 原文件就是全表明文。
     * 红线是<b>不做「悄悄返回明文」</b>——那等于控制台成了后门。存储凭据直读
     * （§7.1）本层拦不住，控制台至少不该主动递刀。
     *
     * <p>判据用 {@link #hasActiveProtection}（策略或标签绑定）：只看策略的话，
     * 上一句的「红线」在标签脱敏这条路上就不成立了。
     */
    public void requirePreviewAllowed(String catalogName, String databaseName, String tableName) {
        if (hasActiveProtection(catalogName, databaseName, tableName)) {
            throw ApiException.forbidden("this request is not available for this table:"
                    + " a row filter / column mask policy or a tag-based mask is active"
                    + " (design §6 / §8.1); data preview applies the policy per row, but"
                    + " partition listing and file downloads are refused;"
                    + " use a controlled engine session instead");
        }
    }

    /**
     * 该 catalog 下所有受保护的表的物理根路径——catalog 级文件下载的收口判据
     * （§7.2：一个 parquet 原文件就是全表明文）。调用方把下载请求的相对路径与
     * 这些前缀比对，命中即 403。
     *
     * <p>两个来源都要算：启用的策略，以及「有列标签命中启用绑定」的表。
     * 后者按标签表反查（{@code database.table}），不再逐表展开 mask——这里判的是
     * 「这个文件值不值得保护」，不是「保护成什么样」。
     *
     * <p>策略按三段名绑定，路径要从 {@code lakehouse_table.path_value} 反查；
     * 表可能已经不存在（策略还没被 DROP 钩子清掉）——跳过即可，文件本来也打不开。
     */
    @Transactional(readOnly = true)
    public List<String> protectedTablePaths(String catalogName) {
        // 用 LinkedHashSet：两个来源可能指向同一张表（既有策略又有标签），
        // 调用方按前缀比对虽然不怕重复，但重复项会让这条列表在排障时难以阅读
        Set<String> paths = new LinkedHashSet<>();
        for (DataPolicyEntity policy : policyRepository.findByCatalogName(catalogName)) {
            if (!policy.isEnabled()) {
                continue;
            }
            tableEntityOf(catalogName, policy.getDatabaseName(), policy.getTableName())
                    .map(TableEntity::getPath)
                    .filter(path -> path != null && !path.isBlank())
                    .ifPresent(paths::add);
        }
        for (String tableRef : tagExpander.tablesWithMask(catalogName)) {
            TableRef ref = TableRef.of(tableRef);
            tableEntityOf(catalogName, ref.database(), ref.table())
                    .map(TableEntity::getPath)
                    .filter(path -> path != null && !path.isBlank())
                    .ifPresent(paths::add);
        }
        return new ArrayList<>(paths);
    }

    // ------------------------------------------------------------------ 表生命周期联动（§5.2 取舍 4）

    /** 删表 / 注销后级联删策略（事件监听器调用）。同名重建因此<b>不继承</b>旧策略。 */
    @Transactional
    public void onTableDropped(String catalogName, String databaseName, String tableName) {
        findPolicy(catalogName, databaseName, tableName).ifPresent(policy -> {
            maskRepository.deleteByPolicyId(policy.getId());
            policyRepository.delete(policy);
            log.info("dropped data policy of {}.{}.{} along with the table",
                    catalogName, databaseName, tableName);
        });
    }

    /** 表改名 / 换库后让策略跟随（三段名列与摘要列一起改，保持一致）。 */
    @Transactional
    public void onTableRenamed(String catalogName, String fromDatabase, String fromTable,
                               String toDatabase, String toTable) {
        findPolicy(catalogName, fromDatabase, fromTable).ifPresent(policy -> {
            policy.setDatabaseName(toDatabase);
            policy.setTableName(toTable);
            policy.setFullNameHash(sha256(fullName(catalogName, toDatabase, toTable)));
            policy.setEntityVersion(policy.getEntityVersion() + 1);
            policy.touch(RequestContext.auditPrincipal(), clock.millis());
            policyRepository.save(policy);
            log.info("moved data policy of {}.{}.{} to {}.{}", catalogName, fromDatabase,
                    fromTable, catalogName, toDatabase, toTable);
        });
    }

    // ------------------------------------------------------------------ 内部

    private record ParsedMask(String column, PolicyExpressions.ValidatedMask parsed,
                              boolean allowNullOnRequired) {
    }

    private List<ParsedMask> parseMasks(PolicyUpsertRequest request,
                                        PolicyExpressions.SchemaView schema) {
        List<ParsedMask> masks = new ArrayList<>();
        if (request.masks() == null) {
            return masks;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (MaskUpsert mask : request.masks()) {
            if (mask == null || mask.column() == null || mask.column().isBlank()) {
                throw ApiException.badRequest("each mask requires a column name");
            }
            if (!seen.add(mask.column())) {
                throw ApiException.badRequest("duplicate mask for column " + mask.column()
                        + " — a column has at most one mask");
            }
            PolicyExpressions.ValidatedMask parsed =
                    PolicyExpressions.parseMask(mask.mask(), mask.column(), schema);
            boolean allowOnRequired = mask.allowNullOnRequired() != null && mask.allowNullOnRequired();
            boolean nullProducing = isNullProducing(mask.mask(), parsed.ast());
            if (nullProducing && !allowOnRequired
                    && schema.requiredColumns().contains(mask.column())) {
                // SPIP 的 fail-closed 清单：REPLACE_WITH_NULL on a required column aborts。
                // 直接硬拒会让「身份证号置空」做不成，所以留一次显式确认（§5.3.3）
                throw ApiException.badRequest("mask for required column " + mask.column()
                        + " can produce NULL — set allowNullOnRequired to confirm"
                        + " (design §5.3.3, ALLOW NULL ON REQUIRED)");
            }
            if (allowOnRequired && !nullProducing) {
                throw ApiException.badRequest("allowNullOnRequired only applies to the"
                        + " replace_with_null mask (or expressions that can produce NULL)");
            }
            masks.add(new ParsedMask(mask.column(), parsed, allowOnRequired));
        }
        return masks;
    }

    /**
     * 这个 mask 能不能产出 NULL。标准集里只有 replace_with_null；逃生口表达式
     * （case_when 无 else、coalesce 首参为 NULL……）一律保守按「可能产出 NULL」算——
     * 误伤的代价是多勾一次确认，漏放的代价是 NOT NULL 列被悄悄置空。
     */
    private static boolean isNullProducing(Object rawMask, Map<String, Object> ast) {
        if (rawMask instanceof Map<?, ?> raw && raw.containsKey("expr")) {
            return true;
        }
        return "replace_with_null".equals(ast.get("fn"));
    }

    /**
     * 整列不可见清单的解析校验（P3，设计 §5.8）。列必须真实存在（嵌套字段写法
     * 自然不在顶层列清单里，同样拒绝——与 mask / 行过滤的嵌套口径一致）；与 mask
     * 同列互斥（两条可见性规则叠加在同一列上说不清谁生效，建策略时拒绝而不是
     * 执行时猜）。行过滤**允许**引用清单内的列——治理员按密级列过滤、用户看不到
     * 该列，是这套能力的标准用法（引擎侧行过滤读的是 scan 原值，不经过可见性裁剪）。
     */
    private List<String> hiddenOf(PolicyUpsertRequest request, PolicyExpressions.SchemaView schema,
                                  List<ParsedMask> masks) {
        if (request.hiddenColumns() == null) {
            return List.of();
        }
        Set<String> maskTargets = new LinkedHashSet<>();
        for (ParsedMask mask : masks) {
            maskTargets.add(mask.column());
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (String column : request.hiddenColumns()) {
            if (column == null || column.isBlank()) {
                throw ApiException.badRequest("hidden columns must not be empty");
            }
            String name = column.trim();
            if (!distinct.add(name)) {
                throw ApiException.badRequest("duplicate hidden column " + name);
            }
            if (!schema.columns().contains(name)) {
                throw ApiException.badRequest("hidden column '" + name + "' does not exist in the"
                        + " table schema (nested fields are not supported); the policy is"
                        + " rejected as a whole — nothing was saved");
            }
            if (maskTargets.contains(name)) {
                throw ApiException.badRequest("column " + name + " cannot be both hidden and"
                        + " masked: a hidden column is never returned, so a mask on it has no"
                        + " observable effect — choose one of the two");
            }
        }
        return new ArrayList<>(distinct);
    }

    private static List<String> hiddenOf(DataPolicyEntity policy) {
        if (policy.getHiddenJson() == null || policy.getHiddenJson().isBlank()) {
            return List.of();
        }
        List<?> raw = Json.read(policy.getHiddenJson(), List.class);
        List<String> names = new ArrayList<>();
        for (Object value : raw) {
            if (value != null) {
                names.add(String.valueOf(value));
            }
        }
        return names;
    }

    private void requireTableSelect(String catalogName, String tableRef) {
        int lastDot = tableRef.lastIndexOf('.');
        if (lastDot <= 0 || lastDot == tableRef.length() - 1) {
            throw ApiException.badRequest("table must be written as database.table, got: " + tableRef);
        }
        String database = tableRef.substring(0, lastDot);
        String table = tableRef.substring(lastDot + 1);
        List<String> namespace = List.of(database.split("\\."));
        authorizationService.require(catalogName, ResourceType.TABLE, namespace, table,
                Access.SELECT, "resolve the data policy of " + tableRef);
    }

    private TableEntity requireTableExists(String catalogName, String databaseName, String tableName) {
        return tableEntityOf(catalogName, databaseName, tableName)
                .orElseThrow(() -> ApiException.tableNotExist(tableName));
    }

    private Optional<TableEntity> tableEntityOf(String catalogName, String databaseName,
                                                String tableName) {
        CatalogEntity catalog = catalogRepository.findByPrefix(catalogName)
                .orElseThrow(() -> ApiException.managementNotExist(
                        io.github.melin.lakehouse.rest.support.ResourceType.CATALOG, catalogName));
        Optional<DatabaseEntity> database =
                databaseRepository.findByCatalogIdAndName(catalog.getId(), databaseName);
        if (database.isEmpty()) {
            return Optional.empty();
        }
        return tableRepository.findByCatalogIdAndDatabaseIdAndName(catalog.getId(),
                database.get().getId(), tableName);
    }

    private Optional<DataPolicyEntity> findPolicy(String catalogName, String databaseName,
                                                  String tableName) {
        return policyRepository.findByFullNameHash(
                sha256(fullName(catalogName, databaseName, tableName)));
    }

    private static boolean matchesRefTable(DataPolicyEntity policy, String refTable) {
        return policy.getTableName().equals(refTable)
                || fullName(policy).endsWith("." + refTable);
    }

    private static ApiException versionMismatch(String tableName, int expected, int actual) {
        return ApiException.entityVersionMismatch(
                io.github.melin.lakehouse.rest.support.ResourceType.TABLE,
                tableName, expected, actual);
    }

    private static String fullName(String catalog, String database, String table) {
        return catalog + "." + database + "." + table;
    }

    private static String fullName(DataPolicyEntity policy) {
        return fullName(policy.getCatalogName(), policy.getDatabaseName(), policy.getTableName());
    }

    private static List<String> exemptOf(DataPolicyEntity policy) {
        if (policy.getExemptJson() == null || policy.getExemptJson().isBlank()) {
            return List.of();
        }
        List<?> raw = Json.read(policy.getExemptJson(), List.class);
        List<String> principals = new ArrayList<>();
        for (Object value : raw) {
            if (value != null) {
                principals.add(String.valueOf(value));
            }
        }
        return principals;
    }

    private static List<String> exemptOf(PolicyUpsertRequest request) {
        if (request.exemptPrincipals() == null) {
            return List.of();
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (String principal : request.exemptPrincipals()) {
            if (principal != null && !principal.isBlank()) {
                distinct.add(principal.trim());
            }
        }
        if (distinct.size() > MAX_EXEMPT_PRINCIPALS) {
            throw ApiException.badRequest("exempt principals exceed " + MAX_EXEMPT_PRINCIPALS
                    + " — model roles instead of enumerating people");
        }
        return new ArrayList<>(distinct);
    }

    /** 主体白名单（{@code FOR} 子句落库的形态；空 = 全部主体生效）。 */
    private static List<String> subjectsOf(DataPolicyEntity policy) {
        if (policy.getSubjectsJson() == null || policy.getSubjectsJson().isBlank()) {
            return List.of();
        }
        List<?> raw = Json.read(policy.getSubjectsJson(), List.class);
        List<String> names = new ArrayList<>();
        for (Object value : raw) {
            if (value != null) {
                names.add(String.valueOf(value));
            }
        }
        return names;
    }

    private static List<String> subjectsOf(PolicyUpsertRequest request) {
        if (request.subjects() == null) {
            return List.of();
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (String name : request.subjects()) {
            if (name != null && !name.isBlank()) {
                distinct.add(name.trim());
            }
        }
        if (distinct.size() > MAX_EXEMPT_PRINCIPALS) {
            throw ApiException.badRequest("policy subjects exceed " + MAX_EXEMPT_PRINCIPALS
                    + " — model roles instead of enumerating people");
        }
        return new ArrayList<>(distinct);
    }

    private PolicyDetail toDetail(DataPolicyEntity policy) {
        if (policy == null) {
            return null;
        }
        List<MaskEntry> masks = new ArrayList<>();
        for (ColumnMaskEntity mask : maskRepository.findByPolicyId(policy.getId())) {
            masks.add(new MaskEntry(mask.getColumnName(),
                    mask.getMaskJson() == null ? null : Json.read(mask.getMaskJson(), Map.class),
                    mask.getMaskSql(), mask.isAllowNullOnRequired()));
        }
        return new PolicyDetail(fullName(policy), policy.isEnabled(),
                policy.getRowFilterJson() == null
                        ? null : Json.read(policy.getRowFilterJson(), LinkedHashMap.class),
                policy.getRowFilterSql(), masks, hiddenOf(policy),
                exemptOf(policy), subjectsOf(policy),
                policy.getEnforcedScope(),
                policy.getEntityVersion(), policy.getCreatedBy(), policy.getCreatedAt(),
                policy.getUpdatedBy(), policy.getUpdatedAt());
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is always available", exception);
        }
    }
}
