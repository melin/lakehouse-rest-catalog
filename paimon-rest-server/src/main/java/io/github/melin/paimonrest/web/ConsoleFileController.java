package io.github.melin.paimonrest.web;

import io.github.melin.paimonrest.dto.ConsoleDtos;
import io.github.melin.paimonrest.service.CatalogFileService;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 控制台的仓库文件浏览端点——两个根：仓库根与表目录。
 *
 * <p><b>非 Polaris 规格端点</b>，是本工程为 Web 控制台加的只读扩展，
 * 语义与取舍写在 {@link ConsoleDtos.CatalogFileListing}、
 * {@link ConsoleDtos.TableFileListing} 与 {@code RestServerProperties.FileBrowser}
 * 的注释里。刻意放在 {@code /api/console/} 下：{@code /api/management/} 那个命名空间要与
 * {@code spec/polaris-management-service.yml} 一一对应，往里塞规格外端点会让
 * 这条不变量需要额外解释。
 *
 * <p><b>只有读，没有写。</b>这里不提供删除文件、创建目录一类的操作：
 * 仓库里的对象归引擎与服务端自己管（{@code TableMetadataService} 写 schema 与快照、
 * {@code purge-on-drop} 决定删表时清不清目录），从控制台手工改文件会让
 * 「库里说的」与「盘上写的」不一致，而那正是这个页面要帮人发现的问题。
 *
 * <p>授权面与 {@code /api/console/v1/**} 的其余端点一致：只过认证拦截器，
 * 不做细粒度判定。刻意如此——控制台用户是拿管理凭据进来的运维者，
 * 而细粒度判定要回答「这个主体能不能看这个 catalog 的物理目录」，
 * 那需要把 catalog 级权限语义延伸到文件系统层，本工程没有这个模型。
 * 部署不希望开放它时用 {@code paimon.rest.file-browser.enabled=false} 整体关掉。
 */
@RestController
@RequiredArgsConstructor
public class ConsoleFileController {

    private final CatalogFileService catalogFileService;

    /**
     * {@code GET /api/console/v1/catalogs/{catalogName}/files?path=<相对仓库根>&prefix=<名称前缀>}：
     * 列出仓库里某个目录的一层内容（Catalog 详情页的「文件列表」）。
     *
     * <p>{@code path} 省略或为空时列出仓库根。给出的必须是**相对**路径——
     * 绝对路径与 {@code ..} 都会被拒（400），理由见
     * {@link CatalogFileService#list}。
     *
     * <p>{@code prefix} 可选，按条目名过滤（服务端过滤后再截到上限）。
     */
    @GetMapping("/api/console/v1/catalogs/{catalogName}/files")
    public ConsoleDtos.CatalogFileListing files(@PathVariable String catalogName,
                                                @RequestParam(name = "path", required = false) String path,
                                                @RequestParam(name = "prefix", required = false) String prefix) {
        return catalogFileService.list(catalogName, path, prefix);
    }

    /**
     * {@code GET /api/console/v1/tables/{tableId}/files?path=<相对表目录>&prefix=<名称前缀>}：
     * 列出某张表的目录里的一层内容（表详情页的「文件列表」）。
     *
     * <p>根是表元数据里的 {@code path}，不是仓库——控制台因此不需要先算
     * 「表路径相对仓库根是哪一段」，而这个端点也天生够不到表目录之外：
     * {@code ..} 与绝对路径同样被 {@link CatalogFileService#listTable} 拒掉。
     * {@code tableId} 用表的内部 id（表详情响应里的 {@code id}），不是
     * {@code database.table} 名字对——按名字定位要拆出 catalog 与库两层判断，
     * 而调用方（表详情页）手里本来就有 id。
     */
    @GetMapping("/api/console/v1/tables/{tableId}/files")
    public ConsoleDtos.TableFileListing tableFiles(@PathVariable String tableId,
                                                   @RequestParam(name = "path", required = false) String path,
                                                   @RequestParam(name = "prefix", required = false) String prefix) {
        return catalogFileService.listTable(tableId, path, prefix);
    }

    /**
     * {@code GET /api/console/v1/catalogs/{catalogName}/files/download?path=<相对仓库根>}：
     * 把仓库里**一个文件**的内容流式回给浏览器（Catalog 详情页文件列表的「下载」）。
     *
     * <p>仍是只读——下载是把已有的字节搬出去，不产生任何写入。路径防护、开关与
     * 授权面与列举端点完全一致（见 {@link CatalogFileService#openFile}）；
     * 对目录与仓库根是 400（「列它去」），文件不存在是 404。
     * {@code path} 是必填参数：没有「下载整个仓库」这种操作。
     */
    @GetMapping("/api/console/v1/catalogs/{catalogName}/files/download")
    public ResponseEntity<InputStreamResource> downloadCatalogFile(
            @PathVariable String catalogName,
            @RequestParam(name = "path") String path) {
        return asAttachment(catalogFileService.openFile(catalogName, path));
    }

    /**
     * {@code GET /api/console/v1/tables/{tableId}/files/download?path=<相对表目录>}：
     * 把某张表目录下的**一个文件**流式回给浏览器（表详情页文件列表的「下载」）。
     *
     * <p>与表级列举同一根、同一防护：出不了表目录，空路径（表目录本身）是 400。
     */
    @GetMapping("/api/console/v1/tables/{tableId}/files/download")
    public ResponseEntity<InputStreamResource> downloadTableFile(
            @PathVariable String tableId,
            @RequestParam(name = "path") String path) {
        return asAttachment(catalogFileService.openTableFile(tableId, path));
    }

    /**
     * 包成附件响应：文件名进 {@code Content-Disposition}（RFC 5987 的 UTF-8 形式，
     * 中文名不乱码），长度用元数据里的 {@code Content-Length}——浏览器因此能显示
     * 下载进度，服务端全程不缓冲，字节从存储直接流向响应。
     */
    private static ResponseEntity<InputStreamResource> asAttachment(CatalogFileService.FileDownload download) {
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(download.name(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentLength(download.size())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new InputStreamResource(download.stream()));
    }
}
