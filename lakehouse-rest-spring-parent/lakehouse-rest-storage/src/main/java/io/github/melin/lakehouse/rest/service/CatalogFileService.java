package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.domain.entity.DatabaseEntity;
import io.github.melin.lakehouse.rest.domain.entity.TableEntity;
import io.github.melin.lakehouse.rest.domain.repo.CatalogRepository;
import io.github.melin.lakehouse.rest.domain.repo.DatabaseRepository;
import io.github.melin.lakehouse.rest.domain.repo.TableRepository;
import io.github.melin.lakehouse.rest.dto.ConsoleDtos.CatalogFileEntry;
import io.github.melin.lakehouse.rest.dto.ConsoleDtos.CatalogFileListing;
import io.github.melin.lakehouse.rest.dto.ConsoleDtos.TableFileListing;
import io.github.melin.lakehouse.rest.service.DataPolicyService;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.PageMeta;
import io.github.melin.lakehouse.rest.support.Paging;
import io.github.melin.lakehouse.rest.support.ResourceType;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.paimon.fs.FileIO;
import org.apache.paimon.fs.FileStatus;
import org.apache.paimon.fs.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 浏览 catalog 仓库的物理目录——两种根：仓库根（catalog 视角）与表目录（单表视角）。
 *
 * <p><b>为什么要有这个能力。</b>REST 协议的读路径全部经过本服务端（元数据在库里），
 * 于是「用 REST catalog 一切正常、绕开服务端看仓库却不对」这类问题在接口上完全看不见。
 * 最典型的一次是 schema 物化没生效：客户端建表成功、写入时却报
 * {@code Cannot get latest schema for table <表名>}，而真正的原因（
 * {@code <表目录>/schema/schema-0} 不存在）只能登机器或开对象存储控制台才看得到。
 * 这个服务把「仓库里到底有什么」搬进控制台，与「表详情里的模式 / 快照」互为印证：
 * 库里说的与盘上写的对不对得上，一眼就能比。
 *
 * <p><b>它读的是物理目录，不是元数据。</b>因此结果里会出现库里根本没有的对象：
 * 别的引擎放进去的文件、失败提交留下的临时目录、以及删表时没连目录一起删
 * （{@code purge-on-drop=false}）留下的旧表目录。这些正是排查时想看的东西，
 * 所以不做过滤；界面上也不把它们说成「表」。
 *
 * <p><b>路径一律相对各自的根。</b>仓库浏览（{@link #list}）相对 catalog 的
 * {@code warehouse}；表目录浏览（{@link #listTable}）相对表元数据里的 {@code path}。
 * 绝对路径不接受——那等于让调用方用控制台去读这台服务器能访问的任何位置
 * （{@code file:///etc}、别的桶），而本端点的授权面只到「认证过的控制台用户」，
 * 不足以承担这种范围。拦截点见 {@link #resolve}。
 *
 * <p><b>每次调用都新构造 FileIO。</b>与 {@link TableMetadataService} 同一理由：
 * 缓存凭据的代价是「管理 API 换过 catalog 凭据之后，这里还在用旧钥匙」。
 * 列表是人工点出来的低频操作，省下的那点开销换不来任何东西。
 */
@Service
@RequiredArgsConstructor
public class CatalogFileService {

    private static final Logger log = LoggerFactory.getLogger(CatalogFileService.class);

    /** 相对路径的分隔符。Paimon 的 {@link Path} 在文件系统层用 {@code /}。 */
    private static final String SEPARATOR = "/";

    private final CatalogRepository catalogRepository;

    private final TableRepository tableRepository;

    private final DatabaseRepository databaseRepository;

    private final StorageFileIoFactory fileIoFactory;

    private final RestServerProperties properties;

    private final DataPolicyService dataPolicyService;

    /**
     * 列出仓库里某个目录的一层内容（根 = catalog 的 {@code warehouse}）。
     *
     * @param catalogName catalog 的 prefix
     * @param path        相对仓库根的目录路径；{@code null} 或空串表示仓库根
     */
    public CatalogFileListing list(String catalogName, String path) {
        return list(catalogName, path, null);
    }

    /**
     * 同上，外加按名称前缀过滤。
     *
     * @param prefix 只返回名称以它开头的条目；{@code null} 或空串表示不过滤。
     *               过滤在服务端做——界面上「输入前缀 + 回车」就是重新发一次这个请求，
     *               于是它天然与 {@code max-entries} 上限一起生效：
     *               过滤后的结果同样按目录优先、名称升序排，再截到上限。
     */
    public CatalogFileListing list(String catalogName, String path, String prefix) {
        return list(catalogName, path, prefix, null, null);
    }

    /**
     * 同上，外加上远程分页。
     *
     * <p>{@code page}/{@code pageSize} 都不给时保持老行为：过滤 + 排序后按
     * {@code max-entries} 截断，{@code page} 为 {@code null}。给了就按页码切，
     * {@code total} 仍是过滤后的完整条数——目录表要显示「共几个、第几页」。
     *
     * <p>目录条目是先列举再过滤的，{@code total} 因此不需要额外的第二次列举：
     * 一次 {@code listStatus} 已经把这一层读完了，切页只是切这个数组。
     */
    public CatalogFileListing list(String catalogName, String path, String prefix,
                                   Integer page, Integer pageSize) {
        int maxEntries = requireEnabled();
        CatalogEntity catalog = catalogRepository.findByPrefix(catalogName)
                .orElseThrow(() -> ApiException.managementNotExist(ResourceType.CATALOG, catalogName));
        String warehouse = catalog.getWarehouse();
        if (warehouse == null || warehouse.isBlank()) {
            throw ApiException.badRequest("catalog " + catalogName + " has no warehouse, so there is"
                    + " no directory to browse; set it with"
                    + " PUT /api/management/v1/catalogs/" + catalogName);
        }
        String relative = resolve(path, "the catalog warehouse");
        String namePrefix = resolvePrefix(prefix);
        DirPage listing = listDirectory(catalog, new Path(trimTrailingSlash(warehouse)), relative,
                "the warehouse " + warehouse, namePrefix, page, pageSize);
        return new CatalogFileListing(catalogName, warehouse, relative, namePrefix, listing.entries(),
                listing.total(), listing.truncated(), maxEntries, listing.page());
    }

    /**
     * 列出某张表的目录里的一层内容（根 = 表元数据里的 {@code path}）。
     *
     * <p>表详情页的「文件列表」用这个而不是拿表路径去调 {@link #list}：
     * 以表目录为根，一是下钻天生出不了表目录（{@code ..} 已被 {@link #resolve} 拒掉，
     * 相对路径也拼不出根之外的位置），二是控制台不用自己剥 warehouse 前缀——
     * scheme 大小写、尾斜杠那些归一化差异留在服务端一处处理。
     *
     * @param tableId 表的内部 id（表详情响应里的 {@code id} 字段）
     * @param path    相对表目录的路径；{@code null} 或空串表示表目录本身
     */
    public TableFileListing listTable(String tableId, String path) {
        return listTable(tableId, path, null);
    }

    /**
     * 同上，外加按名称前缀过滤（语义与 {@link #list} 的那条完全一致）。
     *
     * @param prefix 只返回名称以它开头的条目；{@code null} 或空串表示不过滤
     */
    public TableFileListing listTable(String tableId, String path, String prefix) {
        return listTable(tableId, path, prefix, null, null);
    }

    /** 同上，外加上远程分页（语义与 {@link #list} 的那条完全一致）。 */
    public TableFileListing listTable(String tableId, String path, String prefix,
                                      Integer page, Integer pageSize) {
        int maxEntries = requireEnabled();
        TableEntity table = tableRepository.findById(tableId)
                .orElseThrow(() -> ApiException.managementNotExist(ResourceType.TABLE, tableId));
        String tablePath = table.getPath();
        if (tablePath == null || tablePath.isBlank()) {
            throw ApiException.badRequest("table " + tableId + " has no path recorded,"
                    + " so there is no directory to browse; the path is written when the table"
                    + " is created and cannot be browsed without it");
        }
        CatalogEntity catalog = catalogRepository.findById(table.getCatalogId())
                .orElseThrow(() -> ApiException.managementNotExist(ResourceType.CATALOG, table.getCatalogId()));
        // 库名只为让响应自描述（界面上「paimon_catalog / demo / orders 的文件」）；
        // 元数据里挂着的 id 找不到实体说明数据不一致，如实回传 id 比编一个名字诚实
        String databaseName = databaseRepository.findById(table.getDatabaseId())
                .map(DatabaseEntity::getName)
                .orElse(table.getDatabaseId());
        String relative = resolve(path, "the table directory");
        String namePrefix = resolvePrefix(prefix);
        DirPage listing = listDirectory(catalog, new Path(trimTrailingSlash(tablePath)), relative,
                "the table directory " + tablePath, namePrefix, page, pageSize);
        return new TableFileListing(catalog.getPrefix(), databaseName, table.getName(), tablePath,
                relative, namePrefix, listing.entries(), listing.total(), listing.truncated(),
                maxEntries, listing.page());
    }

    // ------------------------------------------------------------------ 列举核心

    /**
     * 一次目录列举的产物：本页条目、过滤后的总数、是否还有下一页、分页元数据。
     *
     * <p>{@code page} 为 {@code null} 表示调用方没要分页——此时 {@code entries}
     * 是按 {@code max-entries} 截断后的全量视图，{@code truncated} 说的是这件事。
     */
    private record DirPage(List<CatalogFileEntry> entries, int total, boolean truncated,
                           PageMeta page) {}

    /**
     * 列出 {@code root} 下 {@code relative} 那一层，过滤、排序、截断都在这里收口。
     *
     * <p>两种根（仓库、表目录）共用这一段：不存在 404、对着文件列目录 400、
     * 目录条目如实填 0、名称前缀过滤、目录优先排序、超上限截断——语义只有一份，
     * 两个端点才不会漂移。
     *
     * @param namePrefix 名称前缀过滤；空串表示不过滤
     * @param rootLabel  人读的根位置描述（进错误消息与日志），如
     *                   {@code the warehouse s3://bucket/paimon} 或
     *                   {@code the table directory s3://bucket/paimon/demo.db/orders}
     */
    private DirPage listDirectory(CatalogEntity catalog, Path root, String relative,
                                  String rootLabel, String namePrefix,
                                  Integer page, Integer pageSize) {
        Path target = relative.isEmpty() ? root : new Path(root, relative);
        FileIO fileIO = open(catalog, target);

        FileStatus status;
        try {
            if (!fileIO.exists(target)) {
                throw new ApiException(404, ResourceType.CATALOG, catalog.getPrefix(),
                        missingMessage(relative, rootLabel));
            }
            status = fileIO.getFileStatus(target);
        } catch (IOException e) {
            throw listFailure(catalog.getPrefix(), relative, e);
        }
        if (!status.isDir()) {
            throw ApiException.badRequest(describe(relative, rootLabel)
                    + " is a file, not a directory; only directories can be listed");
        }

        FileStatus[] statuses;
        try {
            statuses = fileIO.listStatus(target);
        } catch (IOException e) {
            throw listFailure(catalog.getPrefix(), relative, e);
        }
        List<CatalogFileEntry> entries = new ArrayList<>(statuses.length);
        for (FileStatus child : statuses) {
            String name = child.getPath().getName();
            // 前缀过滤在这里而不是在客户端：过滤后的结果还要按同一个上限截断，
            // 两边各做一次会让「只显示了前 N 条」这句话对不上
            if (!namePrefix.isEmpty() && !name.startsWith(namePrefix)) {
                continue;
            }
            entries.add(new CatalogFileEntry(
                    name,
                    relative.isEmpty() ? name : relative + SEPARATOR + name,
                    child.isDir(),
                    // 目录的长度语义是「不知道」而不是 0，这里如实填 0 并由界面显示占位符：
                    // 递归算大小要遍历整棵子树，不该藏在一次列表请求里
                    child.isDir() ? 0L : child.getLen(),
                    child.getModificationTime()));
        }
        entries.sort(Comparator
                .comparing(CatalogFileEntry::directory).reversed()
                .thenComparing(CatalogFileEntry::name, String.CASE_INSENSITIVE_ORDER));

        int maxEntries = properties.getFileBrowser().getMaxEntries();
        int total = entries.size();
        // 分页模式下 total 是完整条数，本页条数由页码决定；不分页时仍按上限截断，
        // 并如实说「被截断了」——那句话在界面上的位置不同（分页器 vs 提示条），
        // 但都只能由服务端来说，客户端数不出没发给它的东西。
        Paging.Paged<CatalogFileEntry> paged = Paging.paged(entries, page, pageSize);
        if (paged != null) {
            log.debug("listed page {} ({} of {} entries, prefix='{}') at {} under catalog {}",
                    paged.meta().page(), paged.items().size(), total, namePrefix,
                    describe(relative, rootLabel), catalog.getPrefix());
            // 分页模式下「被截断」不成立：每一页都翻得到，`max-entries` 那道截断
            // 只在不分页的老路径上才发生。hasNext 已经由 page 元数据自己说了。
            return new DirPage(paged.items(), total, false, paged.meta());
        }
        boolean truncated = total > maxEntries;
        List<CatalogFileEntry> capped = truncated ? List.copyOf(entries.subList(0, maxEntries)) : entries;
        log.debug("listed {} entries (of {}, truncated={}, prefix='{}') at {} under catalog {}",
                capped.size(), total, truncated, namePrefix,
                describe(relative, rootLabel), catalog.getPrefix());
        return new DirPage(capped, total, truncated, null);
    }

    // ------------------------------------------------------------------ 下载

    /**
     * 打开的文件：流与元数据一起交给控制器包成响应。
     *
     * <p>流由调用方（控制器 / Spring 的消息转换器）负责读完与关闭——
     * 下载是一次性的流式读，服务端不做缓冲：数据文件动辄上百 MB，
     * 读进内存再回给浏览器既慢又容易把堆打爆。
     *
     * @param name             文件名（路径最后一段），进 {@code Content-Disposition}
     * @param size             文件长度（字节），来自 {@link FileStatus}，直接做 {@code Content-Length}
     * @param modificationTime 最后修改时间（epoch 毫秒）
     * @param stream           文件内容流；读完为止
     */
    public record FileDownload(String name, long size, long modificationTime, InputStream stream) {}

    /**
     * 打开仓库里某个文件供下载（根 = catalog 的 {@code warehouse}）。
     *
     * <p>下载与 {@link #list} 同一条路径防护（相对路径、拒 {@code ..} 与绝对路径）、
     * 同一个开关（{@code lakehouse.rest.file-browser.enabled}）、同一个授权面。
     * 只读不变：下载是「把已有的字节搬出去」，不是写。
     */
    public FileDownload openFile(String catalogName, String path) {
        requireEnabled();
        CatalogEntity catalog = catalogRepository.findByPrefix(catalogName)
                .orElseThrow(() -> ApiException.managementNotExist(ResourceType.CATALOG, catalogName));
        String warehouse = catalog.getWarehouse();
        if (warehouse == null || warehouse.isBlank()) {
            throw ApiException.badRequest("catalog " + catalogName + " has no warehouse, so there is"
                    + " nothing to download; set it with"
                    + " PUT /api/management/v1/catalogs/" + catalogName);
        }
        String relative = resolve(path, "the catalog warehouse");
        if (relative.isEmpty()) {
            throw ApiException.badRequest("download needs a file path, but got the warehouse root,"
                    + " which is a directory; pick a file from the listing instead");
        }
        return openForDownload(catalog, new Path(trimTrailingSlash(warehouse)), relative,
                "the warehouse " + warehouse);
    }

    /**
     * 打开某张表目录下的文件供下载（根 = 表元数据里的 {@code path}）。
     *
     * <p>与 {@link #listTable} 同一条防护：{@code path} 相对表目录，
     * {@code ..} 与绝对路径被拒，出不了表目录。空路径（表目录本身）是目录，
     * 直接 400。
     */
    public FileDownload openTableFile(String tableId, String path) {
        requireEnabled();
        TableEntity table = tableRepository.findById(tableId)
                .orElseThrow(() -> ApiException.managementNotExist(ResourceType.TABLE, tableId));
        String tablePath = table.getPath();
        if (tablePath == null || tablePath.isBlank()) {
            throw ApiException.badRequest("table " + tableId + " has no path recorded,"
                    + " so there is nothing to download");
        }
        CatalogEntity catalog = catalogRepository.findById(table.getCatalogId())
                .orElseThrow(() -> ApiException.managementNotExist(ResourceType.CATALOG, table.getCatalogId()));
        // 带行过滤 / 列脱敏策略的表：文件下载必须禁止（设计 §7.2，优先级最高的绕过面——
        // 一个 parquet 原文件就是全表明文，任何「预览已收口」都拦不住这条路）
        requireDownloadAllowed(catalog, table);
        String relative = resolve(path, "the table directory");
        if (relative.isEmpty()) {
            throw ApiException.badRequest("download needs a file path, but got the table directory,"
                    + " which is a directory; pick a file from the listing instead");
        }
        return openForDownload(catalog, new Path(trimTrailingSlash(tablePath)), relative,
                "the table directory " + tablePath);
    }

    /** 表挂了启用中的策略 → 下载 403（带原因；表级与 catalog 级下载共用同一口径）。 */
    private void requireDownloadAllowed(CatalogEntity catalog, TableEntity table) {
        String databaseName = databaseRepository.findById(table.getDatabaseId())
                .map(DatabaseEntity::getName).orElse(null);
        if (databaseName != null
                && dataPolicyService.hasActivePolicy(catalog.getPrefix(), databaseName, table.getName())) {
            throw ApiException.forbidden("file download is not available for this table:"
                    + " a row filter / column mask policy is active (design §7.2)");
        }
    }

    /** 下载与列举共用的「找到那个文件并打开流」；错误语义与列举一致（404 / 400 / 500）。 */
    private FileDownload openForDownload(CatalogEntity catalog, Path root, String relative, String rootLabel) {
        Path target = new Path(root, relative);
        FileIO fileIO = open(catalog, target);
        FileStatus status;
        try {
            if (!fileIO.exists(target)) {
                throw new ApiException(404, ResourceType.CATALOG, catalog.getPrefix(),
                        missingMessage(relative, rootLabel));
            }
            status = fileIO.getFileStatus(target);
        } catch (IOException e) {
            throw listFailure(catalog.getPrefix(), relative, e);
        }
        if (status.isDir()) {
            throw ApiException.badRequest(describe(relative, rootLabel)
                    + " is a directory; only files can be downloaded, list it instead");
        }
        try {
            return new FileDownload(target.getName(), status.getLen(), status.getModificationTime(),
                    fileIO.newInputStream(target));
        } catch (IOException e) {
            throw listFailure(catalog.getPrefix(), relative, e);
        }
    }

    // ------------------------------------------------------------------ 路径与 FileIO

    /**
     * 把调用方给的相对路径归一化，顺手挡掉逃出根的写法。
     *
     * <p>接受的是 {@code a/b} 这种相对路径；空串表示根本身（界面的第一层面包屑）。
     * 拒绝三类：带 scheme 的绝对路径（{@code s3://bucket/x}，那是另一个位置，
     * 由 catalog 的 warehouse 或表元数据决定而不是由请求决定）、以 {@code /} 开头或形如
     * {@code C:\} 的绝对路径、以及含 {@code ..} 的路径。
     *
     * <p>{@code ..} 是这里唯一真正的安全点。{@link Path} 自己不做越界检查
     * （{@code new Path(root, "../../etc")} 会照拼），而本端点的用途只是「在根里下钻」，
     * 因此不去做「拼完再判断前缀」的事后校验——那种写法要处理大小写、符号链接与
     * 分隔符重复等一堆归一化差异，容易漏。直接拒掉 {@code ..} 段更简单也更难写错。
     *
     * @param rootNoun 人读的根描述，进错误消息：{@code the catalog warehouse} /
     *                 {@code the table directory}
     */
    private static String resolve(String path, String rootNoun) {
        String raw = path == null ? "" : path.trim();
        if (raw.isEmpty() || raw.equals(SEPARATOR)) {
            return "";
        }
        if (raw.contains("://")) {
            throw ApiException.badRequest("path must be relative to " + rootNoun
                    + ", but got an absolute location: " + raw
                    + "; the browsed root is fixed by the resource, not by the request");
        }
        if (raw.startsWith(SEPARATOR)) {
            throw ApiException.badRequest("path must be relative to " + rootNoun
                    + ", but got an absolute path: " + raw);
        }
        List<String> segments = new ArrayList<>();
        for (String segment : raw.split(SEPARATOR)) {
            if (segment.isEmpty() || segment.equals(".")) {
                // `a//b` 与 `a/./b` 是同一个位置，归一化掉而不是拒绝——拒绝只会让
                // 界面上一个手抖的 URL 报一条看不懂的错
                continue;
            }
            if (segment.equals("..")) {
                throw ApiException.badRequest("path must stay inside " + rootNoun
                        + ", but got a parent reference: " + raw);
            }
            segments.add(segment);
        }
        return String.join(SEPARATOR, segments);
    }

    /**
     * 归一化名称前缀过滤条件。
     *
     * <p>它**不是路径**：只与条目名（目录项的最后一段）做前缀比较，因此不涉及越界、
     * 不需要 {@link #resolve} 那套防护。唯一要拦的是含分隔符的写法——
     * 「按路径前缀过滤」是一个合理但不同的需求（要跨层，本质是递归列举），
     * 而在这里它永远匹配不到任何条目（条目名不含 {@code /}），与其给一个静默的空结果
     * 让人以为「目录是空的」，不如直说该改用 {@code path} 下钻。
     */
    private static String resolvePrefix(String prefix) {
        String raw = prefix == null ? "" : prefix.trim();
        if (raw.contains(SEPARATOR)) {
            throw ApiException.badRequest("prefix filters entry names, which never contain '"
                    + SEPARATOR + "', but got: " + raw
                    + "; use path to walk into a sub-directory and filter inside it");
        }
        return raw;
    }

    private FileIO open(CatalogEntity catalog, Path target) {        try {
            // 作用域标识用 catalog id：凭据管理器按它记账与缓存，与表元数据那条链路
            // 传 table id 是同一个约定（见 StorageCredentialManager#vend）
            return fileIoFactory.open(target, fileIoFactory.optionsFor(catalog, catalog.getId(), target.toString()));
        } catch (IOException e) {
            throw listFailure(catalog.getPrefix(), "", e);
        }
    }

    /** 把 IO 失败归成 500 并带上一句人话；路径与 catalog 都写进去，否则没法定位是哪台存储。 */
    private static ApiException listFailure(String catalogName, String relative, IOException cause) {
        String message = "failed to list " + describe(relative, "the browsed root")
                + ": " + rootMessage(cause);
        // 原始异常在这里落日志而不是塞进 ApiException：后者的第五个参数是协议错误码
        // （OAuth 用），不是 cause。带完整堆栈的排查信息归日志，响应体里给人读的一句话。
        log.warn("catalog file listing failed under catalog {}: {}", catalogName, message, cause);
        return new ApiException(500, ResourceType.CATALOG, catalogName, message);
    }

    /**
     * 「列不到」的两种说法：根本身不存在是一种（直接说根），根下的子目录是另一种
     * （说它在哪个根之下），拼出来的句子两种根下都读得通。
     */
    private static String missingMessage(String relative, String rootLabel) {
        return relative.isEmpty()
                ? rootLabel + " does not exist"
                : "directory '" + relative + "' does not exist under " + rootLabel;
    }

    /** 条目的说法：根就用给定的说法（可为空串），子路径带引号。 */
    private static String describe(String relative, String fallbackLabel) {
        return relative.isEmpty() ? fallbackLabel : "directory '" + relative + "'";
    }

    private int requireEnabled() {
        if (!properties.getFileBrowser().isEnabled()) {
            throw ApiException.notImplemented("the catalog file browser is disabled in this deployment"
                    + " (lakehouse.rest.file-browser.enabled=false); the warehouse itself is untouched,"
                    + " only this view is off");
        }
        return properties.getFileBrowser().getMaxEntries();
    }

    private static String trimTrailingSlash(String warehouse) {
        String trimmed = warehouse.trim();
        while (trimmed.length() > 1 && trimmed.endsWith(SEPARATOR)) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /** 异常链最里面那条的消息；顶层的 IO 异常常常只有一句「null」。 */
    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }
}
