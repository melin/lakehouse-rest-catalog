package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.config.RequestContext;
import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.domain.entity.DatabaseEntity;
import io.github.melin.lakehouse.rest.domain.entity.LineageColumnEdgeEntity;
import io.github.melin.lakehouse.rest.domain.entity.LineageRunEntity;
import io.github.melin.lakehouse.rest.domain.entity.LineageTableEdgeEntity;
import io.github.melin.lakehouse.rest.domain.entity.TableEntity;
import io.github.melin.lakehouse.rest.domain.repo.CatalogRepository;
import io.github.melin.lakehouse.rest.domain.repo.DatabaseRepository;
import io.github.melin.lakehouse.rest.domain.repo.LineageColumnEdgeRepository;
import io.github.melin.lakehouse.rest.domain.repo.LineageRunRepository;
import io.github.melin.lakehouse.rest.domain.repo.LineageTableEdgeRepository;
import io.github.melin.lakehouse.rest.domain.repo.TableRepository;
import io.github.melin.lakehouse.rest.dto.LineageDtos.Budget;
import io.github.melin.lakehouse.rest.dto.LineageDtos.ColumnEdgeGraph;
import io.github.melin.lakehouse.rest.dto.LineageDtos.ColumnEdgeRow;
import io.github.melin.lakehouse.rest.dto.LineageDtos.ColumnGraph;
import io.github.melin.lakehouse.rest.dto.LineageDtos.ColumnGroup;
import io.github.melin.lakehouse.rest.dto.LineageDtos.ColumnLink;
import io.github.melin.lakehouse.rest.dto.LineageDtos.ColumnReport;
import io.github.melin.lakehouse.rest.dto.LineageDtos.ColumnSource;
import io.github.melin.lakehouse.rest.dto.LineageDtos.CompactEdge;
import io.github.melin.lakehouse.rest.dto.LineageDtos.CompactGraph;
import io.github.melin.lakehouse.rest.dto.LineageDtos.CompactNode;
import io.github.melin.lakehouse.rest.dto.LineageDtos.IngestResult;
import io.github.melin.lakehouse.rest.dto.LineageDtos.ReportBatch;
import io.github.melin.lakehouse.rest.dto.LineageDtos.RunDetail;
import io.github.melin.lakehouse.rest.dto.LineageDtos.RunList;
import io.github.melin.lakehouse.rest.dto.LineageDtos.RunReport;
import io.github.melin.lakehouse.rest.dto.LineageDtos.RunSummary;
import io.github.melin.lakehouse.rest.dto.LineageDtos.SourceReport;
import io.github.melin.lakehouse.rest.dto.LineageDtos.TableEdge;
import io.github.melin.lakehouse.rest.dto.LineageDtos.TableGraph;
import io.github.melin.lakehouse.rest.dto.LineageDtos.TableNode;
import io.github.melin.lakehouse.rest.dto.LineageDtos.TargetReport;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.Access;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.ResourceType;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.AuditPrincipal;
import io.github.melin.lakehouse.rest.support.Digests;
import io.github.melin.lakehouse.rest.support.LineageRefs;
import jakarta.persistence.criteria.Predicate;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 血缘的接收、归一、存储与查询（{@code docs/lineage-design.md} §6–§8）。
 *
 * <p><b>两个端点问的不是同一个问题。</b>上报是「这次执行里，哪一列来自哪一列」，
 * 落到三张表：run（事件，只插入）、表级边、列级边（状态，upsert）。查询返回的是
 * <b>当前</b>的图，不是某次执行的快照——代价是看不到「历史上某次执行时的列映射」，
 * 收益是图查询不用在几十万行里 {@code DISTINCT}（§7.1）。
 *
 * <p><b>服务端不认识 {@code AttributeReference}。</b>归一化在客户端完成（引擎手里有
 * 已解析的计划树），这里只认「节点 / 表级边 / 列级边」三个概念。唯一保留的语义是
 * {@code ~} 前缀（外部节点）：它决定「要不要判权」——不是目录里的东西，
 * 问「你有没有权限」没有意义。
 *
 * <p><b>上报失败绝不影响作业</b>（§6.6）是客户端的义务；服务端的对应义务是把拒绝的
 * 理由说清楚：400 是报文形状不对、403 是缺权限、409 是同一个 {@code runId} 内容却不同。
 * 一律回 500 会让客户端只能整批重试。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LineageService {

    /** 图节点数的硬上限。超过它 400 而不是静默截断（§8.6）。 */
    public static final int MAX_NODES_HARD_LIMIT = 2000;

    /** 批量列边（§8.4）一次最多多少个 ref。列级图只为小图准备，超过 400。 */
    public static final int MAX_COLUMN_EDGE_REFS = 64;

    /** {@code sqlText} 的入库上限，与设计文档 §6.2 一致。 */
    public static final int MAX_SQL_TEXT = 8192;

    /** {@code expression} 的入库上限（列宽 512）。 */
    public static final int MAX_EXPRESSION = 512;

    private static final int MAX_REF_LENGTH = 1024;
    private static final int MAX_COLUMN_LENGTH = 255;

    private static final Set<String> ENGINES = Set.of("SPARK");
    private static final Set<String> STATEMENT_TYPES = Set.of("INSERT", "MERGE", "CTAS");
    private static final Set<String> WRITE_MODES = Set.of("APPEND", "OVERWRITE", "UPSERT", "CREATE");
    private static final Set<String> RUN_MODES = Set.of("BATCH", "STREAMING");
    private static final Set<String> KINDS =
            Set.of("DIRECT", "DERIVED", "AGGREGATE", "CONSTANT", "UNRESOLVED");

    /** {@code |ref|} 的分隔符，见 {@code LineageRunEntity} 的类注释。 */
    private static final char REF_SEPARATOR = '|';

    private final LineageRunRepository runRepository;
    private final LineageTableEdgeRepository tableEdgeRepository;
    private final LineageColumnEdgeRepository columnEdgeRepository;
    private final CatalogRepository catalogRepository;
    private final DatabaseRepository databaseRepository;
    private final TableRepository tableRepository;
    private final AuthorizationService authorizationService;
    private final RestServerProperties properties;
    private final Clock clock;

    // ================================================================== 上报

    /**
     * 接收一批 run。
     *
     * <p>整批一个事务：其中一条 run 判权失败就整批回滚。理由与「不做部分接受」相同——
     * 半条 run 入库会让图上出现「目标表有边、源表没边」的假象（§6.5）。
     * 客户端拿 403 重试时，整批重发是安全的（{@code runId} 幂等）。
     */
    @Transactional
    public IngestResult ingest(ReportBatch batch) {
        RestServerProperties.Lineage config = properties.getLineage();
        if (!config.isEnabled()) {
            throw ApiException.forbidden(
                    "Lineage collection is disabled on this server (lakehouse.rest.lineage.enabled=false)");
        }
        if (batch == null || batch.runs() == null || batch.runs().isEmpty()) {
            throw ApiException.badRequest("runs is required");
        }
        if (batch.runs().size() > config.getMaxRunsPerBatch()) {
            throw ApiException.badRequest("At most " + config.getMaxRunsPerBatch()
                    + " runs per batch, got " + batch.runs().size());
        }

        int accepted = 0;
        int deduplicated = 0;
        int truncated = 0;
        for (RunReport report : batch.runs()) {
            validate(report);
            // 先判权再看幂等：重复上报一条自己都无权声明的 run，回 200 等于确认了它的内容
            requirePermission(report);
            if (runRepository.existsByRunId(report.runId())) {
                deduplicated++;
                continue;
            }
            truncated += store(report);
            accepted++;
        }
        return new IngestResult(accepted, deduplicated, truncated);
    }

    private void validate(RunReport report) {
        if (report == null) {
            throw ApiException.badRequest("runs must not contain null entries");
        }
        requireText(report.runId(), "runId", 64);
        if (!ENGINES.contains(report.engine())) {
            throw ApiException.badRequest("Unknown engine: " + report.engine()
                    + ". This server only accepts " + ENGINES);
        }
        requireText(report.engineVersion(), "engineVersion", 32);
        if (!STATEMENT_TYPES.contains(report.statementType())) {
            throw ApiException.badRequest("Unknown statementType: " + report.statementType()
                    + ", expected one of " + STATEMENT_TYPES);
        }
        if (report.writeMode() != null && !WRITE_MODES.contains(report.writeMode())) {
            throw ApiException.badRequest("Unknown writeMode: " + report.writeMode()
                    + ", expected one of " + WRITE_MODES);
        }
        if (report.mode() != null && !RUN_MODES.contains(report.mode())) {
            throw ApiException.badRequest("Unknown mode: " + report.mode()
                    + ", expected one of " + RUN_MODES);
        }
        if (report.targets() == null || report.targets().isEmpty()) {
            throw ApiException.badRequest("targets is required: a run without a target is not a lineage");
        }
        for (TargetReport target : report.targets()) {
            requireRef(target.table(), "targets[].table");
        }
        if (report.sources() != null) {
            for (SourceReport source : report.sources()) {
                requireRef(source.table(), "sources[].table");
            }
        }
    }

    private void requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(field + " is required");
        }
        if (value.length() > maxLength) {
            throw ApiException.badRequest(field + " is longer than " + maxLength + " characters");
        }
    }

    private void requireRef(String ref, String field) {
        if (ref == null || ref.isBlank()) {
            throw ApiException.badRequest(field + " is required");
        }
        if (ref.length() > MAX_REF_LENGTH) {
            throw ApiException.badRequest(field + " is longer than " + MAX_REF_LENGTH + " characters");
        }
        if (!LineageRefs.isExternal(ref) && LineageRefs.parse(ref).isEmpty()) {
            throw ApiException.badRequest(field + " must be 'catalog.namespace.table' or start with '~': " + ref);
        }
    }

    /**
     * 判权：写目标要 {@code UPDATE}，读源要 {@code DESCRIBE}（§6.5）。
     *
     * <p>设计文档写的是 {@code TABLE_WRITE_DATA} / {@code TABLE_READ_PROPERTIES}，
     * 本仓的权限模型是 Polaris 那 12 项，对应关系是 {@code UPDATE} / {@code DESCRIBE}；
     * 判定本身复用 {@link AuthorizationService#require}，不新造一套。
     *
     * <p>刻意<b>不校验血缘的真伪</b>：能做到的上限是「确认你有权动这些表」，
     * 也就是「能写这张表的人才允许声称写了它」。伪造的缓解手段是 principal 由服务端
     * 解析、边带 engine 与 lastSeen、sqlHash 可回溯；不做签名。
     */
    private void requirePermission(RunReport report) {
        if (!authorizationService.enabled()) {
            return;
        }
        for (TargetReport target : report.targets()) {
            requireTable(target.table(), Access.UPDATE, "claim to write a table in lineage");
        }
        if (report.sources() != null) {
            for (SourceReport source : report.sources()) {
                requireTable(source.table(), Access.DESCRIBE, "claim to read a table in lineage");
            }
        }
    }

    private void requireTable(String ref, Access access, String action) {
        if (LineageRefs.isExternal(ref)) {
            return;
        }
        LineageRefs.Parsed parsed = LineageRefs.parse(ref).orElseThrow(
                () -> ApiException.badRequest("Cannot resolve catalog from " + ref));
        authorizationService.require(parsed.catalog(), ResourceType.TABLE, parsed.namespace(),
                parsed.table(), access, action + ": " + ref);
    }

    /** @return 因超过 {@code max-columns-per-target} 被丢弃的列数（0 表示没有降级） */
    private int store(RunReport report) {
        long now = clock.millis();
        long startedAt = report.startedAt() == null ? now : report.startedAt();
        long finishedAt = report.finishedAt() == null ? now : report.finishedAt();
        String engine = report.engine();
        String writeMode = report.writeMode() == null ? defaultWriteMode(report.statementType())
                : report.writeMode();
        String principal = AuditPrincipal.of(RequestContext.principal());

        LineageRunEntity run = new LineageRunEntity();
        run.setRunId(report.runId());
        run.setEngine(engine);
        run.setEngineVersion(report.engineVersion());
        run.setStatementType(report.statementType());
        run.setWriteMode(writeMode);
        run.setMode(report.mode() == null ? "BATCH" : report.mode());
        run.setCatalog(catalogOf(report.targets().get(0).table()));
        run.setPrincipal(principal);
        run.setSqlHash(blankToNull(report.sqlHash()));
        run.setSqlText(truncate(report.sqlText(), MAX_SQL_TEXT));
        run.setStartedAt(startedAt);
        run.setFinishedAt(finishedAt);
        run.setTargetRefs(targetRefs(report));
        run.setAttributes(report.attributes() == null ? Map.of() : Map.copyOf(report.attributes()));

        int truncatedColumns = 0;
        int limit = properties.getLineage().getMaxColumnsPerTarget();
        List<List<ColumnReport>> columnsPerTarget = new ArrayList<>(report.targets().size());
        for (TargetReport target : report.targets()) {
            List<ColumnReport> columns = target.columns();
            if (columns != null && columns.size() > limit) {
                // 降级为表级：不写半套列边（半套比没有更误导——图上会显示「只有 2000 列有来源」）
                log.debug("Lineage run {} truncated columns of {} from {} to {}",
                        report.runId(), target.table(), columns.size(), limit);
                truncatedColumns += columns.size();
                columns = null;
            }
            columnsPerTarget.add(columns);
        }
        run.setColumnsTruncated(truncatedColumns > 0);
        runRepository.save(run);

        for (int i = 0; i < report.targets().size(); i++) {
            TargetReport target = report.targets().get(i);
            storeTableEdges(report, target, engine, writeMode, finishedAt);
            List<ColumnReport> columns = columnsPerTarget.get(i);
            if (columns != null) {
                storeColumnEdges(report, target, columns, finishedAt);
            }
        }
        return truncatedColumns;
    }

    private String defaultWriteMode(String statementType) {
        return switch (statementType) {
            case "MERGE" -> "UPSERT";
            case "CTAS" -> "CREATE";
            default -> "APPEND";
        };
    }

    private void storeTableEdges(RunReport report, TargetReport target, String engine,
                                 String writeMode, long finishedAt) {
        List<SourceReport> sources = report.sources() == null ? List.of() : report.sources();
        Set<String> seen = new HashSet<>();
        for (SourceReport source : sources) {
            String sourceRef = source.table();
            if (!seen.add(sourceRef)) {
                continue;
            }
            String targetRef = target.table();
            String sourceHash = Digests.sha256Hex(sourceRef);
            String targetHash = Digests.sha256Hex(targetRef);
            LineageTableEdgeEntity edge = tableEdgeRepository
                    .findBySourceRefHashAndTargetRefHashAndEngine(sourceHash, targetHash, engine)
                    .orElseGet(() -> {
                        LineageTableEdgeEntity created = new LineageTableEdgeEntity();
                        created.setSourceRef(sourceRef);
                        created.setSourceRefHash(sourceHash);
                        created.setTargetRef(targetRef);
                        created.setTargetRefHash(targetHash);
                        created.setEngine(engine);
                        created.setRunCount(0L);
                        created.setFirstSeen(finishedAt);
                        return created;
                    });
            edge.setEdgeKind(edgeKind(sourceRef));
            edge.setWriteMode(writeMode);
            edge.setRunCount(edge.getRunCount() + 1);
            edge.setLastSeen(finishedAt);
            edge.setLastRunId(report.runId());
            tableEdgeRepository.save(edge);
        }
    }

    private void storeColumnEdges(RunReport report, TargetReport target, List<ColumnReport> columns,
                                  long finishedAt) {
        String targetRef = target.table();
        String targetHash = Digests.sha256Hex(targetRef);

        // 一次取回「这一对表之间已有的列边」，再在内存里配对：一列一查在宽表上是几百次往返
        Map<String, Map<String, LineageColumnEdgeEntity>> existing = new HashMap<>();
        for (ColumnReport column : columns) {
            for (String sourceRef : sourceRefsOf(column)) {
                existing.computeIfAbsent(sourceRef, ref -> {
                    String sourceHash = Digests.sha256Hex(ref);
                    Map<String, LineageColumnEdgeEntity> byKey = new HashMap<>();
                    for (LineageColumnEdgeEntity entity
                            : columnEdgeRepository.findByTargetRefHashAndSourceRefHash(targetHash, sourceHash)) {
                        byKey.put(columnKey(entity.getSourceColumn(), entity.getTargetColumn()), entity);
                    }
                    return byKey;
                });
            }
        }

        for (ColumnReport column : columns) {
            String targetColumn = column.targetColumn();
            if (targetColumn == null || targetColumn.isBlank()) {
                throw ApiException.badRequest("targets[].columns[].targetColumn is required");
            }
            if (targetColumn.length() > MAX_COLUMN_LENGTH) {
                throw ApiException.badRequest("targetColumn is longer than " + MAX_COLUMN_LENGTH + ": "
                        + targetColumn);
            }
            String kind = normalizeKind(column.kind());
            String expression = truncate(column.expression(), MAX_EXPRESSION);

            List<ColumnSource> sources = column.sources() == null ? List.of() : column.sources();
            if (sources.isEmpty()) {
                // 常量列 / 静态分区列：没有源，但这一列确实被写了（§4.5）。
                // 用空 source 占一行，"没有源" 因此在数据里是显式的，不是查不到。
                upsertColumnEdge(existing, "", "", targetRef, targetHash, targetColumn,
                        kind, expression, report.runId(), finishedAt);
                continue;
            }
            Set<String> seen = new HashSet<>();
            for (ColumnSource source : sources) {
                String sourceRef = source.table();
                String sourceColumn = source.column() == null ? "" : source.column();
                if (sourceColumn.length() > MAX_COLUMN_LENGTH) {
                    throw ApiException.badRequest("source column is longer than " + MAX_COLUMN_LENGTH
                            + ": " + sourceColumn);
                }
                if (!seen.add(sourceRef + '\u0000' + sourceColumn)) {
                    continue;
                }
                upsertColumnEdge(existing, sourceRef, sourceColumn, targetRef, targetHash, targetColumn,
                        kind, expression, report.runId(), finishedAt);
            }
        }
    }

    private List<String> sourceRefsOf(ColumnReport column) {
        if (column.sources() == null || column.sources().isEmpty()) {
            return List.of("");
        }
        List<String> refs = new ArrayList<>();
        for (ColumnSource source : column.sources()) {
            refs.add(source.table() == null ? "" : source.table());
        }
        return refs;
    }

    private void upsertColumnEdge(Map<String, Map<String, LineageColumnEdgeEntity>> existing,
                                  String sourceRef, String sourceColumn, String targetRef,
                                  String targetHash, String targetColumn, String kind,
                                  String expression, String runId, long finishedAt) {
        String sourceHash = Digests.sha256Hex(sourceRef);
        Map<String, LineageColumnEdgeEntity> byKey =
                existing.computeIfAbsent(sourceRef, ref -> new HashMap<>());
        String key = columnKey(sourceColumn, targetColumn);
        LineageColumnEdgeEntity edge = byKey.get(key);
        if (edge == null) {
            edge = new LineageColumnEdgeEntity();
            edge.setSourceRef(sourceRef);
            edge.setSourceRefHash(sourceHash);
            edge.setSourceColumn(sourceColumn);
            edge.setTargetRef(targetRef);
            edge.setTargetRefHash(targetHash);
            edge.setTargetColumn(targetColumn);
            byKey.put(key, edge);
        }
        // kind 与 expression 取最近一次：同一对列可能先 DIRECT 后 DERIVED（§7.2）
        edge.setTransformKind(kind);
        edge.setExpression(expression);
        edge.setLastRunId(runId);
        edge.setLastSeen(finishedAt);
        columnEdgeRepository.save(edge);
    }

    private static String columnKey(String sourceColumn, String targetColumn) {
        return sourceColumn + '\u0000' + targetColumn;
    }

    /**
     * 拼 {@code |ref1|ref2|}。
     *
     * <p>写成「每个目标前面加一个分隔符、末尾再补一个」，而不是
     * {@code new StringBuilder(REF_SEPARATOR)}：{@code REF_SEPARATOR} 是 {@code char}，
     * 那个构造函数会被解析成 {@code StringBuilder(int capacity)}（char 拓宽成 int），
     * 于是前导竖线根本不存在，而 {@code like '%|ref|%'} 一条也匹配不上——
     * 症状是「按表过滤运行历史永远为空」，看起来像没采集到。
     */
    private String targetRefs(RunReport report) {
        StringBuilder sb = new StringBuilder();
        for (TargetReport target : report.targets()) {
            sb.append(REF_SEPARATOR).append(target.table());
        }
        return sb.append(REF_SEPARATOR).toString();
    }

    private static String catalogOf(String ref) {
        return LineageRefs.parse(ref).map(LineageRefs.Parsed::catalog).orElse("");
    }

    private static String edgeKind(String ref) {
        return LineageRefs.isExternal(ref) ? "EXTERNAL" : "TABLE";
    }

    private static String normalizeKind(String kind) {
        if (kind == null || kind.isBlank()) {
            // 缺 kind 时按 UNRESOLVED 处理，而不是猜 DIRECT：宁可标灰，也不要编一条确定的边
            return "UNRESOLVED";
        }
        String upper = kind.trim().toUpperCase(java.util.Locale.ROOT);
        return KINDS.contains(upper) ? upper : "UNRESOLVED";
    }

    private static String truncate(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    // ================================================================== 图查询

    /** 遍历方向。 */
    public enum Direction {
        UPSTREAM, DOWNSTREAM, BOTH;

        static Direction parse(String value) {
            if (value == null || value.isBlank()) {
                return DOWNSTREAM;
            }
            return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "upstream" -> UPSTREAM;
                case "both" -> BOTH;
                case "downstream" -> DOWNSTREAM;
                default -> throw ApiException.badRequest(
                        "Unknown direction: " + value + ", expected upstream / downstream / both");
            };
        }
    }

    /** 一次图查询的结果：默认形状、紧凑形状与 ETag（控制器按 {@code compact} 选一个）。 */
    public record GraphPayload(TableGraph full, CompactGraph compact, String etag) {
    }

    /**
     * 表级上下游（§8.1 / §8.6）。
     *
     * <p>BFS 用 {@code visited} 集合阻断环：{@code A → B → A} 的回刷是真实存在的，
     * 不是错误。形成环的那条边仍在结果里——它说明「这两张表互相喂」，删掉反而是丢信息。
     *
     * <p>节点预算先按跳数、再按 {@code runCount}：同一层里先给常跑的边，
     * 因为它们更可能是用户想看的干路。超预算时返回已展开部分并标 {@code truncated}，
     * <b>不报错</b>——报错会让「看一眼大图」变成不可能。
     */
    @Transactional(readOnly = true)
    public GraphPayload tables(String ref, String direction, Integer depth, String engine,
                               Integer maxNodes, boolean columnCount) {
        String root = requireRoot(ref);
        Direction dir = Direction.parse(direction);
        int hops = depth == null ? 1 : depth;
        int limit = maxNodes == null ? properties.getLineage().getMaxNodes() : maxNodes;
        if (hops < 1) {
            throw ApiException.badRequest("depth must be at least 1");
        }
        if (hops > properties.getLineage().getMaxDepth()) {
            throw ApiException.badRequest("depth must not exceed " + properties.getLineage().getMaxDepth());
        }
        if (limit < 1 || limit > MAX_NODES_HARD_LIMIT) {
            throw ApiException.badRequest("maxNodes must be between 1 and " + MAX_NODES_HARD_LIMIT);
        }

        String engineFilter = engine == null || engine.isBlank() ? null : engine.trim();
        long[] budgetCounts = directNeighbourCounts(root, dir, engineFilter);

        Map<String, LineageTableEdgeEntity> edges = new LinkedHashMap<>();
        Set<String> visited = new LinkedHashSet<>();
        visited.add(root);
        List<String> boundary = new ArrayList<>();
        boolean truncated = false;

        record Hop(String ref, int hop) {
        }
        Deque<Hop> queue = new ArrayDeque<>();
        queue.add(new Hop(root, 0));
        outer:
        while (!queue.isEmpty()) {
            Hop current = queue.poll();
            if (current.hop() >= hops) {
                boundary.add(current.ref());
                continue;
            }
            for (LineageTableEdgeEntity edge : neighbours(current.ref(), dir, engineFilter)) {
                edges.putIfAbsent(edgeKey(edge), edge);
                String next = nextRef(edge, current.ref(), dir);
                if (next == null || visited.contains(next)) {
                    continue;
                }
                if (visited.size() >= limit) {
                    truncated = true;
                    break outer;
                }
                visited.add(next);
                queue.add(new Hop(next, current.hop() + 1));
            }
        }

        Set<String> moreUpstream = boundaryWithMore(boundary, true);
        Set<String> moreDownstream = boundaryWithMore(boundary, false);
        Map<String, NodeMeta> meta = describe(visited);
        Map<String, Integer> columnCounts = columnCount ? columnCounts(visited, edges.values()) : Map.of();

        // 边的顺序：按 lastSeen 倒序，让「最近写过」的边排在前面。
        // 布局不依赖顺序（§9.2 的确定性要求），但图例与列表会
        List<LineageTableEdgeEntity> ordered = new ArrayList<>(edges.values());
        ordered.sort(Comparator.comparing(LineageTableEdgeEntity::getLastSeen,
                Comparator.nullsLast(Comparator.reverseOrder())));

        List<TableEdge> tableEdges = new ArrayList<>(ordered.size());
        for (LineageTableEdgeEntity edge : ordered) {
            tableEdges.add(new TableEdge(edge.getSourceRef(), edge.getTargetRef(), edge.getEdgeKind(),
                    edge.getWriteMode(), edge.getEngine(), nullToZero(edge.getLastSeen()),
                    nullToZero(edge.getRunCount()),
                    columnCount ? columnCounts.getOrDefault(edge.getSourceRef() + '\u0000'
                            + edge.getTargetRef(), 0) : null));
        }

        List<TableNode> nodes = new ArrayList<>(visited.size());
        long maxLastSeen = 0;
        for (String nodeRef : visited) {
            NodeMeta info = meta.getOrDefault(nodeRef, NodeMeta.external());
            boolean more = !nodeRef.equals(root)
                    && (moreUpstream.contains(nodeRef) || moreDownstream.contains(nodeRef));
            nodes.add(new TableNode(nodeRef, info.objectType(), info.format(), info.stale(), more));
        }
        for (LineageTableEdgeEntity edge : ordered) {
            maxLastSeen = Math.max(maxLastSeen, nullToZero(edge.getLastSeen()));
        }

        Budget budget = new Budget(budgetCounts[0], budgetCounts[1], visited.size(), truncated);
        TableGraph full = new TableGraph(root, nodes, tableEdges, budget, truncated);
        CompactGraph compact = compact(full);
        String etag = "\"" + Digests.sha256Hex(Long.toString(maxLastSeen), Integer.toString(nodes.size()),
                Integer.toString(tableEdges.size())).substring(0, 16) + "\"";
        return new GraphPayload(full, compact, etag);
    }

    private String requireRoot(String ref) {
        if (ref == null || ref.isBlank()) {
            throw ApiException.badRequest("ref is required");
        }
        String trimmed = ref.trim();
        if (trimmed.length() > MAX_REF_LENGTH) {
            throw ApiException.badRequest("ref is longer than " + MAX_REF_LENGTH + " characters");
        }
        return trimmed;
    }

    /** root 的直接邻居数（真实总数，不随 {@code maxNodes} 变化）。 */
    private long[] directNeighbourCounts(String root, Direction dir, String engine) {
        long upstream = 0;
        long downstream = 0;
        if (dir != Direction.DOWNSTREAM) {
            Set<String> refs = new HashSet<>();
            for (LineageTableEdgeEntity edge : upstreamEdges(root, engine)) {
                refs.add(edge.getSourceRef());
            }
            upstream = refs.size();
        }
        if (dir != Direction.UPSTREAM) {
            Set<String> refs = new HashSet<>();
            for (LineageTableEdgeEntity edge : downstreamEdges(root, engine)) {
                refs.add(edge.getTargetRef());
            }
            downstream = refs.size();
        }
        return new long[] {upstream, downstream};
    }

    private List<LineageTableEdgeEntity> upstreamEdges(String ref, String engine) {
        String hash = Digests.sha256Hex(ref);
        return engine == null ? tableEdgeRepository.findByTargetRefHash(hash)
                : tableEdgeRepository.findByTargetRefHashAndEngine(hash, engine);
    }

    private List<LineageTableEdgeEntity> downstreamEdges(String ref, String engine) {
        String hash = Digests.sha256Hex(ref);
        return engine == null ? tableEdgeRepository.findBySourceRefHash(hash)
                : tableEdgeRepository.findBySourceRefHashAndEngine(hash, engine);
    }

    private List<LineageTableEdgeEntity> neighbours(String ref, Direction dir, String engine) {
        List<LineageTableEdgeEntity> edges = new ArrayList<>();
        if (dir != Direction.DOWNSTREAM) {
            edges.addAll(upstreamEdges(ref, engine));
        }
        if (dir != Direction.UPSTREAM) {
            edges.addAll(downstreamEdges(ref, engine));
        }
        return edges;
    }

    /** 走一步之后落在哪：上游走 {@code source}，下游走 {@code target}；{@code both} 取另一端。 */
    private static String nextRef(LineageTableEdgeEntity edge, String current, Direction dir) {
        if (dir == Direction.UPSTREAM) {
            return edge.getSourceRef();
        }
        if (dir == Direction.DOWNSTREAM) {
            return edge.getTargetRef();
        }
        return edge.getTargetRef().equals(current) ? edge.getSourceRef() : edge.getTargetRef();
    }

    private static String edgeKey(LineageTableEdgeEntity edge) {
        return edge.getSourceRef() + '\u0000' + edge.getTargetRef() + '\u0000' + edge.getEngine();
    }

    /**
     * 边界节点再走一跳是否还有邻居（画不画 {@code ›} 的依据，§8.6）。
     *
     * <p>一次 {@code in} 查询而不是逐节点查：300 个边界节点逐个查就是 300 次往返。
     */
    private Set<String> boundaryWithMore(Collection<String> boundary, boolean upstream) {
        if (boundary.isEmpty()) {
            return Set.of();
        }
        List<String> hashes = boundary.stream().map(Digests::sha256Hex).toList();
        List<LineageTableEdgeEntity> edges = upstream
                ? tableEdgeRepository.findByTargetRefHashIn(hashes)
                : tableEdgeRepository.findBySourceRefHashIn(hashes);
        Set<String> result = new HashSet<>();
        for (LineageTableEdgeEntity edge : edges) {
            result.add(upstream ? edge.getTargetRef() : edge.getSourceRef());
        }
        return result;
    }

    private Map<String, Integer> columnCounts(Collection<String> nodes,
                                              Collection<LineageTableEdgeEntity> edges) {
        Set<String> edgeKeys = new HashSet<>();
        for (LineageTableEdgeEntity edge : edges) {
            edgeKeys.add(edge.getSourceRef() + '\u0000' + edge.getTargetRef());
        }
        List<String> hashes = nodes.stream().map(Digests::sha256Hex).toList();
        Map<String, Integer> counts = new HashMap<>();
        for (LineageColumnEdgeEntity edge : columnEdgeRepository.findByTargetRefHashIn(hashes)) {
            String key = edge.getSourceRef() + '\u0000' + edge.getTargetRef();
            if (!edgeKeys.contains(key)) {
                continue;
            }
            // 常量列（空 source）也是这一对表之间的列映射，算进线宽
            counts.merge(key, 1, Integer::sum);
        }
        return counts;
    }

    private static CompactGraph compact(TableGraph graph) {
        Map<String, Integer> index = new HashMap<>();
        List<CompactNode> nodes = new ArrayList<>(graph.nodes().size());
        for (int i = 0; i < graph.nodes().size(); i++) {
            TableNode node = graph.nodes().get(i);
            index.put(node.ref(), i);
            nodes.add(new CompactNode(node.ref(), node.format(),
                    "EXTERNAL".equals(node.objectType()) ? "E" : "T", node.more() ? 1 : 0,
                    node.stale() ? 1 : 0));
        }
        List<CompactEdge> edges = new ArrayList<>(graph.edges().size());
        for (TableEdge edge : graph.edges()) {
            Integer source = index.get(edge.source());
            Integer target = index.get(edge.target());
            if (source == null || target == null) {
                continue;
            }
            edges.add(new CompactEdge(source, target, "EXTERNAL".equals(edge.kind()) ? "E" : "T",
                    writeModeLetter(edge.writeMode()), edge.engine(), edge.lastSeen(), edge.runCount(),
                    edge.columnCount() == null ? 0 : edge.columnCount()));
        }
        return new CompactGraph(1, index.getOrDefault(graph.root(), 0), nodes, edges, graph.budget());
    }

    private static String writeModeLetter(String writeMode) {
        if (writeMode == null) {
            return "A";
        }
        return switch (writeMode) {
            case "OVERWRITE" -> "O";
            case "UPSERT" -> "U";
            case "CREATE" -> "C";
            default -> "A";
        };
    }

    /** 节点在目录里的样子：能不能点开、什么格式、是不是已经没了。 */
    private record NodeMeta(String objectType, String format, boolean stale) {

        static NodeMeta external() {
            return new NodeMeta("EXTERNAL", null, false);
        }
    }

    /**
     * 批量解析节点：按 (catalog, 库) 分组查，而不是逐节点查。
     *
     * <p>一张 300 节点的图逐节点查是 900 次往返，而分组之后是「不同库的个数」次——
     * 血缘图通常集中在少数几个库上。
     *
     * <p>{@code stale}：目录里找不到这张表（边还在）。**不隐藏**它：边表是状态，
     * 表被删时由事件级联删边，走不到那里的（例如在别的实例上删的）就只剩这条边，
     * 标灰比静默消失诚实（§8.4）。
     */
    private Map<String, NodeMeta> describe(Set<String> refs) {
        Map<String, NodeMeta> result = new HashMap<>();
        Map<String, CatalogEntity> catalogs = new HashMap<>();
        for (CatalogEntity catalog : catalogRepository.findAllByOrderByPrefixAsc()) {
            catalogs.put(catalog.getPrefix(), catalog);
        }
        Map<String, Map<String, Boolean>> tablesByDatabase = new HashMap<>();

        for (String ref : refs) {
            if (LineageRefs.isExternal(ref)) {
                result.put(ref, NodeMeta.external());
                continue;
            }
            Optional<LineageRefs.Parsed> parsed = LineageRefs.parse(ref);
            if (parsed.isEmpty()) {
                result.put(ref, NodeMeta.external());
                continue;
            }
            CatalogEntity catalog = catalogs.get(parsed.get().catalog());
            if (catalog == null) {
                result.put(ref, new NodeMeta("TABLE", null, true));
                continue;
            }
            String database = String.join(".", parsed.get().namespace());
            Map<String, Boolean> tables = tablesByDatabase.computeIfAbsent(
                    catalog.getId() + '\u0000' + database, key -> loadTableNames(catalog.getId(), database));
            boolean exists = tables.containsKey(parsed.get().table());
            result.put(ref, new NodeMeta("TABLE", catalog.getCatalogFormat(), !exists));
        }
        return result;
    }

    private Map<String, Boolean> loadTableNames(String catalogId, String database) {
        Optional<DatabaseEntity> found = databaseRepository.findByCatalogIdAndName(catalogId, database);
        if (found.isEmpty()) {
            return Map.of();
        }
        Map<String, Boolean> names = new HashMap<>();
        for (TableEntity table : tableRepository.findAllByDatabaseIdOrderByNameAsc(found.get().getId())) {
            names.put(table.getName(), Boolean.TRUE);
        }
        return names;
    }

    private static long nullToZero(Long value) {
        return value == null ? 0L : value;
    }

    // ================================================================== 列级

    /**
     * 按目标列分组的列映射（§8.2）。
     *
     * <p>分组的键是<b>目标列</b>而不是源列：人看血缘时想的是「我这个字段是从哪来的」。
     * 一个目标列有多个上游（{@code JOIN} 之后的常见形态）时 {@code upstream} 是数组。
     *
     * <p>常量列（没有源）也会出现，{@code upstream} 为空、{@code kind=CONSTANT}——
     * 这与「查不到任何映射」是两件事，前端的折叠行靠它区分。
     */
    @Transactional(readOnly = true)
    public ColumnGraph columns(String ref, String direction, String engine) {
        String target = requireRoot(ref);
        Direction dir = Direction.parse(direction);
        String targetHash = Digests.sha256Hex(target);

        Map<String, List<LineageColumnEdgeEntity>> byColumn = new LinkedHashMap<>();
        if (dir != Direction.DOWNSTREAM) {
            for (LineageColumnEdgeEntity edge : columnEdgeRepository.findByTargetRefHash(targetHash)) {
                byColumn.computeIfAbsent(edge.getTargetColumn(), key -> new ArrayList<>()).add(edge);
            }
        }
        if (dir != Direction.UPSTREAM) {
            for (LineageColumnEdgeEntity edge : columnEdgeRepository.findBySourceRefHash(targetHash)) {
                byColumn.computeIfAbsent(edge.getSourceColumn(), key -> new ArrayList<>()).add(edge);
            }
        }

        List<ColumnGroup> groups = new ArrayList<>(byColumn.size());
        for (Map.Entry<String, List<LineageColumnEdgeEntity>> entry : byColumn.entrySet()) {
            List<ColumnLink> upstream = new ArrayList<>();
            List<ColumnLink> downstream = new ArrayList<>();
            String kind = null;
            String expression = null;
            for (LineageColumnEdgeEntity edge : entry.getValue()) {
                String edgeKind = edge.getTransformKind();
                String edgeExpression = edge.getExpression();
                if (dir != Direction.DOWNSTREAM && !edge.getSourceRef().isEmpty()) {
                    upstream.add(new ColumnLink(edge.getSourceRef(), edge.getSourceColumn(),
                            edgeKind, edgeExpression));
                    if (kind == null) {
                        kind = edgeKind;
                        expression = edgeExpression;
                    }
                } else if (dir == Direction.DOWNSTREAM) {
                    downstream.add(new ColumnLink(edge.getTargetRef(), edge.getTargetColumn(),
                            edgeKind, edgeExpression));
                    kind = edgeKind;
                    expression = edgeExpression;
                } else if (kind == null) {
                    // 常量列：只有占位行，没有源
                    kind = edgeKind;
                    expression = edgeExpression;
                }
            }
            groups.add(new ColumnGroup(entry.getKey(), kind == null ? "UNRESOLVED" : kind,
                    expression, List.copyOf(upstream), List.copyOf(downstream)));
        }
        groups.sort(Comparator.comparing(ColumnGroup::column));
        return new ColumnGraph(target, dir.name().toLowerCase(java.util.Locale.ROOT), groups);
    }

    /**
     * 图视图的批量列边（§8.4）：调用方把**当前展示的节点集合**发回来，
     * 这里只回「两端的表都在集合里」的列边。
     *
     * <p>为什么不做成「按根展开」：控制台的图已经经过过滤、展开与截断，
     * 服务端再按根重算一遍得到的节点集合与它对不上，多出来的边在图上没有落点、
     * 少了的边让卡片缺行。集合由持有最新图的一方（控制台）给，是最不容易错的口径。
     * 上限 {@value #MAX_COLUMN_EDGE_REFS} 个 ref——列级图本来就只为小图准备，
     * URL 也装不下几百个全名。
     *
     * <p>常量列（{@code source_ref} 为空串的行）保留：它们不产生画出来的线，
     * 但让目标卡片的列清单里有一行「被写过、无上游」的列。
     */
    public ColumnEdgeGraph columnEdges(List<String> refs) {
        if (refs == null) {
            throw ApiException.badRequest("refs must not be empty");
        }
        List<String> nodes = refs.stream()
                .map(String::trim)
                .filter(ref -> !ref.isEmpty())
                .distinct()
                .toList();
        if (nodes.isEmpty()) {
            throw ApiException.badRequest("refs must not be empty");
        }
        if (nodes.size() > MAX_COLUMN_EDGE_REFS) {
            throw ApiException.badRequest("refs must not exceed " + MAX_COLUMN_EDGE_REFS + " entries");
        }

        Map<String, Integer> indexByRef = new HashMap<>();
        for (int i = 0; i < nodes.size(); i += 1) {
            indexByRef.put(nodes.get(i), i);
        }
        Set<String> hashes = new HashSet<>();
        for (String ref : nodes) {
            hashes.add(Digests.sha256Hex(ref));
        }

        List<ColumnEdgeRow> edges = new ArrayList<>();
        for (LineageColumnEdgeEntity edge : columnEdgeRepository.findByTargetRefHashIn(hashes)) {
            Integer targetIndex = indexByRef.get(edge.getTargetRef());
            if (targetIndex == null) {
                continue;
            }
            String sourceRef = edge.getSourceRef();
            if (sourceRef.isEmpty()) {
                // 常量列：占一个「被写过」的行，不画线（前端按 s = -1 识别）
                edges.add(new ColumnEdgeRow(-1, "", targetIndex, edge.getTargetColumn(),
                        edge.getTransformKind()));
                continue;
            }
            Integer sourceIndex = indexByRef.get(sourceRef);
            if (sourceIndex == null) {
                // 源不在展示集合里：这条边在图上没有落点
                continue;
            }
            edges.add(new ColumnEdgeRow(sourceIndex, edge.getSourceColumn(),
                    targetIndex, edge.getTargetColumn(), edge.getTransformKind()));
        }
        // 确定性排序（§9.2 同一条）：目标表、目标列、源表、源列
        edges.sort(Comparator.comparingInt(ColumnEdgeRow::t)
                .thenComparing(ColumnEdgeRow::tc)
                .thenComparingInt(ColumnEdgeRow::s)
                .thenComparing(ColumnEdgeRow::sc));
        return new ColumnEdgeGraph(nodes, edges);
    }

    // ================================================================== 运行历史

    private static final int MAX_RUN_PAGE = 200;

    /**
     * 运行历史（§8.3）。按 {@code finished_at} 倒序，游标是 {@code (finishedAt, id)} 的复合值。
     *
     * <p>{@code ref} 用 {@code like '%|ref|%'} 过滤：{@code target_refs} 存成竖线包夹的形式，
     * 因此不会出现 {@code t1} 命中 {@code t10} 的子串误配。
     */
    @Transactional(readOnly = true)
    public RunList runs(String ref, String engine, Long since, Integer limit, String cursor) {
        int size = limit == null || limit <= 0 ? 50 : Math.min(limit, MAX_RUN_PAGE);
        String needle = ref == null || ref.isBlank() ? null : REF_SEPARATOR + ref.trim() + REF_SEPARATOR;
        Cursor parsed = Cursor.parse(cursor);

        Specification<LineageRunEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (engine != null && !engine.isBlank()) {
                predicates.add(cb.equal(root.get("engine"), engine.trim()));
            }
            if (needle != null) {
                predicates.add(cb.like(root.get("targetRefs"), "%" + needle + "%"));
            }
            if (since != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("finishedAt"), since));
            }
            if (parsed != null) {
                predicates.add(cb.or(
                        cb.lessThan(root.get("finishedAt"), parsed.finishedAt()),
                        cb.and(cb.equal(root.get("finishedAt"), parsed.finishedAt()),
                                cb.lessThan(root.get("id"), parsed.id()))));
            }
            query.orderBy(cb.desc(root.get("finishedAt")), cb.desc(root.get("id")));
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        List<LineageRunEntity> page = runRepository.findAll(spec, PageRequest.of(0, size + 1)).getContent();
        String next = null;
        if (page.size() > size) {
            page = page.subList(0, size);
            LineageRunEntity last = page.get(page.size() - 1);
            next = Cursor.of(last.getFinishedAt(), last.getId()).encode();
        }
        List<RunSummary> summaries = new ArrayList<>(page.size());
        for (LineageRunEntity run : page) {
            summaries.add(summary(run));
        }
        return new RunList(summaries, next);
    }

    /** 一条 run 的明细：{@code sqlText} 只在详情里出现（§8.3）。 */
    @Transactional(readOnly = true)
    public RunDetail run(String runId) {
        LineageRunEntity run = runRepository.findByRunId(runId)
                .orElseThrow(() -> ApiException.managementNotExist(
                        io.github.melin.lakehouse.rest.support.ResourceType.LINEAGE_RUN, runId));
        List<TargetReport> targets = new ArrayList<>();
        for (String ref : splitTargetRefs(run.getTargetRefs())) {
            List<ColumnReport> columns = new ArrayList<>();
            for (LineageColumnEdgeEntity edge : columnEdgeRepository.findByTargetRefHash(Digests.sha256Hex(ref))) {
                if (edge.getSourceRef().isEmpty() && edge.getSourceColumn().isEmpty()) {
                    columns.add(new ColumnReport(edge.getTargetColumn(), edge.getTransformKind(),
                            edge.getExpression(), List.of()));
                    continue;
                }
                columns.add(new ColumnReport(edge.getTargetColumn(), edge.getTransformKind(),
                        edge.getExpression(),
                        List.of(new ColumnSource(edge.getSourceRef(), edge.getSourceColumn()))));
            }
            targets.add(new TargetReport(ref, List.copyOf(columns)));
        }
        return new RunDetail(summary(run), run.getSqlText(), run.getSqlHash(),
                List.copyOf(targets), run.getAttributes());
    }

    private RunSummary summary(LineageRunEntity run) {
        return new RunSummary(run.getRunId(), run.getEngine(), run.getEngineVersion(),
                run.getStatementType(), run.getWriteMode(), run.getMode(), run.getPrincipal(),
                nullToZero(run.getStartedAt()), nullToZero(run.getFinishedAt()),
                splitTargetRefs(run.getTargetRefs()), run.isColumnsTruncated());
    }

    private static List<String> splitTargetRefs(String targetRefs) {
        if (targetRefs == null || targetRefs.isBlank()) {
            return List.of();
        }
        List<String> refs = new ArrayList<>();
        for (String part : targetRefs.split("\\" + REF_SEPARATOR)) {
            if (!part.isBlank()) {
                refs.add(part);
            }
        }
        return refs;
    }

    /** {@code (finishedAt, id)} 的复合游标：{@code base64url("ts:id")}。 */
    private record Cursor(long finishedAt, long id) {

        static Cursor parse(String token) {
            if (token == null || token.isBlank()) {
                return null;
            }
            try {
                String decoded = new String(Base64.getUrlDecoder().decode(token),
                        java.nio.charset.StandardCharsets.UTF_8);
                int colon = decoded.indexOf(':');
                if (colon <= 0) {
                    throw ApiException.badRequest("Invalid cursor");
                }
                return new Cursor(Long.parseLong(decoded.substring(0, colon)),
                        Long.parseLong(decoded.substring(colon + 1)));
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("Invalid cursor");
            }
        }

        static Cursor of(long finishedAt, long id) {
            return new Cursor(finishedAt, id);
        }

        String encode() {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    (finishedAt + ":" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    // ================================================================== 清理

    /**
     * 清理过期 run（每日一次）。
     *
     * <p>只清 run，不清边：边是「现在谁依赖谁」的状态（§7.5）。
     *
     * @return 删除的行数
     */
    @Transactional
    public int purgeExpiredRuns(long now) {
        int days = properties.getLineage().getRunRetentionDays();
        long cutoff = now - days * 24L * 3600L * 1000L;
        return runRepository.deleteOlderThan(cutoff);
    }

    /**
     * 删一张表的全部边（{@code table.dropped} / {@code table.unregistered}）。
     *
     * <p>只删边，不删 run：run 是历史事实，「这张表当年被谁写过」仍然有价值（§7.5）。
     */
    @Transactional
    public int dropEdges(String ref) {
        if (ref == null || ref.isBlank()) {
            return 0;
        }
        int columns = columnEdgeRepository.deleteByRef(ref);
        int tables = tableEdgeRepository.deleteByRef(ref);
        log.info("Dropped lineage edges of {}: {} table edges, {} column edges", ref, tables, columns);
        return tables + columns;
    }
}
