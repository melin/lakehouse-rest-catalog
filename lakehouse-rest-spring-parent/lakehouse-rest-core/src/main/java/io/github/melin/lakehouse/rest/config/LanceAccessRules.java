package io.github.melin.lakehouse.rest.config;

import io.github.melin.lakehouse.rest.dto.PermissionDtos.Access;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.ResourceType;
import java.util.ArrayList;
import java.util.List;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Lance catalog API（{@code /lance/v1/**}）端点 → 所需 access 的映射表。
 *
 * <p>与 {@link CatalogAccessRules} 并列而不是合并进它：两个基址下的路径形状完全不同
 * （那边是 {@code /v1/{prefix}/databases...}，这边是 {@code /lance/v1/namespace/{id}/...}，
 * 而且这条 {@code {id}} 是**用分隔符拼起来的多级标识**）。合进一张表就得先判基址再判形状，
 * 而「漏判」的后果在两边都是静默放行。
 *
 * <p><b>失败关闭。</b>授权开启时，{@code /lance/v1/**} 下未登记的路径返回 403。
 * 这一面**没有** {@code isPublic} 豁免：Lance 没有 {@code /v1/config} 那样的连接前握手，
 * 因此也没有「还没换到凭据就必须可达」的端点。
 *
 * <p><b>未实现的 operation 也要登记。</b>48 个 operation 里本服务端只实现 13 个，
 * 其余由控制器回 {@code 406 + code 0}（规范里「本实现不支持」的表达）。它们仍然要
 * 在表里有一条：否则调用者拿到的是「没有授权规则」的 403，而 403 会被理解成
 * 「我没有权限」，进而去申请一个永远用不上的授权——真正的事实是**这个能力不存在**。
 * 给这一类的是 {@code CATALOG:DESCRIBE}：能看见这个 catalog 的人就足以得到
 * 「本服务端不提供该 operation」这个答案，它不是资源访问。
 *
 * <p><b>{@code delimiter}</b>：{@code id} 的分隔符默认是 {@code $}，但客户端可以换
 * （{@code ?delimiter=::}），因此它由调用方从 query 里取来传进来，本类不假设。
 */
public final class LanceAccessRules {

    /** Lance catalog API 的路径前缀。 */
    private static final String BASE = "/lance/v1/";

    /** id 的默认分隔符；客户端可用 {@code ?delimiter=} 覆盖。 */
    public static final String DEFAULT_DELIMITER = "$";

    private LanceAccessRules() {
    }

    /**
     * 解析端点所需的 access。
     *
     * @param requestUri 请求路径（不含查询串）
     * @param method     HTTP 方法
     * @param delimiter  {@code id} 的分隔符；为空时按 {@link #DEFAULT_DELIMITER}
     * @return 授权要求；不是 Lance 路径、或形态不认识时返回 {@code null}（→ 403）
     */
    public static CatalogAccessRules.Requirement resolve(String requestUri, String method, String delimiter) {
        if (requestUri == null || !requestUri.startsWith(BASE)) {
            return null;
        }
        String remainder = requestUri.substring(BASE.length());
        if (remainder.isEmpty()) {
            return null;
        }
        String[] raw = remainder.split("/");
        List<String> segments = new ArrayList<>();
        for (String segment : raw) {
            if (!segment.isEmpty()) {
                // getRequestURI() 拿到的是**编码形式**（`$` 是 `%24`）。不解码的话
                // catalog 名会变成 "cat%24sales"，而库里没有这个 catalog ——
                // 授权层因此「查不到 catalog」，判权被静默跳过（AuthorizationService
                // 对未登记 catalog 放行），整个面形同不设防。
                segments.add(decode(segment));
            }
        }
        if (segments.size() < 3) {
            // 至少要是 namespace/{id}/{op} 或 table/{id}/{op}
            return null;
        }
        String head = segments.get(0).toLowerCase(Locale.ROOT);
        String operation = segments.get(segments.size() - 1).toLowerCase(Locale.ROOT);
        if (!"namespace".equals(head) && !"table".equals(head)) {
            return null;
        }
        List<String> levels = splitId(segments.get(1), delimiter);
        if (levels.isEmpty()) {
            return null;
        }
        String catalog = levels.get(0);
        if ("table".equals(head)) {
            return table(catalog, levels, segments, operation);
        }
        return namespace(catalog, levels, segments, operation);
    }

    // ------------------------------------------------------------------ namespace

    private static CatalogAccessRules.Requirement namespace(String catalog, List<String> levels,
                                                            List<String> segments, String operation) {
        // {catalog}$schema 下的表列举：namespace/{id}/table/list
        if (segments.size() == 4 && "table".equals(segments.get(2)) && "list".equals(operation)) {
            return at(catalog, ResourceType.DATABASE_ALL, tail(levels), null, Access.LIST);
        }
        return switch (operation) {
            case "create" -> levels.size() == 1
                    // 建 catalog 本身：由控制器回 406（决策 D2），授权按 catalog 级管理动作判
                    ? at(catalog, ResourceType.CATALOG, List.of(), null, Access.CREATEDATABASE)
                    : at(catalog, ResourceType.CATALOG, tail(levels), null, Access.CREATEDATABASE);
            case "list" -> levels.size() == 1
                    ? at(catalog, ResourceType.CATALOG_ALL, List.of(), null, Access.LIST)
                    : at(catalog, ResourceType.DATABASE_ALL, tail(levels), null, Access.LIST);
            case "describe", "exists" -> levels.size() == 1
                    ? at(catalog, ResourceType.CATALOG, List.of(), null, Access.DESCRIBE)
                    : at(catalog, ResourceType.DATABASE, tail(levels), null, Access.DESCRIBE);
            case "drop" -> levels.size() == 1
                    ? at(catalog, ResourceType.CATALOG, List.of(), null, Access.DROP)
                    : at(catalog, ResourceType.DATABASE, tail(levels), null, Access.DROP);
            // 其余 namespace operation（规范里没有的那几个形态）→ 未实现，只需看得见 catalog
            default -> at(catalog, ResourceType.CATALOG, List.of(), null, Access.DESCRIBE);
        };
    }

    // ------------------------------------------------------------------ table

    private static CatalogAccessRules.Requirement table(String catalog, List<String> levels,
                                                        List<String> segments, String operation) {
        // GET /lance/v1/table：跨命名空间列举，控制器强制要求带 catalog 前缀
        if (segments.size() == 1) {
            return at(catalog, ResourceType.CATALOG_ALL, List.of(), null, Access.LIST);
        }
        if (levels.size() < 3) {
            // 表级 operation 的 id 必须是 cat$schema$table
            return at(catalog, ResourceType.CATALOG, List.of(), null, Access.DESCRIBE);
        }
        List<String> namespace = List.of(levels.get(1));
        String table = levels.get(2);
        return switch (operation) {
            case "describe", "exists" -> at(catalog, ResourceType.TABLE, namespace, table, Access.DESCRIBE);
            case "declare", "register" -> at(catalog, ResourceType.DATABASE, namespace, null, Access.CREATETABLE);
            case "deregister" -> at(catalog, ResourceType.TABLE, namespace, table, Access.DROP);
            // rename 还要在目标库上有 CREATETABLE，由控制器补判（目标名在请求体里）
            case "rename" -> at(catalog, ResourceType.TABLE, namespace, table, Access.ALTER);
            default -> at(catalog, ResourceType.CATALOG, List.of(), null, Access.DESCRIBE);
        };
    }

    // ------------------------------------------------------------------ 工具

    private static CatalogAccessRules.Requirement at(String catalog, ResourceType resourceType,
                                                      List<String> namespace, String objectName, Access access) {
        return new CatalogAccessRules.Requirement(catalog, resourceType, namespace, objectName, access);
    }

    /** {@code levels} 去掉第一级（catalog）之后的命名空间层级。 */
    private static List<String> tail(List<String> levels) {
        return levels.size() <= 1 ? List.of() : levels.subList(1, levels.size());
    }

    /**
     * 切分 {@code id}。
     *
     * <p>这里的切分只服务于「取出 catalog 名与层级」，不做合法性校验——校验在
     * 协议面的 {@code LanceIds} 里，那里才会给出 {@code InvalidInput}。
     * 取不到 catalog 就返回空列表，调用方按未登记处理（403）。
     */
    private static List<String> splitId(String id, String delimiter) {
        if (id == null || id.isEmpty()) {
            return List.of();
        }
        String separator = delimiter == null || delimiter.isEmpty() ? DEFAULT_DELIMITER : delimiter;
        List<String> levels = new ArrayList<>();
        for (String level : id.split(java.util.regex.Pattern.quote(separator), -1)) {
            if (!level.isEmpty()) {
                levels.add(level);
            }
        }
        return levels;
    }

    /** URL 解码一段路径；非法转义序列原样返回（随后按「不认识的路径」处理）。 */
    private static String decode(String segment) {
        try {
            return URLDecoder.decode(segment, StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            return segment;
        }
    }
}
