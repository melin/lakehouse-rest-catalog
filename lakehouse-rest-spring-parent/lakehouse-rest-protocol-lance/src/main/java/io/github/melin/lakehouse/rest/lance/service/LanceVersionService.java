package io.github.melin.lakehouse.rest.lance.service;

import io.github.melin.lakehouse.rest.config.RequestContext;
import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.domain.entity.DatabaseEntity;
import io.github.melin.lakehouse.rest.domain.entity.LanceTableVersionEntity;
import io.github.melin.lakehouse.rest.domain.entity.TableEntity;
import io.github.melin.lakehouse.rest.domain.repo.LanceTableVersionRepository;
import io.github.melin.lakehouse.rest.domain.repo.TableRepository;
import io.github.melin.lakehouse.rest.lance.dto.LanceDtos;
import io.github.melin.lakehouse.rest.lance.support.LanceIds;
import io.github.melin.lakehouse.rest.support.LanceErrors;
import io.github.melin.lakehouse.rest.support.Paging;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 表版本：P1 的**只读**两个 operation 与 P2 的**写**两个 operation。
 *
 * <p><b>两个来源，一条合并规则。</b>版本有两处可问：
 *
 * <ul>
 *   <li><b>存储</b>——{@code _versions/} 下的 manifest 文件（Lance 原生版本管理，
 *       绝大多数表是这种）；</li>
 *   <li><b>我们的库</b>——{@code managed_versioning=true} 时客户端用
 *       {@code CreateTableVersion} 记的账。</li>
 * </ul>
 *
 * 列举时两者**并集**、按版本号去重、托管记录优先（它带着我们记的 metadata）。
 * 这不是「兜底」，而是两种版本管理确实可能同时存在：客户端开了托管之后，
 * 目录里可能还留着切换之前写下的原生 manifest。
 *
 * <p><b>写端点只动版本账，不动数据。</b>{@code CreateTableVersion} 记下
 * 「第 N 版在哪个文件」，{@code BatchDeleteTableVersions} 删掉这些记账行——
 * 规范对后者的说明也是这一句：它删的是 version records，不是表数据。
 *
 * <p><b>{@code BatchCreateTableVersions} 没做，仍然 406。</b>它的路径是
 * {@code /lance/v1/table/version/batch-create}——**没有表标识**，因此授权层
 * （{@code LanceAccessRules}）无法从路径里定位 catalog 与表。本面的规则是
 * 「未登记即拒绝」，而这条路径连「登记在哪张表上」都答不出，
 * 给它开口子就是给所有已认证的主体开一个跨表写权限。宁可 406 说不做。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LanceVersionService {

    /** 托管版本的开关写在表的透传属性里（{@code declare} / {@code register} 时给定）。 */
    public static final String MANAGED_VERSIONING_PROPERTY = "lance.managed_versioning";

    private final LanceTableVersionRepository versionRepository;
    private final TableRepository tableRepository;
    private final LanceCatalogSupport support;

    // ------------------------------------------------------------------ 读（P1）

    /**
     * {@code ListTableVersions}：列表 + 分页 + 排序方向。
     *
     * @param descending {@code true} 时**保证**降序（规范措辞是
     *                   "guaranteed to be returned in descending order"）；
     *                   {@code false} 时按版本号升序（我们选升序，
     *                   因为「第一个版本是什么」比「最新是什么」更需要确定答案）
     */
    @Transactional(readOnly = true)
    public LanceDtos.ListTableVersionsResponse list(String id, String delimiter, Integer limit,
                                                    String pageToken, boolean descending) {
        Resolved resolved = resolve(id, delimiter);
        Map<Long, LanceDtos.TableVersion> managed = new LinkedHashMap<>();
        for (LanceTableVersionEntity row
                : versionRepository.findAllByTableIdOrderByVersionAsc(resolved.table().getId())) {
            managed.put(row.getVersion(), of(row));
        }
        List<LanceDtos.TableVersion> versions = new ArrayList<>();
        for (LanceCatalogSupport.VersionFile file : support.versions(resolved.catalog(), resolved.table().getPath())) {
            if (managed.containsKey(file.version())) {
                continue;
            }
            versions.add(new LanceDtos.TableVersion(file.version(), file.path(), file.size(),
                    null, file.timestampMillis(), Map.of()));
        }
        versions.addAll(managed.values());
        versions.sort(Comparator.comparingLong(LanceDtos.TableVersion::version));
        if (descending) {
            versions.sort(Comparator.comparingLong(LanceDtos.TableVersion::version).reversed());
        }
        int size = Paging.pageSize(limit, 100, 1000);
        Paging.Slice<LanceDtos.TableVersion> slice = Paging.slice(versions, Paging.offset(pageToken), size);
        return new LanceDtos.ListTableVersionsResponse(slice.items(), slice.nextPageToken());
    }

    /** {@code DescribeTableVersion}：托管记录优先，其次按文件名探测存储。 */
    @Transactional(readOnly = true)
    public LanceDtos.DescribeTableVersionResponse describe(String id, String delimiter,
                                                            LanceDtos.DescribeTableVersionRequest request) {
        Resolved resolved = resolve(id, delimiter);
        long version = requireVersion(request == null ? null : request.version());
        Optional<LanceTableVersionEntity> row =
                versionRepository.findByTableIdAndVersion(resolved.table().getId(), version);
        if (row.isPresent()) {
            return new LanceDtos.DescribeTableVersionResponse(of(row.get()));
        }
        LanceCatalogSupport.VersionFile file =
                support.probeVersion(resolved.catalog(), resolved.table().getPath(), version);
        if (file == null) {
            throw LanceErrors.tableVersionNotFound(resolved.id().join(resolved.sep()), version);
        }
        return new LanceDtos.DescribeTableVersionResponse(
                new LanceDtos.TableVersion(file.version(), file.path(), file.size(),
                        null, file.timestampMillis(), Map.of()));
    }

    // ------------------------------------------------------------------ 写（P2）

    /**
     * {@code CreateTableVersion}：{@code put_if_not_exists} 语义。
     *
     * <p><b>三重校验，缺一不可：</b>
     *
     * <ol>
     *   <li>表必须开了 {@code managed_versioning}——否则客户端的写入路径不依赖我们，
     *       记账没有意义，而一旦记了就会与原生 manifest 冲突；</li>
     *   <li>{@code manifest_path} 要过 {@code allowedLocations}（与 {@code declare} 同一条白名单）；</li>
     *   <li>manifest 要**真的在**——这是弱校验（探不到存储时报 503 而不是放行），
     *       因为我们接下来要向客户端承诺「第 N 版存在」。</li>
     * </ol>
     *
     * <p><b>重复版本号回 409。</b>先查一次是为了给出清晰的文案；真正的保证是
     * {@code (table_id, version)} 的唯一约束——并发下两个请求都可能通过那次查询，
     * 只有唯一约束能让其中一个失败。因此 {@code DataIntegrityViolationException}
     * 必须被翻译成 {@code ConcurrentModification}，而不是透成 500。
     */
    @Transactional
    public LanceDtos.CreateTableVersionResponse create(String id, String delimiter,
                                                       LanceDtos.CreateTableVersionRequest request) {
        Resolved resolved = resolve(id, delimiter);
        long version = requireVersion(request == null ? null : request.version());
        if (request == null || request.manifestPath() == null || request.manifestPath().isBlank()) {
            throw LanceErrors.invalidInput("manifest_path is required");
        }
        TableEntity table = resolved.table();
        if (!table.isManagedVersioning()) {
            throw LanceErrors.invalidTableState("table \"" + resolved.id().join(resolved.sep())
                    + "\" is not managed-versioning; declare or register it with the property \""
                    + MANAGED_VERSIONING_PROPERTY + "\" set to true");
        }
        support.requireAllowedLocation(resolved.catalog(), request.manifestPath());
        Boolean present = support.manifestExists(resolved.catalog(), request.manifestPath());
        if (Boolean.FALSE.equals(present)) {
            throw LanceErrors.invalidInput("no manifest at \"" + request.manifestPath() + "\";"
                    + " the manifest must be written before the version is recorded");
        }
        if (present == null) {
            // 探不到存储 ≠ 文件不存在：把存储抖动报成 400 会让客户端以为自己写错了路径
            throw LanceErrors.serviceUnavailable(
                    "cannot verify the manifest at \"" + request.manifestPath() + "\"");
        }
        if (versionRepository.findByTableIdAndVersion(table.getId(), version).isPresent()) {
            throw LanceErrors.concurrentModification(resolved.id().join(resolved.sep()), version);
        }
        LanceTableVersionEntity row = new LanceTableVersionEntity();
        row.setId(Paging.newId());
        row.setTableId(table.getId());
        row.setCatalogId(table.getCatalogId());
        row.setVersion(version);
        row.setManifestPath(request.manifestPath());
        row.setManifestSize(request.manifestSize());
        row.setETag(request.eTag());
        row.setMetadataJson(request.metadata() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(request.metadata()));
        row.markCreated(RequestContext.auditPrincipal(), RequestContext.now());
        try {
            versionRepository.save(row);
        } catch (DataIntegrityViolationException e) {
            throw LanceErrors.concurrentModification(resolved.id().join(resolved.sep()), version);
        }
        return new LanceDtos.CreateTableVersionResponse(of(row));
    }

    /**
     * {@code BatchDeleteTableVersions}：按区间删**版本账**。
     *
     * <p>{@code end_version = -1} 是「到最新为止（含）」；区间是
     * {@code [start, end)}。区间为空（{@code end <= start}）时删 0 条而不是报错——
     * 它是「这段里没有」而不是「你写错了」，客户端按版本滚动清理时经常撞上。
     *
     * <p><b>原生版本管理的表没有账可删</b>，返回 {@code deleted_count = 0} 而不是 409：
     * 这一条删不到任何东西是事实，不是错误。返回 0 反而把「我们不管你的版本」
     * 这个事实告诉了调用方。
     */
    @Transactional
    public LanceDtos.BatchDeleteTableVersionsResponse delete(String id, String delimiter,
                                                             LanceDtos.BatchDeleteTableVersionsRequest request) {
        Resolved resolved = resolve(id, delimiter);
        if (request == null || request.ranges() == null || request.ranges().isEmpty()) {
            throw LanceErrors.invalidInput("ranges is required");
        }
        long deleted = 0;
        for (LanceDtos.VersionRange range : request.ranges()) {
            if (range == null || range.startVersion() == null || range.endVersion() == null) {
                throw LanceErrors.invalidInput("each range needs start_version and end_version");
            }
            long start = range.startVersion();
            long end = range.endVersion();
            if (start < 0) {
                throw LanceErrors.invalidInput("start_version must not be negative: " + start);
            }
            if (end < -1) {
                throw LanceErrors.invalidInput("end_version must be -1 (all) or non-negative: " + end);
            }
            if (end >= 0 && end < start) {
                throw LanceErrors.invalidInput("start_version " + start + " exceeds end_version " + end);
            }
            Long upper = end == -1 ? null : end;
            deleted += versionRepository.deleteRange(resolved.table().getId(), start, upper);
        }
        return new LanceDtos.BatchDeleteTableVersionsResponse(deleted);
    }

    /**
     * 清掉一张表的全部版本账（{@code deregister} 时用）。
     *
     * <p>外部事务里被调用，因此默认传播即可：登记与它的版本账要么一起没，
     * 要么一起留。分成两个事务会留下「表已删、账还在」的中间态。
     */
    @Transactional
    public void deleteVersionsOfTable(String tableId) {
        versionRepository.deleteByTableId(tableId);
    }

    // ------------------------------------------------------------------ 缓存（P1·L1-4）

    /**
     * 把最近读到的版本号写进 {@code lakehouse_table.table_version}。
     *
     * <p><b>独立事务（{@code REQUIRES_NEW}）而不是跟着读事务提交。</b>读版本号的
     * 事务是 {@code readOnly} 的，缓存写要能单独成功或单独失败；
     * 挂起外层事务再开一个是唯一不需要改读事务语义的做法。
     *
     * <p><b>调用方必须接住它的异常</b>：缓存写失败（行被删、数据库抖动）
     * 不能让「读 schema」这个请求失败——调用方要的是 schema 与位置。
     * 这里不自己吞，是为了让「谁负责忽略」这件事留在调用处，看得见。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cacheLatestVersion(String tableId, long version) {
        tableRepository.findById(tableId).ifPresent(table -> {
            table.setTableVersion(version);
            table.touch(RequestContext.auditPrincipal(), RequestContext.now());
            tableRepository.save(table);
        });
    }

    // ------------------------------------------------------------------ 内部

    /** 一次 resolve 的结果；版本端点的四个 operation 都要同一套定位。 */
    private record Resolved(LanceIds.Id id, String sep, CatalogEntity catalog,
                            DatabaseEntity database, TableEntity table) {
    }

    private Resolved resolve(String id, String delimiter) {
        LanceIds.Id parsee = LanceIds.table(id, delimiter);
        String separator = LanceCatalogSupport.sep(delimiter);
        CatalogEntity catalog = support.requireCatalog(parsee.catalog());
        DatabaseEntity database = support.requireDatabase(catalog, parsee, delimiter);
        TableEntity table = support.requireTable(catalog, database, parsee, delimiter);
        return new Resolved(parsee, separator, catalog, database, table);
    }

    /** 版本号：必填、非负。三个要版本号的端点共用这一条校验。 */
    private static long requireVersion(Long version) {
        if (version == null) {
            throw LanceErrors.invalidInput("version is required");
        }
        if (version < 0) {
            throw LanceErrors.invalidInput("version must not be negative: " + version);
        }
        return version;
    }

    private static LanceDtos.TableVersion of(LanceTableVersionEntity row) {
        return new LanceDtos.TableVersion(row.getVersion(), row.getManifestPath(), row.getManifestSize(),
                row.getETag(), row.getCreatedAt(),
                row.getMetadataJson() == null ? Map.of() : new LinkedHashMap<>(row.getMetadataJson()));
    }

    /** {@code declare} / {@code register} 时把透传属性里的开关落到列上。 */
    public static boolean managedVersioningOf(Map<String, String> properties) {
        if (properties == null) {
            return false;
        }
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            if (MANAGED_VERSIONING_PROPERTY.equalsIgnoreCase(entry.getKey())) {
                return Boolean.parseBoolean(entry.getValue().trim().toLowerCase(Locale.ROOT));
            }
        }
        return false;
    }
}
