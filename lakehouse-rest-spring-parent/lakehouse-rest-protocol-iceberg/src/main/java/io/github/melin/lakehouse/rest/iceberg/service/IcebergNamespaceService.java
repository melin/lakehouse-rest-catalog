package io.github.melin.lakehouse.rest.iceberg.service;

import io.github.melin.lakehouse.rest.support.LineageRefs;
import io.github.melin.lakehouse.rest.config.RequestContext;
import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.domain.entity.DatabaseEntity;
import io.github.melin.lakehouse.rest.domain.repo.DatabaseRepository;
import io.github.melin.lakehouse.rest.domain.repo.FunctionRepository;
import io.github.melin.lakehouse.rest.domain.repo.SemanticViewRepository;
import io.github.melin.lakehouse.rest.domain.repo.TableRepository;
import io.github.melin.lakehouse.rest.domain.repo.ViewRepository;
import io.github.melin.lakehouse.rest.event.CatalogEvent;
import io.github.melin.lakehouse.rest.event.CatalogEventPublisher;
import io.github.melin.lakehouse.rest.event.CatalogEventType;
import io.github.melin.lakehouse.rest.iceberg.dto.IcebergDtos;
import io.github.melin.lakehouse.rest.service.CatalogService;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.CatalogFormat;
import io.github.melin.lakehouse.rest.support.NamespacePaths;
import io.github.melin.lakehouse.rest.support.Paging;
import io.github.melin.lakehouse.rest.support.Paths;
import io.github.melin.lakehouse.rest.support.ResourceType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Iceberg 的 namespace（命名空间）端点。
 *
 * <p><b>namespace ↔ database 是同一个东西的两个名词。</b>Iceberg 侧的多级 namespace
 * 直接落进 {@code lakehouse_database.name}（点号拼接，见
 * {@link NamespacePaths}），因此本类与 Paimon 面的 {@code DatabaseService} 做的是
 * 同一件事，只是对外名词与层级形状不同。两者**不共用实现**：协议面之间不能互相依赖
 * （enforcer 守着），而且共用会立刻带进「谁的行为是对的」这类争论——
 * 例如 Paimon 的 {@code drop} 是级联删表，Iceberg 的规格要求非空时拒绝。
 *
 * <p><b>删除不做级联。</b>规格要求 namespace 非空时回 409
 * （{@code NamespaceNotEmptyException}），本服务端遵循规格。这与 Paimon 面的
 * {@code DELETE /databases/{db}} 的行为不同，是**刻意保留的差异**：
 * Iceberg 客户端拿到 409 后的动作是「先把表删掉」，而静默级联会让它以为删完了、
 * 实际上数据文件成了孤儿。
 */
@Service
@RequiredArgsConstructor
public class IcebergNamespaceService {

    private final DatabaseRepository databaseRepository;
    private final TableRepository tableRepository;
    private final ViewRepository viewRepository;
    private final FunctionRepository functionRepository;
    private final SemanticViewRepository semanticViewRepository;
    private final CatalogService catalogService;
    private final RestServerProperties properties;
    private final CatalogEventPublisher events;

    // ------------------------------------------------------------------ 列举

    /**
     * {@code GET /v1/{prefix}/namespaces}（可选 {@code ?parent=}）。
     *
     * <p><b>层级过滤在内存里做。</b>库里存的是点号拼接的平表，没有 parent 列，
     * 因此「某层的直接子级」只能取全量再筛。这是本方案明确接受的取舍
     * （见设计文档 §4.6）：收益是零改动就能表达多级 namespace，
     * 代价是 namespace 数量很大时这一条列举不够快。真到那一步的解法是给
     * {@code lakehouse_database} 加一列 {@code parent_key}，而不是改存储形态。
     */
    @Transactional(readOnly = true)
    public IcebergDtos.ListNamespacesResponse list(String prefix, List<String> parent,
                                                   Integer pageSize, String pageToken, Integer page ) {
        CatalogEntity catalog = resolve(prefix);
        List<String> parentLevels = parent == null ? List.of() : parent;
        List<List<String>> namespaceList = new ArrayList<>();
        for (DatabaseEntity database : databaseRepository.findAllByCatalogIdOrderByNameAsc(catalog.getId())) {
            List<String> levels = NamespacePaths.fromStorageName(database.getName());
            if (!isDirectChild(parentLevels, levels)) {
                continue;
            }
            namespaceList.add(levels);
        }
        // 页码模式（控制台表格）：切页并带回总数；协议里的 pageToken 翻页保持原样。
        Paging.Paged<List<String>> paged = Paging.paged(namespaceList, page, pageSize);
        if (paged != null) {
            return new IcebergDtos.ListNamespacesResponse(paged.items(), null, (int) paged.meta().total());
        }
        Paging.Slice<List<String>> slice = Paging.slice(namespaceList, Paging.offset(pageToken),
                Paging.pageSize(pageSize, properties.getDefaultPageSize(), properties.getMaxPageSize()));
        return new IcebergDtos.ListNamespacesResponse(slice.items(), slice.nextPageToken(), null);
    }

    /** {@code levels} 是否是 {@code parentLevels} 的直接子级（比它正好深一层）。 */
    private static boolean isDirectChild(List<String> parentLevels, List<String> levels) {
        if (levels.size() != parentLevels.size() + 1) {
            return false;
        }
        for (int index = 0; index < parentLevels.size(); index++) {
            if (!parentLevels.get(index).equals(levels.get(index))) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ 创建与读取

    @Transactional
    public IcebergDtos.CreateNamespaceResponse create(String prefix, IcebergDtos.CreateNamespaceRequest request) {
        if (request == null || request.namespace() == null || request.namespace().isEmpty()) {
            throw ApiException.badRequest("namespace must not be empty");
        }
        List<String> levels = normalized(request.namespace());
        // 多级 namespace 用点号拼进血缘的 ref，`~` 在任意一级出现都会让整条路径变成外部节点
        for (String level : levels) {
            LineageRefs.requireNotReserved(level, "namespace");
        }
        CatalogEntity catalog = resolve(prefix);
        String name = NamespacePaths.toStorageName(levels);
        if (databaseRepository.existsByCatalogIdAndName(catalog.getId(), name)) {
            throw namespaceAlreadyExists(name);
        }
        DatabaseEntity database = new DatabaseEntity();
        database.setId(Paging.newId());
        database.setCatalogId(catalog.getId());
        database.setName(name);
        database.setOptions(request.properties() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(request.properties()));
        // location 用拼接名，与 Paimon 面的 DatabaseService 同源（同一个 Paths 模板）
        database.setLocation(Paths.databaseLocation(catalog.getWarehouse(), name));
        database.markCreated(RequestContext.principal(), RequestContext.now());
        databaseRepository.save(database);
        events.publish(CatalogEventType.NAMESPACE_CREATED, prefix, CatalogEvent.TARGET_NAMESPACE,
                name, Map.of("format", "ICEBERG", "levels", levels.size()));
        return new IcebergDtos.CreateNamespaceResponse(levels, optionsOf(database));
    }

    @Transactional(readOnly = true)
    public IcebergDtos.GetNamespaceResponse load(String prefix, List<String> levels) {
        DatabaseEntity database = require(prefix, levels);
        return new IcebergDtos.GetNamespaceResponse(
                NamespacePaths.fromStorageName(database.getName()), optionsOf(database));
    }

    /** {@code HEAD /v1/{prefix}/namespaces/{ns}}：存在返回 200（空体），不存在返回 404。 */
    @Transactional(readOnly = true)
    public void requireExists(String prefix, List<String> levels) {
        require(prefix, levels);
    }

    /**
     * {@code POST .../namespaces/{ns}/properties}：一次批量增删。
     *
     * <p>规格把 {@code updates} 与 {@code removals} 定为必填，因此 {@code null} 按空处理
     * 而不是报错——客户端送 {@code []} 与不送在语义上都是「这一侧不改」。
     */
    @Transactional
    public IcebergDtos.UpdateNamespacePropertiesResponse updateProperties(
            String prefix, List<String> levels, IcebergDtos.UpdateNamespacePropertiesRequest request) {
        DatabaseEntity database = require(prefix, levels);
        Map<String, String> options = new LinkedHashMap<>(database.getOptions());
        List<String> updated = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        if (request != null && request.updates() != null) {
            request.updates().forEach((key, value) -> {
                if (key != null) {
                    options.put(key, value);
                    updated.add(key);
                }
            });
        }
        if (request != null && request.removals() != null) {
            for (String key : request.removals()) {
                if (key == null) {
                    continue;
                }
                if (options.remove(key) != null) {
                    removed.add(key);
                } else {
                    missing.add(key);
                }
            }
        }
        database.setOptions(options);
        database.touch(RequestContext.principal(), RequestContext.now());
        databaseRepository.save(database);
        if (!updated.isEmpty() || !removed.isEmpty()) {
            events.publish(CatalogEventType.NAMESPACE_UPDATED, prefix,
                    CatalogEvent.TARGET_NAMESPACE, database.getName(),
                    Map.of("format", "ICEBERG", "updated", updated, "removed", removed));
        }
        return new IcebergDtos.UpdateNamespacePropertiesResponse(updated, removed, missing);
    }

    // ------------------------------------------------------------------ 删除

    /**
     * {@code DELETE /v1/{prefix}/namespaces/{ns}}：非空时 409。
     *
     * <p>把五种从属对象都数一遍，而不是只数表：Iceberg 客户端删 namespace 之前只会
     * 删它自己知道的那些对象，而通过 Paimon 面或控制台建在这个 namespace 下的
     * 视图 / 函数 / 语义视图同样会让「删掉 namespace」变成一次静默的数据丢失。
     */
    @Transactional
    public void drop(String prefix, List<String> levels) {
        DatabaseEntity database = require(prefix, levels);
        String occupied = firstOccupant(database);
        if (occupied != null) {
            // 名字用库里的点号拼接形式：多级 namespace 在消息里写成 accounting.tax，
            // 与控制台、与 Spark SQL 的三段名读法一致；URL 那套分隔符（0x1F）不可读
            String name = database.getName();
            throw new ApiException(409, null, name,
                    "Namespace " + name + " is not empty: it still holds " + occupied,
                    "NamespaceNotEmptyException");
        }
        databaseRepository.delete(database);
        events.publish(CatalogEventType.NAMESPACE_DROPPED, prefix, CatalogEvent.TARGET_NAMESPACE,
                database.getName(), Map.of("format", "ICEBERG"));
    }

    /** 该 namespace 下第一个非空的从属对象类型；全空返回 {@code null}。 */
    private String firstOccupant(DatabaseEntity database) {
        if (!tableRepository.findAllByDatabaseIdOrderByNameAsc(database.getId()).isEmpty()) {
            return "tables";
        }
        if (!viewRepository.findAllByDatabaseIdOrderByNameAsc(database.getId()).isEmpty()) {
            return "views";
        }
        if (!functionRepository.findAllByDatabaseIdOrderByNameAsc(database.getId()).isEmpty()) {
            return "functions";
        }
        if (!semanticViewRepository.findAllByDatabaseIdOrderByNameAsc(database.getId()).isEmpty()) {
            return "semantic views";
        }
        return null;
    }

    /**
     * 按层级取 database，不存在时抛 404。
     *
     * <p>404 的资源类型是 {@code DATABASE}，于是 Iceberg 形状的错误类型是
     * {@code NoSuchNamespaceException}——这是 Spark 侧识别得最准的错误之一。
     */
    @Transactional(readOnly = true)
    public DatabaseEntity require(String prefix, List<String> levels) {
        return require(resolve(prefix), levels);
    }

    /**
     * 同上，但由调用方给出已解析的 catalog。
     *
     * <p>表 / 视图服务在一个方法里既要 catalog（拿 warehouse、拿 catalogId 去写仓库）又要
     * database，走这个重载就不必把 prefix 查两遍。两个入口的校验完全一致——
     * 它只是把「解析 prefix」这一步挪到了调用方。
     */
    @Transactional(readOnly = true)
    public DatabaseEntity require(CatalogEntity catalog, List<String> levels) {
        if (levels == null || levels.isEmpty()) {
            throw ApiException.badRequest("namespace must not be empty");
        }
        String name = NamespacePaths.toStorageName(levels);
        return databaseRepository.findByCatalogIdAndName(catalog.getId(), name)
                .orElseThrow(() -> namespaceNotExist(name));
    }

    /** 解析 prefix 并确认它确实是一个 Iceberg catalog（见 {@code CatalogService#resolveForFormat}）。 */
    public CatalogEntity resolve(String prefix) {
        return catalogService.resolveForFormat(prefix, CatalogFormat.ICEBERG);
    }

    // ------------------------------------------------------------------ 辅助

    /**
     * 归一化层级：去掉空串与首尾空白。
     *
     * <p>空白层级必须挡在建库之前：{@code ["a", ""]} 拼出来的名字是 {@code "a."}，
     * 而按它反解回来的层级是 {@code ["a"]}——同一个 namespace 有了两个名字，
     * 之后按其中一个去查永远找不到另一个。宁可 400。
     */
    private static List<String> normalized(List<String> levels) {
        List<String> result = new ArrayList<>();
        for (String level : levels) {
            if (level == null || level.isBlank()) {
                throw ApiException.badRequest("namespace levels must not be blank: " + levels);
            }
            result.add(level.trim());
        }
        if (result.isEmpty()) {
            throw ApiException.badRequest("namespace must not be empty");
        }
        return result;
    }

    private Map<String, String> optionsOf(DatabaseEntity database) {
        return database.getOptions() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(database.getOptions());
    }

    private static ApiException namespaceNotExist(String name) {
        return new ApiException(404, ResourceType.DATABASE, name,
                "The given namespace does not exist: " + name, "NoSuchNamespaceException");
    }

    private static ApiException namespaceAlreadyExists(String name) {
        return new ApiException(409, ResourceType.DATABASE, name,
                "The given namespace already exists: " + name, "AlreadyExistsException");
    }
}
