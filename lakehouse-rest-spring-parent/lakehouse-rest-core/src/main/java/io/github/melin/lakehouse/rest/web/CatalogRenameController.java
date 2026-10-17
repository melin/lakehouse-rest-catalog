package io.github.melin.lakehouse.rest.web;

import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.service.CatalogFormatResolver;
import io.github.melin.lakehouse.rest.service.CatalogProtocols;
import io.github.melin.lakehouse.rest.service.CatalogService;
import io.github.melin.lakehouse.rest.support.CatalogFormat;
import io.github.melin.lakehouse.rest.support.ResourceType;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * 两条**同路径**的重命名端点：{@code POST /v1/{prefix}/tables/rename} 与
 * {@code POST /v1/{prefix}/views/rename}。
 *
 * <p><b>为什么这两条要单独有一个控制器。</b>Paimon 与 Iceberg 的两份规格在这两条上
 * 完全撞车：HTTP 方法、路径逐字相同（设计文档 §2.1 表里「语义同路径但请求体不同」那两行），
 * 而 Spring 不允许同一个「方法 + 路径」被注册两次——两个协议面各写一个
 * {@code @PostMapping("/tables/rename")}，启动时就会以
 * {@code Ambiguous mapping} 失败。因此它们不能待在任何一个协议面的控制器树里，
 * 只能上移到装配层，按 catalog 的格式把请求交给对的那个面。
 *
 * <p>两边的差异有三处，都要在这一层照顾到：
 *
 * <table border="1">
 *   <caption>同一个动作，两份规格</caption>
 *   <tr><th></th><th>Paimon</th><th>Iceberg</th></tr>
 *   <tr><td>请求体标识符</td><td>{@code {database, object}}</td>
 *       <td>{@code {namespace: [...], name}}</td></tr>
 *   <tr><td>成功状态码</td><td><b>200</b></td><td><b>204</b></td></tr>
 *   <tr><td>谁来绑定请求体</td><td colspan="2">各自的面（用各自的 DTO）</td></tr>
 * </table>
 *
 * <p>请求体**不在这一层绑定**：{@code JsonNode} 穿过边界，由协议面拿自己的契约去绑
 * （见 {@code CatalogProtocol#rename}）。core 因此不认识任何一边的 DTO——那正是
 * 「core 不依赖协议面」这条不变量的具体形状。
 *
 * <p><b>格式的判据有两层，顺序不能反。</b>先查 prefix 在库里的格式（一个 catalog
 * 只有一种格式，这是权威判据）；查不到（该 prefix 还没登记）才看请求体字段名
 * （{@code source.namespace} 是数组 → Iceberg；{@code source.object} /
 * {@code source.database} → Paimon，见 {@link CatalogFormatResolver#fromRenameBody}）。
 * 第二层不是冗余：自动登记开着时，敲错 prefix 的客户端会走到这里，
 * 而那时唯一的线头就是请求体。判定结果还要**传给自动登记**——
 * 否则一个 Iceberg 客户端的 rename 会建出一个 Paimon catalog，
 * 而它此后每一次调用都会 404。
 */
@RestController
@RequestMapping("/v1/{prefix}")
@RequiredArgsConstructor
public class CatalogRenameController {

    private final CatalogFormatResolver formatResolver;
    private final CatalogService catalogService;
    /** 协议面注册表。为什么它是独立 bean 而不是 {@code CatalogService} 的一个方法，见那个类的注释。 */
    private final CatalogProtocols protocols;

    /** {@code POST /v1/{prefix}/tables/rename}。 */
    @PostMapping("/tables/rename")
    public ResponseEntity<Void> renameTable(@PathVariable String prefix,
                                            @RequestBody(required = false) JsonNode body) {
        return rename(prefix, ResourceType.TABLE, body);
    }

    /** {@code POST /v1/{prefix}/views/rename}。 */
    @PostMapping("/views/rename")
    public ResponseEntity<Void> renameView(@PathVariable String prefix,
                                           @RequestBody(required = false) JsonNode body) {
        return rename(prefix, ResourceType.VIEW, body);
    }

    /**
     * 判定格式 → 取（必要时按该格式自动登记）catalog → 交给那个面 → 按格式给状态码。
     *
     * <p>状态码取自**库里的**格式（而不是本地变量）：两者在正常情况下一致，
     * 而真出现不一致时（例如两次调用之间有人改了库），按库里那一份给才是与
     * 处理这次请求的那个面自洽的。
     */
    private ResponseEntity<Void> rename(String prefix, ResourceType resourceType, JsonNode body) {
        CatalogFormat requested = formatResolver.registeredFormatOf(prefix)
                .orElseGet(() -> CatalogFormatResolver.fromRenameBody(body));
        CatalogEntity catalog = catalogService.resolveOrCreate(prefix, requested);
        CatalogFormat format = catalogService.formatOf(catalog);
        protocols.of(format).rename(catalog, resourceType, body);
        return format == CatalogFormat.ICEBERG
                ? ResponseEntity.noContent().build()
                : ResponseEntity.ok().build();
    }
}
