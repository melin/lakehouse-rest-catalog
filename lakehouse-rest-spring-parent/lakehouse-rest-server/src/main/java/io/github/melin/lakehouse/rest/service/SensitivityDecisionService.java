package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.domain.entity.SensitivityFindingEntity;
import io.github.melin.lakehouse.rest.domain.entity.SensitivitySuppressionEntity;
import io.github.melin.lakehouse.rest.domain.repo.SensitivityFindingRepository;
import io.github.melin.lakehouse.rest.domain.repo.SensitivitySuppressionRepository;
import io.github.melin.lakehouse.rest.dto.SensitivityDtos.FindingDecisionRequest;
import io.github.melin.lakehouse.rest.dto.SensitivityDtos.FindingDecisionResult;
import io.github.melin.lakehouse.rest.dto.SensitivityDtos.Suppression;
import io.github.melin.lakehouse.rest.dto.SensitivityDtos.SuppressionList;
import io.github.melin.lakehouse.rest.event.CatalogEvent;
import io.github.melin.lakehouse.rest.event.CatalogEventPublisher;
import io.github.melin.lakehouse.rest.event.CatalogEventType;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.ColumnRefs;
import io.github.melin.lakehouse.rest.support.ResourceType;
import io.github.melin.lakehouse.rest.support.SensitivityEnums.FindingState;
import io.github.melin.lakehouse.rest.support.SensitivityEnums.TagSource;
import java.time.Clock;
import java.util.ArrayList;
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
 * 人审决策（{@code POST /sensitivity-findings:decide}）与永久抑制项的管理
 * （设计见 {@code docs/sensitive-data-design.md} §7.2 / §9）。
 *
 * <p><b>它是 P1 人审闭环的核心，而闭环缺的从来不是「点一下按钮」。</b>
 * 一条命中被拒绝之后，第二天重扫会**再次**判成敏感——如果拒绝只改了那条 finding
 * 的状态，人就变成了一台每天重复劳动的机器，而重复劳动的最后结果是没人再看队列。
 * 因此一次 {@code REJECTED} 必须同时落下**两件事**：finding 的状态，以及一条
 * 按 {@code (column_ref_hash, type_code)} 记的永久抑制项——扫描落标前先查它。
 *
 * <p><b>为什么这个服务独立于 {@link SensitivityScanService}。</b>扫描是「读」，
 * 这里的每个动作都是「写」，而且写的是治理结论（标签、抑制项）。预览与扫描共用
 * 那条只读通道是为了收口，人审写路径同理：一条写路径、一处事务边界、一套事件，
 * 混进扫描服务会让后者的事务边界（刻意不加 {@code @Transactional}，见那边的类注释）
 * 与这里需要的原子性互相打架。
 *
 * <p><b>整批一个事务。</b>人审是「这一屏 20 条一起确认」，如果逐条提交，
 * 中途失败会让队列停在一个「前 7 条已确认、后面还是待审」的状态——人看到的
 * 是一个既不是开始也不是结束的界面，唯一能做的是再点一次（而重复点击会撞上
 * 已经确认的那 7 条）。整批一个事务让失败退回原点。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SensitivityDecisionService {

    private final SensitivityFindingRepository findingRepository;
    private final SensitivitySuppressionRepository suppressionRepository;
    private final SensitivityTagWriter tagWriter;
    private final CatalogEventPublisher events;
    private final Clock clock;

    /**
     * 批量确认 / 拒绝。
     *
     * @param request   请求体：id 列表 + {@code CONFIRMED} / {@code REJECTED} + 备注
     * @param principal 审核人
     */
    @Transactional
    public FindingDecisionResult decide(FindingDecisionRequest request, String principal) {
        if (request == null) {
            throw ApiException.badRequest("request body must not be empty");
        }
        List<Long> ids = request.ids() == null ? List.of() : request.ids();
        List<Long> distinct = new ArrayList<>(new LinkedHashSet<>(ids));
        distinct.removeIf(java.util.Objects::isNull);
        if (distinct.isEmpty()) {
            throw ApiException.badRequest("ids must not be empty: decide acts on a batch of findings");
        }
        FindingState decision = parseDecision(request.decision());
        String note = trimToNull(request.note());
        long now = clock.millis();

        List<SensitivityFindingEntity> found = findingRepository.findAllById(distinct);
        if (found.size() != distinct.size()) {
            Set<Long> known = new LinkedHashSet<>();
            for (SensitivityFindingEntity entity : found) {
                known.add(entity.getId());
            }
            List<Long> missing = new ArrayList<>(distinct);
            missing.removeAll(known);
            // 整批 404 而不是「能找到的照做」：人审是一次有意的动作，
            // 悄悄少做几条会让「我明明点了 20 条」与结果对不上。
            throw ApiException.managementNotExist(ResourceType.SENSITIVITY_FINDING,
                    missing.toString());
        }

        // 同一次决策里同一张表可能有多条命中（一列两种类型），表级级别只重算一次
        Set<String> touchedTables = new LinkedHashSet<>();
        List<Long> tagIds = new ArrayList<>();
        List<Long> suppressionIds = new ArrayList<>();
        int skipped = 0;
        for (SensitivityFindingEntity entity : found) {
            FindingState current = FindingState.parse(entity.getState()).orElse(null);
            if (current == FindingState.CONFIRMED || current == FindingState.REJECTED) {
                // 幂等：重复提交（网络重试、两个人同时点）不该失败
                skipped++;
                continue;
            }
            entity.setState(decision.name());
            entity.setDecidedBy(principal);
            entity.setDecidedAt(now);
            findingRepository.save(entity);

            if (decision == FindingState.CONFIRMED) {
                // 人工确认 = 权威性最高的结论，因此 source=MANUAL（不是 AUTO）。
                // 这也让下一轮扫描不会再覆盖它（TagWriter 的优先级规则）。
                tagWriter.apply(entity.getCatalogName(), entity.getDatabaseName(),
                                entity.getTableName(), entity.getColumnName(), entity.getTypeCode(),
                                entity.getConfidence(), entity.getId(), TagSource.MANUAL, principal)
                        .ifPresent(tag -> tagIds.add(tag.getId()));
            } else {
                SensitivitySuppressionEntity suppression = suppress(entity, note, principal, now);
                suppressionIds.add(suppression.getId());
                // 驳回同时撤掉自动标签：留着它会让「驳回」在策略面上不生效
                // （P3 的标签驱动脱敏读的是标签，不是 finding）
                tagWriter.removeAuto(entity.getCatalogName(), entity.getDatabaseName(),
                        entity.getTableName(), entity.getColumnName(), entity.getTypeCode(), principal);
            }
            touchedTables.add(entity.getCatalogName() + "\u0000" + entity.getDatabaseName()
                    + "\u0000" + entity.getTableName());
            publishDecided(entity, decision, note);
        }

        for (String key : touchedTables) {
            String[] parts = key.split("\u0000", -1);
            tagWriter.refreshTableLevel(parts[0], parts[1], parts[2], principal);
        }
        log.info("sensitivity decide {}: {} findings, {} skipped, {} tags, {} suppressions",
                decision, found.size() - skipped, skipped, tagIds.size(), suppressionIds.size());
        return new FindingDecisionResult(decision.name(), found.size() - skipped, skipped,
                tagIds, suppressionIds);
    }

    /**
     * 这批命中涉及哪些 catalog。
     *
     * <p><b>判权必须在决策之前、且按 finding 自己记着的 catalog 判。</b>
     * {@code decide} 的请求体里没有 catalog（它只有 id），因此控制器要先从 id 反查出
     * catalog 再去判权——若反过来「先决策、后判权」，就等于任何人都能改任何 catalog
     * 的治理结论。同一条推理见 {@code GET /sensitivity-scans/{id}}。
     *
     * @throws ApiException 404：有 id 不存在（与决策时同一判据，避免两条路径口径不一）
     */
    @Transactional(readOnly = true)
    public List<String> catalogsOf(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Long> distinct = new ArrayList<>(new LinkedHashSet<>(ids));
        distinct.removeIf(java.util.Objects::isNull);
        List<SensitivityFindingEntity> found = findingRepository.findAllById(distinct);
        if (found.size() != distinct.size()) {
            Set<Long> known = new LinkedHashSet<>();
            for (SensitivityFindingEntity entity : found) {
                known.add(entity.getId());
            }
            List<Long> missing = new ArrayList<>(distinct);
            missing.removeAll(known);
            throw ApiException.managementNotExist(ResourceType.SENSITIVITY_FINDING,
                    missing.toString());
        }
        Set<String> catalogs = new LinkedHashSet<>();
        for (SensitivityFindingEntity entity : found) {
            catalogs.add(entity.getCatalogName());
        }
        return List.copyOf(catalogs);
    }

    /** 按 id 取一条抑制项（{@code DELETE /sensitivity-suppressions/{id}} 的判权前一步）。 */
    @Transactional(readOnly = true)
    public SensitivitySuppressionEntity require(Long id) {
        if (id == null) {
            throw ApiException.badRequest("id must not be empty");
        }
        return suppressionRepository.findById(id)
                .orElseThrow(() -> ApiException.managementNotExist(
                        ResourceType.SENSITIVITY_SUPPRESSION, String.valueOf(id)));
    }

    /** {@code GET /sensitivity-suppressions?catalog=}：抑制项清单（含已解禁的）。 */
    @Transactional(readOnly = true)
    public SuppressionList suppressions(String catalog) {
        String catalogName = requireCatalog(catalog);
        List<Suppression> items = new ArrayList<>();
        for (SensitivitySuppressionEntity entity
                : suppressionRepository.findByCatalogNameOrderByDecidedAtDesc(catalogName)) {
            items.add(toDto(entity));
        }
        return new SuppressionList(items);
    }

    /**
     * {@code DELETE /sensitivity-suppressions/{id}}：显式解禁。
     *
     * <p><b>置 {@code enabled=false} 而不是删行</b>（与 {@code DELETE /column-tags}
     * 的物理删除刻意不同）：标签删掉可以重建，而「谁在什么时候否掉、谁又在什么时候
     * 解禁」这段历史一旦删掉就再也拼不出来。这也让界面能显示「已解除」而不是
     * 让它凭空消失。
     *
     * <p>解禁之后**不会立刻重扫**：下一轮扫描才会重新判这一列。这是有意的——
     * 解禁是「我改主意了」，不是「现在就给我一个结论」；立刻重扫会让一次点击
     * 变成一次明文读，而用户点的是「解除抑制」，不是「立刻读这张表」。
     */
    @Transactional
    public Suppression release(Long id, String principal) {
        if (id == null) {
            throw ApiException.badRequest("id must not be empty");
        }
        SensitivitySuppressionEntity entity = suppressionRepository.findById(id)
                .orElseThrow(() -> ApiException.managementNotExist(
                        ResourceType.SENSITIVITY_SUPPRESSION, String.valueOf(id)));
        if (!entity.isEnabledFlag()) {
            // 已经解禁过：返回现状而不是 409（重复点击不是错误）
            return toDto(entity);
        }
        return toDto(releaseEntity(entity, principal, "RELEASED"));
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 落一条永久抑制项（存在则重新启用并更新理由）。
     *
     * <p><b>「解禁过又再次被拒」要能工作。</b>抑制项被解禁（{@code enabled=false}）后
     * 用户可能改主意、再拒一次；此时若新插一行会撞唯一键
     * {@code (column_ref_hash, type_code)}，因此这里是 upsert 而不是 insert。
     */
    private SensitivitySuppressionEntity suppress(SensitivityFindingEntity finding, String note,
                                                  String principal, long now) {
        return upsertSuppression(finding.getCatalogName(), finding.getDatabaseName(),
                finding.getTableName(), finding.getColumnName(), finding.getColumnRefHash(),
                finding.getTypeCode(), note, finding.getId(), principal, now);
    }

    /**
     * 落一条抑制项（供「删标签」这条路径复用，{@code DELETE /column-tags/{id}} 的要求）。
     *
     * <p><b>删标签必须同时落抑制项</b>（§9 那一行）：删掉一条自动标签的语义是
     * 「这条结论我撤回」，如果只在库里删行，下一轮扫描会把同一列判成同一个类型
     * 再写回来——用户看到的是「删了又回来了」，而唯一能解释这件事的地方是一个
     * 他没打开过的扫描历史。
     *
     * <p>理由里带上操作人：抑制项是一句「不是敏感」的断言，而断言要有人负责。
     */
    @Transactional
    public void suppressColumn(String catalog, String database, String table, String column,
                               String columnRefHash, String typeCode, String reason,
                               Long findingId, String principal) {
        if (typeCode == null || typeCode.isBlank()) {
            // 无类型的标签（owner.team）没有可抑制的判定：抑制项的键是类型
            return;
        }
        upsertSuppression(catalog, database, table, column, columnRefHash, typeCode,
                reason, findingId, principal, clock.millis());
    }

    /**
     * 解除一条抑制项（不报错版本，供「手工重新打标」复用）。
     *
     * <p>治理员手工给某列打上某个类型的标签时，之前「这列不是该类型」的否决就作废了
     * ——不解除的话，下一轮扫描仍会跳过它，而手工标签会在某次「删了再扫」之后
     * 变成一条永远不会被自动确认的孤儿结论。
     */
    @Transactional
    public boolean liftSuppression(String columnRefHash, String typeCode, String principal) {
        if (columnRefHash == null || typeCode == null) {
            return false;
        }
        SensitivitySuppressionEntity entity = suppressionRepository
                .findByColumnRefHashAndTypeCode(columnRefHash, typeCode)
                .orElse(null);
        if (entity == null || !entity.isEnabledFlag()) {
            return false;
        }
        releaseEntity(entity, principal, "LIFTED");
        return true;
    }

    private SensitivitySuppressionEntity upsertSuppression(String catalog, String database,
                                                           String table, String column,
                                                           String columnRefHash, String typeCode,
                                                           String reason, Long findingId,
                                                           String principal, long now) {
        SensitivitySuppressionEntity entity = suppressionRepository
                .findByColumnRefHashAndTypeCode(columnRefHash, typeCode)
                .orElse(null);
        if (entity == null) {
            entity = new SensitivitySuppressionEntity();
            entity.setCatalogName(catalog);
            entity.setDatabaseName(database);
            entity.setTableName(table);
            entity.setColumnName(column);
            entity.setColumnRefHash(columnRefHash);
            entity.setTypeCode(typeCode);
            entity.markCreated(principal, now);
        } else {
            entity.touch(principal, now);
        }
        entity.setReason(reason);
        entity.setDecidedBy(principal);
        entity.setDecidedAt(now);
        entity.setFindingId(findingId);
        entity.setEnabledFlag(true);
        return suppressionRepository.save(entity);
    }

    private SensitivitySuppressionEntity releaseEntity(SensitivitySuppressionEntity entity,
                                                       String principal, String change) {
        long now = clock.millis();
        entity.setEnabledFlag(false);
        entity.touch(principal, now);
        SensitivitySuppressionEntity saved = suppressionRepository.save(entity);

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("kind", "SUPPRESSION");
        attributes.put("change", change);
        attributes.put("catalog", entity.getCatalogName());
        attributes.put("database", entity.getDatabaseName());
        attributes.put("table", entity.getTableName());
        attributes.put("column", entity.getColumnName());
        attributes.put("columnRef", ColumnRefs.fullName(entity.getCatalogName(),
                entity.getDatabaseName(), entity.getTableName(), entity.getColumnName()));
        attributes.put("typeCode", entity.getTypeCode());
        attributes.put("releasedBy", principal);
        // 复用 catalog 级治理配置变更这个类型（CatalogEventType 的注释里写明
        // 「P3 的真绑定落地后复用同一个类型，属性里加 kind 区分」）：
        // 解禁是一条治理配置变更，不是一次扫描、也不是一条标签变更。
        events.publish(CatalogEventType.SENSITIVITY_BINDING_CHANGED, entity.getCatalogName(),
                CatalogEvent.TARGET_TABLE,
                entity.getDatabaseName() + "." + entity.getTableName(), attributes);
        return saved;
    }

    private void publishDecided(SensitivityFindingEntity entity, FindingState decision, String note) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("findingId", entity.getId());
        attributes.put("scanId", entity.getScanId());
        attributes.put("columnRef", ColumnRefs.fullName(entity.getCatalogName(),
                entity.getDatabaseName(), entity.getTableName(), entity.getColumnName()));
        attributes.put("typeCode", entity.getTypeCode());
        attributes.put("confidence", entity.getConfidence());
        attributes.put("decision", decision.name());
        attributes.put("note", note);
        events.publish(CatalogEventType.SENSITIVITY_FINDING_DECIDED, entity.getCatalogName(),
                CatalogEvent.TARGET_TABLE,
                entity.getDatabaseName() + "." + entity.getTableName(), attributes);
    }

    private static FindingState parseDecision(String decision) {
        String value = trimToNull(decision);
        if (value == null) {
            throw ApiException.badRequest("decision is required; supported: CONFIRMED, REJECTED");
        }
        FindingState parsed = FindingState.parse(value)
                .orElseThrow(() -> ApiException.badRequest("Unknown decision \"" + decision
                        + "\"; supported: CONFIRMED, REJECTED"));
        if (parsed != FindingState.CONFIRMED && parsed != FindingState.REJECTED) {
            // PENDING / AUTO_APPLIED 是人审**不该**写进去的两个值：前者是初始态、
            // 后者是扫描的产出。允许它们会让 REJECTED 的抑制项与状态脱节。
            throw ApiException.badRequest("decision must be CONFIRMED or REJECTED, got " + decision);
        }
        return parsed;
    }

    private static String requireCatalog(String catalog) {
        String value = trimToNull(catalog);
        if (value == null) {
            throw ApiException.badRequest("catalog is required");
        }
        return value;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Suppression toDto(SensitivitySuppressionEntity entity) {
        return new Suppression(entity.getId(), entity.getCatalogName(), entity.getDatabaseName(),
                entity.getTableName(), entity.getColumnName(),
                ColumnRefs.fullName(entity.getCatalogName(), entity.getDatabaseName(),
                        entity.getTableName(), entity.getColumnName()),
                entity.getTypeCode(), entity.getReason(), entity.isEnabledFlag(),
                entity.getDecidedBy(), entity.getDecidedAt(), entity.getFindingId(),
                entity.isEnabledFlag() ? null : entity.getUpdatedBy(),
                entity.isEnabledFlag() ? null : entity.getUpdatedAt());
    }
}
