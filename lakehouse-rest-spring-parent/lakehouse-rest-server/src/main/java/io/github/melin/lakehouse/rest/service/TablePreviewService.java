package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.config.RequestContext;
import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.domain.entity.DatabaseEntity;
import io.github.melin.lakehouse.rest.domain.entity.TableEntity;
import io.github.melin.lakehouse.rest.domain.repo.CatalogRepository;
import io.github.melin.lakehouse.rest.domain.repo.DatabaseRepository;
import io.github.melin.lakehouse.rest.domain.repo.TableRepository;
import io.github.melin.lakehouse.rest.dto.PreviewDtos;
import io.github.melin.lakehouse.rest.event.CatalogEvent;
import io.github.melin.lakehouse.rest.event.CatalogEventPublisher;
import io.github.melin.lakehouse.rest.event.CatalogEventType;
import io.github.melin.lakehouse.rest.preview.ColumnFilter;
import io.github.melin.lakehouse.rest.preview.PartitionSlice;
import io.github.melin.lakehouse.rest.preview.PreviewChunk;
import io.github.melin.lakehouse.rest.preview.PreviewColumn;
import io.github.melin.lakehouse.rest.preview.PreviewFilters;
import io.github.melin.lakehouse.rest.preview.PreviewLimits;
import io.github.melin.lakehouse.rest.preview.PreviewTable;
import io.github.melin.lakehouse.rest.preview.PreviewTableFactory;
import io.github.melin.lakehouse.rest.preview.PreviewTarget;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.CatalogFormat;
import io.github.melin.lakehouse.rest.support.Paging;
import io.github.melin.lakehouse.rest.support.ResourceType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 数据预览的编排层：校验请求 → 解析分区 → 交给格式读 → 组装代价与回显。
 *
 * <p><b>它不认识任何格式。</b>Paimon 与 Iceberg 的差别被压到两个接口后面
 * （{@link PreviewTableFactory} 与 {@link PreviewTable}），这里只按
 * {@link CatalogFormat} 在实现表里查一次。加一个格式等于加一个实现类。
 *
 * <p><b>三段式的第二、三段在这里合一。</b>第一段（分区裁剪）由 {@link PartitionPlanner}
 * 决定，第二段（裁文件）与第三段（筛行）都发生在 {@code table.read(...)} 里——
 * 因为"裁文件"只有格式自己的扫描器做得到（Paimon 的 {@code DataSplit} 是
 * {@code dataFiles + deleteFiles} 的装配体，自己挑文件会把 delete 文件落下）。
 * 本层因此**从不**按文件路径过滤，只传"要哪几个分区"。
 *
 * <p><b>三个文件数一路分开。</b>{@code filesRead < filesInScope < filesInTable} 是常态，
 * 合并成一个分母会把"分区已经把范围缩小了"这件事说反（{@code 3/12} 读成 {@code 3/128}，
 * 用户以为采样很激进）。所以 {@code Scanned} 三个都带，提示语也按实际情况拼。
 */
@Service
public class TablePreviewService {

    private static final Logger log = LoggerFactory.getLogger(TablePreviewService.class);

    /** 分区枚举端点的条数上限：超过就只回最近写入的这么多，响应里 {@code truncated=true}。 */
    private static final int MAX_PARTITIONS = 20_000;

    /** 分区枚举的默认页大小（与全站表格口径一致，见 {@link Paging}）。 */
    private static final int PARTITION_PAGE_SIZE = 50;

    /** 同一主体同时进行的预览数上限；超了 429。 */
    private static final int MAX_CONCURRENT_PER_PRINCIPAL = 2;

    private final TableRepository tableRepository;

    private final DatabaseRepository databaseRepository;

    private final CatalogRepository catalogRepository;

    private final Map<CatalogFormat, PreviewTableFactory> factories = new LinkedHashMap<>();

    private final CatalogEventPublisher events;

    private final PreviewCursorCodec cursors;

    private final PreviewLimits limits = PreviewLimits.DEFAULTS;

    /** 主体 → 进行中的预览数。只在同一个实例内计数，多实例下相当于放大了配额，够用。 */
    private final Map<String, Integer> inFlight = new ConcurrentHashMap<>();

    public TablePreviewService(TableRepository tableRepository,
                               DatabaseRepository databaseRepository,
                               CatalogRepository catalogRepository,
                               List<PreviewTableFactory> factories,
                               CatalogEventPublisher events,
                               PreviewCursorCodec cursors) {
        this.tableRepository = tableRepository;
        this.databaseRepository = databaseRepository;
        this.catalogRepository = catalogRepository;
        this.events = events;
        this.cursors = cursors;
        for (PreviewTableFactory factory : factories) {
            this.factories.put(factory.format(), factory);
        }
    }

    // ------------------------------------------------------------------ 解析定位

    /**
     * 按 id 定位表、catalog 与库名。
     *
     * <p>与文件列表端点同一条路：{@code tableId} 是表详情响应里的内部 id，不是
     * {@code db.table} 名字对——按名字定位要拆出 catalog 与库两层判断，而调用方
     * （表详情页）手里本来就有 id。
     *
     * <p>**不做判权**：判权在控制器里（那里同时知道调用方的意图），与本工程其余
     * 管理 / 控制台端点的分工一致。
     */
    public PreviewTarget resolve(String tableId) {
        TableEntity table = tableRepository.findById(tableId)
                .orElseThrow(() -> ApiException.managementNotExist(ResourceType.TABLE, tableId));
        CatalogEntity catalog = catalogRepository.findById(table.getCatalogId())
                .orElseThrow(() -> ApiException.managementNotExist(ResourceType.CATALOG,
                        table.getCatalogId()));
        // 库名既进判权的 namespace 那一段，也进响应与审计。库里挂着的 id 找不到实体
        // 说明数据不一致，如实回传 id 比编一个名字诚实（与文件列表端点同一条规则）
        String database = databaseRepository.findById(table.getDatabaseId())
                .map(DatabaseEntity::getName)
                .orElse(Objects.requireNonNullElse(table.getDatabaseId(), ""));
        return new PreviewTarget(table, catalog, database);
    }

    /**
     * 按 {@code prefix / 库名 / 表名} 定位同一张表（给"手里只有名字"的页面用）。
     *
     * <p>只有 Iceberg 表详情页需要这条路：它按名字加载表（{@code LoadTableResult}
     * 里没有本服务端的内部 id），而预览的两个端点按 id 寻址。与其把预览改成两套寻址
     * 方式（两套就有两套判权路径与两套测试），不如给它一条窄路去换 id。
     *
     * <p>库名口径与存储优化那套管理端点一致（{@code TableOptimizationService}）：
     * 库里存的是一条可能带点号的记录，控制台传的就是它，**不在这里拆层级**。
     */
    public PreviewTarget resolve(String prefix, String database, String table) {
        CatalogEntity catalog = catalogRepository.findByPrefix(prefix)
                .orElseThrow(() -> ApiException.managementNotExist(ResourceType.CATALOG, prefix));
        DatabaseEntity found = databaseRepository.findByCatalogIdAndName(catalog.getId(), database)
                .orElseThrow(() -> ApiException.managementNotExist(ResourceType.DATABASE, database));
        TableEntity entity = tableRepository
                .findByCatalogIdAndDatabaseIdAndName(catalog.getId(), found.getId(), table)
                .orElseThrow(() -> ApiException.managementNotExist(ResourceType.TABLE, table));
        return new PreviewTarget(entity, catalog, found.getName());
    }

    // ------------------------------------------------------------------ 预览

    /**
     * 读一页数据。
     *
     * <p>调用方必须已经通过 TABLE 级 {@code SELECT} 的判定（见
     * {@code TablePreviewController}）：能看元数据不等于能看数据。
     *
     * @param target  已定位的表
     * @param request 请求；{@code null} 等同于"全默认"（最新快照、自动分区、前 N 列、100 行）
     */
    public PreviewDtos.PreviewResponse preview(PreviewTarget target, PreviewDtos.PreviewRequest request) {
        PreviewDtos.PreviewRequest req = request == null
                ? new PreviewDtos.PreviewRequest(null, null, null, null, null, null)
                : request;
        // 游标优先于请求里的快照：第二页必须读第一页那一个快照，否则会在一份新的
        // 清单上跳过旧的行数（见 PreviewCursorCodec 的类注释）
        PreviewCursorCodec.Cursor cursor = cursors.decode(req.cursor());
        Long snapshotId = cursor == null ? req.snapshotId() : cursor.snapshotId();

        String key = requireSlot();
        try (PreviewTable table = open(target, snapshotId)) {
            return readSlice(target, table, req, cursor);
        } finally {
            releaseSlot(key);
        }
    }

    private PreviewDtos.PreviewResponse readSlice(PreviewTarget target, PreviewTable table,
                                                  PreviewDtos.PreviewRequest req,
                                                  PreviewCursorCodec.Cursor cursor) {
        // 期间表又提交过：游标里的行号对应的清单已经不是最新的那份。
        // 用户显式指定了快照（时间旅行）时不算漂移——那本来就是"看历史"
        if (cursor != null && req.snapshotId() == null
                && !Objects.equals(table.latestSnapshotId(), table.snapshotId())) {
            throw new ApiException(409, ResourceType.TABLE, target.table().getName(),
                    "The table committed a new snapshot while this preview was paging (reading"
                            + " snapshot " + table.snapshotId() + ", latest is "
                            + table.latestSnapshotId() + "); load the first page again");
        }

        List<PreviewColumn> all = table.columns();
        Map<String, PreviewColumn> byName = new LinkedHashMap<>();
        for (PreviewColumn column : all) {
            byName.put(column.name(), column);
        }
        Projection projection = projection(req.columns(), req.filters(), all, byName);
        List<PreviewFilters.Compiled> compiled =
                PreviewFilters.compile(req.filters(), projection.readNames(), byName, limits.maxFilters());

        List<String> fields = table.partitionFields();
        List<PartitionSlice> slices = fields.isEmpty() ? List.of() : table.partitions();
        PartitionPlanner.Decision decision =
                partition(target, table, fields, slices, all, req, cursor);

        String fingerprint =
                PreviewCursorCodec.fingerprint(req.filters(), projection.readNames(), decision.resolved());
        if (cursor != null && !fingerprint.equals(cursor.fingerprint())) {
            // 条件变了但游标还是旧的：继续读会在一份"按别的条件裁好的"位置上下跳
            throw new ApiException(409, ResourceType.TABLE, target.table().getName(),
                    "The cursor was issued for different filters, columns or partition; load the"
                            + " first page again");
        }

        int limit = limit(req.limit());
        int splitOffset = cursor == null ? 0 : cursor.splitOffset();
        long skipRows = cursor == null ? 0 : cursor.splitRows();
        long startedAt = System.nanoTime();
        PreviewChunk chunk = table.read(new PreviewTable.ReadSpec(
                projection.indices(),
                projection.output().size(),
                compiled,
                decision.selection(),
                splitOffset,
                skipRows,
                limit,
                limits.maxFiles(),
                limits.maxBytes(),
                startedAt + limits.timeoutMs() * 1_000_000L));
        long elapsedMs = Math.max(0, (System.nanoTime() - startedAt) / 1_000_000L);

        int filesInTable = table.totalFiles();
        int filesInScope = table.countFiles(decision.selection());
        // 有进展才给"加载更多"：否则按钮会一直转，每点一次都返回 0 行
        boolean advanced = chunk.exhausted() || chunk.filesRead() > 0 || chunk.rowsRead() > 0
                || chunk.nextSplitOffset() > splitOffset;
        boolean hasMore = !chunk.exhausted() && advanced;
        boolean sampled = chunk.filesRead() < filesInScope;

        String next = hasMore
                ? cursors.encode(new PreviewCursorCodec.Cursor(
                        table.snapshotId(),
                        chunk.nextSplitOffset(),
                        chunk.nextSplitRows(),
                        fingerprint,
                        decision.resolved(),
                        decision.mode(),
                        decision.auto(),
                        decision.depth(),
                        decision.label()))
                : null;

        List<String> warnings = new ArrayList<>(decision.warnings());
        if (projection.defaulted()) {
            warnings.add("默认只返回前 " + projection.output().size() + " 列（共 " + all.size()
                    + " 列），可在列投影里勾更多");
        }
        if (sampled) {
            // 两个分母都要在：只说 3/12 会让人以为全表就 12 个文件
            warnings.add("只读了最近 " + chunk.filesRead() + "/" + filesInScope + " 个文件"
                    + (filesInScope < filesInTable ? "（本分区）" : "")
                    + "，全表 " + filesInTable + " 个");
        }
        if (!chunk.exhausted() && !advanced) {
            warnings.add("这一页没有取到新的数据文件（预算已用完），换一个分区或加一个过滤条件再试");
        }

        // 审计只记第一页：一次预览 = 一行，把"翻了几页"变成 N 行会让事件表变成访问日志。
        // 条件只记列名与算子，值不落库——值可能就是手机号（设计文档 §10）
        if (cursor == null) {
            publish(target, table, decision, projection, chunk, req.filters(), filesInScope,
                    filesInTable, elapsedMs, sampled);
        }

        return new PreviewDtos.PreviewResponse(
                table.snapshotId(),
                new PreviewDtos.PartitionSelection(decision.mode(), decision.resolved(),
                        decision.label(), decision.auto(), decision.depth(), decision.fallback(),
                        decision.partitionsTotal(), decision.partitionsMatched()),
                projection.output(),
                chunk.rows(),
                next,
                hasMore,
                new PreviewDtos.Scanned(chunk.filesRead(), filesInScope, filesInTable,
                        chunk.bytesRead(), chunk.rowsRead(), elapsedMs),
                sampled,
                List.copyOf(warnings));
    }

    /**
     * 这次读哪些分区。
     *
     * <p>续读（{@code cursor != null}）时**不重新规划**：把游标里固化的取值还原成切片，
     * 回显也沿用游标里的 {@code mode/auto/depth/label}——第二页与第一页对"我在看哪个
     * 分区"必须给同一个答案，否则前端会以为分区被自己变了。
     */
    private PartitionPlanner.Decision partition(PreviewTarget target, PreviewTable table,
                                                List<String> fields, List<PartitionSlice> slices,
                                                List<PreviewColumn> all,
                                                PreviewDtos.PreviewRequest req,
                                                PreviewCursorCodec.Cursor cursor) {
        if (cursor == null) {
            // transforms 由格式给：Paimon 返回空表（全是 identity），Iceberg 的 bucket /
            // truncate 必须在这里露出来，否则桶号会被当成可排序的数字
            return PartitionPlanner.decide(fields, all, table.partitionTransforms(), slices,
                    req.partition(), req.filters(), limits.maxFiles(), limits.maxCascadeDepth());
        }
        List<PartitionSlice> selection = PartitionPlanner.resolve(slices, cursor.resolved());
        if (!cursor.resolved().isEmpty() && selection.isEmpty()) {
            throw new ApiException(409, ResourceType.TABLE, target.table().getName(),
                    "The partition this cursor points at no longer exists (it had "                            + cursor.resolved().stream()
                                    .map(value -> value.field() + "=" + value.value()).toList()
                            + ", now " + slices.size() + " partitions); load the first page again");
        }
        return new PartitionPlanner.Decision(cursor.mode(), selection, cursor.resolved(),
                cursor.label(), cursor.auto(), cursor.depth(), null, slices.size(),
                selection.size(), List.of());
    }

    // ------------------------------------------------------------------ 分区列表

    /**
     * 分区枚举——分区选择器的数据源。
     *
     * <p>只读清单，不读数据文件：Paimon 走 {@code listPartitionEntries()}（自带文件数、
     * 总大小、最近写入时间），Iceberg 聚合 {@code planFiles()}。因此它和预览一样需要
     * TABLE 级 {@code SELECT}：分区值与文件数本身就是数据分布信息。
     *
     * <p>排序是"最近写入的在前"，空分区（0 个数据文件）附在最后按分区值倒序——
     * 它们仍然可选，点了会落到"这个分区没有数据"的空态，比从列表里消失让人去猜要好。
     */
    public PreviewDtos.PartitionPage partitions(PreviewTarget target, Integer page, Integer pageSize,
                                                String keyword, Long snapshotId) {
        try (PreviewTable table = open(target, snapshotId)) {
            List<PartitionSlice> slices = table.partitions();
            String prefix = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
            List<PartitionSlice> filtered = new ArrayList<>(slices.size());
            for (PartitionSlice slice : slices) {
                if (prefix.isEmpty() || matches(slice, prefix)) {
                    filtered.add(slice);
                }
            }
            filtered.sort(RECENT_FIRST);
            boolean truncated = filtered.size() > MAX_PARTITIONS;
            List<PartitionSlice> capped = truncated ? filtered.subList(0, MAX_PARTITIONS) : filtered;

            List<PreviewDtos.PartitionRow> rows = new ArrayList<>(capped.size());
            for (PartitionSlice slice : capped) {
                rows.add(new PreviewDtos.PartitionRow(slice.values(), slice.files(), slice.bytes(),
                        slice.lastWriteTime() > 0 ? slice.lastWriteTime() : null, slice.records()));
            }
            Paging.Paged<PreviewDtos.PartitionRow> paged = Paging.paged(rows, page, pageSize);
            if (paged != null) {
                return new PreviewDtos.PartitionPage(target.catalog().getPrefix(),
                        target.database(), target.table().getName(), table.snapshotId(),
                        paged.items(), truncated, paged.meta());
            }
            // 不分页的老路径：一次给全量（上限仍是 20000 条），page 为 null
            return new PreviewDtos.PartitionPage(target.catalog().getPrefix(), target.database(),
                    target.table().getName(), table.snapshotId(), List.copyOf(rows), truncated, null);
        }
    }

    private static final Comparator<PartitionSlice> RECENT_FIRST = (left, right) -> {
        boolean leftEmpty = left.files() == 0;
        boolean rightEmpty = right.files() == 0;
        if (leftEmpty != rightEmpty) {
            return leftEmpty ? 1 : -1;
        }
        if (!leftEmpty) {
            int byTime = Long.compare(right.lastWriteTime(), left.lastWriteTime());
            if (byTime != 0) {
                return byTime;
            }
        }
        return right.key().compareTo(left.key());
    };

    private static boolean matches(PartitionSlice slice, String prefix) {
        for (Object value : slice.values().values()) {
            if (value != null && String.valueOf(value).toLowerCase(Locale.ROOT).startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ 打开表

    private PreviewTable open(PreviewTarget target, Long snapshotId) {
        CatalogFormat format = CatalogFormat.of(target.catalog().getCatalogFormat());
        PreviewTableFactory factory = factories.get(format);
        if (factory == null) {
            // 装配层少装了一个协议模块。对调用方是"这个格式没实现预览"，对我们
            // 是部署错误——两者都用 400 表达，消息里写清是哪种
            throw ApiException.badRequest("catalog " + target.catalog().getPrefix() + " is "
                    + format + " but no preview implementation is registered for that format;"
                    + " add the matching protocol module to this deployment");
        }
        return factory.open(target, snapshotId);
    }

    // ------------------------------------------------------------------ 投影与限额

    /**
     * 本次要读的列。
     *
     * @param readNames 读的列（**含**只为过滤而补读的列，顺序即读取顺序）
     * @param output    回给调用方的列（{@code readNames} 的前 N 个，N = {@code output.size()}）
     * @param defaulted 是否因为没勾选列而走了"前 N 列"的默认值
     */
    private record Projection(int[] indices, List<String> readNames, List<PreviewColumn> output,
                              boolean defaulted) {
    }

    private Projection projection(List<String> requested, List<ColumnFilter> filters,
                                  List<PreviewColumn> all, Map<String, PreviewColumn> byName) {
        List<PreviewColumn> output = new ArrayList<>();
        boolean defaulted = false;
        if (requested == null || requested.isEmpty()) {
            for (PreviewColumn column : all) {
                if (output.size() >= limits.maxColumns()) {
                    defaulted = true;
                    break;
                }
                output.add(column);
            }
        } else {
            // 去重但保序：重复勾选同一列不该让"第几列"错位
            LinkedHashSet<String> names = new LinkedHashSet<>();
            List<String> unknown = new ArrayList<>();
            for (String name : requested) {
                if (name == null) {
                    continue;
                }
                if (!byName.containsKey(name)) {
                    unknown.add(name);
                }
                names.add(name);
            }
            if (!unknown.isEmpty()) {
                throw ApiException.badRequest("Unknown columns " + unknown + "; this table has "
                        + byName.keySet());
            }
            for (String name : names) {
                output.add(byName.get(name));
            }
        }

        List<String> readNames = new ArrayList<>(output.size() + 2);
        for (PreviewColumn column : output) {
            readNames.add(column.name());
        }
        // 过滤条件可以引用没勾选的列：读出来、判完、再按 output.size() 切掉
        if (filters != null) {
            for (ColumnFilter filter : filters) {
                if (filter != null && filter.column() != null
                        && byName.containsKey(filter.column())
                        && !readNames.contains(filter.column())) {
                    readNames.add(filter.column());
                }
            }
        }
        int[] indices = new int[readNames.size()];
        for (int index = 0; index < readNames.size(); index++) {
            indices[index] = indexOf(all, readNames.get(index));
        }
        return new Projection(indices, List.copyOf(readNames), List.copyOf(output), defaulted);
    }

    private static int indexOf(List<PreviewColumn> columns, String name) {
        for (int index = 0; index < columns.size(); index++) {
            if (columns.get(index).name().equals(name)) {
                return index;
            }
        }
        throw ApiException.badRequest("Unknown column '" + name + "'");
    }

    private int limit(Integer requested) {
        if (requested == null) {
            return limits.defaultLimit();
        }
        if (requested < 1 || requested > limits.maxLimit()) {
            // 不夹取而是报错：夹到 200 会让调用方以为"要 500 拿到 200 是正常的"，
            // 而 skip 的代价与页大小同阶，这个上限不是姿态而是物理约束
            throw ApiException.badRequest("limit must be between 1 and " + limits.maxLimit()
                    + ", got " + requested);
        }
        return requested;
    }

    // ------------------------------------------------------------------ 并发与审计

    private String requireSlot() {
        String principal = Objects.requireNonNullElse(RequestContext.principal(), "");
        Integer now = inFlight.merge(principal, 1, Integer::sum);
        if (now > MAX_CONCURRENT_PER_PRINCIPAL) {
            releaseSlot(principal);
            throw new ApiException(429, null, null,
                    "You already have " + MAX_CONCURRENT_PER_PRINCIPAL + " previews running;"
                            + " wait for one to finish");
        }
        return principal;
    }

    private void releaseSlot(String principal) {
        // computeIfPresent 而不是 get + put：并发下"减到 0 就删"与"另一个请求刚加 1"
        // 之间不能有空隙，否则计数会凭空少一，配额就永远回不到 0
        inFlight.computeIfPresent(principal, (key, value) -> value <= 1 ? null : value - 1);
    }

    private void publish(PreviewTarget target, PreviewTable table, PartitionPlanner.Decision decision,
                         Projection projection, PreviewChunk chunk, List<ColumnFilter> filters,
                         int filesInScope, int filesInTable, long elapsedMs, boolean sampled) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("database", target.database());
        attributes.put("table", target.table().getName());
        attributes.put("format", table.formatLabel());
        if (table.snapshotId() != null) {
            attributes.put("snapshotId", table.snapshotId());
        }
        attributes.put("columns", projection.output().size());
        attributes.put("rows", chunk.rows().size());
        attributes.put("rowsRead", chunk.rowsRead());
        attributes.put("filesRead", chunk.filesRead());
        attributes.put("filesInScope", filesInScope);
        attributes.put("filesInTable", filesInTable);
        attributes.put("elapsedMs", elapsedMs);
        attributes.put("sampled", sampled);
        attributes.put("partitionMode", decision.mode());
        // 只记字段名与命中数，**不记取值**：分区值可能是 region、也可能按手机号前缀分区
        attributes.put("partitionFields", decision.resolved().stream()
                .map(PreviewDtos.PartitionValue::field).toList());
        attributes.put("partitionsMatched", decision.partitionsMatched());
        // 条件同理：列名 + 算子足够回答"谁看了哪些范围"，值不落库
        attributes.put("filters", filterNamesOf(filters));
        try {
            events.publish(CatalogEventType.TABLE_PREVIEWED, target.catalog().getPrefix(),
                    CatalogEvent.TARGET_TABLE,
                    target.database() + "." + target.table().getName(), attributes);
        } catch (RuntimeException exception) {
            // 审计失败不该让"看一眼数据"失败：发布器已经逐个监听器兜底，
            // 这里再兜一层是防它自己在拼事件时炸掉
            log.warn("cannot publish TABLE_PREVIEWED for {}: {}", target.table().getName(),
                    exception.getMessage());
        }
    }

    /** 条件摘要成 {@code 列名:算子}——值刻意不在这里出现。 */
    private static List<String> filterNamesOf(List<ColumnFilter> filters) {
        if (filters == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>(filters.size());
        for (ColumnFilter filter : filters) {
            if (filter != null && filter.column() != null) {
                names.add(filter.column() + ":" + filter.op());
            }
        }
        return List.copyOf(names);
    }
}
