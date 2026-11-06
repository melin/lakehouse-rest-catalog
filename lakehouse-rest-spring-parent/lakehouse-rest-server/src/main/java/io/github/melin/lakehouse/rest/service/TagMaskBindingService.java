package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.config.RequestContext;
import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.domain.entity.TagMaskBindingEntity;
import io.github.melin.lakehouse.rest.domain.repo.CatalogRepository;
import io.github.melin.lakehouse.rest.domain.repo.TagMaskBindingRepository;
import io.github.melin.lakehouse.rest.dto.SensitivityDtos.TagMaskBinding;
import io.github.melin.lakehouse.rest.dto.SensitivityDtos.TagMaskBindingList;
import io.github.melin.lakehouse.rest.dto.SensitivityDtos.TagMaskBindingUpsertRequest;
import io.github.melin.lakehouse.rest.event.CatalogEvent;
import io.github.melin.lakehouse.rest.event.CatalogEventPublisher;
import io.github.melin.lakehouse.rest.event.CatalogEventType;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.Json;
import io.github.melin.lakehouse.rest.support.PolicyExpressions;
import io.github.melin.lakehouse.rest.support.ResourceType;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 标签 → 脱敏动作绑定的服务（设计见 {@code docs/sensitive-data-design.md} §4.5 / §8.1 / §9）。
 *
 * <p><b>它是 P3 唯一的新写路径，而写入的口径只有一条：这份 mask 要能落到未知的列上。</b>
 * 绑定是 catalog 级规则（「凡是 {@code PII.CN_ID_CARD} 的列都这么脱敏」），写入时
 * 还不知道会命中哪些表、哪些列，因此：
 * <ul>
 *   <li>mask 用 {@link PolicyExpressions#parseMaskTemplate} 校验——标准集查函数与参数，
 *       逃生口查白名单并<b>禁止引用列</b>；「结果类型等于列类型」留给引擎编译时兜
 *       （理由见那个方法的注释）；</li>
 *   <li>{@code tagValue} 为空 = 该键任意分级，落库统一归一成<b>空串</b>（不是 {@code null}）。
 *       这不是洁癖：唯一键是 {@code (catalog_name, tag_key, tag_value)}，而 MySQL 唯一索引下
 *       多行 {@code NULL} 互不冲突——用 {@code null} 存通配，同一个键能插两条通配绑定，
 *       之后哪条生效取决于查询顺序。</li>
 * </ul>
 *
 * <p><b>为什么没有乐观锁。</b>{@code lakehouse_tag_mask_binding} 是 P0 建的六张表之一，
 * 字段表（§4.5）里没有 {@code entity_version}，加列要走一次 V5 迁移。而绑定的量级是
 * 「几条到几十条」、冲突后果是「一条规则被覆盖」——下一轮 resolve 就能看出来，
 * 不像标签那样会静默丢一条人工结论。因此 PUT 是 last-write-wins，审计靠
 * {@code updatedBy} / {@code updatedAt}。这条取舍写在这里，是因为
 * {@code ColumnTagService} 那一侧刻意选了相反的一侧（人工结论不能丢）；
 * 两个服务口径不同是有意的，不是漏了。
 *
 * <p><b>事件与 {@code PUT /sensitivity-settings} 共用 {@code sensitivity.binding.changed}。</b>
 * 设计稿的事件清单只给了这一个类型承载「catalog 级治理配置变更」。因此这里
 * 在属性里写 {@code kind=BINDING}，而设置那条写 {@code kind=SETTINGS}——
 * 下游订阅方要能判断「该刷新哪份缓存」（引擎侧 60s TTL 的策略缓存 vs 扫描配置）。
 * 两边都写 {@code kind} 而不是「只有一处写」，是为了让判断逻辑不必依赖
 * 「没有 kind 就当设置」这种隐式约定。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TagMaskBindingService {

    /** 事件属性里的种类标记（与 {@code SensitivitySettingsService} 共用同一个事件类型）。 */
    public static final String EVENT_KIND = "BINDING";

    /** 渲染模板 SQL 时用的占位列名：绑定还不知道落在哪一列上，展示成 {@code show_first_n(column, 3)}。 */
    private static final String TEMPLATE_PLACEHOLDER_COLUMN = "column";

    private final TagMaskBindingRepository bindingRepository;
    private final CatalogRepository catalogRepository;
    private final CatalogEventPublisher events;
    private final Clock clock;

    // ------------------------------------------------------------------ 读

    /**
     * {@code GET /tag-mask-bindings?catalog=}：一个 catalog 的全部绑定，**含停用项**。
     *
     * <p>不分页：量级是「几条到几十条」（catalog 级规则），为它做分页是把复杂度花在
     * 永远不会大的集合上——与类型列表、抑制项列表同一取舍。停用项必须回：
     * 「我停过哪条」与「我从没配过」在界面上是两件事，后者会让人重复配一条已经在的表。
     */
    @Transactional(readOnly = true)
    public TagMaskBindingList list(String catalog) {
        String catalogName = requireCatalogName(catalog);
        requireCatalog(catalogName);
        List<TagMaskBinding> items = new ArrayList<>();
        for (TagMaskBindingEntity entity
                : bindingRepository.findByCatalogNameOrderByPriorityDesc(catalogName)) {
            items.add(toDto(entity));
        }
        return new TagMaskBindingList(items);
    }

    // ------------------------------------------------------------------ 写

    /**
     * {@code PUT /tag-mask-bindings}：按唯一键 {@code (catalog, tagKey, tagValue)} upsert。
     *
     * <p>重复 PUT 是更新而不是新增：唯一键本身就是这条规则的地址（见 DTO 注释）。
     * <b>内容没变不发事件</b>——同一个值连按两次保存不该在事件流里留两条，
     * 否则「绑定变更」这条流会被无意义的重复写满，真正的那次改动反而淹没在里面。
     */
    @Transactional
    public TagMaskBinding upsert(TagMaskBindingUpsertRequest request, String principal) {
        if (request == null) {
            throw ApiException.badRequest("request body must not be empty");
        }
        String catalogName = requireCatalogName(request.catalog());
        requireCatalog(catalogName);
        String tagKey = requireText(request.tagKey(), "tagKey");
        String tagValue = normalizeTagValue(request.tagValue());
        if (request.mask() == null) {
            throw ApiException.badRequest("mask must not be empty");
        }
        PolicyExpressions.ValidatedMask parsed =
                PolicyExpressions.parseMaskTemplate(request.mask(), TEMPLATE_PLACEHOLDER_COLUMN);
        String maskJson = Json.write(parsed.ast());
        int priority = request.priority() == null
                ? TagMaskExpander.DEFAULT_PRIORITY : request.priority();

        TagMaskBindingEntity entity = bindingRepository
                .findByCatalogNameAndTagKeyAndTagValue(catalogName, tagKey, tagValue)
                .orElse(null);
        boolean created = entity == null;
        long now = clock.millis();
        boolean enabled = request.enabled() != null
                ? request.enabled()
                : (created || entity.isEnabled());

        // 内容没变：直接回当前状态，不动 updatedAt、不发事件（审计要能区分「谁真的改了」）
        if (!created && sameContent(entity, maskJson, priority, enabled)) {
            return toDto(entity);
        }

        if (created) {
            entity = new TagMaskBindingEntity();
            entity.setCatalogName(catalogName);
            entity.setTagKey(tagKey);
            entity.setTagValue(tagValue);
            entity.markCreated(principal, now);
        }
        entity.setMaskJson(maskJson);
        entity.setMaskSql(parsed.sql());
        entity.setPriority(priority);
        entity.setEnabled(enabled);
        entity.touch(principal, now);
        TagMaskBindingEntity saved = bindingRepository.save(entity);

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("kind", EVENT_KIND);
        attributes.put("action", created ? "CREATED" : "UPDATED");
        attributes.put("tagKey", tagKey);
        attributes.put("tagValue", tagValue);
        attributes.put("priority", priority);
        attributes.put("enabled", enabled);
        attributes.put("maskSql", saved.getMaskSql());
        attributes.put("catalog", catalogName);
        events.publish(CatalogEventType.SENSITIVITY_BINDING_CHANGED, catalogName,
                CatalogEvent.TARGET_CATALOG, catalogName, attributes);
        log.info("tag mask binding {} for {}/{}={} by {}", created ? "created" : "updated",
                catalogName, tagKey, tagValue.isEmpty() ? "<any>" : tagValue, principal);
        return toDto(saved);
    }

    /**
     * {@code DELETE /tag-mask-bindings/{id}}：物理删除一条绑定。
     *
     * <p>物理删除而不是置 {@code enabled=false}：两者都在数据模型里（{@code enabled_flag}
     * 是「保留规则但不参与展开」），语义不同——停用是「先关掉观察一下」，删除是
     * 「这条规则不要了」。删错了可以按同样的键再 PUT 一次回来，所以「不敢删」不是问题；
     * 而停用之后这条绑定仍会出现在列表里，正适合「临时关掉」。
     *
     * <p>删除之后，之前靠它脱敏的列会**立刻**回到明文（引擎侧最多晚 60 秒缓存）。
     * 这不是一个可以静默发生的动作，所以事件照发——它是「为什么这些列突然明文了」
     * 的唯一一手线索。
     */
    @Transactional
    public void delete(Long id, String principal) {
        TagMaskBindingEntity entity = require(id);
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("kind", EVENT_KIND);
        attributes.put("action", "DELETED");
        attributes.put("tagKey", entity.getTagKey());
        attributes.put("tagValue", entity.getTagValue());
        attributes.put("priority", entity.getPriority());
        attributes.put("maskSql", entity.getMaskSql());
        attributes.put("catalog", entity.getCatalogName());
        attributes.put("deletedBy", principal);
        bindingRepository.delete(entity);
        events.publish(CatalogEventType.SENSITIVITY_BINDING_CHANGED, entity.getCatalogName(),
                CatalogEvent.TARGET_CATALOG, entity.getCatalogName(), attributes);
        log.info("tag mask binding {} deleted by {}", id, principal);
    }

    /** 按 id 取绑定；不存在抛 404（资源类型是 {@code TAG_MASK_BINDING}）。 */
    @Transactional(readOnly = true)
    public TagMaskBindingEntity require(Long id) {
        if (id == null) {
            throw ApiException.badRequest("id must not be empty");
        }
        return bindingRepository.findById(id)
                .orElseThrow(() -> ApiException.managementNotExist(
                        ResourceType.TAG_MASK_BINDING, String.valueOf(id)));
    }

    // ------------------------------------------------------------------ 内部

    /** 内容是否与库里那条完全一致（比较落库形态，不比较请求原值）。 */
    private static boolean sameContent(TagMaskBindingEntity entity, String maskJson,
                                       int priority, boolean enabled) {
        return maskJson.equals(entity.getMaskJson())
                && priority == entity.getPriority()
                && enabled == entity.isEnabled();
    }

    /**
     * 通配归一：{@code null} / 空串 / 全空白 → 空串。
     *
     * <p>理由见类注释与 {@code TagMaskBindingEntity} 的类注释：唯一索引下
     * 多行 {@code NULL} 互不冲突，用 {@code null} 存通配会让同一把键能插两条。
     * 读侧（{@code TagMaskExpander.matches}）仍把 {@code null} 当通配，
     * 因为 P0 建的存量行里可能有 {@code null}。
     */
    private static String normalizeTagValue(String tagValue) {
        if (tagValue == null) {
            return "";
        }
        String trimmed = tagValue.trim();
        return trimmed;
    }

    private CatalogEntity requireCatalog(String catalogName) {
        return catalogRepository.findByPrefix(catalogName)
                .orElseThrow(() -> ApiException.managementNotExist(ResourceType.CATALOG, catalogName));
    }

    private static String requireCatalogName(String catalog) {
        return requireText(catalog, "catalog");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(field + " must not be empty");
        }
        return value.trim();
    }

    private static TagMaskBinding toDto(TagMaskBindingEntity entity) {
        String tagKey = entity.getTagKey();
        String tagValue = entity.getTagValue();
        return new TagMaskBinding(entity.getId(), entity.getCatalogName(), tagKey, tagValue,
                tagKey != null && tagKey.endsWith(TagMaskExpander.WILDCARD_KEY_SUFFIX),
                tagValue == null || tagValue.isEmpty(),
                entity.getMaskJson() == null ? null : Json.read(entity.getMaskJson(), Map.class),
                entity.getMaskSql(), entity.getPriority(), entity.isEnabled(),
                entity.getUpdatedBy(), entity.getUpdatedAt());
    }
}
