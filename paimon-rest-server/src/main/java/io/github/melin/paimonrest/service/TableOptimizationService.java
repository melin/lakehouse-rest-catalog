package io.github.melin.paimonrest.service;

import io.github.melin.paimonrest.domain.entity.CatalogEntity;
import io.github.melin.paimonrest.domain.entity.DatabaseEntity;
import io.github.melin.paimonrest.domain.entity.TableEntity;
import io.github.melin.paimonrest.domain.entity.TableOptimizationEntity;
import io.github.melin.paimonrest.domain.repo.TableOptimizationRepository;
import io.github.melin.paimonrest.dto.ManagementDtos;
import io.github.melin.paimonrest.dto.OptimizationDtos;
import io.github.melin.paimonrest.dto.OptimizationDtos.ActionPolicy;
import io.github.melin.paimonrest.dto.OptimizationDtos.ActionPolicyUpdate;
import io.github.melin.paimonrest.dto.OptimizationDtos.CatalogSettings;
import io.github.melin.paimonrest.dto.OptimizationDtos.TablePolicy;
import io.github.melin.paimonrest.dto.OptimizationDtos.UpdateCatalogSettingsRequest;
import io.github.melin.paimonrest.dto.OptimizationDtos.UpdateTablePolicyRequest;
import io.github.melin.paimonrest.service.OptimizationJobService.BatchTable;
import io.github.melin.paimonrest.support.ApiException;
import io.github.melin.paimonrest.support.OptimizationOptions;
import io.github.melin.paimonrest.support.OptimizationOptions.Action;
import io.github.melin.paimonrest.support.Paging;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 表存储优化的策略服务：读策略、写策略、catalog 级默认值、立即执行。
 *
 * <p><b>「默认设置 / 自定义」两档怎么落地。</b>表策略只存用户显式改过的键
 * （差异），所以「用默认设置」不是一个布尔开关，而是「差异为空」。
 * 对外的 {@link ActionPolicy} 同时给出差异与生效值，控制台据此渲染两档的选中态与
 * 「现在实际会用什么参数」——两边共用同一份合并结果（{@link OptimizationConfigResolver}），
 * 不各算一遍。
 *
 * <p>可优化的表有一道明确边界：只有 Paimon 表、且不是外部表。前者是因为三项动作都是
 * Paimon 的提交语义（快照、manifest、孤儿文件），另外三种表类型根本没有这些东西；
 * 后者是因为外部表的位置不归本服务所有（{@code register} 登记或建表自带 {@code path}），
 * 对它做 compaction 等于在别人的目录里写文件。
 */
@Service
@RequiredArgsConstructor
public class TableOptimizationService {

    /** 调度间隔下限 1 分钟：更短的周期没有意义（优化本身是低频动作），只会空转。 */
    public static final int MIN_SCHEDULE_SECONDS = 60;
    /** 上限 1 天：低于这个频率的「定时优化」不如直接用控制台的立即执行。 */
    public static final int MAX_SCHEDULE_SECONDS = 86400;
    public static final int DEFAULT_SCHEDULE_SECONDS = 3600;

    private final TableOptimizationRepository policyRepository;
    private final TableLookup tableLookup;
    private final ManagementCatalogService catalogService;
    private final OptimizationConfigResolver configResolver;
    private final OptimizationJobService jobService;
    private final Clock clock;

    // ------------------------------------------------------------------ 表策略

    /** 读表策略；还没配过的表返回一份「默认关闭、参数全用默认值」的策略。 */
    @Transactional
    public TablePolicy policy(String prefix, String databaseName, String tableName) {
        DatabaseEntity database = tableLookup.requireDatabase(prefix, databaseName);
        TableEntity table = tableLookup.requireTable(prefix, databaseName, tableName);
        return toPolicy(prefix, database.getName(), table, policyRepository.findByTableId(table.getId()).orElse(null));
    }

    /**
     * 写表策略。
     *
     * <p>首次启用时把调度游标设成「立即到期」：用户按下开关的意图就是「现在开始优化」，
     * 让他再等一个调度周期没有道理。这也是唯一一处由写操作决定调度时机的逻辑。
     */
    @Transactional
    public TablePolicy updatePolicy(String prefix, String databaseName, String tableName,
                                    UpdateTablePolicyRequest request, String principal) {
        DatabaseEntity database = tableLookup.requireDatabase(prefix, databaseName);
        TableEntity table = tableLookup.requireTable(prefix, databaseName, tableName);
        requireOptimizable(table);
        if (request == null) {
            throw ApiException.badRequest("request body must not be empty");
        }
        long now = clock.millis();
        TableOptimizationEntity policy = policyRepository.findByTableId(table.getId()).orElse(null);
        if (policy == null) {
            policy = newPolicy(table, principal, now);
        }
        if (request.enabled() != null) {
            policy.setEnabled(request.enabled());
            if (request.enabled() && policy.getNextRunAt() == null) {
                policy.setNextRunAt(now);
            }
        }
        if (request.scheduleIntervalSeconds() != null) {
            int interval = request.scheduleIntervalSeconds();
            if (interval < MIN_SCHEDULE_SECONDS || interval > MAX_SCHEDULE_SECONDS) {
                throw ApiException.badRequest("scheduleIntervalSeconds must be between "
                        + MIN_SCHEDULE_SECONDS + " and " + MAX_SCHEDULE_SECONDS);
            }
            policy.setScheduleIntervalSeconds(interval);
        }
        if (request.actions() != null) {
            for (ActionPolicyUpdate update : request.actions()) {
                Action action = Action.parse(update.action());
                if (action == null) {
                    throw ApiException.badRequest("Unknown optimization action: " + update.action()
                            + ", supported: " + OptimizationOptions.actionNames());
                }
                if (update.enabled() != null) {
                    switch (action) {
                        case COMPACT -> policy.setCompactionEnabled(update.enabled());
                        case EXPIRE -> policy.setSnapshotExpireEnabled(update.enabled());
                        case ORPHAN -> policy.setOrphanRemoveEnabled(update.enabled());
                    }
                }
                if (update.config() != null) {
                    OptimizationOptions.validate(action, update.config());
                    Map<String, String> config = new LinkedHashMap<>(update.config());
                    switch (action) {
                        case COMPACT -> policy.setCompactionConfig(config);
                        case EXPIRE -> policy.setSnapshotExpireConfig(config);
                        case ORPHAN -> policy.setOrphanRemoveConfig(config);
                    }
                }
            }
        }
        policy.touch(principal, now);
        policyRepository.save(policy);
        return toPolicy(prefix, database.getName(), table, policy);
    }

    // ------------------------------------------------------------------ 表的优化历史

    /**
     * 一张表参与过的批次明细，最近的在前（表详情页「存储优化」页签的三块历史）。
     *
     * <p>与 {@link #policy} 一样只做读：不校验「这张表能不能被优化」。理由是同一条——
     * 一张今天被改成外部表的表，它过去跑过的历史仍然是事实，翻不出来的话，
     * 用户看到的是「昨天的记录凭空消失了」。
     */
    @Transactional(readOnly = true)
    public OptimizationDtos.TableHistory history(String prefix, String databaseName, String tableName) {
        // requireTable 内部已经解析过 database（不存在时抛 404），不必再查一次
        TableEntity table = tableLookup.requireTable(prefix, databaseName, tableName);
        return new OptimizationDtos.TableHistory(prefix + "." + databaseName + "." + tableName,
                jobService.itemsOfTable(table.getId()));
    }

    // ------------------------------------------------------------------ catalog 默认值

    /** catalog 级默认设置：原样给出 {@code optimization.*} 属性，加一份拆好的生效值。 */
    @Transactional(readOnly = true)
    public CatalogSettings settings(String prefix) {
        CatalogEntity catalog = catalogService.require(prefix);
        return new CatalogSettings(prefix, optimizationProperties(catalog), defaultActions(catalog));
    }

    /**
     * 更新 catalog 级默认设置。
     *
     * <p>逐键合并而不是整体替换：控制台改一个键不该把运维用属性文件写进去的其它键抹掉。
     * 传空值表示清除该键（回落内置默认）。
     *
     * <p>写回走 {@link ManagementCatalogService#update}，因此沿用 catalog 的
     * {@code entityVersion} 乐观并发——两个人同时改同一 catalog 的属性，
     * 后提交的那个会拿到 409 而不是静默覆盖。
     */
    @Transactional
    public CatalogSettings updateSettings(String prefix, UpdateCatalogSettingsRequest request, String principal) {
        CatalogEntity catalog = catalogService.require(prefix);
        if (request == null || request.properties() == null || request.properties().isEmpty()) {
            throw ApiException.badRequest("properties must not be empty");
        }
        Map<String, String> merged = new LinkedHashMap<>(catalog.getProperties());
        List<Action> touched = new ArrayList<>();
        for (Map.Entry<String, String> entry : request.properties().entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim();
            Action action = requireSettingsKey(key);
            if (!touched.contains(action)) {
                touched.add(action);
            }
            if (entry.getValue() == null || entry.getValue().isBlank()) {
                merged.remove(key);
            } else {
                merged.put(key, entry.getValue().trim());
            }
        }
        // 只校验这次碰到的动作：catalog 属性是自由键空间，别人手工写进去的非法值
        // 不该把「改另一个动作」的请求一起挡掉。
        for (Action action : touched) {
            OptimizationOptions.validate(action, OptimizationOptions.catalogDefaults(merged, action));
        }
        ManagementDtos.UpdateCatalogRequest update = new ManagementDtos.UpdateCatalogRequest(
                catalog.getEntityVersion(), merged, catalog.getStorageConfig());
        catalogService.update(prefix, update);
        return settings(prefix);
    }

    // ------------------------------------------------------------------ 立即执行

    /**
     * 立即优化一张表（控制台的「立即优化」）。
     *
     * <p>手动执行**不受总开关约束**：开关管的是「要不要自动定期做」，而用户点这个按钮
     * 就是在说「现在就做」。但动作必须落在已启用的三项之内——请求里指定了被关掉的动作时
     * 直接 400，而不是「悄悄跑一遍用户已经决定不做的动作」。
     */
    @Transactional
    public OptimizationDtos.Job run(String prefix, String databaseName, String tableName,
                                    List<String> requestedActions, String principal) {
        DatabaseEntity database = tableLookup.requireDatabase(prefix, databaseName);
        TableEntity table = tableLookup.requireTable(prefix, databaseName, tableName);
        requireOptimizable(table);
        TableOptimizationEntity policy = policyRepository.findByTableId(table.getId()).orElse(null);
        List<Action> enabled = enabledActions(policy);
        List<Action> actions = requestedActions == null || requestedActions.isEmpty()
                ? enabled
                : OptimizationOptions.parseActions(requestedActions);
        for (Action action : actions) {
            if (!enabled.contains(action)) {
                throw ApiException.badRequest("action " + action.wire() + " is not enabled for this table");
            }
        }
        if (actions.isEmpty()) {
            throw ApiException.badRequest("this table has no enabled optimization action");
        }
        String fullName = prefix + "." + database.getName() + "." + table.getName();
        return jobService.detail(jobService.createJob(table.getCatalogId(),
                OptimizationJobService.TRIGGER_MANUAL,
                List.of(new BatchTable(table.getId(), fullName, actions)), null, principal).getId(), false);
    }

    // ------------------------------------------------------------------ 内部

    /** 表的优化能力边界，写操作与立即执行都要过这一关。 */
    public static void requireOptimizable(TableEntity table) {
        if (!"PAIMON".equalsIgnoreCase(table.getTableType())) {
            throw ApiException.badRequest("storage optimization is only available for Paimon tables,"
                    + " but this table is " + table.getTableType());
        }
        if (table.isExternal()) {
            throw ApiException.badRequest("storage optimization is not available for external tables:"
                    + " their location is not owned by this service");
        }
    }

    /** 表已启用的动作；没有策略行时按三项的默认开关（孤儿文件清理默认关闭）。 */
    public static List<Action> enabledActions(TableOptimizationEntity policy) {
        List<Action> actions = new ArrayList<>();
        for (Action action : Action.values()) {
            boolean enabled = policy == null ? OptimizationOptions.defaultEnabled(action) : switch (action) {
                case COMPACT -> policy.isCompactionEnabled();
                case EXPIRE -> policy.isSnapshotExpireEnabled();
                case ORPHAN -> policy.isOrphanRemoveEnabled();
            };
            if (enabled) {
                actions.add(action);
            }
        }
        return actions;
    }

    private TableOptimizationEntity newPolicy(TableEntity table, String principal, long now) {
        TableOptimizationEntity policy = new TableOptimizationEntity();
        policy.setId(Paging.newId());
        policy.setTableId(table.getId());
        policy.setCatalogId(table.getCatalogId());
        policy.setEnabled(false);
        policy.setScheduleIntervalSeconds(DEFAULT_SCHEDULE_SECONDS);
        policy.setCompactionEnabled(true);
        policy.setSnapshotExpireEnabled(true);
        policy.setOrphanRemoveEnabled(false);
        policy.markCreated(principal, now);
        policy.setUpdatedAt(now);
        return policy;
    }

    private TablePolicy toPolicy(String catalog, String databaseName, TableEntity table,
                                 TableOptimizationEntity policy) {
        Map<String, String> catalogProperties = configResolver.catalogProperties(table.getCatalogId());
        List<ActionPolicy> actions = new ArrayList<>();
        for (Action action : Action.values()) {
            Map<String, String> config = policy == null ? new LinkedHashMap<>() : switch (action) {
                case COMPACT -> policy.getCompactionConfig();
                case EXPIRE -> policy.getSnapshotExpireConfig();
                case ORPHAN -> policy.getOrphanRemoveConfig();
            };
            boolean enabled = policy == null ? OptimizationOptions.defaultEnabled(action) : switch (action) {
                case COMPACT -> policy.isCompactionEnabled();
                case EXPIRE -> policy.isSnapshotExpireEnabled();
                case ORPHAN -> policy.isOrphanRemoveEnabled();
            };
            actions.add(new ActionPolicy(action.wire(), action.displayName(), enabled,
                    config == null ? new LinkedHashMap<>() : new LinkedHashMap<>(config),
                    configResolver.resolve(action, catalogProperties, config),
                    config == null || config.isEmpty()));
        }
        boolean supported = "PAIMON".equalsIgnoreCase(table.getTableType()) && !table.isExternal();
        String reason = null;
        if (!"PAIMON".equalsIgnoreCase(table.getTableType())) {
            reason = "只有 Paimon 表支持存储优化，当前表类型为 " + table.getTableType();
        } else if (table.isExternal()) {
            reason = "外部表的位置不归本服务所有，不支持存储优化";
        }
        return new TablePolicy(catalog, databaseName, table.getName(),
                policy != null && policy.isEnabled(),
                policy == null ? DEFAULT_SCHEDULE_SECONDS : policy.getScheduleIntervalSeconds(),
                policy == null ? null : policy.getNextRunAt(),
                policy == null ? null : policy.getLastJobId(),
                supported, reason, actions);
    }

    private Map<String, String> optimizationProperties(CatalogEntity catalog) {
        Map<String, String> properties = new LinkedHashMap<>();
        catalog.getProperties().forEach((key, value) -> {
            if (key != null && key.startsWith(OptimizationOptions.CATALOG_PREFIX)) {
                properties.put(key, value);
            }
        });
        return properties;
    }

    private List<ActionPolicy> defaultActions(CatalogEntity catalog) {
        List<ActionPolicy> policies = new ArrayList<>();
        for (Action action : Action.values()) {
            Map<String, String> catalogConfig =
                    OptimizationOptions.catalogDefaults(catalog.getProperties(), action);
            policies.add(new ActionPolicy(action.wire(), action.displayName(), true,
                    catalogConfig,
                    configResolver.resolve(action, catalog.getProperties(), Map.of()),
                    catalogConfig.isEmpty()));
        }
        return policies;
    }

    /** 校验 catalog 属性键的形状：{@code optimization.<动作>.<参数名>}。 */
    private Action requireSettingsKey(String key) {
        if (!key.startsWith(OptimizationOptions.CATALOG_PREFIX)) {
            throw ApiException.badRequest("optimization setting must start with '"
                    + OptimizationOptions.CATALOG_PREFIX + "': " + key);
        }
        String rest = key.substring(OptimizationOptions.CATALOG_PREFIX.length());
        int dot = rest.indexOf('.');
        if (dot <= 0 || dot == rest.length() - 1) {
            throw ApiException.badRequest("optimization setting must look like '"
                    + OptimizationOptions.CATALOG_PREFIX + "<action>.<option>': " + key);
        }
        Action action = Action.parse(rest.substring(0, dot));
        if (action == null) {
            throw ApiException.badRequest("Unknown optimization action '" + rest.substring(0, dot)
                    + "' in setting key: " + key + ", supported: " + OptimizationOptions.actionNames());
        }
        // 参数名必须是该项动作认得的键，否则它会静默落库、静默不生效。
        if (!OptimizationOptions.isKnownOption(action, rest.substring(dot + 1))) {
            throw ApiException.badRequest("Unknown optimization option in setting key: " + key);
        }
        return action;
    }
}
