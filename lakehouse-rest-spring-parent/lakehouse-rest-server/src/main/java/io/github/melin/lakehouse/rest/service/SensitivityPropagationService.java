package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.config.RequestContext;
import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.domain.entity.ColumnTagEntity;
import io.github.melin.lakehouse.rest.domain.entity.LineageColumnEdgeEntity;
import io.github.melin.lakehouse.rest.domain.entity.SensitivityFindingEntity;
import io.github.melin.lakehouse.rest.domain.entity.SensitivityScanEntity;
import io.github.melin.lakehouse.rest.domain.entity.SensitivitySuppressionEntity;
import io.github.melin.lakehouse.rest.domain.repo.ColumnTagRepository;
import io.github.melin.lakehouse.rest.domain.repo.LineageColumnEdgeRepository;
import io.github.melin.lakehouse.rest.domain.repo.SensitivityFindingRepository;
import io.github.melin.lakehouse.rest.domain.repo.SensitivitySuppressionRepository;
import io.github.melin.lakehouse.rest.support.ColumnRefs;
import io.github.melin.lakehouse.rest.support.Digests;
import io.github.melin.lakehouse.rest.support.Json;
import io.github.melin.lakehouse.rest.support.LineageRefs;
import io.github.melin.lakehouse.rest.support.SensitivityCatalogSettings;
import io.github.melin.lakehouse.rest.support.SensitivityEnums.FindingState;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 沿血缘列边把「这列敏感」这个结论往上下游搬（设计见
 * {@code docs/sensitive-data-design.md} §7.1 / §7.2）。
 *
 * <p><b>这一节存在的理由不是「少扫几次」，而是「扫描够不到的地方也有结论」。</b>
 * 数据从 {@code ods.user} 流到 {@code dwd.user_wide}，身份证列改名叫
 * {@code id_no_enc}——按列名判的 D1 在这一列上什么也判不出来，而血缘边是现成的：
 * 它就是 {@code ods.user.id_card} 的直接引用。没有传播，这张宽表只能等人工一个一个打标。
 *
 * <p><b>三档判定，来自 {@code transform_kind}：</b>
 * <ul>
 *   <li>{@code DIRECT}：直接列引用，语义不变 → 继承，置信度 × {@code confidenceDecay}（默认 0.9）；</li>
 *   <li>{@code DERIVED}：{@code substr(id_card,1,6)} / {@code md5(id_card)} —— 已变形但仍可反推
 *       → 继承，再 × 0.6，并**同时落一条 {@code PENDING} 待复核建议**：变形之后的列还算不算敏感，
 *       是一个需要人看一眼的判断，不该由一条规则悄悄定；</li>
 *   <li>{@code AGGREGATE}：{@code count(id_card)} 不敏感、{@code max(salary)} 敏感——规则分不开
 *       → **不落标签**，只落 {@code PENDING} 建议，并且**不再往下传**（这条链下游没有结论可继承）。</li>
 * </ul>
 * {@code CONSTANT} / {@code UNRESOLVED} 不传播：前者没有源列，后者连源都解析不出来。
 *
 * <p><b>「停在 PENDING」指的是落一条 finding，而不是给标签加一个状态位。</b>
 * 标签表存的是「现在这列是什么」，它没有待审态（见 {@code ColumnTagEntity} 的类注释）；
 * 而人审的入口、批量确认、拒绝落抑制项这一整套（P1）都挂在 finding 上。
 * 因此**建议走 finding，结论走 tag**：同一条边，{@code DIRECT} 两边都写，
 * {@code AGGREGATE} 与超出深度上限的只写前者。
 *
 * <p><b>人工否决优先于自动传播</b>（§7.2）：目标列若落在一条仍生效的抑制项里，
 * 连建议都不提。否则「判错了 → 人否掉 → 明天换个上游又推一遍」会变成每天的例行劳动。
 *
 * <p><b>传播以扫描为触发器，没有独立端点。</b>§9 的 API 清单里没有 propagation 这一条，
 * 而 {@code PENDING} 建议必须挂在某次扫描上（{@code scan_id} 是 finding 的外键维度，
 * 唯一键 {@code (scan_id, column_ref_hash, type_code)} 就建在它上面）。因此传播是
 * **一次扫描的收尾步骤**：扫完哪些表，就从那些表的标签往下游走。这也顺带满足了
 * §7.1 的「幂等增量」——增量来自「只从这次扫到的表出发」，幂等来自唯一键与
 * {@link SensitivityTagWriter#applyInherited} 的「结论没变就不写」。
 *
 * <p><b>三条边界，都是为了「不制造假结论」：</b>
 * <ol>
 *   <li><b>不跨 catalog。</b>{@code ods} 与 {@code dwd} 分属两个 catalog 时，这条边在这里跳过。
 *       扫描是 catalog 级治理动作，往别人的 catalog 里写标签会绕过那里的判权与设置；</li>
 *   <li><b>不进已经人工下过结论的列。</b>目标列若已有 {@code MANUAL} 标签，连建议都不提；</li>
 *   <li><b>深度上限</b>（默认 5 跳，catalog 可覆盖，yml 可调）。超限的那一跳停在
 *       {@code PENDING} 建议——一条误标沿长链污染半张数仓，比漏标更难收拾。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SensitivityPropagationService {

    /** 直接列引用：语义不变，只按配置的逐跳折损。 */
    private static final String DIRECT = "DIRECT";

    /** 派生列（{@code substr} / {@code md5}）：继承，但额外打折并要人看一眼。 */
    private static final String DERIVED = "DERIVED";

    /**
     * 派生列：已变形但仍可反推，额外 ×0.6。
     *
     * <p>这个系数是设计稿 §7.1 定死的策略值，不做成配置项：它表达的是「变形之后
     * 还算不算同一个结论」这个判断，而不是「你的数仓有多深」那种需要按环境调的参数。
     * 置信度本身会随跳数继续折损，两者相乘才是这条继承结论的最终分数。
     */
    private static final double DERIVED_FACTOR = 0.6d;

    /** 聚合列：不继承，只产建议；也不往下传。 */
    private static final String AGGREGATE = "AGGREGATE";

    /** 一次传播最多写多少条标签：血缘图有环、也有意外的扇出，这是最后一道兜底。 */
    private static final int MAX_WRITES = 5000;

    private final LineageColumnEdgeRepository columnEdges;
    private final ColumnTagRepository tagRepository;
    private final SensitivityFindingRepository findingRepository;
    private final SensitivitySuppressionRepository suppressionRepository;
    private final SensitivityTagWriter tagWriter;
    private final SensitivitySettingsService settingsService;
    private final RestServerProperties properties;
    private final Clock clock;

    /**
     * 从这次扫描覆盖的表出发传播一轮。
     *
     * <p>调用点在扫描写完成终态之后，异常一律吞掉并记 WARN：传播是**收尾的增值动作**，
     * 让它把一次已经成功的扫描变成失败，等于「扫完了、结论也对，但接口报错」。
     *
     * @param scan  这次扫描（{@code id} 与 {@code catalogName} 是必需的）
     * @param seeds 这次扫描覆盖的表（库名 + 表名）；{@code CATALOG} 级扫描就是整个 catalog
     * @return 本轮写了什么，供调用方记日志（也便于测试断言）
     */
    public Result propagate(SensitivityScanEntity scan, List<Seed> seeds) {
        if (seeds == null || seeds.isEmpty()) {
            return Result.EMPTY;
        }
        String catalog = scan.getCatalogName();
        int maxDepth = settingsService.resolvedPropagationMaxDepth(SensitivityCatalogSettings
                .read(settingsService.requireCatalog(catalog).getProperties()));
        double decay = properties.getSensitivity().getPropagation().getConfidenceDecay();
        if (decay <= 0 || decay > 1) {
            // 配错了就按默认值走，而不是把置信度乘以一个 >1 的系数——
            // 「继承来的结论比上游更可信」没有任何依据，且会让人审队列的排序整体失真
            log.warn("lakehouse.rest.sensitivity.propagation.confidence-decay={} is not in (0,1];"
                    + " falling back to 0.9 for catalog {}", decay, catalog);
            decay = 0.9d;
        }

        Set<String> suppressed = suppressedKeys(catalog);
        Set<String> suggested = existingSuggestionKeys(scan.getId());
        Set<String> visited = new HashSet<>();
        Map<String, List<LineageColumnEdgeEntity>> edgesBySource = new LinkedHashMap<>();
        Deque<Node> queue = new ArrayDeque<>();
        for (Seed seed : seeds) {
            enqueueSeeds(catalog, seed, queue, visited, suppressed);
        }

        int tagsWritten = 0;
        int suggestions = 0;
        int crossesCatalog = 0;
        List<SensitivityFindingEntity> pending = new ArrayList<>();
        Set<String> touchedTables = new LinkedHashSet<>();
        while (!queue.isEmpty() && tagsWritten + suggestions < MAX_WRITES) {
            Node node = queue.poll();
            String sourceRef = LineageRefs.of(catalog, node.database(), node.table());
            String sourceHash = Digests.sha256Hex(sourceRef);
            for (LineageColumnEdgeEntity edge : edgesBySource.computeIfAbsent(sourceRef,
                    ref -> columnEdges.findBySourceRefHash(sourceHash))) {
                if (!node.column().equals(edge.getSourceColumn())) {
                    // 同表的其它列：仓储一次取回整张表的列边，配对照样在内存里做
                    continue;
                }
                LineageRefs.Parsed target = LineageRefs.parse(edge.getTargetRef()).orElse(null);
                if (target == null || target.namespace().isEmpty()
                        || edge.getTargetColumn() == null || edge.getTargetColumn().isBlank()) {
                    continue;
                }
                if (!target.catalog().equals(catalog)) {
                    // 跨 catalog 不传播：扫描是 catalog 级治理动作，写别人家的标签会绕过那里的判权
                    crossesCatalog++;
                    continue;
                }
                String database = String.join(".", target.namespace());
                String targetColumn = edge.getTargetColumn();
                String targetHash = ColumnRefs.hash(catalog, database, target.table(), targetColumn);
                String suppressionKey = node.typeCode() + "|" + targetHash;
                if (suppressed.contains(suppressionKey)) {
                    // 人审否决过的结论，连建议都不提（§7.2）
                    continue;
                }
                String kind = edge.getTransformKind();
                int depth = node.depth() + 1;
                if (!DIRECT.equals(kind) && !DERIVED.equals(kind) && !AGGREGATE.equals(kind)) {
                    continue;
                }
                String visitKey = targetHash + "|" + node.typeCode();
                if (!visited.add(visitKey)) {
                    // 环：血缘图本来就有环（lineage-design 讨论过），visited 是唯一的刹车
                    continue;
                }
                // 这一跳的置信度先算出来，三种归宿（落标签 / 停在建议 / 聚合建议）共用它：
                // 建议的排序依据是「这条有多值得看」，与标签的分数必须是同一个口径，
                // 否则人审队列里「0.81 的建议」与「0.729 的标签」说的是同一件事却排在两处。
                double factor = DIRECT.equals(kind) ? decay
                        : AGGREGATE.equals(kind) ? decay : decay * DERIVED_FACTOR;
                double confidence = clamp(node.confidence() * factor);
                if (depth > maxDepth) {
                    // 超过深度上限：停在建议，不再往下推。这一条同时是「误标不会沿长链扩散」的保证
                    if (suggested.add(visitKey)) {
                        pending.add(suggestion(scan, catalog, database, target.table(), targetColumn,
                                targetHash, node, depth, kind, confidence, "DEPTH_EXCEEDED",
                                "inherited depth would exceed the limit of " + maxDepth + " hops"));
                        suggestions++;
                    }
                    continue;
                }
                if (AGGREGATE.equals(kind)) {
                    // 聚合列不继承：count(id_card) 与 max(salary) 无法用规则分开，交给人
                    if (suggested.add(visitKey)) {
                        pending.add(suggestion(scan, catalog, database, target.table(), targetColumn,
                                targetHash, node, depth, kind, confidence, "AGGREGATE",
                                "an aggregate over a sensitive column may or may not be sensitive"));
                        suggestions++;
                    }
                    continue;
                }
                boolean written = tagWriter.applyInherited(catalog, database, target.table(),
                        targetColumn, node.typeCode(), confidence,
                        ColumnRefs.fullName(catalog, node.database(), node.table(), node.column()),
                        auditPrincipal()).isPresent();
                if (written) {
                    tagsWritten++;
                    touchedTables.add(database + "\u0000" + target.table());
                }
                if (DERIVED.equals(kind) && suggested.add(visitKey)) {
                    // 派生列额外要人看一眼：变形之后还算不算同一个结论，规则给不出答案
                    pending.add(suggestion(scan, catalog, database, target.table(), targetColumn,
                            targetHash, node, depth, kind, confidence, "DERIVED",
                            "the column is derived from " + ColumnRefs.fullName(catalog,
                                    node.database(), node.table(), node.column())
                                    + " (" + edge.getExpression() + "); confirm the inheritance"));
                    suggestions++;
                }
                // 无论标签写没写进去都要继续往下走：目标列已经有更强结论时 applyInherited 会返回空，
                // 但那条链下游还没被推过（第一次传播到这里）。用本轮算出的置信度继续，
                // 是保守的一侧——最多把下游算低一点，不会把它算高。
                queue.add(new Node(database, target.table(), targetColumn, node.typeCode(),
                        confidence, depth));
            }
        }
        if (!pending.isEmpty()) {
            findingRepository.saveAll(pending);
        }
        for (String key : touchedTables) {
            String[] parts = key.split("\u0000", -1);
            // 表级级别 = 该表所有列标签的 max(level)（§5.5）。继承来的标签同样抬高它，
            // 否则「这张表里最敏感的一列有多敏感」会漏掉整条传播链。
            tagWriter.refreshTableLevel(catalog, parts[0], parts[1], auditPrincipal());
        }
        if (tagsWritten > 0 || suggestions > 0) {
            log.info("sensitivity propagation for scan {}: {} inherited tag(s), {} suggestion(s),"
                    + " {} cross-catalog edge(s) skipped, maxDepth={}",
                    scan.getId(), tagsWritten, suggestions, crossesCatalog, maxDepth);
        }
        return new Result(tagsWritten, suggestions, crossesCatalog);
    }

    /**
     * 把一张表的已启用列级标签放进队列。
     *
     * <p>只认**有类型且有分级**的标签：{@code owner.team} 这类自由标签没有敏感语义，
     * 传下去只会让下游多一条解释不了的标签。置信度为空的（人工打的）按 1.0 起算——
     * 人工结论的权威性就是满分，它往下游折损才是我们要的语义。
     */
    private void enqueueSeeds(String catalog, Seed seed, Deque<Node> queue, Set<String> visited,
                              Set<String> suppressed) {
        for (ColumnTagEntity tag : tagRepository.findByCatalogNameAndDatabaseNameAndTableName(
                catalog, seed.database(), seed.table())) {
            if (!tag.isEnabled() || ColumnRefs.isTableLevel(tag.getColumnName())) {
                // 表级标签不参与传播：它是「这张表里最敏感的一列有多敏感」的汇总结论，
                // 没有对应的列可以继承（与 §5.5 拍板的「表级标签不参与策略展开」同一理由）
                continue;
            }
            if (tag.getTypeCode() == null || tag.getTypeCode().isBlank() || tag.level() == null) {
                continue;
            }
            String key = tag.getColumnRefHash() + "|" + tag.getTypeCode();
            if (suppressed.contains(key) || !visited.add(key)) {
                continue;
            }
            double confidence = tag.getConfidence() == null ? 1.0d : tag.getConfidence();
            queue.add(new Node(seed.database(), seed.table(), tag.getColumnName(),
                    tag.getTypeCode(), confidence, 0));
        }
    }

    /**
     * 落一条「请人看一眼」的建议（{@code PENDING}）。
     *
     * <p><b>{@code matched_detectors_json} 记的是来历，不是得分。</b>人审看到一行
     * 「{@code DW.DIM_USER.id_no} 可能是身份证」时必须能回答「凭什么」——
     * 这里是「{@code 第 2 跳、AGGREGATE 边、来自 ods.user.id_card}」，
     * 而那些数字与探测器得分长得一样（{@code {"name":0.9}}）。用 {@code inherited} /
     * {@code reason} 两个键把两者分开，比塞一个 {@code {"name":0.0}} 诚实。
     *
     * <p>置信度照实写（已经逐跳折损过）：它是「这条建议有多值得看」的唯一排序依据，
     * 人审队列按它降序（{@code findByScanIdAndStateOrderByConfidenceDesc}）。
     */
    private SensitivityFindingEntity suggestion(SensitivityScanEntity scan, String catalog,
                                                String database, String table, String column,
                                                String columnHash, Node node, int depth, String kind,
                                                double confidence, String reason, String detail) {
        SensitivityFindingEntity entity = new SensitivityFindingEntity();
        entity.setScanId(scan.getId());
        entity.setCatalogName(catalog);
        entity.setDatabaseName(database);
        entity.setTableName(table);
        entity.setColumnName(column);
        entity.setColumnRefHash(columnHash);
        entity.setTypeCode(node.typeCode());
        entity.setConfidence(confidence);
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("inherited", true);
        provenance.put("depth", depth);
        provenance.put("transformKind", kind);
        provenance.put("reason", reason);
        provenance.put("detail", detail);
        provenance.put("fromColumn", ColumnRefs.fullName(catalog, node.database(),
                node.table(), node.column()));
        entity.setMatchedDetectorsJson(Json.write(provenance));
        // 没有采样：这条建议不是从值里看出来的。留空而不是默认 HEAD——
        // 「没采」与「采到了但用的是前 N 行」是两件事，而采样方式会被人审拿来判断可信度
        entity.setSamplingMode(null);
        entity.setState(FindingState.PENDING.name());
        entity.markCreated(auditPrincipal(), clock.millis());
        return entity;
    }

    /** 这个 catalog 里仍生效的抑制项，键是 {@code typeCode|columnRefHash}（与扫描同一写法）。 */
    private Set<String> suppressedKeys(String catalog) {
        Set<String> keys = new HashSet<>();
        for (SensitivitySuppressionEntity entity
                : suppressionRepository.findByCatalogNameAndEnabledFlagTrueOrderByDecidedAtDesc(catalog)) {
            keys.add(entity.getTypeCode() + "|" + entity.getColumnRefHash());
        }
        return keys;
    }

    /**
     * 这次扫描里已经有的建议，键是 {@code columnRefHash|typeCode}。
     *
     * <p>扫描自己已经为这一列提过同一个类型时（值层命中过），传播不再补一条——
     * 同一次扫描里同列同型只该有一条记录，这也是 finding 唯一键的语义。
     */
    private Set<String> existingSuggestionKeys(String scanId) {
        Set<String> keys = new HashSet<>();
        for (SensitivityFindingEntity entity : findingRepository.findByScanIdOrderByConfidenceDesc(scanId)) {
            keys.add(entity.getColumnRefHash() + "|" + entity.getTypeCode());
        }
        return keys;
    }

    private static String auditPrincipal() {
        return RequestContext.auditPrincipal();
    }

    private static double clamp(double value) {
        if (Double.isNaN(value)) {
            return 0d;
        }
        return Math.max(0d, Math.min(1d, value));
    }

    /** 一张种子表：这次扫描覆盖到的库名与表名。 */
    public record Seed(String database, String table) {
    }

    /** 一轮传播的产出（写了几条继承标签、几条建议、跳过了几条跨 catalog 的边）。 */
    public record Result(int tagsWritten, int suggestions, int crossCatalogSkipped) {

        public static final Result EMPTY = new Result(0, 0, 0);
    }

    /** BFS 队列里的一个「已经知道的敏感结论」：哪一列、什么类型、当前置信度、第几跳。 */
    private record Node(String database, String table, String column, String typeCode,
                        double confidence, int depth) {
    }
}
