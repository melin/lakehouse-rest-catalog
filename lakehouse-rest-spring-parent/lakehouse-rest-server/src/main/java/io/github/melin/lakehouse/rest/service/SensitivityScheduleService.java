package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.config.RequestContext;
import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.domain.entity.SensitivityScheduleEntity;
import io.github.melin.lakehouse.rest.domain.repo.SensitivityScheduleRepository;
import io.github.melin.lakehouse.rest.dto.SensitivityDtos.SensitivitySchedule;
import io.github.melin.lakehouse.rest.dto.SensitivityDtos.SensitivityScheduleList;
import io.github.melin.lakehouse.rest.dto.SensitivityDtos.SensitivityScheduleUpsertRequest;
import io.github.melin.lakehouse.rest.service.exec.ExecutionClusterService;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.CronSpec;
import io.github.melin.lakehouse.rest.support.ResourceType;
import io.github.melin.lakehouse.rest.support.SensitivityCatalogSettings;
import io.github.melin.lakehouse.rest.support.SensitivityEnums.ScanExecution;
import io.github.melin.lakehouse.rest.support.SensitivityEnums.ScanMode;
import io.github.melin.lakehouse.rest.support.SensitivityEnums.ScanScope;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 定时扫描调度项的读写（设计见 {@code docs/sensitive-data-design.md} §4.6 / §5.6.3 / §9）。
 *
 * <p><b>本类只做「配置」，不做「触发」。</b>触发是 {@code SensitivityScheduleDispatcher}
 * 的节拍，它需要多副本语义（租约 + {@code next_run_at} 的 CAS），而那是运行期的事，
 * 与「一个治理员新建了一条调度项」没有交集。分开之后，CRUD 可以在测试里直接跑，
 * 不必为了验证「cron 写错了会不会 400」去驱动一个定时任务。
 *
 * <p><b>写入时就把 {@code next_run_at} 算出来，不留空。</b>留空的后果是「保存成功、
 * 但下次运行时刻未知」，而节拍那一侧又要为「空值怎么算」再实现一次——两次推导
 * 在同一份 cron 上给出不同答案时，用户看到的预览与真实的触发时刻会对不上，
 * 且双方都不报错。所以：新建、改 cron、重新启用，三处都在写事务里把下一次算好。
 *
 * <p><b>改名被拒（400）。</b>{@code lakehouse_sensitivity_scan.schedule_name} 存的就是
 * 这个名字，而扫描是历史记录——改名会让「这条扫描是哪条调度项触发的」在库里失去指向。
 * 换个名字的做法是建一条新的、停用旧的：历史留在旧名字下，那才是「可审计」。
 *
 * <p><b>{@code execution=SPARK} 在建调度项时就 fail-closed。</b>与
 * {@code POST /sensitivity-scans} 同一条口径（§5.6.5）：给一个没有可派发执行集群的
 * catalog 建 SPARK 调度项必须 400，而不是等到下一次触发才失败——后者的现象是
 * 「调度项看起来配好了，凌晨三点什么都没发生」，而那正是定时任务最难排查的一种坏法。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SensitivityScheduleService {

    private final SensitivityScheduleRepository repository;
    private final SensitivitySettingsService settingsService;
    private final ExecutionClusterService clusters;
    private final RestServerProperties properties;
    private final Clock clock;

    // ------------------------------------------------------------------ 读

    /** {@code GET /sensitivity-schedules}：按 catalog 过滤（不传 = 全部，判权在控制器）。 */
    @Transactional(readOnly = true)
    public SensitivityScheduleList list(String catalog) {
        String catalogName = trimToNull(catalog);
        List<SensitivityScheduleEntity> entities = catalogName == null
                ? repository.findAllByOrderByNameAsc()
                : repository.findAllByCatalogNameOrderByNameAsc(catalogName);
        List<SensitivitySchedule> items = new ArrayList<>(entities.size());
        for (SensitivityScheduleEntity entity : entities) {
            items.add(toDto(entity));
        }
        return new SensitivityScheduleList(items);
    }

    /** {@code GET /sensitivity-schedules/{id}}：不存在抛 404（资源类型是 {@code SENSITIVITY_SCHEDULE}）。 */
    @Transactional(readOnly = true)
    public SensitivitySchedule get(Long id) {
        return toDto(require(id));
    }

    /** 按 id 取实体；不存在抛 404。 */
    @Transactional(readOnly = true)
    public SensitivityScheduleEntity require(Long id) {
        if (id == null) {
            throw ApiException.badRequest("scheduleId must not be empty");
        }
        return repository.findById(id)
                .orElseThrow(() -> ApiException.managementNotExist(ResourceType.SENSITIVITY_SCHEDULE,
                        String.valueOf(id)));
    }

    // ------------------------------------------------------------------ 写

    /**
     * {@code POST /sensitivity-schedules}：新建。
     *
     * <p>同名先查一次（409）：唯一键撞车会以 {@code DataIntegrityViolationException} 冒出来，
     * 那是一个 500，而「名字重复」是一个用户能自己改的错误。
     */
    @Transactional
    public SensitivitySchedule create(SensitivityScheduleUpsertRequest request) {
        if (request == null) {
            throw ApiException.badRequest("request body must not be empty");
        }
        String name = requireName(request.name());
        if (repository.findByName(name).isPresent()) {
            throw ApiException.managementAlreadyExist(ResourceType.SENSITIVITY_SCHEDULE, name);
        }
        String catalogName = trimToNull(request.catalog());
        if (catalogName == null) {
            throw ApiException.badRequest("catalog is required");
        }
        CatalogEntity catalog = settingsService.requireCatalog(catalogName);

        SensitivityScheduleEntity entity = new SensitivityScheduleEntity();
        entity.setName(name);
        entity.setCatalogName(catalog.getPrefix());
        applyScope(entity, request.scope(), request.database(), request.table());
        applyParams(entity, catalog, request);
        entity.setEnabledFlag(request.enabled() == null || request.enabled());
        entity.setEntityVersion(0);
        // 新建时也算一次下次运行时刻：停用状态下也算（重新启用时不必再等一次补算）
        entity.setNextRunAt(nextRunAfter(entity.getCron(), clock.millis()));
        entity.markCreated(RequestContext.principal(), clock.millis());
        SensitivityScheduleEntity saved = repository.save(entity);
        log.info("sensitivity schedule {} created: cron={} scope={} catalog={} execution={} nextRunAt={}",
                saved.getName(), saved.getCron(), scopeOf(saved), saved.getCatalogName(),
                saved.getExecution(), saved.getNextRunAt());
        return toDto(saved);
    }

    /**
     * {@code PUT /sensitivity-schedules/{id}}：改（含启停）。
     *
     * <p><b>每次都重算 {@code next_run_at}，即使 cron 没变。</b>两个原因：
     * ① 停用再启用之后，库里那个值可能已经是很久以前的时刻，不重算会让它「一启用就立刻跑」，
     * 而那通常是深夜；② 改 cron 后如果不重算，下一次触发仍按旧表达式的时间点发生一次
     * ——用户会看到「我刚改成每小时，它却按昨天的每天三点跑了一次」。
     *
     * <p>乐观锁用 {@code entityVersion}：两个治理员同时改同一条调度项时由人来裁决。
     * 注意这个版本号与调度器的抢占**不是一回事**——调度器推进 {@code next_run_at} 时
     * 不动它，因此「看到 3、实际 4」只会由另一次人工编辑造成。
     */
    @Transactional
    public SensitivitySchedule update(Long id, SensitivityScheduleUpsertRequest request) {
        if (request == null) {
            throw ApiException.badRequest("request body must not be empty");
        }
        SensitivityScheduleEntity entity = require(id);
        if (request.entityVersion() != null && request.entityVersion() != entity.getEntityVersion()) {
            throw ApiException.entityVersionMismatch(ResourceType.SENSITIVITY_SCHEDULE,
                    entity.getName(), request.entityVersion(), entity.getEntityVersion());
        }
        String name = trimToNull(request.name());
        if (name != null && !name.equals(entity.getName())) {
            throw ApiException.badRequest("调度项不支持改名（当前 " + entity.getName() + "，请求 " + name
                    + "）：扫描历史按 schedule_name 回查触发来源，改名会让历史失去指向。"
                    + "要换名字请新建一条并停用这一条");
        }
        String catalogName = trimToNull(request.catalog());
        if (catalogName == null) {
            throw ApiException.badRequest("catalog is required");
        }
        CatalogEntity catalog = settingsService.requireCatalog(catalogName);
        entity.setCatalogName(catalog.getPrefix());
        applyScope(entity, request.scope(), request.database(), request.table());
        applyParams(entity, catalog, request);
        if (request.enabled() != null) {
            entity.setEnabledFlag(request.enabled());
        }
        entity.setNextRunAt(nextRunAfter(entity.getCron(), clock.millis()));
        entity.setEntityVersion(entity.getEntityVersion() + 1);
        entity.touch(RequestContext.principal(), clock.millis());
        SensitivityScheduleEntity saved = repository.save(entity);
        return toDto(saved);
    }

    /**
     * {@code DELETE /sensitivity-schedules/{id}}：删除。
     *
     * <p><b>真删，不是停用。</b>「停用」是一个独立动作（{@code PUT enabled=false}），
     * 它的语义是「这条规则还在、只是暂时不跑」；删除的语义是「不再需要这条规则」。
     * 把删除实现成停用会让两者在库里长得一样，而清单上「我删掉了它却还在」是最容易被
     * 当成 bug 的一种正确行为。历史扫描不受影响——它们按 {@code schedule_name} 存字符串，
     * 不建外键（§4.6）。
     */
    @Transactional
    public void delete(Long id) {
        SensitivityScheduleEntity entity = require(id);
        repository.delete(entity);
        log.info("sensitivity schedule {} deleted", entity.getName());
    }

    // ------------------------------------------------------------------ 供节拍使用

    /**
     * 算下一次运行时刻（节拍推进与写入共用这一份）。
     *
     * @throws ApiException 400：表达式永不匹配（新建时已校验，这里兜的是「库里的行被手改过」）
     */
    public long nextRunAfter(String cron, long fromMillis) {
        try {
            return CronSpec.parse(cron).requireNextAfter(fromMillis, clock.getZone());
        } catch (IllegalArgumentException exception) {
            throw ApiException.badRequest(exception.getMessage());
        }
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 范围三段：**递进**校验 + 落库。
     *
     * <p>{@code scope} 由调用方给（与 {@code POST /sensitivity-scans} 同一形状），
     * 但要与三段名一致：给了 {@code scope=TABLE} 却没有表名，是「说了一套、填了另一套」，
     * 静默按填的走会让界面上的层级标签骗人。
     *
     * <p>{@code database} / {@code table} 在更宽的层级上**置空**而不是保留：
     * 一条从表级改成 catalog 级的调度项如果还留着旧表名，读模型里就会出现
     * 「scope=CATALOG 但有 table」这种自相矛盾的行，而按表名过滤的界面会把它算进那张表。
     */
    private void applyScope(SensitivityScheduleEntity entity, String scopeValue,
                            String databaseValue, String tableValue) {
        ScanScope scope = parseScope(scopeValue);
        String database = trimToNull(databaseValue);
        String table = trimToNull(tableValue);
        if (scope == ScanScope.TABLE && (database == null || table == null)) {
            throw ApiException.badRequest("scope=TABLE requires database and table");
        }
        if (scope == ScanScope.DATABASE && database == null) {
            throw ApiException.badRequest("scope=DATABASE requires database");
        }
        entity.setDatabaseName(scope == ScanScope.CATALOG ? null : database);
        entity.setTableName(scope == ScanScope.TABLE ? table : null);
    }

    /** 模式 / 执行位置 / 采样量；三处校验与 {@code POST /sensitivity-scans} 保持同一口径。 */
    private void applyParams(SensitivityScheduleEntity entity, CatalogEntity catalog,
                             SensitivityScheduleUpsertRequest request) {
        applyCron(entity, request.cron());
        ScanMode mode = parseMode(request.mode());
        entity.setMode(mode.name());
        entity.setSampleSize(resolveSampleSize(request.sampleSize()));

        ScanExecution execution = request.execution() == null || request.execution().isBlank()
                ? ScanExecution.parse(settingsService.resolvedExecution(
                        SensitivityCatalogSettings.read(catalog.getProperties())))
                        .orElse(ScanExecution.IN_PROCESS)
                : ScanExecution.parse(request.execution().trim())
                        .orElseThrow(() -> ApiException.badRequest("Unknown execution \""
                                + request.execution() + "\" for catalog " + catalog.getPrefix()
                                + "; supported: IN_PROCESS, SPARK"));
        if (execution == ScanExecution.SPARK) {
            if (mode == ScanMode.METADATA_ONLY) {
                // 与发起扫描同一条：元数据档下推没有收益。调度项更要在**建的时候**拦住——
                // 否则它会每天凌晨失败一次，而失败只体现在扫描历史里。
                throw ApiException.badRequest("execution=SPARK is not allowed for mode=METADATA_ONLY"
                        + " (a metadata-only scan pushed to Spark adds a job scheduling overhead"
                        + " with no benefit); use execution=IN_PROCESS or mode=SAMPLED");
            }
            // fail-closed：不可派发的 catalog 建 SPARK 调度项必须 400（§5.6.5）
            clusters.assertDispatchable(catalog.getPrefix());
        }
        entity.setExecution(execution.name());
    }

    /**
     * cron：新建必填、修改留空表示沿用旧值，写入前先解析一次。
     *
     * <p><b>为什么先解析再落库。</b>落库之后才校验的话，一条写错的表达式会先进库、
     * 再在「算下次运行时刻」时抛 400——现象仍然是 400，但那一次事务里已经改过
     * {@code mode} / {@code execution} 等字段（修改是 {@code @Transactional} 会回滚，
     * 所以不至于脏；可是错误信息会指向「下次运行时刻算不出来」，而不是「你写错了 cron」）。
     * 校验放在最前面，报错就指着它自己。
     *
     * <p><b>修改时留空沿用旧值</b>：{@code PUT} 是整份替换，但「只改启停」是很常见的动作，
     * 强迫调用方把 cron 一起回传只会让界面上的表单变成唯一可用的调用方式。
     */
    private void applyCron(SensitivityScheduleEntity entity, String cronValue) {
        String cron = trimToNull(cronValue);
        if (cron == null) {
            if (trimToNull(entity.getCron()) == null) {
                throw ApiException.badRequest("cron is required (5 or 6 fields, e.g. \"0 3 * * *\")");
            }
            return;
        }
        try {
            CronSpec.parse(cron);
        } catch (IllegalArgumentException exception) {
            throw ApiException.badRequest(exception.getMessage());
        }
        entity.setCron(cron);
    }

    private int resolveSampleSize(Integer requested) {
        int max = properties.getSensitivity().getScan().getMaxSampleSize();
        int defaultValue = properties.getSensitivity().getScan().getSampleSize();
        if (requested == null) {
            return defaultValue;
        }
        if (requested <= 0) {
            throw ApiException.badRequest("sampleSize must be positive, got " + requested);
        }
        if (requested > max) {
            throw ApiException.badRequest("sampleSize " + requested
                    + " exceeds lakehouse.rest.sensitivity.scan.max-sample-size=" + max);
        }
        return requested;
    }

    private static String requireName(String value) {
        String name = trimToNull(value);
        if (name == null) {
            throw ApiException.badRequest("name is required");
        }
        if (name.length() > 128) {
            throw ApiException.badRequest("name must be at most 128 characters, got " + name.length());
        }
        return name;
    }

    private static ScanScope parseScope(String value) {
        String scope = trimToNull(value);
        if (scope == null) {
            return ScanScope.CATALOG;
        }
        return ScanScope.parse(scope.toUpperCase(Locale.ROOT))
                .orElseThrow(() -> ApiException.badRequest("Unknown scope \"" + value
                        + "\"; supported: CATALOG, DATABASE, TABLE"));
    }

    private static ScanMode parseMode(String value) {
        String mode = trimToNull(value);
        if (mode == null) {
            return ScanMode.METADATA_ONLY;
        }
        return ScanMode.parse(mode.toUpperCase(Locale.ROOT))
                .orElseThrow(() -> ApiException.badRequest("Unknown mode \"" + value
                        + "\"; supported: METADATA_ONLY, SAMPLED"));
    }

    /** 派生层级（库里只存三段名，见实体注释）。 */
    static ScanScope scopeOf(SensitivityScheduleEntity entity) {
        if (trimToNull(entity.getTableName()) != null) {
            return ScanScope.TABLE;
        }
        return trimToNull(entity.getDatabaseName()) != null ? ScanScope.DATABASE : ScanScope.CATALOG;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static SensitivitySchedule toDto(SensitivityScheduleEntity entity) {
        return new SensitivitySchedule(entity.getId(), entity.getName(), entity.getCron(),
                entity.getCatalogName(), scopeOf(entity).name(), entity.getDatabaseName(),
                entity.getTableName(), entity.getMode(), entity.getExecution(), entity.getSampleSize(),
                entity.isEnabledFlag(), entity.getLastRunAt(), entity.getNextRunAt(),
                entity.getEntityVersion(), entity.getCreatedBy(), entity.getCreatedAt(),
                entity.getUpdatedBy(), entity.getUpdatedAt());
    }
}
