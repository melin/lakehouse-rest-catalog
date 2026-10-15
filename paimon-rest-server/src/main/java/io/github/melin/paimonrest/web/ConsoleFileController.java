package io.github.melin.paimonrest.web;

import io.github.melin.paimonrest.dto.ConsoleDtos;
import io.github.melin.paimonrest.service.CatalogFileService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 控制台的 catalog 仓库浏览端点。
 *
 * <p><b>非 Polaris 规格端点</b>，是本工程为 Web 控制台加的只读扩展，
 * 语义与取舍写在 {@link ConsoleDtos.CatalogFileListing} 与
 * {@code RestServerProperties.FileBrowser} 的注释里。刻意放在
 * {@code /api/console/} 下：{@code /api/management/} 那个命名空间要与
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
@RequestMapping("/api/console/v1/catalogs")
@RequiredArgsConstructor
public class ConsoleFileController {

    private final CatalogFileService catalogFileService;

    /**
     * {@code GET /api/console/v1/catalogs/{catalogName}/files?path=<相对仓库根>}：
     * 列出仓库里某个目录的一层内容。
     *
     * <p>{@code path} 省略或为空时列出仓库根。给出的必须是**相对**路径——
     * 绝对路径与 {@code ..} 都会被拒（400），理由见
     * {@link CatalogFileService#list}。
     */
    @GetMapping("/{catalogName}/files")
    public ConsoleDtos.CatalogFileListing files(@PathVariable String catalogName,
                                                @RequestParam(name = "path", required = false) String path) {
        return catalogFileService.list(catalogName, path);
    }
}
