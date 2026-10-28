package io.github.melin.lakehouse.rest.lance.web;

import io.github.melin.lakehouse.rest.support.LanceErrors;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /lance/v1/**} 下**未实现**的 operation：一律 {@code 406 + code 0}。
 *
 * <p>规范 48 个 operation 里本服务端实现 13 个（另 5 个是 P1/P2 的预留），
 * 其余 30 个是刻意不做的数据面 / 索引 / 标签 / 事务 / 建表删表
 * （清单与理由见 {@code docs/lance-rest-catalog-design.md} §3.1）。
 *
 * <p><b>为什么是 406 而不是 404。</b>404 会被客户端理解成「地址错了」，
 * 然后它会去核对 uri 与 delimiter；500 看起来像服务坏了；而「假装成功」
 * 是最坏的一种——客户端会把一个没发生的操作当成已发生。
 * {@code code 0 (Unsupported)} 是规范为「本实现不支持」预留的那个位置，
 * 它的 HTTP 状态恰好是 406。
 *
 * <p><b>为什么用通配控制器而不是让 Spring 自己答 404。</b>那样答出来的是
 * Paimon 形状的 {@code {message, resourceType, ...}}（或 Spring 的默认错误页），
 * 而 Lance 客户端是 OpenAPI 生成的代码，按四字段反序列化——它对「解析失败」
 * 的报错会盖掉真正的原因（这个能力不存在）。
 *
 * <p><b>它的优先级最低。</b>{@code /lance/v1/**} 是最不具体的模式，
 * 上面两个控制器里的精确路径先命中；只有落在它们之外的请求才到这里。
 * 因此「加一个新 operation 却忘了写控制器」会表现为 406 而不是静默 404——
 * 这正是我们想要的方向（宁可说不支持，也不要假装没有这条路径）。
 */
@RestController
@RequestMapping("/lance/v1/**")
public class LanceUnsupportedController {

    @RequestMapping(method = {org.springframework.web.bind.annotation.RequestMethod.GET,
            org.springframework.web.bind.annotation.RequestMethod.POST,
            org.springframework.web.bind.annotation.RequestMethod.PUT,
            org.springframework.web.bind.annotation.RequestMethod.DELETE,
            org.springframework.web.bind.annotation.RequestMethod.PATCH,
            org.springframework.web.bind.annotation.RequestMethod.HEAD})
    public void unsupported(HttpServletRequest request) {
        throw LanceErrors.unsupported(operationOf(request));
    }

    /**
     * 从路径尾段猜 operation 名，只为了让错误文案能点名它。
     *
     * <p>猜错不影响语义（{@code code} 恒为 0），但能让排障的人一眼看到
     * 「是哪个 operation 没实现」，而不是对着一条路径自己数段。
     */
    private static String operationOf(HttpServletRequest request) {
        String path = request == null ? null : request.getRequestURI();
        if (path == null) {
            return "unknown";
        }
        String method = request.getMethod() == null ? "" : request.getMethod() + " ";
        return method + path;
    }
}
