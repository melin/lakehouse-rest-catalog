package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.domain.repo.CatalogRepository;
import io.github.melin.lakehouse.rest.support.CatalogFormat;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 判断「这个请求属于哪个协议面」，用于选错误响应的形状。
 *
 * <p><b>为什么错误处理需要它。</b>两个协议面的错误报文形状不同（Paimon 扁平、
 * Iceberg 嵌套一层，见 {@link io.github.melin.lakehouse.rest.support.IcebergErrors}），
 * 而 {@code GlobalExceptionHandler} 是全局的、拿不到控制器意图。报文形状选错的后果很具体：
 * Spark 的 Iceberg 客户端拿不到 {@code error} 字段，只会报一句
 * 「Unable to process: …」，看不出真正的原因。
 *
 * <p><b>判据的次序：路径形状优先，catalog 格式兜底。</b>两个规格选了不同的资源名词
 * （Paimon 用 {@code databases}、Iceberg 用 {@code namespaces}）与各自的独有路径，
 * 因此绝大多数请求**只看路径就能定**，不必查库。只有四条路径需要查：
 * {@code tables/rename}、{@code views/rename}（两边同路径、请求体不同）、
 * {@code /tables}、{@code /v1/config}。前两条按 prefix 查 catalog 的格式，
 * 后两条同理——查不到就按 Paimon 解释，那是本特性引入前的行为，不会让既有请求变形。
 *
 * <p><b>{@link #of} 刻意不看请求体。</b>设计文档提到「兜底按请求体字段名分流」
 * （出现 {@code object} 则 Paimon、出现 {@code namespace} 则 Iceberg）。
 * 那个判据是给**业务分派**用的，放在选错误形状这条路上不合适：异常处理器不该为了
 * 挑错误形状去解析已经失败过一次的请求体（{@code HttpMessageNotReadableException}
 * 正是最常见的失败之一）。错误形状按路径定，业务分派按 prefix + 请求体定，
 * 两者不冲突——请求体那一层判据落在 {@link #fromRenameBody}，它只服务
 * 重命名那两条同路径端点。
 *
 * <p><b>查库失败不抛出。</b>这是错误处理路径上的代码，它自己抛异常会把「404」变成
 * 「500 且没有原始信息」。取不到就当 Paimon——那是本特性引入前的唯一形状。
 */
@Service
@RequiredArgsConstructor
public class CatalogFormatResolver {

    private static final Logger log = LoggerFactory.getLogger(CatalogFormatResolver.class);

    private static final String V1 = "/v1/";

    /**
     * Lance 面的基址。
     *
     * <p>它**不是** {@code /v1/} 的子路径，因此必须单独判——而且要在
     * {@code /v1/} 那套判据之前：{@code /lance/v1/...} 不以 {@code /v1/} 开头，
     * 落进下面那套逻辑会被当成「管理 API」而按 Paimon 形状回错误。
     *
     * <p>为什么 Lance 面不挂 {@code /v1/{prefix}}：它**没有 config 握手**，客户端只配
     * {@code uri} 与 {@code delimiter}，{@code id} 的第一级才是 catalog。三条理由与
     * 选型的取舍见 {@code docs/lance-rest-catalog-design.md} §4.1。
     */
    private static final String LANCE_V1 = "/lance/v1/";

    private final CatalogRepository catalogRepository;

    /**
     * 这个请求属于哪个协议面。
     *
     * @param request 当前请求；为 {@code null} 时按默认格式（Paimon）
     */
    public CatalogFormat of(HttpServletRequest request) {
        if (request == null) {
            return CatalogFormat.DEFAULT;
        }
        String path = request.getRequestURI();
        if (path != null && path.startsWith(LANCE_V1)) {
            return CatalogFormat.LANCE;
        }
        if (path == null || !path.startsWith(V1)) {
            // 管理 API / 控制台扩展端点：它们有自己的形状，与协议面无关
            return CatalogFormat.DEFAULT;
        }
        String remainder = path.substring(V1.length());
        if (remainder.isEmpty()) {
            return CatalogFormat.DEFAULT;
        }
        int slash = remainder.indexOf('/');
        String prefix = slash < 0 ? remainder : remainder.substring(0, slash);
        String rest = slash < 0 ? "" : remainder.substring(slash + 1);

        if (prefix.isEmpty()) {
            return CatalogFormat.DEFAULT;
        }
        if (isIcebergOnly(rest)) {
            return CatalogFormat.ICEBERG;
        }
        if (isPaimonOnly(rest)) {
            return CatalogFormat.DEFAULT;
        }
        // 同路径上的四条：/v1/config（用 ?warehouse= 当 prefix）、/tables、/tables/rename、
        // /views/rename。前者的 prefix 在查询串里，其余在路径里。
        String lookup = isConfig(prefix, rest) ? request.getParameter("warehouse") : prefix;
        return formatOf(lookup, CatalogFormat.DEFAULT);
    }

    /**
     * 按 prefix 查 catalog 的格式。
     *
     * @param prefix   catalog 的对外标识
     * @param fallback 查不到（未登记、或库不可用）时的返回值
     */
    public CatalogFormat formatOf(String prefix, CatalogFormat fallback) {
        return registeredFormatOf(prefix).orElse(fallback);
    }

    /**
     * 按 prefix 查**已登记** catalog 的格式；未登记时返回空。
     *
     * <p>与 {@link #formatOf} 的差别是「查不到时给不出答案」与「猜一个」。
     * 需要这个区别的地方是重命名分派：那里未登记时的下一步是**看请求体**
     * （见 {@link #fromRenameBody}），而不是回落到 Paimon。
     *
     * <p>它**不触发自动登记**：判断格式这件事不该有副作用，
     * 而且自动登记要按判定出的格式建（见 {@code CatalogService#resolveOrCreate}）——
     * 那必须发生在判定之后。
     */
    public Optional<CatalogFormat> registeredFormatOf(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return Optional.empty();
        }
        try {
            return catalogRepository.findByPrefix(prefix)
                    .map(catalog -> CatalogFormat.of(catalog.getCatalogFormat()));
        } catch (RuntimeException e) {
            log.debug("could not resolve the format of prefix {}; treating it as unregistered", prefix, e);
            return Optional.empty();
        }
    }

    /**
     * 由重命名请求体的字段名判断它属于哪个面——**最后一道兜底**。
     *
     * <p>只在 prefix 未登记时用到（登记过的 catalog 只有一个格式，直接查库就是答案）。
     * 判据是两边的标识符形状不同，而且是**互斥**的：
     *
     * <table border="1">
     *   <caption>同一个 rename 报文，两种形状</caption>
     *   <tr><th></th><th>标识符字段</th></tr>
     *   <tr><td>Paimon</td><td>{@code {database, object}}——扁平的两个字符串</td></tr>
     *   <tr><td>Iceberg</td><td>{@code {namespace: [...], name}}——namespace 是**数组**</td></tr>
     * </table>
     *
     * <p>判不出（报文残缺、两个特征都没有）时按 Paimon——那是本特性引入前的唯一
     * 选择，与 {@link #of} 的兜底方向一致。这也意味着一个残缺的 Iceberg 报文会走
     * Paimon 的绑定、在那里以 400 失败，而不是在分派层变成一个 500。
     */
    public static CatalogFormat fromRenameBody(JsonNode body) {
        JsonNode identifier = body == null ? null : body.path("source");
        if (identifier != null && identifier.isObject()) {
            if (identifier.path("namespace").isArray()) {
                return CatalogFormat.ICEBERG;
            }
            if (identifier.has("object") || identifier.has("database")) {
                return CatalogFormat.PAIMON;
            }
        }
        return CatalogFormat.DEFAULT;
    }

    /** Iceberg 独有的第一段路径。 */
    private static boolean isIcebergOnly(String rest) {
        return firstSegmentIs(rest, "namespaces", "transactions");
    }

    /** Paimon 独有的第一段路径。 */
    private static boolean isPaimonOnly(String rest) {
        return firstSegmentIs(rest, "databases", "permissions");
    }

    /**
     * {@code rest} 的第一段是否就是其中之一。
     *
     * <p><b>必须把「只有这一段」与「后面还有别的段」都算上。</b>集合操作
     * （{@code GET /v1/{prefix}/databases}）与单个资源（{@code /v1/{prefix}/databases/x}）
     * 的第一段是同一个词，只匹配 {@code startsWith("databases/")} 会漏掉前者。
     * 漏掉的后果不是「少判一条」，而是**判给另一个面**：漏判之后会回落到按 catalog
     * 格式查，于是一个 Iceberg catalog 的 prefix 上敲 {@code /v1/{prefix}/databases}
     * 会拿到 Iceberg 形状的错误报文——而发这个请求的客户端只会按 Paimon 的形状解析，
     * 它看到的是一句没有 message 的「Unable to process」。这正是本类存在的理由的反面。
     */
    private static boolean firstSegmentIs(String rest, String... candidates) {
        int slash = rest.indexOf('/');
        String head = slash < 0 ? rest : rest.substring(0, slash);
        for (String candidate : candidates) {
            if (candidate.equals(head)) {
                return true;
            }
        }
        return false;
    }

    /** {@code /v1/config}：前缀在查询串里，路径里的那一段固定是 {@code config}。 */
    private static boolean isConfig(String prefix, String rest) {
        return rest.isEmpty() && "config".equals(prefix);
    }
}
