package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.config.RequestContext;
import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.domain.repo.CatalogRepository;
import io.github.melin.lakehouse.rest.dto.SensitivityDtos.SensitivitySettings;
import io.github.melin.lakehouse.rest.dto.SensitivityDtos.SensitivitySettingsUpdateRequest;
import io.github.melin.lakehouse.rest.event.CatalogEvent;
import io.github.melin.lakehouse.rest.event.CatalogEventPublisher;
import io.github.melin.lakehouse.rest.event.CatalogEventType;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.ResourceType;
import io.github.melin.lakehouse.rest.support.SensitivityCatalogSettings;
import io.github.melin.lakehouse.rest.support.SensitivityCatalogSettings.Overrides;
import io.github.melin.lakehouse.rest.scan.SensitivityEnums.ScanExecution;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * catalog 级敏感数据治理设置（设计见 {@code docs/sensitive-data-design.md}
 * §5.6.1 / §5.6.4 / §9）。
 *
 * <p><b>三个字段都是「catalog 覆盖」，读接口同时回生效值。</b>只回覆盖值的话，
 * 界面要回答「这个 catalog 现在实际会怎么跑」就得再取一次全局配置，
 * 于是同一件事有两个答案源。生效值在这里算一次、随响应一起给。
 *
 * <p><b>三层覆盖的顺序是「请求 &gt; catalog &gt; 全局」，因此本类只负责后两层。</b>
 * 请求层的合并发生在发起扫描的地方（{@code SensitivityScanService}）——
 * 那是唯一同时知道「请求想要什么」与「这个 catalog 缺省是什么」的地方。
 *
 * <p><b>写在 {@code lakehouse_catalog.properties_json}，不新加列。</b>理由见
 * {@link SensitivityCatalogSettings} 的类注释。这里要强调的是它的一个副作用：
 * {@code ManagementCatalogService.update} 会把 {@code properties} **整份替换**，
 * 因此本类写入时必须基于**现有 Map 拷贝**（{@link SensitivityCatalogSettings#write}
 * 已如此实现）——直接用一份只含 {@code sensitivity.*} 的新 Map 会把
 * {@code optimization.*} 等别的键删掉，而那个 bug 的现象是「改完扫描设置，
 * 优化策略的 catalog 默认值全没了」。
 *
 * <p><b>改设置进审计。</b>它改的是「这个 catalog 的扫描怎么跑」——与改绑定同级，
 * 因此发 {@code sensitivity.binding.changed} 事件并递增 catalog 的
 * {@code entityVersion}（不清空、不重置，与 catalog 更新同一口径）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SensitivitySettingsService {

    /**
     * 并发上限的允许范围。上限取 64：它是「进程内同时几条扫描」，
     * 而每条扫描都会向存储侧拉样本——允许配到 1000 只会得到一个把对象存储打满的部署。
     */
    private static final int MIN_CONCURRENT_SCANS = 1;
    private static final int MAX_CONCURRENT_SCANS = 64;

    /** 传播跳数上限：超过它就不是「沿血缘往前推」，而是「全图扩散」（设计稿 §7.1）。 */
    private static final int MIN_PROPAGATION_DEPTH = 1;
    private static final int MAX_PROPAGATION_DEPTH = 32;

    /**
     * 事件属性里的种类标记。
     *
     * <p>{@code sensitivity.binding.changed} 这个类型被两条写路径共用（§9 的事件清单
     * 只给了它一个），P3 起还有 {@code TagMaskBindingService} 那条（{@code kind=BINDING}）。
     * 下游订阅方据此判断「该刷新哪份缓存」：设置改的是扫描怎么跑，
     * 绑定改的是脱敏怎么展开——两者的失效目标完全不同。两边都写 {@code kind}，
     * 判断逻辑才不必依赖「没有 kind 就当设置」这种隐式约定。
     */
    public static final String EVENT_KIND = "SETTINGS";

    private final CatalogRepository catalogRepository;
    private final RestServerProperties properties;
    private final CatalogEventPublisher events;
    private final Clock clock;

    // ------------------------------------------------------------------ 读

    /**
     * {@code GET /sensitivity-settings?catalog=}：一个 catalog 的设置 + 三个生效值。
     *
     * <p>catalog 必须存在（404 而不是回一份全空的设置）：回空值会让「catalog 名写错了」
     * 与「这个 catalog 什么都没覆盖」在界面上长得一样，而两者的处置完全不同。
     */
    @Transactional(readOnly = true)
    public SensitivitySettings get(String catalogName) {
        CatalogEntity catalog = requireCatalog(catalogName);
        Overrides overrides = SensitivityCatalogSettings.read(catalog.getProperties());
        return new SensitivitySettings(catalog.getPrefix(),
                overrides.defaultExecution(),
                overrides.maxConcurrentScans(),
                overrides.propagationMaxDepth(),
                resolvedExecution(overrides),
                resolvedMaxConcurrentScans(overrides),
                resolvedPropagationMaxDepth(overrides),
                catalog.getEntityVersion(),
                catalog.getUpdatedBy(),
                catalog.getUpdatedAt());
    }

    // ------------------------------------------------------------------ 写

    /**
     * {@code PUT /sensitivity-settings?catalog=}：整份替换三个覆盖值。
     *
     * <p>{@code entityVersion} 做乐观锁：三个字段都是治理决定，两个治理员同时改
     * 必须由人来裁决（与 {@code POST /sensitivity-scans} 的「同表互斥」同一思路，
     * 只是一个挡并发写、一个挡并发扫）。{@code null} 表示跳过校验——
     * 与 {@code ManagementCatalogService.update} 的 {@code currentEntityVersion} 同口径。
     */
    @Transactional
    public SensitivitySettings update(String catalogName, SensitivitySettingsUpdateRequest request) {
        if (request == null) {
            throw ApiException.badRequest("request body must not be empty");
        }
        CatalogEntity catalog = requireCatalog(catalogName);
        if (request.entityVersion() != null && request.entityVersion() != catalog.getEntityVersion()) {
            throw ApiException.entityVersionMismatch(ResourceType.CATALOG, catalogName,
                    request.entityVersion(), catalog.getEntityVersion());
        }
        String execution = normalizeExecution(request.defaultExecution(), catalogName);
        Integer scans = range(request.maxConcurrentScans(), MIN_CONCURRENT_SCANS,
                MAX_CONCURRENT_SCANS, "maxConcurrentScans");
        Integer depth = range(request.propagationMaxDepth(), MIN_PROPAGATION_DEPTH,
                MAX_PROPAGATION_DEPTH, "propagationMaxDepth");

        Overrides before = SensitivityCatalogSettings.read(catalog.getProperties());
        Overrides after = new Overrides(execution, scans, depth);
        // 基于现有 Map 写：properties_json 是同一张表里所有 catalog 级设置的共用空间
        // （optimization.* 也在里面），整份替换会把别人的键删掉
        Map<String, String> updated = SensitivityCatalogSettings.write(catalog.getProperties(), after);
        catalog.setProperties(new LinkedHashMap<>(updated));
        catalog.setEntityVersion(catalog.getEntityVersion() + 1);
        catalog.touch(RequestContext.principal(), clock.millis());
        CatalogEntity saved = catalogRepository.save(catalog);

        // 只有真的变了才发事件：同一个值连按两次保存不该在事件流里留两条
        // （与事件框架「没有变化的操作不进事件流」同一条规则）
        if (!after.equals(before)) {
            Map<String, Object> attributes = new LinkedHashMap<>();
            // 与 TagMaskBindingService 共用同一个事件类型，靠 kind 区分（见 EVENT_KIND 的注释）
            attributes.put("kind", EVENT_KIND);
            attributes.put("defaultExecution", saved.getProperties()
                    .get(SensitivityCatalogSettings.KEY_DEFAULT_EXECUTION));
            attributes.put("maxConcurrentScans", saved.getProperties()
                    .get(SensitivityCatalogSettings.KEY_MAX_CONCURRENT_SCANS));
            attributes.put("propagationMaxDepth", saved.getProperties()
                    .get(SensitivityCatalogSettings.KEY_PROPAGATION_MAX_DEPTH));
            attributes.put("resolvedExecution", resolvedExecution(after));
            attributes.put("entityVersion", saved.getEntityVersion());
            events.publish(CatalogEventType.SENSITIVITY_BINDING_CHANGED, catalogName,
                    CatalogEvent.TARGET_CATALOG, catalogName, attributes);
        }
        return new SensitivitySettings(saved.getPrefix(), execution, scans, depth,
                resolvedExecution(after), resolvedMaxConcurrentScans(after),
                resolvedPropagationMaxDepth(after), saved.getEntityVersion(),
                saved.getUpdatedBy(), saved.getUpdatedAt());
    }

    // ------------------------------------------------------------------ 生效值（三层覆盖的后两层）

    /**
     * 执行位置的生效值：catalog 覆盖 &gt; 全局默认。
     *
     * <p>全局值解析不出来时回落 {@code IN_PROCESS} 并留 WARN 一次——
     * 这里是启动路径上的一个便利项，拼错一个枚举名不该让整个 catalog 的扫描不可用，
     * 但也不该静默（「我在 yml 里写的 SPARK 为什么没生效」要有地方能查到）。
     */
    public String resolvedExecution(Overrides overrides) {
        if (overrides != null && overrides.defaultExecution() != null) {
            return ScanExecution.parse(overrides.defaultExecution())
                    .map(Enum::name)
                    .orElseGet(() -> {
                        log.warn("catalog sets an unrecognized default-execution \"{}\";"
                                + " falling back to the global default",
                                overrides.defaultExecution());
                        return globalExecution();
                    });
        }
        return globalExecution();
    }

    /** 全局默认执行位置；配错了回落 {@code IN_PROCESS}。 */
    public String globalExecution() {
        String configured = properties.getSensitivity().getScan().getExecution();
        return ScanExecution.parse(configured)
                .map(Enum::name)
                .orElseGet(() -> {
                    log.warn("lakehouse.rest.sensitivity.scan.execution is \"{}\", which is not one of"
                            + " IN_PROCESS / SPARK; falling back to IN_PROCESS", configured);
                    return ScanExecution.IN_PROCESS.name();
                });
    }

    /**
     * A 路线（进程内）扫描并发的生效值：catalog 覆盖 &gt; 全局默认。
     *
     * <p><b>它与执行集群那把 {@code maxConcurrentJobs} 无关</b>（设计稿 §5.6.4 的警示框）：
     * 这个数守的是服务端进程内同时几条扫描在读存储；那把闸门守的是「那台集群上同时
     * 几个作业在跑」（优化批次 + 下推扫描一起算）。混成一个数字会让「调大集群并发」
     * 意外放开对象存储的并发。
     */
    public int resolvedMaxConcurrentScans(Overrides overrides) {
        Integer override = overrides == null ? null : overrides.maxConcurrentScans();
        return override != null && override > 0
                ? override : Math.max(MIN_CONCURRENT_SCANS, properties.getSensitivity().getScan().getMaxConcurrent());
    }

    /** 血缘传播跳数的生效值（P2 消费）：catalog 覆盖 &gt; 全局默认。 */
    public int resolvedPropagationMaxDepth(Overrides overrides) {
        Integer override = overrides == null ? null : overrides.propagationMaxDepth();
        return override != null && override > 0
                ? override : Math.max(MIN_PROPAGATION_DEPTH,
                        properties.getSensitivity().getPropagation().getMaxDepth());
    }

    // ------------------------------------------------------------------ 内部

    /** 按 prefix 取 catalog；不存在抛 404。 */
    @Transactional(readOnly = true)
    public CatalogEntity requireCatalog(String catalogName) {
        if (catalogName == null || catalogName.isBlank()) {
            throw ApiException.badRequest("catalog must not be empty");
        }
        return catalogRepository.findByPrefix(catalogName.trim())
                .orElseThrow(() -> ApiException.managementNotExist(ResourceType.CATALOG, catalogName.trim()));
    }

    /**
     * 校验并归一执行位置。空白 = 清除覆盖（回落全局）；写了但认不出 = 400 而不是静默回落。
     *
     * <p>与「yml 里写错只 warn」刻意不同：yml 是运维手写的自由文本且改一次要重启，
     * 一个拼错就拒绝启动代价太大；而这里是 API 请求，调用方立刻能改，
     * 静默把它当成「清除覆盖」会让「我明明设了 SPARK」变成一条查不出来的行为。
     */
    private String normalizeExecution(String execution, String catalogName) {
        if (execution == null || execution.isBlank()) {
            return null;
        }
        return ScanExecution.parse(execution)
                .map(Enum::name)
                .orElseThrow(() -> ApiException.badRequest("Unknown defaultExecution \"" + execution
                        + "\" for catalog " + catalogName + "; supported: IN_PROCESS, SPARK"));
    }

    /** 区间校验；{@code null} 透传（表示清除覆盖）。 */
    private static Integer range(Integer value, int min, int max, String field) {
        if (value == null) {
            return null;
        }
        if (value < min || value > max) {
            throw ApiException.badRequest(field + " must be between " + min + " and " + max
                    + ", got " + value);
        }
        return value;
    }
}
