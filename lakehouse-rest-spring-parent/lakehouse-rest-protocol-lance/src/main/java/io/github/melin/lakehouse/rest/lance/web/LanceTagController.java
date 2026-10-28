package io.github.melin.lakehouse.rest.lance.web;

import io.github.melin.lakehouse.rest.lance.dto.LanceDtos;
import io.github.melin.lakehouse.rest.lance.service.LanceTagService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 表标签的两个**只读** operation：{@code ListTableTags} 与 {@code GetTableTagVersion}。
 *
 * <p>它们与版本控制器分开而不是并进一个类：路径前缀相同（{@code /lance/v1/table}），
 * 但语义不同——版本是「我们的版本账 + 存储里的 manifest」，标签是「客户端写在数据集里的
 * 文件，我们只读」。合成一个类会让「哪些端点会写」这件事变模糊。
 *
 * <p><b>标签的写操作（{@code tags/create|delete|update}）不在这里</b>：它们改的是
 * 客户端的数据集，而本服务端从不写 Lance 的文件（设计文档 §3.2）。因此仍由兜底控制器
 * 回 {@code 406 + code 0}。
 *
 * <p><b>只接 {@code POST}。</b>规范的 {@code tags/list} 与 {@code tags/version} 都是
 * POST；版本那一面额外接了 GET，是因为参考实现（Gravitino）发的是 GET 且有据可查，
 * 这里没有同样的证据，就不去猜一个可能永远没人用的形态。
 */
@RestController
@RequestMapping("/lance/v1/table")
@RequiredArgsConstructor
public class LanceTagController {

    private final LanceTagService tagService;

    /** 列举标签。query：{@code limit} / {@code page_token} / {@code delimiter}。 */
    @PostMapping("/{id}/tags/list")
    public LanceDtos.ListTableTagsResponse listTags(
            @PathVariable String id,
            @RequestParam(required = false) String delimiter,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String page_token) {
        return tagService.list(id, delimiter, limit, page_token);
    }

    /** 一个标签指向哪个版本。body：{@code {"tag":"v1.0.0"}}。 */
    @PostMapping("/{id}/tags/version")
    public LanceDtos.GetTableTagVersionResponse tagVersion(
            @PathVariable String id,
            @RequestParam(required = false) String delimiter,
            @RequestBody(required = false) LanceDtos.GetTableTagVersionRequest request) {
        return tagService.version(id, delimiter, request);
    }
}
