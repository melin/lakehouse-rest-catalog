package io.github.melin.lakehouse.rest.lance.service;

import io.github.melin.lakehouse.rest.config.RequestContext;
import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.domain.entity.DatabaseEntity;
import io.github.melin.lakehouse.rest.domain.repo.DatabaseRepository;
import io.github.melin.lakehouse.rest.domain.repo.TableRepository;
import io.github.melin.lakehouse.rest.event.CatalogEvent;
import io.github.melin.lakehouse.rest.event.CatalogEventPublisher;
import io.github.melin.lakehouse.rest.event.CatalogEventType;
import io.github.melin.lakehouse.rest.lance.dto.LanceDtos;
import io.github.melin.lakehouse.rest.lance.support.LanceIds;
import io.github.melin.lakehouse.rest.support.LanceErrors;
import io.github.melin.lakehouse.rest.support.Paging;
import io.github.melin.lakehouse.rest.support.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lance 的 namespace 端点（5 个 operation：create / list / describe / drop / exists）。
 *
 * <p><b>两级命名空间，与我们的三层一一对应</b>：{@code catalog} 是第一级、
 * {@code schema} 是第二级，表只能挂在第二级下。这让本面不需要任何嵌套或
 * 扁平化的技巧——{@code catalog$schema} 直接落 {@code lakehouse_database.name}。
 *
 * <p><b>第一级（catalog）上的写操作一律 406。</b>建 catalog 要决定 warehouse 与存储配置，
 * 那是管理 API 的职责；让 Lance 客户端创建 catalog 等于允许它绕过
 * {@code allowedLocations}。见 {@code docs/lance-rest-catalog-design.md} 决策 D2。
 *
 * <p><b>删除只支持 {@code Restrict}</b>：非空回 {@code NamespaceNotEmpty(3)}。
 * {@code Cascade} 回 {@code 406}——规范自己说 {@code Restrict} 就够，而且递归删
 * 会把一次 HTTP 调用变成可能持续几分钟的批量删对象存储，删的还是**我们从不写的目录**。
 */
@Service
@RequiredArgsConstructor
public class LanceNamespaceService {

    private final DatabaseRepository databaseRepository;
    private final TableRepository tableRepository;
    private final LanceCatalogSupport support;
    private final CatalogEventPublisher events;

    // ------------------------------------------------------------------ create

    /**
     * {@code POST /lance/v1/namespace/{id}/create}。
     *
     * @param mode {@code create}（默认，已存在则 409）/ {@code exist_ok}（已存在返回 200）
     *              / {@code overwrite}（重置属性，**不动数据**）
     */
    @Transactional
    public LanceDtos.CreateNamespaceResponse create(String id, String delimiter, String mode,
                                                    LanceDtos.CreateNamespaceRequest request) {
        LanceIds.Id parsee = LanceIds.namespace(id, delimiter);
        if (request != null) {
            LanceIds.requireSameAsBody(id, request.id(), delimiter);
        }
        if (parsee.levels().size() == 1) {
            throw LanceErrors.unsupported("CreateNamespace at the catalog level");
        }
        String schema = parsee.schema();
        CatalogEntity catalog = support.requireCatalog(parsee.catalog());
        Map<String, String> properties = request == null || request.properties() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(request.properties());

        DatabaseEntity existing = databaseRepository
                .findByCatalogIdAndName(catalog.getId(), schema).orElse(null);
        if (existing != null) {
            return switch (normalizeMode(mode)) {
                case "exist_ok" -> new LanceDtos.CreateNamespaceResponse(
                        parsee.join(delimiter), optionsOf(existing));
                case "overwrite" -> {
                    existing.setOptions(properties);
                    existing.touch(RequestContext.auditPrincipal(), RequestContext.now());
                    databaseRepository.save(existing);
                    yield new LanceDtos.CreateNamespaceResponse(parsee.join(delimiter), optionsOf(existing));
                }
                default -> throw LanceErrors.namespaceAlreadyExists(parsee.join(delimiter));
            };
        }

        DatabaseEntity database = new DatabaseEntity();
        database.setId(Paging.newId());
        database.setCatalogId(catalog.getId());
        database.setName(schema);
        database.setOptions(properties);
        database.setLocation(Paths.databaseLocation(catalog.getWarehouse(), schema));
        database.markCreated(RequestContext.auditPrincipal(), RequestContext.now());
        databaseRepository.save(database);
        events.publish(CatalogEventType.NAMESPACE_CREATED, parsee.catalog(),
                CatalogEvent.TARGET_NAMESPACE, schema, Map.of("format", "LANCE"));
        return new LanceDtos.CreateNamespaceResponse(parsee.join(delimiter), optionsOf(database));
    }

    // ------------------------------------------------------------------ list

    /**
     * {@code GET /lance/v1/namespace/{id}/list}。
     *
     * <p><b>回的是子级名字，不是完整 id，也不是对象。</b>规范那句
     * 「child namespaces relative to the parent namespace {@code id}」就是字面意思：
     * 父级是 catalog 时给 schema 名，客户端自己拼回路径（Spark 靠
     * {@code spark.sql.catalog.<c>.parent} 把 catalog 补回去）。
     *
     * <p>第二级（schema）之下没有子命名空间，因此 {@code id} 有两段时回空列表，
     * 而不是「把它当前缀去匹配」——后者会把 {@code sales} 与 {@code sales_eu} 混在一起。
     */
    @Transactional(readOnly = true)
    public LanceDtos.ListNamespacesResponse list(String id, String delimiter, Integer limit, String pageToken) {
        LanceIds.Id parsee = LanceIds.namespace(id, delimiter);
        CatalogEntity catalog = support.requireCatalog(parsee.catalog());
        List<String> namespaces = new ArrayList<>();
        if (parsee.levels().size() == 1) {
            for (DatabaseEntity database : databaseRepository.findAllByCatalogIdOrderByNameAsc(catalog.getId())) {
                namespaces.add(database.getName());
            }
        }
        int size = Paging.pageSize(limit, 100, 1000);
        Paging.Slice<String> slice = Paging.slice(namespaces, Paging.offset(pageToken), size);
        return new LanceDtos.ListNamespacesResponse(slice.items(), slice.nextPageToken());
    }

    // ------------------------------------------------------------------ describe / exists

    @Transactional(readOnly = true)
    public LanceDtos.DescribeNamespaceResponse describe(String id, String delimiter) {
        LanceIds.Id parsee = LanceIds.namespace(id, delimiter);
        CatalogEntity catalog = support.requireCatalog(parsee.catalog());
        if (parsee.levels().size() == 1) {
            return new LanceDtos.DescribeNamespaceResponse(parsee.catalog(), catalogProperties(catalog));
        }
        DatabaseEntity database = requireDatabase(catalog, parsee, delimiter);
        return new LanceDtos.DescribeNamespaceResponse(parsee.join(delimiter), optionsOf(database));
    }

    /** {@code POST .../exists}：不存在即 404，与 Paimon 面「返回 bool」不同，照规范。 */
    @Transactional(readOnly = true)
    public void requireExists(String id, String delimiter) {
        describe(id, delimiter);
    }

    // ------------------------------------------------------------------ drop

    /**
     * {@code POST /lance/v1/namespace/{id}/drop}。
     *
     * <p><b>{@code overwrite} 之外的两个模式只清我们这层的元数据。</b>
     * 命名空间属性（location、comment）只是我们 DB 里的字段，
     * 调 {@code overwrite} 成功不代表数据集被动过——这一点客户端最容易误解。
     *
     * @param mode     query 上的 {@code ?mode=}（控制台/脚本用）
     * @param behavior body 里的 {@code behavior}（规范定义的位置，Spark connector 用）
     */
    @Transactional
    public void drop(String id, String delimiter, String mode, String behavior) {
        LanceIds.Id parsee = LanceIds.namespace(id, delimiter);
        if (parsee.levels().size() == 1) {
            throw LanceErrors.unsupported("DropNamespace at the catalog level");
        }
        if ("cascade".equals(normalizeMode(mode)) || "cascade".equals(normalizeMode(behavior))) {
            throw LanceErrors.unsupported("DropNamespace with mode=cascade");
        }
        CatalogEntity catalog = support.requireCatalog(parsee.catalog());
        DatabaseEntity database = requireDatabase(catalog, parsee, delimiter);
        if (!tableRepository.findAllByDatabaseIdOrderByNameAsc(database.getId()).isEmpty()) {
            throw LanceErrors.namespaceNotEmpty(parsee.join(delimiter));
        }
        databaseRepository.delete(database);
        events.publish(CatalogEventType.NAMESPACE_DROPPED, parsee.catalog(),
                CatalogEvent.TARGET_NAMESPACE, database.getName(), Map.of("format", "LANCE"));
    }

    // ------------------------------------------------------------------ 内部

    private DatabaseEntity requireDatabase(CatalogEntity catalog, LanceIds.Id parsee, String delimiter) {
        return databaseRepository.findByCatalogIdAndName(catalog.getId(), parsee.schema())
                .orElseThrow(() -> LanceErrors.namespaceNotFound(parsee.join(delimiterOr(delimiter))));
    }

    private static Map<String, String> optionsOf(DatabaseEntity database) {
        return database.getOptions() == null ? Map.of() : new LinkedHashMap<>(database.getOptions());
    }

    private static Map<String, String> catalogProperties(CatalogEntity catalog) {
        return catalog.getProperties() == null ? Map.of() : new LinkedHashMap<>(catalog.getProperties());
    }

    private static String normalizeMode(String mode) {
        return mode == null || mode.isBlank() ? "create" : mode.trim().toLowerCase(Locale.ROOT);
    }

    private static String delimiterOr(String delimiter) {
        return delimiter == null || delimiter.isEmpty() ? LanceIds.DEFAULT_DELIMITER : delimiter;
    }
}
