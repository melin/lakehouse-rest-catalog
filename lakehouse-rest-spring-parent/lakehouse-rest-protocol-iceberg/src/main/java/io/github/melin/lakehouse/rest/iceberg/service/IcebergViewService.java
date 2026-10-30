package io.github.melin.lakehouse.rest.iceberg.service;

import io.github.melin.lakehouse.rest.config.RequestContext;
import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.domain.entity.DatabaseEntity;
import io.github.melin.lakehouse.rest.domain.entity.ViewEntity;
import io.github.melin.lakehouse.rest.domain.repo.ViewRepository;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SchemaParser;
import org.apache.iceberg.UpdateRequirement;
import org.apache.iceberg.view.ViewMetadata;
import org.apache.iceberg.view.ViewMetadataParser;
import org.apache.iceberg.view.ViewVersion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Iceberg 视图的生命周期：列举 / 装载 / 建视图 / 提交 / 删除 / 重命名。
 *
 * <p><b>视图与表在存储上共用一张 {@code lakehouse_view}，但元数据代数完全不同。</b>
 * 表是「一轮 update 作用在一份 {@code TableMetadata} 上」，视图是「一轮 update 作用在
 * 一份 {@code ViewMetadata} 上」：视图没有快照、没有分区、没有数据文件，
 * 只有一串 {@code versions[]}（每个版本是「一段 SQL + 一个 schema」的组合）、
 * 一份 {@code version-log} 与一份 {@code properties}。因此本类与
 * {@link IcebergTableService} **不共用实现**：两者唯一相同的部分就是「先写仓库、
 * 后落库」这条写序与 CAS 那一道闸，而这两件事各自只有十来行。
 *
 * <p><b>{@code view_schema_doc} 复用为 {@code ViewMetadata} JSON。</b>
 * Iceberg 的 {@code versions[]} 与 Paimon 视图的「字段 + 默认查询语句 + 各方言语句」
 * 表达的是同一件事（这段视图怎么算出来），因此同一列按格式承载各自的形状，
 * 读取方（控制台）按 catalog 格式选解析器——与 {@code lakehouse_table.schema_doc}
 * 在 Iceberg 侧存 Iceberg 形状的 schema 是同一个手法。
 * 收益很直接：**视图不需要任何投影行**。{@code lakehouse_table_schema} /
 * {@code lakehouse_snapshot} / {@code lakehouse_branch} 的主键都是 {@code table_id}，
 * 视图没有对应的从属表，控制台读视图读的就是这一份文档。
 *
 * <p><b>与表不同的三处取舍，都写在这里：</b>
 *
 * <ul>
 *   <li><b>删除只摘目录条目，不碰仓库。</b>仓里那份 {@code metadata.json} 留成孤儿。
 *       理由有两层：一是视图的位置可能与其他对象共用（视图本身不拥有数据，
 *       它只是一段 SQL），按目录删会误删别人的文件；二是交付次序上
 *       「先删指针、后删文件」才是安全的方向，而在一个事务里先落库再删文件
 *       一旦删失败就会回滚，反而把指针留在了一个可能已被删掉的文件上。
 *       孤儿文件是无害的，且视图元数据文件只有几 KB。</li>
 *   <li><b>视图不产生任何投影行</b>，所以提交成功后不必像表那样重读实体
 *       （表要重读是因为投影行要按新实体重建）。</li>
 *   <li><b>建视图时 {@code view-version} 里的 {@code schema-id} 由上游改写。</b>
 *       客户端送的常常是一个占位值，{@code ViewMetadata.Builder#setCurrentVersion(version, schema)}
 *       在分配 schema 时会把它换成真正分配的 id（实测：客户端送 99、落库为 0）。
 *       因此**不要**拿报文里的 {@code schema-id} 做什么判断。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IcebergViewService {

    private final ViewRepository viewRepository;
    private final IcebergNamespaceService namespaceService;
    private final CatalogService catalogService;
    private final StorageFileAccess fileAccess;
    private final RestServerProperties properties;
    private final CatalogEventPublisher events;

    // ------------------------------------------------------------------ 列举

    /** {@code GET /v1/{prefix}/namespaces/{namespace}/views}。 */
    @Transactional(readOnly = true)
    public IcebergDtos.ListViewsResponse list(String prefix, List<String> levels,
                                              Integer pageSize, String pageToken, Integer page ) {
        CatalogEntity catalog = resolve(prefix);
        DatabaseEntity database = namespaceService.require(catalog, levels);
        List<IcebergDtos.TableIdentifier> identifiers = new ArrayList<>();
        for (ViewEntity view : viewRepository.findAllByDatabaseIdOrderByNameAsc(database.getId())) {
            if (isIceberg(view)) {
                identifiers.add(identifier(levels, view.getName()));
            }
        }
        // 页码模式（控制台表格）：切页并带回总数；协议里的 pageToken 翻页保持原样。
        Paging.Paged<IcebergDtos.TableIdentifier> paged = Paging.paged(identifiers, page, pageSize);
        if (paged != null) {
            return new IcebergDtos.ListViewsResponse(paged.items(), null, (int) paged.meta().total());
        }
        Paging.Slice<IcebergDtos.TableIdentifier> slice = Paging.slice(identifiers,
                Paging.offset(pageToken),
                Paging.pageSize(pageSize, properties.getDefaultPageSize(), properties.getMaxPageSize()));
        return new IcebergDtos.ListViewsResponse(slice.items(), slice.nextPageToken(), null);
    }

    // ------------------------------------------------------------------ 读

    /**
     * {@code GET /v1/{prefix}/namespaces/{namespace}/views/{view}}。
     *
     * <p>回的是**库里存的那份文档原文**（走 {@code Json.readTree} 嵌进报文），
     * 不是「按字段重新拼一份」——理由与表侧完全一致：上游的 {@code ViewMetadata}
     * 也在随版本增字段，重新拼会把本服务端不认识但必须保真的那些悄悄丢掉。
     */
    @Transactional(readOnly = true)
    public IcebergDtos.LoadViewResponse load(String prefix, List<String> levels, String viewName) {
        CatalogEntity catalog = resolve(prefix);
        ViewEntity view = requireIcebergView(catalog, levels, viewName);
        return new IcebergDtos.LoadViewResponse(view.getMetadataLocation(),
                Json.readTree(requireDocument(view)), null);
    }

    /** {@code HEAD .../views/{view}}：存在即返回（204），不存在抛 404。 */
    @Transactional(readOnly = true)
    public void requireExists(String prefix, List<String> levels, String viewName) {
        requireIcebergView(resolve(prefix), levels, viewName);
    }

    // ------------------------------------------------------------------ 建视图

    /**
     * {@code POST /v1/{prefix}/namespaces/{namespace}/views}。
     *
     * <p>建视图用 {@code ViewMetadata.builder()}（而不是像表那样走
     * {@code newTableMetadata} 这类静态工厂）：视图的「第一个版本」就是客户端送来的
     * 那个 {@code view-version}，因此 builder 的入口是
     * {@code setCurrentVersion(version, schema)}——它同时做两件事，
     * 把版本加进 {@code versions[]} 并把 schema 分配进 {@code schemas[]}。
     *
     * <p>与表侧一样，{@code build()} 不会填 {@code metadataFileLocation}
     * （实测为 {@code null}），位置必须由服务端自己推，见
     * {@link IcebergMetadataFiles}。而 {@code location} 若客户端没给，就用
     * {@code lakehouse.rest.path-template} 推一个——与表同一套模板，于是视图与表
     * 在同一层目录下（{@code <warehouse>/<db>.db/<name>}）。同名表与视图共用目录
     * 不会互相覆盖（元数据文件名里带 uuid），代价只是排障时 {@code ls metadata/}
     * 会同时看到两类文件：这是刻意保留的，因为**视图不该有自己的一套目录约定**——
     * Iceberg 的视图与表本来就住在同一个 namespace 下。
     */
    @Transactional
    public IcebergDtos.LoadViewResponse create(String prefix, List<String> levels,
                                               IcebergDtos.CreateViewRequest request) {
        CatalogEntity catalog = resolve(prefix);
        DatabaseEntity database = namespaceService.require(catalog, levels);
        String viewName = requireName(request == null ? null : request.name(), "name");
        if (viewRepository.existsByDatabaseIdAndName(database.getId(), viewName)) {
            throw viewAlreadyExists(levels, viewName);
        }
        if (request.schema() == null || request.schema().isNull()) {
            throw ApiException.badRequest("schema is required to create a view");
        }
        Schema schema = parseSchema(request.schema());
        ViewVersion version = IcebergUpdateCodec.parseViewVersion(request.viewVersion());

        Map<String, String> viewProperties = request.properties() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(request.properties());
        String location = request.location() == null || request.location().isBlank()
                ? Paths.tableLocation(properties.getPathTemplate(), catalog.getWarehouse(),
                        database.getName(), viewName)
                : request.location();
        // 视图与表走同一条白名单：视图不写数据，但它会在自己的 location 下写 metadata，
        // 而引擎取票时同样按这个位置收窄。漏掉视图这一处，等于给「越界写」留了个后门。
        Paths.requireWithin(location, catalog.getAllowedLocations());
        ViewMetadata metadata = ViewMetadata.builder()
                .setLocation(location)
                .setProperties(viewProperties)
                .setCurrentVersion(version, schema)
                .build();

        String metadataLocation = IcebergMetadataFiles.nextViewMetadataLocation(location, null);
        String document = ViewMetadataParser.toJson(metadata);

        // 先写仓库、后落库（与表侧同一条写序，理由见 IcebergTableService 的类注释）
        fileAccess.write(catalog.getId(), metadataLocation, document);

        ViewEntity view = new ViewEntity();
        view.setId(Paging.newId());
        view.setCatalogId(catalog.getId());
        view.setDatabaseId(database.getId());
        view.setName(viewName);
        view.setMetadataLocation(metadataLocation);
        view.setViewSchemaDoc(document);
        view.markCreated(RequestContext.principal(), RequestContext.now());
        viewRepository.save(view);
        events.publish(CatalogEventType.VIEW_CREATED, prefix, CatalogEvent.TARGET_VIEW,
                NamespacePaths.toStorageName(levels) + "." + viewName, Map.of("format", "ICEBERG"));

        return new IcebergDtos.LoadViewResponse(metadataLocation, Json.readTree(document), null);
    }

    // ------------------------------------------------------------------ 提交

    /**
     * {@code POST /v1/{prefix}/namespaces/{namespace}/views/{view}}：一轮 update 作用在
     * 当前 {@code ViewMetadata} 上。
     *
     * <p>顺序与表侧一致：**先校验 requirements、再应用 updates**。
     * 视图的 requirements 只有两个（{@code assert-view-uuid} 与
     * {@code assert-view-does-not-exist}），都由上游的
     * {@code UpdateRequirement#validate(ViewMetadata)} 判——本服务端不自己判 uuid。
     *
     * <p>没有 update 时直接回当前状态、不写文件：空提交不产生新一代元数据，
     * 而多写一个文件会让「这一代的序号为什么跳了」变成一个需要读代码才能回答的问题。
     */
    @Transactional
    public IcebergDtos.LoadViewResponse commit(String prefix, List<String> levels, String viewName,
                                               IcebergDtos.UpdateViewRequest request) {
        CatalogEntity catalog = resolve(prefix);
        ViewEntity view = requireIcebergView(catalog, levels, viewName);
        requireMatchingIdentifier(request == null ? null : request.identifier(), levels, viewName);

        String expectedLocation = view.getMetadataLocation();
        ViewMetadata base = parseViewMetadata(expectedLocation, requireDocument(view));

        List<UpdateRequirement> requirements = IcebergUpdateCodec.parseRequirements(
                request == null ? null : request.requirements());
        IcebergUpdateCodec.validate(requirements, base);

        List<MetadataUpdate> updates = IcebergUpdateCodec.parseUpdates(
                request == null ? null : request.updates());
        if (updates.isEmpty()) {
            return new IcebergDtos.LoadViewResponse(expectedLocation,
                    Json.readTree(requireDocument(view)), null);
        }
        ViewMetadata updated = IcebergUpdateCodec.apply(base, updates);

        String newLocation = IcebergMetadataFiles.nextViewMetadataLocation(updated.location(),
                expectedLocation);
        String document = ViewMetadataParser.toJson(updated);

        fileAccess.write(catalog.getId(), newLocation, document);

        int rows = viewRepository.updateViewMetadata(view.getId(), expectedLocation, newLocation,
                document, RequestContext.now(), RequestContext.auditPrincipal());
        if (rows == 0) {
            // 读到基线之后、写回之前被别人提交过：仓库里的孤儿文件无害，库里仍指旧版本
            throw new ApiException(409, ResourceType.VIEW, viewName,
                    "Commit failed: the view metadata changed concurrently (expected metadata-location "
                            + expectedLocation + "); refresh the view and retry",
                    "CommitFailedException");
        }
        events.publish(CatalogEventType.VIEW_UPDATED, prefix, CatalogEvent.TARGET_VIEW,
                NamespacePaths.toStorageName(levels) + "." + viewName,
                Map.of("format", "ICEBERG", "updates", updates.size()));
        return new IcebergDtos.LoadViewResponse(newLocation, Json.readTree(document), null);
    }

    // ------------------------------------------------------------------ 登记

    /**
     * {@code POST /v1/{prefix}/namespaces/{namespace}/register-view}。
     *
     * <p>与表的 {@code register} 同一条路线：**内容以仓库里那一份为准**——先读
     * {@code metadata-location} 指向的文件（读不到就是 404，语义正是「位置写错」），
     * 解析成 {@code ViewMetadata}，再把指针与文档原文一起落库。视图不投影任何从属行，
     * 因此登记比登记表还轻：一行 {@code lakehouse_view} 就完了。
     *
     * <p><b>没有 {@code overwrite}。</b>规格的 {@code RegisterViewRequest} 只有
     * {@code name} 与 {@code metadata-location} 两个字段，因此重名一律 409——
     * 不去学表侧「带 overwrite 就覆盖」的行为，规格没给的字段就别提供语义。
     */
    @Transactional
    public IcebergDtos.LoadViewResponse register(String prefix, List<String> levels,
                                                 IcebergDtos.RegisterViewRequest request) {
        CatalogEntity catalog = resolve(prefix);
        DatabaseEntity database = namespaceService.require(catalog, levels);
        String viewName = requireName(request == null ? null : request.name(), "name");
        String metadataLocation = request == null ? null : request.metadataLocation();
        if (metadataLocation == null || metadataLocation.isBlank()) {
            throw ApiException.badRequest("metadata-location is required to register a view");
        }
        // 与表侧 register 同一条：这是下一个要读、且后续每次提交都要往上写的路径。
        // 视图实体不存 path（位置只在 ViewMetadata 里），所以判这一处就够了。
        Paths.requireWithin(metadataLocation, catalog.getAllowedLocations());
        // 文件读不到时 StorageFileAccess 抛 404——先排除位置写错这一种，语义正是它那个 404
        String document = fileAccess.read(catalog.getId(), metadataLocation);
        // 这里的文档是**调用方指来**的：解析不动是它给错了位置（指向了不是视图元数据的文件），
        // 报 400 而不是像读路径那样 500——读路径的 500 语义是「服务端自己的一致性坏了」
        try {
            ViewMetadataParser.fromJson(metadataLocation, document);
        } catch (RuntimeException e) {
            throw ApiException.badRequest("Cannot parse ViewMetadata at " + metadataLocation
                    + ": " + messageOf(e));
        }

        if (viewRepository.existsByDatabaseIdAndName(database.getId(), viewName)) {
            throw viewAlreadyExists(levels, viewName);
        }
        ViewEntity view = new ViewEntity();
        view.setId(Paging.newId());
        view.setCatalogId(catalog.getId());
        view.setDatabaseId(database.getId());
        view.setName(viewName);
        view.setMetadataLocation(metadataLocation);
        view.setViewSchemaDoc(document);
        view.markCreated(RequestContext.principal(), RequestContext.now());
        viewRepository.save(view);
        // 复用 VIEW_CREATED 并带 registered 标记，而不是新增事件类型：
        // 目录条目出现的语义与「建视图」相同，事件消费者不需要为登记建一套新的订阅。
        events.publish(CatalogEventType.VIEW_CREATED, prefix, CatalogEvent.TARGET_VIEW,
                NamespacePaths.toStorageName(levels) + "." + viewName,
                Map.of("format", "ICEBERG", "registered", "true"));

        return new IcebergDtos.LoadViewResponse(metadataLocation, Json.readTree(document), null);
    }

    // ------------------------------------------------------------------ 删除

    /**
     * {@code DELETE /v1/{prefix}/namespaces/{namespace}/views/{view}}：只摘目录条目。
     *
     * <p><b>不删仓库里的元数据文件、也不受 {@code purge-on-drop} 影响。</b>
     * 那个开关说的是「删表时顺手清数据目录」，而视图不拥有数据目录——它只是一段 SQL，
     * 它的位置甚至可能与别的对象共用。按目录删会误删别人的文件，因此这里连
     * 「删掉自己那一个 metadata.json」都不做：那个文件是孤儿，无害且只有几 KB，
     * 而删它要求「先落库、后删文件」的跨事务次序，为几 KB 引入一个跨事务的补偿动作
     * 不划算。
     */
    @Transactional
    public void drop(String prefix, List<String> levels, String viewName) {
        CatalogEntity catalog = resolve(prefix);
        ViewEntity view = requireIcebergView(catalog, levels, viewName);
        viewRepository.delete(view);
        events.publish(CatalogEventType.VIEW_DROPPED, prefix, CatalogEvent.TARGET_VIEW,
                NamespacePaths.toStorageName(levels) + "." + viewName, Map.of("format", "ICEBERG"));
    }

    // ------------------------------------------------------------------ 重命名

    /**
     * {@code POST /v1/{prefix}/views/rename}。
     *
     * <p>只改「目录里的名字」，不动 {@code ViewMetadata} 里的 {@code location}——
     * 与表侧 {@code IcebergTableService#rename} 同一取舍（Iceberg 的 rename
     * 语义本来就是改标识）。这也是唯一该这么做的选择：改 location 意味着要把
     * 元数据文件挪到新目录、并重写文档里的 {@code location}，那是
     * {@code set-location} + 提交两件事，客户端要用的话自己会发。
     *
     * <p>收 {@code CatalogEntity} 而不是 prefix 的理由与表侧同（见
     * {@code IcebergTableService#rename} 的注释）：这条路径由 core 的
     * {@code CatalogRenameController} 分派进来，分派那一刻已经查出了 catalog。
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

        ViewEntity view = requireIcebergView(catalog, sourceLevels, sourceName);
        DatabaseEntity target = namespaceService.require(catalog, targetLevels);
        if (viewRepository.existsByDatabaseIdAndName(target.getId(), targetName)) {
            throw viewAlreadyExists(targetLevels, targetName);
        }
        view.setDatabaseId(target.getId());
        view.setName(targetName);
        view.touch(RequestContext.principal(), RequestContext.now());
        viewRepository.save(view);
        events.publish(CatalogEventType.VIEW_RENAMED, catalog.getPrefix(), CatalogEvent.TARGET_VIEW,
                NamespacePaths.toStorageName(targetLevels) + "." + targetName,
                Map.of("format", "ICEBERG",
                        "from", NamespacePaths.toStorageName(sourceLevels) + "." + sourceName));
    }

    // ------------------------------------------------------------------ 内部

    /** 解析 prefix 并确认它确实是一个 Iceberg catalog。 */
    private CatalogEntity resolve(String prefix) {
        return catalogService.resolveForFormat(prefix, CatalogFormat.ICEBERG);
    }

    /**
     * 按层级取一个**可被 Iceberg 面服务**的视图，不存在或不是 Iceberg 视图时抛 404。
     *
     * <p>「不是 Iceberg 视图」也报 404：从客户端看，这个位置上没有它要的东西是事实。
     * 但消息里点出真正的原因——一个通过 Paimon 面建的视图
     * （{@code view_schema_doc} 是 Paimon 形状、{@code metadata_location} 为空）
     * 会让 {@code load} 永远 404，而运维手上的线索只有一句「视图不存在」。
     */
    private ViewEntity requireIcebergView(CatalogEntity catalog, List<String> levels, String viewName) {
        DatabaseEntity database = namespaceService.require(catalog, levels);
        ViewEntity view = viewRepository
                .findByCatalogIdAndDatabaseIdAndName(catalog.getId(), database.getId(), viewName)
                .orElseThrow(() -> viewNotExist(levels, viewName));
        if (!isIceberg(view)) {
            throw new ApiException(404, ResourceType.VIEW, viewName,
                    "view " + NamespacePaths.toStorageName(levels) + "." + viewName
                            + " exists but has no Iceberg metadata; it cannot be served over the"
                            + " Iceberg REST protocol",
                    "NoSuchViewException");
        }
        return view;
    }

    /**
     * 这个视图能否被 Iceberg 面服务：有元数据文件位置才算。
     *
     * <p>判据比表侧更简单（表侧还要认 {@code table_type}）：{@code lakehouse_view}
     * 没有格式列，而 Paimon 面的视图**从不写元数据文件**，因此
     * {@code metadata_location} 为空就一定是 Paimon 形状的目录条目。
     */
    private static boolean isIceberg(ViewEntity view) {
        return view.getMetadataLocation() != null && !view.getMetadataLocation().isBlank();
    }

    /** 读一份视图元数据文档；缺失时抛 500（服务端自己的一致性被破坏了）。 */
    private String requireDocument(ViewEntity view) {
        String document = view.getViewSchemaDoc();
        if (document == null || document.isBlank()) {
            throw new ApiException(500, ResourceType.VIEW, view.getName(),
                    "view " + view.getName() + " has no Iceberg metadata document");
        }
        return document;
    }

    /** 由实体还原 {@code ViewMetadata}。位置取自实体，于是 {@code metadataFileLocation()} 是对的。 */
    private ViewMetadata parseViewMetadata(String metadataLocation, String document) {
        try {
            return ViewMetadataParser.fromJson(metadataLocation, document);
        } catch (RuntimeException e) {
            throw new ApiException(500, ResourceType.VIEW, metadataLocation,
                    "stored Iceberg view metadata at " + metadataLocation + " cannot be read back: "
                            + messageOf(e));
        }
    }

    private Schema parseSchema(JsonNode node) {
        try {
            return SchemaParser.fromJson(Json.write(node));
        } catch (RuntimeException e) {
            throw ApiException.badRequest("Cannot parse schema: " + messageOf(e));
        }
    }

    /**
     * 请求体里的 {@code identifier} 若出现，必须与路径指的视图一致。
     *
     * <p>与表侧同一取舍：不一致时报 400 而不是忽略，因为客户端带上它通常意味着
     * 它认为自己提交的是另一个视图。
     */
    private static void requireMatchingIdentifier(IcebergDtos.TableIdentifier identifier,
                                                  List<String> levels, String viewName) {
        if (identifier == null) {
            return;
        }
        if (identifier.name() != null && !identifier.name().equals(viewName)) {
            throw ApiException.badRequest("identifier.name \"" + identifier.name()
                    + "\" does not match the view in the path: " + viewName);
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

    private static ApiException viewNotExist(List<String> levels, String viewName) {
        return new ApiException(404, ResourceType.VIEW, viewName,
                "The given view does not exist: " + NamespacePaths.toStorageName(levels) + "." + viewName,
                "NoSuchViewException");
    }

    private static ApiException viewAlreadyExists(List<String> levels, String viewName) {
        return new ApiException(409, ResourceType.VIEW, viewName,
                "The given view already exists: " + NamespacePaths.toStorageName(levels) + "." + viewName,
                "AlreadyExistsException");
    }
}
