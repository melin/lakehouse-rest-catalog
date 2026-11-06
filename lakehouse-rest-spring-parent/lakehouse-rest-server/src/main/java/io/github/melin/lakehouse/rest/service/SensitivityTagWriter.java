package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.domain.entity.ColumnTagEntity;
import io.github.melin.lakehouse.rest.domain.entity.SensitiveTypeEntity;
import io.github.melin.lakehouse.rest.domain.repo.ColumnTagRepository;
import io.github.melin.lakehouse.rest.event.CatalogEvent;
import io.github.melin.lakehouse.rest.event.CatalogEventPublisher;
import io.github.melin.lakehouse.rest.event.CatalogEventType;
import io.github.melin.lakehouse.rest.support.ColumnRefs;
import io.github.melin.lakehouse.rest.support.SensitivityEnums.SensitivityLevel;
import io.github.melin.lakehouse.rest.support.SensitivityEnums.TagSource;
import io.github.melin.lakehouse.rest.support.SensitivityTags;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 标签的**写入方**：扫描的自动应用、人审的确认、表级级别的重算都走这里
 * （设计见 {@code docs/sensitive-data-design.md} §5.3 / §5.5 / §7.2）。
 *
 * <p><b>为什么把写标签收成一个组件，而不是让扫描与人审各写一遍。</b>
 * 写标签有五件事必须一起做对：唯一键寻址（{@code column_ref_hash + tag_key}）、
 * 乐观锁版本号、{@code source} 的优先级、事件、以及表级级别的重算。
 * 两条写路径各写一遍的漂移表现是「自动标签会更新表级级别、人审确认的不会」——
 * 而这种缺失不报错，只是某个页面的数字长期偏低。
 *
 * <p><b>{@code source} 的优先级是硬规则：人工结论不被自动结论覆盖。</b>
 * 治理员手工把某列从 {@code L4} 降到 {@code L3} 之后，下一轮扫描不能把它写回
 * {@code L4}——那等于「人做的判断每天被人推翻一次」，而这个系统存在的理由
 * 恰恰是让人的判断留下来。反向是允许的：人工确认一条 {@code AUTO} 标签时，
 * 它升级为 {@code MANUAL}（确认动作本身就是权威性更高的结论）。
 *
 * <p><b>表级标签（{@code column_name} 空串）在这里统一重算。</b>它的值是「这张表
 * 所有列标签的 {@code max(level)}」（§5.5），因此任何一次标签写入 / 删除之后
 * 它都可能变。表级标签**不参与策略展开**（§5.5 已拍板），所以它的写入不会
 * 改变任何一列的脱敏行为——这一点是它敢在每次写标签后顺带算一次的前提。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SensitivityTagWriter {

    private final ColumnTagRepository tagRepository;
    private final SensitiveTypeService typeService;
    private final CatalogEventPublisher events;
    private final Clock clock;

    /**
     * 落一条敏感标签（upsert）。
     *
     * @param catalog    catalog 名
     * @param database   库名
     * @param table      表名
     * @param column     列名（空串 = 表级，调用方不要用它写表级标签，走 {@link #refreshTableLevel}）
     * @param typeCode   敏感类型编码
     * @param confidence 置信度（{@code AUTO} / {@code INHERITED} 才有值；人工确认传扫描算出的那个）
     * @param findingId  溯源 finding（可空）
     * @param source     来源
     * @param principal  写入主体
     * @return 落下的标签；**被人工结论挡住时不写，返回空**（见类注释的优先级规则）
     */
    @Transactional
    public Optional<ColumnTagEntity> apply(String catalog, String database, String table, String column,
                                           String typeCode, Double confidence, Long findingId,
                                           TagSource source, String principal) {
        SensitiveTypeEntity type = typeService.require(typeCode);
        SensitivityLevel level = SensitivityLevel.parse(type.getLevel()).orElse(null);
        if (level == null) {
            // 类型表的 level 只允许 L1-L4（CORE / IMPORTANT 只人工打，不进类型表）。
            // 走到这里说明有一行被手工改坏了：拒绝写，而不是落一条没有分级的敏感标签
            // ——那种标签在所有按级别组织的视图里都会消失。
            log.warn("sensitive type {} has no parsable level ({}), tag not written",
                    typeCode, type.getLevel());
            return Optional.empty();
        }
        String tagKey = SensitivityTags.sensitiveTagKey(type.getCategory(), typeCode);
        String referenceHash = ColumnRefs.hash(catalog, database, table, column);
        long now = clock.millis();
        ColumnTagEntity entity = tagRepository.findByColumnRefHashAndTagKey(referenceHash, tagKey)
                .orElse(null);
        boolean created = entity == null;
        if (created) {
            entity = new ColumnTagEntity();
            entity.setCatalogName(catalog);
            entity.setDatabaseName(database);
            entity.setTableName(table);
            entity.setColumnName(column);
            entity.setColumnRefHash(referenceHash);
            entity.setTagKey(tagKey);
            entity.setEntityVersion(0);
            entity.markCreated(principal, now);
        } else {
            if (source == TagSource.AUTO && TagSource.MANUAL.name().equals(entity.getSource())) {
                // 人工结论不被自动结论覆盖（类注释的第一条硬规则）
                return Optional.empty();
            }
            entity.setEntityVersion(entity.getEntityVersion() + 1);
            entity.touch(principal, now);
        }
        entity.setTagValue(level.name());
        entity.setTypeCode(typeCode);
        entity.setSource(source.name());
        entity.setConfidence(confidence);
        entity.setFindingId(findingId);
        entity.setEnabled(true);
        // inheritedFrom 只由血缘传播（P2）写；这里显式清空，避免「上一条是继承、
        // 这条是人工确认」时残留一个说不清来历的来源列
        entity.setInheritedFrom(null);
        ColumnTagEntity saved = tagRepository.save(entity);

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("database", database);
        attributes.put("table", table);
        attributes.put("column", column);
        attributes.put("columnRef", ColumnRefs.fullName(catalog, database, table, column));
        attributes.put("tagKey", tagKey);
        attributes.put("tagValue", saved.getTagValue());
        attributes.put("typeCode", typeCode);
        attributes.put("level", level.name());
        attributes.put("source", saved.getSource());
        attributes.put("confidence", confidence);
        attributes.put("change", created ? "CREATED" : "UPDATED");
        events.publish(CatalogEventType.COLUMN_TAG_CHANGED, catalog, CatalogEvent.TARGET_TABLE,
                database + "." + table, attributes);
        return Optional.of(saved);
    }

    /**
     * 落一条**血缘继承**来的标签（设计稿 §7.1）。
     *
     * <p><b>它是 {@link #apply} 之外的一条独立入口，而不是给 {@code apply} 多一个参数。</b>
     * 两条路径有三处语义不同，塞进一个方法只会让每处都要靠 {@code if} 分辨：
     * <ul>
     *   <li><b>凭什么写。</b>{@code apply} 凭「扫描自己判出来了」，本方法凭「上游是这样」——
     *       后者必须记下 {@code inherited_from}，否则这条标签既解释不了、也追不回去；</li>
     *   <li><b>能不能覆盖。</b>{@code apply} 只挡 {@code MANUAL}（自动不推翻人工）；本方法
     *       还要挡 {@code AUTO}：扫描在**这一列上**直接判出来的结论，证据强于「从上游推来」，
     *       拿继承去覆盖它是一次降级——而级别没变、只是来源变了的覆盖，在界面上看不出来；</li>
     *   <li><b>幂等靠什么。</b>同一轮传播会被重放（扫描重试、多实例），重复写同一条结论
     *       不该在事件流里留下一串 {@code UPDATED}。</li>
     * </ul>
     *
     * <p><b>置信度由调用方算好传进来。</b>逐跳折损（{@code DIRECT} ×0.9、{@code DERIVED}
     * 再 ×0.6）是传播那一侧的策略，写标签这一侧只负责落库——把它挪进来，等于让
     * 「写一条标签」隐式依赖「这条标签是怎么来的」。
     *
     * @param inheritedFrom 上游列全名（{@code catalog.db.table.column}），必填
     * @return 落下的标签；**被人工 / 自动结论挡住、或与现有继承结论等同时返回空**
     */
    @Transactional
    public Optional<ColumnTagEntity> applyInherited(String catalog, String database, String table,
                                                    String column, String typeCode, Double confidence,
                                                    String inheritedFrom, String principal) {
        SensitiveTypeEntity type = typeService.require(typeCode);
        SensitivityLevel level = SensitivityLevel.parse(type.getLevel()).orElse(null);
        if (level == null) {
            // 与 apply 同一条理由：没有分级的敏感标签在所有按级别组织的视图里都会消失
            log.warn("sensitive type {} has no parsable level ({}), inherited tag not written",
                    typeCode, type.getLevel());
            return Optional.empty();
        }
        String tagKey = SensitivityTags.sensitiveTagKey(type.getCategory(), typeCode);
        String referenceHash = ColumnRefs.hash(catalog, database, table, column);
        ColumnTagEntity entity = tagRepository.findByColumnRefHashAndTagKey(referenceHash, tagKey)
                .orElse(null);
        if (entity != null) {
            TagSource existing = TagSource.parse(entity.getSource()).orElse(TagSource.MANUAL);
            if (existing == TagSource.MANUAL) {
                // 人工结论优先：治理员已经就这一列下过判断，继承不该把它写回算法结论
                return Optional.empty();
            }
            if (existing == TagSource.AUTO) {
                // 扫描在这一列上直接判出来的结论强于「从上游推来的」——覆盖是一次降级
                return Optional.empty();
            }
            if (!shouldRewrite(entity, level, confidence, inheritedFrom)) {
                // 同一轮传播重放：结论一模一样就不写，避免事件流里一串无意义的 UPDATED
                return Optional.empty();
            }
            entity.setEntityVersion(entity.getEntityVersion() + 1);
            entity.touch(principal, clock.millis());
        } else {
            entity = new ColumnTagEntity();
            entity.setCatalogName(catalog);
            entity.setDatabaseName(database);
            entity.setTableName(table);
            entity.setColumnName(column);
            entity.setColumnRefHash(referenceHash);
            entity.setTagKey(tagKey);
            entity.setEntityVersion(0);
            entity.markCreated(principal, clock.millis());
        }
        entity.setTagValue(level.name());
        entity.setTypeCode(typeCode);
        entity.setSource(TagSource.INHERITED.name());
        entity.setConfidence(confidence);
        // 继承标签没有自己的 finding：它凭的是上游那条结论，不是这一轮的某个探测器命中
        entity.setFindingId(null);
        entity.setInheritedFrom(inheritedFrom);
        entity.setEnabled(true);
        ColumnTagEntity saved = tagRepository.save(entity);

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("database", database);
        attributes.put("table", table);
        attributes.put("column", column);
        attributes.put("columnRef", ColumnRefs.fullName(catalog, database, table, column));
        attributes.put("tagKey", tagKey);
        attributes.put("tagValue", saved.getTagValue());
        attributes.put("typeCode", typeCode);
        attributes.put("level", level.name());
        attributes.put("source", saved.getSource());
        attributes.put("confidence", confidence);
        attributes.put("inheritedFrom", inheritedFrom);
        attributes.put("change", "UPDATED");
        // 事件必须发：引擎侧的策略缓存按 catalog.db.table 驱逐（设计稿 §8.1）。
        // 继承标签同样会改变某一列的 mask，漏发这条事件的表现是「下游已经继承了，
        // 但上下游两边读出来的脱敏不一致」，而它要等缓存 TTL 到期才自愈。
        events.publish(CatalogEventType.COLUMN_TAG_CHANGED, catalog, CatalogEvent.TARGET_TABLE,
                database + "." + table, attributes);
        return Optional.of(saved);
    }

    /**
     * 现有的继承标签要不要重写。
     *
     * <p>三条都变了才算「有新结论」：级别、置信度（容差 {@code 1e-6}，逐跳相乘会累积
     * 浮点误差）、来源列。只比级别会让「上游换了列、但级别一样」的继承永远停在旧的
     * {@code inherited_from} 上，而那一列在血缘图上已经不再流向这里。
     */
    private static boolean shouldRewrite(ColumnTagEntity entity, SensitivityLevel level,
                                         Double confidence, String inheritedFrom) {
        if (!level.name().equals(entity.getTagValue())) {
            return true;
        }
        if (!inheritedFrom.equals(entity.getInheritedFrom())) {
            return true;
        }
        double previous = entity.getConfidence() == null ? -1d : entity.getConfidence();
        return confidence == null || Math.abs(previous - confidence) > 1e-6;
    }

    /**
     * 撤掉一条**自动得出**的标签（{@code AUTO} 与 {@code INHERITED}，人审驳回 / 自动应用不再成立时）。
     *
     * <p><b>只删这两种来源的</b>：人审驳回说的是「这个结论不成立」，不是
     * 「这条人工结论也错了」。删掉人工标签会让一次误点毁掉一条治理结论，
     * 而人工结论无法从任何地方重建。
     *
     * <p><b>{@code INHERITED} 必须一起来。</b>P2 起驳回一条继承来的结论（比如
     * 「md5 之后的列仍然算身份证」这个判断被推翻）如果只删 {@code AUTO}，表现是
     * 人审队列里的状态变了、抑制项也落了，而**那一列的 mask 还在生效**——
     * 用户看到的是「我驳回了，它照样打码」，这比不让人驳回更坏。
     *
     * @return 真的删了返回 {@code true}（没有可删的也是 {@code false}，不报错）
     */
    @Transactional
    public boolean removeAutomatic(String catalog, String database, String table, String column,
                                   String typeCode, String principal) {
        SensitiveTypeEntity type = typeService.require(typeCode);
        String tagKey = SensitivityTags.sensitiveTagKey(type.getCategory(), typeCode);
        String referenceHash = ColumnRefs.hash(catalog, database, table, column);
        ColumnTagEntity entity = tagRepository.findByColumnRefHashAndTagKey(referenceHash, tagKey)
                .orElse(null);
        if (entity == null) {
            return false;
        }
        TagSource source = TagSource.parse(entity.getSource()).orElse(TagSource.MANUAL);
        if (source != TagSource.AUTO && source != TagSource.INHERITED) {
            // MANUAL：人审驳回不代表人工结论也错了（见方法注释）
            return false;
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("database", database);
        attributes.put("table", table);
        attributes.put("column", column);
        attributes.put("columnRef", ColumnRefs.fullName(catalog, database, table, column));
        attributes.put("tagKey", tagKey);
        attributes.put("beforeTagValue", entity.getTagValue());
        attributes.put("typeCode", typeCode);
        attributes.put("source", entity.getSource());
        attributes.put("change", "DELETED");
        attributes.put("deletedBy", principal);
        events.publish(CatalogEventType.COLUMN_TAG_CHANGED, catalog, CatalogEvent.TARGET_TABLE,
                database + "." + table, attributes);
        tagRepository.delete(entity);
        return true;
    }

    /**
     * 重算一张表的表级级别（{@code column_name=''} 那一行）。
     *
     * <p>三条规则：
     * <ol>
     *   <li>值 = 所有**启用的列级**标签的 {@code max(level)}（{@link SensitivityTags#max}）；</li>
     *   <li>某一行的表级标签若是 {@code MANUAL}（治理员手工定的整表级别，比如判了
     *       {@code CORE}），**不覆盖**——理由与列级标签的优先级相同；</li>
     *   <li>列级标签全没了（或都停用）→ 删掉自动产生的表级标签。留着会让
     *       「这张表是 L4」这个说法失去依据，而它恰恰是要被拿去对外说的一句话。</li>
     * </ol>
     *
     * <p>它**不**参与策略展开（§5.5 拍板），因此这里多写一行不会改变任何列的脱敏行为。
     */
    @Transactional
    public void refreshTableLevel(String catalog, String database, String table, String principal) {
        List<ColumnTagEntity> tags =
                tagRepository.findByCatalogNameAndDatabaseNameAndTableName(catalog, database, table);
        SensitivityLevel highest = null;
        ColumnTagEntity tableLevel = null;
        for (ColumnTagEntity tag : tags) {
            if (ColumnRefs.isTableLevel(tag.getColumnName())) {
                if (SensitivityTags.TABLE_LEVEL_TAG_KEY.equals(tag.getTagKey())) {
                    tableLevel = tag;
                }
                continue;
            }
            if (!tag.isEnabled()) {
                continue;
            }
            highest = SensitivityTags.max(highest, tag.level());
        }
        long now = clock.millis();
        if (tableLevel != null && TagSource.MANUAL.name().equals(tableLevel.getSource())) {
            return;
        }
        if (highest == null) {
            if (tableLevel != null) {
                Map<String, Object> attributes = new LinkedHashMap<>();
                attributes.put("database", database);
                attributes.put("table", table);
                attributes.put("column", "");
                attributes.put("tagKey", SensitivityTags.TABLE_LEVEL_TAG_KEY);
                attributes.put("beforeTagValue", tableLevel.getTagValue());
                attributes.put("change", "DELETED");
                attributes.put("deletedBy", principal);
                attributes.put("reason", "no enabled column-level tag is left");
                events.publish(CatalogEventType.COLUMN_TAG_CHANGED, catalog,
                        CatalogEvent.TARGET_TABLE, database + "." + table, attributes);
                tagRepository.delete(tableLevel);
            }
            return;
        }
        if (tableLevel != null && highest.name().equals(tableLevel.getTagValue())) {
            return;
        }
        boolean created = tableLevel == null;
        ColumnTagEntity entity = created ? new ColumnTagEntity() : tableLevel;
        long version = created ? 0 : entity.getEntityVersion() + 1;
        String before = created ? null : entity.getTagValue();
        if (created) {
            entity.setCatalogName(catalog);
            entity.setDatabaseName(database);
            entity.setTableName(table);
            // 空串 = 表级（照抄血缘边「常量列用空串占位」的做法，不引入可空列）
            entity.setColumnName(ColumnRefs.TABLE_LEVEL_COLUMN);
            entity.setColumnRefHash(ColumnRefs.hash(catalog, database, table,
                    ColumnRefs.TABLE_LEVEL_COLUMN));
            entity.setTagKey(SensitivityTags.TABLE_LEVEL_TAG_KEY);
            entity.setSource(TagSource.AUTO.name());
            entity.markCreated(principal, now);
        } else {
            entity.touch(principal, now);
        }
        entity.setTagValue(highest.name());
        // 表级标签没有对应的类型（它不是「某一列是什么类型」），因此 typeCode 恒空
        entity.setTypeCode(null);
        entity.setConfidence(null);
        entity.setFindingId(null);
        entity.setInheritedFrom(null);
        entity.setEnabled(true);
        entity.setEntityVersion((int) version);
        ColumnTagEntity saved = tagRepository.save(entity);

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("database", database);
        attributes.put("table", table);
        attributes.put("column", "");
        attributes.put("tagKey", SensitivityTags.TABLE_LEVEL_TAG_KEY);
        attributes.put("beforeTagValue", before);
        attributes.put("tagValue", saved.getTagValue());
        attributes.put("level", saved.getTagValue());
        attributes.put("source", saved.getSource());
        attributes.put("change", created ? "CREATED" : "UPDATED");
        events.publish(CatalogEventType.COLUMN_TAG_CHANGED, catalog, CatalogEvent.TARGET_TABLE,
                database + "." + table, attributes);
    }
}
