package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.config.RequestContext;
import io.github.melin.lakehouse.rest.domain.entity.SensitiveTypeEntity;
import io.github.melin.lakehouse.rest.domain.repo.SensitiveTypeRepository;
import io.github.melin.lakehouse.rest.dto.SensitivityDtos.SensitiveType;
import io.github.melin.lakehouse.rest.dto.SensitivityDtos.SensitiveTypeList;
import io.github.melin.lakehouse.rest.dto.SensitivityDtos.SensitiveTypeUpsertRequest;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.DetectorConfig;
import io.github.melin.lakehouse.rest.support.Json;
import io.github.melin.lakehouse.rest.support.Paging;
import io.github.melin.lakehouse.rest.support.ResourceType;
import io.github.melin.lakehouse.rest.support.SensitivityDetectors.CandidateType;
import io.github.melin.lakehouse.rest.support.SensitivityEnums.DetectorKind;
import io.github.melin.lakehouse.rest.support.SensitivityEnums.SensitiveCategory;
import io.github.melin.lakehouse.rest.support.SensitivityEnums.SensitivityLevel;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 敏感类型与探测器配置的服务（设计见 {@code docs/sensitive-data-design.md} §4.1 / §5.1 / §5.3）。
 *
 * <p><b>类型表是「识别能力的配置面」</b>，与 finding / tag 那两张「结论面」分开：
 * 改一个类型的权重会改变**下一轮**扫描怎么判，但不会动已经落下的结论
 * （历史标签保留，直到下一次扫描按新口径覆盖）。这正是验收断言
 * 「改一个内置类型的探测器权重后，下一轮扫描的判定随之改变」的落点——
 * 它说的是「下一轮」，不是「立刻重算」。
 *
 * <p><b>内置类型只可停用、不可删。</b>删除会让历史 finding 与 tag 指向一个不存在的
 * {@code type_code}，控制台渲染标签时就只剩一个孤零零的字符串
 * （{@code PII.CN_ID_CARD} 里的后半段没有展示名）。因此 {@code DELETE} 对内置类型
 * 抛 409，而不是静默改成停用——静默改语义会让「我删了怎么还在」变成一个问题。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SensitiveTypeService {

    /** 自动应用阈值允许的范围。0 会让任何命中都自动落标（误脱敏的入口），1 则永远不会自动。 */
    private static final double MIN_THRESHOLD = 0.1d;
    private static final double MAX_THRESHOLD = 1.0d;

    /** 类型编码的形状：大写字母、数字与下划线。它进标签键（{@code PII.CN_ID_CARD}），因此不能有分隔符。 */
    private static final String CODE_PATTERN = "[A-Z][A-Z0-9_]{1,63}";

    private final SensitiveTypeRepository repository;
    private final Clock clock;

    // ------------------------------------------------------------------ 读

    /**
     * 全部类型（含停用的）。
     *
     * <p>列表不分页：类型是「几十」个，与优化策略、数据策略的列表同一取舍——
     * 为它建数据库级分页是把分页复杂度花在永远不会大的集合上。
     */
    @Transactional(readOnly = true)
    public SensitiveTypeList list() {
        return new SensitiveTypeList(repository.findAllByOrderByTypeCodeAsc().stream()
                .map(SensitiveTypeService::toDto)
                .toList());
    }

    /** 按编码取一个类型；不存在抛 404。 */
    @Transactional(readOnly = true)
    public SensitiveType get(String typeCode) {
        return toDto(require(typeCode));
    }

    /**
     * 参与识别的候选类型（扫描用）。
     *
     * <p><b>一条查全量，不在扫描循环里逐表过滤</b>：扫描一个 catalog 会调用它一次
     * （不是每张表一次），因此「先全量再 filter」在这里其实无碍，但把它写成
     * 「只取 enabled 的」是一个更强的承诺——将来若有人把调用点搬进循环，
     * 这个查询不会跟着变成 N 次全量载入。
     *
     * <p>认不出的 {@code category} / {@code level} 会被跳过并留 warn：
     * 类型表是治理员可改的，一行脏数据不该让整轮扫描抛异常——它只该让那个类型
     * 不参与识别（表现为「这个类型扫不出来」，而 warn 里带着 type_code 可查）。
     */
    @Transactional(readOnly = true)
    public List<CandidateType> candidates() {
        List<CandidateType> candidates = new ArrayList<>();
        for (SensitiveTypeEntity entity : repository.findAllByEnabledTrueOrderByTypeCodeAsc()) {
            if (SensitivityLevel.parse(entity.getLevel()).isEmpty()) {
                log.warn("sensitive type {} has an unrecognized level \"{}\"; skipping it in scans",
                        entity.getTypeCode(), entity.getLevel());
                continue;
            }
            candidates.add(new CandidateType(entity.getTypeCode(), entity.getLevel(),
                    entity.getMinConfidence(), entity.isAutoApply(),
                    DetectorConfig.parse(entity.getDetectorsJson())));
        }
        return candidates;
    }

    // ------------------------------------------------------------------ 写

    /**
     * 新建自定义类型。
     *
     * <p>编码必须由调用方给出（不像别的资源用服务端生成 id）：{@code type_code} 会进
     * 标签键与事件属性，是**跨系统契约**的一部分，自动生成的随机串没法被下游引用。
     */
    @Transactional
    public SensitiveType create(SensitiveTypeUpsertRequest request, String principal) {
        if (request == null) {
            throw ApiException.badRequest("request body must not be empty");
        }
        String code = normalizeCode(request.typeCode());
        if (code == null) {
            throw ApiException.badRequest("typeCode is required and must match " + CODE_PATTERN);
        }
        if (repository.existsByTypeCode(code)) {
            throw ApiException.managementAlreadyExist(ResourceType.SENSITIVE_TYPE, code);
        }
        long now = clock.millis();
        SensitiveTypeEntity entity = new SensitiveTypeEntity();
        entity.setTypeCode(code);
        // 自定义类型恒 builtin=false：这个字段只有 seed 能设，API 改不了——
        // 否则「把一个内置类型标成非内置再删掉」就是一条绕过 409 保护的路
        entity.setBuiltin(false);
        apply(entity, request, true);
        entity.markCreated(principal, now);
        return toDto(repository.save(entity));
    }

    /**
     * 整份更新一个类型（内置类型也可改）。
     *
     * <p><b>内置类型的每个字段都可改</b>（除了 {@code builtin} 与 {@code typeCode}）：
     * 验收断言明确要求「改一个内置类型的探测器权重后，下一轮扫描的判定随之改变」，
     * 而权重就住在 {@code detectors} 里。改动的责任由审计承担
     * （{@code updatedBy} / {@code updatedAt} 与事件），不靠「内置就锁死」来免责。
     *
     * <p>{@code typeCode} 在请求体里给了就要求与路径一致（防止把 body 里的编码
     * 静默当成改名——改名会让历史 finding 指向旧编码）。
     */
    @Transactional
    public SensitiveType update(String typeCode, SensitiveTypeUpsertRequest request, String principal) {
        if (request == null) {
            throw ApiException.badRequest("request body must not be empty");
        }
        SensitiveTypeEntity entity = require(typeCode);
        if (request.typeCode() != null && !request.typeCode().isBlank()
                && !request.typeCode().trim().equals(entity.getTypeCode())) {
            throw ApiException.badRequest("typeCode in the request body (" + request.typeCode()
                    + ") does not match the path (" + entity.getTypeCode()
                    + "); a sensitive type cannot be renamed because historical findings and tags"
                    + " reference its code");
        }
        apply(entity, request, false);
        entity.touch(principal, clock.millis());
        return toDto(repository.save(entity));
    }

    /**
     * 删除一个类型。
     *
     * <p><b>内置类型返 409</b>（验收断言之一），自定义类型物理删除。
     *
     * <p>自定义类型为什么可以物理删：它的编码没有被内置 seed 承诺过，
     * 删除是治理员的显式意图；而它可能留下的历史 finding 里存的只是一个字符串
     * （{@code type_code} 列），读出来仍然能显示——缺失的只是「展示名」，
     * 这与内置类型的情况相同。**区别在于内置类型删了就没法恢复**：下次启动
     * seed 会把它重新建出来（typeCode 唯一），于是「删掉了但重启又回来了」
     * 是一个比 409 更难理解的状态。相比之下，自定义类型删了就是删了。
     */
    @Transactional
    public void delete(String typeCode, String principal) {
        SensitiveTypeEntity entity = require(typeCode);
        if (entity.isBuiltin()) {
            throw ApiException.conflict("sensitive type " + entity.getTypeCode()
                    + " is built in and cannot be deleted; disable it instead"
                    + " (PUT with enabled=false) — deleting it would leave historical findings"
                    + " and column tags pointing at a type that no longer exists");
        }
        repository.delete(entity);
    }

    // ------------------------------------------------------------------ 内部

    /** 按编码取实体；不存在抛 404（资源类型是 {@code SENSITIVE_TYPE}）。 */
    @Transactional(readOnly = true)
    public SensitiveTypeEntity require(String typeCode) {
        if (typeCode == null || typeCode.isBlank()) {
            throw ApiException.badRequest("typeCode must not be empty");
        }
        return repository.findByTypeCode(typeCode.trim())
                .orElseThrow(() -> ApiException.managementNotExist(
                        ResourceType.SENSITIVE_TYPE, typeCode.trim()));
    }

    /**
     * 把请求体落到实体上。
     *
     * <p>{@code creating} 决定「没给的字段」怎么处理：新建时缺字段用内置默认
     * （它是「给一个能用的起点」），更新时缺字段保持原值（PUT 的逐字段语义，
     * 与 {@code ExecutionClusterDtos} 的「留空 = 保持原样」同一约定）。
     * 不做成「整份替换」的理由：{@code detectors} 是一棵嵌套结构，
     * 要求调用方每次改动都回填完整树，等于让「只想改阈值」的请求也承担
     * 「把探测器配错一遍」的风险。
     */
    private void apply(SensitiveTypeEntity entity, SensitiveTypeUpsertRequest request, boolean creating) {
        if (hasText(request.typeName())) {
            entity.setTypeName(request.typeName().trim());
        } else if (creating) {
            throw ApiException.badRequest("typeName is required");
        }
        if (hasText(request.category())) {
            SensitiveCategory category = SensitiveCategory.parse(request.category())
                    .orElseThrow(() -> ApiException.badRequest("Unknown category: " + request.category()
                            + ", supported: " + categoryNames()));
            entity.setCategory(category.name());
        } else if (creating) {
            throw ApiException.badRequest("category is required, supported: " + categoryNames());
        }
        if (hasText(request.level())) {
            SensitivityLevel level = SensitivityLevel.parse(request.level())
                    .orElseThrow(() -> ApiException.badRequest("Unknown level: " + request.level()
                            + ", supported: L1, L2, L3, L4 (CORE / IMPORTANT can only be applied"
                            + " to column tags by hand)"));
            if (!level.automatable()) {
                // 类型表的 level 是「探测器命中后落标签用的分级」，而 CORE / IMPORTANT
                // 按国家与行业目录人工认定（§5.5）——探测器没有资格产出它们。
                // 允许写进类型表，等于允许扫描产出一条「重要数据」的结论。
                throw ApiException.badRequest(level + " cannot be a sensitive type level:"
                        + " CORE and IMPORTANT data are designated by national and industry"
                        + " catalogues, not inferred by detectors (see design §5.5)");
            }
            entity.setLevel(level.name());
        } else if (creating) {
            throw ApiException.badRequest("level is required, supported: L1, L2, L3, L4");
        }
        if (request.detectors() != null) {
            entity.setDetectorsJson(normalizeDetectors(request.detectors()));
        }
        if (request.autoApply() != null) {
            entity.setAutoApply(request.autoApply());
        }
        if (request.minConfidence() != null) {
            double threshold = request.minConfidence();
            if (threshold < MIN_THRESHOLD || threshold > MAX_THRESHOLD) {
                throw ApiException.badRequest("minConfidence must be between " + MIN_THRESHOLD
                        + " and " + MAX_THRESHOLD + " (0 would auto-apply every single hit,"
                        + " 1 would never auto-apply)");
            }
            entity.setMinConfidence(threshold);
        }
        if (request.enabled() != null) {
            entity.setEnabled(request.enabled());
        }
    }

    /**
     * 把 {@code detectors} 收成一份干净的 JSON 文本。
     *
     * <p><b>走一次 {@code DetectorConfig.parse} 再序列化回去</b>，而不是把请求体
     * 原样存下：认不出的层（拼错的 {@code patterns}、不是数组的 {@code types}）
     * 会被丢掉，库里永远是一份「解析得动」的配置。存原文的话，错误的形状会一直
     * 留在库里，直到某天有人调扫描才发现「这一层配了但不生效」。
     *
     * <p>全空的配置不当成错误：{@code {"name":{}} 与不写 {@code detectors}
     * 在效果上都是「这个类型没有可用判据」——它从此不会被任何列命中。
     * 允许它是为了「先把类型建出来、探测器随后再配」这个真实流程；
     * 拒绝它只会逼调用方先编一个假的规则。
     */
    private String normalizeDetectors(Object detectors) {
        String json;
        try {
            json = Json.write(detectors);
        } catch (RuntimeException e) {
            throw ApiException.badRequest("detectors must be a JSON object of layer -> layer config");
        }
        DetectorConfig parsed = DetectorConfig.parse(json);
        if (parsed.configuredLayers().isEmpty()) {
            log.info("sensitive type detectors have no recognizable layer; the type will not match"
                    + " any column until a layer is configured");
        }
        return parsed.toJson();
    }

    /** 编码归一：只大写化不猜测，形状不对返回 {@code null} 由调用方报错。 */
    private static String normalizeCode(String typeCode) {
        if (typeCode == null || typeCode.isBlank()) {
            return null;
        }
        String code = typeCode.trim().toUpperCase(java.util.Locale.ROOT);
        return code.matches(CODE_PATTERN) ? code : null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String categoryNames() {
        return String.join(", ", java.util.Arrays.stream(SensitiveCategory.values())
                .map(Enum::name).toList());
    }

    // ------------------------------------------------------------------ 映射

    /**
     * 实体 → 读模型。
     *
     * <p>{@code detectors} 回结构化对象而不是 JSON 字符串：控制台要逐层编辑它，
     * 回字符串等于把解析规则复制一份到 JS 里。{@code configuredLayers} 是
     * 「实际配了哪几层」的摘要——界面要能一眼看出「这个类型只靠列名判」，
     * 而不必展开 JSON 自己数。
     */
    static SensitiveType toDto(SensitiveTypeEntity entity) {
        DetectorConfig config = DetectorConfig.parse(entity.getDetectorsJson());
        Map<String, Object> detectors = new LinkedHashMap<>();
        for (DetectorKind kind : config.configuredLayers()) {
            detectors.put(kind.name().toLowerCase(java.util.Locale.ROOT),
                    layerToMap(kind, config));
        }
        return new SensitiveType(entity.getTypeCode(), entity.getTypeName(), entity.getCategory(),
                entity.getLevel(), detectors, entity.isAutoApply(),
                io.github.melin.lakehouse.rest.support.SensitivityEnums
                        .defaultAutoApply(entity.getTypeCode()),
                entity.getMinConfidence(), entity.isBuiltin(), entity.isEnabled(),
                config.configuredLayers().stream().map(DetectorKind::name).toList(),
                entity.getCreatedBy(), entity.getCreatedAt(),
                entity.getUpdatedBy(), entity.getUpdatedAt());
    }

    /** 把一层展开成控制台好编辑的键值对（只放非空项，空项在界面上就是「没填」）。 */
    private static Map<String, Object> layerToMap(DetectorKind kind, DetectorConfig config) {
        DetectorConfig.LayerConfig layer = config.layer(kind);
        Map<String, Object> out = new LinkedHashMap<>();
        if (!layer.patterns().isEmpty()) {
            out.put("patterns", layer.patterns());
        }
        if (!layer.types().isEmpty()) {
            out.put("types", layer.types());
        }
        if (!layer.lengths().isEmpty()) {
            out.put("lengths", layer.lengths());
        }
        if (layer.regex() != null) {
            out.put("regex", layer.regex());
        }
        if (layer.validator() != null) {
            out.put("validator", layer.validator());
        }
        if (!layer.values().isEmpty()) {
            out.put("values", layer.values());
        }
        if (layer.maxCardinalityRatio() != null) {
            out.put("maxCardinalityRatio", layer.maxCardinalityRatio());
        }
        if (layer.weight() != 1.0) {
            out.put("weight", layer.weight());
        }
        return out;
    }
}
