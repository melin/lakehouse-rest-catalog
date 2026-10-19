package io.github.melin.lakehouse.rest.iceberg.service;

import io.github.melin.lakehouse.rest.config.RequestContext;
import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.domain.entity.DatabaseEntity;
import io.github.melin.lakehouse.rest.domain.entity.TableEntity;
import io.github.melin.lakehouse.rest.domain.repo.TableRepository;
import io.github.melin.lakehouse.rest.domain.repo.TableSnapshotRepository;
import io.github.melin.lakehouse.rest.event.CatalogEvent;
import io.github.melin.lakehouse.rest.event.CatalogEventPublisher;
import io.github.melin.lakehouse.rest.event.CatalogEventType;
import io.github.melin.lakehouse.rest.iceberg.dto.IcebergDtos;
import io.github.melin.lakehouse.rest.iceberg.support.IcebergMetadataFiles;
import io.github.melin.lakehouse.rest.iceberg.support.IcebergUpdateCodec;
import io.github.melin.lakehouse.rest.service.CatalogService;
import io.github.melin.lakehouse.rest.service.StorageFileAccess;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.CatalogFormat;
import io.github.melin.lakehouse.rest.support.Json;
import io.github.melin.lakehouse.rest.support.NamespacePaths;
import io.github.melin.lakehouse.rest.support.Paging;
import io.github.melin.lakehouse.rest.support.Paths;
import io.github.melin.lakehouse.rest.support.ResourceType;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.PartitionSpecParser;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SchemaParser;
import org.apache.iceberg.SortOrder;
import org.apache.iceberg.SortOrderParser;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.UpdateRequirement;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Iceberg 表的生命周期：列举 / 装载 / 建表 / 提交 / 删除 / 重命名 / 登记 / 注销 / metrics。
 *
 * <p><b>写路径的次序是「先写仓库、后落库」，而且只有这一个次序是对的。</b>
 * 一次提交同时产生两处写入：仓库里的新 {@code metadata.json}、库里的文档与投影行。
 *
 * <table border="1">
 *   <caption>两种次序失败时的后果</caption>
 *   <tr><th>次序</th><th>失败时</th></tr>
 *   <tr><td>先落库、后写仓库</td><td>库里指向一个**不存在**的元数据文件 →
 *       表从此读不开，必须人工修</td></tr>
 *   <tr><td><b>先写仓库、后落库</b>（本实现）</td><td>仓库里多一个**孤儿**
 *       metadata 文件 → 无害（Iceberg 本来就允许，{@code remove_orphan_files}
 *       能清），库里仍指旧版本，客户端重试即可</td></tr>
 * </table>
 *
 * <p><b>并发用两道闸，而不是一把锁。</b>Iceberg 客户端自己会送 {@code requirements}
 * （{@code assert-table-uuid} / {@code assert-ref-snapshot-id} / …），服务端**必须逐条校验**；
 * 服务端这侧再加一道 CAS（落库时 {@code where metadata_location = :expected}）。
 * 两道闸管的不是同一件事：{@code requirements} 管「客户端基于哪个状态提交」，
 * CAS 管「在我读到与写回之间有没有别人插进来」。只有前者会漏掉多副本部署下的竞态，
 * 只有后者会漏掉「客户端拿了一份很旧的元数据但恰好 uuid 相同」。
 * 影响 0 行时回 409 {@code CommitFailedException}——客户端对这个状态码的动作就是 refresh 重试。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IcebergTableService {

    /**
     * {@code paimon_table.table_type} 里表示 Iceberg 表的取值。
     *
     * <p>与 Paimon 侧的 {@code PAIMON} / {@code format-table} / {@code object-table} 同一列。
     * 复用这一列的直接收益是 {@code GET /databases/{db}/table-details?tableType=ICEBERG}
     * 照旧能按格式过滤，控制台的表列表也不用为 Iceberg 加一套判据。
     */
    public static final String TABLE_TYPE_ICEBERG = "ICEBERG";

    /**
     * Iceberg 客户端在 {@code GET .../tables/{table}} 上用的 {@code snapshots} 参数取值。
     *
     * <p>规格只定义了 {@code all} 与 {@code refs}，并写明**不传参数时等价于 {@code all}**
     * （{@code rest-catalog-open-api-iceberg.yaml} 里那一句
     * 「Default if no param is provided is all」）。
     */
    private static final String SNAPSHOTS_ALL = "all";
    private static final String SNAPSHOTS_REFS = "refs";

    /** 上游在建表时写进 properties 的创建时刻；照抄它，让元数据与 Iceberg 自己的服务端一致。 */
    private static final String PROPERTY_CREATED_AT = "created-at";

    private final TableRepository tableRepository;
    private final TableSnapshotRepository snapshotRepository;
    private final IcebergNamespaceService namespaceService;
    private final IcebergProjections projections;
    private final IcebergCredentialService credentialService;
    private final CatalogService catalogService;
    private final StorageFileAccess fileAccess;
    private final RestServerProperties properties;
    private final CatalogEventPublisher events;

    // ------------------------------------------------------------------ 列举

    /** {@code GET /v1/{prefix}/namespaces/{namespace}/tables}。 */
    @Transactional(readOnly = true)
    public IcebergDtos.ListTablesResponse list(String prefix, List<String> levels,
                                               Integer pageSize, String pageToken) {
        CatalogEntity catalog = resolve(prefix);
        DatabaseEntity database = namespaceService.require(catalog, levels);
        List<IcebergDtos.TableIdentifier> identifiers = new ArrayList<>();
        for (TableEntity table : tableRepository.findAllByDatabaseIdOrderByNameAsc(database.getId())) {
            if (isIceberg(table)) {
                identifiers.add(identifier(levels, table.getName()));
            }
        }
        Paging.Slice<IcebergDtos.TableIdentifier> slice = Paging.slice(identifiers,
                Paging.offset(pageToken),
                Paging.pageSize(pageSize, properties.getDefaultPageSize(), properties.getMaxPageSize()));
        return new IcebergDtos.ListTablesResponse(slice.items(), slice.nextPageToken());
    }

    // ------------------------------------------------------------------ 读

    /**
     * {@code GET /v1/{prefix}/namespaces/{namespace}/tables/{table}}。
     *
     * <p>{@code snapshots=refs} 时按上游 {@code CatalogHandlers.loadTable} 的做法
     * **重建**一份元数据（{@code suppressHistoricalSnapshots()}），而不是自己删 JSON 里的
     * 数组元素：保留哪些快照的判据（被任一 ref 引用）由上游定义，自己实现一份等于把它抄下来
     * 并承诺它不变。{@code all}（含不传参数）时直接回原文，
     * 因此「引擎读到的」与「库里存的」逐字节相同。
     *
     * @param delegation {@code X-Iceberg-Access-Delegation} 请求头的原值，可为 {@code null}
     */
    @Transactional(readOnly = true)
    public IcebergDtos.LoadTableResponse load(String prefix, List<String> levels, String tableName,
                                              String snapshotsMode, String delegation) {
        CatalogEntity catalog = resolve(prefix);
        TableEntity table = requireIcebergTable(catalog, levels, tableName);
        TableMetadata metadata = readMetadata(table);
        List<IcebergDtos.StorageCredential> credentials =
                credentialService.delegated(catalog, table, delegation);
        return new IcebergDtos.LoadTableResponse(table.getMetadataLocation(),
                metadataNode(table, metadata, snapshotsMode), null, credentials);
    }

    /** {@code HEAD .../tables/{table}}：存在即返回（204），不存在抛 404。 */
    @Transactional(readOnly = true)
    public void requireExists(String prefix, List<String> levels, String tableName) {
        requireIcebergTable(resolve(prefix), levels, tableName);
    }

    // ------------------------------------------------------------------ 建表

    /**
     * {@code POST /v1/{prefix}/namespaces/{namespace}/tables}。
     *
     * <p><b>{@code stage-create} 明确报 400，不静默忽略。</b>暂存建表（staged create）
     * 的用途只有一个：在 {@code POST /transactions/commit} 里把多张表一次性提交。
     * 本服务端一期不做多表事务，因此「暂存」在这里没有任何后续路径——它只会是一张
     * 已经落库、也写了元数据文件的普通表。客户端拿到 200 之后会在
     * {@code transactions/commit} 里提交它，而那个端点不存在，最终表现为一次
     * 「建表成功但事务提交 404」的错，原因离现场很远。宁可现在就拒。
     *
     * @param delegation {@code X-Iceberg-Access-Delegation} 请求头的原值，可为 {@code null}。
     *                   规格给建表这条路径也留了这个头（客户端建完表往往会立刻要凭据去写数据），
     *                   因此这里与 {@link #load} 同样处理：**不问不给**。
     */
    @Transactional
    public IcebergDtos.LoadTableResponse create(String prefix, List<String> levels,
                                               IcebergDtos.CreateTableRequest request,
                                               String delegation) {
        if (request != null && Boolean.TRUE.equals(request.stageCreate())) {
            throw new ApiException(400, ResourceType.TABLE, request.name(),
                    "stage-create is not supported by this catalog: multi-table transactions"
                            + " (POST /v1/{prefix}/transactions/commit) are not implemented, so a staged"
                            + " create has no commit path",
                    "UnsupportedOperationException");
        }
        CatalogEntity catalog = resolve(prefix);
        DatabaseEntity database = namespaceService.require(catalog, levels);
        String tableName = requireName(request == null ? null : request.name(), "name");
        if (tableRepository.existsByDatabaseIdAndName(database.getId(), tableName)) {
            throw tableAlreadyExists(levels, tableName);
        }
        if (request.schema() == null || request.schema().isNull()) {
            throw ApiException.badRequest("schema is required to create a table");
        }

        Schema schema = parseSchema(request.schema());
        PartitionSpec spec = request.partitionSpec() == null || request.partitionSpec().isNull()
                ? PartitionSpec.unpartitioned()
                : parseSpec(schema, request.partitionSpec());
        SortOrder sortOrder = request.writeOrder() == null || request.writeOrder().isNull()
                ? SortOrder.unsorted()
                : parseSortOrder(schema, request.writeOrder());

        Map<String, String> tableProperties = request.properties() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(request.properties());
        tableProperties.putIfAbsent(PROPERTY_CREATED_AT, OffsetDateTime.now(ZoneOffset.UTC).toString());

        String location = request.location() == null || request.location().isBlank()
                ? Paths.tableLocation(properties.getPathTemplate(), catalog.getWarehouse(),
                        database.getName(), tableName)
                : request.location();
        TableMetadata metadata = TableMetadata.newTableMetadata(schema, spec, sortOrder, location,
                tableProperties);

        // 上游的 newTableMetadata 不填 metadataFileLocation（实测为 null），位置必须由
        // 「拥有这张表」的那一层推——本服务端就是那一层，见 IcebergMetadataFiles 的类注释
        String metadataLocation = IcebergMetadataFiles.nextTableMetadataLocation(location, null);
        String document = TableMetadataParser.toJson(metadata);

        // 先写仓库、后落库（理由见类注释）
        fileAccess.write(catalog.getId(), metadataLocation, document);

        TableEntity table = new TableEntity();
        table.setId(Paging.newId());
        table.setCatalogId(catalog.getId());
        table.setDatabaseId(database.getId());
        table.setName(tableName);
        table.setPath(location);
        table.setExternal(false);
        table.setTableType(TABLE_TYPE_ICEBERG);
        applyMetadata(table, metadataLocation, document, metadata);
        table.markCreated(RequestContext.principal(), RequestContext.now());
        tableRepository.save(table);
        projections.rebuild(table, metadata);
        events.publish(CatalogEventType.TABLE_CREATED, prefix, CatalogEvent.TARGET_TABLE,
                NamespacePaths.toStorageName(levels) + "." + tableName, Map.of("format", "ICEBERG"));

        return new IcebergDtos.LoadTableResponse(metadataLocation, Json.readTree(document), null,
                credentialService.delegated(catalog, table, delegation));
    }

    // ------------------------------------------------------------------ 提交

    /**
     * {@code POST /v1/{prefix}/namespaces/{namespace}/tables/{table}}：一轮 update 作用在
     * 当前元数据上。
     *
     * <p>顺序照上游 {@code CatalogHandlers.commit}：**先校验 requirements、再应用 updates**。
     * 反过来的话，一次本该被拒的过期提交会先被应用、再被拒——副作用虽然都回滚了，
     * 但日志里留下的是一条「已经改过又回退」的记录，排障时看不出真正发生过什么。
     */
    @Transactional
    public IcebergDtos.LoadTableResponse commit(String prefix, List<String> levels, String tableName,
                                                IcebergDtos.UpdateTableRequest request) {
        CatalogEntity catalog = resolve(prefix);
        TableEntity table = requireIcebergTable(catalog, levels, tableName);
        requireMatchingIdentifier(request == null ? null : request.identifier(), levels, tableName);

        String expectedLocation = table.getMetadataLocation();
        TableMetadata base = readMetadata(table);

        List<UpdateRequirement> requirements = IcebergUpdateCodec.parseRequirements(
                request == null ? null : request.requirements());
        IcebergUpdateCodec.validate(requirements, base);

        List<MetadataUpdate> updates = IcebergUpdateCodec.parseUpdates(
                request == null ? null : request.updates());
        if (updates.isEmpty()) {
            // 没有 update 就没有新元数据：不写文件、不推位置，直接把当前状态回给客户端
            return new IcebergDtos.LoadTableResponse(expectedLocation,
                    Json.readTree(requireDocument(table)), null, null);
        }
        TableMetadata updated = IcebergUpdateCodec.apply(base, updates);

        String newLocation = IcebergMetadataFiles.nextTableMetadataLocation(updated.location(),
                expectedLocation);
        String document = TableMetadataParser.toJson(updated);

        fileAccess.write(catalog.getId(), newLocation, document);

        int rows = tableRepository.updateIcebergMetadata(table.getId(), expectedLocation, newLocation,
                document, updated.uuid(), updated.formatVersion(), updated.currentSchemaId(),
                SchemaParser.toJson(updated.schema()), TABLE_TYPE_ICEBERG, updated.location(),
                snapshotIdOf(updated), null, RequestContext.now(), RequestContext.principal());
        if (rows == 0) {
            // 读到基线之后、写回之前被别人提交过：仓库里的孤儿文件无害，库里仍指旧版本
            throw new ApiException(409, ResourceType.TABLE, tableName,
                    "Commit failed: the table metadata changed concurrently (expected metadata-location "
                            + expectedLocation + "); refresh the table and retry",
                    "CommitFailedException");
        }
        // CAS 是批量更新，绕过持久化上下文，必须重新读一遍（见 TableRepository 的注释）
        TableEntity refreshed = tableRepository.findById(table.getId())
                .orElseThrow(() -> tableNotExist(levels, tableName));
        projections.rebuild(refreshed, updated);
        events.publish(CatalogEventType.TABLE_COMMITTED, prefix, CatalogEvent.TARGET_TABLE,
                NamespacePaths.toStorageName(levels) + "." + tableName,
                Map.of("format", "ICEBERG", "updates", updates.size()));

        return new IcebergDtos.LoadTableResponse(newLocation, Json.readTree(document), null, null);
    }

    // ------------------------------------------------------------------ 删除

    /**
     * {@code DELETE /v1/{prefix}/namespaces/{namespace}/tables/{table}}。
     *
     * <p><b>「要不要连仓库一起清」有两个来源，取或。</b>Iceberg 规格的这条路径有一个
     * {@code purgeRequested} 查询参数（默认 {@code false}，语义是「用户要求删掉底下的数据
     * 与元数据」），本服务端另有 {@code lakehouse.rest.table-metadata.purge-on-drop}
     * 这一个部署级开关。两者管的不是同一件事：
     *
     * <ul>
     *   <li>{@code purgeRequested} 是**这一次请求**的显式要求，客户端的
     *       {@code DROP TABLE ... PURGE} 会带上它；</li>
     *   <li>{@code purge-on-drop} 是**这个部署**的策略，说的是「删表时顺手把目录也清掉」
     *       （默认关，与 Paimon 面同一个开关、同一套取舍）。</li>
     * </ul>
     *
     * <p>取或而不是取一：客户端明确要求 purge 时不该被部署策略挡住；部署策略开着时，
     * 客户端不发这个参数（等价于 false）也不该变成「留下垃圾目录」。
     *
     * <p><b>外部表永不清。</b>{@code register} 登记进来的表 {@code external=true}，
     * 它的目录不归本服务端所有——那批文件可能有别的引擎在读，
     * 甚至是另一个 catalog 的本体。删掉它是**不可逆的越权**，因此
     * {@code purgeRequested=true} 也挡不住这一条。
     */
    @Transactional
    public void drop(String prefix, List<String> levels, String tableName, Boolean purgeRequested) {
        CatalogEntity catalog = resolve(prefix);
        TableEntity table = requireIcebergTable(catalog, levels, tableName);
        projections.deleteAll(table.getId());
        tableRepository.delete(table);
        boolean purge = Boolean.TRUE.equals(purgeRequested)
                || properties.getTableMetadata().isPurgeOnDrop();
        if (purge && !table.isExternal()) {
            fileAccess.deleteDirectory(catalog.getId(), table.getPath());
        }
        events.publish(CatalogEventType.TABLE_DROPPED, prefix, CatalogEvent.TARGET_TABLE,
                NamespacePaths.toStorageName(levels) + "." + tableName,
                Map.of("format", "ICEBERG", "purged", purge && !table.isExternal()));
    }

    /**
     * {@code POST .../tables/{table}/unregister}：只从目录里摘掉这张表，**绝不碰仓库**。
     *
     * <p>与 {@link #drop} 的区别就在这一点上，而且这个区别是 Iceberg 语义里唯一的区别：
     * {@code unregister} 的用途正是「这张表的数据与元数据由别处管理，本目录不再声明它」。
     * 因此它不受 {@code purge-on-drop} 影响——那个开关说的是「删表时顺手清目录」，
     * 而这里根本没有「删除」这个动作。
     */
    @Transactional
    public void unregister(String prefix, List<String> levels, String tableName) {
        CatalogEntity catalog = resolve(prefix);
        TableEntity table = requireIcebergTable(catalog, levels, tableName);
        projections.deleteAll(table.getId());
        tableRepository.delete(table);
        events.publish(CatalogEventType.TABLE_UNREGISTERED, prefix, CatalogEvent.TARGET_TABLE,
                NamespacePaths.toStorageName(levels) + "." + tableName,
                Map.of("format", "ICEBERG"));
    }

    // ------------------------------------------------------------------ 重命名

    /**
     * {@code POST /v1/{prefix}/tables/rename}。
     *
     * <p>只改「目录里的名字」，不动数据位置——与 Paimon 面 {@code TableService.rename}
     * 同一取舍：Iceberg 的 rename 语义本来就是改标识，不是移动数据。
     *
     * <p><b>这里收 {@code CatalogEntity} 而不是 prefix，与同类里其余方法不同。</b>
     * 原因是这条路径**不由本面的控制器入口**：它与 Paimon 规格逐字同路径，
     * 只能由 core 的 {@code CatalogRenameController} 按 catalog 格式分派过来，
     * 而那一层为了选面已经把 catalog 查出来了（见 {@code CatalogProtocol#rename}）。
     * 让本方法再按 prefix 查一遍是白查一次；更重要的是，那样查出来的结果可能与
     * 分派依据不一致（例如两次调用之间有人改了库），而「处理这次请求的面」是分派那一刻定的。
     */
    @Transactional
    public void rename(CatalogEntity catalog, IcebergDtos.RenameTableRequest request) {
        if (request == null || request.source() == null || request.destination() == null) {
            throw ApiException.badRequest("rename requires source and destination identifiers");
        }
        List<String> sourceLevels = requireNamespace(request.source().namespace(), "source.namespace");
        List<String> targetLevels = requireNamespace(request.destination().namespace(), "destination.namespace");
        String sourceName = requireName(request.source().name(), "source.name");
        String targetName = requireName(request.destination().name(), "destination.name");

        TableEntity table = requireIcebergTable(catalog, sourceLevels, sourceName);
        DatabaseEntity target = namespaceService.require(catalog, targetLevels);
        if (tableRepository.existsByDatabaseIdAndName(target.getId(), targetName)) {
            throw tableAlreadyExists(targetLevels, targetName);
        }
        table.setDatabaseId(target.getId());
        table.setName(targetName);
        table.touch(RequestContext.principal(), RequestContext.now());
        tableRepository.save(table);
        events.publish(CatalogEventType.TABLE_RENAMED, catalog.getPrefix(), CatalogEvent.TARGET_TABLE,
                NamespacePaths.toStorageName(targetLevels) + "." + targetName,
                Map.of("format", "ICEBERG",
                        "from", NamespacePaths.toStorageName(sourceLevels) + "." + sourceName));
    }

    // ------------------------------------------------------------------ 登记

    /**
     * {@code POST /v1/{prefix}/namespaces/{namespace}/register}：登记一张已存在于对象存储的
     * Iceberg 表。
     *
     * <p><b>元数据以仓库里那一份为准。</b>这里把 {@code metadata-location} 指向的文件读进来、
     * 原样存为 {@code metadata_doc}，而不是自己重建一份：{@code register} 的语义就是
     * 「仓库里已经有这张表，本目录只登记它的位置」。表被标成 {@code external=true}，
     * 于是 {@code purge-on-drop} 也不会去删它的目录——那批文件不归本服务端所有。
     *
     * @param delegation {@code X-Iceberg-Access-Delegation} 请求头的原值，可为 {@code null}
     */
    @Transactional
    public IcebergDtos.LoadTableResponse register(String prefix, List<String> levels,
                                                 IcebergDtos.RegisterTableRequest request,
                                                 String delegation) {
        CatalogEntity catalog = resolve(prefix);
        DatabaseEntity database = namespaceService.require(catalog, levels);
        String tableName = requireName(request == null ? null : request.name(), "name");
        String metadataLocation = request == null ? null : request.metadataLocation();
        if (metadataLocation == null || metadataLocation.isBlank()) {
            throw ApiException.badRequest("metadata-location is required to register a table");
        }
        // 文件读不到时 StorageFileAccess 抛 404——先排除位置写错这一种，语义正是它那个 404
        String document = fileAccess.read(catalog.getId(), metadataLocation);
        TableMetadata metadata = parseMetadata(metadataLocation, document);

        TableEntity existing = tableRepository
                .findByCatalogIdAndDatabaseIdAndName(catalog.getId(), database.getId(), tableName)
                .orElse(null);
        if (existing != null && !Boolean.TRUE.equals(request.overwrite())) {
            throw tableAlreadyExists(levels, tableName);
        }
        TableEntity table = existing == null ? new TableEntity() : existing;
        if (existing == null) {
            table.setId(Paging.newId());
            table.setCatalogId(catalog.getId());
            table.setDatabaseId(database.getId());
            table.setName(tableName);
            table.setExternal(true);
            table.markCreated(RequestContext.principal(), RequestContext.now());
        } else {
            table.touch(RequestContext.principal(), RequestContext.now());
        }
        table.setTableType(TABLE_TYPE_ICEBERG);
        applyMetadata(table, metadataLocation, document, metadata);
        tableRepository.save(table);
        projections.rebuild(table, metadata);
        events.publish(CatalogEventType.TABLE_REGISTERED, prefix, CatalogEvent.TARGET_TABLE,
                NamespacePaths.toStorageName(levels) + "." + tableName,
                Map.of("format", "ICEBERG"));

        return new IcebergDtos.LoadTableResponse(metadataLocation, Json.readTree(document), null,
                credentialService.delegated(catalog, table, delegation));
    }

    // ------------------------------------------------------------------ 凭据

    /**
     * {@code GET .../tables/{table}/credentials}：下发给这张表用的对象存储凭据。
     *
     * <p>放在本类而不是让控制器去拼 {@code catalog + table} 两段：那张表是**按 Iceberg 面
     * 的口径**取出来的（{@code requireIcebergTable}），而「取表」这一步的判据
     * （格式、404 的形状、报错消息）都是本类的事。控制器里再写一遍就会有两份，
     * 而两份的偏差只会表现为「同一个不存在的表，两个端点给出的错不一样」。
     */
    @Transactional(readOnly = true)
    public IcebergDtos.LoadCredentialsResponse credentials(String prefix, List<String> levels,
                                                          String tableName) {
        CatalogEntity catalog = resolve(prefix);
        TableEntity table = requireIcebergTable(catalog, levels, tableName);
        return credentialService.credentials(catalog, table);
    }

    // ------------------------------------------------------------------ metrics

    /**
     * {@code POST .../tables/{table}/metrics}。
     *
     * <p><b>只校验与记日志，不写进投影。</b>设计文档把这一项写成「落到快照与分区的统计投影」，
     * 落地时改了主意，理由是这个动作在现有列上**必然是错的**：
     * {@code CommitReport.commit-metrics} 里的是**本次提交的增量**
     * （{@code added-records} / {@code added-data-files} / {@code added-files-size}），
     * 而投影列（{@code total_record_count} / {@code file_count} / {@code file_size_in_bytes}）
     * 存的是**表的当前总量**——把增量写进去，控制台的四张统计卡片会立刻变成错的数，
     * 而且错得看不出来（翻页、下单提交又会被覆盖）。总量的权威来源是快照的
     * {@code summary}，它就在 {@code metadata_doc} 里，随提交一起到手，不需要等这份报告。
     *
     * <p>它仍然有用：报告里的 {@code snapshot-id} 是客户端**声明自己提交过**的快照。
     * 如果这个 id 不在我们的投影里，说明有人绕开了本服务端往仓库里写（直连文件系统、
     * 或者另一个 catalog 指向了同一个目录）——那条路径不会经过本服务端的授权、审计与优化，
     * 因此值得留一条 warn。这是这个端点在本服务端唯一能给出的确定结论。
     */
    @Transactional(readOnly = true)
    public void reportMetrics(String prefix, List<String> levels, String tableName, JsonNode report) {
        CatalogEntity catalog = resolve(prefix);
        TableEntity table = requireIcebergTable(catalog, levels, tableName);
        if (report == null || report.isNull() || !report.has("report-type")) {
            throw ApiException.badRequest("report-type is required");
        }
        String reportType = report.path("report-type").asString("");
        Long snapshotId = report.has("snapshot-id") && report.path("snapshot-id").isNumber()
                ? report.path("snapshot-id").asLong() : null;
        log.debug("iceberg metrics report for {}.{}: type={}, snapshot={}",
                NamespacePaths.toStorageName(levels), tableName, reportType, snapshotId);
        if (snapshotId != null && !snapshotId.equals(table.getLatestSnapshotId())
                && !knownSnapshot(table.getId(), snapshotId)) {
            log.warn("iceberg client reported metrics for snapshot {} of {}.{}, but this catalog has no"
                            + " such snapshot: the table was probably written through another path",
                    snapshotId, NamespacePaths.toStorageName(levels), tableName);
        }
    }

    // ------------------------------------------------------------------ 供同面其它服务复用

    /** 解析 prefix 并确认它确实是一个 Iceberg catalog。 */
    public CatalogEntity resolve(String prefix) {
        return catalogService.resolveForFormat(prefix, CatalogFormat.ICEBERG);
    }

    /**
     * 按层级取一张**可被 Iceberg 面服务**的表，不存在或不是 Iceberg 表时抛 404。
     *
     * <p>「不是 Iceberg 表」也报 404 而不是 500：从客户端看，这个位置上没有它要的东西是事实。
     * 但消息里必须点出真正的原因——一张通过控制台按 Paimon 形态建在这个 catalog 下的表，
     * 会让 {@code load} 永远 404，而运维手上的线索只有一句「表不存在」。
     */
    @Transactional(readOnly = true)
    public TableEntity requireIcebergTable(CatalogEntity catalog, List<String> levels, String tableName) {
        DatabaseEntity database = namespaceService.require(catalog, levels);
        TableEntity table = tableRepository
                .findByCatalogIdAndDatabaseIdAndName(catalog.getId(), database.getId(), tableName)
                .orElseThrow(() -> tableNotExist(levels, tableName));
        if (!isIceberg(table)) {
            throw new ApiException(404, ResourceType.TABLE, tableName,
                    "table " + NamespacePaths.toStorageName(levels) + "." + tableName
                            + " exists but is not an Iceberg table (tableType=" + table.getTableType()
                            + "); it cannot be served over the Iceberg REST protocol",
                    "NoSuchTableException");
        }
        return table;
    }

    /** 这张表能否被 Iceberg 面服务：有格式标记或有真正的 Iceberg 元数据。 */
    public static boolean isIceberg(TableEntity table) {
        if (TABLE_TYPE_ICEBERG.equalsIgnoreCase(table.getTableType())) {
            return true;
        }
        // 老数据（本特性之前登记的表）没有 tableType，但有元数据位置的同样能服务
        return table.getMetadataLocation() != null && !table.getMetadataLocation().isBlank();
    }

    /** 读一份 {@code metadata.json} 原文；表里没有文档时抛 500（服务端自己的一致性被破坏了）。 */
    public String requireDocument(TableEntity table) {
        String document = table.getMetadataDoc();
        if (document == null || document.isBlank()) {
            throw new ApiException(500, ResourceType.TABLE, table.getName(),
                    "table " + table.getName() + " has no Iceberg metadata document");
        }
        return document;
    }

    /** 由实体还原 {@code TableMetadata}。位置取自实体，于是 {@code metadataFileLocation()} 是对的。 */
    public TableMetadata readMetadata(TableEntity table) {
        return parseMetadata(table.getMetadataLocation(), requireDocument(table));
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 把元数据的关键字段投影到表实体上。
     *
     * <p>{@code schema_doc} 存的是 **Iceberg 形状**的 schema JSON（{@code SchemaParser} 的产物），
     * 而不是 Paimon 的 schema 文档：同一列按格式承载各自的形状，与
     * {@code paimon_view.view_schema_doc} 在 Iceberg 侧存 {@code ViewMetadata} 是同一个手法。
     * 读取方（控制台）按 catalog 格式选解析器。
     */
    private void applyMetadata(TableEntity table, String metadataLocation, String document,
                               TableMetadata metadata) {
        table.setMetadataLocation(metadataLocation);
        table.setMetadataDoc(document);
        table.setTableUuid(metadata.uuid());
        table.setFormatVersion(metadata.formatVersion());
        table.setPath(metadata.location());
        table.setSchemaId(metadata.currentSchemaId());
        table.setSchemaDoc(SchemaParser.toJson(metadata.schema()));
        table.setLatestSnapshotId(snapshotIdOf(metadata));
        // Iceberg 的快照没有 uuid（Paimon 有），这一列在 Iceberg 侧永远是空
        table.setLatestSnapshotUuid(null);
    }

    private static Long snapshotIdOf(TableMetadata metadata) {
        return metadata.currentSnapshot() == null ? null : metadata.currentSnapshot().snapshotId();
    }

    /**
     * 按 {@code snapshots} 参数决定回应哪一份元数据 JSON。
     *
     * <p>{@code all}（含不传参数）直接回库里存的原文——不是「重新序列化一遍」。
     * 这一点是有意的：引擎读到的那份文件与库里存的那份必须逐字节相同，
     * 中间过一遍 parse/serialize 就可能把上游新增的、本服务端不认识但必须保真的字段
     * （{@code next-row-id} / {@code encryption-keys} / {@code read-restrictions}）弄丢。
     */
    private JsonNode metadataNode(TableEntity table, TableMetadata metadata, String snapshotsMode) {
        if (snapshotsMode == null || snapshotsMode.isBlank()
                || SNAPSHOTS_ALL.equalsIgnoreCase(snapshotsMode)) {
            return Json.readTree(requireDocument(table));
        }
        if (SNAPSHOTS_REFS.equalsIgnoreCase(snapshotsMode)) {
            TableMetadata trimmed = TableMetadata.buildFrom(metadata)
                    .withMetadataLocation(metadata.metadataFileLocation())
                    .suppressHistoricalSnapshots()
                    .build();
            return Json.readTree(TableMetadataParser.toJson(trimmed));
        }
        throw ApiException.badRequest("Invalid snapshots mode: " + snapshotsMode
                + " (expected one of: all, refs)");
    }

    private boolean knownSnapshot(String tableId, long snapshotId) {
        return snapshotRepository.findByTableIdAndSnapshotId(tableId, snapshotId).isPresent();
    }

    private Schema parseSchema(JsonNode node) {
        try {
            return SchemaParser.fromJson(Json.write(node));
        } catch (RuntimeException e) {
            throw ApiException.badRequest("Cannot parse schema: " + messageOf(e));
        }
    }

    private PartitionSpec parseSpec(Schema schema, JsonNode node) {
        try {
            return PartitionSpecParser.fromJson(schema, Json.write(node));
        } catch (RuntimeException e) {
            throw ApiException.badRequest("Cannot parse partition-spec: " + messageOf(e));
        }
    }

    private SortOrder parseSortOrder(Schema schema, JsonNode node) {
        try {
            return SortOrderParser.fromJson(schema, Json.write(node));
        } catch (RuntimeException e) {
            throw ApiException.badRequest("Cannot parse write-order: " + messageOf(e));
        }
    }

    private TableMetadata parseMetadata(String metadataLocation, String document) {
        try {
            return TableMetadataParser.fromJson(metadataLocation, document);
        } catch (RuntimeException e) {
            throw new ApiException(500, ResourceType.TABLE, metadataLocation,
                    "stored Iceberg metadata at " + metadataLocation + " cannot be read back: "
                            + messageOf(e));
        }
    }

    /**
     * 请求体里的 {@code identifier} 若出现，必须与路径指的表一致。
     *
     * <p>不一致时报 400 而不是忽略：单表提交里这个字段是可选的（路径已经指明了表），
     * 客户端会带上它通常意味着它认为自己提交的是另一张表——那是一个必须让调用方
     * 立刻知道的错，静默按路径执行等于替它改了一个它没打算改的表。
     */
    private static void requireMatchingIdentifier(IcebergDtos.TableIdentifier identifier,
                                                  List<String> levels, String tableName) {
        if (identifier == null) {
            return;
        }
        if (identifier.name() != null && !identifier.name().equals(tableName)) {
            throw ApiException.badRequest("identifier.name \"" + identifier.name()
                    + "\" does not match the table in the path: " + tableName);
        }
        if (identifier.namespace() != null && !identifier.namespace().isEmpty()
                && !identifier.namespace().equals(levels)) {
            throw ApiException.badRequest("identifier.namespace " + identifier.namespace()
                    + " does not match the namespace in the path: " + levels);
        }
    }

    private static List<String> requireNamespace(List<String> levels, String field) {
        if (levels == null || levels.isEmpty()) {
            throw ApiException.badRequest(field + " must not be empty");
        }
        return levels;
    }

    private static String requireName(String name, String field) {
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest(field + " must not be blank");
        }
        return name;
    }

    private static String messageOf(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }

    private static IcebergDtos.TableIdentifier identifier(List<String> levels, String name) {
        return new IcebergDtos.TableIdentifier(levels, name);
    }

    private static ApiException tableNotExist(List<String> levels, String tableName) {
        return new ApiException(404, ResourceType.TABLE, tableName,
                "The given table does not exist: " + NamespacePaths.toStorageName(levels) + "." + tableName,
                "NoSuchTableException");
    }

    private static ApiException tableAlreadyExists(List<String> levels, String tableName) {
        return new ApiException(409, ResourceType.TABLE, tableName,
                "The given table already exists: " + NamespacePaths.toStorageName(levels) + "." + tableName,
                "AlreadyExistsException");
    }
}
