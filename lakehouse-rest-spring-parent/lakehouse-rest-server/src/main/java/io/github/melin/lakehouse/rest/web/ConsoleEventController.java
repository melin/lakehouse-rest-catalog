package io.github.melin.lakehouse.rest.web;

import io.github.melin.lakehouse.rest.dto.ConsoleDtos;
import io.github.melin.lakehouse.rest.service.ConsoleEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 控制台的事件端点。
 *
 * <p><b>非 Polaris 规格端点</b>，与 {@link ConsoleMetaController} 同一取向：放进
 * {@code /api/console/} 命名空间而不进管理规格，保持「管理 API 与规格一一对应」
 * 的不变量。鉴权走控制台门禁（Bearer），不做 catalog 级 RBAC——事件不含凭据，
 * 敏感度与 {@code /meta} 同级，口径见 {@code ConsoleDtos} 的类注释。
 */
@RestController
@RequestMapping("/api/console/v1")
@RequiredArgsConstructor
public class ConsoleEventController {

    private final ConsoleEventService events;

    /**
     * {@code GET /api/console/v1/events?since=<id>&catalog=<name>&category=<name>&limit=<n>&page=<n>&pageSize=<n>}。
     *
     * <p>两种翻法共用同一个端点，互斥（见 {@link ConsoleDtos.EventPage}）：
     * 不给 {@code page}/{@code pageSize} 就是增量流——「给我 since 之后的新事件」；
     * 给了就是控制台表格的远程分页，按 id 倒序的第 N 页，响应里带
     * {@code page.total}，{@code el-pagination} 因此能直接渲染总页数。
     *
     * @param since    游标：只回比它新的事件；省略表示首次拉取，回当前最新一页
     * @param catalog  可选的目录过滤；省略回全部目录
     * @param category 可选的类别过滤（{@code TABLE} / {@code GRANT} …，大小写不敏感）；
     *                 未知取值回 400 而不是空页
     * @param limit    增量流模式的页大小，默认 100、上限 1000
     * @param page     远程分页的页码（1-based）；与 {@code pageSize} 一起给才生效
     * @param pageSize 远程分页的页大小，默认 20、上限 200
     */
    @GetMapping("/events")
    public ConsoleDtos.EventPage events(@RequestParam(required = false) Long since,
                                        @RequestParam(required = false) String catalog,
                                        @RequestParam(required = false) String category,
                                        @RequestParam(required = false) String type,
                                        @RequestParam(required = false) Integer limit,
                                        @RequestParam(name = "page", required = false) Integer page,
                                        @RequestParam(name = "pageSize", required = false) Integer pageSize) {
        return events.events(catalog, category, type, since, limit, page, pageSize);
    }
}
