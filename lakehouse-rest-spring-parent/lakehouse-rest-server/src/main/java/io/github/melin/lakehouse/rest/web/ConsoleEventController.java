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
     * {@code GET /api/console/v1/events?since=<id>&catalog=<name>&limit=<n>}。
     *
     * @param since   游标：只回比它新的事件；省略表示首次拉取，回当前最新一页
     * @param catalog 可选的目录过滤；省略回全部目录
     * @param limit   页大小，默认 100、上限 1000
     */
    @GetMapping("/events")
    public ConsoleDtos.EventPage events(@RequestParam(required = false) Long since,
                                        @RequestParam(required = false) String catalog,
                                        @RequestParam(required = false) Integer limit) {
        return events.events(catalog, since, limit);
    }
}
