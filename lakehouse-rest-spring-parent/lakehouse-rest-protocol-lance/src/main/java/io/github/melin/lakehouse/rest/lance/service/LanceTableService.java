package io.github.melin.lakehouse.rest.lance.service;

import io.github.melin.lakehouse.rest.config.RequestContext;
import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.domain.entity.DatabaseEntity;
import io.github.melin.lakehouse.rest.domain.entity.TableEntity;
import io.github.melin.lakehouse.rest.domain.repo.DatabaseRepository;
import io.github.melin.lakehouse.rest.domain.repo.TableRepository;
import io.github.melin.lakehouse.rest.event.CatalogEvent;
import io.github.melin.lakehouse.rest.event.CatalogEventPublisher;
import io.github.melin.lakehouse.rest.event.CatalogEventType;
import io.github.melin.lakehouse.rest.lance.dto.LanceDtos;
import io.github.melin.lakehouse.rest.lance.support.LanceIds;
import io.github.melin.lakehouse.rest.support.LanceErrors;
import io.github.melin.lakehouse.rest.support.Paging;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lance 的表端点（8 个 operation：list / listAll / describe / exists / declare /
 * register / deregister / rename）。
 *
 * <p><b>这一面只做登记，不碰存储。</b>{@code declare} 登记的是**意图**（可以还没有数据集），
 * {@code register} 登记的是**既成事实**（位置里已有 manifest，做一次弱校验）。
 * 建表与写数据由 Lance SDK 在客户端完成——规范专门有一节 "Why Not CreateTable and
 * DropTable" 劝退服务端做这件事，理由里最硬的一条是：写到一半失败会留下半成品目录，
 * 而 catalog 没有回收机制。
 *
 * <p><b>{@code table_type} 填 {@code LANCE}</b>，与 Paimon 的 {@code lance-table}
 * （Paimon schema 里的表类型，是另一种东西）区分开。控制台按 {@code catalog_format}
 * 判形态，不按这个串。
 */
@Service
@RequiredArgsConstructor
public class LanceTableService {

    private final DatabaseRepository databaseRepository;
    private final TableRepository tableRepository;
    private final LanceCatalogSupport support;
    private final CatalogEventPublisher events;

    // ------------------------------------------------------------------ 列举

    /**
     * {@code GET .../namespace/{id}/table/list}：列 schema 下的表。
     *
     * <p><b>回**完整 id**（{@code cat$schema$table}）而不是裸表名。</b>规范给的形状如此，
     * 别按直觉裁剪：客户端拿这个串直接拼下一个请求路径，裁掉前缀会让它再拼一遍——
     * 而拼的那一边用的是它自己配的 delimiter，届时错在哪一层很难判断。
     */
    @Transactional(readOnly = true)
    public LanceDtos.ListTablesResponse list(String id, String delimiter, Integer limit, String pageToken) {
        LanceIds.Id parsee = LanceIds.namespace(id, delimiter);
        CatalogEntity catalog = support.requireCatalog(parsee.catalog());
        DatabaseEntity database = requireDatabase(catalog, parsee, delimiter);
        List<String> tables = new ArrayList<>();
        for (TableEntity table : tableRepository.findAllByDatabaseIdOrderByNameAsc(database.getId())) {
            tables.add(parsee.catalog() + sep(delimiter) + parsee.schema() + sep(delimiter) + table.getName());
        }
        return page(tables, limit, pageToken);
    }

    /**
     * {@code GET /lance/v1/table/{id}}：跨命名空间列举。
     *
     * <p><b>必须带 catalog 前缀</b>（{@code id} 至少一段）：全库扫描既慢又越权——
     * 它能把「我有权看哪些 catalog」这件事的答案一起带出去，而
     * {@code CATALOG_ALL} 挡不住这种期待。
     */
    @Transactional(readOnly = true)
    public LanceDtos.ListTablesResponse search(String id, String delimiter, Integer limit, String pageToken) {
        LanceIds.Id parsee = LanceIds.search(id, delimiter);
        CatalogEntity catalog = support.requireCatalog(parsee.catalog());
        List<String> tables = new ArrayList<>();
        for (TableEntity table : tableRepository.findAllByCatalogIdOrderByDatabaseIdAscNameAsc(catalog.getId())) {
            DatabaseEntity database = databaseRepository.findById(table.getDatabaseId()).orElse(null);
            String schema = database == null ? "" : database.getName();
            tables.add(parsee.catalog() + sep(delimiter) + schema + sep(delimiter) + table.getName());
        }
        return page(tables, limit, pageToken);
    }

    // ------------------------------------------------------------------ describe

    /**
     * {@code POST /lance/v1/table/{id}/describe}。
     *
     * @param loadDetailedMetadata {@code true} 时 P0 回 **406**：读 schema / version /
     *                             stats 要打开数据集，那属于 P1
     * @param checkDeclared        {@code true} 时探测存储，填 {@code is_only_declared}
     * @param withTableUri         {@code true} 时把 location 规范化成合法 URI 填 {@code table_uri}
     * @param vendCredentials      {@code true} 时在 {@code storage_options} 里下发临时凭据
     */
    @Transactional(readOnly = true)
    public LanceDtos.DescribeTableResponse describe(String id, String delimiter,
                                                     boolean loadDetailedMetadata, boolean checkDeclared,
                                                     boolean withTableUri, boolean vendCredentials) {
        if (loadDetailedMetadata) {
            throw LanceErrors.unsupported("DescribeTable with load_detailed_metadata=true");
        }
        LanceIds.Id parsee = LanceIds.table(id, delimiter);
        CatalogEntity catalog = support.requireCatalog(parsee.catalog());
        DatabaseEntity database = requireDatabase(catalog, parsee, delimiter);
        TableEntity table = requireTable(catalog, database, parsee, delimiter);
        // is_only_declared 是「登记了但存储里还没有数据集」——与「存储里有数据集」
        // 恰好相反，别把探测结果直接当它用（这两个名字太像，写反了不会报错，
        // 只会让客户端在表还没建好时以为它已经就绪）。
        Boolean has = checkDeclared ? support.hasDataset(catalog, table.getPath()) : null;
        Boolean onlyDeclared = has == null ? null : !has;
        return new LanceDtos.DescribeTableResponse(parsee.join(sep(delimiter)),
                table.getPath(),
                propertiesOf(table),
                support.storageOptions(catalog, table.getId(), table.getPath(), vendCredentials),
                withTableUri ? tableUri(table.getPath()) : null,
                onlyDeclared);
    }

    /** {@code POST .../exists}：不存在即 404（与 Paimon 面「返回 bool」不同，照规范）。 */
    @Transactional(readOnly = true)
    public void requireExists(String id, String delimiter) {
        LanceIds.Id parsee = LanceIds.table(id, delimiter);
        CatalogEntity catalog = support.requireCatalog(parsee.catalog());
        DatabaseEntity database = requireDatabase(catalog, parsee, delimiter);
        requireTable(catalog, database, parsee, delimiter);
    }

    // ------------------------------------------------------------------ declare / register

    /**
     * {@code POST .../declare}：登记建表意图。
     *
     * <p><b>不写存储、不创建版本。</b>表在客户端真正写入之前，
     * {@code check_declared=true} 会看到 {@code is_only_declared=true}。
     *
     * @param mode {@code create}（默认，已存在 409）/ {@code exist_ok} / {@code overwrite}
     *             （覆盖登记，**不动数据**）
     */
    @Transactional
    public LanceDtos.TableRef declare(String id, String delimiter, LanceDtos.DeclareTableRequest request,
                                     String mode, boolean vendCredentials) {
        LanceIds.Id parsee = LanceIds.table(id, delimiter);
        if (request != null) {
            LanceIds.requireSameAsBody(id, request.id(), delimiter);
        }
        CatalogEntity catalog = support.requireCatalog(parsee.catalog());
        DatabaseEntity database = requireDatabase(catalog, parsee, delimiter);
        Map<String, String> properties = propertiesOf(request);

        TableEntity existing = tableRepository
                .findByCatalogIdAndDatabaseIdAndName(catalog.getId(), database.getId(), parsee.table())
                .orElse(null);
        if (existing != null) {
            return switch (mode(mode)) {
                case "exist_ok" -> ref(parsee, existing, sep(delimiter), catalog, vendCredentials);
                case "overwrite" -> {
                    existing.setPropertiesJson(properties);
                    if (request != null && request.location() != null && !request.location().isBlank()) {
                        support.requireAllowedLocation(catalog, request.location());
                        existing.setPath(request.location());
                    }
                    existing.touch(RequestContext.auditPrincipal(), RequestContext.now());
                    tableRepository.save(existing);
                    yield ref(parsee, existing, sep(delimiter), catalog, vendCredentials);
                }
                default -> throw LanceErrors.tableAlreadyExists(parsee.join(sep(delimiter)));
            };
        }

        String location = request == null || request.location() == null || request.location().isBlank()
                ? support.defaultLocation(catalog, parsee.schema(), parsee.table())
                : request.location();
        support.requireAllowedLocation(catalog, location);

        TableEntity table = new TableEntity();
        table.setId(Paging.newId());
        table.setCatalogId(catalog.getId());
        table.setDatabaseId(database.getId());
        table.setName(parsee.table());
        table.setPath(location);
        table.setTableType("LANCE");
        table.setExternal(false);
        table.setPropertiesJson(properties);
        table.markCreated(RequestContext.auditPrincipal(), RequestContext.now());
        tableRepository.save(table);
        events.publish(CatalogEventType.TABLE_CREATED, parsee.catalog(), CatalogEvent.TARGET_TABLE,
                table.getName(), Map.of("format", "LANCE", "operation", "declare"));
        return ref(parsee, table, sep(delimiter), catalog, vendCredentials);
    }

    /**
     * {@code POST .../register}：登记已有数据集。
     *
     * <p>与 declare 的差别只有一处校验强度：这里要求位置里**已经有**一个数据集
     * （{@code _versions/} 下有 manifest）。它仍然是弱校验——验不出
     * 「有 manifest 但数据文件不全」，那种表我们打不开，而打开它是客户端的事。
     */
    @Transactional
    public LanceDtos.TableRef register(String id, String delimiter, LanceDtos.RegisterTableRequest request,
                                       String mode, boolean vendCredentials) {
        LanceIds.Id parsee = LanceIds.table(id, delimiter);
        if (request != null) {
            LanceIds.requireSameAsBody(id, request.id(), delimiter);
        }
        if (request == null || request.location() == null || request.location().isBlank()) {
            throw LanceErrors.invalidInput("register requires location");
        }
        CatalogEntity catalog = support.requireCatalog(parsee.catalog());
        DatabaseEntity database = requireDatabase(catalog, parsee, delimiter);
        support.requireAllowedLocation(catalog, request.location());
        support.requireDataset(catalog, request.location());

        TableEntity existing = tableRepository
                .findByCatalogIdAndDatabaseIdAndName(catalog.getId(), database.getId(), parsee.table())
                .orElse(null);
        if (existing != null && !"overwrite".equals(mode(mode)) && !"exist_ok".equals(mode(mode))) {
            throw LanceErrors.tableAlreadyExists(parsee.join(sep(delimiter)));
        }
        TableEntity table = existing == null ? new TableEntity() : existing;
        if (existing == null) {
            table.setId(Paging.newId());
            table.setCatalogId(catalog.getId());
            table.setDatabaseId(database.getId());
            table.setName(parsee.table());
            table.markCreated(RequestContext.auditPrincipal(), RequestContext.now());
        } else {
            table.touch(RequestContext.auditPrincipal(), RequestContext.now());
        }
        table.setPath(request.location());
        table.setTableType("LANCE");
        table.setExternal(true);
        table.setPropertiesJson(propertiesOf(request.properties()));
        tableRepository.save(table);
        events.publish(CatalogEventType.TABLE_REGISTERED, parsee.catalog(), CatalogEvent.TARGET_TABLE,
                table.getName(), Map.of("format", "LANCE"));
        return ref(parsee, table, sep(delimiter), catalog, vendCredentials);
    }

    // ------------------------------------------------------------------ deregister

    /**
     * {@code POST .../deregister}：**只删登记，数据留着**。
     *
     * <p>响应带回 {@code location} 与 {@code properties}，便于调用方自己清理目录——
     * 我们从不写这些文件，也就不该删它们（规范里 {@code DropTable} 被劝退的理由同此）。
     */
    @Transactional
    public LanceDtos.DeregisterTableResponse deregister(String id, String delimiter) {
        LanceIds.Id parsee = LanceIds.table(id, delimiter);
        CatalogEntity catalog = support.requireCatalog(parsee.catalog());
        DatabaseEntity database = requireDatabase(catalog, parsee, delimiter);
        TableEntity table = requireTable(catalog, database, parsee, delimiter);
        LanceDtos.DeregisterTableResponse response = new LanceDtos.DeregisterTableResponse(
                parsee.join(sep(delimiter)), table.getPath(), propertiesOf(table));
        tableRepository.delete(table);
        events.publish(CatalogEventType.TABLE_UNREGISTERED, parsee.catalog(), CatalogEvent.TARGET_TABLE,
                table.getName(), Map.of("format", "LANCE"));
        return response;
    }

    // ------------------------------------------------------------------ rename

    /**
     * {@code POST .../rename}。
     *
     * <p>{@code new_name} 可以是 {@code new_table}（同 schema 内改名）或
     * {@code new_schema$new_table}（换 schema）。<b>跨 catalog 一律 400</b>：
     * 本服务端的 rename 是 catalog 内操作，跨 catalog 涉及两行不同的
     * {@code catalog_id}，不是一次改名的语义。
     */
    @Transactional
    public LanceDtos.TableRef rename(String id, String delimiter, LanceDtos.RenameTableRequest request,
                                     boolean vendCredentials) {
        LanceIds.Id parsee = LanceIds.table(id, delimiter);
        if (request == null || request.newName() == null || request.newName().isBlank()) {
            throw LanceErrors.invalidInput("rename requires new_name");
        }
        CatalogEntity catalog = support.requireCatalog(parsee.catalog());
        DatabaseEntity database = requireDatabase(catalog, parsee, delimiter);
        TableEntity table = requireTable(catalog, database, parsee, delimiter);

        String[] target = request.newName().split(java.util.regex.Pattern.quote(sep(delimiter)), -1);
        if (target.length > 2) {
            throw LanceErrors.invalidInput("rename must not cross catalogs: \"" + request.newName() + "\"");
        }
        String newSchema = target.length == 2 ? target[0] : parsee.schema();
        String newName = target.length == 2 ? target[1] : target[0];
        if (newName.isEmpty() || (target.length == 2 && newSchema.isEmpty())) {
            throw LanceErrors.invalidInput("new_name \"" + request.newName() + "\" has an empty level");
        }
        DatabaseEntity targetDatabase = databaseRepository
                .findByCatalogIdAndName(catalog.getId(), newSchema)
                .orElseThrow(() -> LanceErrors.namespaceNotFound(parsee.catalog() + sep(delimiter) + newSchema));
        if (tableRepository.findByCatalogIdAndDatabaseIdAndName(catalog.getId(), targetDatabase.getId(), newName)
                .isPresent()) {
            throw LanceErrors.tableAlreadyExists(
                    parsee.catalog() + sep(delimiter) + newSchema + sep(delimiter) + newName);
        }
        String oldName = table.getName();
        table.setName(newName);
        table.setDatabaseId(targetDatabase.getId());
        table.touch(RequestContext.auditPrincipal(), RequestContext.now());
        tableRepository.save(table);
        events.publish(CatalogEventType.TABLE_RENAMED, parsee.catalog(), CatalogEvent.TARGET_TABLE,
                newName, Map.of("format", "LANCE", "from", oldName));
        return new LanceDtos.TableRef(parsee.catalog() + sep(delimiter) + newSchema + sep(delimiter) + newName,
                table.getPath(), propertiesOf(table),
                support.storageOptions(catalog, table.getId(), table.getPath(), vendCredentials));
    }

    // ------------------------------------------------------------------ 内部

    private DatabaseEntity requireDatabase(CatalogEntity catalog, LanceIds.Id parsee, String delimiter) {
        return databaseRepository.findByCatalogIdAndName(catalog.getId(), parsee.schema())
                .orElseThrow(() -> LanceErrors.namespaceNotFound(
                        parsee.catalog() + sep(delimiter) + parsee.schema()));
    }

    private TableEntity requireTable(CatalogEntity catalog, DatabaseEntity database, LanceIds.Id parsee,
                                      String delimiter) {
        return tableRepository
                .findByCatalogIdAndDatabaseIdAndName(catalog.getId(), database.getId(), parsee.table())
                .orElseThrow(() -> LanceErrors.tableNotFound(parsee.join(sep(delimiter))));
    }

    private LanceDtos.TableRef ref(LanceIds.Id parsee, TableEntity table, String delimiter,
                                    CatalogEntity catalog, boolean vendCredentials) {
        return new LanceDtos.TableRef(parsee.join(delimiter), table.getPath(), propertiesOf(table),
                support.storageOptions(catalog, table.getId(), table.getPath(), vendCredentials));
    }

    private static LanceDtos.ListTablesResponse page(List<String> tables, Integer limit, String pageToken) {
        int size = Paging.pageSize(limit, 100, 1000);
        Paging.Slice<String> slice = Paging.slice(tables, Paging.offset(pageToken), size);
        return new LanceDtos.ListTablesResponse(slice.items(), slice.nextPageToken());
    }

    private static Map<String, String> propertiesOf(TableEntity table) {
        return table.getPropertiesJson() == null ? Map.of() : new LinkedHashMap<>(table.getPropertiesJson());
    }

    private static Map<String, String> propertiesOf(LanceDtos.DeclareTableRequest request) {
        return request == null || request.properties() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(request.properties());
    }

    /** 请求里的 {@code properties}：空 map 而不是 null——表里那列不为 null，读回来才不用判空。 */
    private static Map<String, String> propertiesOf(Map<String, String> properties) {
        return properties == null ? new LinkedHashMap<>() : new LinkedHashMap<>(properties);
    }

    /**
     * {@code location} → {@code table_uri}。
     *
     * <p>{@code file:} 补 scheme（裸路径不是合法 URI，客户端拿它拼 URL 会得到
     * 一个相对路径）；{@code s3://} 之类原样返回。
     */
    private static String tableUri(String location) {
        if (location == null || location.isBlank()) {
            return null;
        }
        if (location.contains("://")) {
            return location;
        }
        return "file://" + (location.startsWith("/") ? location : "/" + location);
    }

    private static String mode(String mode) {
        return mode == null || mode.isBlank() ? "create" : mode.trim().toLowerCase(Locale.ROOT);
    }

    private static String sep(String delimiter) {
        return delimiter == null || delimiter.isEmpty() ? LanceIds.DEFAULT_DELIMITER : delimiter;
    }
}
