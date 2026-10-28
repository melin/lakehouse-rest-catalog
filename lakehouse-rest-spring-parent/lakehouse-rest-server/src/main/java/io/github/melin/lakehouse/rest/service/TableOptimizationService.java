package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.domain.entity.DatabaseEntity;
import io.github.melin.lakehouse.rest.domain.entity.OptimizationJobItemEntity;
import io.github.melin.lakehouse.rest.domain.entity.TableEntity;
import io.github.melin.lakehouse.rest.domain.entity.TableOptimizationEntity;
import io.github.melin.lakehouse.rest.domain.repo.CatalogRepository;
import io.github.melin.lakehouse.rest.domain.repo.DatabaseRepository;
import io.github.melin.lakehouse.rest.domain.repo.OptimizationJobItemRepository;
import io.github.melin.lakehouse.rest.domain.repo.TableOptimizationRepository;
import io.github.melin.lakehouse.rest.domain.repo.TableRepository;
import io.github.melin.lakehouse.rest.dto.ManagementDtos;
import io.github.melin.lakehouse.rest.dto.OptimizationDtos;
import io.github.melin.lakehouse.rest.dto.OptimizationDtos.ActionPolicy;
import io.github.melin.lakehouse.rest.dto.OptimizationDtos.ActionPolicyUpdate;
import io.github.melin.lakehouse.rest.dto.OptimizationDtos.CatalogSettings;
import io.github.melin.lakehouse.rest.dto.OptimizationDtos.TablePolicy;
import io.github.melin.lakehouse.rest.dto.OptimizationDtos.UpdateCatalogSettingsRequest;
import io.github.melin.lakehouse.rest.dto.OptimizationDtos.UpdateTablePolicyRequest;
import io.github.melin.lakehouse.rest.event.CatalogEvent;
import io.github.melin.lakehouse.rest.event.CatalogEventPublisher;
import io.github.melin.lakehouse.rest.event.CatalogEventType;
import io.github.melin.lakehouse.rest.service.OptimizationJobService.BatchTable;
import io.github.melin.lakehouse.rest.service.exec.ExecutionClusterService;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.CatalogFormat;
import io.github.melin.lakehouse.rest.support.LanceOptimizationOptions;
import io.github.melin.lakehouse.rest.support.OptimizationOptions;
import io.github.melin.lakehouse.rest.support.OptimizationOptions.Action;
import io.github.melin.lakehouse.rest.support.OptimizationStatus;
import io.github.melin.lakehouse.rest.support.Paging;
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
 * <p><b>可优化的表：Paimon、Iceberg、Lance 三种格式。</b>三项动作的语义在三种格式上
 * 是一一对应的（小文件合并 / 过期快照清理 / 孤儿文件清理），差别只在参数与执行语句——
 * 参数分派见 {@link OptimizationOptions#defaults(io.github.melin.lakehouse.rest.support.CatalogFormat,
 * OptimizationOptions.Action)}，执行分派在优化任务侧。
 *
 * <p><b>外部表的规则按格式不同</b>（{@link #requireOptimizable}）：Paimon / Iceberg 的
 * 外部表被拒——它的位置不归本服务所有（{@code register} 登记或建表自带 {@code path}），
 * 对它做 compaction 等于在别人的目录里写文件。**Lance 是例外**：Lance 的数据全部由客户端
 * 写入，{@code register} 登记进来的表天然「外部」，照搬这条会让 Lance 表一张也开不了，
 * 而 400 的文案（「位置不归本服务所有」）还会把人往权限问题上引。
 */
@Service
@RequiredArgsConstructor
public class TableOptimizationService {

    /** 调度间隔下限 1 分钟：更短的周期没有意义（优化本身是低频动作），只会空转。 */
    public static final int MIN_SCHEDULE_SECONDS = 60;
    /** 上限 1 天：低于这个频率的「定时优化」不如直接用控制台的立即执行。 */
    public static final int MAX_SCHEDULE_SECONDS = 86400;
    public static final int DEFAULT_SCHEDULE_SECONDS = 3600;

    /** 明细的活跃状态：同一张表最多出现在一条活跃明细里（不变式 I1）。 */
    private static final List<String> ACTIVE_ITEMS =
            List.of(OptimizationStatus.Item.PENDING.name(), OptimizationStatus.Item.RUNNING.name());

    private final TableOptimizationRepository policyRepository;
    private final OptimizationJobItemRepository itemRepository;
    private final CatalogRepository catalogRepository;
    private final DatabaseRepository databaseRepository;
    private final TableRepository tableRepository;
    private final ManagementCatalogService catalogService;
    private final OptimizationConfigResolver configResolver;
    private final OptimizationJobService jobService;
    private final ExecutionClusterService clusters;
    private final CatalogEventPublisher events;
    private final Clock clock;

    // ------------------------------------------------------------------ 表策略

    /** 读表策略；还没配过的表返回一份「默认关闭、参数全用默认值」的策略。 */
    @Transactional
    public TablePolicy policy(String prefix, String databaseName, String tableName) {
        TableRef ref = requireTable(prefix, databaseName, tableName);
        // 读路径不抛「格式认不出」：一张旧数据或脏数据里的表仍然要能打开策略页，
        // 页面上写清「不支持，因为表格式是 X」比一个 400 更有用（写路径才硬拒，
        // 见 requireOptimizable）。format 为 null 时 toPolicy 按 Paimon 渲染但置 supported=false。
        CatalogFormat format = recognizedFormatOf(ref.table());
        return toPolicy(prefix, ref.database().getName(), ref.table(),
                policyRepository.findByTableId(ref.table().getId()).orElse(null), format);
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
        TableRef ref = requireTable(prefix, databaseName, tableName);
        TableEntity table = ref.table();
        requireOptimizable(table);
        CatalogFormat format = formatOf(table);
        if (request == null) {
            throw ApiException.badRequest("request body must not be empty");
        }
        long now = clock.millis();
        TableOptimizationEntity policy = policyRepository.findByTableId(table.getId()).orElse(null);
        if (policy == null) {
            policy = newPolicy(table, principal, now, format);
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
                // 不支持的动作在这里就拒掉：允许「存进去」等于让一个一跑就失败的开关存在，
                // 而它的失败原因会是「服务端没实现」——那是 bug 的说法，不是能力边界
                // （能力边界由 ActionPolicy.supported 表达，见 toPolicy）。
                if (!OptimizationOptions.isSupported(format, action)) {
                    throw ApiException.badRequest(OptimizationOptions.unsupportedReason(format, action));
                }
                if (update.enabled() != null) {
                    switch (action) {
                        case COMPACT -> policy.setCompactionEnabled(update.enabled());
                        case EXPIRE -> policy.setSnapshotExpireEnabled(update.enabled());
                        case ORPHAN -> policy.setOrphanRemoveEnabled(update.enabled());
                    }
                }
                if (update.config() != null) {
                    OptimizationOptions.validate(format, action, update.config());
                    assertRetentionAboveCommitInterval(format, action, update.config(), table);
                    Map<String, String> config = new LinkedHashMap<>(update.config());
                    switch (action) {
                        case COMPACT -> policy.setCompactionConfig(config);
                        case EXPIRE -> policy.setSnapshotExpireConfig(config);
                        case ORPHAN -> policy.setOrphanRemoveConfig(config);
                    }
                }
            }
        }
        // fail-closed：给一个没有可派发执行集群的 catalog 保存「启用」的策略 → 400。
        // 允许保存等于允许「配了但永远不会跑」，而这类静默失效最难发现。
        // 例外是 enabled=false：那是刻意的关闭，不是遗漏——清理也必须能保存。
        if (policy.isEnabled()) {
            clusters.assertDispatchable(prefix);
        }
        policy.touch(principal, now);
        policyRepository.save(policy);
        events.publish(CatalogEventType.OPTIMIZATION_POLICY_UPDATED, prefix, CatalogEvent.TARGET_TABLE,
                prefix + "." + ref.database().getName() + "." + tableName,
                Map.of("scope", "table", "format", format.name(), "enabled", policy.isEnabled()));
        return toPolicy(prefix, ref.database().getName(), table, policy, format);
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
        TableRef ref = requireTable(prefix, databaseName, tableName);
        return new OptimizationDtos.TableHistory(prefix + "." + ref.database().getName() + "." + tableName,
                jobService.itemsOfTable(ref.table().getId()));
    }

    // ------------------------------------------------------------------ catalog 默认值

    /** catalog 级默认设置：原样给出 {@code optimization.*} 属性，加一份拆好的生效值。 */
    @Transactional(readOnly = true)
    public CatalogSettings settings(String prefix) {
        CatalogEntity catalog = catalogService.require(prefix);
        return new CatalogSettings(prefix, optimizationProperties(catalog),
                defaultActions(catalog, formatOf(catalog)));
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
        CatalogFormat format = formatOf(catalog);
        if (request == null || request.properties() == null || request.properties().isEmpty()) {
            throw ApiException.badRequest("properties must not be empty");
        }
        Map<String, String> merged = new LinkedHashMap<>(catalog.getProperties());
        List<Action> touched = new ArrayList<>();
        for (Map.Entry<String, String> entry : request.properties().entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim();
            Action action = requireSettingsKey(key, format);
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
            OptimizationOptions.validate(format, action,
                    OptimizationOptions.catalogDefaults(merged, format, action));
        }
        ManagementDtos.UpdateCatalogRequest update = new ManagementDtos.UpdateCatalogRequest(
                catalog.getEntityVersion(), null, merged, catalog.getStorageConfig());
        catalogService.update(prefix, update);
        events.publish(CatalogEventType.OPTIMIZATION_POLICY_UPDATED, prefix, CatalogEvent.TARGET_CATALOG,
                prefix,
                Map.of("scope", "catalog", "format", format.name(), "keys", touched.stream()
                        .map(Action::wire).toList()));
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
        TableRef ref = requireTable(prefix, databaseName, tableName);
        TableEntity table = ref.table();
        requireOptimizable(table);
        CatalogFormat format = formatOf(table);
        TableOptimizationEntity policy = policyRepository.findByTableId(table.getId()).orElse(null);
        List<Action> enabled = enabledActions(policy, format);
        List<Action> actions = requestedActions == null || requestedActions.isEmpty()
                ? enabled
                : OptimizationOptions.parseActions(requestedActions);
        for (Action action : actions) {
            // 不支持的动作给出的是「能力边界」而不是「你没开这个开关」：
            // 「action orphan is not enabled for this table」会让人去策略页找个不存在的开关
            if (!OptimizationOptions.isSupported(format, action)) {
                throw ApiException.badRequest(OptimizationOptions.unsupportedReason(format, action));
            }
            if (!enabled.contains(action)) {
                throw ApiException.badRequest("action " + action.wire() + " is not enabled for this table");
            }
        }
        if (actions.isEmpty()) {
            throw ApiException.badRequest("this table has no enabled optimization action");
        }
        // 立即执行同样 fail-closed：它直接建批次，不经过出批那条路，
        // 因此这里不拦的话，「没有执行集群」的时候点一下按钮就会造出一个永远 PENDING 的批次
        clusters.assertDispatchable(prefix);
        // 与出批同一把锁、同一顺序（catalog → 明细）：先把 catalog 行锁住，再复查这张表
        // 有没有活跃明细。不复查就会与调度批次同表双跑——这不是多副本才有的问题，
        // 单机两个线程（用户点按钮 vs 调度扫描）就能复现
        catalogRepository.lockById(table.getCatalogId());
        itemRepository.findFirstByTableIdAndStatusInOrderByCreatedAtDesc(table.getId(), ACTIVE_ITEMS)
                .ifPresent(active -> {
                    throw ApiException.conflict("table " + prefix + "." + ref.database().getName() + "."
                            + table.getName() + " is already in batch " + active.getJobId());
                });
        String fullName = prefix + "." + ref.database().getName() + "." + table.getName();
        return jobService.detail(jobService.createJob(table.getCatalogId(),
                OptimizationJobService.TRIGGER_MANUAL,
                List.of(new BatchTable(table.getId(), fullName, actions)), null, principal).getId(), false);
    }

    // ------------------------------------------------------------------ 内部

    /**
     * Lance 的 expire 多一条**相对**下限：保留窗口不得小于该 catalog 的最大提交间隔。
     *
     * <p>为什么这条是硬校验而不是提示：{@code VACUUM} 不区分「过期」与「还在写」——
     * 它按时间戳删版本、再回收不被保留版本引用的文件。窗口小于表的提交间隔时，
     * 一次慢提交的写入文件就会被判定为无引用而删掉，而这件事<b>没有干跑可以兜</b>
     * （另两种格式的孤儿清理有 {@code dry_run}）。因此口径与 Paimon 的孤儿清理护栏
     * 相同、强度更高（{@code docs/lance-table-optimization-design.md} §8 R4）。
     *
     * <p>只校验「这次提交里真的改 {@code retention-hours} 的请求」：用户只改执行间隔时
     * 弹一句「你的保留窗口太小」会让人无从下手——那个值不是他这次动的。
     */
    private void assertRetentionAboveCommitInterval(CatalogFormat format, Action action,
                                                    Map<String, String> config, TableEntity table) {
        if (format != CatalogFormat.LANCE || action != Action.EXPIRE) {
            return;
        }
        String value = config.get(LanceOptimizationOptions.RETENTION_HOURS);
        if (value == null || value.isBlank()) {
            return;
        }
        int retention;
        try {
            retention = Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            return; // 非法数字由 OptimizationOptions.validate 报，这里不抢它的错误文案
        }
        int floor = LanceOptimizationOptions.maxCommitIntervalHours(
                configResolver.catalogProperties(table.getCatalogId()));
        if (retention < floor) {
            throw ApiException.badRequest(LanceOptimizationOptions.RETENTION_HOURS + " must be at least "
                    + floor + " hours: it may not be shorter than the max commit interval of this catalog"
                    + " (optimization.expire." + LanceOptimizationOptions.MAX_COMMIT_INTERVAL_HOURS
                    + "), otherwise VACUUM can delete files that are still being written");
        }
    }

    /** 一次表定位的结果：格式从 {@code table.tableType} 推，catalog 留给需要 catalog 级信息的地方。 */
    private record TableRef(DatabaseEntity database, TableEntity table) {
    }

    /**
     * 按 {@code catalog / database / table} 三段定位表，**两种格式都认**。
     *
     * <p>不直接复用 Paimon 协议面的 {@code TableLookup}：它经 {@code DatabaseService.require}
     * 走 {@code resolveForFormat(prefix, PAIMON)}，对 Iceberg catalog 会以一个「这不是 Paimon
     * catalog」的 404 结束——而优化策略、立即执行、历史这三个端点对两种格式都要回答。
     * 定位本身与格式无关（两种格式的表都落在 {@code lakehouse_database} / {@code lakehouse_table}
     * 两张表里，Iceberg 只是 {@code table_type} 不同、多级 namespace 用点号拼在库名上），
     * 因此这里按 catalog_id + 库名 + 表名直接查。
     *
     * <p>{@code databaseName} 就是库名那一列的原值：Iceberg 的多级 namespace
     * {@code ["accounting","tax"]} 在库里存成 {@code accounting.tax}，控制台路由与
     * Spark SQL 的三段名也都是这个形状（见 {@code NamespacePaths.toStorageName}）。
     */
    private TableRef requireTable(String prefix, String databaseName, String tableName) {
        CatalogEntity catalog = catalogService.require(prefix);
        DatabaseEntity database = databaseRepository.findByCatalogIdAndName(catalog.getId(), databaseName)
                .orElseThrow(() -> ApiException.databaseNotExist(databaseName));
        TableEntity table = tableRepository
                .findByCatalogIdAndDatabaseIdAndName(catalog.getId(), database.getId(), tableName)
                .orElseThrow(() -> ApiException.tableNotExist(tableName));
        return new TableRef(database, table);
    }

    /** 表的优化能力边界，写操作与立即执行都要过这一关。 */
    public static void requireOptimizable(TableEntity table) {
        CatalogFormat format = recognizedFormatOf(table);
        if (format == null || !isOptimizableFormat(table.getTableType())) {
            throw ApiException.badRequest("storage optimization is only available for Paimon, Iceberg and"
                    + " Lance tables, but this table is " + table.getTableType());
        }
        // Lance 例外：它的数据本来就全部由客户端写入，register 登记进来的表天然是「外部」的。
        // 照搬这条会让 Lance 表一张也开不了，而文案还会把人往权限问题上引。
        if (format != CatalogFormat.LANCE && table.isExternal()) {
            throw ApiException.badRequest("storage optimization is not available for external tables:"
                    + " their location is not owned by this service");
        }
    }

    /**
     * 这张表能不能优化（不抛异常的版本，调度扫描用它跳过不该进批的表）。
     *
     * <p>与 {@link #requireOptimizable} 同一套判据：能优化的格式 + 非外部表（Lance 不看外部）。
     * 调度侧不能抛异常——一条脏策略把整轮扫描打断，代价是「所有 catalog 都停止优化」。
     */
    public static boolean isOptimizable(TableEntity table) {
        if (table == null) {
            return false;
        }
        CatalogFormat format = recognizedFormatOf(table);
        if (format == null || !isOptimizableFormat(table.getTableType())) {
            return false;
        }
        return format == CatalogFormat.LANCE || !table.isExternal();
    }

    /** 三种可优化格式。放在这里而不是散在各处：加第四种格式时要改的只有这一处。 */
    private static boolean isOptimizableFormat(String tableType) {
        return "PAIMON".equalsIgnoreCase(tableType) || "ICEBERG".equalsIgnoreCase(tableType)
                || "LANCE".equalsIgnoreCase(tableType);
    }

    /**
     * 表的格式，**认不出返回 {@code null}**。
     *
     * <p>不用 {@link CatalogFormat#of}：它对认不出的取值回落 Paimon，那在只有两种格式的年代
     * 是安全的一侧（回落目标就是「唯一可能的另一侧」），加了第三种之后回落的是一个
     * <b>错误答案</b>——一张 {@code table_type=lance-table} 的旧表会拿到 Paimon 的参数表，
     * 于是表单里出现 {@code retain-min} 这类它根本不认的键。
     *
     * <p>读路径（{@link #policy}）把 {@code null} 表达成「不支持 + 原因」；写路径
     * （{@link #requireOptimizable}）直接 400 并指名取值。
     */
    static CatalogFormat recognizedFormatOf(TableEntity table) {
        return table == null ? null : CatalogFormat.parse(table.getTableType()).orElse(null);
    }

    /** 表的格式；认不出时 400（只在已过 {@link #requireOptimizable} 的路径上调用）。 */
    public static CatalogFormat formatOf(TableEntity table) {
        CatalogFormat format = recognizedFormatOf(table);
        if (format == null) {
            throw ApiException.badRequest("storage optimization is not available for table type "
                    + (table == null ? "null" : table.getTableType()));
        }
        return format;
    }

    /** catalog 的格式（catalog 级默认值按它校验）。catalog 的格式由创建时选定，不会认不出。 */
    static CatalogFormat formatOf(CatalogEntity catalog) {
        return CatalogFormat.of(catalog.getCatalogFormat());
    }

    /**
     * 表已启用的动作（按格式）；没有策略行时按各格式的默认开关。
     *
     * <p>结果**不含该格式不支持的动作**：Lance 表上即便库里 {@code orphan_remove_enabled}
     * 是 {@code true}（历史数据、或有人直接改库），也不会进批——它不会被翻译成语句。
     * 这一层过滤与 {@link #toPolicy} 里读时算 {@code supported} 是同一条规则的两端：
     * 一个让页面不显示开关，一个让批次不带这个动作。
     */
    public static List<Action> enabledActions(TableOptimizationEntity policy, CatalogFormat format) {
        List<Action> actions = new ArrayList<>();
        for (Action action : Action.values()) {
            if (!OptimizationOptions.isSupported(format, action)) {
                continue;
            }
            boolean enabled = policy == null ? OptimizationOptions.defaultEnabled(format, action) : switch (action) {
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

    private TableOptimizationEntity newPolicy(TableEntity table, String principal, long now,
                                              CatalogFormat format) {
        TableOptimizationEntity policy = new TableOptimizationEntity();
        policy.setId(Paging.newId());
        policy.setTableId(table.getId());
        policy.setCatalogId(table.getCatalogId());
        policy.setEnabled(false);
        policy.setScheduleIntervalSeconds(DEFAULT_SCHEDULE_SECONDS);
        // 三项默认开关按格式取：Lance 的 expire（= VACUUM，不可逆、无干跑）默认关，
        // 与另两种格式把 orphan 默认关是同一条理由——会删数据的那一项必须显式按下开关
        policy.setCompactionEnabled(OptimizationOptions.defaultEnabled(format, Action.COMPACT));
        policy.setSnapshotExpireEnabled(OptimizationOptions.defaultEnabled(format, Action.EXPIRE));
        policy.setOrphanRemoveEnabled(OptimizationOptions.defaultEnabled(format, Action.ORPHAN));
        policy.markCreated(principal, now);
        policy.setUpdatedAt(now);
        return policy;
    }

    private TablePolicy toPolicy(String catalog, String databaseName, TableEntity table,
                                 TableOptimizationEntity policy, CatalogFormat format) {
        Map<String, String> catalogProperties = configResolver.catalogProperties(table.getCatalogId());
        // 格式认不出时按 Paimon 渲染一份空壳（页面会因 supported=false 整块收起），
        // 但绝不能因为「不知道该用哪套参数」而让整个策略接口 500
        CatalogFormat rendering = format == null ? CatalogFormat.DEFAULT : format;
        List<ActionPolicy> actions = new ArrayList<>();
        for (Action action : Action.values()) {
            Map<String, String> config = policy == null ? new LinkedHashMap<>() : switch (action) {
                case COMPACT -> policy.getCompactionConfig();
                case EXPIRE -> policy.getSnapshotExpireConfig();
                case ORPHAN -> policy.getOrphanRemoveConfig();
            };
            boolean supported = format != null && OptimizationOptions.isSupported(format, action);
            // 不支持的动作一律回 enabled=false：库里那一列对 Lance 表是无意义的
            // （orphan_remove_enabled 落库后换不了格式，格式又不可变），读时算比写时清更可靠
            boolean enabled = !supported ? false
                    : policy == null ? OptimizationOptions.defaultEnabled(rendering, action) : switch (action) {
                        case COMPACT -> policy.isCompactionEnabled();
                        case EXPIRE -> policy.isSnapshotExpireEnabled();
                        case ORPHAN -> policy.isOrphanRemoveEnabled();
                    };
            actions.add(new ActionPolicy(action.wire(), action.displayName(), enabled,
                    config == null ? new LinkedHashMap<>() : new LinkedHashMap<>(config),
                    configResolver.resolve(rendering, action, catalogProperties, config),
                    config == null || config.isEmpty(),
                    supported,
                    supported ? null : OptimizationOptions.unsupportedReason(rendering, action)));
        }
        boolean supported = isOptimizable(table);
        String reason = null;
        if (format == null) {
            // 「认不出」与「认得但不支持」要分开说：前者是脏数据或旧格式（改不动），
            // 后者是能力边界。两句都带格式取值，用户才知道该去改什么
            reason = "只有 Paimon、Iceberg 与 Lance 表支持存储优化，无法识别的表格式：" + table.getTableType();
        } else if (!isOptimizableFormat(table.getTableType())) {
            reason = "只有 Paimon、Iceberg 与 Lance 表支持存储优化，当前表类型为 " + table.getTableType();
        } else if (format != CatalogFormat.LANCE && table.isExternal()) {
            reason = "外部表的位置不归本服务所有，不支持存储优化";
        }
        return new TablePolicy(catalog, databaseName, table.getName(),
                rendering.wireName(),
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

    private List<ActionPolicy> defaultActions(CatalogEntity catalog, CatalogFormat format) {
        List<ActionPolicy> policies = new ArrayList<>();
        for (Action action : Action.values()) {
            Map<String, String> catalogConfig =
                    OptimizationOptions.catalogDefaults(catalog.getProperties(), format, action);
            boolean supported = OptimizationOptions.isSupported(format, action);
            policies.add(new ActionPolicy(action.wire(), action.displayName(), supported,
                    catalogConfig,
                    configResolver.resolve(format, action, catalog.getProperties(), Map.of()),
                    catalogConfig.isEmpty(),
                    supported,
                    supported ? null : OptimizationOptions.unsupportedReason(format, action)));
        }
        return policies;
    }

    /** 校验 catalog 属性键的形状：{@code optimization.<动作>.<参数名>}。 */
    private Action requireSettingsKey(String key, CatalogFormat format) {
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
        // 「认得的键」按 catalog 格式取：Iceberg 的 retain-last 写进 Paimon catalog 是无意义的。
        // 另一类例外是**只允许出现在 catalog 属性里**的键（Lance 的
        // optimization.expire.max-commit-interval-hours）：它是全局护栏，不是表参数。
        String option = rest.substring(dot + 1);
        if (!OptimizationOptions.isKnownOption(format, action, option)
                && !OptimizationOptions.isCatalogOnlyOption(format, action, option)) {
            throw ApiException.badRequest("Unknown optimization option in setting key: " + key);
        }
        return action;
    }
}
