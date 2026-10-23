package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.config.RequestContext;
import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.domain.entity.ColumnMaskEntity;
import io.github.melin.lakehouse.rest.domain.entity.DataPolicyEntity;
import io.github.melin.lakehouse.rest.domain.entity.DatabaseEntity;
import io.github.melin.lakehouse.rest.domain.entity.TableEntity;
import io.github.melin.lakehouse.rest.domain.repo.CatalogRepository;
import io.github.melin.lakehouse.rest.domain.repo.ColumnMaskRepository;
import io.github.melin.lakehouse.rest.domain.repo.DataPolicyRepository;
import io.github.melin.lakehouse.rest.domain.repo.DatabaseRepository;
import io.github.melin.lakehouse.rest.domain.repo.TableRepository;
import io.github.melin.lakehouse.rest.dto.DataPolicyDtos.MaskBinding;
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
            items.add(new PolicySummary(fullName(policy), policy.isEnabled(),
                    policy.getRowFilterJson() != null,
                    maskRepository.findByPolicyId(policy.getId()).size(),
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
        List<String> exempt = exemptOf(request);
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
        policy.setExemptJson(exempt.isEmpty() ? null : Json.write(exempt));
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
        List<ResolvedPolicy> resolved = new ArrayList<>();
        for (String name : names) {
            DataPolicyEntity policy = byName.get(name);
            if (policy == null || !policy.isEnabled()) {
                // 「没策略」与「已停用」对引擎等价：都放行，但状态如实分开回，排障能看出来
                resolved.add(new ResolvedPolicy(name,
                        policy == null ? "NONE" : "DISABLED", null, null, null,
                        List.of(), false, null));
                continue;
            }
            requireTableSelect(catalogName, name);
            if (exemptOf(policy).contains(principal)) {
                resolved.add(new ResolvedPolicy(name, "EXEMPT", policy.getEntityVersion(),
                        null, null, List.of(), true, policy.getEnforcedScope()));
                continue;
            }
            Map<String, Object> folded = policy.getRowFilterJson() == null ? null
                    : PolicyExpressions.foldPrincipal(
                            Json.read(policy.getRowFilterJson(), Map.class), principal);
            List<MaskBinding> bindings = new ArrayList<>();
            for (ColumnMaskEntity mask : maskRepository.findByPolicyId(policy.getId())) {
                bindings.add(new MaskBinding(mask.getColumnName(),
                        Json.read(mask.getMaskJson(), Map.class), mask.isAllowNullOnRequired()));
            }
            resolved.add(new ResolvedPolicy(name, "FOUND", policy.getEntityVersion(),
                    folded, policy.getRowFilterSql(), bindings, false, policy.getEnforcedScope()));
        }
        return new ResolveResult(principal, resolved);
    }

    // ------------------------------------------------------------------ 读路径收口（P0 拒绝版）

    /** 这张表有没有启用中的策略（预览 / 文件下载的 403 判据；§6、§7.2）。 */
    @Transactional(readOnly = true)
    public boolean hasActivePolicy(String catalogName, String databaseName, String tableName) {
        return findPolicy(catalogName, databaseName, tableName)
                .map(DataPolicyEntity::isEnabled).orElse(false);
    }

    /**
     * 拒绝带策略表的预览（403 + 说明）。
     *
     * <p>P0 不做「按策略预览」（需要服务端求值器，P1 交付）；红线是<b>不做「悄悄返回
     * 明文」</b>——那等于控制台成了后门。存储凭据直读（§7.1）本层拦不住，控制台
     * 至少不该主动递刀。
     */
    public void requirePreviewAllowed(String catalogName, String databaseName, String tableName) {
        if (hasActivePolicy(catalogName, databaseName, tableName)) {
            throw ApiException.forbidden("preview is not available for this table:"
                    + " a row filter / column mask policy is active (design §6);"
                    + " use a controlled engine session instead");
        }
    }

    /**
     * 该 catalog 下所有启用策略的表的物理根路径——catalog 级文件下载的收口判据
     * （§7.2：一个 parquet 原文件就是全表明文）。调用方把下载请求的相对路径与
     * 这些前缀比对，命中即 403。
     *
     * <p>策略按三段名绑定，路径要从 {@code lakehouse_table.path_value} 反查；
     * 表可能已经不存在（策略还没被 DROP 钩子清掉）——跳过即可，文件本来也打不开。
     */
    @Transactional(readOnly = true)
    public List<String> protectedTablePaths(String catalogName) {
        List<String> paths = new ArrayList<>();
        for (DataPolicyEntity policy : policyRepository.findByCatalogName(catalogName)) {
            if (!policy.isEnabled()) {
                continue;
            }
            tableEntityOf(catalogName, policy.getDatabaseName(), policy.getTableName())
                    .map(TableEntity::getPath)
                    .filter(path -> path != null && !path.isBlank())
                    .ifPresent(paths::add);
        }
        return paths;
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
                policy.getRowFilterSql(), masks, exemptOf(policy), policy.getEnforcedScope(),
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
