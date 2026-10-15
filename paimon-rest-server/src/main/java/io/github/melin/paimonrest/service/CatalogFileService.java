package io.github.melin.paimonrest.service;

import io.github.melin.paimonrest.config.RestServerProperties;
import io.github.melin.paimonrest.domain.entity.CatalogEntity;
import io.github.melin.paimonrest.domain.repo.CatalogRepository;
import io.github.melin.paimonrest.dto.ConsoleDtos.CatalogFileEntry;
import io.github.melin.paimonrest.dto.ConsoleDtos.CatalogFileListing;
import io.github.melin.paimonrest.support.ApiException;
import io.github.melin.paimonrest.support.ResourceType;
import java.io.IOException;
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
 * 浏览 catalog 仓库的物理目录。
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
 * <p><b>路径一律相对仓库根。</b>请求给的是相对路径，服务端拼在 catalog 的
 * {@code warehouse} 上。绝对路径不接受——那等于让调用方用控制台去读这台服务器
 * 能访问的任何位置（{@code file:///etc}、别的桶），而本端点的授权面只到
 * 「认证过的控制台用户」，不足以承担这种范围。拦截点见 {@link #resolve}。
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

    private final StorageFileIoFactory fileIoFactory;

    private final RestServerProperties properties;

    /**
     * 列出某个目录的一层内容。
     *
     * @param catalogName catalog 的 prefix
     * @param path        相对仓库根的目录路径；{@code null} 或空串表示仓库根
     */
    public CatalogFileListing list(String catalogName, String path) {
        int maxEntries = properties.getFileBrowser().getMaxEntries();
        if (!properties.getFileBrowser().isEnabled()) {
            throw ApiException.notImplemented("the catalog file browser is disabled in this deployment"
                    + " (paimon.rest.file-browser.enabled=false); the warehouse itself is untouched,"
                    + " only this view is off");
        }
        CatalogEntity catalog = catalogRepository.findByPrefix(catalogName)
                .orElseThrow(() -> ApiException.managementNotExist(ResourceType.CATALOG, catalogName));
        String warehouse = catalog.getWarehouse();
        if (warehouse == null || warehouse.isBlank()) {
            throw ApiException.badRequest("catalog " + catalogName + " has no warehouse, so there is"
                    + " no directory to browse; set it with"
                    + " PUT /api/management/v1/catalogs/" + catalogName);
        }
        Path root = new Path(trimTrailingSlash(warehouse));
        String relative = resolve(path);
        Path target = relative.isEmpty() ? root : new Path(root, relative);
        FileIO fileIO = open(catalog, target);

        FileStatus status;
        try {
            if (!fileIO.exists(target)) {
                throw new ApiException(404, ResourceType.CATALOG, catalogName,
                        describe(relative) + " does not exist under the warehouse " + warehouse);
            }
            status = fileIO.getFileStatus(target);
        } catch (IOException e) {
            throw listFailure(catalogName, relative, e);
        }
        if (!status.isDir()) {
            throw ApiException.badRequest(describe(relative) + " is a file, not a directory;"
                    + " only directories can be listed");
        }

        FileStatus[] statuses;
        try {
            statuses = fileIO.listStatus(target);
        } catch (IOException e) {
            throw listFailure(catalogName, relative, e);
        }
        List<CatalogFileEntry> entries = new ArrayList<>(statuses.length);
        for (FileStatus child : statuses) {
            String name = child.getPath().getName();
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

        boolean truncated = entries.size() > maxEntries;
        List<CatalogFileEntry> page = truncated ? List.copyOf(entries.subList(0, maxEntries)) : entries;
        log.debug("listed {} entries (of {}, truncated={}) at {} under catalog {}",
                page.size(), entries.size(), truncated, describe(relative), catalogName);
        return new CatalogFileListing(catalogName, warehouse, relative, page,
                entries.size(), truncated, maxEntries);
    }

    // ------------------------------------------------------------------ 路径与 FileIO

    /**
     * 把调用方给的相对路径归一化，顺手挡掉逃出仓库根的写法。
     *
     * <p>接受的是 {@code a/b} 这种仓库内相对路径；空串表示仓库根（界面的「仓库根」面包屑）。
     * 拒绝三类：带 scheme 的绝对路径（{@code s3://bucket/x}，那是另一个位置，
     * 由 catalog 的 warehouse 决定而不是由请求决定）、以 {@code /} 开头或形如
     * {@code C:\} 的绝对路径、以及含 {@code ..} 的路径。
     *
     * <p>{@code ..} 是这里唯一真正的安全点。{@link Path} 自己不做越界检查
     * （{@code new Path(root, "../../etc")} 会照拼），而本端点的用途只是「在仓库里下钻」，
     * 因此不去做「拼完再判断前缀」的事后校验——那种写法要处理大小写、符号链接与
     * 分隔符重复等一堆归一化差异，容易漏。直接拒掉 {@code ..} 段更简单也更难写错。
     */
    private static String resolve(String path) {
        String raw = path == null ? "" : path.trim();
        if (raw.isEmpty() || raw.equals(SEPARATOR)) {
            return "";
        }
        if (raw.contains("://")) {
            throw ApiException.badRequest("path must be relative to the catalog warehouse,"
                    + " but got an absolute location: " + raw
                    + "; the warehouse is fixed by the catalog, not by the request");
        }
        if (raw.startsWith(SEPARATOR)) {
            throw ApiException.badRequest("path must be relative to the catalog warehouse,"
                    + " but got an absolute path: " + raw);
        }
        List<String> segments = new ArrayList<>();
        for (String segment : raw.split(SEPARATOR)) {
            if (segment.isEmpty() || segment.equals(".")) {
                // `a//b` 与 `a/./b` 是同一个位置，归一化掉而不是拒绝——拒绝只会让
                // 界面上一个手抖的 URL 报一条看不懂的错
                continue;
            }
            if (segment.equals("..")) {
                throw ApiException.badRequest("path must stay inside the catalog warehouse,"
                        + " but got a parent reference: " + raw);
            }
            segments.add(segment);
        }
        return String.join(SEPARATOR, segments);
    }

    private FileIO open(CatalogEntity catalog, Path target) {
        try {
            // 作用域标识用 catalog id：凭据管理器按它记账与缓存，与表元数据那条链路
            // 传 table id 是同一个约定（见 StorageCredentialManager#vend）
            return fileIoFactory.open(target, fileIoFactory.optionsFor(catalog, catalog.getId(), target.toString()));
        } catch (IOException e) {
            throw listFailure(catalog.getPrefix(), describe(""), e);
        }
    }

    /** 把 IO 失败归成 500 并带上一句人话；路径与 catalog 都写进去，否则没法定位是哪台存储。 */
    private static ApiException listFailure(String catalogName, String relative, IOException cause) {
        String message = "failed to list " + describe(relative) + ": " + rootMessage(cause);
        // 原始异常在这里落日志而不是塞进 ApiException：后者的第五个参数是协议错误码
        // （OAuth 用），不是 cause。带完整堆栈的排查信息归日志，响应体里给人读的一句话。
        log.warn("catalog file listing failed under catalog {}: {}", catalogName, message, cause);
        return new ApiException(500, ResourceType.CATALOG, catalogName, message);
    }

    private static String describe(String relative) {
        return relative.isEmpty() ? "the warehouse root" : "directory '" + relative + "'";
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
