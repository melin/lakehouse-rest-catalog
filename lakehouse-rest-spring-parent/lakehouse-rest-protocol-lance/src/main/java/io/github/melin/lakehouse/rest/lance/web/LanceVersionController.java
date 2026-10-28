package io.github.melin.lakehouse.rest.lance.web;

import io.github.melin.lakehouse.rest.lance.dto.LanceDtos;
import io.github.melin.lakehouse.rest.lance.service.LanceVersionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 表版本的四个 operation（{@code ListTableVersions} / {@code DescribeTableVersion}
 * 是 P1，{@code CreateTableVersion} / {@code BatchDeleteTableVersions} 是 P2）。
 *
 * <p><b>{@code version/list} 同时接 {@code GET} 与 {@code POST}。</b>这不是宽容：
 * 现行规范写的是 {@code POST}，而参考实现（Gravitino 1.3.1）与它的中文文档写的是
 * {@code GET}，两种客户端都真实存在。规范自己在这一条上给了「REST namespace
 * 不使用请求体」的说明，而 GET 与 POST 在这个 operation 上语义相同（都是列举），
 * 因此都接不会带来歧义——只接一个则会让一半客户端拿到 405。
 *
 * <p><b>{@code BatchCreateTableVersions} 不在这里。</b>它的路径
 * {@code /lance/v1/table/version/batch-create} 不带表标识，
 * 授权层无法定位资源（理由见 {@link LanceVersionService} 的类注释），
 * 因此刻意落在兜底控制器的 406 上。
 */
@RestController
@RequestMapping("/lance/v1/table")
@RequiredArgsConstructor
public class LanceVersionController {

    private final LanceVersionService versionService;

    /** 列举版本。query：{@code descending} / {@code limit} / {@code page_token} / {@code delimiter}。 */
    @GetMapping("/{id}/version/list")
    public LanceDtos.ListTableVersionsResponse listVersionsGet(
            @PathVariable String id,
            @RequestParam(required = false) String delimiter,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String page_token,
            @RequestParam(required = false) Boolean descending) {
        return versionService.list(id, delimiter, limit, page_token, Boolean.TRUE.equals(descending));
    }

    @PostMapping("/{id}/version/list")
    public LanceDtos.ListTableVersionsResponse listVersions(
            @PathVariable String id,
            @RequestParam(required = false) String delimiter,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String page_token,
            @RequestParam(required = false) Boolean descending) {
        return versionService.list(id, delimiter, limit, page_token, Boolean.TRUE.equals(descending));
    }

    @PostMapping("/{id}/version/describe")
    public LanceDtos.DescribeTableVersionResponse describeVersion(
            @PathVariable String id,
            @RequestParam(required = false) String delimiter,
            @RequestBody(required = false) LanceDtos.DescribeTableVersionRequest request) {
        return versionService.describe(id, delimiter, request);
    }

    @PostMapping("/{id}/version/create")
    public LanceDtos.CreateTableVersionResponse createVersion(
            @PathVariable String id,
            @RequestParam(required = false) String delimiter,
            @RequestBody(required = false) LanceDtos.CreateTableVersionRequest request) {
        return versionService.create(id, delimiter, request);
    }

    @PostMapping("/{id}/version/delete")
    public LanceDtos.BatchDeleteTableVersionsResponse deleteVersions(
            @PathVariable String id,
            @RequestParam(required = false) String delimiter,
            @RequestBody(required = false) LanceDtos.BatchDeleteTableVersionsRequest request) {
        return versionService.delete(id, delimiter, request);
    }
}
