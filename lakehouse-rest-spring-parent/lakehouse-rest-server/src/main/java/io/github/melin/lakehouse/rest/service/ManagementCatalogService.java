package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.support.LineageRefs;
import io.github.melin.lakehouse.rest.config.RequestContext;
import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.domain.entity.PermissionAssignmentEntity;
import io.github.melin.lakehouse.rest.domain.repo.CatalogRepository;
import io.github.melin.lakehouse.rest.domain.repo.PermissionAssignmentRepository;
import io.github.melin.lakehouse.rest.domain.repo.TableSnapshotRepository;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.Catalog;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.CachedCredentialRevocation;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.Catalogs;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.CreateCatalogRequest;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.DatabaseStat;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.Histogram;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.TableStat;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.TableStats;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.TableStatsSummary;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.Totals;
import io.github.melin.lakehouse.rest.dto.ExecutionClusterDtos.Summary;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.UpdateCatalogRequest;
import io.github.melin.lakehouse.rest.dto.ManagementEnums.CatalogType;
import io.github.melin.lakehouse.rest.dto.ManagementEnums.StorageType;
import io.github.melin.lakehouse.rest.dto.StorageDtos.StorageConfigInfo;
import io.github.melin.lakehouse.rest.event.CatalogEvent;
import io.github.melin.lakehouse.rest.event.CatalogEventPublisher;
import io.github.melin.lakehouse.rest.event.CatalogEventType;
import io.github.melin.lakehouse.rest.paimon.service.DatabaseService;
import io.github.melin.lakehouse.rest.service.exec.ExecutionClusterService;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.CatalogFormat;
import io.github.melin.lakehouse.rest.support.CredentialCipher;
import io.github.melin.lakehouse.rest.support.PageMeta;
import io.github.melin.lakehouse.rest.support.Paging;
import io.github.melin.lakehouse.rest.support.ResourceType;
import io.github.melin.lakehouse.rest.support.StorageConfigs;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.JpaSort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理 API 的 catalog 读写。
 *
 * <p>catalog 在管理面与 catalog 面是同一个对象，只是暴露的名字不同：
 * 管理规格的 {@code Catalog.name} 就是 catalog API 路径里的 {@code {prefix}}。
 * 因此这里直接操作 {@link CatalogEntity}，不另建实体。
 *
 * <p><b>存储配置的落库方式。</b>{@code storageConfigInfo} 是判别联合
 * （{@code S3} / {@code AZURE} / {@code GCS} / {@code FILE}，字段集合各不相同），
 * 因此整份配置以 JSON 存进 {@link CatalogEntity#getStorageConfig()}，
 * 并把 {@code storageType} 与 {@code allowedLocations} 再写一份到投影列，
 * 便于直接用 SQL 按存储类型和位置筛选。
 *
 * <p>仓库位置取自 {@code allowedLocations} 的首项（{@link StorageConfigs#warehouseOf}）：
 * 表路径模板要用它拼路径，所以即使调用方没给位置，也会补上配置里的默认仓库。
 *
 * <p><b>静态凭据由本服务收口。</b>写路径在落库前把明文密钥密封（{@link CredentialCipher}），
 * 读路径在返回前抹掉密文（{@link StorageConfigs#withoutSecrets}）——
 * 一写一读都经过这里，是因为 catalog 的读写只在这一个服务上；
 * 两个方向各留一处，脱敏就不会漏在别的装配点上。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ManagementCatalogService {

    private final CatalogRepository catalogRepository;
    private final PermissionAssignmentRepository permissionAssignmentRepository;
    private final TableSnapshotRepository snapshotRepository;
    private final DatabaseService databaseService;
    private final RestServerProperties properties;
    private final StorageRuntimePolicy storagePolicy;
    private final CredentialCipher cipher;
    private final CatalogEventPublisher events;
    private final ExecutionClusterService executionClusters;

    /**
     * 「凭据作废」入口要清的就是这个缓存。
     *
     * <p>它在这里而不是在一个新服务里：作废的作用对象是 catalog，判定「这是哪个 catalog」
     * 也要先 {@code require(name)}，两件事都在本类手里，多一个类只会多一层转发。
     */
    private final StorageCredentialCache credentialCache;

    /**
     * {@code GET /catalogs}：全量、按 catalog 名升序。
     *
     * <p>排序是**分页契约的一部分**而不是顺手加的：控制台的表格全部走远程分页
     * （页由控制器切），分页要求顺序稳定，否则同一个 {@code page} 两次请求
     * 拿到的东西可能不一样，翻页会漏也会重。因此全量与分页两条路径共用这一份顺序。
     *
     * <p>切页放在控制器而不是这里：catalog 列表要先按调用者的可见范围过滤，
     * 「先过滤再切页」才能让 {@code total} 等于这个人真的能看到的条数。
     */
    @Transactional(readOnly = true)
    public Catalogs list() {
        List<CatalogEntity> entities = catalogRepository.findAll(Sort.by(Sort.Direction.ASC, "prefix"));
        // 一次取一页的执行集群摘要（一条 IN 查询），不是每行一个查询：
        // 列表是控制台打开得最频繁的页面，N+1 在这里等于「catalog 越多越慢」
        Map<String, Summary> clusters = executionClusters.summaries(
                entities.stream().map(CatalogEntity::getId).toList());
        List<Catalog> result = new ArrayList<>();
        for (CatalogEntity entity : entities) {
            result.add(toDto(entity, clusters.getOrDefault(entity.getId(), Summary.NOT_CONFIGURED)));
        }
        return new Catalogs(result, null);
    }

    /** {@code POST /catalogs}。 */
    @Transactional
    public Catalog create(CreateCatalogRequest request) {
        if (request == null || request.catalog() == null) {
            throw ApiException.badRequest("catalog is required");
        }
        Catalog inbound = request.catalog();
        String name = inbound.name();
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("catalog.name is required");
        }
        // `~` 是血缘里外部节点的保留前缀（docs/lineage-design.md §4.1）：catalog 名以它开头，
        // 这个 catalog 下的所有表在血缘图里都会变成「点不开」的节点
        LineageRefs.requireNotReserved(name, "catalog");
        if (catalogRepository.findByPrefix(name).isPresent()) {
            throw ApiException.managementAlreadyExist(ResourceType.CATALOG, name);
        }

        String type = inbound.type() == null ? CatalogType.INTERNAL.wireName()
                : CatalogType.parse(inbound.type())
                .orElseThrow(() -> ApiException.badRequest("Unsupported catalog.type: " + inbound.type()))
                .wireName();
        CatalogFormat format = parseFormat(inbound.catalogFormat());

        CatalogEntity entity = new CatalogEntity();
        entity.setId(Paging.newId());
        entity.setPrefix(name);
        entity.setCatalogType(type);
        entity.setCatalogFormat(format.wireName());
        applyStorage(entity, inbound.storageConfigInfo(), null);
        entity.setProperties(inbound.properties() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(inbound.properties()));
        entity.setDefaults(new LinkedHashMap<>());
        entity.setOverrides(new LinkedHashMap<>());
        entity.markCreated(RequestContext.principal(), RequestContext.now());
        CatalogEntity saved = catalogRepository.save(entity);

        provisionBootstrapGrants(saved);
        events.publish(CatalogEventType.CATALOG_CREATED, name, CatalogEvent.TARGET_CATALOG, name,
                Map.of("format", saved.getCatalogFormat(), "type", saved.getCatalogType()));
        return toDto(saved);
    }

    /** {@code GET /catalogs/{catalogName}}。 */
    @Transactional(readOnly = true)
    public Catalog get(String name) {
        return toDto(require(name));
    }

    /** 小文件阈值：100 MB。与控制台的口径同一个数（前端 `utils/storageHealth.js`）。 */
    static final long SMALL_FILE_THRESHOLD = 100L * 1024 * 1024;

    /**
     * 逐表明细的排序白名单：前端排序键 → JPQL 表达式。
     *
     * <p>表达式只出自这张白名单，前端传什么都不能拼进 SQL 之外的东西；
     * 不在白名单里的键回落到 {@code size}（见 {@code tableRows}）。
     *
     * <p>「平均文件大小」是派生列（没有实体字段），只能用 {@code JpaSort#unsafe} 塞原始表达式，
     * 而这条路径有两条不能违反的约束，违反时症状都是**整列 500**（Hibernate 语法错误），
     * 且只有在真发一次请求时才暴露：
     *
     * <ol>
     *   <li><b>表达式里不能有空格。</b>Spring Data 把排序属性拼进 {@code order by} 时会先看
     *       首段是不是已声明的别名，不是就在前面补一个根别名——带空格的
     *       {@code case when … end} 于是变成 {@code order by t.case when …}，直接语法错。
     *       所以这里写的是 {@code nullif(…)} 而不是 {@code case when}。</li>
     *   <li><b>除法要自己挡掉 0 与 null。</b>{@code nullif(s.fileCount,0)}：没统计的行本来就是
     *       null（排到一端），而 fileCount=0 的行不该让 MySQL 报警告、让 H2 抛除零错。
     *       乘 {@code *1.0} 是为了让除法落在浮点上——整数除法会把 333.3 与 333.7 排成并列。</li>
     * </ol>
     */
    private static final Map<String, String> SORT_EXPRESSIONS = Map.of(
            "files", "s.fileCount",
            "size", "s.fileSizeInBytes",
            "records", "s.recordCount",
            "updatedAt", "s.timeMillis",
            "table", "t.name",
            "avgSize", "s.fileSizeInBytes*1.0/nullif(s.fileCount,0)");

    /**
     * {@code GET /catalogs/{catalogName}/table-stats}：整个 catalog 的存储汇总，SQL 里算完。
     *
     * <p>为控制台的「存储」页而生。此前那一页是「一张表一个请求」（快照 / loadTable），
     * 表多时是一条请求队列；而统计需要的每个数本来就以投影行的形式躺在
     * {@code lakehouse_snapshot} 里（Paimon 与 Iceberg 共表，写入点分别是
     * 提交链路与 {@code IcebergProjections}），所以这里的成本就是几次对同一份
     * 投影 join 的**聚合**——KPI、按库、Top 10、直方图四块一次算完，
     * 响应大小与表数无关，浏览器不再做任何逐行计算。
     *
     * <p>**left join**：还没有快照的表也有一行，统计列为 null——「无快照」
     * 是正常状态，不能因为它把表从统计里抹掉。{@code onlyPaimon} 过滤掉
     * Format / Object / Lance 表（它们没有快照，混进来只会变成一列「无快照」）。
     *
     * <p>这是一次聚合查询而不是预计算任务：投影行本身就是写路径维护好的中间产物，
     * 单表聚合走索引是毫秒级；真到了成为热点的规模，再在这份投影上加物化即可，
     * 契约不需要变。
     *
     * <p>{@code database} 的过滤口径（控制台「按库过滤」随请求下发）：
     * <b>KPI / Top 10 / 直方图跟着库走，按库聚合不跟</b>——后者是「全 catalog 的分布」
     * 与下拉候选的来源，跟着塌缩成一个库，用户就换不回去了。
     */
    @Transactional(readOnly = true)
    public TableStatsSummary tableStatsSummary(String catalogName, boolean onlyPaimon, String database) {
        CatalogEntity catalog = require(catalogName);
        String db = database == null || database.isBlank() ? null : database;
        // 单行聚合：Spring Data 会把「一行本身是 Object[]」的结果再包一层，取首行。
        Object[] sums = snapshotRepository.summarizeCatalogTables(
                catalog.getId(), db, onlyPaimon, SMALL_FILE_THRESHOLD).get(0);
        long tables = num(sums[0]);
        long empty = num(sums[1]);
        long unknown = num(sums[2]);
        long files = num(sums[3]);
        long size = num(sums[4]);
        long filesWithSize = num(sums[7]);
        long smallTables = num(sums[8]);
        long smallFiles = num(sums[9]);
        long smallSize = num(sums[10]);

        List<DatabaseStat> databases = new ArrayList<>();
        for (Object[] row : snapshotRepository.groupCatalogTablesByDatabase(
                catalog.getId(), onlyPaimon, SMALL_FILE_THRESHOLD)) {
            databases.add(new DatabaseStat((String) row[0], num(row[1]), num(row[2]),
                    num(row[3]), num(row[4]), num(row[5])));
        }

        // Top 10：复用逐表明细查询（存储降序取前若干行，Java 里滤掉空表后截 10），
        // 不用再写一条 SQL。多取一些是因为空表（统计列 null）也占行，按旧口径它们不进 Top 10。
        PageRequest top = PageRequest.of(0, 100,
                JpaSort.unsafe(Sort.Direction.DESC, SORT_EXPRESSIONS.get("size")));
        List<TableStat> topTables = snapshotRepository
                .findCatalogTableRows(catalog.getId(), db, onlyPaimon, null, top).stream()
                .map(ManagementCatalogService::toTableStat)
                .filter(stat -> stat.fileSizeInBytes() != null && stat.fileSizeInBytes() > 0)
                .limit(10)
                .toList();

        Object[] buckets = snapshotRepository.bucketCatalogTables(catalog.getId(), db, onlyPaimon).get(0);
        List<Long> counts = new ArrayList<>(5);
        long counted = 0;
        for (int i = 0; i < 5; i++) {
            long value = num(buckets[i]);
            counts.add(value);
            counted += value;
        }

        Totals totals = new Totals(tables, empty, unknown, files, size,
                num(sums[5]), num(sums[6]), filesWithSize,
                filesWithSize > 0 ? (double) size / filesWithSize : null,
                smallTables, smallFiles, smallSize,
                smallTables > 0 || tables - empty - unknown > 0
                        ? (double) smallTables / Math.max(1, tables - empty - unknown) : 0.0);
        return new TableStatsSummary(totals, databases, topTables,
                new Histogram(counts, counted, num(buckets[5])));
    }

    /**
     * {@code GET /catalogs/{catalogName}/table-stats/tables}：逐表明细的**一页**（远程分页）。
     *
     * <p>分页 / 过滤 / 排序全部下推到 SQL（limit/offset 在数据库里截断），
     * 打开存储页不再把全表行搬到浏览器——浏览器只在选中库之后按页取数。
     * 分页默认值刻意大于通用端点（{@code RestServerProperties} 的 100/1000）：
     * 一行就是几个数字，一页 2000 行约 200 KB，控制台一屏 20 行可以少翻几次页。
     */
    @Transactional(readOnly = true)
    public TableStats tableRows(String catalogName, String database, String keyword,
                                boolean onlyPaimon, String sort, String order,
                                Integer maxResults, String pageToken,
                                Integer page, Integer pageSize) {
        CatalogEntity catalog = require(catalogName);
        String db = database == null || database.isBlank() ? null : database;
        String kw = keyword == null || keyword.isBlank() ? null : keyword.trim();
        String key = sort != null && SORT_EXPRESSIONS.containsKey(sort) ? sort : "size";
        Sort.Direction dir = "asc".equalsIgnoreCase(order)
                ? Sort.Direction.ASC : Sort.Direction.DESC;
        long total = snapshotRepository.countCatalogTableRows(catalog.getId(), db, onlyPaimon, kw);

        // 两种翻法互斥，判据是「有没有页码」：控制台的表格给 page/pageSize（要渲染
        // 「共 N 条」必须有 total），脚本与引擎侧给 maxResults/pageToken（一路向前）。
        // 两种都在 SQL 里截断，不存在「先查全量再切页」。
        if (page != null || pageSize != null) {
            int size = Paging.pageSize(pageSize, Paging.DEFAULT_PAGE_SIZE, Paging.MAX_PAGE_SIZE);
            int current = Paging.page(page);
            List<TableStat> pageRows = snapshotRepository
                    .findCatalogTableRows(catalog.getId(), db, onlyPaimon, kw,
                            PageRequest.of(current - 1, size,
                                    JpaSort.unsafe(dir, SORT_EXPRESSIONS.get(key))))
                    .stream()
                    .map(ManagementCatalogService::toTableStat)
                    .toList();
            return new TableStats(pageRows, total, null, PageMeta.of(total, current, size));
        }

        int size = Paging.pageSize(maxResults, 2000, 5000);
        int offset = Paging.offset(pageToken);
        // offset 换算成页号下推给数据库；token 是 base64url("offset:N")，页大小不变时
        // offset 恒为页大小的整数倍，整除不会失真。
        Pageable pageable = PageRequest.of(offset / size, size,
                JpaSort.unsafe(dir, SORT_EXPRESSIONS.get(key)));
        List<TableStat> rows = snapshotRepository
                .findCatalogTableRows(catalog.getId(), db, onlyPaimon, kw, pageable).stream()
                .map(ManagementCatalogService::toTableStat)
                .toList();
        String next = offset + rows.size() < total ? Paging.encode(offset + size) : null;
        return new TableStats(rows, total, next, null);
    }

    private static TableStat toTableStat(Object[] row) {
        return new TableStat((String) row[0], (String) row[1], (String) row[2],
                (Long) row[3], (Long) row[4], (Long) row[5], (Long) row[6], (Long) row[7],
                (Long) row[8]);
    }

    /** SQL 聚合结果归一：空 catalog 的 sum 回 null，按 0 记。 */
    private static long num(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /** {@code PUT /catalogs/{catalogName}}。 */
    @Transactional
    public Catalog update(String name, UpdateCatalogRequest request) {
        CatalogEntity entity = require(name);
        if (request == null) {
            throw ApiException.badRequest("request body is required");
        }
        // 规格把 currentEntityVersion 声明为可选：给了就必须匹配，没给就跳过校验
        if (request.currentEntityVersion() != null
                && request.currentEntityVersion() != entity.getEntityVersion()) {
            throw ApiException.entityVersionMismatch(ResourceType.CATALOG, name,
                    request.currentEntityVersion(), entity.getEntityVersion());
        }
        if (request.properties() != null) {
            entity.setProperties(new LinkedHashMap<>(request.properties()));
        }
        requireFormatUnchanged(entity, request.catalogFormat());
        if (request.storageConfigInfo() != null) {
            applyStorage(entity, request.storageConfigInfo(), StorageConfigs.of(entity));
        }
        entity.setEntityVersion(entity.getEntityVersion() + 1);
        entity.touch(RequestContext.principal(), RequestContext.now());
        CatalogEntity saved = catalogRepository.save(entity);
        // 只记「改了哪个对象的哪一版」：属性里可能有密钥类内容（applyStorage 写的是
        // 加密后的凭据），审计表不该是第二份凭据备份
        events.publish(CatalogEventType.CATALOG_UPDATED, name, CatalogEvent.TARGET_CATALOG, name,
                Map.of("format", saved.getCatalogFormat(), "entityVersion", saved.getEntityVersion()));
        return toDto(saved);
    }

    /**
     * {@code DELETE /catalogs/{catalogName}}。
     *
     * <p>级联清理该 catalog 下的全部从属对象：database（及其表、分区、视图、函数、语义视图）
     * 与该 catalog 的全部权限赋权。规格没有 cascade 参数，这里按级联实现，
     * 与 catalog API 的 {@code DELETE /databases/{database}} 保持一致。
     */
    @Transactional
    public void delete(String name) {
        CatalogEntity entity = require(name);

        // 赋权是 catalog 内的记录，随 catalog 一起消失；principal role 与主体不动
        List<PermissionAssignmentEntity> grants =
                permissionAssignmentRepository.findByCatalogId(entity.getId());
        permissionAssignmentRepository.deleteByCatalogId(entity.getId());
        // 复用 catalog API 的库级级联删除，保证两条删除路径的语义一致
        int databases = databaseService.dropAll(entity.getPrefix());
        catalogRepository.delete(entity);
        log.debug("deleted catalog {}: {} databases and {} permission assignments removed",
                name, databases, grants.size());
        // 级联删掉的库与表不再各发一条：一条 catalog.dropped 已经说明了范围，
        // 逐级补发只会让「一次删目录」在事件流里膨胀成几百条
        events.publish(CatalogEventType.CATALOG_DROPPED, name, CatalogEvent.TARGET_CATALOG, name,
                Map.of("databases", databases, "permissions", grants.size()));
    }

    /** 按名称取 catalog，不存在则 404。供其他管理服务复用。 */
    @Transactional(readOnly = true)
    public CatalogEntity require(String name) {
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("catalog name is required");
        }
        return catalogRepository.findByPrefix(name)
                .orElseThrow(() -> ApiException.managementNotExist(ResourceType.CATALOG, name));
    }

    /**
     * {@code POST /catalogs/{catalogName}/credentials/revoke}：清掉这个 catalog 的凭据缓存。
     *
     * <p><b>这个入口能做什么、不能做什么，必须先说清楚（设计稿 G10 / P2-3）。</b>
     *
     * <ul>
     *   <li><b>能做：</b>把服务端缓存里属于这个 catalog 的条目全部清掉——下一读表会重新
     *       签发，新票用新配置（比如刚换过的那对钥匙）。表级票与服务端自己用的 catalog 级票
     *       一起清，两种都要清的理由见 {@code StorageCredentialCache#invalidateCatalog}。</li>
     *   <li><b>不能做：</b>让**已经发出去**的票立刻失效。四家云的 STS 都没有按 token 吊销的
     *       API，一张 1 小时的票在它的寿命内永远可用。真要「立刻断掉」，只有三条路：
     *       缩短 TTL 后重新签发、轮换 catalog 上的钥匙、或在云侧用条件（时间段 / 来源
     *       身份 / VPC 端点）拒绝。回执里把这句话带回去，是因为这个端点最容易被当成
     *       「紧急撤销按钮」——而它按不了那个。</li>
     *   <li><b>不推进 {@code throttling} 的冷却：</b>作废与限流是两件事，顺手解掉冷却
     *       只会让刚被限流的云侧立刻再挨一轮。</li>
     * </ul>
     *
     * <p>并发地，若此刻正好有一次签发在途，它的结果不会写回缓存（缓存里的作废代数
     * 会把它挡掉）——也就是说这里之后的下一次读表一定重新签发。
     */
    public CachedCredentialRevocation revokeCachedCredentials(String catalogName) {
        CatalogEntity entity = require(catalogName);
        int cleared = credentialCache.invalidateCatalog(entity.getId());
        log.info("revoked {} cached credentials for catalog {}", cleared, catalogName);
        return new CachedCredentialRevocation(catalogName, cleared, true,
                "cached credentials dropped; the next table read mints a new one. Tokens already"
                        + " handed out stay valid until their own expiry (no cloud offers per-token"
                        + " revocation) — to cut that window, lower lakehouse.rest.credential.ttl-seconds,"
                        + " rotate the key pair stored on the catalog, or deny at the cloud side"
                        + " (time window / source identity / VPC endpoint)");
    }

    /** 实体 → 管理规格的 {@code Catalog}（顺带查一次执行集群摘要，单条路径用）。 */
    public Catalog toDto(CatalogEntity entity) {
        return toDto(entity, executionClusters.summaryOf(entity.getId()));
    }

    /** 实体 → 管理规格的 {@code Catalog}，摘要由调用方给（列表批量取好的那一份）。 */
    private Catalog toDto(CatalogEntity entity, Summary executionCluster) {
        return new Catalog(
                entity.getCatalogType(),
                // 用 of() 而不是直接回列值：认不出来的取值（手工改库、旧数据）在
                // 服务端别处都是按默认格式解释的（CatalogService#formatOf），
                // 管理面若原样回一个服务端自己都不认的值，读的人会更困惑
                CatalogFormat.of(entity.getCatalogFormat()).wireName(),
                entity.getPrefix(),
                new LinkedHashMap<>(entity.getProperties()),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getEntityVersion(),
                StorageConfigs.withoutSecrets(StorageConfigs.of(entity)),
                executionCluster);
    }

    /**
     * 解析管理 API 传入的 {@code catalogFormat}。
     *
     * <p>没给就用部署的默认格式（{@code lakehouse.rest.default-catalog-format}）——
     * 与自动登记用的是同一个开关，理由也一样：这个值决定 catalog 此后服务哪一套协议，
     * 猜错的表现是「客户端每一次调用都 404」，因此部署必须能替调用方定这个默认值。
     *
     * <p>给了但认不出来则 400，**不让脏值进库**：列是 {@code varchar}，写进去一个
     * 拼错的 {@code ICEBURG} 不会有任何即时报错，而后果是该 catalog 的所有请求
     * 都按 Paimon 解释——错误现场离原因非常远（{@link CatalogFormat#of} 的注释
     * 说明了读取侧为什么只能回落而不能抛）。
     */
    private CatalogFormat parseFormat(String requested) {
        if (requested == null || requested.isBlank()) {
            return CatalogFormat.of(properties.getDefaultCatalogFormat());
        }
        return CatalogFormat.parse(requested)
                .orElseThrow(() -> ApiException.badRequest("Unsupported catalog.catalogFormat: "
                        + requested + "; expected one of " + Arrays.toString(CatalogFormat.values())));
    }

    /**
     * 校验 {@code PUT} 里带来的格式确实是原来那个。
     *
     * <p>格式不可变（见 {@link CatalogFormat} 的注释）：同一个 prefix 下两种格式的
     * 元数据代数、仓库里的物化产物、客户端可见的端点集合都不同，改一个字段等于让
     * 所有已建对象的语义整体漂移。
     *
     * <p>这里**接受**取值相同的字段而不是一概拒绝，是因为管理面的常见用法是
     * 「读一个 catalog、改一个字段、写回去」——那种往返会把整个 {@code Catalog}
     * 带回来。断言相等而不是静默忽略，则是为了让「以为能改格式」的调用方立刻知道不行。
     */
    private void requireFormatUnchanged(CatalogEntity entity, String requested) {
        if (requested == null || requested.isBlank()) {
            return;
        }
        CatalogFormat current = CatalogFormat.of(entity.getCatalogFormat());
        CatalogFormat parsed = parseFormat(requested);
        if (parsed != current) {
            throw ApiException.badRequest("catalog.catalogFormat is immutable: catalog \""
                    + entity.getPrefix() + "\" is a " + current.wireName()
                    + " catalog and cannot become a " + parsed.wireName()
                    + " catalog; delete and recreate it to change the format");
        }
    }

    /**
     * 归一化并校验一份存储配置，然后写进实体。
     *
     * <p>调用方没给 {@code storageConfigInfo} 时按 {@code FILE} 处理、位置退回默认仓库，
     * 与 feature 上线前的行为一致。落库细节见 {@link StorageConfigs#apply}。
     *
     * @param existing 库中已有的配置，新建时为 {@code null}。只有静态凭据的合并需要它：
     *                 密钥只写不读，请求里省略时必须从旧值继承（见
     *                 {@link StorageConfigs#mergeCredentials}）
     */
    private void applyStorage(CatalogEntity entity, StorageConfigInfo requested, StorageConfigInfo existing) {
        StorageConfigInfo supplied = requested == null
                ? StorageConfigs.blank(StorageType.FILE, new ArrayList<>())
                : requested;
        StorageConfigInfo normalized = StorageConfigs.normalize(supplied, properties.getDefaultWarehouse());
        StorageConfigInfo merged = StorageConfigs.mergeCredentials(normalized, existing, cipher);
        StorageConfigs.validate(merged);
        // 存储类型是否可用是本部署的能力问题（装了哪个 FileIO），
        // 与配置本身是否合法是两件事，因此分两步校验、分开报错
        storagePolicy.requireSupported(merged.storageType(), entity.getPrefix());
        StorageConfigs.apply(entity, merged);
    }

    /**
     * 新建 catalog 时把「自举角色」授成该 catalog 的管理员。
     *
     * <p>授权体系必须有个入口：没有任何赋权时，连服务管理员之外的第一个人都授不了权。
     * 自举角色由 {@code lakehouse.rest.authorization.bootstrap-principal-role} 指定，
     * 它是一串 principal（{@code role:<name>}），不依赖角色实体是否存在——
     * 角色还没建时这条赋权是「悬空」的，等角色建好、有人加入，权限立刻生效。
     *
     * <p>给两条：{@code CATALOG/ALL} 管 catalog 实体本身（改属性、删 catalog、建库），
     * {@code CATALOG_ALL/ALL} 管其下一切后代。两个作用域在官方模型里本就分开，
     * 少给一条会出现「能建库却不能列表」这类半残状态。
     *
     * <p>仅在开启授权时写入；关闭授权时判定全放行，不需要这些数据。
     */
    private void provisionBootstrapGrants(CatalogEntity catalog) {
        if (!properties.getAuthorization().isEnabled()) {
            return;
        }
        String roleName = properties.getAuthorization().getBootstrapPrincipalRole();
        if (roleName == null || roleName.isBlank()) {
            log.info("no bootstrap principal role configured; catalog {} starts without any grant,"
                    + " only service admins can grant access on it", catalog.getPrefix());
            return;
        }
        String principal = "role:" + roleName;
        long now = RequestContext.now();
        for (String resourceType : List.of("CATALOG", "CATALOG_ALL")) {
            PermissionAssignmentEntity grant = new PermissionAssignmentEntity();
            grant.setId(Paging.newId());
            grant.setCatalogId(catalog.getId());
            grant.setResourceType(resourceType);
            grant.setDatabaseName("");
            grant.setObjectName("");
            grant.setAccess("ALL");
            grant.setPrincipal(principal);
            grant.markCreated(RequestContext.principal(), now);
            permissionAssignmentRepository.save(grant);
        }
        log.info("granted CATALOG/ALL and CATALOG_ALL/ALL on catalog {} to {}",
                catalog.getPrefix(), principal);
    }
}
