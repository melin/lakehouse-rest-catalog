package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.support.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * GCS 的 {@code CredentialAccessBoundary}：把一次令牌交换的结果收窄到这张表。
 *
 * <p>与 S3 的 policy、OSS 的 policy 是同一件事的第三种写法。GCS 这条有三处
 * 必须照文档来、写错就静默失效的地方：
 *
 * <ol>
 *   <li><b>{@code availableResource} 必须写到「对象」这一层</b>，形如
 *       {@code //storage.googleapis.com/projects/_/buckets/<bucket>/objects/<prefix>*}。
 *       写到桶（{@code .../buckets/<bucket>}）会被 STS 直接拒（
 *       {@code INVALID_ATTRIBUTE}）；写成 HTTP URL 也不行——它要的是**协议相对**的形式，
 *       以 {@code //} 开头，没有 {@code https:}。</li>
 *   <li><b>{@code availablePermissions} 用的是「角色名」而不是动作名</b>：
 *       {@code inRole:roles/storage.objectViewer}。写成 {@code storage.objects.get}
 *       会得到一个不认识的权限、请求被拒。</li>
 *   <li><b>规则上限 10 条</b>。超过时 STS 拒绝整份 boundary，而报错只说规则数超限；
 *       与其让它失败，不如在这里拒绝签发并说清楚——凭据下发退回转发档仍然能读表。</li>
 * </ol>
 *
 * <p><b>写权限用两个角色而不是 {@code objectAdmin}。</b>{@code objectAdmin} 含
 * {@code objects.delete} 与 {@code objects.update}，也含 ACL 管理；而「往表目录里
 * 写数据文件」需要的只是「创建对象」。给多了不会报错，只会让一张表的票能删掉
 * 同桶其它位置的对象（如果 boundary 之外还有别的规则在起作用的话）。
 */
public final class GcsBoundary {

    /** STS 允许的规则数上限。公开：调用方要能提前判断「这次位置太多、干脆别签」。 */
    public static final int MAX_RULES = 10;

    private GcsBoundary() {
    }

    /**
     * 生成 {@code accessBoundary} 的 JSON 文本。
     *
     * <p>返回值是一个**字符串**而不是对象：GCS 的 token exchange 接口里
     * {@code options} 字段要求「JSON 序列化后的字符串」。把对象直接塞进去，
     * 服务端会回 {@code Invalid JSON payload}——而错误信息里看不出是「多了一层还是少了一层」。
     *
     * @param scopes     作用域；超过 {@link #MAX_RULES} 条时抛出
     * @param allowWrite 是否要写权限
     */
    public static String json(List<CredentialScope> scopes, boolean allowWrite) {
        List<CredentialScope> effective = scopes == null ? List.of() : List.copyOf(scopes);
        if (effective.size() > MAX_RULES) {
            throw new IllegalArgumentException("a GCS credential access boundary takes at most "
                    + MAX_RULES + " rules, got " + effective.size());
        }
        List<Map<String, Object>> rules = new ArrayList<>();
        for (CredentialScope scope : effective) {
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("availableResource", resource(scope));
            rule.put("availablePermissions", permissions(allowWrite));
            rules.add(rule);
        }
        Map<String, Object> boundary = new LinkedHashMap<>();
        boundary.put("accessBoundaryRules", rules);
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("accessBoundary", boundary);
        return Json.write(wrapper);
    }

    /**
     * 一条规则能碰的对象集合。
     *
     * <p>前缀为空（桶根）时写成 {@code objects/*}：这仍然表达在对象层，
     * 只是范围是整桶——不能省略这一段去写桶级资源，那会被 STS 拒。
     */
    static String resource(CredentialScope scope) {
        String prefix = scope.readPrefixes().isEmpty() ? "" : scope.readPrefixes().get(0);
        return "//storage.googleapis.com/projects/_/buckets/" + scope.bucket()
                + "/objects/" + prefix + "*";
    }

    /**
     * 权限清单。
     *
     * <p>读只要 {@code objectViewer}（含 list）；写再加 {@code objectCreator}。
     * 顺序固定，便于断言与阅读。
     */
    static List<String> permissions(boolean allowWrite) {
        LinkedHashSet<String> permissions = new LinkedHashSet<>();
        permissions.add("inRole:roles/storage.objectViewer");
        if (allowWrite) {
            permissions.add("inRole:roles/storage.objectCreator");
        }
        return List.copyOf(permissions);
    }
}
