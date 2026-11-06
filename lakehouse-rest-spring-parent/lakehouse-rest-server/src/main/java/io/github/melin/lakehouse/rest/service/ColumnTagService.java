package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.config.RequestContext;
import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.domain.entity.ColumnTagEntity;
import io.github.melin.lakehouse.rest.domain.entity.DatabaseEntity;
import io.github.melin.lakehouse.rest.domain.entity.TableEntity;
import io.github.melin.lakehouse.rest.domain.repo.CatalogRepository;
import io.github.melin.lakehouse.rest.domain.repo.ColumnTagRepository;
import io.github.melin.lakehouse.rest.domain.repo.DatabaseRepository;
import io.github.melin.lakehouse.rest.domain.repo.TableRepository;
import io.github.melin.lakehouse.rest.dto.SensitivityDtos.ColumnTag;
import io.github.melin.lakehouse.rest.dto.SensitivityDtos.ColumnTagCoverage;
import io.github.melin.lakehouse.rest.dto.SensitivityDtos.ColumnTagList;
import io.github.melin.lakehouse.rest.dto.SensitivityDtos.ColumnTagUpsertRequest;
import io.github.melin.lakehouse.rest.event.CatalogEvent;
import io.github.melin.lakehouse.rest.event.CatalogEventPublisher;
import io.github.melin.lakehouse.rest.event.CatalogEventType;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.ColumnRefs;
import io.github.melin.lakehouse.rest.support.CoverageTotals;
import io.github.melin.lakehouse.rest.support.Paging;
import io.github.melin.lakehouse.rest.support.ResourceType;
import io.github.melin.lakehouse.rest.support.SensitivityEnums.SensitivityLevel;
import io.github.melin.lakehouse.rest.support.SensitivityEnums.TagSource;
import io.github.melin.lakehouse.rest.support.SensitivityTags;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 列级标签的服务（设计见 {@code docs/sensitive-data-design.md} §4.4 / §8.4 / §9）。
 *
 * <p><b>这张表是「结论的当前状态」，而它有三条写路径，各自标自己的来源。</b>
 * 本类承担人工的那两条（打标 / 撤标），{@code source} 恒 {@code MANUAL}；
 * 自动应用（§5.3）走 {@link SensitivityTagWriter} 标 {@code AUTO}，人审确认走
 * {@link SensitivityDecisionService} 标 {@code MANUAL}，血缘传播（§7.2）留到 P2 标
 * {@code INHERITED}。三条路径共用一张表但**不共用优先级**：{@code AUTO} 永远不覆盖
 * 已有的 {@code MANUAL}（这是「人工结论优先」在写侧的唯一落点）。
 *
 * <p><b>为什么 {@code source} 不可由调用方指定。</b>手工打标恒为 {@code MANUAL}。
 * 允许请求体指定 {@code AUTO}，等于开了一个绕过自动应用闸门（§5.3 的白名单 + 阈值）
 * 的口子——{@code AUTO} 这个值的含义是「扫描算出来并且过了白名单」，
 * 不是「调用方说它是自动的」。这与 {@code DataPolicyEntity} 的策略文本不能由
 * 引擎侧写入是同一条性质：治理结论只能由声称它的那条路径产生。
 *
 * <p><b>DELETE 在 P1 起同时写永久抑制项</b>（设计稿 §7.2 / §9 的那一行）。
 * 只删行是不够的：P1 的扫描会真的自动落标，同一列下一轮会被判成同一类型再写回来，
 * 用户看到的是「删了又回来了」。因此撤标 = 删标签 + 落一条
 * {@code (column_ref_hash, type_code)} 的抑制项，两条路径（这里与
 * {@code POST /sensitivity-findings:decide} 的 {@code REJECTED}）共用
 * {@link SensitivityDecisionService#suppressColumn}，口径不会漂。
 *
 * <p>反向的一条也在这里：手工打标 {@code typeCode} 非空时**解除**抑制
 * （{@link SensitivityDecisionService#liftSuppression}）。不解除的话下一轮扫描仍会
 * 跳过这一列，这条手工标签会在某次「删了再扫」之后变成一条永远不会被自动确认的孤儿结论。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ColumnTagService {

    /**
     * 人工结论优先于自动结论（{@code bySource} 的归属规则）。
     *
     * <p>一列可以同时有人工标签与自动标签（治理员手工纠正了扫描的结论），
     * 而 {@code bySource} 要回答的是「这个范围里有多少列是靠人管起来的」——
     * 把有人工标签的列算成 {@code AUTO} 会把「人工覆盖」这件事从统计里抹掉。
     * 顺序与 {@code SensitivityLevel.rank()} 无关：它表达的是**结论的权威性**，
     * 不是敏感度。
     */
    private static final List<TagSource> SOURCE_PRIORITY =
            List.of(TagSource.MANUAL, TagSource.AUTO, TagSource.INHERITED);

    private final ColumnTagRepository tagRepository;
    private final CatalogRepository catalogRepository;
    private final DatabaseRepository databaseRepository;
    private final TableRepository tableRepository;
    private final SensitiveTypeService typeService;
    private final SensitivityDecisionService decisionService;
    private final SensitivityTagWriter tagWriter;
    private final CatalogEventPublisher events;
    private final Clock clock;

    // ------------------------------------------------------------------ 读

    /**
     * {@code GET /column-tags?catalog=&table=&tagKey=&level=}：标签清单 + 覆盖率。
     *
     * <p><b>catalog 必填</b>：这张表的量级是「每个命中列一行」，全库查询在
     * 多租户部署下既慢又没有意义（调用方永远只关心一个 catalog）。设计稿 §13 的
     * 「控制台强制分页 + 按 catalog 过滤」也是这个口径。
     *
     * <p><b>{@code tagKey} / {@code level} 只筛 {@code items}，不影响 {@code coverage}。</b>
     * 覆盖率描述的是**范围**（catalog / 库 / 表）的完整情况，而不是当前这一页筛选结果的
     * 情况——否则「按 {@code level=L4} 筛一下」会让覆盖率从 12% 跳到 100%，
     * 而那个数字看上去像是「治理完成了」。「范围内有几列是 L4」由
     * {@code coverage.byLevel} 回答，那才是对的问题。
     *
     * <p>先过滤再切页（与全部既有列表端点同一口径）。
     */
    @Transactional(readOnly = true)
    public ColumnTagList list(String catalog, String table, String tagKey, String level,
                              Integer page, Integer pageSize) {
        String catalogName = requireCatalogName(catalog);
        CatalogEntity catalogEntity = requireCatalog(catalogName);
        String tableFilter = trimToNull(table);
        String database = null;
        String tableName = null;
        if (tableFilter != null) {
            String[] parts = splitTable(tableFilter);
            database = parts[0];
            tableName = parts[1];
            // 库 / 表都存在才继续：不存在的范围与「这个范围里一条标签都没有」在界面上
            // 长得一样，而两者的处置完全不同（一个要改输入，一个要去扫）
            requireDatabase(catalogEntity.getId(), database);
            requireTable(catalogEntity.getId(), database, tableName);
        }

        List<ColumnTagEntity> inRange = tagsInRange(catalogName, database, tableName);
        String keyFilter = trimToNull(tagKey);
        SensitivityLevel levelFilter = parseLevel(level);
        List<ColumnTag> filtered = new ArrayList<>();
        for (ColumnTagEntity entity : inRange) {
            if (keyFilter != null && !keyFilter.equals(entity.getTagKey())) {
                continue;
            }
            if (levelFilter != null && levelFilter != entity.level()) {
                continue;
            }
            filtered.add(toDto(entity));
        }
        Paging.Paged<ColumnTag> paged = Paging.paged(filtered, page, pageSize);
        ColumnTagCoverage coverage = coverage(catalogEntity, database, tableName, inRange);
        return paged == null
                ? new ColumnTagList(filtered, null, coverage)
                : new ColumnTagList(paged.items(), paged.meta(), coverage);
    }

    /** {@code DELETE /column-tags/{id}} 的判权前一步：读出一条标签；不存在抛 404。 */
    @Transactional(readOnly = true)
    public ColumnTag get(Long id) {
        return toDto(require(id));
    }

    // ------------------------------------------------------------------ 手工打标

    /**
     * {@code PUT /column-tags}：手工打标（upsert）。
     *
     * <p><b>唯一键是 {@code (column_ref_hash, tag_key)}，因此「重复 PUT」是更新而不是新增</b>
     * ——同一列同一键只有一个值，这一点由数据模型保证（见 {@code ColumnTagEntity} 的
     * 类注释），服务层不需要先查重再插入。
     *
     * <p><b>乐观锁照 {@code DataPolicyService.upsert} 的口径</b>：新建时
     * {@code entityVersion} 由服务端置 {@code 0}（调用方给什么都被忽略），更新时
     * <b>必须</b>带上且与当前值一致，否则 409。刻意与
     * {@code SensitivitySettingsService}（那里 {@code null} = 跳过校验）不同：
     * 那边改的是「一个 catalog 的扫描怎么跑」，两个人同时改的后果是「一个版本的设置被覆盖」，
     * 下次扫描就能看出来；这边丢的是一条**人工治理结论**，而人工结论无法从任何地方重建
     * ——所以这里选「不给版本就不让改」这一侧，代价是多一次读。
     *
     * <p><b>表必须存在，列不校验。</b>三段（catalog / 库 / 表）写错就是写错，拦下来；
     * 而**列名不做存在性校验**：Lance 表根本不写 {@code schema_doc}（见
     * {@code SensitivityScanService} 的 §6.1 兜底），external 表注册后未刷新元数据时
     * 也读不到列清单——对这类表强制校验列，等于「这张表上的标签永远打不上」。
     * 列名只在 {@code column} 为空串时代表表级标签（{@link ColumnRefs#TABLE_LEVEL_COLUMN}）。
     */
    @Transactional
    public ColumnTag upsert(ColumnTagUpsertRequest request, String principal) {
        if (request == null) {
            throw ApiException.badRequest("request body must not be empty");
        }
        String catalogName = requireCatalogName(request.catalog());
        CatalogEntity catalog = requireCatalog(catalogName);
        String database = requireText(request.database(), "database");
        String table = requireText(request.table(), "table");
        requireDatabase(catalog.getId(), database);
        requireTable(catalog.getId(), database, table);
        String column = normalizeColumn(request.column());
        String tagKey = requireText(request.tagKey(), "tagKey");
        String tagValue = trimToNull(request.tagValue());
        String typeCode = trimToNull(request.typeCode());

        if (typeCode != null) {
            // 类型必须存在：删掉一个类型（自定义类型可删）之后，旧标签里的 type_code 还能显示
            // （它只是一个字符串），但**新写**一条指向不存在类型的标签会让「这条标签是什么类型」
            // 永远无从解释。历史与新写是两种承诺，这里只挡新写。
            typeService.require(typeCode);
        }
        SensitivityLevel level = resolveLevel(tagValue, typeCode);

        String referenceHash = ColumnRefs.hash(catalogName, database, table, column);
        ColumnTagEntity entity = tagRepository.findByColumnRefHashAndTagKey(referenceHash, tagKey)
                .orElse(null);
        long now = clock.millis();
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("database", database);
        attributes.put("table", table);
        attributes.put("column", column);
        attributes.put("columnRef", ColumnRefs.fullName(catalogName, database, table, column));
        attributes.put("tagKey", tagKey);
        attributes.put("typeCode", typeCode);
        attributes.put("level", level == null ? null : level.name());

        if (entity == null) {
            entity = new ColumnTagEntity();
            entity.setCatalogName(catalogName);
            entity.setDatabaseName(database);
            entity.setTableName(table);
            entity.setColumnName(column);
            entity.setColumnRefHash(referenceHash);
            entity.setTagKey(tagKey);
            // 来源恒 MANUAL，且不由请求体决定（见类注释）
            entity.setSource(TagSource.MANUAL.name());
            entity.setEntityVersion(0);
            entity.markCreated(principal, now);
            attributes.put("change", "CREATED");
        } else {
            if (request.entityVersion() == null || request.entityVersion() != entity.getEntityVersion()) {
                throw ApiException.entityVersionMismatch(ResourceType.COLUMN_TAG,
                        ColumnRefs.fullName(catalogName, database, table, column) + "#" + tagKey,
                        request.entityVersion() == null ? -1 : request.entityVersion(),
                        entity.getEntityVersion());
            }
            attributes.put("beforeTagValue", entity.getTagValue());
            attributes.put("entityVersion", entity.getEntityVersion());
            attributes.put("change", "UPDATED");
            entity.setEntityVersion(entity.getEntityVersion() + 1);
            entity.touch(principal, now);
        }
        entity.setTagValue(tagValue);
        entity.setTypeCode(typeCode);
        if (request.enabled() != null) {
            entity.setEnabled(request.enabled());
        }
        // 手工打标不写 confidence / findingId / inheritedFrom：前两个是「扫描算出来的」，
        // 第三个是「血缘传过来的」，人工结论三者皆无。刻意在这里显式说明，而不是沉默——
        // 否则将来有人看到这三列空着，会以为「手工标签也要评分」。
        entity.setConfidence(null);
        entity.setFindingId(null);
        entity.setInheritedFrom(null);
        ColumnTagEntity saved = tagRepository.save(entity);
        if (typeCode != null) {
            // 手工重新打标 = 撤回之前的否决：不解除的话，下一轮扫描仍会跳过这一列，
            // 而这条手工标签会在某次「删了再扫」之后变成一条永远不会被自动确认的孤儿结论。
            decisionService.liftSuppression(referenceHash, typeCode, principal);
        }

        attributes.put("tagValue", saved.getTagValue());
        attributes.put("afterTagValue", saved.getTagValue());
        attributes.put("entityVersion", saved.getEntityVersion());
        attributes.put("type", saved.getTypeCode());
        attributes.put("source", saved.getSource());
        events.publish(CatalogEventType.COLUMN_TAG_CHANGED, catalogName,
                CatalogEvent.TARGET_TABLE, database + "." + table, attributes);
        // 表级级别 = 该表所有列标签的 max(level)（§5.5），因此任何一次列级写入之后都要重算。
        // 它不参与策略展开，所以这一步不会改变任何列的脱敏行为。
        tagWriter.refreshTableLevel(catalogName, database, table, principal);
        return toDto(saved);
    }

    /**
     * {@code DELETE /column-tags/{id}}：删除一条标签。
     *
     * <p>物理删除而不是置 {@code enabled=false}：两者都在设计里（§4.4 的
     * {@code enabled_flag} 是「保留记录但不参与策略展开」），但它们的语义不同——
     * 停用是「暂时不生效，我还留着这条结论」，删除是「这条结论我撤回」。
     * 撤回之后**必须**能被重打（同一列同一键再 PUT 一次就回来了），否则
     * 「删错了没法恢复」会让人不敢删；而 {@code enabled} 那条路留给「先关掉、
     * 观察一下再说」。
     *
     * <p><b>同时写一条永久抑制项</b>（P1，见类注释）：删标签的语义是「这条结论我撤回」，
     * 而只要扫描还会自动落标，「撤回」就必须比这一行活得更久——否则同一列会在下一轮
     * 被重新判成同一类型。抑制项只在该列改名（哈希变）或治理员显式解禁后失效
     * （{@code DELETE /sensitivity-suppressions/{id}}）。
     */
    @Transactional
    public void delete(Long id, String principal) {
        ColumnTagEntity entity = require(id);
        snapshotDelete(entity, principal);
        tagRepository.delete(entity);
        // P1：删标签同时写永久抑制项（§9 那一行）。理由见 SnapshotDelete 上方的说明
        // 与 SensitivityDecisionService#suppressColumn：只删行的话，下一轮扫描会把
        // 同一列判成同一类型再写回来，用户看到的是「删了又回来了」。
        decisionService.suppressColumn(entity.getCatalogName(), entity.getDatabaseName(),
                entity.getTableName(), entity.getColumnName(), entity.getColumnRefHash(),
                entity.getTypeCode(), "column tag deleted by " + principal, entity.getFindingId(),
                principal);
        tagWriter.refreshTableLevel(entity.getCatalogName(), entity.getDatabaseName(),
                entity.getTableName(), principal);
    }

    /**
     * 删除前发一条 {@code column.tag.changed}，把**被删掉的值**留在事件流里。
     *
     * <p>这是本类唯一一条「先发事件再删」的路径，也是刻意的：删完之后那行就不在了，
     * 事件是「这条治理结论曾经是什么」的唯一残留。事件的
     * {@code before} 字段因此不是可选项——没有它，审计只能回答「某人在某时删了一条
     * 标签」，答不了「他删掉的是什么级别」。
     */
    private void snapshotDelete(ColumnTagEntity entity, String principal) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("database", entity.getDatabaseName());
        attributes.put("table", entity.getTableName());
        attributes.put("column", entity.getColumnName());
        attributes.put("columnRef", ColumnRefs.fullName(entity.getCatalogName(), entity.getDatabaseName(),
                entity.getTableName(), entity.getColumnName()));
        attributes.put("tagKey", entity.getTagKey());
        attributes.put("beforeTagValue", entity.getTagValue());
        attributes.put("typeCode", entity.getTypeCode());
        attributes.put("source", entity.getSource());
        attributes.put("change", "DELETED");
        attributes.put("deletedBy", principal);
        events.publish(CatalogEventType.COLUMN_TAG_CHANGED, entity.getCatalogName(),
                CatalogEvent.TARGET_TABLE,
                entity.getDatabaseName() + "." + entity.getTableName(), attributes);
    }

    // ------------------------------------------------------------------ 覆盖率

    /**
     * 算一遍范围内的覆盖率。
     *
     * <p>分母（列总数）来自 {@code lakehouse_table.schema_doc}，因此**没扫过的表把覆盖率拉低**
     * ——这正是「已分类 / 全部」比「扫描已完成」诚实的地方（§13）。
     *
     * <p>表数上限 {@link CoverageTotals#MAX_TABLES}：为这个读接口解析几万张表的 JSON
     * 是把一次界面刷新变成一次全量元数据扫描。超过时把 {@code counted} 与
     * {@code totalTables} 两个数都回传，让前端写明「按前 N 张表计算」——
     * 只回一个被截断的分母等于让这个数字撒谎。
     *
     * <p>分子按**已打标列**统计（不是标签行）：一列有 {@code PII.CN_ID_CARD} 与
     * {@code owner.team} 两条标签时它在 {@code byLevel} 与 {@code bySource} 里各算一次，
     * 而「L4 有几列」的答案必须是「有几列的最高级别是 L4」。
     */
    private ColumnTagCoverage coverage(CatalogEntity catalog, String database, String table,
                                       List<ColumnTagEntity> tags) {
        List<TableEntity> tables;
        if (database != null && table != null) {
            // 单表范围：分母就是这一张表的列数。走 findAll 会去数整个 catalog，
            // 那不是「这张表的覆盖率」——本屏在表详情页里也用，那个数字必须是表的。
            tables = List.of(requireTable(catalog.getId(), database, table));
        } else {
            tables = tableRepository.findAllByCatalogIdOrderByDatabaseIdAscNameAsc(catalog.getId());
        }
        int totalTables = tables.size();
        List<String> schemaDocs = new ArrayList<>(Math.min(totalTables, CoverageTotals.MAX_TABLES));
        for (TableEntity entity : tables) {
            if (schemaDocs.size() >= CoverageTotals.MAX_TABLES) {
                break;
            }
            // 保留 null：Lance 表不写 schema_doc，它贡献 0 列但**确实被数过**。
            // 把它从分母里剔掉会让覆盖率虚高——「数了这张表，它一列都没告诉我」
            // 才是实情，而这正是这个数字要表达的。
            schemaDocs.add(entity.getSchemaDoc());
        }
        CoverageTotals.Totals totals = CoverageTotals.count(schemaDocs, totalTables);

        // 表级标签（column_name 为空串）不进分子：它不是「某一列被分类了」，
        // 而是「这张表里最敏感的一列有多敏感」——把它算成已分类列会让
        // 分母（真实的列数）与分子（列 + 表）不可比。
        Map<String, SensitivityLevel> columnLevels = new LinkedHashMap<>();
        Set<String> taggedTables = new LinkedHashSet<>();
        for (ColumnTagEntity tag : tags) {
            if (!tag.isEnabled()) {
                // 停用的标签不参与策略展开，也就不该被算成「已分类」——
                // 否则「我把它关了」不会让覆盖率动，而那个数字本应是「现在受保护的比例」。
                continue;
            }
            taggedTables.add(tag.getDatabaseName() + "." + tag.getTableName());
            if (ColumnRefs.isTableLevel(tag.getColumnName())) {
                continue;
            }
            SensitivityLevel level = tag.level();
            columnLevels.merge(tag.getColumnRefHash(), level,
                    (left, right) -> left == null ? right : right);
        }

        Map<String, Integer> byLevel = new LinkedHashMap<>();
        Map<String, Integer> bySource = new LinkedHashMap<>();
        for (String referenceHash : columnLevels.keySet()) {
            SensitivityLevel level = highestLevel(tags, referenceHash);
            if (level != null) {
                byLevel.merge(level.name(), 1, Integer::sum);
            }
            TagSource source = dominantSource(tags, referenceHash);
            if (source != null) {
                bySource.merge(source.name(), 1, Integer::sum);
            }
        }
        return new ColumnTagCoverage(columnLevels.size(), totals.totalColumns(),
                taggedTables.size(), totals.counted(), totals.totalTables(), byLevel, bySource);
    }

    /** 一列的最高级别（{@code null} 表示它的标签都不是分级，如 {@code owner.team}）。 */
    private static SensitivityLevel highestLevel(List<ColumnTagEntity> tags, String referenceHash) {
        SensitivityLevel highest = null;
        for (ColumnTagEntity tag : tags) {
            if (!tag.isEnabled() || !referenceHash.equals(tag.getColumnRefHash())) {
                continue;
            }
            highest = SensitivityTags.max(highest, tag.level());
        }
        return highest;
    }

    /** 一列的归属来源（人工优先，见 {@link #SOURCE_PRIORITY}）。 */
    private static TagSource dominantSource(List<ColumnTagEntity> tags, String referenceHash) {
        Set<String> present = new LinkedHashSet<>();
        for (ColumnTagEntity tag : tags) {
            if (tag.isEnabled() && referenceHash.equals(tag.getColumnRefHash())) {
                present.add(tag.getSource());
            }
        }
        for (TagSource candidate : SOURCE_PRIORITY) {
            if (present.contains(candidate.name())) {
                return candidate;
            }
        }
        return null;
    }

    /** 范围内的全部标签（含表级，含停用的——覆盖率需要看到停用的才跳得对）。 */
    private List<ColumnTagEntity> tagsInRange(String catalogName, String database, String table) {
        List<ColumnTagEntity> all = database != null && table != null
                ? tagRepository.findByCatalogNameAndDatabaseNameAndTableName(catalogName, database, table)
                : tagRepository.findByCatalogName(catalogName);
        List<ColumnTagEntity> sorted = new ArrayList<>(all);
        sorted.sort(Comparator.comparing(ColumnTagEntity::getDatabaseName)
                .thenComparing(ColumnTagEntity::getTableName)
                .thenComparing(ColumnTagEntity::getColumnName)
                .thenComparing(ColumnTagEntity::getTagKey));
        return sorted;
    }

    // ------------------------------------------------------------------ 内部

    /** 按 id 取标签；不存在抛 404（资源类型是 {@code COLUMN_TAG}）。 */
    @Transactional(readOnly = true)
    public ColumnTagEntity require(Long id) {
        if (id == null) {
            throw ApiException.badRequest("id must not be empty");
        }
        return tagRepository.findById(id)
                .orElseThrow(() -> ApiException.managementNotExist(ResourceType.COLUMN_TAG, String.valueOf(id)));
    }

    /**
     * 校验标签值：**带类型的标签必须是合法分级**。
     *
     * <p>规则只有一条，但它必须存在：{@code typeCode} 有值意味着「这条标签是敏感标签」，
     * 而敏感标签的 {@code tag_value} 按 §4.4 存的是分级。允许它写 {@code "yes"}，
     * 就会让 {@code level=L4} 过滤、分级分布、表级 {@code max(level)} 三处
     * 同时把这个值当成「没有分级」——标签还在，但它在所有按级别组织的地方都消失。
     *
     * <p>不带类型的标签（{@code owner.team} / {@code tier=P0}）不受约束：
     * 这类标签的值是自由文本，本来就没有分级语义。
     *
     * <p>{@code CORE} / {@code IMPORTANT} **允许**手工打（§5.5：那两档按国家与行业目录
     * 人工认定），与类型表刻意相反——类型表拒绝它们是因为那是「探测器会用它落标签」的地方。
     */
    private static SensitivityLevel resolveLevel(String tagValue, String typeCode) {
        if (typeCode == null) {
            return SensitivityTags.parseLevel(tagValue).orElse(null);
        }
        if (tagValue == null) {
            throw ApiException.badRequest("tagValue is required for a typed tag (typeCode=" + typeCode
                    + "): a sensitive tag stores a level (L1-L4 / CORE / IMPORTANT)");
        }
        return SensitivityTags.parseLevel(tagValue)
                .orElseThrow(() -> ApiException.badRequest("tagValue \"" + tagValue
                        + "\" is not a valid level for a typed tag; supported: L1, L2, L3, L4,"
                        + " CORE, IMPORTANT"));
    }

    /** 过滤条件里的 {@code level}；给了但认不出 → 400（不静默忽略，否则会回一份「全部」）。 */
    private static SensitivityLevel parseLevel(String level) {
        String value = trimToNull(level);
        if (value == null) {
            return null;
        }
        return SensitivityLevel.parse(value)
                .orElseThrow(() -> ApiException.badRequest("Unknown level \"" + level
                        + "\"; supported: L1, L2, L3, L4, CORE, IMPORTANT"));
    }

    private CatalogEntity requireCatalog(String catalogName) {
        return catalogRepository.findByPrefix(catalogName)
                .orElseThrow(() -> ApiException.managementNotExist(ResourceType.CATALOG, catalogName));
    }

    private static String requireCatalogName(String catalog) {
        return requireText(catalog, "catalog");
    }

    private DatabaseEntity requireDatabase(String catalogId, String database) {
        return databaseRepository.findByCatalogIdAndName(catalogId, database)
                .orElseThrow(() -> ApiException.databaseNotExist(database));
    }

    private TableEntity requireTable(String catalogId, String database, String table) {
        DatabaseEntity databaseEntity = requireDatabase(catalogId, database);
        return tableRepository.findByCatalogIdAndDatabaseIdAndName(catalogId, databaseEntity.getId(), table)
                .orElseThrow(() -> ApiException.tableNotExist(table));
    }

    /** {@code database.table} 拆两段；形状不对 → 400（不猜「只有一个点所以是表名」）。 */
    private static String[] splitTable(String table) {
        int dot = table.indexOf('.');
        if (dot <= 0 || dot == table.length() - 1 || table.indexOf('.', dot + 1) >= 0) {
            throw ApiException.badRequest("table must be \"database.table\", got \"" + table + "\"");
        }
        return new String[]{table.substring(0, dot).trim(), table.substring(dot + 1).trim()};
    }

    /** 列名归一：空白 → 空串（表级标签用空串占位，见 {@link ColumnRefs#TABLE_LEVEL_COLUMN}）。 */
    private static String normalizeColumn(String column) {
        String value = trimToNull(column);
        return value == null ? ColumnRefs.TABLE_LEVEL_COLUMN : value;
    }

    private static String requireText(String value, String field) {
        String text = trimToNull(value);
        if (text == null) {
            throw ApiException.badRequest(field + " must not be empty");
        }
        return text;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // ------------------------------------------------------------------ 映射

    private static ColumnTag toDto(ColumnTagEntity entity) {
        SensitivityLevel level = entity.level();
        return new ColumnTag(entity.getId(), entity.getCatalogName(), entity.getDatabaseName(),
                entity.getTableName(), entity.getColumnName(),
                ColumnRefs.fullName(entity.getCatalogName(), entity.getDatabaseName(),
                        entity.getTableName(), entity.getColumnName()),
                ColumnRefs.isTableLevel(entity.getColumnName()),
                entity.getTagKey(), entity.getTagValue(), level == null ? null : level.name(),
                entity.getSource(), entity.getTypeCode(), entity.getConfidence(),
                entity.getInheritedFrom(), entity.getFindingId(), entity.isEnabled(),
                entity.getEntityVersion(), entity.getUpdatedBy(), entity.getUpdatedAt());
    }
}
